package egovframework.unstructured.collector.image.perf;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import egovframework.unstructured.collector.common.config.DbKindDetector;
import egovframework.unstructured.collector.common.config.SourcePoolPeak;
import egovframework.unstructured.collector.common.config.VoiceDirState;
import egovframework.unstructured.collector.common.config.VoiceModeState;
import egovframework.unstructured.collector.image.batch.ImageCollectService;
import egovframework.unstructured.collector.image.config.AdminDb;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <b>수용자 이미지 수집 검증</b>(시뮬레이터 6번 탭) — SIM 사진 N명분을 만들어 실제 이미지 파이프라인으로 처리하고
 * 처리량·단계별 시간·정합성을 잰다.
 *
 * <ol>
 *   <li><b>준비</b> — SIM 사진 데이터 생성(보라미 3테이블 + 암호화된 더미 사진). 측정에 넣지 않는다</li>
 *   <li><b>측정</b> — 이미지 파이프라인({@link ImageCollectService})을 워커 N개 · 건당 가상 지연으로 돌린다.
 *       원천·Admin 풀의 최고 연결 수를 함께 잰다</li>
 *   <li><b>검증</b> — 매핑 행 수 = 성공 수, 매핑된 순번이 최신(2)인지, 저장된 사진이 원문과 같은지(복호화 정확성)</li>
 *   <li><b>정리</b> — SIM 행 · 매핑 · 더미 · 저장 사진을 지운다(실패·중단이어도)</li>
 * </ol>
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

    public enum Phase {
        PREPARING("준비"), RUNNING("측정"), VERIFYING("검증"), CLEANING("정리"), DONE("완료"), FAILED("실패");

        private final String label;

        Phase(String label) {
            this.label = label;
        }

        boolean active() {
            return this != DONE && this != FAILED;
        }
    }

    @Schema(description = "수용자 이미지 수집 검증 조건")
    public record ImagePerfRequest(
            @Schema(description = "이미지 수집 건수(수용자 수) — 1~1,000", example = "50") Integer count,
            @Schema(description = "이미지 워커(동시성) 수 — 1~64", example = "4") Integer workers,
            @Schema(description = "건당 가상 이미지 처리 지연(ms) — 0~60,000. 실제로 기다린다", example = "200") Long latencyMs
    ) {
        public static final int DEFAULT_COUNT = 50;
        public static final int DEFAULT_WORKERS = 4;
        public static final long DEFAULT_LATENCY_MS = 200L;

        ImagePerfRequest withDefaults() {
            return new ImagePerfRequest(count == null ? DEFAULT_COUNT : count, workers == null ? DEFAULT_WORKERS : workers,
                    latencyMs == null ? DEFAULT_LATENCY_MS : latencyMs);
        }

        void validate() {
            if (count < 1 || count > ImageSimulationService.MAX) {
                throw new IllegalArgumentException("이미지 수집 건수는 1~" + ImageSimulationService.MAX + " 이어야 합니다: " + count);
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

    private final ExecutorService runner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "image-perf-runner");
        t.setDaemon(true);
        return t;
    });

    private volatile Run current;

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
        ImagePerfRequest req = (raw == null ? new ImagePerfRequest(null, null, null) : raw).withDefaults();
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
        out.put("message", batch ? "중단 요청 — 처리 중인 건을 끝내고 멈춥니다" : "중단 요청 — 측정 전에 멈춥니다");
        out.putAll(snapshot(run));
        return out;
    }

    public boolean isActive() {
        Run run = current;
        return run != null && run.phase.active();
    }

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
        try {
            run.to(Phase.PREPARING, "SIM 사진 %d명분 생성 (보라미 3테이블 + 암호화 더미) — %s".formatted(req.count(), dbKind.label()));
            long p0 = System.currentTimeMillis();
            Map<String, Object> seed = sim.seed(req.count());
            long prepareMs = System.currentTimeMillis() - p0;
            if (run.cancelRequested) {
                throw new IllegalStateException("측정 전에 중단했습니다");
            }

            run.to(Phase.RUNNING, "워커 %d개 · 건당 가상 지연 %dms 로 %d명 처리 중".formatted(req.workers(), req.latencyMs(), req.count()));
            poolPeak.reset();
            ImageCollectService.ImageRunResult r = collect.run(new ImageCollectService.ImageRunRequest(
                    null, ImageSimulationService.PREFIX, req.count(), req.workers(), true, req.latencyMs(), "PERF", true,
                    true));   // SIM 원본은 수집기 저장소에만 있다 — 내장 Mock 브로커로 받는다
            Map<String, Object> hikari = new LinkedHashMap<>(poolPeak.snapshot());
            hikari.put("after", poolPeak.state());

            run.to(Phase.VERIFYING, "매핑 행 · 최신 순번 · 복호화 결과(원문 해시) 대조");
            Map<String, Object> checks = checks(r);
            result = summarize(run, req, r, seed, prepareMs, hikari, checks);
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("[ImagePerf] {} 실패 — {}", run.id, error, e);
        }
        run.to(Phase.CLEANING, "SIM 행 · 매핑 · 더미 · 저장 사진 삭제");
        Map<String, Object> cleanup;
        try {
            cleanup = sim.clean();
            cleanup.put("ok", true);
        } catch (Exception e) {
            cleanup = new LinkedHashMap<>();
            cleanup.put("ok", false);
            cleanup.put("error", e.getMessage());
        }
        if (result != null) {
            result.put("cleanup", cleanup);
            appendHistory(result);
        }
        run.result = result;
        run.error = error;
        run.finishedAt = System.currentTimeMillis();
        if (error != null) {
            run.to(Phase.FAILED, error);
        } else {
            run.to(Phase.DONE, "완료 — 성공 %s · 실패 %s · %s TPS · %s초".formatted(result.get("success"), result.get("fail"),
                    result.get("tps"), result.get("totalSec")));
        }
    }

    private Map<String, Object> checks(ImageCollectService.ImageRunResult r) {
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
        c.put("mappedRows", rows.size());
        c.put("mappedExpected", r.success());
        c.put("latestChosen", latest);
        c.put("filesExist", exist);
        c.put("plainMatch", plainOk);
        c.put("plainChecked", plain.size());
        c.put("ok", rows.size() == r.success() && latest == rows.size() && exist == rows.size()
                && plainOk == plain.size() && r.fail() == 0);
        c.put("sample", rows.stream().limit(5).toList());
        return c;
    }

    private Map<String, Object> summarize(Run run, ImagePerfRequest req, ImageCollectService.ImageRunResult r,
                                          Map<String, Object> seed, long prepareMs, Map<String, Object> hikari,
                                          Map<String, Object> checks) {
        double sec = r.elapsedMs() / 1000d;
        long processed = (long) r.success() + r.fail();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", run.id);
        m.put("at", LocalDateTime.now().withNano(0).toString());
        m.put("count", req.count());
        m.put("workers", req.workers());
        m.put("latencyMs", req.latencyMs());
        m.put("execId", r.execId());
        m.put("canceled", r.canceled());
        m.put("totalSec", round(sec, 2));
        m.put("prepareSec", round(prepareMs / 1000d, 1));
        m.put("tps", sec <= 0 ? 0d : round(processed / sec, 3));
        m.put("total", r.total());
        m.put("success", r.success());
        m.put("fail", r.fail());
        m.put("skipped", r.skipped());
        m.put("inserted", r.inserted());
        m.put("updated", r.updated());
        m.put("stale", r.stale());
        m.put("stages", r.stages());
        m.put("decryptAvgMs", stageAvg(r, "DECRYPT"));
        m.put("mapAvgMs", stageAvg(r, "MAP"));
        m.put("acquireAvgMs", stageAvg(r, "ACQUIRE"));
        m.put("saveAvgMs", stageAvg(r, "SAVE"));
        m.put("hikari", hikari);
        m.put("checks", checks);
        m.put("failures", r.outcomes().stream().filter(o -> "FAIL".equals(o.status())).limit(10)
                .map(o -> Map.of("corrNo", o.corrNo(), "step", String.valueOf(o.failedAt()), "error", String.valueOf(o.errMsg())))
                .toList());
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("broker", r.broker());
        env.put("decrypt", modes.decrypt().name());
        env.put("db", dbKind.label());
        env.put("adminDb", adminDb.isH2() ? "로컬 H2" : adminDb.url());
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

    private synchronized void appendHistory(Map<String, Object> result) {
        Path f = historyFile();
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, objectMapper.writeValueAsString(result) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
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
