package egovframework.unstructured.collector.voice.perf;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import egovframework.unstructured.collector.voice.batch.BatchProgress;
import egovframework.unstructured.collector.voice.batch.ResumeMode;
import egovframework.unstructured.collector.voice.batch.VerificationService;
import egovframework.unstructured.collector.voice.batch.VoiceBatchResult;
import egovframework.unstructured.collector.voice.batch.VoiceCollectService;
import egovframework.unstructured.collector.voice.batch.Workers;
import egovframework.unstructured.collector.common.broker.XvarmBrokerClient;
import egovframework.unstructured.collector.common.config.DeployEnvPreset;
import egovframework.unstructured.collector.common.config.MockDatasetState;
import egovframework.unstructured.collector.common.config.SourcePoolPeak;
import egovframework.unstructured.collector.common.config.VoiceDirState;
import egovframework.unstructured.collector.common.config.VoiceProperties;
import egovframework.unstructured.collector.voice.controller.VoiceMockController;
import egovframework.unstructured.collector.common.decrypt.DecryptService;
import egovframework.unstructured.collector.common.model.BatchWindow;
import egovframework.unstructured.collector.common.model.FileProcOutcome;
import egovframework.unstructured.collector.common.model.ProcStatus;
import egovframework.unstructured.collector.voice.source.BoramiSourceClient;
import egovframework.unstructured.collector.common.config.DbKindDetector;
import egovframework.unstructured.collector.voice.source.SimulationDataService;
import egovframework.unstructured.collector.voice.stt.MockSttLatency;
import egovframework.unstructured.collector.voice.stt.SttClient;
import egovframework.unstructured.collector.common.sync.PhoneFileProvider;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 성능 시험 — <b>실제 Java 파이프라인</b>을 N건으로 돌려 처리량·시간·자원을 잰다. STT 만 MOCK(가상 지연)이다.
 *
 * <p>두 가지를 돈다. 둘은 같은 엔진(준비 → 측정 → 검증 → 정리)을 쓰고, 한 번에 하나만 돈다.</p>
 * <ul>
 *   <li><b>기본 부하 검증</b>(4번 탭) — 정한 워커(XVARM 확보 · STT 처리)로 한 회차</li>
 *   <li><b>임계 성능 시험</b>(5번 탭) — 한쪽 워커를 단계마다 늘려 같은 건수를 처리하며 포화 지점을 찾는다.
 *       최저 응답 뒤 연속으로 개선이 없거나, XVARM 확보 대기가 한도를 넘거나, STT 에러가 나면 멈춘다</li>
 * </ul>
 *
 * <p><b>한 회차(한 단계)의 순서</b></p>
 * <ol>
 *   <li><b>준비</b> — 로컬 산출물(멱등 표식·수신 파일·보존물)을 비우고, 원천 DB 에 SIM 데이터를 <b>어제 하루</b>에
 *       만든다(접견·전화 건수, 전화 중 기 STT 비율). 준비 시간은 측정에 넣지 않는다</li>
 *   <li><b>측정</b> — {@code [어제 00:00, 오늘 00:00)} 를 수동 배치({@code TEST_BATCH} · {@code MANUAL} ·
 *       실행 주체 {@code PERF})로 워커 N개가 처리한다. 단계별 시간·힙·원천 풀 최고 연결 수를 함께 잰다</li>
 *   <li><b>검증</b> — 로그 컬렉터에서 되읽어 T2 에 RUNNING 이 남지 않았는지, 단계 행이 겹치지 않았는지,
 *       T4 행 수가 처리 건수와 같은지 본다</li>
 *   <li><b>정리</b> — 공용 DB 의 SIM 행과 원본 더미 파일을 <b>지운다</b>. 로그(TST)와 STT 출력은 남겨
 *       [상세 검증]으로 볼 수 있게 한다(다음 회차 준비 때 출력 폴더는 지운다)</li>
 * </ol>
 *
 * <p>결과는 {@code {ROOT}/perf/history.jsonl}(기본 부하) · {@code ramp-history.jsonl}(임계) 에 <b>마지막 실행 한 건만</b>
 * 덮어쓴다(2026-10-01 — 누적하면 PV 가 끝없이 커진다). PV 라 파드를 다시 띄워도 남고, 여러 사람이 같은 결과를 본다.</p>
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class PerfRunService {

    private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String HISTORY_FILE = "history.jsonl";
    private static final String RAMP_HISTORY_FILE = "ramp-history.jsonl";
    /** 화면이 읽는 이력 최대 건수 — 파일은 자르지 않는다. */
    private static final int HISTORY_READ_MAX = 300;
    /** 임계 시험 도중 조기 종료 조건을 보는 간격. */
    private static final long MONITOR_MS = 500L;

    public enum Kind { BASIC, RAMP }

    public enum Phase {
        PREPARING("준비"), RUNNING("측정"), VERIFYING("검증"), CLEANING("정리"), DONE("완료"), FAILED("실패");

        private final String label;

        Phase(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        boolean active() {
            return this != DONE && this != FAILED;
        }
    }

    private final VoiceCollectService collect;
    private final BatchProgress progress;
    /** admin 연동 실행기 — 스케줄 · 바로 실행 · 재처리가 돌고 있으면 시험을 시작하지 않는다. */
    private final egovframework.unstructured.collector.batch.UnstructuredJobRunner scheduler;
    private final SimulationDataService sim;
    private final MockDatasetState dataset;
    private final egovframework.unstructured.collector.common.transfer.ZenonClient zenon;
    private final VerificationService verification;
    private final MockSttLatency latency;
    private final PerfStageMeter meter;
    private final SourcePoolPeak poolPeak;
    private final VoiceDirState dirs;
    private final VoiceProperties props;
    private final DeployEnvPreset deployEnv;
    private final DbKindDetector dbKind;
    private final BoramiSourceClient source;
    private final XvarmBrokerClient broker;
    private final PhoneFileProvider phoneFileProvider;
    private final DecryptService decryptService;
    private final SttClient sttClient;
    private final ObjectMapper objectMapper;
    /** 로컬 산출물 정리를 [시뮬레이션 데이터 생성]과 같은 코드로 한다. */
    private final VoiceMockController mock;

    private final ExecutorService runner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "perf-runner");
        t.setDaemon(true);
        return t;
    });

    private volatile Run current;

    /** 건당 STT 처리 시간 — 두 요청이 같은 모양으로 넘긴다. {@code realSleep} 이 아니면 고속 모드다. */
    private record Load(MockSttLatency.Mode mode, long meetMs, long phoneMs, int jitterPercent, Long timeoutMs,
                        boolean realSleep) {

        static Load of(PerfRequest r) {
            return new Load(r.mode(), r.meetLatencyMs(), r.phoneLatencyMs(), r.jitterPercent(), r.sttTimeoutMs(),
                    Boolean.TRUE.equals(r.realSleep()));
        }

        static Load of(RampRequest r) {
            return new Load(r.mode(), r.meetLatencyMs(), r.phoneLatencyMs(), r.jitterPercent(), r.sttTimeoutMs(),
                    Boolean.TRUE.equals(r.realSleep()));
        }

        Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("mode", mode.name());
            m.put("meetMs", meetMs);
            m.put("phoneMs", phoneMs);
            m.put("jitterPercent", jitterPercent);
            m.put("realSleep", realSleep);
            return m;
        }
    }

    /** 준비 단계가 만든 것. */
    private record Prepared(Map<String, Object> seed, Map<String, Object> cleared, int oldOutputs, long prepareMs) {}

    /** 측정 한 번의 결과 — 배치 결과와 그동안의 자원·단계 시간. */
    private record Measured(VoiceBatchResult result, long heapStart, long heapEnd, long heapPeak,
                            List<Map<String, Object>> stages, Map<String, Object> hikari,
                            double acquireMaxMs, long sttErrors, String earlyStop,
                            boolean fastForward, Workers workers, List<Long> virtualSttMs,
                            PerfStageMeter.Pipeline pipeline) {}

    /** 한 회차의 진행 상태 — 러너 스레드가 쓰고 API 스레드가 읽는다. */
    private static final class Run {
        final String id;
        final Kind kind;
        final Object req;
        final long startedAt = System.currentTimeMillis();
        volatile Phase phase = Phase.PREPARING;
        volatile String message = "준비 중…";
        volatile long finishedAt;
        volatile Map<String, Object> result;
        volatile String error;
        volatile boolean cancelRequested;
        // 임계 시험 — 단계가 끝날 때마다 쌓인다(화면이 실시간으로 그린다)
        final List<Map<String, Object>> steps = new CopyOnWriteArrayList<>();
        volatile List<Integer> plan = List.of();
        volatile int stepNo;
        volatile int workers;
        volatile int bestStep;
        volatile String stopReason;

        Run(String id, Kind kind, Object req) {
            this.id = id;
            this.kind = kind;
            this.req = req;
        }

        void to(Phase p, String msg) {
            this.phase = p;
            this.message = msg;
            log.info("[Perf] {} {} — {}", id, p.label(), msg);
        }
    }

    // ── 시작 · 중단 · 상태 ──────────────────────────────────────────────────

    /**
     * 기본 부하 검증 한 회차를 시작한다 — 비동기. 바로 돌아오고, 진행은 {@link #current()} 로 본다.
     *
     * @throws IllegalArgumentException 조건이 범위를 벗어났다(400)
     * @throws IllegalStateException    이미 성능 시험이나 배치가 돌고 있다(409)
     */
    public synchronized Map<String, Object> start(PerfRequest raw) {
        PerfRequest req = (raw == null ? new PerfRequest(null, null, null, null, null, null, null, null, null, null, null, null) : raw)
                .withDefaults();
        req.validate(props.batch().maxFilesPerRun());
        ensureIdle();
        Run run = new Run(LocalDateTime.now().format(RUN_ID), Kind.BASIC, req);
        current = run;
        runner.submit(() -> executeBasic(run));
        return snapshot(run);
    }

    /** 임계 성능 시험(워커 램프업)을 시작한다 — 비동기. */
    public synchronized Map<String, Object> startRamp(RampRequest raw) {
        RampRequest req = (raw == null ? new RampRequest(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null) : raw).withDefaults();
        req.validate(props.batch().maxFilesPerRun());
        ensureIdle();
        Run run = new Run(LocalDateTime.now().format(RUN_ID), Kind.RAMP, req);
        run.plan = List.copyOf(req.plan());
        current = run;
        runner.submit(() -> executeRamp(run));
        return snapshot(run);
    }

    private void ensureIdle() {
        Run running = current;
        if (running != null && running.phase.active()) {
            throw new IllegalStateException("성능 시험이 이미 돌고 있습니다 — " + running.id + " ("
                    + (running.kind == Kind.RAMP ? "임계 성능" : "기본 부하") + " · " + running.phase.label() + ")");
        }
        if (progress.isRunning() || scheduler.isRunning()) {
            throw new IllegalStateException("다른 배치가 실행 중입니다 — 끝난 뒤 다시 시작하십시오");
        }
    }

    /** 중단 — 준비 중이면 측정을 건너뛰고, 측정 중이면 처리 중인 건만 끝내고 멈춘다. */
    public Map<String, Object> cancel() {
        Run run = current;
        Map<String, Object> out = new LinkedHashMap<>();
        if (run == null || !run.phase.active()) {
            out.put("accepted", false);
            out.put("message", "돌고 있는 성능 시험이 없습니다");
            return out;
        }
        run.cancelRequested = true;
        boolean batch = progress.cancel();
        out.put("accepted", true);
        out.put("message", batch ? "중단 요청 — 처리 중인 건을 끝내고 멈춥니다" : "중단 요청 — 측정 전에 멈춥니다");
        out.putAll(snapshot(run));
        return out;
    }

    public boolean isActive() {
        Run run = current;
        return run != null && run.phase.active();
    }

    /** 지금(또는 마지막) 회차. 한 번도 안 돌았으면 {@code phase=null}. */
    public Map<String, Object> current() {
        Run run = current;
        if (run == null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("runId", null);
            m.put("phase", null);
            m.put("active", false);
            return m;
        }
        return snapshot(run);
    }

    private Map<String, Object> snapshot(Run run) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", run.id);
        m.put("kind", run.kind.name());
        m.put("phase", run.phase.name());
        m.put("phaseLabel", run.phase.label());
        m.put("active", run.phase.active());
        m.put("message", run.message);
        m.put("request", run.req);
        m.put("elapsedMs", (run.finishedAt == 0 ? System.currentTimeMillis() : run.finishedAt) - run.startedAt);
        m.put("cancelRequested", run.cancelRequested);
        // 측정 뒤(검증·정리)에도 마지막 진행률을 싣는다 — 화면이 측정 마지막 폴링을 놓치면 14/20 에서 멈춰 보인다.
        if (run.phase == Phase.RUNNING || run.phase == Phase.VERIFYING || run.phase == Phase.CLEANING) {
            m.put("progress", progress.snapshot());
        }
        if (run.kind == Kind.RAMP) {
            m.put("plan", run.plan);
            m.put("rampTarget", ((RampRequest) run.req).rampTarget());
            m.put("stepNo", run.stepNo);
            m.put("workers", run.workers);
            m.put("steps", List.copyOf(run.steps));
            m.put("bestStep", run.bestStep);
            m.put("stopReason", run.stopReason);
            if (run.phase == Phase.RUNNING && meter.isActive()) {
                // 지금 단계의 XVARM 확보 대기 — 한도에 얼마나 다가갔는지 화면이 본다
                m.put("liveAcquireMs", Math.round(Math.max(meter.oldestInFlightMs(PerfStage.ACQUIRE),
                        meter.maxMs(PerfStage.ACQUIRE))));
                m.put("liveSttErrors", meter.errors(PerfStage.STT));
            }
        }
        m.put("result", run.result);
        m.put("error", run.error);
        return m;
    }

    // ── 기본 부하 검증 ───────────────────────────────────────────────────────

    private void executeBasic(Run run) {
        PerfRequest req = (PerfRequest) run.req;
        Map<String, Object> result = null;
        String error = null;
        try {
            Prepared prep = prepare(run, req.meetCount(), req.phoneCount(), req.sttPercent(), true, "");
            if (run.cancelRequested) {
                throw new CanceledBeforeRun();
            }
            run.to(Phase.RUNNING, "워커 %s 로 %d건 처리 중".formatted(req.workers().describe(), req.count()));
            Measured d = measure(Load.of(req), req.workers(), null, run);
            run.to(Phase.VERIFYING, "로그 컬렉터에서 T1·T2·T4 되읽기 — " + d.result().execId());
            Map<String, Object> checks = checks(d.result());
            result = summarizeBasic(run, req, d, prep, checks);
        } catch (CanceledBeforeRun e) {
            error = "측정 전에 중단했습니다";
        } catch (Exception e) {
            error = rootMessage(e);
            log.warn("[Perf] {} 실패 — {}", run.id, error, e);
        }
        finish(run, result, error, HISTORY_FILE);
    }

    private Map<String, Object> summarizeBasic(Run run, PerfRequest req, Measured d, Prepared prep,
                                               Map<String, Object> checks) {
        VoiceBatchResult r = d.result();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", run.id);
        m.put("at", LocalDateTime.now().withNano(0).toString());
        m.put("scenario", req.scenario());
        m.put("count", req.count());
        m.put("meetCount", req.meetCount());
        m.put("phoneCount", req.phoneCount());
        m.put("sttPercent", req.sttPercent());
        m.put("sourceSttPhones", prep.seed().get("sourceSttPhones"));
        m.put("acquireWorkers", req.acquireWorkers());
        m.put("sttWorkers", req.sttWorkers());
        m.put("concurrency", req.sttWorkers());   // 옛 이력·CSV 와 같은 칸(= STT 워커)
        m.put("latency", Load.of(req).describe());
        m.put("sttTimeoutMs", req.sttTimeoutMs());
        common(m, prep);
        m.putAll(batchMetrics(d));
        m.put("checks", checks);
        m.put("prepareSec", round(prep.prepareMs() / 1000d, 1));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("idempotencyMarkers", prep.cleared().get("idempotencyMarkers"));
        p.put("sttTempFiles", prep.cleared().get("sttTempFiles"));
        p.put("oldOutputDirs", prep.oldOutputs());
        p.put("filesWritten", ((List<?>) prep.seed().getOrDefault("files", List.of())).size());
        m.put("prepare", p);
        return m;
    }

    // ── 임계 성능 시험 (워커 램프업) ──────────────────────────────────────────

    private void executeRamp(Run run) {
        RampRequest req = (RampRequest) run.req;
        List<Integer> plan = run.plan;
        String stop = null;
        String error = null;
        Prepared first = null;
        Double bestSec = null;
        int sinceBest = 0;
        try {
            for (int i = 0; i < plan.size(); i++) {
                if (run.cancelRequested) {
                    stop = "사용자가 중지했습니다";
                    break;
                }
                int w = plan.get(i);
                Workers ws = req.workersAt(w);
                run.stepNo = i + 1;
                run.workers = w;
                String tag = "단계 %d/%d · %s %d (%s)".formatted(i + 1, plan.size(), req.target().label(), w, ws.describe());
                Prepared prep = prepare(run, req.meetCount(), req.phoneCount(), req.sttPercent(), i == 0, tag + " — ");
                if (first == null) {
                    first = prep;
                }
                if (run.cancelRequested) {
                    stop = "사용자가 중지했습니다";
                    break;
                }
                run.to(Phase.RUNNING, "%s 로 %d건 처리 중".formatted(tag, req.count()));
                Measured d = measure(Load.of(req), ws, req, run);
                run.to(Phase.VERIFYING, tag + " — 로그 컬렉터 되읽기");
                Map<String, Object> checks = checks(d.result());

                Map<String, Object> step = stepRow(i + 1, w, d, checks, prep);
                step.put("rampTarget", req.rampTarget());
                VoiceBatchResult r = d.result();
                // 도중에 끊긴 단계는 건수가 달라 응답 시간을 비교하지 않는다
                if (!r.canceled()) {
                    double sec = ((Number) step.get("totalSec")).doubleValue();   // 고속 모드면 가상 STT 포함
                    if (bestSec == null || sec < bestSec) {
                        bestSec = sec;
                        sinceBest = 0;
                        run.bestStep = i + 1;
                    } else {
                        sinceBest++;
                    }
                }
                step.put("sinceBest", sinceBest);
                run.steps.add(step);

                if (d.earlyStop() != null) {
                    stop = d.earlyStop();
                    break;
                }
                if (run.cancelRequested) {
                    stop = "사용자가 중지했습니다";
                    break;
                }
                if (sinceBest >= req.patience()) {
                    Map<String, Object> best = run.steps.get(run.bestStep - 1);
                    stop = "최저 응답(%s %s · %s초) 이후 %d회 연속 개선 없음 — 포화로 판단"
                            .formatted(req.target().label(), best.get("workers"), best.get("totalSec"), sinceBest);
                    break;
                }
            }
            if (stop == null) {
                stop = "최대 %s %d 까지 모든 단계를 마쳤습니다".formatted(req.target().label(), plan.get(plan.size() - 1));
            }
        } catch (Exception e) {
            error = rootMessage(e);
            log.warn("[Perf] {} 임계 시험 실패 — {}", run.id, error, e);
        }
        run.stopReason = stop != null ? stop : error;

        Map<String, Object> result = null;
        if (!run.steps.isEmpty() && first != null) {
            result = new LinkedHashMap<>();
            result.put("runId", run.id);
            result.put("at", LocalDateTime.now().withNano(0).toString());
            result.put("request", req);
            result.put("plan", plan);
            result.put("rampTarget", req.rampTarget());
            result.put("latency", Load.of(req).describe());
            common(result, first);
            result.put("steps", List.copyOf(run.steps));
            result.put("bestStep", run.bestStep);
            if (run.bestStep > 0) {
                Map<String, Object> b = run.steps.get(run.bestStep - 1);
                Map<String, Object> best = new LinkedHashMap<>();
                best.put("step", run.bestStep);
                best.put("workers", b.get("workers"));
                best.put("totalSec", b.get("totalSec"));
                best.put("tps", b.get("tps"));
                result.put("best", best);
            }
            result.put("stopReason", stop == null ? error : stop);
        }
        finish(run, result, result == null ? (error == null ? stop : error) : null, RAMP_HISTORY_FILE);
    }

    /** 임계 시험의 표 한 줄. {@code workers} 는 이 단계에서 늘린 쪽의 워커 수(차트 가로축). */
    private Map<String, Object> stepRow(int no, int workers, Measured d, Map<String, Object> checks, Prepared prep) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("step", no);
        m.put("workers", workers);
        m.putAll(batchMetrics(d));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stages = (List<Map<String, Object>>) m.get("stages");   // 고속 모드면 STT 는 가상 시간
        m.put("acquireAvgMs", stageAvg(stages, PerfStage.ACQUIRE));
        m.put("acquireMaxMs", round(d.acquireMaxMs(), 1));
        m.put("sttAvgMs", stageAvg(stages, PerfStage.STT));
        m.put("sttErrors", d.sttErrors());
        m.put("checksOk", checks.get("available") == Boolean.TRUE ? checks.get("ok") : null);
        m.put("sourceSttPhones", prep.seed().get("sourceSttPhones"));
        m.put("earlyStop", d.earlyStop());
        return m;
    }

    private static Object stageAvg(List<Map<String, Object>> stages, PerfStage s) {
        return stages.stream().filter(x -> s.name().equals(x.get("key"))).findFirst().map(x -> x.get("avgMs")).orElse(0d);
    }

    /**
     * 조기 종료 조건 — 단계 도중에 반 초마다, 끝난 뒤에 한 번 더 본다.
     *
     * @return 걸렸으면 사유, 아니면 null
     */
    private String breach(RampRequest req) {
        if (req.acquireLimitSec() > 0) {
            double worst = Math.max(meter.oldestInFlightMs(PerfStage.ACQUIRE), meter.maxMs(PerfStage.ACQUIRE));
            if (worst > req.acquireLimitSec() * 1000d) {
                return "XVARM 확보 대기 %.1f초 — 한도 %d초 초과".formatted(worst / 1000d, req.acquireLimitSec());
            }
        }
        long errs = meter.errors(PerfStage.STT);
        if (Boolean.TRUE.equals(req.stopOnSttError()) && errs > 0) {
            return "STT 에러·타임아웃 %d건 발생".formatted(errs);
        }
        return null;
    }

    /** 단계 도중 조기 종료를 지켜보는 스레드 — 걸리면 배치를 멈추고 사유를 남긴다. */
    private final class Monitor implements AutoCloseable {
        private final Thread thread;
        private volatile String reason;

        Monitor(RampRequest req, Run run) {
            thread = new Thread(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        String why = breach(req);
                        if (why != null) {
                            reason = why;
                            run.message = "조기 종료 — " + why + " · 처리 중인 건을 끝내고 멈춥니다";
                            log.warn("[Perf] {} 조기 종료 — {}", run.id, why);
                            progress.cancel();
                            return;
                        }
                        Thread.sleep(MONITOR_MS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "perf-ramp-monitor");
            thread.setDaemon(true);
            thread.start();
        }

        String reason() {
            return reason;
        }

        @Override
        public void close() {
            thread.interrupt();
            try {
                thread.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ── 공통 엔진 ───────────────────────────────────────────────────────────

    /** 준비 — 로컬 산출물을 비우고 SIM 데이터를 만든다. 지난 시험 출력 폴더는 회차 처음에만 지운다. */
    private Prepared prepare(Run run, int meet, int phone, int sttPercent, boolean deleteOutputs, String tag) {
        long p0 = System.currentTimeMillis();
        run.to(Phase.PREPARING, tag + "로컬 산출물 정리" + (deleteOutputs ? " · 지난 시험 출력 삭제" : ""));
        Map<String, Object> cleared = mock.clearLocalFiles();
        // 결과는 PV 에 남기지 않는다(제논 전송) — 지난 시험의 제논 수신증(메모리)만 비운다
        int oldOutputs = deleteOutputs ? zenon.clearTestReceipts() : 0;
        run.to(Phase.PREPARING, "%sSIM 데이터 %d건 생성 (접견 %d · 전화 %d · 기 STT %d%%) — %s"
                .formatted(tag, meet + phone, meet, phone, sttPercent, dbKind.label()));
        Map<String, Object> seed = sim.seedPerf(meet, phone, sttPercent);
        return new Prepared(seed, cleared, oldOutputs, System.currentTimeMillis() - p0);
    }

    /**
     * 측정 — 워커 구성 {@code workers} 로 어제 하루 창을 처리하며 힙·원천 풀·단계 시간을 잰다.
     *
     * @param guard 임계 시험이면 조기 종료 조건, 기본 부하면 null
     */
    private Measured measure(Load load, Workers workers, RampRequest guard, Run run) {
        latency.apply(load.mode(), load.meetMs(), load.phoneMs(), load.jitterPercent(),
                load.timeoutMs() == null ? 0L : load.timeoutMs(), !load.realSleep());
        LocalDate today = LocalDate.now();
        BatchWindow window = BatchWindow.manual(today.minusDays(1).atStartOfDay(), today.atStartOfDay());
        List<MemoryPoolMXBean> heapPools = ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(p -> p.getType() == MemoryType.HEAP && p.isValid()).toList();
        heapPools.forEach(MemoryPoolMXBean::resetPeakUsage);
        long heapStart = heapUsed();
        poolPeak.reset();
        meter.begin();
        VoiceBatchResult r;
        String early = null;
        try (Monitor mon = guard == null ? null : new Monitor(guard, run)) {
            r = collect.run(window, null, "PERF", true, ResumeMode.FULL, null, workers);
            if (mon != null) {
                early = mon.reason();
            }
        } finally {
            meter.end();
            latency.clear();
        }
        if (guard != null && early == null) {
            early = breach(guard);   // 반 초 사이에 끝난 건이 넘었을 수 있다
        }
        // 원천 풀 — 최고 연결 수와, 배치가 끝난 지금 빌려 간 연결이 모두 돌아왔는지(누수 확인)
        Map<String, Object> hikari = new LinkedHashMap<>(poolPeak.snapshot());
        hikari.put("after", poolPeak.state());
        long heapEnd = heapUsed();
        long heapPeak = heapPools.stream().mapToLong(p -> {
            MemoryUsage u = p.getPeakUsage();
            return u == null ? 0L : u.getUsed();
        }).sum();
        return new Measured(r, heapStart, heapEnd, heapPeak, meter.snapshot(), hikari,
                meter.maxMs(PerfStage.ACQUIRE), meter.errors(PerfStage.STT), early,
                !load.realSleep(), workers, meter.virtualSttMs(), meter.pipeline());
    }

    /**
     * 배치 한 번의 처리량·결과·자원 — 기본 부하 결과와 임계 시험 표가 같이 쓴다.
     *
     * <p><b>고속 모드</b>면 STT 를 기다리지 않았으므로 건별로 적어 둔 처리 시간을 되살린다.</p>
     * <ul>
     *   <li><b>생산자-소비자</b>(확보·STT 워커를 나눠 돈 경우) — 확보는 실제로 돌았고 그 끝난 시각이 건마다 있다.
     *       그 시각에 건이 대기열에 들어왔다고 보고 STT 워커 {@code S} 개가 (실제 처리 시간 + 가상 시간)만큼 일한다고
     *       다시 짠다({@link #pipelineMs}). 확보와 STT 가 겹치므로 둘을 더하지 않는다 — 확보가 느리면 확보 구간이,
     *       STT 가 느리면 STT 가 총 소요를 정한다</li>
     *   <li><b>순차</b> — 건마다 확보 → STT 가 이어지므로 실제 소요 + 가상 시간의 합이다</li>
     *   <li>가상 STT 시간({@code virtualSttSec})은 참고치 — 건별 처리 시간을
     *       STT 워커에 나눠 준 뒤 가장 늦게 끝나는 워커의 시각({@link #distribute})</li>
     *   <li>TPS = 처리 건수(성공 + 실패) ÷ 총 소요</li>
     *   <li>평균 처리 시간 · STT 단계 평균에도 가상 시간을 넣는다 — 안 넣으면 STT 가 0ms 로 보인다.
     *       건당 처리 시간에 대기열에서 기다린 시간은 넣지 않는다</li>
     * </ul>
     */
    private static Map<String, Object> batchMetrics(Measured d) {
        VoiceBatchResult r = d.result();
        List<FileProcOutcome> done = r.outcomes().stream().filter(o -> o.status() != ProcStatus.SKIPPED).toList();
        double realSec = r.elapsedMs() / 1000d;
        List<Long> virtual = d.fastForward() ? d.virtualSttMs() : List.of();
        long virtualTotalMs = virtual.stream().mapToLong(Long::longValue).sum();
        int sttWorkers = d.workers().stt();
        double virtualSttSec = distribute(virtual, sttWorkers) / 1000d;
        PerfStageMeter.Pipeline pl = d.pipeline();
        double sec;
        if (!d.fastForward()) {
            sec = realSec;
        } else if (pl != null) {
            long loop = Math.max(0L, pl.loopMs());
            long sim = pipelineMs(pl.items(), sttWorkers, pl.acquireSpanMs());
            // 루프 밖(대상 조회 · T1/T2 개시 · T4/T1 마감)은 실제 그대로, 루프는 다시 짠 시간으로
            sec = (r.elapsedMs() - loop + Math.max(sim, loop)) / 1000d;
        } else {
            sec = realSec + virtualSttSec;
        }
        long processed = (long) r.successCnt() + r.failCnt();
        double realAvg = done.stream().mapToLong(FileProcOutcome::elapsedMs).average().orElse(0d);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("execId", r.execId());
        m.put("execStsCd", r.execStsCd());
        m.put("canceled", r.canceled());
        m.put("fastForward", d.fastForward());
        m.put("acquireWorkers", d.workers().acquire());
        m.put("sttWorkers", sttWorkers);
        m.put("pipeline", pl != null);
        m.put("realSec", round(realSec, 2));
        m.put("virtualSec", round(sec - realSec, 2));
        m.put("virtualSttSec", round(virtualSttSec, 2));
        // 확보 워커가 마지막 건을 끝낸 시각(실제) — 이것이 총 소요에 가까우면 확보가 병목이다
        m.put("acquireSpanSec", pl == null ? null : round(pl.acquireSpanMs() / 1000d, 2));
        m.put("totalSec", round(sec, 2));
        m.put("avgMs", done.isEmpty() ? 0d : round(realAvg + (double) virtualTotalMs / done.size(), 1));
        m.put("tps", sec <= 0 ? 0d : round(processed / sec, 3));
        m.put("total", r.targetCnt());
        m.put("success", r.successCnt());
        m.put("fail", r.failCnt());
        m.put("timeout", done.stream().filter(PerfRunService::isTimeout).count());
        m.put("skipped", r.skippedCnt());
        m.put("stages", d.fastForward()
                ? withVirtual(d.stages(), PerfStage.STT, virtual) : d.stages());
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("startMb", mb(d.heapStart()));
        h.put("endMb", mb(d.heapEnd()));
        h.put("peakMb", mb(d.heapPeak()));
        h.put("maxMb", mb(Runtime.getRuntime().maxMemory()));
        m.put("heap", h);
        m.put("hikari", d.hikari());
        return m;
    }

    /**
     * 생산자-소비자를 가상 시간으로 다시 짠다 — 마지막 건이 끝나는 시각(루프 시작 기준 ms).
     *
     * <p>건은 확보가 끝난 시각({@code readyMs})에 대기열에 들어오고, 가장 먼저 비는 STT 워커가 받아
     * (실제 처리 시간 + 가상 시간)만큼 일한다. 확보에서 끝난 건(실패·건너뜀)은 STT 워커를 쓰지 않지만,
     * 확보 워커가 마지막 건을 끝낸 시각({@code acquireSpanMs})보다 일찍 끝날 수는 없다.</p>
     */
    static long pipelineMs(List<PerfStageMeter.PipelineItem> items, int sttWorkers, long acquireSpanMs) {
        java.util.PriorityQueue<Long> free = new java.util.PriorityQueue<>();
        for (int i = 0; i < Math.max(1, sttWorkers); i++) {
            free.add(0L);
        }
        long end = acquireSpanMs;
        List<PerfStageMeter.PipelineItem> byReady = items.stream()
                .sorted(java.util.Comparator.comparingLong(PerfStageMeter.PipelineItem::readyMs)).toList();
        for (PerfStageMeter.PipelineItem it : byReady) {
            long start = Math.max(free.poll(), it.readyMs());
            long finish = start + it.serviceMs() + it.virtualMs();
            free.add(finish);
            end = Math.max(end, finish);
        }
        return end;
    }

    /**
     * 건별 처리 시간을 워커 {@code workers} 개에 차례로 나눠 줄 때 마지막 워커가 끝나는 시각(ms).
     * 한 건은 가장 먼저 비는 워커가 받는다 — 배치 워커 풀과 같은 방식이다.
     */
    static long distribute(List<Long> durationsMs, int workers) {
        if (durationsMs.isEmpty()) {
            return 0L;
        }
        java.util.PriorityQueue<Long> free = new java.util.PriorityQueue<>();
        for (int i = 0; i < Math.max(1, workers); i++) {
            free.add(0L);
        }
        for (long d : durationsMs) {
            free.add(free.poll() + d);
        }
        return free.stream().mapToLong(Long::longValue).max().orElse(0L);
    }

    /**
     * 고속 모드 — 그 단계의 평균·최대에 기다리지 않은 처리 시간을 더해 싣는다.
     * STT 는 실제로 0ms 라 가상 시간이 곧 처리 시간이다.
     */
    private static List<Map<String, Object>> withVirtual(List<Map<String, Object>> stages, PerfStage stage, List<Long> virtual) {
        if (virtual.isEmpty()) {
            return stages;
        }
        List<Map<String, Object>> out = new ArrayList<>(stages.size());
        for (Map<String, Object> s : stages) {
            if (!stage.name().equals(s.get("key"))) {
                out.add(s);
                continue;
            }
            Map<String, Object> v = new LinkedHashMap<>(s);
            double realAvg = stage == PerfStage.STT ? 0d : ((Number) s.getOrDefault("avgMs", 0d)).doubleValue();
            v.put("count", virtual.size());
            v.put("avgMs", round(realAvg + virtual.stream().mapToLong(Long::longValue).average().orElse(0d), 1));
            v.put("maxMs", (double) virtual.stream().mapToLong(Long::longValue).max().orElse(0L));
            v.put("virtual", true);
            out.add(v);
        }
        return out;
    }

    /** 두 결과가 같이 싣는 환경 — STT 엔진 · 모드 · DB. */
    private void common(Map<String, Object> m, Prepared prep) {
        m.put("sttMode", sttClient.mode());
        m.put("modes", modes());
        m.put("env", deployEnv.snapshot().get("kind"));
        m.put("db", prep.seed().get("db"));
    }

    /** 끝 — 공용 DB 의 SIM 행을 지우고(실패·중단이어도) 이력을 남긴다. */
    private void finish(Run run, Map<String, Object> result, String error, String historyFile) {
        run.to(Phase.CLEANING, "공용 DB 의 SIM 행 · 원본 더미 파일 삭제");
        Map<String, Object> cleanup = cleanup();
        if (result != null) {
            result.put("cleanup", cleanup);
            appendHistory(historyFile, result);
        }
        run.result = result;
        run.error = error;
        run.finishedAt = System.currentTimeMillis();
        if (error != null) {
            run.to(Phase.FAILED, error);
        } else if (run.kind == Kind.RAMP) {
            run.to(Phase.DONE, "완료 — " + result.get("stopReason"));
        } else {
            run.to(Phase.DONE, "완료 — %s · %s TPS · %s초".formatted(result.get("execStsCd"), result.get("tps"), result.get("totalSec")));
        }
    }

    /** 측정 전에 중단 요청이 들어왔다 — 정리만 하고 끝낸다. */
    private static final class CanceledBeforeRun extends RuntimeException {
        CanceledBeforeRun() {
            super(null, null, false, false);
        }
    }

    private Map<String, Object> cleanup() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Map<String, Object> c = sim.clean();
            dataset.reset();
            out.put("ok", true);
            out.put("counts", c.get("counts"));
        } catch (Exception e) {
            log.warn("[Perf] SIM 데이터 정리 실패 — {} · [시뮬레이션 데이터 초기화]로 지우십시오", e.getMessage());
            out.put("ok", false);
            out.put("error", e.getMessage());
        }
        return out;
    }

    /** STT 타임아웃 — MOCK 의 가상 타임아웃이거나, NPU 호출의 읽기 타임아웃. */
    static boolean isTimeout(FileProcOutcome o) {
        String e = o.errMsg();
        return e != null && (e.startsWith("SttTimeoutException") || e.contains("timed out"));
    }

    /**
     * 끝난 배치를 로그 컬렉터에서 되읽어 맞춰 본다.
     *
     * <ul>
     *   <li>T2 에 {@code RUNNING} 이 남지 않았는가 — 타임아웃·동시 처리 뒤에도 단계가 닫혔는지</li>
     *   <li>T2 단계 행이 겹치지 않았는가 — 동시 처리에서 두 워커가 같은 단계를 두 번 열지 않았는지</li>
     *   <li>T4 행 수 = 처리 건수(성공+실패), T1 성공 수 = T4 SUCCESS 수</li>
     * </ul>
     */
    private Map<String, Object> checks(VoiceBatchResult r) {
        Map<String, Object> c = new LinkedHashMap<>();
        Map<String, Object> db;
        try {
            db = verification.db(r.execId());
        } catch (Exception e) {
            c.put("available", false);
            c.put("reason", "로그 컬렉터 조회 실패 — " + e.getMessage());
            return c;
        }
        if (!Boolean.TRUE.equals(db.get("available"))) {
            c.put("available", false);
            c.put("reason", db.getOrDefault("reason", "로그 컬렉터 미연동"));
            return c;
        }
        c.put("available", true);
        Map<?, ?> t1 = (Map<?, ?>) db.get("t1");
        List<?> t2 = (List<?>) db.getOrDefault("t2", List.of());
        Map<?, ?> t4 = (Map<?, ?>) db.getOrDefault("t4", Map.of());

        long running = 0;
        Map<String, Integer> perType = new HashMap<>();
        for (Object o : t2) {
            Map<?, ?> s = (Map<?, ?>) o;
            if ("RUNNING".equals(String.valueOf(s.get("stepStsCd")))) {
                running++;
            }
            perType.merge(String.valueOf(s.get("stepTypeCd")), 1, Integer::sum);
        }
        List<String> dup = perType.entrySet().stream().filter(e -> e.getValue() > 1).map(Map.Entry::getKey).sorted().toList();
        long expected = (long) r.successCnt() + r.failCnt();
        long t4Rows = num(t4.get("total"));
        Map<?, ?> byStatus = t4.get("byStatus") instanceof Map<?, ?> b ? b : Map.of();
        long t4Success = num(byStatus.get("SUCCESS"));
        long t1Success = num(t1 == null ? null : t1.get("successCnt"));
        // 제논 — 성공한 건마다 수신증 하나(전송 실패 건은 없다). T5(비식별·전송 로그)는 3단계 복원(2026-10-01)으로 남지 않는다.
        //   수신증은 수집기 메모리에 최근 keep-receipts 개만 있어, 성공이 그보다 많으면 건수 대조를 건너뛴다
        long zenonSent = zenon.receipts(r.execId()).size();
        boolean zenonComparable = r.successCnt() <= zenon.keepLimit();

        c.put("t1Status", t1 == null ? null : t1.get("execStsCd"));
        c.put("t2Steps", t2.size());
        c.put("t2Running", running);
        c.put("t2Duplicated", dup);
        c.put("t4Rows", t4Rows);
        c.put("t4Expected", expected);
        c.put("t1Success", t1Success);
        c.put("t4Success", t4Success);
        c.put("zenonMode", zenon.mode());
        c.put("zenonSent", zenonSent);
        c.put("zenonExpected", zenonComparable ? (long) r.successCnt() : null);
        c.put("ok", running == 0 && dup.isEmpty() && t4Rows == expected && t1Success == t4Success
                && (!zenonComparable || zenonSent == r.successCnt()));
        return c;
    }

    private Map<String, Object> modes() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", source.mode());
        m.put("broker", broker.mode());
        m.put("phone", phoneFileProvider.mode());
        m.put("decrypt", decryptService.mode());
        m.put("stt", sttClient.mode());
        return m;
    }

    /** 화면 입력 패널이 쓰는 한 장 — 범위·기본값·지금 구성. {@code batchWorkers} 는 운영 배치의 워커 구성. */
    public Map<String, Object> info(Workers batchWorkers) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sttMode", sttClient.mode());
        m.put("sttTimeoutSec", props.stt().timeoutSec());
        m.put("mockLatency", latency.snapshot());
        m.put("minTotal", PerfRequest.MIN_TOTAL);
        m.put("maxTotal", PerfRequest.MAX_TOTAL);
        m.put("maxLatencyMs", PerfRequest.MAX_LATENCY_MS);
        m.put("maxTimeoutMs", PerfRequest.MAX_TIMEOUT_MS);
        m.put("defaultAcquireWorkers", PerfRequest.DEFAULT_ACQUIRE_WORKERS);
        m.put("defaultSttWorkers", PerfRequest.DEFAULT_STT_WORKERS);
        m.put("maxWorkers", PerfRequest.MAX_WORKERS);
        m.put("rampMaxWorkers", RampRequest.MAX_WORKERS);
        m.put("rampMaxSteps", RampRequest.MAX_STEPS);
        m.put("batchConcurrency", batchWorkers.stt());
        m.put("batchAcquireWorkers", batchWorkers.acquire());
        // 원천 풀 설정 — 화면이 'Hikari 최대 40 · 누수 감지 30초' 로 보여 준다
        Map<String, Object> pool = new LinkedHashMap<>();
        pool.put("maxPoolSize", props.source().directDb().maxPoolSize());
        pool.put("leakDetectionMs", props.source().directDb().leakDetectionThresholdMs());
        pool.put("idleTimeoutMs", props.source().directDb().idleTimeoutMs());
        m.put("pool", pool);
        m.put("maxFilesPerRun", props.batch().maxFilesPerRun());
        m.put("modes", modes());
        m.put("env", deployEnv.snapshot().get("kind"));
        m.put("db", dbKind.label());
        m.put("historyFile", historyFile(HISTORY_FILE).toString().replace('\\', '/'));
        m.put("rampHistoryFile", historyFile(RAMP_HISTORY_FILE).toString().replace('\\', '/'));
        m.put("active", isActive());
        return m;
    }

    // ── 이력 ──────────────────────────────────────────────────────────────

    private Path historyFile(String name) {
        return Path.of(dirs.baseDir(), "perf", name);
    }

    /**
     * 마지막 실행 결과만 남긴다 — 덮어쓰기(TRUNCATE_EXISTING). 누적하면 PV({@code {ROOT}/perf/})가 끝없이 커진다(2026-10-01).
     * 화면의 ③ 실행 이력에는 마지막 한 회차만 보인다.
     */
    private synchronized void appendHistory(String name, Map<String, Object> result) {
        Path f = historyFile(name);
        try {
            Files.createDirectories(f.getParent());
            String line = objectMapper.writeValueAsString(result) + "\n";
            Files.writeString(f, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.warn("[Perf] 이력 기록 실패 — {} ({})", f, e.getMessage());
        }
    }

    /** 기본 부하 검증 이력 — 최근 것이 앞. */
    public Map<String, Object> history() {
        return readHistory(HISTORY_FILE);
    }

    /** 임계 성능 시험 이력 — 최근 것이 앞. */
    public Map<String, Object> rampHistory() {
        return readHistory(RAMP_HISTORY_FILE);
    }

    public Map<String, Object> clearHistory() {
        return deleteHistory(HISTORY_FILE);
    }

    public Map<String, Object> clearRampHistory() {
        return deleteHistory(RAMP_HISTORY_FILE);
    }

    /** 한 줄이 깨져 있어도 나머지는 읽는다. */
    private synchronized Map<String, Object> readHistory(String name) {
        Path f = historyFile(name);
        List<Map<String, Object>> items = new ArrayList<>();
        int broken = 0;
        if (Files.isRegularFile(f)) {
            try {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        items.add(objectMapper.readValue(line, new TypeReference<Map<String, Object>>() { }));
                    } catch (IOException e) {
                        broken++;
                    }
                }
            } catch (IOException e) {
                log.warn("[Perf] 이력 읽기 실패 — {} ({})", f, e.getMessage());
            }
        }
        Collections.reverse(items);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("file", f.toString().replace('\\', '/'));
        out.put("total", items.size());
        out.put("broken", broken);
        out.put("items", items.size() > HISTORY_READ_MAX ? items.subList(0, HISTORY_READ_MAX) : items);
        return out;
    }

    private synchronized Map<String, Object> deleteHistory(String name) {
        Path f = historyFile(name);
        Map<String, Object> out = new LinkedHashMap<>();
        int n = 0;
        try {
            if (Files.isRegularFile(f)) {
                n = (int) Files.readAllLines(f, StandardCharsets.UTF_8).stream().filter(l -> !l.isBlank()).count();
                Files.delete(f);
            }
        } catch (IOException e) {
            out.put("error", e.getMessage());
        }
        out.put("deleted", n);
        out.put("file", f.toString().replace('\\', '/'));
        return out;
    }

    // ── 보조 ──────────────────────────────────────────────────────────────

    /** 화면에 올릴 실패 사유 — 가장 안쪽 원인만, 300자까지. 바깥 예외는 SQL 전문을 싣고 와 읽히지 않는다. */
    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = root.getMessage() == null ? "" : root.getMessage();
        return root.getClass().getSimpleName() + ": " + (msg.length() > 300 ? msg.substring(0, 300) + "…" : msg);
    }

    private static long heapUsed() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static double mb(long bytes) {
        return round(bytes / 1024d / 1024d, 1);
    }

    private static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }

    private static long num(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return o == null ? 0L : Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    @PreDestroy
    void shutdown() {
        runner.shutdownNow();
    }
}
