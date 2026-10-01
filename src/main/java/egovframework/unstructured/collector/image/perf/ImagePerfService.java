package egovframework.unstructured.collector.image.perf;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import egovframework.unstructured.collector.common.config.DbKindDetector;
import egovframework.unstructured.collector.common.config.SourcePoolPeak;
import egovframework.unstructured.collector.common.config.VoiceDirState;
import egovframework.unstructured.collector.common.config.VoiceModeState;
import egovframework.unstructured.collector.image.batch.ImageCollectService;
import egovframework.unstructured.collector.image.config.AdminDb;
import egovframework.unstructured.collector.image.model.ImageOutcome;
import egovframework.unstructured.collector.image.model.ImageStage;
import egovframework.unstructured.collector.image.sim.ImageSimulationService;
import egovframework.unstructured.collector.image.store.InmatePhotoRepository;
import egovframework.unstructured.collector.voice.batch.BatchProgress;
import egovframework.unstructured.collector.voice.perf.PerfRunService;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * <b>수용자 이미지 수집 검증</b>(시뮬레이터 6번 탭) — SIM 사진으로 실제 이미지 파이프라인을 돌려 처리량·단계별 시간·
 * 정합성을 재고, <b>재실행(멱등성)</b>을 검증한다.
 *
 * <p><b>두 가지 실행</b></p>
 * <ul>
 *   <li><b>NEW — 초기화 후 신규 실행</b>: 남은 SIM 을 지우고 N명분을 새로 만든 뒤 처리한다</li>
 *   <li><b>RERUN — 기존 데이터로 재실행</b>: 남아 있는 SIM 을 그대로 다시 처리한다. 범위 ALL 은 전건을 다시 받아
 *       UPSERT(이미 매핑된 건은 갱신 · 안 된 건은 신규), MISSING 은 운영 배치처럼 변경 없는 건을 건너뛰고 실패·누락분만</li>
 * </ul>
 *
 * <p><b>의도적 실패(주입)</b> — 실패율(%)만큼의 건을 <b>정확한 건수로</b> 골라 정한 단계(수신·복호화·저장·매핑, 또는 고르게
 * 섞기)에서 실패시킨다. 매핑 단계는 UPSERT 뒤 커밋 전에 실패시켜 롤백과 커넥션 반납을 실제로 거친다.
 * 예: 신규 실행 실패율 10% → 90% 성공 · 10% 실패, 이어서 재실행(ALL · 0%) → 실패했던 10% 는 신규 INSERT,
 * 성공했던 90% 는 키 중복 없이 UPSERT(갱신) → 최종 100% 매핑.</p>
 *
 * <ol>
 *   <li><b>준비</b> — NEW 는 SIM 생성(측정에 넣지 않는다), RERUN 은 남은 SIM 확인 · 재실행 전 매핑 스냅샷</li>
 *   <li><b>측정</b> — 이미지 파이프라인({@link ImageCollectService})을 워커 N개 · 건당 가상 지연으로 돌린다.
 *       원천·Admin 풀의 풀별 최고 연결 · 대기 · 최대 대기 시간을 함께 잰다</li>
 *   <li><b>검증</b> — 스냅샷과 주입 목록으로 계산한 <b>기대값</b>(신규·UPSERT·건너뜀·실패·최종 매핑)과 실제를 대조하고,
 *       주입 건이 매핑에 반영되지 않았는지(롤백) · 최신 순번 · 원문 해시 · PK 중복 오류 0 · 커넥션 풀 반납을 본다</li>
 * </ol>
 *
 * <p><b>SIM 데이터는 남긴다</b> — 재실행을 위해서다. 지우는 것은 [SIM 데이터 정리]({@link #cleanSim()})와 다음 신규 실행이다.
 * 남아 있어도 실제 수집 배치는 SIM 행을 집어가지 않는다.</p>
 *
 * <p><b>브로커</b>: SIM 원본(암호화된 더미 사진)은 수집기 저장소에만 있어, 받기는 설정 브로커가 아니라 수집기 내장
 * Mock 브로커로 한다 — 개발계 브로커는 별도 파드 · DUMMY 어댑터라 FILEKEY 와 무관한 가짜 음성을 자기 파드에 만든다.
 * 리포트의 {@code env.broker} 에 그렇게 적힌다. 실제 수집({@code POST /api/v1/image/batches})은 설정 브로커를 쓴다.</p>
 *
 * <p>음성 성능 시험·배치와 겹쳐 돌지 않는다 — 풀 최고치 측정이 섞인다.</p>
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class ImagePerfService {

    private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String HISTORY_FILE = "image-history.jsonl";
    private static final int HISTORY_READ_MAX = 200;
    /** 매핑된 순번 — 시딩이 순번 1·2(사진)·3(사진 아님)을 만든다. 최신 사진은 2 다. */
    private static final int LATEST_SN = 2;
    /** 브로커가 수신 폴더에 떨군 SIM 암호문 이름의 앞부분 — {@code ImageTarget.receiveName()} = img_{CORR_NO}_{IMAGE_SN}.bin. */
    private static final String RECEIVED_PREFIX = "img_" + ImageSimulationService.PREFIX;

    public enum Phase {
        PREPARING("준비"), RUNNING("측정"), VERIFYING("검증"), DONE("완료"), FAILED("실패");

        private final String label;

        Phase(String label) {
            this.label = label;
        }

        boolean active() {
            return this != DONE && this != FAILED;
        }
    }

    /** NEW — 초기화 후 신규 실행 · RERUN — 기존 데이터로 재실행(멱등성). */
    public enum Mode {
        NEW("신규 실행"), RERUN("재실행");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 재실행 범위 — ALL: 전건 다시(UPSERT) · MISSING: 변경 없는 건은 건너뛰고 실패·누락분만(운영 배치와 같다). */
    public enum Scope {
        ALL("전체 덮어쓰기(UPSERT)"), MISSING("실패·누락분만");

        private final String label;

        Scope(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 의도적 실패 지점 — RANDOM 은 수신·복호화·저장·매핑에 고르게 나눈다. */
    public enum FailStage {
        RANDOM(null), ACQUIRE(ImageStage.ACQUIRE), DECRYPT(ImageStage.DECRYPT), SAVE(ImageStage.SAVE), MAP(ImageStage.MAP);

        private final ImageStage stage;

        FailStage(ImageStage stage) {
            this.stage = stage;
        }
    }

    private static final List<ImageStage> INJECTABLE = List.of(ImageStage.ACQUIRE, ImageStage.DECRYPT, ImageStage.SAVE,
            ImageStage.MAP);

    @Schema(description = "수용자 이미지 수집 검증 조건")
    public record ImagePerfRequest(
            @Schema(description = "이미지 수집 건수(수용자 수) — 1~5,000. 재실행(RERUN)이면 무시하고 남아 있는 SIM 전부", example = "50") Integer count,
            @Schema(description = "이미지 워커(동시성) 수 — 1~64", example = "4") Integer workers,
            @Schema(description = "건당 가상 이미지 처리 지연(ms) — 0~60,000. 실제로 기다린다", example = "200") Long latencyMs,
            @Schema(description = "NEW — SIM 을 지우고 새로 만들어 실행 · RERUN — 남아 있는 SIM 으로 다시 실행(멱등성)", example = "NEW") Mode mode,
            @Schema(description = "의도적 실패율(%) — 0~100. 대상 건수 × 비율만큼(반올림) 정확히 실패시킨다", example = "10") Integer failRatePct,
            @Schema(description = "의도적 실패 지점 — RANDOM(고르게) · ACQUIRE · DECRYPT · SAVE · MAP(커밋 전 롤백)", example = "RANDOM") FailStage failStage,
            @Schema(description = "재실행 범위 — ALL(전체 덮어쓰기 UPSERT) · MISSING(실패·누락분만)", example = "ALL") Scope rerunScope
    ) {
        public static final int DEFAULT_COUNT = 50;
        public static final int DEFAULT_WORKERS = 4;
        public static final long DEFAULT_LATENCY_MS = 200L;

        public static ImagePerfRequest fresh(int count, int workers, long latencyMs, int failRatePct, FailStage stage) {
            return new ImagePerfRequest(count, workers, latencyMs, Mode.NEW, failRatePct, stage, null);
        }

        public static ImagePerfRequest rerun(int workers, long latencyMs, Scope scope, int failRatePct, FailStage stage) {
            return new ImagePerfRequest(null, workers, latencyMs, Mode.RERUN, failRatePct, stage, scope);
        }

        ImagePerfRequest withDefaults() {
            return new ImagePerfRequest(count == null ? DEFAULT_COUNT : count, workers == null ? DEFAULT_WORKERS : workers,
                    latencyMs == null ? DEFAULT_LATENCY_MS : latencyMs, mode == null ? Mode.NEW : mode,
                    failRatePct == null ? 0 : failRatePct, failStage == null ? FailStage.RANDOM : failStage,
                    rerunScope == null ? Scope.ALL : rerunScope);
        }

        void validate() {
            if (mode == Mode.NEW && (count < 1 || count > ImageSimulationService.MAX)) {
                throw new IllegalArgumentException("이미지 수집 건수는 1~" + ImageSimulationService.MAX + " 이어야 합니다: " + count);
            }
            if (failRatePct < 0 || failRatePct > 100) {
                throw new IllegalArgumentException("의도적 실패율은 0~100% 이어야 합니다: " + failRatePct);
            }
            if (workers < 1 || workers > 64) {
                throw new IllegalArgumentException("이미지 워커 수는 1~64 이어야 합니다: " + workers);
            }
            if (latencyMs < 0 || latencyMs > 60_000) {
                throw new IllegalArgumentException("가상 이미지 처리 지연은 0~60,000ms 이어야 합니다: " + latencyMs);
            }
        }
    }

    private final ImageCollectService collect;
    private final ImageSimulationService sim;
    private final InmatePhotoRepository photos;
    private final AdminDb adminDb;
    private final SourcePoolPeak poolPeak;
    private final VoiceDirState dirs;
    private final VoiceModeState modes;
    private final DbKindDetector dbKind;
    private final PerfRunService voicePerf;
    private final BatchProgress voiceProgress;
    private final ObjectMapper objectMapper;
    /** 단계별 실행 기록 — 상태 폴링에 새 줄만, 결과에 전체를 싣는다. */
    private final egovframework.unstructured.collector.image.batch.ImageTrace trace;

    private final ExecutorService runner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "image-perf-runner");
        t.setDaemon(true);
        return t;
    });

    private volatile Run current;
    private final Random random = new Random();

    private static final class Run {
        final String id;
        final ImagePerfRequest req;
        final long startedAt = System.currentTimeMillis();
        volatile Phase phase = Phase.PREPARING;
        volatile String message = "준비 중…";
        volatile long finishedAt;
        volatile Map<String, Object> result;
        volatile String error;
        volatile boolean cancelRequested;

        Run(String id, ImagePerfRequest req) {
            this.id = id;
            this.req = req;
        }

        void to(Phase p, String msg) {
            phase = p;
            message = msg;
            log.info("[ImagePerf] {} {} — {}", id, p.label, msg);
        }
    }

    public synchronized Map<String, Object> start(ImagePerfRequest raw) {
        ImagePerfRequest req = (raw == null ? new ImagePerfRequest(null, null, null, null, null, null, null) : raw).withDefaults();
        req.validate();
        Run running = current;
        if (running != null && running.phase.active()) {
            throw new IllegalStateException("수용자 이미지 검증이 이미 돌고 있습니다 — " + running.id);
        }
        if (collect.isRunning()) {
            throw new IllegalStateException("수용자 이미지 수집이 돌고 있습니다 — 끝난 뒤 다시 시작하십시오");
        }
        if (voicePerf.isActive() || voiceProgress.isRunning()) {
            throw new IllegalStateException("음성 배치·성능 시험이 돌고 있습니다 — 풀 최고치 측정이 섞이므로 끝난 뒤 시작하십시오");
        }
        Run run = new Run(LocalDateTime.now().format(RUN_ID), req);
        current = run;
        runner.submit(() -> execute(run));
        return snapshot(run);
    }

    public Map<String, Object> cancel() {
        Run run = current;
        Map<String, Object> out = new LinkedHashMap<>();
        if (run == null || !run.phase.active()) {
            out.put("accepted", false);
            out.put("message", "돌고 있는 수용자 이미지 검증이 없습니다");
            return out;
        }
        run.cancelRequested = true;
        boolean batch = collect.cancel();
        out.put("accepted", true);
        out.put("message", (batch ? "중단 요청 — 처리 중인 건을 끝내고 멈춥니다" : "중단 요청 — 측정 전에 멈춥니다")
                + " · SIM 데이터는 남겨 둡니다(재실행·정리 가능)");
        out.putAll(snapshot(run));
        return out;
    }

    public boolean isActive() {
        Run run = current;
        return run != null && run.phase.active();
    }

    /** [SIM 데이터 정리] — 검증·수집이 도는 중에는 지우지 않는다(처리 중인 건의 원본·매핑이 사라진다). */
    public synchronized Map<String, Object> cleanSim() {
        guardIdle("SIM 데이터를 정리할");
        return sim.clean();
    }

    /** SIM 만 만들기(Swagger) — 검증·수집이 도는 중에는 막는다. */
    public synchronized Map<String, Object> seedSim(int count) {
        guardIdle("SIM 데이터를 만들");
        return sim.seed(count);
    }

    private void guardIdle(String what) {
        if (isActive() || collect.isRunning()) {
            throw new IllegalStateException("수용자 이미지 검증·수집이 돌고 있어 " + what + " 수 없습니다 — 끝난 뒤 다시 하십시오");
        }
    }

    public Map<String, Object> current() {
        return current(0);
    }

    /**
     * 현재 실행 상태 — {@code traceAfter} 뒤에 붙은 실행 기록만 {@code trace} 로 싣는다(화면이 폴링하며 이어 붙인다).
     */
    public Map<String, Object> current(int traceAfter) {
        Map<String, Object> m = currentBase();
        if (m.get("runId") != null) {
            m.put("traceExecId", trace.execId());
            m.put("trace", trace.since(Math.max(0, traceAfter)));
        }
        return m;
    }

    private Map<String, Object> currentBase() {
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
        m.put("phase", run.phase.name());
        m.put("phaseLabel", run.phase.label);
        m.put("active", run.phase.active());
        m.put("message", run.message);
        m.put("request", run.req);
        m.put("elapsedMs", (run.finishedAt == 0 ? System.currentTimeMillis() : run.finishedAt) - run.startedAt);
        m.put("cancelRequested", run.cancelRequested);
        if (run.phase == Phase.RUNNING || run.phase == Phase.VERIFYING) {
            m.put("progress", collect.progress());
        }
        m.put("result", run.result);
        m.put("error", run.error);
        return m;
    }

    // ── 실행 ──────────────────────────────────────────────────────────────

    private void execute(Run run) {
        ImagePerfRequest req = run.req;
        Map<String, Object> result = null;
        String error = null;
        trace.begin(null);   // 지난 실행 기록을 비운다 — 수집이 시작되면 실행 ID 로 다시 연다
        try {
            long p0 = System.currentTimeMillis();
            Map<String, Object> seed;
            List<String> seeded;
            Set<String> before;
            if (req.mode() == Mode.NEW) {
                run.to(Phase.PREPARING, "남은 SIM 정리 후 SIM 사진 %d명분 생성 (보라미 3테이블 + 암호화 더미) — %s"
                        .formatted(req.count(), dbKind.label()));
                seed = sim.seed(req.count());
                seeded = sim.seededCorrNos();
                before = Set.of();
            } else {
                run.to(Phase.PREPARING, "남아 있는 SIM 확인 · 재실행 전 매핑 스냅샷");
                seeded = sim.seededCorrNos();
                if (seeded.isEmpty()) {
                    throw new IllegalStateException("재실행할 SIM 데이터가 없습니다 — [초기화 후 신규 실행] 을 먼저 하십시오");
                }
                before = new HashSet<>(photos.mappedCorrNos(ImageSimulationService.PREFIX));
                before.retainAll(new HashSet<>(seeded));
                seed = new LinkedHashMap<>();
                seed.put("inmates", seeded.size());
            }
            long prepareMs = System.currentTimeMillis() - p0;
            if (run.cancelRequested) {
                throw new IllegalStateException("측정 전에 중단했습니다");
            }

            // 실제로 단계를 거치는 건 — 재실행 MISSING 은 이미 매핑된(변경 없는) 건을 건너뛴다
            boolean force = req.mode() == Mode.NEW || req.rerunScope() == Scope.ALL;
            List<String> candidates = force ? seeded : seeded.stream().filter(c -> !before.contains(c)).toList();
            Map<String, ImageStage> injected = pickInjected(candidates, req.failRatePct(), req.failStage());

            run.to(Phase.RUNNING, "%s%s · 워커 %d개 · 건당 가상 지연 %dms · %d명%s".formatted(req.mode().label(),
                    req.mode() == Mode.RERUN ? "(" + req.rerunScope().label() + ")" : "", req.workers(), req.latencyMs(),
                    seeded.size(), injected.isEmpty() ? "" : " · 의도적 실패 " + injected.size() + "건"));
            poolPeak.reset();
            ImageCollectService.ImageRunResult r = collect.run(new ImageCollectService.ImageRunRequest(
                    null, ImageSimulationService.PREFIX, seeded.size(), req.workers(), force, req.latencyMs(), "PERF", true,
                    true,   // SIM 원본은 수집기 저장소에만 있다 — 내장 Mock 브로커로 받는다
                    injected));
            Map<String, Object> hikari = new LinkedHashMap<>(poolPeak.snapshot());
            Map<String, Object> after = poolPeak.state();
            hikari.put("after", after);

            run.to(Phase.VERIFYING, "기대값(신규·UPSERT·실패·최종 매핑) 대조 · 롤백 · 최신 순번 · 원문 해시 · 풀 반납");
            Expect ex = new Expect(req, seeded, before, candidates, injected);
            Map<String, Object> checks = checks(ex, r, after);
            result = summarize(run, req, r, seed, prepareMs, hikari, checks, ex);
            result.put("simKept", sim.residual());
            result.put("trace", trace.entries());
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("[ImagePerf] {} 실패 — {}", run.id, error, e);
        }
        if (result != null) {
            appendHistory(result);
        }
        run.result = result;
        run.error = error;
        run.finishedAt = System.currentTimeMillis();
        if (error != null) {
            run.to(Phase.FAILED, error);
        } else {
            run.to(Phase.DONE, "완료 — 신규 %s · UPSERT %s · 건너뜀 %s · 실패 %s(주입 %s) · %s TPS · %s초 · 정합성 %s".formatted(
                    result.get("inserted"), result.get("updated"), result.get("skipped"), result.get("fail"),
                    result.get("injectedFail"), result.get("tps"), result.get("totalSec"),
                    Boolean.TRUE.equals(((Map<?, ?>) result.get("checks")).get("ok")) ? "✓" : "✗"));
        }
    }

    /**
     * 의도적 실패 대상 — 대상 건수 × 비율(반올림)만큼 정확히 무작위로 고른다. RANDOM 이면 수신·복호화·저장·매핑에 고르게 나눈다.
     */
    private Map<String, ImageStage> pickInjected(List<String> candidates, int ratePct, FailStage stage) {
        int k = (int) Math.round(candidates.size() * ratePct / 100.0);
        if (k <= 0) {
            return Map.of();
        }
        List<String> pool = new ArrayList<>(candidates);
        Collections.shuffle(pool, random);
        Map<String, ImageStage> out = new LinkedHashMap<>();
        for (int i = 0; i < k; i++) {
            out.put(pool.get(i), stage.stage != null ? stage.stage : INJECTABLE.get(i % INJECTABLE.size()));
        }
        return out;
    }

    /**
     * 기대값 — 실행 전 스냅샷(이미 매핑된 SIM)과 주입 목록으로 계산한다. 수집기가 다시 떠도(메모리를 잃어도) 맞다.
     *
     * <ul>
     *   <li>신규 INSERT = 대상 중 매핑이 없던 건 − 주입</li>
     *   <li>UPSERT(갱신) = 대상 중 매핑이 있던 건 − 주입 (재실행 ALL)</li>
     *   <li>건너뜀 = 대상이 아닌 건 (재실행 MISSING — 변경 없음)</li>
     *   <li>최종 매핑 = 실행 전 매핑 ∪ (대상 − 주입) — 주입 건은 롤백돼 새로 반영되지 않는다</li>
     * </ul>
     */
    private record Expect(ImagePerfRequest req, List<String> seeded, Set<String> before, List<String> candidates,
                          Map<String, ImageStage> injected) {

        long inserted() {
            return candidates.stream().filter(c -> !before.contains(c) && !injected.containsKey(c)).count();
        }

        long updated() {
            return candidates.stream().filter(c -> before.contains(c) && !injected.containsKey(c)).count();
        }

        long skipped() {
            return seeded.size() - candidates.size();
        }

        long mapped() {
            Set<String> m = new HashSet<>(before);
            candidates.stream().filter(c -> !injected.containsKey(c)).forEach(m::add);
            return m.size();
        }
    }

    private Map<String, Object> checks(Expect ex, ImageCollectService.ImageRunResult r, Map<String, Object> poolAfter) {
        Map<String, Object> c = new LinkedHashMap<>();
        List<Map<String, Object>> rows;
        try {
            rows = photos.listByPrefix(ImageSimulationService.PREFIX, 100_000);
        } catch (Exception e) {
            c.put("available", false);
            c.put("reason", "Admin DB 매핑 테이블 조회 실패 — " + e.getMessage());
            return c;
        }
        c.put("available", true);
        long latest = rows.stream().filter(m -> ((Number) m.get("image_sn")).intValue() == LATEST_SN).count();
        Map<String, String> paths = new LinkedHashMap<>();
        rows.forEach(m -> paths.put(String.valueOf(m.get("corr_no")), String.valueOf(m.get("photo_path"))));
        long exist = paths.values().stream().filter(p -> Files.isRegularFile(Path.of(p))).count();
        Map<String, Boolean> plain = sim.verifyPlain(paths);
        long plainOk = plain.values().stream().filter(Boolean::booleanValue).count();
        // 이번 실행이 쓴 행 — 주입 건이 여기에 있으면 롤백되지 않은 것이다
        Set<String> touched = rows.stream().filter(m -> r.execId().equals(m.get("last_batch_exec_id")))
                .map(m -> String.valueOf(m.get("corr_no"))).collect(Collectors.toSet());
        long injectedTouched = ex.injected().keySet().stream().filter(touched::contains).count();
        boolean returned = Boolean.TRUE.equals(poolAfter.get("returned"));

        List<Map<String, Object>> items = new ArrayList<>();
        int inj = ex.injected().size();
        item(items, "fail", r.fail() == inj && r.injectedFail() == inj,
                inj == 0 ? "실패 %d건 (기대 0)".formatted(r.fail())
                        : "실패 %d건 = 의도적 실패 %d건 · 실제 오류 %d건".formatted(r.fail(), inj, r.fail() - r.injectedFail()));
        item(items, "inserted", r.inserted() == ex.inserted(), "신규 INSERT %d = 기대 %d".formatted(r.inserted(), ex.inserted()));
        item(items, "updated", r.updated() == ex.updated(), "UPSERT(덮어쓰기) %d = 기대 %d".formatted(r.updated(), ex.updated()));
        if (ex.skipped() > 0 || r.skipped() > 0) {
            item(items, "skipped", r.skipped() == ex.skipped(), "건너뜀(변경 없음) %d = 기대 %d".formatted(r.skipped(), ex.skipped()));
        }
        item(items, "mapped", rows.size() == ex.mapped(), "최종 매핑 %d행 = 기대 %d행".formatted(rows.size(), ex.mapped()));
        item(items, "touched", touched.size() == r.inserted() + r.updated(),
                "이번 실행이 쓴 행 %d = 신규 + UPSERT %d".formatted(touched.size(), r.inserted() + r.updated()));
        if (inj > 0) {
            item(items, "rollback", injectedTouched == 0, "의도적 실패 건 매핑 미반영(롤백) %d/%d".formatted(inj - injectedTouched, inj));
        }
        item(items, "latest", latest == rows.size(), "최신 순번(2) 선택 %d/%d".formatted(latest, rows.size()));
        item(items, "files", exist == rows.size(), "저장 파일 %d/%d".formatted(exist, rows.size()));
        item(items, "plain", plainOk == plain.size(), "원문 일치(복호화) %d/%d".formatted(plainOk, plain.size()));
        item(items, "dupKey", r.dupKeyFail() == 0, "PK 중복 오류 %d건".formatted(r.dupKeyFail()));
        item(items, "pool", returned, returned ? "커넥션 풀 반납 완료 (사용 0 · 대기 0)" : "커넥션 풀 반납 안 됨");

        traceVerify(r, rows.size(), touched.size(), latest, exist, plainOk, plain.size(), poolAfter);

        c.put("items", items);
        c.put("mappedRows", rows.size());
        c.put("mappedExpected", ex.mapped());
        c.put("latestChosen", latest);
        c.put("filesExist", exist);
        c.put("plainMatch", plainOk);
        c.put("plainChecked", plain.size());
        c.put("injectedTouched", injectedTouched);
        c.put("ok", !r.canceled() && items.stream().allMatch(i -> Boolean.TRUE.equals(i.get("ok"))));
        c.put("sample", rows.stream().limit(5).toList());
        return c;
    }

    /** 사후 검증을 손으로 돌릴 명령과 이번 결과 — 2번 탭 "실행 결과 상세 검증" 과 같은 모양. */
    private void traceVerify(ImageCollectService.ImageRunResult r, int mapped, int touched, long latest, long exist,
                             long plainOk, int plainChecked, Map<String, Object> poolAfter) {
        var V = egovframework.unstructured.collector.image.batch.ImageTrace.Step.VERIFY;
        trace.add(V, "매핑 테이블 — SIM 행 수 · 이번 실행이 쓴 행 · 최신 순번(" + LATEST_SN + ")", "SQL",
                photos.verifySqlFor(ImageSimulationService.PREFIX, r.execId(), LATEST_SN),
                "total | touched | latest\n" + mapped + " | " + touched + " | " + latest);
        String root = String.valueOf(r.outputRoot());
        trace.add(V, "저장 사진 — PV 의 이미지 폴더(수용자별 하위 폴더)", "SHELL",
                "find " + root + " -type f -name '" + ImageSimulationService.PREFIX + "*' | wc -l",
                exist + "   # 매핑 " + mapped + "행 중 파일이 있는 것");
        trace.add(V, "수신 폴더 잔여물 — 받은 암호문(img_{수용자}_{순번}.bin)은 처리 뒤 지운다", "SHELL",
                "ls " + dirs.receiveMeet().replace('\\', '/') + " | grep -c '^" + RECEIVED_PREFIX + "'",
                String.valueOf(countReceived()));
        trace.add(V, "원문 일치 — 저장 사진의 SHA-256 = SIM 원본(복호화 전) 해시", "CODE",
                "sha256sum " + root + "/<수용자>/<파일>   # 시뮬레이터가 만든 원문 해시와 대조",
                plainOk + "/" + plainChecked + " 일치");
        trace.add(V, "커넥션 풀 반납 — 끝난 뒤 사용 0 · 대기 0", "CODE",
                "HikariPoolMXBean.getActiveConnections() / getThreadsAwaitingConnection()",
                String.valueOf(poolAfter));
    }

    private long countReceived() {
        try (var s = Files.list(Path.of(dirs.receiveMeet()))) {
            return s.filter(p -> p.getFileName().toString().startsWith(RECEIVED_PREFIX)).count();
        } catch (Exception e) {
            return 0L;
        }
    }

    private static void item(List<Map<String, Object>> items, String key, boolean ok, String label) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("ok", ok);
        m.put("label", label);
        items.add(m);
    }

    private Map<String, Object> summarize(Run run, ImagePerfRequest req, ImageCollectService.ImageRunResult r,
                                          Map<String, Object> seed, long prepareMs, Map<String, Object> hikari,
                                          Map<String, Object> checks, Expect ex) {
        double sec = r.elapsedMs() / 1000d;
        long processed = (long) r.success() + r.fail();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", run.id);
        m.put("at", LocalDateTime.now().withNano(0).toString());
        m.put("mode", req.mode().name());
        m.put("modeLabel", req.mode().label());
        m.put("rerunScope", req.mode() == Mode.RERUN ? req.rerunScope().name() : null);
        m.put("rerunScopeLabel", req.mode() == Mode.RERUN ? req.rerunScope().label() : null);
        m.put("count", ex.seeded().size());
        m.put("workers", req.workers());
        m.put("latencyMs", req.latencyMs());
        m.put("failRatePct", req.failRatePct());
        m.put("failStage", req.failStage().name());
        m.put("execId", r.execId());
        m.put("canceled", r.canceled());
        m.put("totalSec", round(sec, 2));
        m.put("prepareSec", round(prepareMs / 1000d, 1));
        m.put("tps", sec <= 0 ? 0d : round(processed / sec, 3));
        m.put("total", r.total());
        m.put("success", r.success());
        m.put("fail", r.fail());
        m.put("injectedFail", r.injectedFail());
        m.put("realFail", r.fail() - r.injectedFail());
        m.put("skipped", r.skipped());
        m.put("inserted", r.inserted());
        m.put("updated", r.updated());
        m.put("stale", r.stale());
        m.put("failByStage", r.failByStage());
        m.put("dupKeyFail", r.dupKeyFail());
        Map<String, Long> injByStage = new LinkedHashMap<>();
        ex.injected().values().forEach(st -> injByStage.merge(st.name(), 1L, Long::sum));
        m.put("injected", ex.injected().size());
        m.put("injectedByStage", injByStage);
        Map<String, Object> exp = new LinkedHashMap<>();
        exp.put("beforeMapped", ex.before().size());
        exp.put("beforeUnmapped", ex.seeded().size() - ex.before().size());
        exp.put("candidates", ex.candidates().size());
        exp.put("inserted", ex.inserted());
        exp.put("updated", ex.updated());
        exp.put("skipped", ex.skipped());
        exp.put("mapped", ex.mapped());
        m.put("expected", exp);
        Map<String, Object> cov = new LinkedHashMap<>();
        Object mapped = checks.get("mappedRows");
        long mappedRows = mapped instanceof Number n ? n.longValue() : 0L;
        cov.put("mapped", mappedRows);
        cov.put("total", ex.seeded().size());
        cov.put("pct", ex.seeded().isEmpty() ? 0d : round(mappedRows * 100d / ex.seeded().size(), 1));
        m.put("coverage", cov);
        m.put("stages", r.stages());
        m.put("decryptAvgMs", stageAvg(r, "DECRYPT"));
        m.put("mapAvgMs", stageAvg(r, "MAP"));
        m.put("acquireAvgMs", stageAvg(r, "ACQUIRE"));
        m.put("saveAvgMs", stageAvg(r, "SAVE"));
        m.put("hikari", hikari);
        m.put("checks", checks);
        m.put("failures", r.outcomes().stream().filter(ImageOutcome::isFail).limit(10)
                .map(o -> Map.of("corrNo", o.corrNo(), "step", String.valueOf(o.failedAt()), "error", String.valueOf(o.errMsg()),
                        "injected", o.injected()))
                .toList());
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("broker", r.broker());
        env.put("decrypt", modes.decrypt().name());
        env.put("db", dbKind.label());
        env.put("adminDb", adminDb.isH2() ? "로컬 H2" : adminDb.url());
        env.put("upsert", r.upsertMode());
        env.put("outputRoot", r.outputRoot());
        env.put("encrypted", seed.get("encrypted"));
        m.put("env", env);
        return m;
    }

    private static Object stageAvg(ImageCollectService.ImageRunResult r, String key) {
        return r.stages().stream().filter(s -> key.equals(s.get("key"))).findFirst().map(s -> s.get("avgMs")).orElse(0d);
    }

    // ── 이력 ──────────────────────────────────────────────────────────────

    private Path historyFile() {
        return Path.of(dirs.baseDir(), "perf", HISTORY_FILE);
    }

    /** 마지막 실행 결과만 남긴다 — 덮어쓰기(TRUNCATE_EXISTING). 누적하면 PV 가 끝없이 커진다(2026-10-01). */
    private synchronized void appendHistory(Map<String, Object> result) {
        Path f = historyFile();
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, objectMapper.writeValueAsString(result) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.warn("[ImagePerf] 이력 기록 실패 — {} ({})", f, e.getMessage());
        }
    }

    public synchronized Map<String, Object> history() {
        Path f = historyFile();
        List<Map<String, Object>> items = new ArrayList<>();
        if (Files.isRegularFile(f)) {
            try {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) {
                        try {
                            items.add(objectMapper.readValue(line, new TypeReference<Map<String, Object>>() { }));
                        } catch (IOException ignored) {
                            // 깨진 줄은 건너뛴다
                        }
                    }
                }
            } catch (IOException e) {
                log.warn("[ImagePerf] 이력 읽기 실패 — {} ({})", f, e.getMessage());
            }
        }
        Collections.reverse(items);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("file", f.toString().replace('\\', '/'));
        out.put("total", items.size());
        out.put("items", items.size() > HISTORY_READ_MAX ? items.subList(0, HISTORY_READ_MAX) : items);
        return out;
    }

    public synchronized Map<String, Object> clearHistory() {
        Path f = historyFile();
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

    private static double round(double v, int d) {
        double f = Math.pow(10, d);
        return Math.round(v * f) / f;
    }

    @PreDestroy
    void shutdown() {
        runner.shutdownNow();
    }
}
