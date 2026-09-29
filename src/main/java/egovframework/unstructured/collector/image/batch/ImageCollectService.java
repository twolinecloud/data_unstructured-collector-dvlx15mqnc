package egovframework.unstructured.collector.image.batch;

import egovframework.unstructured.collector.common.broker.BrokerOutputCheck;
import egovframework.unstructured.collector.common.broker.MockXvarmBrokerClient;
import egovframework.unstructured.collector.common.broker.XvarmBrokerClient;
import egovframework.unstructured.collector.common.config.VoiceDirState;
import egovframework.unstructured.collector.common.model.VoiceKind;
import egovframework.unstructured.collector.common.sync.FileArrivalWatcher;
import egovframework.unstructured.collector.image.config.AdminDb;
import egovframework.unstructured.collector.image.config.ImageProperties;
import egovframework.unstructured.collector.image.model.ImageOutcome;
import egovframework.unstructured.collector.image.model.ImageStage;
import egovframework.unstructured.collector.image.model.ImageTarget;
import egovframework.unstructured.collector.image.source.ImageSourceService;
import egovframework.unstructured.collector.image.store.ImageFileStore;
import egovframework.unstructured.collector.image.store.InmatePhotoRepository;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <b>수용자 이미지 수집</b> — 보라미 조회 → FILEKEY 추출 → 브로커 수신 → 복호화(접견과 같은 모듈) → 저장 → Admin DB 매핑.
 *
 * <ol>
 *   <li>최신 이미지 조회 · FILEKEY 추출 — {@link ImageSourceService}(페이지 단위, 배치 앞에서 한 번)</li>
 *   <li>건마다(워커 N개) — 브로커 {@code POST /api/v1/xvarm/extract} 로 수신 폴더에 받기 → 복호화 → 저장 → 매핑</li>
 * </ol>
 *
 * <p><b>변경 없는 건은 다시 받지 않는다</b> — 매핑 테이블의 순번·FILEKEY 가 같고 저장 파일이 있으면 '건너뜀'.
 * {@code force} 로 다시 받게 할 수 있다. 더 최신 사진이 이미 매핑된 수용자도 건너뛴다.</p>
 *
 * <p><b>커넥션을 잡고 기다리지 않는다</b>: 조회는 문장마다 원천 커넥션을 빌렸다 곧 돌려주고, 파일 수신·복호화·저장은
 * 트랜잭션 밖이다. 매핑만 Admin DB 에서 짧은 트랜잭션 하나다({@link InmatePhotoRepository#upsert}).</p>
 *
 * <p>한 번에 하나만 돈다. 처리 이력은 아직 로그 컬렉터(T1·T2·T4)에 남기지 않는다 — 이미지용 작업 코드(C-코드)가
 * 로그 컬렉터에 먼저 정해져야 한다. 결과는 응답과 매핑 테이블({@code last_batch_exec_id})로 확인한다.</p>
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class ImageCollectService {

    private static final DateTimeFormatter EXEC_ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String CANCELED = "중단됨 — 사용자가 배치를 멈췄습니다";
    /** 결과에 싣는 건별 목록 상한 — 수천 건 배치의 응답이 커지지 않게. */
    private static final int OUTCOME_LIMIT = 300;

    private final ImageSourceService source;
    private final XvarmBrokerClient broker;
    /**
     * 수집기 내장 Mock 브로커 — SIM 검증(6번 탭)이 쓴다. SIM 원본은 수집기 저장소에만 있어 실제 브로커가 읽을 수 없다
     * (개발계 브로커는 별도 파드 · DUMMY 어댑터라 FILEKEY 와 무관한 가짜 음성을 자기 파드에 만든다).
     */
    private final MockXvarmBrokerClient mockBroker;
    private final FileArrivalWatcher watcher;
    private final VoiceDirState dirs;
    private final ImageFileStore store;
    private final InmatePhotoRepository repo;
    private final ImageProperties props;
    private final AdminDb adminDb;

    private final AtomicBoolean running = new AtomicBoolean();

    @Schema(description = "수용자 이미지 수집 조건")
    public record ImageRunRequest(
            @Schema(description = "이 수용자들만(비우면 전체)", example = "[\"2023000001\"]") List<String> corrNos,
            @Schema(description = "이 접두의 수용자만 — 시뮬레이션(SIMIMG)", example = "") String corrNoPrefix,
            @Schema(description = "최대 수용자 수(비우면 image.max-per-run)", example = "100") Integer limit,
            @Schema(description = "워커 수(비우면 image.workers)", example = "4") Integer workers,
            @Schema(description = "변경 없는 건도 다시 받는다", example = "false") Boolean force,
            @Schema(description = "건당 가상 처리 지연(ms) — 성능 시험용. 실제로 기다린다", example = "0") Long virtualLatencyMs,
            @Schema(hidden = true) String triggerBy,
            @Schema(hidden = true) Boolean testRun,
            /** SIM 검증 — 설정 브로커 대신 수집기 내장 Mock 브로커로 받는다(원본이 수집기 저장소에만 있다) */
            @Schema(hidden = true) Boolean simBroker
    ) {}

    /** 한 번의 결과. */
    public record ImageRunResult(
            String execId, String triggerBy, int workers, int total, int success, int fail, int skipped,
            int inserted, int updated, int stale, long elapsedMs, boolean canceled,
            List<Map<String, Object>> stages, List<ImageOutcome> outcomes, boolean outcomesTruncated,
            String outputRoot, String mapTable, Map<String, String> sourceTables,
            /** 실제로 받은 브로커 — 설정 모드, 또는 SIM 검증이면 수집기 내장 Mock */
            String broker
    ) {}

    // 진행 — 화면이 폴링한다({@link #progress()})
    private final AtomicInteger done = new AtomicInteger();
    private final AtomicInteger success = new AtomicInteger();
    private final AtomicInteger fail = new AtomicInteger();
    private final AtomicInteger skipped = new AtomicInteger();
    private final AtomicInteger active = new AtomicInteger();
    private volatile boolean cancelRequested;
    private volatile String current;
    private volatile int total;
    private volatile String execId;
    private volatile long startedAt;

    public boolean isRunning() {
        return running.get();
    }

    public Map<String, Object> progress() {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean r = running.get();
        m.put("running", r);
        m.put("execId", execId);
        m.put("total", total);
        m.put("done", done.get());
        m.put("success", success.get());
        m.put("fail", fail.get());
        m.put("skipped", skipped.get());
        m.put("active", active.get());
        m.put("current", current);
        m.put("cancelRequested", cancelRequested);
        m.put("elapsedMs", startedAt == 0 ? 0 : System.currentTimeMillis() - startedAt);
        return m;
    }

    /** 중단 — 처리 중인 건은 끝까지, 시작하지 않은 건은 '건너뜀'. */
    public boolean cancel() {
        if (!running.get()) {
            return false;
        }
        cancelRequested = true;
        return true;
    }

    /**
     * 한 번 돈다 — 동기. 끝날 때까지 돌아오지 않는다.
     *
     * @throws IllegalStateException 이미 돌고 있다
     */
    public ImageRunResult run(ImageRunRequest req) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("수용자 이미지 수집이 이미 돌고 있습니다 — " + execId);
        }
        try {
            return runInternal(req == null ? new ImageRunRequest(null, null, null, null, null, null, null, null, null) : req);
        } finally {
            running.set(false);
            current = null;
            active.set(0);
        }
    }

    private ImageRunResult runInternal(ImageRunRequest req) {
        long t0 = System.currentTimeMillis();
        boolean test = Boolean.TRUE.equals(req.testRun());
        String trigger = req.triggerBy() == null ? "MANUAL" : req.triggerBy();
        int workers = Math.max(1, Math.min(64, req.workers() != null ? req.workers() : props.workers()));
        long virtualMs = req.virtualLatencyMs() == null ? 0L : Math.max(0L, Math.min(600_000L, req.virtualLatencyMs()));
        boolean force = Boolean.TRUE.equals(req.force());
        // SIM 검증이면 수집기 내장 Mock 브로커로 받는다 — 설정 브로커(개발계 REST·DUMMY)는 SIM 원본을 읽을 수 없다
        XvarmBrokerClient via = Boolean.TRUE.equals(req.simBroker()) ? mockBroker : broker;
        String brokerLabel = via == mockBroker && !"MOCK".equals(broker.mode())
                ? "MOCK(SIM 검증 — 수집기 내장 · 설정 " + broker.mode() + " 는 쓰지 않음)" : via.mode();

        execId = "IMG-" + LocalDateTime.now().format(EXEC_ID) + (test ? "-TST" : "");
        startedAt = t0;
        cancelRequested = false;
        done.set(0);
        success.set(0);
        fail.set(0);
        skipped.set(0);
        active.set(0);
        total = 0;
        ImageMetrics m = new ImageMetrics();
        log.info("[Image] 시작 — execId={} · 워커 {} · 브로커 {} · 조건 corrNos={} prefix={} limit={} force={}{}", execId, workers,
                brokerLabel, req.corrNos() == null ? "전체" : req.corrNos().size() + "명", req.corrNoPrefix(), req.limit(), force,
                virtualMs > 0 ? " · 가상 지연 " + virtualMs + "ms" : "");

        // ── 1 · 최신 이미지 조회 ──────────────────────────────────────────
        long s = ImageMetrics.start();
        List<ImageTarget> latest = source.findLatest(
                new ImageSourceService.Selection(req.corrNos(), req.corrNoPrefix(), req.limit()));
        m.add(ImageStage.QUERY, s);
        // ── 2·3 · 문서ID · FILEKEY 추출 ─────────────────────────────────────
        s = ImageMetrics.start();
        List<ImageTarget> targets = source.attachFileKeys(latest);
        m.add(ImageStage.FILEKEY, s);
        total = targets.size();
        log.info("[Image] 대상 {}명 — FILEKEY 확보 {}명 · {}", targets.size(),
                targets.stream().filter(t -> t.fileKey() != null).count(), source.tables());

        // 이미 매핑된 사진 — 변경 없는 건은 다시 받지 않는다(매핑 조회 실패는 전건 다시 받기로)
        Map<String, InmatePhotoRepository.Mapped> mapped;
        try {
            mapped = repo.findMapped(targets.stream().map(ImageTarget::corrNo).toList());
        } catch (Exception e) {
            log.warn("[Image] 기존 매핑 조회 실패 — 전건 새로 받는다 ({})", e.getMessage());
            mapped = Map.of();
        }

        // ── 건마다 — 워커 N개 ──────────────────────────────────────────────
        List<ImageOutcome> outcomes = new ArrayList<>(targets.size());
        boolean canceled = false;
        if (!targets.isEmpty()) {
            Map<String, InmatePhotoRepository.Mapped> known = mapped;
            AtomicInteger seq = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(Math.min(workers, targets.size()), r -> {
                Thread th = new Thread(r, "image-worker-" + seq.incrementAndGet());
                th.setDaemon(true);
                return th;
            });
            List<Future<ImageOutcome>> futures = new ArrayList<>(targets.size());
            try {
                for (ImageTarget t : targets) {
                    futures.add(pool.submit(() -> {
                        if (cancelRequested) {
                            return count(ImageOutcome.skipped(t, CANCELED));
                        }
                        active.incrementAndGet();
                        current = t.shortId();
                        try {
                            return count(processOne(t, known.get(t.corrNo()), force, virtualMs, m, via));
                        } finally {
                            active.decrementAndGet();
                        }
                    }));
                }
            } finally {
                pool.shutdown();
            }
            for (int i = 0; i < futures.size(); i++) {
                outcomes.add(await(futures.get(i), targets.get(i)));
            }
            canceled = cancelRequested;
        }

        long ok = outcomes.stream().filter(ImageOutcome::isSuccess).count();
        long ng = outcomes.stream().filter(o -> "FAIL".equals(o.status())).count();
        long sk = outcomes.size() - ok - ng;
        long ins = outcomes.stream().filter(o -> "INSERTED".equals(o.mapResult())).count();
        long upd = outcomes.stream().filter(o -> "UPDATED".equals(o.mapResult())).count();
        long stl = outcomes.stream().filter(o -> "STALE".equals(o.mapResult())).count();
        long elapsed = System.currentTimeMillis() - t0;
        log.info("[Image] 종료{} — execId={} · 대상 {} · 성공 {}(신규 {} · 갱신 {} · 옛 사진 {}) · 실패 {} · 건너뜀 {} · {}ms",
                canceled ? "(중단됨)" : "", execId, outcomes.size(), ok, ins, upd, stl, ng, sk, elapsed);
        boolean truncated = outcomes.size() > OUTCOME_LIMIT;
        return new ImageRunResult(execId, trigger, workers, outcomes.size(), (int) ok, (int) ng, (int) sk,
                (int) ins, (int) upd, (int) stl, elapsed, canceled, m.snapshot(),
                truncated ? List.copyOf(outcomes.subList(0, OUTCOME_LIMIT)) : outcomes, truncated,
                store.outputRoot().toString().replace('\\', '/'), adminDb.table(AdminDb.PHOTO_TABLE), source.tables(),
                brokerLabel);
    }

    private ImageOutcome count(ImageOutcome o) {
        done.incrementAndGet();
        switch (o.status()) {
            case "SUCCESS" -> success.incrementAndGet();
            case "FAIL" -> fail.incrementAndGet();
            default -> skipped.incrementAndGet();
        }
        return o;
    }

    /** 한 건 — 받기 → (가상 지연) → 복호화 → 저장 → 매핑. 예외를 밖으로 던지지 않는다. */
    private ImageOutcome processOne(ImageTarget t, InmatePhotoRepository.Mapped cur, boolean force, long virtualMs,
                                    ImageMetrics m, XvarmBrokerClient via) {
        long t0 = System.currentTimeMillis();
        if (t.docId() == null) {
            m.error(ImageStage.FILEKEY);
            return ImageOutcome.fail(t, ImageStage.FILEKEY,
                    "공통파일기본(TB_SMSM_CMFI_BS)에 CMMN_FILE_ID=" + t.imageCmmnFileId() + " 가 없다", 0L);
        }
        if (t.fileKey() == null || t.fileKey().isBlank()) {
            m.error(ImageStage.FILEKEY);
            return ImageOutcome.fail(t, ImageStage.FILEKEY, "XVARM(ASYSCONTENTELEMENT)에 ELEMENTID=" + t.docId() + " 가 없다", 0L);
        }
        if (cur != null && cur.imageSn() > t.imageSn()) {
            return ImageOutcome.skipped(t, "더 최신 사진(순번 " + cur.imageSn() + ")이 이미 매핑돼 있다");
        }
        if (!force && cur != null && cur.imageSn() == t.imageSn() && Objects.equals(cur.fileKey(), t.fileKey())
                && cur.photoPath() != null && Files.isRegularFile(Path.of(cur.photoPath()))) {
            return ImageOutcome.skipped(t, "변경 없음 — 이미 매핑된 최신 사진");
        }

        Path dir = dirs.receiveDir(VoiceKind.MEET);   // 브로커 출력 폴더 = 접견 수신 폴더
        Path received = null;
        ImageStage step = ImageStage.ACQUIRE;
        try {
            // ── 브로커 수신 ──
            long s = ImageMetrics.start();
            watcher.clearStale(dir, t.receiveName());
            XvarmBrokerClient.ExtractResult r = via.extractFile(new XvarmBrokerClient.FileRequest(
                    t.docId(), t.fileKey(), t.requestId(execId), t.receiveName()));
            BrokerOutputCheck.mismatch(r.filePath(), dirs.receiveMeet()).ifPresent(reason -> {
                throw new IllegalStateException(reason);
            });
            received = watcher.awaitFile(dir, fileNameOf(r.filePath(), t.receiveName()));
            m.add(ImageStage.ACQUIRE, s);

            // ── 가상 처리 지연(성능 시험) ──
            if (virtualMs > 0) {
                step = ImageStage.VIRTUAL;
                s = ImageMetrics.start();
                sleep(virtualMs);
                m.add(ImageStage.VIRTUAL, s);
            }

            // ── 복호화 ──
            step = ImageStage.DECRYPT;
            s = ImageMetrics.start();
            byte[] plain = store.decrypt(t, Files.readAllBytes(received));
            m.add(ImageStage.DECRYPT, s);

            // ── 저장 ──
            step = ImageStage.SAVE;
            s = ImageMetrics.start();
            ImageFileStore.Saved saved = store.save(t, plain);
            m.add(ImageStage.SAVE, s);

            // ── DB 매핑 — 짧은 트랜잭션 하나 ──
            step = ImageStage.MAP;
            s = ImageMetrics.start();
            String path = saved.path().toString().replace('\\', '/');
            InmatePhotoRepository.MapResult mr = repo.upsert(new InmatePhotoRepository.PhotoRow(
                    t.corrNo(), t.imageSn(), t.imageCmmnFileId(), t.docId(), t.fileKey(), path, saved.size(),
                    saved.ext(), execId));
            m.add(ImageStage.MAP, s);
            return ImageOutcome.success(t, mr.name(), path, saved.size(), saved.ext(), System.currentTimeMillis() - t0);
        } catch (Exception e) {
            m.error(step);
            String reason = e.getClass().getSimpleName() + ": " + shorten(rootMessage(e));
            log.warn("[Image] 처리 실패 [{}] — {} ({})", step, t.shortId(), reason);
            return ImageOutcome.fail(t, step, reason, System.currentTimeMillis() - t0);
        } finally {
            // 받은 원본(암호문)은 성공·실패를 가리지 않고 지운다 — 사진은 저장소에만 남는다
            if (received != null) {
                try {
                    Files.deleteIfExists(received);
                } catch (Exception ignored) {
                    // 다음 요청 전 clearStale 이 치운다
                }
            }
        }
    }

    private void sleep(long ms) throws InterruptedException {
        long until = System.currentTimeMillis() + ms;
        for (long left = ms; left > 0; left = until - System.currentTimeMillis()) {
            if (cancelRequested) {
                throw new IllegalStateException("중단됨 — 가상 처리 지연 중 멈췄습니다");
            }
            Thread.sleep(Math.min(left, 200L));
        }
    }

    private static ImageOutcome await(Future<ImageOutcome> f, ImageTarget t) {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ImageOutcome.fail(t, ImageStage.ACQUIRE, "InterruptedException: 결과 대기 중 중단됨", 0L);
        } catch (ExecutionException e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            return ImageOutcome.fail(t, ImageStage.ACQUIRE, c.getClass().getSimpleName() + ": " + shorten(c.getMessage()), 0L);
        }
    }

    private static String fileNameOf(String path, String fallback) {
        if (path == null || path.isBlank()) {
            return fallback;
        }
        int i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String tail = i >= 0 ? path.substring(i + 1) : path;
        return tail.isBlank() ? fallback : tail;
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c == t ? String.valueOf(t.getMessage()) : c.getClass().getSimpleName() + ": " + c.getMessage();
    }

    private static String shorten(String s) {
        if (s == null) {
            return "(사유 없음)";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }
}
