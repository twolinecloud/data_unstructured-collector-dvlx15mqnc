package egovframework.unstructured.collector.image.batch;

import egovframework.unstructured.collector.common.broker.BrokerOutputCheck;
import egovframework.unstructured.collector.common.broker.MockXvarmBrokerClient;
import egovframework.unstructured.collector.common.broker.XvarmBrokerClient;
import egovframework.unstructured.collector.common.config.VoiceProperties;
import egovframework.unstructured.collector.common.logging.LogCollectorClient;
import egovframework.unstructured.collector.common.model.StepType;
import egovframework.unstructured.collector.common.util.InmatePidGenerator;
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
import java.util.function.BiConsumer;
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
 * <p>한 번에 하나만 돈다.</p>
 *
 * <p><b>처리 이력 — 로그 컬렉터 T1 · T2 · T4</b>(2026-10-02, 음성과 같은 표). 실행마다 T1 을 하나 연다 — 작업
 * {@code image.job-id}(기본 {@code IMAGE_COLLECT}, 작업명 '수용자 이미지 수집') · 데이터 구분 UNSTRUCTURED(채번 UNS) ·
 * 시험 실행이면 {@code TEST_BATCH}(채번 TST). 수집 구간이 없는 배치라 T1 구간은 비운다 — 음성 [바로 실행] 워터마크에 끼지 않는다.
 * T2 는 비정형 3단계({@link StepType}) — COLLECT(조회 · FILEKEY · 브로커 수신)는 시작에, ANALYZE(복호화 · 이미지 확인)와
 * SEND(저장 · Admin DB 매핑)는 처음 닿을 때 연다. T4 는 처리한 사진 1장 = 1행(건너뛴 건은 남기지 않는다 — 음성과 같다).
 * 로그 컬렉터가 꺼져 있으면 실행 ID 는 음성과 같은 자리의 로컬 {@code yyyyMMdd + UNS|TST + HHmmssSSS} 이고 아무것도 보내지 않는다.</p>
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class ImageCollectService {

    /**
     * 실행 ID 시각 — 밀리초까지. 초 단위면 연달아 돈 두 배치(신규 실행 → 곧바로 재실행)가 같은 ID 가 되어
     * 매핑의 {@code last_batch_exec_id} 로 "이번 실행이 쓴 행"을 가릴 수 없고, 브로커 요청 키도 겹친다.
     * 로컬 ID 는 음성과 같은 모양 {@code yyyyMMdd + UNS|TST + HHmmssSSS} = 20자(컬럼 30자) — 9~11번째 자리가 작업코드라
     * 시험 이력 정리(TST)가 같은 규칙으로 걸러진다. (2026-10-02 전에는 {@code IMG-yyyyMMdd-HHmmssSSS[-TST]})
     */
    private static final DateTimeFormatter EXEC_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter EXEC_TIME = DateTimeFormatter.ofPattern("HHmmssSSS");
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
    /**
     * 더미 키 표식 장애 — 교정번호에 표식(CF·AF·SF)을 단 건을 그 단계에서 한 번만 실패시킨다(수신 · 복호화 · 매핑).
     * 의도적 실패(주입)와 달리 실제 수집 API 로도 동작한다 — 대시보드 더미가 실제 배치에서 실패해야 하기 때문이다. 운영 제외.
     */
    private final egovframework.unstructured.collector.mock.ScenarioFaults scenarioFaults;
    /** 처리 이력 T1 · T2 · T4 — 음성과 같은 로그 컬렉터 API. */
    private final LogCollectorClient logCollector;
    /** T4 INMATE_PID — 교정번호를 그대로 남기지 않는다(음성과 같은 가명화). */
    private final InmatePidGenerator pidGenerator;
    /** 데이터 구분(C01) · 시험 작업 ID — 음성 설정을 그대로 쓴다(비정형 단일 진입점). */
    private final VoiceProperties voiceProps;

    /** T1 작업명 — 컬렉터 {@code JobId} 열거형에 없는 작업이라 이름을 함께 보낸다. 긴급 재처리가 이 이름으로 이미지 배치를 알아본다. */
    public static final String JOB_NM = "수용자 이미지 수집";

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
        return run(req, null);
    }

    /**
     * 위와 같되 실행 ID 를 받자마자 알린다 — admin 연동(긴급 재처리)이 접수 응답에 실제 execId 를 싣는 데 쓴다.
     *
     * @param onExecId {@code (execId, 로그 컬렉터 채번인가)}. null 이면 부르지 않는다
     */
    public ImageRunResult run(ImageRunRequest req, BiConsumer<String, Boolean> onExecId) {
        return guarded(req, onExecId, null);
    }

    /**
     * 긴급 재처리 체인 — 로그 컬렉터가 <b>같은 EXEC_ID 로 다시 연</b> 이미지 배치로 돈다. 이미지는 날짜 구간이 없어 지금 시점 전체
     * 수용자의 최신 사진을 다시 보고, 원배치에서 실패였던 수용자가 이제 최신 사진으로 매핑돼 있으면(이번에 처리됐든 다른 실행이
     * 처리했든) 그 T4 행을 성공으로 덮어쓴다(V19). T1 은 T4 최종 상태로 마감한다.
     *
     * @param failedRows 원배치 T4 의 실패 행({@code rec_file_id} · {@code inmate_pid} · {@code file_nm})
     */
    public ImageRunResult runReopened(ImageRunRequest req, String reopenedExecId, List<com.fasterxml.jackson.databind.JsonNode> failedRows) {
        return guarded(req, null, new Reopen(reopenedExecId, failedRows == null ? List.of() : failedRows));
    }

    /** 다시 연 배치 — 실행 ID 와 원배치 T4 실패 행. */
    private record Reopen(String execId, List<com.fasterxml.jackson.databind.JsonNode> failedRows) {}

    private ImageRunResult guarded(ImageRunRequest req, BiConsumer<String, Boolean> onExecId, Reopen reopen) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("수용자 이미지 수집이 이미 돌고 있습니다 — " + execId);
        }
        try {
            return runInternal(req == null ? new ImageRunRequest(null, null, null, null, null, null, null, null, null, null) : req,
                    onExecId, reopen);
        } finally {
            running.set(false);
            current = null;
            active.set(0);
        }
    }

    private ImageRunResult runInternal(ImageRunRequest req, BiConsumer<String, Boolean> onExecId, Reopen reopen) {
        long t0 = System.currentTimeMillis();
        // SIM(SIMIMG…)은 시뮬레이터 시험 전용이다(2026-10-02 결정) — 그 접두로 돌리면 늘 시험 실행(TST 이력)으로 남긴다
        boolean test = Boolean.TRUE.equals(req.testRun()) || isSimPrefix(req.corrNoPrefix());
        String trigger = req.triggerBy() == null ? "MANUAL" : req.triggerBy();
        int workers = Math.max(1, Math.min(64, req.workers() != null ? req.workers() : props.workers()));
        long virtualMs = req.virtualLatencyMs() == null ? 0L : Math.max(0L, Math.min(600_000L, req.virtualLatencyMs()));
        boolean force = Boolean.TRUE.equals(req.force());
        // SIM 검증이면 수집기 내장 Mock 브로커로 받는다 — 설정 브로커(개발계 REST·DUMMY)는 SIM 원본을 읽을 수 없다
        XvarmBrokerClient via = Boolean.TRUE.equals(req.simBroker()) ? mockBroker : broker;
        String brokerLabel = via == mockBroker && !"MOCK".equals(broker.mode())
                ? "MOCK(SIM 검증 — 수집기 내장 · 설정 " + broker.mode() + " 는 쓰지 않음)" : via.mode();
        // 의도적 실패(주입) — 명시적 시험 실행(6번 탭)에서만. 실제 수집 API 로는 켤 수 없다
        //   (SIM 접두는 이력만 시험(TST)으로 남길 뿐 주입을 허용하지 않는다)
        Map<String, ImageStage> inject = Boolean.TRUE.equals(req.testRun()) && req.injectFailures() != null
                ? Map.copyOf(req.injectFailures()) : Map.of();

        // ── T1 — 로그 컬렉터가 채번한다(UNSTRUCTURED → UNS · 시험 → TST). 미연동이면 로컬 ID(같은 자리에 UNS/TST) ──
        String collectorExecId = reopen != null ? reopen.execId()
                : logCollector.createBatch(test ? voiceProps.batch().testJobId() : props.jobId(),
                        test ? JOB_NM + "(시험)" : JOB_NM, voiceProps.batch().dataTypeCd(), execTypeOf(trigger), trigger, null, null);
        LocalDateTime now = LocalDateTime.now();
        execId = collectorExecId != null ? collectorExecId : now.format(EXEC_DATE) + (test ? "TST" : "UNS") + now.format(EXEC_TIME);
        if (onExecId != null) {
            try {
                onExecId.accept(execId, collectorExecId != null);
            } catch (RuntimeException e) {
                log.warn("[Image] execId 알림 실패(배치는 계속) — {}", e.getMessage());
            }
        }
        StepLog steps = new StepLog(execId);
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
        List<ImageTarget> targets;
        try {
            long s = ImageMetrics.start();
            List<ImageTarget> latest = source.findLatest(
                    new ImageSourceService.Selection(req.corrNos(), req.corrNoPrefix(), req.limit()));
            m.add(ImageStage.QUERY, s);
            // ── 2·3 · 문서ID · FILEKEY 추출 ─────────────────────────────────────
            s = ImageMetrics.start();
            targets = source.attachFileKeys(latest);
            m.add(ImageStage.FILEKEY, s);
        } catch (RuntimeException e) {
            // 원천 조회에서 죽었다 — 열어 둔 T2 COLLECT · T1 을 실패로 닫고 던진다(RUNNING 으로 남기지 않는다)
            String err = LogCollectorClient.FileProcReq.errStackOf(StepType.COLLECT,
                    e.getClass().getSimpleName() + ": " + shorten(rootMessage(e)));
            steps.failCollect(err);
            logCollector.finishBatch(execId, "FAIL", (int) ((System.currentTimeMillis() - t0) / 1000), 0L, 0L, 0L, err);
            throw e;
        }
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
                            return count(processOne(t, known.get(t.corrNo()), force, virtualMs, m, via, inject.get(t.corrNo()),
                                    steps));
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
        // ── 처리 이력 — T2 마감(3단계 건수) · T4(처리한 사진 1장 = 1행) · T1 마감 ─────────────────────
        writeLogs(steps, targets, outcomes, canceled, t0, m, reopen);

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

    /** SIM 사진 접두({@code SIMIMG…})로만 도는 실행인가. */
    private static boolean isSimPrefix(String prefix) {
        return prefix != null && prefix.trim().startsWith(egovframework.unstructured.collector.image.sim.ImageSimulationService.PREFIX);
    }

    /** T1 EXEC_TYPE_CD — 스케줄러가 부른 것만 SCHEDULED, 나머지(바로 실행 · API · 시뮬레이터 · 재처리)는 MANUAL. */
    static String execTypeOf(String triggerBy) {
        return triggerBy != null && triggerBy.toUpperCase(java.util.Locale.ROOT).startsWith("SCHEDULE") ? "SCHEDULED" : "MANUAL";
    }

    /**
     * 처리 이력을 남긴다 — T2 3단계 마감 · T4 · T1 마감. 로그 컬렉터가 꺼져 있으면 아무것도 보내지 않는다.
     *
     * <p>건수 규칙은 음성과 같다: 건너뛴 건(변경 없음 · 더 최신 사진 · 중단)은 T2 · T4 에 넣지 않는다. 각 단계의 in 은 앞 단계의 out,
     * err 는 그 단계에서 실패한 건, SEND 의 out 이 성공 건수다. T1 대상 = 조회한 사진 수(건너뜀 포함), 성공 · 실패 = T4 와 같다
     * (정합성 T1.SUCCESS_CNT == Σ T4 SUCCESS).</p>
     */
    private void writeLogs(StepLog steps, List<ImageTarget> targets, List<ImageOutcome> outcomes, boolean canceled, long t0,
                           ImageMetrics m, Reopen reopen) {
        long processed = outcomes.stream().filter(o -> !"SKIPPED".equals(o.status())).count();
        Map<StepType, Long> failAt = new java.util.EnumMap<>(StepType.class);
        for (ImageOutcome o : outcomes) {
            if (o.isFail()) {
                failAt.merge(stepOf(o), 1L, Long::sum);
            }
        }
        long ok = outcomes.stream().filter(ImageOutcome::isSuccess).count();
        long ng = outcomes.stream().filter(ImageOutcome::isFail).count();
        long collectErr = failAt.getOrDefault(StepType.COLLECT, 0L);
        long analyzeErr = failAt.getOrDefault(StepType.ANALYZE, 0L);
        long sendErr = failAt.getOrDefault(StepType.SEND, 0L);
        String topErr = topErrStack(outcomes);
        steps.finishAll(processed, collectErr, processed - collectErr, analyzeErr, processed - collectErr - analyzeErr, sendErr,
                ok, topErr, System.currentTimeMillis() - t0, m);

        List<LogCollectorClient.FileProcReq> rows = new ArrayList<>((int) processed);
        for (int i = 0; i < outcomes.size(); i++) {
            ImageOutcome o = outcomes.get(i);
            if ("SKIPPED".equals(o.status())) {
                continue;   // 이번 배치가 처리한 건이 아니다 — 넣으면 정합성이 어긋난다
            }
            rows.add(fileProcOf(i < targets.size() ? targets.get(i) : null, o));
        }
        if (reopen != null) {
            rows.addAll(resolvedRows(reopen, outcomes, rows));
        }
        logCollector.createFileProcs(execId, rows);

        String sts = canceled ? "CANCELED" : ng == 0 ? "SUCCESS" : ok > 0 ? "PARTIAL" : "FAIL";
        long target = outcomes.size();
        if (reopen != null) {
            // 같은 실행 ID 로 다시 돈 배치 — T1 은 T4 최종 상태(원래 성공 + 해결된 실패)로 마감한다
            Map<String, Long> by = logCollector.fileStatusCounts(execId);
            if (by != null) {
                ok = by.getOrDefault("SUCCESS", 0L);
                ng = by.getOrDefault("FAIL", 0L);
                target = Math.max(target, by.values().stream().mapToLong(Long::longValue).sum());
                sts = canceled ? "CANCELED" : ng == 0 ? "SUCCESS" : ok > 0 ? "PARTIAL" : "FAIL";
                log.info("[Image] 다시 연 배치 {} — T4 최종 상태로 T1 마감: {} · 성공 {} · 실패 {}", execId, sts, ok, ng);
            }
        }
        logCollector.finishBatch(execId, sts, (int) ((System.currentTimeMillis() - t0) / 1000),
                target, ok, ng, ng == 0 ? null : topErr);
    }

    /**
     * 다시 연 배치 — 원배치에서 실패였던 수용자가 지금 최신 사진으로 매핑돼 있으면(이번에 성공 · '변경 없음' · '더 최신 사진') 그 T4 행을
     * 성공으로 덮어쓸 행. 이번 실행이 같은 (수용자 · 파일)을 이미 적었으면 넣지 않는다. 이번에도 실패했거나 원천에서 사라진 수용자는
     * 그대로 실패로 남는다(해결된 만큼만 닫힌다).
     */
    private List<LogCollectorClient.FileProcReq> resolvedRows(Reopen reopen, List<ImageOutcome> outcomes,
                                                             List<LogCollectorClient.FileProcReq> written) {
        java.util.Set<String> resolved = new java.util.HashSet<>();
        for (ImageOutcome o : outcomes) {
            boolean mapped = o.isSuccess() || ("SKIPPED".equals(o.status()) && !CANCELED.equals(o.errMsg()));
            if (mapped && o.corrNo() != null) {
                resolved.add(pidGenerator.of(o.corrNo()));
            }
        }
        java.util.Set<String> already = new java.util.HashSet<>();
        written.forEach(r -> already.add(r.inmatePid() + "|" + r.recFileId()));
        List<LogCollectorClient.FileProcReq> out = new ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode f : reopen.failedRows()) {
            String pid = f.path("inmate_pid").asText(null);
            String rec = f.path("rec_file_id").asText(null);
            if (pid == null || rec == null || !resolved.contains(pid) || !already.add(pid + "|" + rec)) {
                continue;
            }
            out.add(new LogCollectorClient.FileProcReq(rec, null, f.path("file_nm").asText(null), pid, 0L, "SUCCESS", null,
                    StepType.SEND.name()));
        }
        if (!out.isEmpty()) {
            log.info("[Image] 다시 연 배치 {} — 원래 실패 {}건 중 {}건이 지금 최신 사진으로 매핑돼 있어 성공으로 덮어쓴다",
                    reopen.execId(), reopen.failedRows().size(), out.size());
        }
        return out;
    }

    /** 실패한 세부 단계의 3단계 — 단계가 비어 있으면(워커 밖 예외) 수집으로 본다. */
    private static StepType stepOf(ImageOutcome o) {
        return o.failedAt() == null ? StepType.COLLECT : o.failedAt().stepType();
    }

    /**
     * T4 한 행 — 사진 1장.
     *
     * <ul>
     *   <li>REC_FILE_ID — 이미지 공통파일ID({@code IMAGE_CMMN_FILE_ID}, 사진 원본 키). 없으면 문서ID, 그것도 없으면 가명ID#순번</li>
     *   <li>FILE_PATH · FILE_NM — 성공이면 <b>저장한</b> 사진의 폴더 · 이름, 실패면 원본 파일명(저장하지 못했다)</li>
     *   <li>STEP_TYPE_CD — 성공 SEND · 실패면 실패한 단계(로그 컬렉터에 컬럼이 생기면 적재 — 그 전까지 ERR_STACK 의 {@code [단계]})</li>
     * </ul>
     */
    private LogCollectorClient.FileProcReq fileProcOf(ImageTarget t, ImageOutcome o) {
        String pid = pidGenerator.of(o.corrNo());
        String rec = t != null && t.imageCmmnFileId() != null && !t.imageCmmnFileId().isBlank() ? t.imageCmmnFileId()
                : t != null && t.docId() != null ? t.docId() : pid + "#" + o.imageSn();
        StepType step = o.isSuccess() ? StepType.SEND : stepOf(o);
        String path = null;
        String name = t == null ? null : t.fileName();
        if (o.isSuccess() && o.photoPath() != null) {
            int cut = o.photoPath().lastIndexOf('/');
            path = cut > 0 ? o.photoPath().substring(0, cut) : null;
            name = cut >= 0 ? o.photoPath().substring(cut + 1) : o.photoPath();
        }
        return new LogCollectorClient.FileProcReq(rec, path, name, pid, o.isSuccess() ? o.fileSize() : 0L,
                o.isSuccess() ? "SUCCESS" : "FAIL",
                o.isSuccess() ? null : LogCollectorClient.FileProcReq.errStackOf(step, o.errMsg()), step.name());
    }

    /** 대표 오류 — 실패 사유 중 가장 많은 것(T1 ERR_MSG). 실패가 없으면 null. */
    private static String topErrStack(List<ImageOutcome> outcomes) {
        Map<String, Integer> freq = new LinkedHashMap<>();
        Map<String, StepType> stepOfReason = new LinkedHashMap<>();
        for (ImageOutcome o : outcomes) {
            if (o.isFail() && o.errMsg() != null) {
                freq.merge(o.errMsg(), 1, Integer::sum);
                stepOfReason.putIfAbsent(o.errMsg(), stepOf(o));
            }
        }
        return freq.entrySet().stream().max(Map.Entry.comparingByValue())
                .map(e -> LogCollectorClient.FileProcReq.errStackOf(stepOfReason.get(e.getKey()), e.getKey())
                        + (freq.size() > 1 ? " (외 다른 사유 " + (freq.size() - 1) + "종)" : ""))
                .orElse(null);
    }

    /**
     * 이번 실행의 T2 — COLLECT 는 시작에, ANALYZE · SEND 는 처음 닿을 때 연다(워커 여럿이 동시에 닿아도 한 번).
     * 컬렉터가 꺼져 있으면 단계 ID 가 null 이고 마감도 보내지 않는다.
     */
    private final class StepLog {
        private final String execId;
        private final Map<StepType, String> ids = new java.util.EnumMap<>(StepType.class);

        StepLog(String execId) {
            this.execId = execId;
            open(StepType.COLLECT);
        }

        synchronized void open(StepType s) {
            if (!ids.containsKey(s)) {
                ids.put(s, logCollector.createStep(execId, s.seq(), s.name()));
            }
        }

        /** 원천 조회에서 죽었다 — 건을 하나도 만들지 못했으니 COLLECT 를 실패로 닫는다. */
        synchronized void failCollect(String err) {
            String id = ids.get(StepType.COLLECT);
            if (id != null) {
                logCollector.finishStep(id, "FAIL", 0, 0L, 0L, 0L, err);
            }
        }

        /** 열린 단계만 마감한다 — 건수 · 소요 시간(세부 단계 시간의 합, 실제 경과를 넘지 않게). */
        synchronized void finishAll(long collectIn, long collectErr, long analyzeIn, long analyzeErr, long sendIn, long sendErr,
                                    long sendOut, String err, long wallMs, ImageMetrics m) {
            finish(StepType.COLLECT, collectIn, collectIn - collectErr, collectErr, err, wallMs, m);
            finish(StepType.ANALYZE, analyzeIn, analyzeIn - analyzeErr, analyzeErr, err, wallMs, m);
            finish(StepType.SEND, sendIn, sendOut, sendErr, err, wallMs, m);
        }

        private void finish(StepType s, long in, long out, long errCnt, String err, long wallMs, ImageMetrics m) {
            if (!ids.containsKey(s)) {
                return;
            }
            String id = ids.get(s);
            String sts = errCnt == 0 ? "SUCCESS" : (out <= 0 ? "FAIL" : "PARTIAL");
            long ms = 0;
            for (ImageStage st : ImageStage.values()) {
                if (st.stepType() == s) {
                    ms += m.totalMs(st);
                }
            }
            int sec = (int) (Math.min(ms, wallMs) / 1000);
            if (id != null) {
                logCollector.finishStep(id, sts, sec, in, Math.max(0, out), errCnt, errCnt == 0 ? null : err);
            }
            log.info("[Image] T2 {} 마감 — {} in={} out={} err={} ({}초){}", s, sts, in, Math.max(0, out), errCnt, sec,
                    id == null ? "  [컬렉터 미연동 — 적재 안 됨]" : "");
        }
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
                                    ImageMetrics m, XvarmBrokerClient via, ImageStage injectAt, StepLog steps) {
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
            if (scenarioFaults.failOnce(egovframework.unstructured.collector.mock.FailureScenario.COLLECT_FAIL, t.corrNo())) {
                throw new IllegalStateException("XVARM 추출 실패 — 브로커가 이 건을 거부했습니다 [더미 시나리오 COLLECT_FAIL · 1회]");
            }
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

            steps.open(StepType.ANALYZE);   // T2 정제/분석 — 처음 닿을 때 연다
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
            if (scenarioFaults.failOnce(egovframework.unstructured.collector.mock.FailureScenario.ANALYZE_FAIL, t.corrNo())) {
                throw new IllegalStateException("복호화 결과가 이미지가 아닙니다 — 매직 넘버 불일치 [더미 시나리오 ANALYZE_FAIL · 1회]");
            }
            m.add(ImageStage.DECRYPT, s);
            if (sample) {
                trace.add(ImageTrace.Step.RECEIVE, "복호화 — 접견과 같은 RVS 키(AES/CBC) · 결과가 이미지인지 매직 넘버로 확인", "CODE",
                        "MediaDecryptor.decrypt(" + slash(received) + ")  // CMMN_FILE_ENC_YN=" + (t.encrypted() ? "Y" : "N"),
                        "입력 " + cipher.length + " bytes → 출력 " + plain.length + " bytes · 시작 바이트 " + hex(plain, 4)
                                + " → " + String.valueOf(egovframework.unstructured.collector.image.model.ImageFormat.detect(plain)));
            }
            injectIf(injectAt, ImageStage.DECRYPT);

            // ── 저장 ──
            steps.open(StepType.SEND);   // T2 적재/전송 — 처음 닿을 때 연다
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
            // 커밋 전에 부른다 — 여기서 던지면 매핑이 롤백된다(의도적 실패 주입 · 더미 시나리오 SEND_FAIL)
            Runnable beforeCommit = () -> {
                injectIf(injectAt, ImageStage.MAP);
                if (scenarioFaults.failOnce(egovframework.unstructured.collector.mock.FailureScenario.SEND_FAIL, t.corrNo())) {
                    throw new IllegalStateException("Admin DB 매핑 실패 — 커밋 전 롤백 [더미 시나리오 SEND_FAIL · 1회]");
                }
            };
            try {
                mr = repo.upsert(row, beforeCommit);
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
