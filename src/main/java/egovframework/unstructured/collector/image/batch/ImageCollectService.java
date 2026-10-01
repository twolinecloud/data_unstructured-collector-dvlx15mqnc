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
import java.util.regex.Pattern;

/**
 * <b>수용자 이미지 수집</b> — 보라미 조회 → FILEKEY 추출 → 브로커 수신 → 복호화(접견과 같은 모듈) → 저장 → Admin DB 매핑.
 *
 * <ol>
 *   <li>최신 이미지 조회 · FILEKEY 추출 — {@link ImageSourceService}(페이지 단위, 배치 앞에서 한 번)</li>
 *   <li>건마다(워커 N개) — 브로커 {@code POST /api/v1/xvarm/extract} 로 수신 폴더에 받기 → 복호화 → 저장 → 매핑</li>
 * </ol>
 *
 * <p><b>재실행해도 안전하다(멱등)</b> — 변경 없는 건은 다시 받지 않는다: 매핑 테이블의 순번·FILEKEY 가 같고 저장
 * 파일이 있으면 '건너뜀'({@code force} 로 다시 받게 할 수 있다). 다시 받는 건은 저장 파일을 원자적으로 덮어쓰고
 * 매핑을 UPSERT 한다 — 키 중복 오류 없이 있으면 갱신 · 없으면 넣기. 더 최신 사진이 이미 매핑된 수용자는 덮지 않는다.</p>
 *
 * <p><b>건마다 격리한다</b> — 한 건의 수신·복호화·저장·매핑 오류(Error 포함)는 그 건만 '실패'로 남기고 로그를 쓴 뒤
 * 다음 건을 계속한다. 워커 스레드도, 배치 전체도 멈추지 않는다.</p>
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

    /**
     * 실행 ID 시각 — 밀리초까지. 초 단위면 연달아 돈 두 배치(신규 실행 → 곧바로 재실행)가 같은 ID 가 되어
     * 매핑의 {@code last_batch_exec_id} 로 "이번 실행이 쓴 행"을 가릴 수 없고, 브로커 요청 키도 겹친다.
     * {@code IMG-yyyyMMdd-HHmmssSSS-TST} = 26자(컬럼 30자).
     */
    private static final DateTimeFormatter EXEC_ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS");
    private static final String CANCELED = "중단됨 — 사용자가 배치를 멈췄습니다";
    /** 결과에 싣는 건별 목록 상한 — 수천 건 배치의 응답이 커지지 않게. */
    private static final int OUTCOME_LIMIT = 300;
    /** 키 중복 오류 — PostgreSQL · H2 · 스프링 예외 이름. 멱등 UPSERT 면 나오지 않아야 한다. */
    private static final Pattern DUP_KEY = Pattern.compile(
            "duplicate key|DuplicateKey|primary key violation|unique constraint|Unique index", Pattern.CASE_INSENSITIVE);

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
    /** 단계별 실행 기록(6번 탭) — 조회 첫 페이지 · 첫 건(표본)의 실제 SQL · cURL · 파일 명령과 결과. */
    private final ImageTrace trace;
    /** 브로커 주소 — 기록의 cURL 에 쓴다. */
    private final egovframework.unstructured.collector.common.config.VoiceModeState modeState;

    private final AtomicBoolean running = new AtomicBoolean();
    /** 이번 실행에서 표본(첫 건)을 이미 잡았는가 — 워커가 여럿이어도 한 건만 적는다. */
    private final AtomicBoolean sampleTaken = new AtomicBoolean();

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
            @Schema(hidden = true) Boolean simBroker,
            /** 의도적 실패(주입) — 교정번호 → 실패시킬 단계. 시험 실행({@code testRun})에서만 쓴다 */
            @Schema(hidden = true) Map<String, ImageStage> injectFailures
    ) {}

    /** 한 번의 결과. */
    public record ImageRunResult(
            String execId, String triggerBy, int workers, int total, int success, int fail, int skipped,
            int inserted, int updated, int stale, long elapsedMs, boolean canceled,
            List<Map<String, Object>> stages, List<ImageOutcome> outcomes, boolean outcomesTruncated,
            String outputRoot, String mapTable, Map<String, String> sourceTables,
            /** 실제로 받은 브로커 — 설정 모드, 또는 SIM 검증이면 수집기 내장 Mock */
            String broker,
            /** 실패 중 의도적 실패(주입) 건수 — 실제 오류는 {@code fail - injectedFail} */
            int injectedFail,
            /** 실패 단계별 건수(단계 순) */
            Map<String, Integer> failByStage,
            /** 키 중복(PK) 오류로 실패한 건수 — 멱등 UPSERT 면 늘 0 이어야 한다 */
            int dupKeyFail,
            /** 매핑 방식 — ON CONFLICT(PostgreSQL) 또는 행 잠금 + INSERT/UPDATE(H2) */
            String upsertMode
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
            return runInternal(req == null ? new ImageRunRequest(null, null, null, null, null, null, null, null, null, null) : req);
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
        // 의도적 실패(주입) — 시험 실행에서만. 실제 수집 API 로는 켤 수 없다
        Map<String, ImageStage> inject = test && req.injectFailures() != null ? Map.copyOf(req.injectFailures()) : Map.of();

        execId = "IMG-" + LocalDateTime.now().format(EXEC_ID) + (test ? "-TST" : "");
        trace.begin(execId);
        sampleTaken.set(false);
        startedAt = t0;
        cancelRequested = false;
        done.set(0);
        success.set(0);
        fail.set(0);
        skipped.set(0);
        active.set(0);
        total = 0;
        ImageMetrics m = new ImageMetrics();
        log.info("[Image] 시작 — execId={} · 워커 {} · 브로커 {} · 조건 corrNos={} prefix={} limit={} force={}{}{}", execId, workers,
                brokerLabel, req.corrNos() == null ? "전체" : req.corrNos().size() + "명", req.corrNoPrefix(), req.limit(), force,
                virtualMs > 0 ? " · 가상 지연 " + virtualMs + "ms" : "",
                inject.isEmpty() ? "" : " · 의도적 실패 주입 " + inject.size() + "건");

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
                            return count(processOne(t, known.get(t.corrNo()), force, virtualMs, m, via, inject.get(t.corrNo())));
                        } catch (Throwable e) {
                            // processOne 은 예외를 밖으로 던지지 않는다 — 그래도 새면 이 건만 실패로 두고 다음 건을 계속한다
                            log.error("[Image] 처리 중 예기치 못한 오류 — {} ({})", t.shortId(), e.toString(), e);
                            return count(ImageOutcome.fail(t, ImageStage.ACQUIRE, e.getClass().getSimpleName() + ": "
                                    + shorten(rootMessage(e)), 0L));
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
        long inj = outcomes.stream().filter(ImageOutcome::injected).count();
        long dup = outcomes.stream().filter(o -> o.isFail() && o.errMsg() != null && DUP_KEY.matcher(o.errMsg()).find()).count();
        Map<String, Integer> byStage = new LinkedHashMap<>();
        for (ImageStage st : ImageStage.values()) {
            int n = (int) outcomes.stream().filter(o -> o.isFail() && o.failedAt() == st).count();
            if (n > 0) {
                byStage.put(st.name(), n);
            }
        }
        long elapsed = System.currentTimeMillis() - t0;
        log.info("[Image] 종료{} — execId={} · 대상 {} · 성공 {}(신규 {} · 갱신 {} · 옛 사진 {}) · 실패 {}{} · 건너뜀 {} · {}ms",
                canceled ? "(중단됨)" : "", execId, outcomes.size(), ok, ins, upd, stl, ng,
                inj > 0 ? "(주입 " + inj + " · 실제 " + (ng - inj) + ")" : "", sk, elapsed);
        boolean truncated = outcomes.size() > OUTCOME_LIMIT;
        return new ImageRunResult(execId, trigger, workers, outcomes.size(), (int) ok, (int) ng, (int) sk,
                (int) ins, (int) upd, (int) stl, elapsed, canceled, m.snapshot(),
                truncated ? List.copyOf(outcomes.subList(0, OUTCOME_LIMIT)) : outcomes, truncated,
                store.outputRoot().toString().replace('\\', '/'), adminDb.table(AdminDb.PHOTO_TABLE), source.tables(),
                brokerLabel, (int) inj, byStage, (int) dup, repo.upsertMode());
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

    /**
     * 한 건 — 받기 → (가상 지연) → 복호화 → 저장 → 매핑. 예외(Error 포함)를 밖으로 던지지 않는다.
     *
     * @param injectAt 의도적 실패(주입) 단계 — 그 단계의 일을 한 뒤 실패시킨다(매핑은 커밋 전 롤백). 보통 null
     */
    private ImageOutcome processOne(ImageTarget t, InmatePhotoRepository.Mapped cur, boolean force, long virtualMs,
                                    ImageMetrics m, XvarmBrokerClient via, ImageStage injectAt) {
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
        // 표본 — 이번 실행에서 처음 수신까지 온 한 건만 단계별 실행 기록을 남긴다
        boolean sample = sampleTaken.compareAndSet(false, true);
        try {
            // ── 브로커 수신 ──
            long s = ImageMetrics.start();
            watcher.clearStale(dir, t.receiveName());
            XvarmBrokerClient.FileRequest fr = new XvarmBrokerClient.FileRequest(
                    t.docId(), t.fileKey(), t.requestId(execId), t.receiveName());
            XvarmBrokerClient.ExtractResult r = via.extractFile(fr);
            if (sample) {
                traceExtract(t, via, fr, r);
            }
            BrokerOutputCheck.mismatch(r.filePath(), dirs.receiveMeet()).ifPresent(reason -> {
                throw new IllegalStateException(reason);
            });
            received = watcher.awaitFile(dir, fileNameOf(r.filePath(), t.receiveName()), () -> cancelRequested);
            if (sample) {
                trace.add(ImageTrace.Step.RECEIVE, "수신 폴더 도착 확인 — 쓰기가 끝날 때까지(크기 안정) 기다린 뒤", "SHELL",
                        "ls -la " + slash(received), lsLine(received));
            }
            m.add(ImageStage.ACQUIRE, s);
            injectIf(injectAt, ImageStage.ACQUIRE);

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
            byte[] cipher = Files.readAllBytes(received);
            byte[] plain = store.decrypt(t, cipher);
            m.add(ImageStage.DECRYPT, s);
            if (sample) {
                trace.add(ImageTrace.Step.RECEIVE, "복호화 — 접견과 같은 RVS 키(AES/CBC) · 결과가 이미지인지 매직 넘버로 확인", "CODE",
                        "MediaDecryptor.decrypt(" + slash(received) + ")  // CMMN_FILE_ENC_YN=" + (t.encrypted() ? "Y" : "N"),
                        "입력 " + cipher.length + " bytes → 출력 " + plain.length + " bytes · 시작 바이트 " + hex(plain, 4)
                                + " → " + String.valueOf(egovframework.unstructured.collector.image.model.ImageFormat.detect(plain)));
            }
            injectIf(injectAt, ImageStage.DECRYPT);

            // ── 저장 ──
            step = ImageStage.SAVE;
            s = ImageMetrics.start();
            ImageFileStore.Saved saved = store.save(t, plain);
            m.add(ImageStage.SAVE, s);
            if (sample) {
                trace.add(ImageTrace.Step.MAP, "저장 — 임시 파일에 쓰고 원자적으로 옮김(같은 수용자의 옛 사진 파일은 지움)", "SHELL",
                        "ls -la " + slash(saved.path()) + "\nhead -c 4 " + slash(saved.path()) + " | od -An -tx1",
                        lsLine(saved.path()) + "\n " + hex(plain, 4));
            }
            injectIf(injectAt, ImageStage.SAVE);   // 저장 파일은 남는다 — 재실행이 원자적으로 덮어쓴다

            // ── DB 매핑 — 짧은 트랜잭션 하나(UPSERT). 돌아오면 커밋·반납이 끝나 있다 ──
            step = ImageStage.MAP;
            s = ImageMetrics.start();
            String path = saved.path().toString().replace('\\', '/');
            InmatePhotoRepository.PhotoRow row = new InmatePhotoRepository.PhotoRow(
                    t.corrNo(), t.imageSn(), t.imageCmmnFileId(), t.docId(), t.fileKey(), path, saved.size(),
                    saved.ext(), execId);
            InmatePhotoRepository.MapResult mr;
            try {
                mr = repo.upsert(row, injectAt == ImageStage.MAP ? () -> injectIf(injectAt, ImageStage.MAP) : null);
            } catch (RuntimeException e) {
                if (sample) {
                    trace.add(ImageTrace.Step.MAP, "DB 매핑 UPSERT — 실패(트랜잭션 롤백 · 커넥션 반납)", "SQL",
                            repo.upsertSqlFor(row, java.sql.Timestamp.valueOf(LocalDateTime.now().withNano(0))),
                            e.getClass().getSimpleName() + ": " + shorten(rootMessage(e)));
                }
                throw e;
            }
            m.add(ImageStage.MAP, s);
            if (sample) {
                trace.add(ImageTrace.Step.MAP, "DB 매핑 UPSERT — 한 트랜잭션(커밋 뒤 커넥션 반납) · " + repo.upsertMode(), "SQL",
                        repo.upsertSqlFor(row, java.sql.Timestamp.valueOf(LocalDateTime.now().withNano(0))),
                        "→ " + mr.name() + (mr == InmatePhotoRepository.MapResult.INSERTED ? " (신규 INSERT)"
                                : mr == InmatePhotoRepository.MapResult.UPDATED ? " (UPSERT 덮어쓰기)" : " (옛 사진 — 덮지 않음)"));
                Map<String, Object> found = repo.find(t.corrNo());
                trace.add(ImageTrace.Step.MAP, "매핑 확인 — 방금 쓴 행", "SQL", repo.findSqlFor(t.corrNo()),
                        ImageTrace.table(List.of("corr_no", "image_sn", "photo_path", "file_size", "file_ext", "last_batch_exec_id",
                                "reg_dtm", "mod_dtm"), found.isEmpty() ? List.of() : List.of(found), found.isEmpty() ? 0 : 1, 1));
            }
            return ImageOutcome.success(t, mr.name(), path, saved.size(), saved.ext(), System.currentTimeMillis() - t0);
        } catch (InjectedFailureException e) {
            m.error(step);
            if (sample) {
                traceFailure(step, "의도적 실패(주입) — 이 건은 롤백되고 다음 건을 계속한다", e.getMessage());
            }
            log.info("[Image] 의도적 실패(주입) [{}] — {}", step, t.shortId());
            return ImageOutcome.injectedFail(t, step, e.getMessage(), System.currentTimeMillis() - t0);
        } catch (Exception | Error e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            m.error(step);
            String reason = e.getClass().getSimpleName() + ": " + shorten(rootMessage(e));
            if (sample) {
                traceFailure(step, "처리 실패 — 다음 건을 계속한다", reason);
            }
            log.warn("[Image] 처리 실패 [{}] — {} ({}) · 다음 건을 계속한다", step, t.shortId(), reason);
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

    /** ④ 브로커 추출 요청을 cURL 로 적는다 — 내장 Mock 이면 같은 요청을 수집기 안에서 처리한 것이다. */
    private void traceExtract(ImageTarget t, XvarmBrokerClient via, XvarmBrokerClient.FileRequest fr,
                              XvarmBrokerClient.ExtractResult r) {
        String body = "{\"docId\":\"" + fr.docId() + "\",\"fileKey\":\"" + fr.fileKey() + "\",\"requestId\":\""
                + fr.requestId() + "\",\"fileName\":\"" + fr.fileName() + "\"}";
        boolean internal = via == mockBroker;
        String base = internal ? "http://(수집기 내장 Mock)" : String.valueOf(modeState.brokerBaseUrl()).replaceAll("/+$", "");
        String cmd = (internal ? "# 수집기 내장 Mock 브로커 — 아래 요청을 수집기 안에서 처리한다(SIM 원본은 수집기 저장소에만 있다)\n" : "")
                + "curl -s -X POST '" + base + "/api/v1/xvarm/extract' \\\n  -H 'Content-Type: application/json' \\\n  -d '"
                + body + "'";
        trace.add(ImageTrace.Step.RECEIVE, "브로커 추출 요청 — FILEKEY 의 원본을 수신 폴더로(" + via.mode() + ")", "HTTP", cmd,
                "{\"requestId\":\"" + r.requestId() + "\",\"status\":\"DONE\",\"filePath\":\""
                        + String.valueOf(r.filePath()).replace('\\', '/') + "\",\"fileSize\":" + r.fileSize() + "}");
    }

    private void traceFailure(ImageStage step, String title, String reason) {
        ImageTrace.Step at = step == ImageStage.ACQUIRE || step == ImageStage.DECRYPT ? ImageTrace.Step.RECEIVE
                : step == ImageStage.FILEKEY ? ImageTrace.Step.FILEKEY : ImageTrace.Step.MAP;
        trace.add(at, "[" + step.name() + "] " + title, "CODE", "# 단계 " + step.name() + " 에서 예외", reason);
    }

    private static String slash(Path p) {
        return p.toString().replace('\\', '/');
    }

    /** {@code ls -la} 한 줄 모양 — 크기 · 수정 시각 · 경로. */
    private static String lsLine(Path p) {
        try {
            return "-rw-r--r--  " + Files.size(p) + "  " + Files.getLastModifiedTime(p).toInstant()
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().withNano(0) + "  " + slash(p);
        } catch (Exception e) {
            return "ls: " + slash(p) + ": " + e.getMessage();
        }
    }

    private static String hex(byte[] b, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, b.length); i++) {
            sb.append(i == 0 ? "" : " ").append(String.format("%02x", b[i]));
        }
        return sb.toString();
    }

    private static void injectIf(ImageStage injectAt, ImageStage here) {
        if (injectAt == here) {
            throw new InjectedFailureException(here);
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
