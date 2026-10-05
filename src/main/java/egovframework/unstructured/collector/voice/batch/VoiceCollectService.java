package egovframework.unstructured.collector.voice.batch;

import egovframework.unstructured.collector.common.broker.BrokerOutputCheck;
import egovframework.unstructured.collector.common.broker.XvarmBrokerClient;
import egovframework.unstructured.collector.common.config.VoiceDirState;
import egovframework.unstructured.collector.common.config.VoiceProperties;
import egovframework.unstructured.collector.common.decrypt.DecryptService;
import egovframework.unstructured.collector.common.logging.LogCollectorClient;
import egovframework.unstructured.collector.common.model.BatchWindow;
import egovframework.unstructured.collector.common.model.FileProcOutcome;
import egovframework.unstructured.collector.common.model.ProcStatus;
import egovframework.unstructured.collector.common.model.StepType;
import egovframework.unstructured.collector.common.model.SttResult;
import egovframework.unstructured.collector.common.model.VoiceFile;
import egovframework.unstructured.collector.common.model.VoiceKind;
import egovframework.unstructured.collector.common.model.VoiceTarget;
import egovframework.unstructured.collector.voice.perf.PerfStage;
import egovframework.unstructured.collector.voice.perf.PerfStageMeter;
import egovframework.unstructured.collector.voice.source.BoramiSourceClient;
import egovframework.unstructured.collector.voice.stt.SttClient;
import egovframework.unstructured.collector.common.transfer.ZenonClient;
import egovframework.unstructured.collector.voice.transfer.ZenonVoiceDocument;
import egovframework.unstructured.collector.common.sync.FileArrivalWatcher;
import egovframework.unstructured.collector.common.util.InmatePidGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 음성 수집 배치의 본체 — <b>[수집] 대상 선별 → 파일 확보 → 복호화 → [정제/분석] STT → [적재/전송] 제논 전송 → 임시 파일 정리</b>.
 *
 * <p>로그 테이블 INSERT 는 로그 컬렉터 API 로만 한다 — 이 서비스는 로그 DB 커넥션을 잡지 않는다.
 * 원천(보라미) DB 는 대상 조회에서만 쓴다.</p>
 *
 * <p><b>T2 단계 — 3단계 체인</b>(2026-10-01 복원): {@code COLLECT 1}(파일 확보·복호화) → {@code ANALYZE 2}(STT) →
 * {@code SEND 3}(제논 전송) — {@link StepType}. 결과는 온프레미스 제논(Zenon)으로
 * 넘긴다. {@code COLLECT} 는 배치 시작에 열고, {@code ANALYZE} 는 첫 STT 가 시작될 때, {@code SEND} 는 첫 전송
 * 직전에 연다. 모두 배치 끝에서 한 번에 마감한다.</p>
 *
 * <p><b>결과를 PV 에 남기지 않는다</b>: 예전에는 STT 결과를 {@code xenon/{meet|phone}/{execId}/} 에 쓰고 하류가
 * 집어 갔다. 이제 SEND 가 제논 수신 API 로 바로 보낸다({@link ZenonClient}). 2026-10-05 부터 전송 양식은 data-collector 와
 * 같다 — 건마다 레코드를 전송 런에 쌓고 배치 끝에 청크(gzip · chunked · X-Run-Id/X-Seq/X-Is-Last …)로 나눠 순서대로 보낸다.
 * 받아들여진 건은 그 건의 임시 파일(받은 원본 · 복호화 오디오 · 전사 보존물)을 지운다(Purge). 실패한 청크와 그 뒤 청크의 건은
 * 전사 보존물이 남아 {@code FROM_SEND} 재처리가 STT 없이 <b>원배치 런을 다음 순번부터 이어</b> 보낸다.</p>
 *
 * <p><b>워커</b>: 확보(I/O)와 STT(연산)를 다른 워커가 맡는다 — {@link Workers}. 둘 다 1 이면 순차다.</p>
 *
 * <p><b>건별 격리</b>: 한 건이 실패해도 배치를 멈추지 않는다. 1,300건을 도는 배치에서
 * 한 파일이 깨졌다고 전체가 중단되면 나머지 1,299건을 다시 처리해야 한다.
 * 실패는 T4 에 사유와 함께 남기고 다음 건으로 넘어간다.</p>
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class VoiceCollectService {

    /** 로컬 임시 EXEC_ID — 컬렉터 규칙(yyyyMMdd + 작업코드3 + 회차3)의 자리를 맞춘다. */
    private static final DateTimeFormatter LOCAL_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    // 밀리초까지 — 초 단위면 같은 초에 돈 두 배치가 같은 ID 가 되어 제논 수신증·보존물이 섞인다(작업코드 자리 9~11 은 그대로)
    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("HHmmssSSS");

    private final VoiceProperties props;
    private final VoiceDirState dirs;
    private final BoramiSourceClient source;
    private final XvarmBrokerClient broker;
    private final egovframework.unstructured.collector.common.sync.PhoneFileProvider phoneFileProvider;
    private final FileArrivalWatcher watcher;
    private final DecryptService decryptService;
    private final SttClient sttClient;
    private final ZenonClient zenon;
    private final ObjectMapper objectMapper;
    private final LogCollectorClient logCollector;
    private final IdempotencyGuard idempotency;
    private final InmatePidGenerator pidGenerator;
    private final BatchProgress progress;
    private final StageFaultState stageFault;
    private final egovframework.unstructured.collector.voice.stt.SttTempStore sttTemp;
    private final PerfStageMeter meter;
    /** 더미 키 표식(CF) 장애 — 접견 수집에서 한 번만 실패시킨다. 운영 프로필에서는 아무 일도 하지 않는다. */
    private final egovframework.unstructured.collector.mock.ScenarioFaults scenarioFaults;

    /**
     * 건을 동시에 처리할 워커 수 — <b>운영 기본은 1(순차)</b>.
     *
     * <p>성능 테스트가 실행마다 1/2/4/8/16 으로 바꿔 돌린다. 운영값은 그 결과를 보고 정한다 —
     * 올리면 브로커(추출 스레드 4개 고정)·NFS·NPU 에 동시에 그만큼 부하가 간다.</p>
     */
    @org.springframework.beans.factory.annotation.Value("${voice.batch.concurrency:1}")
    private int defaultConcurrency;

    /**
     * XVARM 확보 워커 수 — <b>운영 기본은 1</b>. 원천(브로커 추출 스레드 4개 고정 · NFS)에 한 번에 한 건만 요청한다.
     * STT 워커({@code voice.batch.concurrency})를 늘려도 확보는 이 수만큼만 동시에 간다.
     */
    @org.springframework.beans.factory.annotation.Value("${voice.batch.acquire-workers:1}")
    private int defaultAcquireWorkers;

    /** 설정의 워커 구성 — 운영 배치가 쓰는 값. */
    public Workers defaultWorkers() {
        return new Workers(defaultAcquireWorkers, defaultConcurrency);
    }

    /**
     * 실패한 건의 중간 산출물을 남길지 — <b>Resume 의 전제</b>.
     *
     * <p>켜면 복호화 오디오({@code {ROOT}/xvram/decoding})와 전사 결과({@code stt_temp/{execId}})가
     * 남아 다음 재처리가 그 단계부터 이어서 간다. ⚠ 복호화 오디오는 평문이고 그 안에 성명·
     * 주민등록번호가 들어 있다 — 운영에서 PII 를 즉시 지워야 하면 {@code false} 로 내린다.
     * 꺼도 재처리는 동작한다: 보존물이 없으면 앞 단계부터 다시 한다.</p>
     */
    @org.springframework.beans.factory.annotation.Value("${voice.resume.keep-on-failure:true}")
    private boolean keepOnFailure;

    /**
     * 배치를 1회 실행한다.
     *
     * @param window    처리할 시간창
     * @param kinds     처리할 종류(접견/전화). 비면 둘 다
     * @param triggerBy 실행 주체(SCHEDULER / 사용자)
     */
    public VoiceBatchResult run(BatchWindow window, List<VoiceKind> kinds, String triggerBy) {
        return run(window, kinds, triggerBy, false);
    }

    /**
     * 배치를 1회 실행한다.
     *
     * @param testRun 시뮬레이터에서 돌린 시험인가. {@code true} 면 EXEC_ID 가 {@code ...TST...} 로
     *                채번되어 나중에 [테스트 데이터 초기화] 로 통째로 지울 수 있다.
     *                스케줄러가 도는 실제 배치는 항상 {@code false} 다 — 시험 기록과 섞이면
     *                정합성 대사(T1.SUCCESS_CNT == Σ T3·T4·T5)가 의미를 잃는다.
     */
    public VoiceBatchResult run(BatchWindow window, List<VoiceKind> kinds, String triggerBy, boolean testRun) {
        return run(window, kinds, triggerBy, testRun, ResumeMode.FULL, null);
    }

    /**
     * 배치를 1회 실행한다 — <b>이어서 하기(Resume)</b> 포함.
     *
     * @param resume     어느 단계부터 이어서 할지. {@link ResumeMode#FULL} 이면 종전대로 전부 한다
     * @param fromExecId 보존물을 어느 배치에서 찾을지. 비면 가장 최근 것
     */
    public VoiceBatchResult run(BatchWindow window, List<VoiceKind> kinds, String triggerBy, boolean testRun,
                                ResumeMode resume, String fromExecId) {
        return run(window, kinds, triggerBy, testRun, resume, fromExecId, defaultConcurrency);
    }

    /**
     * 배치를 1회 실행한다 — 워커 수를 정해서.
     *
     * @param concurrency 건을 동시에 처리할 워커 수. 1 이하면 순차(운영 기본). 성능 테스트가 쓴다
     */
    public VoiceBatchResult run(BatchWindow window, List<VoiceKind> kinds, String triggerBy, boolean testRun,
                                ResumeMode resume, String fromExecId, int concurrency) {
        return run(window, kinds, triggerBy, testRun, resume, fromExecId, new Workers(defaultAcquireWorkers, concurrency));
    }

    /**
     * 배치를 1회 실행한다 — XVARM 확보 워커와 STT 처리 워커를 나눠 정해서.
     *
     * @param workers 확보 · STT 워커 수. 둘 다 1 이면 순차(운영 기본), 아니면 생산자-소비자({@link #runPipelined})
     */
    public VoiceBatchResult run(BatchWindow window, List<VoiceKind> kinds, String triggerBy, boolean testRun,
                                ResumeMode resume, String fromExecId, Workers workers) {
        return run(window, kinds, triggerBy, testRun, resume, fromExecId, workers, null);
    }

    /**
     * 배치를 1회 실행한다 — execId 를 받자마자 알린다.
     *
     * @param onExecId T1 을 열어 execId 를 받은 직후 한 번 불린다({@code (execId, 컬렉터 채번인가)} — 미연동이면 로컬 임시 ID · false).
     *                 admin 연동의 비동기 실행(바로 실행 · 긴급 재처리)이 접수 응답에 실제 execId 를 싣는 데 쓴다. null 이면 안 부른다
     */
    public VoiceBatchResult run(BatchWindow window, List<VoiceKind> kinds, String triggerBy, boolean testRun,
                                ResumeMode resume, String fromExecId, Workers workers,
                                java.util.function.BiConsumer<String, Boolean> onExecId) {
        long startedAt = System.currentTimeMillis();
        Workers w = workers == null ? Workers.sequential() : workers;
        List<VoiceKind> targets = (kinds == null || kinds.isEmpty())
                ? List.of(VoiceKind.MEET, VoiceKind.PHONE) : kinds;

        String collectorExecId = openBatch(window, triggerBy, testRun);
        String execId = collectorExecId != null ? collectorExecId : localExecId(testRun);
        if (onExecId != null) {
            try {
                onExecId.accept(execId, collectorExecId != null);
            } catch (RuntimeException e) {
                log.warn("[Batch] execId 알림 실패(배치는 계속) — {}", e.getMessage());
            }
        }
        log.info("[Batch] 시작 — execId={} {} kinds={}{}", execId, window, targets,
                testRun ? "  [시험 실행 — TST 로 채번, 초기화로 삭제 가능]" : "");
        // 트랙별로 따로 찍는다. 두 시나리오는 연동 주체가 달라서 한 줄에 섞으면
        // 어느 모드가 어느 경로에 걸린 것인지 읽히지 않는다.
        if (targets.contains(VoiceKind.MEET)) {
            log.info("[Batch]   접견 트랙 — 조회={} → 브로커={} → 복호화={} → STT={} → 전송=제논 {}",
                    source.mode(), broker.mode(), decryptService.mode(), sttClient.mode(), zenon.mode());
        }
        if (targets.contains(VoiceKind.PHONE)) {
            log.info("[Batch]   전화 트랙 — 조회={} → 파일연계={} → 복호화={} → STT={} → 전송=제논 {}  (XVARM 경유 없음)",
                    source.mode(), phoneFileProvider.mode(), decryptService.mode(), sttClient.mode(), zenon.mode());
        }

        // ── T2 ① COLLECT — 파일 확보·복호화. 배치 시작에 연다 ────────────────────
        RunContext ctx = new RunContext(execId);
        ctx.fromExecId = fromExecId;
        // 전송 런 — 건마다 레코드를 쌓고 배치 끝에 청크로 나눠 보낸다(data-collector 양식). 재처리면 원배치 런을 이어 받는다
        ctx.transfer = zenon.openSession(execId, fromExecId);
        log.info("[Batch]   전송 — 제논 {} · {} · 청크 {}건", zenon.mode(), zenon.endpoint(), zenon.chunkRecords());
        ctx.collectStepId = logCollector.createStep(execId, StepType.COLLECT.seq(), StepType.COLLECT.name());

        List<VoiceTarget> found = scopeForRun(findTargets(window, targets), testRun);
        List<FileProcOutcome> outcomes = new ArrayList<>(found.size());

        // 화면 진행률 — 대상 수가 확정된 지금부터 센다. 배치 REST 는 동기라 이것 없이는
        // 수 분짜리 배치가 도는지 죽었는지 화면에서 알 수 없다.
        boolean pipelined = w.pipelined() && found.size() > 1;
        progress.begin(execId, window.toString(), found.size(), w, pipelined);
        // 부분 성공은 확률이 아니라 건수다 — 대상 수가 정해진 지금 몇 건을 떨어뜨릴지 확정한다.
        stageFault.beginBatch(found.size());
        boolean canceled = false;
        long loopStartedAt = System.currentTimeMillis();
        try {
            if (pipelined) {
                log.info("[Batch]   생산자-소비자 — XVARM 확보 워커 {}개 → STT 처리 워커 {}개 (대상 {}건)",
                        w.acquire(), w.stt(), found.size());
                canceled = runPipelined(found, ctx, resume, w, outcomes);
            } else {
                for (VoiceTarget t : found) {
                    // 중단은 건과 건 사이에서만 받는다 — 처리 중인 건을 끊으면 복호화 원본이 남거나
                    //   반쯤 쓴 산출물이 생긴다. 남은 건은 '중단됨' 으로 세어 합계를 맞춘다.
                    if (progress.isCancelRequested()) {
                        canceled = true;
                        log.warn("[Batch] 중단 요청 — execId={} · 처리 {}건 · 남은 {}건은 건너뜀으로 남긴다",
                                execId, outcomes.size(), found.size() - outcomes.size());
                        for (VoiceTarget rest : found.subList(outcomes.size(), found.size())) {
                            outcomes.add(FileProcOutcome.skipped(rest, CANCELED));
                            progress.finishFile(ProcStatus.SKIPPED);
                        }
                        break;
                    }
                    progress.startFile(t);
                    FileProcOutcome o = processOne(t, ctx, resume);
                    progress.finishFile(o.status());
                    outcomes.add(o);
                }
            }
        } finally {
            progress.end();
        }
        if (pipelined) {
            // 동시 처리에서는 건별 시간의 합이 실제로 흐른 시간을 넘는다(4건이 1초씩 겹치면 합은 4초).
            //   T2 소요 시간은 '그 단계가 열려 있던 시간' 이므로 벽시계로 자른다.
            ctx.capElapsed(System.currentTimeMillis() - loopStartedAt);
        }

        // ── SEND 마무리 — 쌓아 둔 레코드를 청크로 나눠 순서대로 보낸다. 실패한 청크(와 그 뒤)의 건은 SEND 실패로 바꾼다
        ZenonClient.Report transfer = flushTransfer(ctx, outcomes);

        int success = (int) outcomes.stream().filter(FileProcOutcome::isSuccess).count();
        int skipped = (int) outcomes.stream().filter(o -> o.status() == ProcStatus.SKIPPED).count();
        int fail = outcomes.size() - success - skipped;

        // ── T2 마감 — COLLECT 는 확보 건수로, ANALYZE 는 STT 건수로 ───────────────
        List<VoiceBatchResult.StepLog> steps = new ArrayList<>(3);
        long collectIn = outcomes.size() - skipped;
        long collectErr = outcomes.stream().filter(o -> o.failedAt(FileProcOutcome.STEP_COLLECT)).count();
        long collectOut = collectIn - collectErr;
        steps.add(finishStep(ctx.collectStepId, FileProcOutcome.STEP_COLLECT,
                collectIn, collectOut, collectErr, ctx.collectMs));

        // STT 가 한 건이라도 시작됐을 때만 ANALYZE 행이 있다 — 전건 확보 실패면 STT 단계는 돌지 않은 것이다.
        long analyzeErr = outcomes.stream().filter(o -> o.failedAt(FileProcOutcome.STEP_ANALYZE)).count();
        if (ctx.analyzeStarted) {
            // ANALYZE 는 STT 까지다 — 나간 건수는 STT 를 통과해 SEND 로 넘어간 수.
            steps.add(finishStep(ctx.analyzeStepId, FileProcOutcome.STEP_ANALYZE,
                    collectOut, collectOut - analyzeErr, analyzeErr, ctx.analyzeMs));
        }
        // SEND — STT 를 통과한 건이 한 번이라도 제논 전송을 시도했을 때만 행이 있다.
        if (ctx.sendStarted) {
            long sendIn = collectOut - analyzeErr;
            long sendErr = outcomes.stream().filter(o -> o.failedAt(FileProcOutcome.STEP_SEND)).count();
            steps.add(finishStep(ctx.sendStepId, FileProcOutcome.STEP_SEND, sendIn, success, sendErr, ctx.sendMs));
        }

        // T4 — 파일 1건 = 1행. 정합성 대사(T1.SUCCESS_CNT == Σ T3·T4·T5)의 근거다.
        logCollector.createFileProcs(execId, toFileProcReqs(outcomes));

        // 결과를 PV 에 남기지 않는다 — 어디로 보냈는지만 싣는다
        Map<String, String> sent = new LinkedHashMap<>();
        sent.put("mode", zenon.mode());
        sent.put("endpoint", zenon.endpoint());
        sent.put("sent", String.valueOf(success));
        if (!transfer.isEmpty()) {
            sent.put("runId", transfer.runId());
            sent.put("chunks", String.valueOf(transfer.chunks().size()));
            sent.put("seq", transfer.fromSeq() + "~" + (transfer.fromSeq() + transfer.chunks().size() - 1));
            sent.put("continued", String.valueOf(transfer.continued()));
            sent.put("closed", String.valueOf(transfer.closed()));
        }

        long elapsedMs = System.currentTimeMillis() - startedAt;
        VoiceBatchResult result = new VoiceBatchResult(execId, collectorExecId != null, window.toString(),
                found.size(), success, fail, skipped, elapsedMs, sent, steps, outcomes, canceled);

        // [바로 실행]의 시작점은 따로 적지 않는다 — T1 이 원본이다.
        //   배치를 열 때 훑을 구간(target_from/to_dtm)을 T1 에 남기고, 마감 상태가 SUCCESS 인 배치의
        //   MAX(target_to_dtm) 이 곧 워터마크다. PARTIAL·FAIL·CANCELED 는 로그 컬렉터가 세지 않으므로,
        //   빠진 건이 있는 구간이 '다 수집했다' 로 둔갑하지 않는다.

        logCollector.finishBatch(execId, result.execStsCd(), elapsedSec(startedAt),
                (long) found.size(), (long) success, (long) fail,
                result.errMsg() == null ? null : LogCollectorClient.FileProcReq.errStackOf(result.errMsg()));
        log.info("[Batch] 종료{} — {}", canceled ? "(중단됨)" : "", result.summary());
        return result;
    }

    /** 중단 사유 — 순차·생산자-소비자가 같은 문구를 쓴다(화면이 이 접두로 센다). */
    private static final String CANCELED = "중단됨 — 사용자가 배치를 멈췄습니다";

    /** SEND 에 쌓아 둔 건의 임시 위치 — flushTransfer 가 수신증 위치로 바꾼다. */
    private static final String PENDING_SEND = "zenon:pending";

    /**
     * 쌓아 둔 레코드를 보낸다 — 청크로 나눠 순서대로, 한 청크가 실패하면 멈춘다({@link ZenonClient.Session#flush}).
     *
     * <p>받아들여진 건은 수신증 위치로, 실패한 청크 · 그 뒤 청크의 건은 SEND 실패로 결과를 고친다. 정합성 대사
     * (T1.SUCCESS_CNT == Σ T4 SUCCESS)는 고친 뒤의 결과로 맞춘다. 중단(cancel)된 배치도 이미 STT 를 마친 건은 보낸다.</p>
     */
    private ZenonClient.Report flushTransfer(RunContext ctx, List<FileProcOutcome> outcomes) {
        if (ctx.transfer == null || ctx.transfer.size() == 0) {
            return ZenonClient.Report.empty(ctx.execId, zenon.mode(), zenon.endpoint());
        }
        long t0 = System.currentTimeMillis();
        long mSend = meter.start();
        ZenonClient.Report rep;
        try {
            rep = ctx.transfer.flush();
        } catch (RuntimeException e) {
            // 전송 코드 자체가 깨졌다 — 쌓인 건을 모두 SEND 실패로 남긴다(전사는 남아 있어 재처리할 수 있다)
            log.error("[Batch] 제논 전송 실패(예기치 않음) — execId={} · {}", ctx.execId, e.toString(), e);
            for (FileProcOutcome o : outcomes) {
                if (o.isSuccess() && PENDING_SEND.equals(o.sttPath())) {
                    ctx.sendFailed.putIfAbsent(o.target().idempotencyKey(),
                            e.getClass().getSimpleName() + ": " + shorten(e.getMessage()));
                }
            }
            rep = ZenonClient.Report.empty(ctx.execId, zenon.mode(), zenon.endpoint());
        } finally {
            meter.add(PerfStage.SEND, mSend);
        }
        ctx.addSend(System.currentTimeMillis() - t0);
        for (int i = 0; i < outcomes.size(); i++) {
            FileProcOutcome o = outcomes.get(i);
            if (!o.isSuccess() || !PENDING_SEND.equals(o.sttPath())) {
                continue;
            }
            String key = o.target().idempotencyKey();
            ZenonClient.Receipt rc = ctx.delivered.get(key);
            if (rc != null) {
                outcomes.set(i, FileProcOutcome.success(o.target(), o.fileSize(), o.sttChars(), rc.location(), o.elapsedMs()));
            } else {
                String why = ctx.sendFailed.getOrDefault(key, "전송 결과 없음");
                if (!progress.isCancelRequested()) {
                    meter.error(PerfStage.SEND);
                }
                log.warn("[Batch] 처리 실패 [{}] — {} ({})", FileProcOutcome.STEP_SEND, o.target().shortId(), why);
                outcomes.set(i, FileProcOutcome.fail(o.target(), FileProcOutcome.STEP_SEND,
                        "ZenonSendException: " + shorten(why), o.elapsedMs()));
            }
        }
        return rep;
    }

    /** 확보 워커가 STT 워커에게 넘기는 끝 표시 — 확보 워커가 모두 끝나면 STT 워커 수만큼 넣는다. */
    private static final Staged END = new Staged(null, ResumeMode.FULL, -1);

    /**
     * 생산자-소비자 — <b>XVARM 확보 워커</b>가 파일을 받아 대기열에 넣고, <b>STT 처리 워커</b>가 곧바로 집어 간다.
     *
     * <p>확보 워커는 대상을 앞에서부터 하나씩 가져가 확보(접견: 브로커 추출 → 수신 폴더 도착 / 전화: 파일 연계
     * 수신)까지만 한다. STT 워커는 복호화 · STT · 제논 전송을 한다. 대상 조회 · T1/T2 개시 · T4/T1 마감은
     * 순차와 똑같이 이 스레드가 한다. 결과는 <b>대상 순서 그대로</b> 모은다 — T4 행 순서가 순차 실행과 같다.</p>
     *
     * <p><b>대기열은 STT 워커 수만큼만</b> 받는다 — STT 가 밀리면 확보도 쉬어 간다. 끝없이 받아 두면 확보한
     * 원본 음성이 디스크에 쌓이고, 중단했을 때 버릴 것만 늘어난다.</p>
     *
     * <p><b>DB 커넥션</b>: 두 워커 모두 원천 DB 를 쓰지 않는다(대상 조회는 루프 전에 끝났다). 단계 기록(T2)·파일
     * 이력(T4)은 로그 컬렉터 API 호출이고 트랜잭션은 그쪽에서 짧게 닫힌다 — STT·확보 대기 동안 커넥션을 잡는 일이 없다.</p>
     *
     * <p>중단은 순차와 같은 원칙이다 — 처리 중인 건은 끝까지 간다. 확보를 시작하지 않은 건, 확보는 끝났지만
     * STT 워커가 아직 집지 않은 건은 '건너뜀' 으로 남긴다(받아 둔 파일은 지운다).</p>
     *
     * @return 중단됐는가
     */
    private boolean runPipelined(List<VoiceTarget> found, RunContext ctx, ResumeMode resume, Workers workers,
                                 List<FileProcOutcome> outcomes) {
        int n = found.size();
        int acquirers = Math.min(workers.acquire(), n);
        int sttWorkers = Math.min(workers.stt(), n);
        FileProcOutcome[] results = new FileProcOutcome[n];
        BlockingQueue<Staged> queue = new ArrayBlockingQueue<>(sttWorkers);
        AtomicInteger next = new AtomicInteger();
        AtomicInteger liveConsumers = new AtomicInteger(sttWorkers);
        AtomicBoolean canceled = new AtomicBoolean();
        long loopStart = System.nanoTime();

        ExecutorService acquirePool = daemonPool("voice-acquire-", acquirers);
        ExecutorService sttPool = daemonPool("voice-stt-", sttWorkers);
        List<Future<?>> consumers = new ArrayList<>(sttWorkers);
        List<Future<?>> producers = new ArrayList<>(acquirers);
        try {
            for (int i = 0; i < sttWorkers; i++) {
                consumers.add(sttPool.submit(() -> consume(queue, ctx, results, canceled, liveConsumers)));
            }
            for (int i = 0; i < acquirers; i++) {
                producers.add(acquirePool.submit(() ->
                        produce(found, next, queue, ctx, resume, results, canceled, liveConsumers, loopStart)));
            }
            producers.forEach(VoiceCollectService::join);
            // 확보가 모두 끝났다 — STT 워커마다 끝 표시 하나. 대기열이 차 있으면 STT 워커가 비울 때까지 기다린다.
            for (int i = 0; i < sttWorkers; i++) {
                if (!offer(queue, END, liveConsumers)) {
                    break;
                }
            }
            consumers.forEach(VoiceCollectService::join);
        } finally {
            acquirePool.shutdownNow();
            sttPool.shutdownNow();
        }
        meter.pipelineLoop(elapsedMs(loopStart));

        for (int i = 0; i < n; i++) {
            FileProcOutcome o = results[i];
            // 빈칸은 워커가 예기치 않게 멈춘 경우뿐이다 — 합계가 대상 수와 맞게 실패로 채운다
            outcomes.add(o != null ? o : FileProcOutcome.fail(found.get(i), FileProcOutcome.STEP_ANALYZE,
                    "IllegalStateException: 워커가 결과를 남기지 않고 멈췄습니다", 0L));
        }
        if (canceled.get()) {
            log.warn("[Batch] 중단 요청 — execId={} · 시작하지 않은 {}건은 건너뜀으로 남긴다", ctx.execId,
                    outcomes.stream().filter(o -> o.status() == ProcStatus.SKIPPED
                            && CANCELED.equals(o.errMsg())).count());
        }
        return canceled.get();
    }

    /** 확보 워커 — 대상을 앞에서부터 하나씩 가져가 확보하고 대기열에 넣는다. */
    private void produce(List<VoiceTarget> found, AtomicInteger next, BlockingQueue<Staged> queue, RunContext ctx,
                         ResumeMode resume, FileProcOutcome[] results, AtomicBoolean canceled,
                         AtomicInteger liveConsumers, long loopStart) {
        for (int i = next.getAndIncrement(); i < found.size(); i = next.getAndIncrement()) {
            VoiceTarget t = found.get(i);
            if (progress.isCancelRequested()) {
                canceled.set(true);
                progress.skipFile();
                results[i] = FileProcOutcome.skipped(t, CANCELED);
                continue;
            }
            progress.startFile(t);
            progress.move(null, BatchProgress.Zone.ACQUIRE);
            Staged st = acquireStage(t, ctx, resume, i);
            st.readyMs = elapsedMs(loopStart);
            meter.acquireDone(st.readyMs);
            if (st.done != null) {
                // 이미 처리된 건 · 확보 실패 — STT 워커에 넘길 것이 없다
                progress.move(BatchProgress.Zone.ACQUIRE, null);
                progress.finishFile(st.done.status());
                results[i] = st.done;
                continue;
            }
            progress.move(BatchProgress.Zone.ACQUIRE, BatchProgress.Zone.QUEUE);
            if (!offer(queue, st, liveConsumers)) {
                // STT 워커가 모두 멈췄다 — 넘길 곳이 없으니 받은 파일을 버리고 실패로 남긴다
                progress.move(BatchProgress.Zone.QUEUE, null);
                release(st);
                FileProcOutcome o = FileProcOutcome.fail(t, FileProcOutcome.STEP_ANALYZE,
                        "IllegalStateException: STT 워커가 모두 멈춰 넘기지 못했습니다", st.activeMs);
                progress.finishFile(o.status());
                results[i] = o;
            }
        }
    }

    /** STT 워커 — 대기열에서 한 건씩 집어 복호화 · STT · 제논 전송까지 한다. 끝 표시를 받으면 멈춘다. */
    private void consume(BlockingQueue<Staged> queue, RunContext ctx, FileProcOutcome[] results,
                         AtomicBoolean canceled, AtomicInteger liveConsumers) {
        try {
            while (true) {
                Staged st = queue.take();
                if (st == END) {
                    return;
                }
                progress.move(BatchProgress.Zone.QUEUE, BatchProgress.Zone.STT);
                FileProcOutcome o;
                if (progress.isCancelRequested()) {
                    // 확보는 끝났지만 아직 시작하지 않은 건 — 받은 파일을 지우고 건너뜀으로 남긴다
                    canceled.set(true);
                    release(st);
                    o = FileProcOutcome.skipped(st.target, CANCELED);
                } else {
                    meter.beginItem();
                    long c0 = System.nanoTime();
                    o = processStage(st, ctx);
                    meter.pipelineItem(st.readyMs, elapsedMs(c0), meter.endItem());
                }
                progress.move(BatchProgress.Zone.STT, null);
                progress.finishFile(o.status());
                results[st.index] = o;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            liveConsumers.decrementAndGet();
        }
    }

    /**
     * 대기열에 넣는다 — 차 있으면 STT 워커가 비울 때까지 기다린다(배압).
     *
     * @return 넣었는가. STT 워커가 모두 멈춰 받아 줄 곳이 없으면 false
     */
    private static boolean offer(BlockingQueue<Staged> queue, Staged st, AtomicInteger liveConsumers) {
        try {
            while (!queue.offer(st, 200, TimeUnit.MILLISECONDS)) {
                if (liveConsumers.get() <= 0) {
                    return false;
                }
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 워커가 끝나기를 기다린다. 워커는 건별 예외를 스스로 잡으므로 여기서 실패가 나는 일은 드물다. */
    private static void join(Future<?> f) {
        try {
            f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            log.warn("[Batch] 워커가 예기치 않게 멈췄다 — {}: {}", c.getClass().getSimpleName(), shorten(c.getMessage()));
        }
    }

    private static ExecutorService daemonPool(String prefix, int size) {
        AtomicInteger seq = new AtomicInteger();
        return Executors.newFixedThreadPool(size, r -> {
            Thread th = new Thread(r, prefix + seq.incrementAndGet());
            th.setDaemon(true);
            return th;
        });
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    // ── 단계별 ────────────────────────────────────────────────────────────

    /** 배치 1회 동안 단계 기록이 들고 다니는 상태 — 단계 ID 와 구간별 누적 시간. */
    private static final class RunContext {
        final String execId;
        String fromExecId;
        String collectStepId;
        String analyzeStepId;
        String sendStepId;
        boolean analyzeStarted;
        boolean sendStarted;
        /** 제논 전송 런 — SEND 가 레코드를 쌓고 배치 끝에 보낸다. */
        ZenonClient.Session transfer;
        /** 전송 결과 — 키 → 수신증(받아들여짐) / 사유(실패 · 미전송). flush 가 채우고 결과를 고친다. */
        final Map<String, ZenonClient.Receipt> delivered = new java.util.concurrent.ConcurrentHashMap<>();
        final Map<String, String> sendFailed = new java.util.concurrent.ConcurrentHashMap<>();
        long collectMs;
        long analyzeMs;
        long sendMs;

        RunContext(String execId) {
            this.execId = execId;
        }

        // 구간별 누적 — 동시 처리에서 여러 워커가 더하므로 잠그고 더한다. 순차에서는 경합이 없다.
        synchronized void addCollect(long ms) {
            collectMs += ms;
        }

        synchronized void addAnalyze(long ms) {
            analyzeMs += ms;
        }

        synchronized void addSend(long ms) {
            sendMs += ms;
        }

        /** 동시 처리 — 건별 시간의 합이 실제 경과 시간을 넘지 않게 자른다. */
        synchronized void capElapsed(long wallMs) {
            collectMs = Math.min(collectMs, wallMs);
            analyzeMs = Math.min(analyzeMs, wallMs);
            sendMs = Math.min(sendMs, wallMs);
        }
    }

    /**
     * T1 을 열어 EXEC_ID 를 받아 온다. 컬렉터가 없으면 null — 호출자가 로컬 임시 ID 를 만든다.
     */
    private String openBatch(BatchWindow window, String triggerBy, boolean testRun) {
        String jobId = testRun ? props.batch().testJobId() : props.batch().jobId();
        // 훑을 구간을 T1 에 남긴다 — [바로 실행]의 워터마크(MAX(target_to_dtm))가 여기서 나온다.
        //   예전에는 null 로 보내 T1 의 구간 칸이 전부 비어 있었다.
        return logCollector.createBatch(jobId, props.batch().dataTypeCd(), window.execTypeCd(), triggerBy,
                window.from(), window.recordedEnd(LocalDateTime.now()));
    }

    /**
     * 컬렉터가 없을 때의 임시 EXEC_ID — 같은 자리에 작업코드가 오게 만든다.
     *
     * <p>컬렉터 채번 규칙: yyyyMMdd(8) + 작업코드(3) + 회차(3) → 9~11번째 자리가 작업코드다.
     * 테스트 데이터 삭제가 그 자리(TST)를 보므로 로컬 ID 도 자리를 맞춘다. 비정형 작업코드는 {@code UNS}(2026-10-02 — 그 전 VOC).</p>
     */
    private static String localExecId(boolean testRun) {
        LocalDateTime now = LocalDateTime.now();
        String local = LOCAL_DATE.format(now) + (testRun ? "TST" : "UNS") + LOCAL_TIME.format(now);
        log.info("[Batch] 로그 컬렉터 미연동 — 로컬 임시 execId 사용: {}", local);
        return local;
    }

    /** ANALYZE 단계를 연다 — 첫 STT 직전에 한 번. 컬렉터는 같은 단계 재호출을 기존 행으로 돌려보내므로 안전하다. */
    private void beginAnalyze(RunContext ctx) {
        // 동시 처리에서 두 워커가 같이 닿아도 T2 행은 하나만 연다 — 잠근 채로 확인하고 연다.
        synchronized (ctx) {
            if (ctx.analyzeStarted) {
                return;
            }
            ctx.analyzeStarted = true;
            ctx.analyzeStepId = logCollector.createStep(ctx.execId, StepType.ANALYZE.seq(), StepType.ANALYZE.name());
        }
        log.info("[Batch] T2 ANALYZE 시작 — stepLogId={}", ctx.analyzeStepId == null ? "(미연동)" : ctx.analyzeStepId);
    }

    /** SEND 단계를 연다 — 첫 제논 전송 직전에 한 번. ANALYZE 와 같은 방식이다. */
    private void beginSend(RunContext ctx) {
        synchronized (ctx) {
            if (ctx.sendStarted) {
                return;
            }
            ctx.sendStarted = true;
            // 비정형 체인의 3번 칸(COLLECT 1 · ANALYZE 2 · SEND 3). 순번은 컬렉터가 체인 위치로 다시 정한다.
            ctx.sendStepId = logCollector.createStep(ctx.execId, StepType.SEND.seq(), StepType.SEND.name());
        }
        log.info("[Batch] T2 SEND 시작 — stepLogId={}", ctx.sendStepId == null ? "(미연동)" : ctx.sendStepId);
    }

    /** 단계를 마감하고 결과 요약을 돌려준다. 컬렉터 미연동이면 요약만 만든다. */
    private VoiceBatchResult.StepLog finishStep(String stepLogId, String stepTypeCd,
                                                long in, long out, long err, long elapsedMs) {
        String sts = err == 0 ? "SUCCESS" : (out == 0 ? "FAIL" : "PARTIAL");
        int sec = (int) (elapsedMs / 1000);
        boolean logged = stepLogId != null;
        if (logged) {
            logCollector.finishStep(stepLogId, sts, sec, in, out, err, null);
        }
        log.info("[Batch] T2 {} 마감 — {} in={} out={} err={} ({}초){}", stepTypeCd, sts, in, out, err, sec,
                logged ? "" : "  [컬렉터 미연동 — 적재 안 됨]");
        return new VoiceBatchResult.StepLog(stepTypeCd, stepLogId, sts, in, out, err, sec, logged);
    }

    /**
     * 이 창을 돌리면 <b>실제로 처리할 건이 몇 개인가</b> — 배치를 열지 않고 센다.
     *
     * <p>시뮬레이터가 실행 버튼을 누르기 직전에 부른다. 0건이면 T1 을 빈 배치로 하나 남기는 대신
     * "시뮬레이션 데이터를 만들까요?" 를 먼저 묻는다. 대상은 있는데 모두 처리가 끝난 경우도
     * 0건이다 — 누르면 전부 '건너뜀' 으로 끝나 아무것도 확인할 수 없으니 같은 취급이다.</p>
     *
     * <p>로그 컬렉터·브로커·파일은 건드리지 않는다. 보라미 조회와 멱등 표식 확인뿐이다.</p>
     */
    public Map<String, Object> pending(BatchWindow window, List<VoiceKind> kinds) {
        return pending(window, kinds, false);
    }

    /**
     * 위와 같되, 실행 종류에 맞춰 센다 — 시험 실행이면 대시보드 더미({@code DMY-…})를, 실제 실행이면 시뮬레이터 데이터
     * ({@code SIM-…})를 뺀다. 그 실행이 실제로 집는 것과 같게({@link #scopeForRun}).
     */
    public Map<String, Object> pending(BatchWindow window, List<VoiceKind> kinds, boolean testRun) {
        List<VoiceKind> targets = (kinds == null || kinds.isEmpty())
                ? List.of(VoiceKind.MEET, VoiceKind.PHONE) : kinds;
        List<VoiceTarget> found = scopeForRun(findTargets(window, targets, true), testRun);
        long processed = found.stream().filter(idempotency::isProcessed).count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("window", window.toString());
        out.put("from", window.from().toString());
        out.put("to", window.to().toString());
        out.put("total", found.size());
        out.put("processed", processed);
        out.put("pending", found.size() - processed);
        return out;
    }

    private List<VoiceTarget> findTargets(BatchWindow window, List<VoiceKind> kinds) {
        return findTargets(window, kinds, false);
    }

    /**
     * 실행 종류에 따라 <b>더미 데이터를 가른다</b>(2026-10-02 결정) — 시뮬레이터 데이터와 대시보드 데이터가 서로를 선점하지 않게.
     *
     * <ul>
     *   <li><b>시험 실행</b>(시뮬레이터 {@code test=true}, TEST_BATCH) — 대시보드 더미({@code DMY-…})를 집지 않는다.
     *       시험이 먼저 처리하면 멱등 표식이 남아 실제 배치가 '건너뜀' 으로 넘겨 대시보드에 아무것도 나오지 않는다.</li>
     *   <li><b>실제 실행</b>(스케줄러 · {@code /internal/batch/run} · {@code /internal/batch/reprocess} · {@code test=false}) —
     *       시뮬레이터 데이터({@code SIM-…})를 집지 않는다. SIM 은 시뮬레이터 화면의 수동 시험 전용이다 — 자동 실행이 집으면
     *       시뮬레이터가 만든 데이터를 먼저 처리해 버려(선점) 시험이 '건너뜀' 으로 끝나고, 운영(UNS) 이력 · 대시보드에 시험 데이터가 섞인다.
     *       이미지는 실제 수집이 원래 {@code SIMIMG…} 를 뺀다({@code ImageSourceService}).</li>
     * </ul>
     * <p>실제 보라미 행(접두 없음)은 어느 쪽에서도 빼지 않는다.</p>
     */
    private static List<VoiceTarget> scopeForRun(List<VoiceTarget> found, boolean testRun) {
        egovframework.unstructured.collector.mock.DummyTarget other = testRun
                ? egovframework.unstructured.collector.mock.DummyTarget.DASHBOARD
                : egovframework.unstructured.collector.mock.DummyTarget.SIMULATOR;
        List<VoiceTarget> out = found.stream().filter(t -> !other.ownsKey(t.idempotencyKey())).toList();
        if (out.size() < found.size()) {
            log.info("[Batch] {} — {} {}건은 {} 몫이라 건너뛴다", testRun ? "시험 실행" : "실제 실행",
                    testRun ? "대시보드 더미(DMY)" : "시뮬레이터 데이터(SIM)", found.size() - out.size(),
                    testRun ? "실제 배치" : "시뮬레이터 시험 실행");
        }
        return out;
    }

    private List<VoiceTarget> findTargets(BatchWindow window, List<VoiceKind> kinds, boolean quiet) {
        int limit = props.batch().maxFilesPerRun();
        List<String> codes = props.batch().speclMngSeCd();
        java.util.function.Predicate<VoiceTarget> done = idempotency.snapshot();
        List<VoiceTarget> all = new ArrayList<>();
        if (kinds.contains(VoiceKind.MEET)) {
            if (!quiet) {
                log.info("[Track:MEET] ① 보라미 조회 — 4단 조인(특이수용자→녹취파일→공통파일→XVARM) via {}",
                        source.mode());
            }
            all.addAll(scan("접견", (l, o) -> source.findMeetTargets(window, codes, l, o), limit, done));
        }
        if (kinds.contains(VoiceKind.PHONE)) {
            if (!quiet) {
                log.info("[Track:PHONE] ① 보라미 조회 — 단일 테이블(TB_IMPH_UCDR_DS) via {}", source.mode());
            }
            all.addAll(scan("전화", (l, o) -> source.findPhoneTargets(window, codes, l, o), limit, done));
        }
        // 상한은 '처리할 건' 기준으로 자른다. 건너뛸 건까지 세어 자르면, 이미 끝난 건이 자리를 차지해
        //   정작 처리할 건이 밀려난다.
        List<VoiceTarget> out = new ArrayList<>(all.size());
        int pending = 0;
        int dropped = 0;
        for (VoiceTarget t : all) {
            if (!done.test(t)) {
                if (pending >= limit) {
                    dropped++;
                    continue;
                }
                pending++;
            }
            out.add(t);
        }
        if (dropped > 0) {
            log.warn("[Batch] 처리할 건이 상한({})을 넘어 {}건은 다음 실행으로 넘긴다", limit, dropped);
        }
        return out;
    }

    /** 페이지 하나를 가져오는 함수 — (limit, offset) → 행. */
    @FunctionalInterface
    private interface PageFetcher {
        List<VoiceTarget> fetch(int limit, int offset);
    }

    /** 한 번의 조회로 훑는 최대 페이지 수 — 창이 비정상적으로 넓어도 끝이 있게. */
    private static final int MAX_PAGES = 200;

    /**
     * 한 트랙을 <b>페이지로</b> 훑는다 — 이미 처리된 건이 상한을 다 채워 새 건이 밀려나지 않게.
     *
     * <p><b>왜 필요한가</b>: 조회는 {@code CRT_DT} 오름차순 + {@code FETCH FIRST {상한}} 이고, 이미 처리했는지는
     * 조회 <b>뒤에</b> 멱등 표식으로 가린다. 창이 좁을 때(최근 20분)는 문제가 없었지만, 주기배치를
     * 당일 전체로, [바로 실행]을 30일로 넓히자 앞쪽의 처리 끝난 건이 상한을 다 채웠다 — 운영 상한 500 에
     * 하루 1,300건이면 오전 500건이 끝난 뒤로는 오후 건이 <b>영영 조회되지 않는다</b>.</p>
     *
     * <p>첫 페이지는 그대로 싣는다 — 이미 처리된 건은 '건너뜀' 으로 보고되는 종전 동작이다.
     * 다음 페이지부터는 처리할 건만 싣는다 — 수만 건을 '건너뜀' 으로 늘어놓을 이유가 없고,
     * 건너뛴 건은 T4 에 남지도 않는다.</p>
     */
    private List<VoiceTarget> scan(String track, PageFetcher f, int limit,
                                   java.util.function.Predicate<VoiceTarget> done) {
        List<VoiceTarget> out = new ArrayList<>();
        int offset = 0;
        int pages = 0;
        int pending = 0;
        while (true) {
            List<VoiceTarget> page = f.fetch(limit, offset);
            pages++;
            for (VoiceTarget t : page) {
                boolean processed = done.test(t);
                if (pages == 1 || !processed) {
                    out.add(t);
                }
                if (!processed) {
                    pending++;
                }
            }
            if (page.size() < limit || pending >= limit) {
                break;
            }
            if (pages >= MAX_PAGES) {
                log.warn("[Batch] {} 조회가 {}페이지({}행)에 닿아 멈춘다 — 창이 지나치게 넓다", track, pages, offset + page.size());
                break;
            }
            offset += page.size();
        }
        if (pages > 1) {
            log.info("[Batch] {} — 처리 끝난 건을 넘겨 {}페이지까지 훑었다(미처리 {}건)", track, pages, pending);
        }
        return out;
    }

    /**
     * 확보까지 마친 한 건 — 확보 워커가 만들어 STT 워커에게 넘긴다. 순차에서는 한 스레드가 이어서 쓴다.
     */
    private static final class Staged {
        final VoiceTarget target;
        final int index;
        /** 이 건에 실제로 적용된 재처리 단계 — 보존물이 없으면 앞 단계로 내려간다. */
        ResumeMode resume;
        /** 이 건이 디스크에 만든 것들 — 성공했을 때만(실패면 보존 정책에 따라) 지운다. */
        final List<Path> toClean = new ArrayList<>(2);
        /** 확보한 원본(복호화 전). 재처리로 수집을 건너뛰었거나 보라미 기존 STT 를 쓰면 null. */
        VoiceFile acquired;
        /** 보존돼 있던 복호화 오디오 — 분석부터 재처리. */
        VoiceFile plain;
        /** 이미 있는 전사 — 보존된 stt_temp(전송부터 재처리) 또는 보라미 기존 STT. */
        SttResult stt;
        long fileSize;
        /** 워커가 이 건에 실제로 쓴 시간(ms) — 대기열에서 기다린 시간은 넣지 않는다. */
        long activeMs;
        /** 확보가 끝난 시각(생산자-소비자 루프 시작 기준 ms) — 고속 모드 파이프라인 모의에 쓴다. */
        long readyMs;
        /** 확보 단계에서 끝났다(이미 처리됨 · 확보 실패) — STT 워커에 넘기지 않는다. */
        FileProcOutcome done;

        Staged(VoiceTarget target, ResumeMode resume, int index) {
            this.target = target;
            this.resume = resume;
            this.index = index;
        }
    }

    /**
     * 한 건을 끝까지 처리한다(순차) — 확보 단계와 STT 단계를 한 스레드가 잇는다. 실패해도 예외를 던지지 않는다.
     */
    private FileProcOutcome processOne(VoiceTarget target, RunContext ctx, ResumeMode resume) {
        Staged st = acquireStage(target, ctx, resume, -1);
        return st.done != null ? st.done : processStage(st, ctx);
    }

    /**
     * <b>확보 단계</b>(확보 워커) — 이미 처리됐는지 보고, 재처리면 보존물을 찾고, 없으면 파일을 받아 온다
     * (접견: 브로커 추출 → 수신 폴더 도착 / 전화: 파일 연계 수신). 복호화는 STT 워커가 한다.
     *
     * <p><b>이어서 하기</b>: {@code resume} 가 가리키는 단계부터 시작하되, 그 단계가 기대하는 보존물이 없으면
     * 앞 단계로 내려간다. 보존물은 빠른 길일 뿐 유일한 길이 아니다.</p>
     *
     * <p>실패해도 예외를 밖으로 던지지 않는다 — {@link Staged#done} 에 결과를 싣고, 받은 파일은 정리한다.</p>
     */
    private Staged acquireStage(VoiceTarget target, RunContext ctx, ResumeMode resume, int index) {
        long t0 = System.currentTimeMillis();
        Staged st = new Staged(target, resume, index);
        if (idempotency.isProcessed(target)) {
            log.debug("[Batch] 이미 처리됨 — {}", target.shortId());
            st.done = FileProcOutcome.skipped(target, "이미 처리된 건");
            return st;
        }
        String failure = null;
        try {
            // ── 전송부터 이어서 — 보존된 전사 결과를 찾는다(제논 전송에서 깨진 건) ────────────
            if (st.resume.fromTranscript()) {
                st.stt = sttTemp.find(target, ctx.fromExecId).orElse(null);
                if (st.stt == null) {
                    log.info("[Resume] 보존된 전사 결과가 없다 — {} · STT 부터 다시 한다", target.shortId());
                    st.resume = ResumeMode.FROM_ANALYZE;
                } else {
                    log.info("[Resume] 전사 결과 재사용 — {} ({}자) · 수집·복호화·STT 생략 → 제논 전송",
                            target.shortId(), st.stt.charCount());
                }
            }

            // ── ANALYZE 부터 이어서 — 보존된 복호화 오디오를 찾는다 ────────────────
            if (st.stt == null && st.resume == ResumeMode.FROM_ANALYZE) {
                st.plain = findPreservedAudio(target).orElse(null);
                if (st.plain == null) {
                    log.info("[Resume] 보존된 복호화 오디오가 없다 — {} · 수집부터 다시 한다", target.shortId());
                } else {
                    st.fileSize = st.plain.sizeBytes();
                    log.info("[Resume] 복호화 오디오 재사용 — {} · 수집·복호화 생략", st.plain.path().getFileName());
                }
            }

            // ── COLLECT — 파일 확보 ──────────────────────────────────────────
            if (st.stt == null && st.plain == null) {
                if (stageFault.shouldFail(StageFaultState.Stage.COLLECT)) {
                    throw StageFaultState.fault(StageFaultState.Stage.COLLECT, "수집 단계 장애 주입");
                }
                SttResult reused = target.hasSourceStt() ? tryReadSourceStt(target) : null;
                if (reused == null) {
                    // 확보 대기는 끝나기 전에도 잰다 — 임계 성능 시험이 'XVARM 확보 대기 N초 초과' 로 멈춘다
                    long mAcq = meter.start(PerfStage.ACQUIRE);
                    try {
                        st.acquired = acquire(target, ctx.execId);
                    } finally {
                        meter.add(PerfStage.ACQUIRE, mAcq);
                    }
                    st.toClean.add(st.acquired.path());
                    st.fileSize = st.acquired.sizeBytes();
                } else {
                    st.stt = reused;   // 보라미가 이미 가진 STT(계획서 Q1) — 오디오를 만질 필요가 없다
                }
            }
        } catch (Exception e) {
            // 사유만 남긴다 — 예외 메시지에 파일 경로·업무 값이 섞여 들어가지 않게 요약한다.
            failure = e.getClass().getSimpleName() + ": " + shorten(e.getMessage());
            log.warn("[Batch] 처리 실패 [{}] — {} ({})", FileProcOutcome.STEP_COLLECT, target.shortId(), failure);
        }
        long ms = System.currentTimeMillis() - t0;
        st.activeMs = ms;
        ctx.addCollect(ms);
        if (failure != null) {
            st.done = FileProcOutcome.fail(target, FileProcOutcome.STEP_COLLECT, failure, ms);
            finish(st, false, null);   // 받다 만 파일은 지운다 — STT 가 쓸 오디오가 아직 없다
        }
        return st;
    }

    /**
     * <b>STT 단계</b>(STT 워커) — 복호화(수집의 나머지) → 분석(STT) → 전송(제논) → 임시 파일 정리.
     * 실패해도 예외를 밖으로 던지지 않는다.
     *
     * <p>두 구간으로 나눠 잰다 — 어느 구간에서 실패했는지가 T2 의 단계별 건수를 가른다. 복호화 실패는
     * 확보와 같이 COLLECT 실패다.</p>
     *
     * <p><b>중간 산출물</b>: 성공하면 지우고, 실패하면 남긴다({@code voice.resume.keep-on-failure}).
     * 남겨야 다음 재처리가 그 단계부터 갈 수 있다. ⚠ 복호화 오디오는 평문이라 PII 가 디스크에
     * 머문다 — 운영에서 즉시 지워야 하면 설정을 내린다.</p>
     */
    private FileProcOutcome processStage(Staged st, RunContext ctx) {
        VoiceTarget target = st.target;
        long t0 = System.currentTimeMillis();
        String step = FileProcOutcome.STEP_COLLECT;
        boolean ok = false;
        // STT 가 쓸 오디오 — 실패했을 때 이것을 보존해야 재처리가 STT 부터 갈 수 있다.
        //   복호화 산출물에만 기대면 안 된다: 복호화가 SKIP 이면 산출물이 아예 없고,
        //   그러면 '분석 장애 -> 이어서 하기' 시나리오가 성립하지 않는다.
        Path readyAudio = null;
        try {
            SttResult stt = st.stt;
            long fileSize = st.fileSize;
            VoiceFile plain = st.plain;

            // ── COLLECT(이어서) — 복호화. 확보는 확보 워커가 끝냈다 ─────────────────
            if (st.acquired != null) {
                plain = decryptService.decrypt(st.acquired);
                if (!plain.path().equals(st.acquired.path())) {
                    st.toClean.add(plain.path());   // 복호화가 새 파일을 만든 경우
                }
            }
            if (plain != null) {
                readyAudio = plain.path();
            }
            long tCollected = System.currentTimeMillis();
            ctx.addCollect(tCollected - t0);

            // ── ANALYZE — STT ─────────────────────────────────────────────────
            step = FileProcOutcome.STEP_ANALYZE;
            if (stt == null) {
                beginAnalyze(ctx);
                if (stageFault.shouldFail(StageFaultState.Stage.ANALYZE)) {
                    throw StageFaultState.fault(StageFaultState.Stage.ANALYZE,
                            "STT 호출 실패(500/Timeout) 주입");
                }
                long mStt = meter.start();
                try {
                    stt = sttClient.transcribe(plain);
                } catch (RuntimeException e) {
                    // 중단 요청으로 깬 가상 지연은 STT 에러가 아니다
                    if (!progress.isCancelRequested()) {
                        meter.error(PerfStage.STT);
                    }
                    throw e;
                } finally {
                    meter.add(PerfStage.STT, mStt);   // 타임아웃도 기다린 만큼이 STT 시간이다
                }
                if (stt.isEmpty()) {
                    ctx.addAnalyze(System.currentTimeMillis() - tCollected);
                    return FileProcOutcome.fail(target, step, "STT 결과가 비어 있음",
                            st.activeMs + System.currentTimeMillis() - t0);
                }
                // 전사 결과를 남긴다 — SEND 가 깨져도 STT 를 다시 돌리지 않게.
                long mTemp = meter.start();
                sttTemp.save(ctx.execId, target, stt, fileSize);
                meter.add(PerfStage.TEMP, mTemp);
            }
            long tAnalyzed = System.currentTimeMillis();
            ctx.addAnalyze(tAnalyzed - tCollected);

            // ── SEND — 제논(Zenon) 전송 ───────────────────────────────────────
            step = FileProcOutcome.STEP_SEND;
            beginSend(ctx);
            if (stageFault.shouldFail(StageFaultState.Stage.SEND)) {
                throw StageFaultState.fault(StageFaultState.Stage.SEND, "제논 전송 실패(500/Timeout) 주입");
            }
            ZenonClient.Record record = ZenonVoiceDocument.of(ctx.execId, target, stt, fileSize,
                    pidGenerator.of(target.corrNo()), objectMapper);
            try {
                zenon.precheck(record);   // 키 표식(SF) — 그 건만 한 번 거부(청크는 멈추지 않는다)
            } catch (RuntimeException e) {
                if (!progress.isCancelRequested()) {
                    meter.error(PerfStage.SEND);
                }
                throw e;
            }
            // 전송 런에 쌓는다 — 보내는 것은 배치 끝(flushTransfer). 받아들여지면 그때 전사 보존물을 지우고 멱등 표식을 남긴다
            //   (안 받아들여지면 둘 다 남아 FROM_SEND 재처리가 전사를 다시 쓴다). 받은 원본 · 복호화 오디오는 전사가 있으니 지금 지운다.
            ctx.transfer.stage(record, receipt -> {
                ctx.delivered.put(target.idempotencyKey(), receipt);
                sttTemp.discardAnywhere(target);
                idempotency.markProcessed(target);
                log.debug("[Purge] 전송 완료 — {} · 임시 파일 정리", target.shortId());
            }, why -> ctx.sendFailed.put(target.idempotencyKey(), why));
            ctx.addSend(System.currentTimeMillis() - tAnalyzed);
            ok = true;
            return FileProcOutcome.success(target, fileSize, stt.charCount(), PENDING_SEND,
                    st.activeMs + System.currentTimeMillis() - t0);

        } catch (Exception e) {
            // 사유만 남긴다 — 예외 메시지에 파일 경로·업무 값이 섞여 들어가지 않게 요약한다.
            String reason = e.getClass().getSimpleName() + ": " + shorten(e.getMessage());
            log.warn("[Batch] 처리 실패 [{}] — {} ({})", step, target.shortId(), reason);
            long ms = System.currentTimeMillis() - t0;
            if (FileProcOutcome.STEP_COLLECT.equals(step)) {
                ctx.addCollect(ms);
            } else if (FileProcOutcome.STEP_SEND.equals(step)) {
                ctx.addSend(ms);
            } else {
                ctx.addAnalyze(ms);
            }
            return FileProcOutcome.fail(target, step, reason, st.activeMs + ms);

        } finally {
            finish(st, ok, readyAudio);
        }
    }

    /**
     * 건이 끝났다 — 디스크에 만든 것을 정리한다.
     *
     * <p><b>성공했을 때만 다 지운다.</b> 실패한 건은 STT 가 쓸 오디오를 작업 폴더에 약속된 이름으로 남겨야
     * 다음 재처리가 STT 부터 이어서 갈 수 있다 — keep-on-failure 를 내리면 종전대로 즉시 지운다.
     * 복호화된 원본 음성은 그 자체로 민감하므로 남길 이유가 없으면 지운다(계획서 5.3-(4)).</p>
     */
    private void finish(Staged st, boolean ok, Path readyAudio) {
        if (ok) {
            cleanupAll(st.toClean);
            // 이 건이 앞선 실패에서 남긴 보존 오디오도 지운다 — 재처리로 되살려 썼든,
            // 수집부터 다시 해서 성공했든 이제 필요 없다. 안 지우면 평문 PII 가 계속 쌓인다.
            discardPreservedAudio(st.target);
        } else if (!keepOnFailure) {
            cleanupAll(st.toClean);
        } else {
            // 실패 — STT 가 쓸 오디오를 작업 폴더에 약속된 이름으로 남기고 나머지는 지운다.
            Path kept = preserveAudio(st.target, readyAudio);
            cleanupAll(st.toClean.stream().filter(p -> !p.equals(kept)).toList());
            if (kept != null) {
                log.info("[Resume] 오디오 보존 — {} · 재처리가 STT 부터 이어 간다", kept.getFileName());
            }
        }
    }

    /** 확보만 하고 처리하지 않은 건(중단) — 받아 둔 원본을 지운다. 복호화 전이라 보존할 것이 없다. */
    private void release(Staged st) {
        cleanupAll(st.toClean);
    }

    /**
     * 보존된 복호화 오디오를 찾는다 — {@code {ROOT}/xvram/decoding/decrypted_*}.
     *
     * <p>파일명은 복호화기가 정한 {@code decrypted_<원본명>} 이다. 원본명을 모르면 찾지 못한 것으로
     * 본다 — 추측해서 엉뚱한 파일을 집으면 다른 사람의 음성을 STT 에 태우게 된다.</p>
     */
    /**
     * 실패한 건의 오디오를 작업 폴더에 <b>약속된 이름</b>({@code decrypted_<원본명>})으로 남긴다.
     *
     * <p>이미 그 자리에 그 이름으로 있으면(복호화가 만든 경우) 그대로 두고, 다른 곳에 있으면
     * 옮긴다. 이름을 한곳에서 정해 두어야 {@link #findPreservedAudio} 가 찾을 수 있다.</p>
     *
     * @return 보존한 경로. 남길 것이 없으면 {@code null}
     */
    /** 성공했으니 보존 오디오를 지운다 — 평문 PII 를 필요 이상으로 남기지 않는다. */
    private void discardPreservedAudio(VoiceTarget target) {
        String src = target.srcFileName();
        if (src == null || src.isBlank()) {
            return;
        }
        Path p = Path.of(dirs.work()).resolve("decrypted_" + src);
        try {
            if (Files.deleteIfExists(p)) {
                log.debug("[Resume] 보존 오디오 정리 — {}", p.getFileName());
            }
        } catch (IOException e) {
            log.debug("[Resume] 보존 오디오 정리 실패 — {} ({})", p, e.getMessage());
        }
    }

    private Path preserveAudio(VoiceTarget target, Path audio) {
        String src = target.srcFileName();
        if (audio == null || src == null || src.isBlank() || !Files.isRegularFile(audio)) {
            return null;
        }
        Path dest = Path.of(dirs.work()).resolve("decrypted_" + src);
        try {
            if (audio.toAbsolutePath().normalize().equals(dest.toAbsolutePath().normalize())) {
                return audio;
            }
            Files.createDirectories(dest.getParent());
            Files.copy(audio, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return dest;
        } catch (IOException e) {
            // 보존에 실패해도 배치 결과는 바뀌지 않는다 — 재처리가 수집부터 다시 하면 된다.
            log.warn("[Resume] 오디오 보존 실패 — {} ({}) · 재처리는 수집부터 한다", dest, e.getMessage());
            return null;
        }
    }

    private java.util.Optional<VoiceFile> findPreservedAudio(VoiceTarget target) {
        String src = target.srcFileName();
        if (src == null || src.isBlank()) {
            return java.util.Optional.empty();
        }
        Path p = Path.of(dirs.work()).resolve("decrypted_" + src);
        if (!Files.isRegularFile(p)) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(new VoiceFile(target, p, Files.size(p), formatOf(src), true));
        } catch (IOException e) {
            log.warn("[Resume] 보존 오디오를 읽지 못했다 — {} ({})", p, e.getMessage());
            return java.util.Optional.empty();
        }
    }

    /** 원본 파일명의 확장자로 포맷을 본다 — 보존물은 이미 복호화돼 있어 매직 넘버 판별이 필요 없다. */
    private static String formatOf(String srcFileName) {
        int dot = srcFileName.lastIndexOf('.');
        return dot > 0 ? srcFileName.substring(dot + 1).toLowerCase() : "unknown";
    }

    /**
     * 파일을 우리 스토리지까지 가져온다.
     *
     * <p>경로가 둘로 갈린다 — 접견은 우리가 XVARM 브로커에 추출을 <b>지시</b>하고,
     * 전화는 별도 ESB 프로바이더가 떨궈 주는 것을 <b>기다린다</b>.
     * 어느 쪽이든 마지막은 수신 디렉터리를 보는 것으로 같다.</p>
     */
    private VoiceFile acquire(VoiceTarget target, String execId) {
        // 요청 전에 그 이름에 남아 있는 것은 이번 요청의 산출물일 수 없다 — 먼저 치운다.
        watcher.clearStale(target);
        if (target.kind() == VoiceKind.MEET) {
            // 더미 시나리오 COLLECT_FAIL — 브로커에 요청하기 전에 끊는다. 개발계 브로커(DUMMY 어댑터)는 FILEKEY 가 없거나
            //   읽히지 않아도 무음 WAV 를 만들어 주므로 메타 누락만으로는 실패하지 않는다. 표식 건만 · 한 번만 · 운영 제외.
            if (scenarioFaults.failOnce(egovframework.unstructured.collector.mock.FailureScenario.COLLECT_FAIL,
                    target.idempotencyKey())) {
                throw new IllegalStateException("XVARM 추출 요청 거부 — FILEKEY 를 확인할 수 없습니다 [더미 시나리오 COLLECT_FAIL · 1회]");
            }
            log.info("[Track:MEET] ② XVARM 추출 요청 — {} via 브로커 {} (execId={})", target.shortId(), broker.mode(), execId);
            XvarmBrokerClient.ExtractResult extracted = broker.extract(target, execId);
            log.info("[Track:MEET] ③ ESB 수신 대기 — {} (브로커 산출 {})",
                    target.shortId(), extracted.filePath());
            // 브로커가 만든 파일이 우리 쪽에 보이는데 수신 폴더 밖이면 기다려 봐야 타임아웃이다.
            //   브로커를 local 프로파일 없이 띄웠을 때 300초를 날리던 자리 — 지금 끊는다.
            //   운영은 보라미 서버 경로라 우리 쪽에 없어 이 판정에 걸리지 않는다(BrokerOutputCheck).
            BrokerOutputCheck.mismatch(extracted.filePath(), dirs.receiveMeet())
                    .ifPresent(reason -> { throw new IllegalStateException(reason); });
            // 브로커가 알려준 실제 파일명을 그대로 쓴다.
            //   추측한 이름으로 찾으면 브로커가 다른 이름으로 만들었을 때 영영 못 찾고 타임아웃이 난다.
            //   Mock 브로커는 우리와 같은 명명 정책을 써서 우연히 일치했을 뿐이고,
            //   실제 XVARM 이 파일명을 어떻게 정하는지는 아직 모른다(계획서 Q3).
            // 중단 요청은 대기 중에도 곧바로 먹는다 — 파일이 끝내 오지 않는 건에서 대기 제한(300초)까지 멈춰 있지 않게
            return watcher.await(target, fileNameOf(extracted.filePath()), progress::isCancelRequested);
        }
        log.info("[Track:PHONE] ② 전화 파일 연계 요청 — {} via {}", target.shortId(), phoneFileProvider.mode());
        phoneFileProvider.request(target);
        log.info("[Track:PHONE] ③ 수신 대기 — {}", target.shortId());
        return watcher.await(target, null, progress::isCancelRequested);
    }

    /** 경로에서 파일명만 뽑는다. 경로가 비었으면 null — watcher 가 정책으로 되돌아간다. */
    private static String fileNameOf(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            Path name = Path.of(path).getFileName();
            return name == null ? null : name.toString();
        } catch (Exception e) {
            // 브로커가 우리 OS 와 다른 경로 표기를 줄 수 있다(보라미는 리눅스).
            // 파싱이 안 되면 구분자로 직접 자른다.
            int i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            String tail = (i >= 0) ? path.substring(i + 1) : path;
            return tail.isBlank() ? null : tail;
        }
    }

    /**
     * 보라미가 만들어 둔 STT 텍스트를 읽어 온다. <b>못 읽으면 null 을 돌려주고 일반 경로로 넘긴다.</b>
     *
     * <p>예전에는 여기서 예외를 던져 그 건을 통째로 실패시켰다. 그런데 {@code TELP_STT_FLPTH_NM}
     * 은 <b>보라미 서버 기준 경로</b>다(실DB 표본값 {@code /data001/stt/...}). 그 파일이 우리 쪽에
     * 동기화되어 있다는 보장이 없고, 어떻게 넘어오는지도 아직 정해지지 않았다(계획서 Q1).</p>
     *
     * <p>읽히지 않는다고 수집 자체를 버리는 것은 과하다 — 우리에겐 오디오를 받아 직접 STT 하는
     * 정상 경로가 있다. 지름길이 막혔으면 먼 길로 가면 된다. 다만 <b>조용히 넘어가지는 않는다</b>:
     * 이 경고가 반복되면 Q1 의 전제가 틀렸다는 신호이므로 로그로 남긴다.</p>
     *
     * @return 재사용할 STT. 경로가 없거나 읽지 못하면 {@code null}
     */
    private SttResult tryReadSourceStt(VoiceTarget target) {
        java.nio.file.Path p;
        try {
            p = java.nio.file.Path.of(target.sourceSttPath());
        } catch (Exception e) {
            log.warn("[Track:PHONE] 보라미 STT 경로를 해석할 수 없다 — 직접 STT 로 돌린다 ({} · {})",
                    target.sourceSttPath(), target.shortId());
            return null;
        }
        if (!Files.exists(p)) {
            log.warn("[Track:PHONE] 보라미 STT 파일이 우리 쪽에 없다 — 직접 STT 로 돌린다 "
                            + "({} · {}). TELP_STT_FLPTH_NM 은 보라미 서버 기준 경로다(계획서 Q1)",
                    p, target.shortId());
            return null;
        }
        try {
            String text = Files.readString(p);
            log.info("[Track:PHONE] 보라미 STT 재사용 — {} ({}자, 복호화·STT 생략)",
                    target.shortId(), text.length());
            return new SttResult(text, "BORAMI", 0, true);
        } catch (IOException e) {
            log.warn("[Track:PHONE] 보라미 STT 파일을 읽지 못했다 — 직접 STT 로 돌린다 ({} · {})",
                    e.getMessage(), target.shortId());
            return null;
        }
    }

    /**
     * 이번 건이 디스크에 만든 원본·복호화 산출물을 지운다.
     *
     * <p>기본은 삭제다. 복호화된 원본 음성은 그 자체로 민감하고 우리가 보관할 이유가 없다
     * — STT 가 끝나면 원본은 역할을 다한 것이다. 보존이 필요하면 보존 기간·암호화 저장을
     * 따로 정해야 한다(계획서 Q5).</p>
     *
     * <p>삭제 실패는 경고만 남기고 넘어간다. 데이터 처리 자체는 이미 끝났고, 여기서 예외를
     * 올리면 성공한 건이 실패로 뒤집힌다.</p>
     */
    private void cleanupAll(List<Path> paths) {
        if (props.batch().retainSourceFile() || paths.isEmpty()) {
            return;
        }
        for (Path p : paths) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                log.warn("[Batch] 원본 삭제 실패 — {} ({})", p.getFileName(), e.getMessage());
            }
        }
    }

    private List<LogCollectorClient.FileProcReq> toFileProcReqs(List<FileProcOutcome> outcomes) {
        List<LogCollectorClient.FileProcReq> rows = new ArrayList<>(outcomes.size());
        for (FileProcOutcome o : outcomes) {
            if (o.status() == ProcStatus.SKIPPED) {
                continue;   // 이번 배치가 처리한 건이 아니다 — 집계에 넣으면 대사가 어긋난다
            }
            // 컬렉터 T4 스펙 순서대로: REC_FILE_ID · FILE_PATH · FILE_NM · INMATE_PID · FILE_SIZE · PROC_STS_CD · ERR_STACK
            //   + STEP_TYPE_CD(끝난 단계 — 컬렉터에 컬럼이 생기면 적재된다. 그 전까지 실패 단계는 ERR_STACK 의 [단계])
            StepType step = o.stepTypeForLog();
            rows.add(new LogCollectorClient.FileProcReq(
                    o.target().idempotencyKey(),          // 접견 TARE_FILE_NO / 전화 VRFC_ESTL_ID — NOT NULL
                    o.target().srcFilePath(),             // 보라미 쪽 원본 경로(우리 임시 경로가 아니다)
                    o.target().srcFileName(),
                    pidGenerator.of(o.target().corrNo()),
                    o.fileSize(),
                    o.status().name(),
                    o.isSuccess() ? null : LogCollectorClient.FileProcReq.errStackOf(step, o.errMsg()),
                    step == null ? null : step.name()));
        }
        return rows;
    }

    private static Integer elapsedSec(long startedAt) {
        return (int) ((System.currentTimeMillis() - startedAt) / 1000);
    }

    private static String shorten(String msg) {
        if (msg == null) {
            return "(사유 없음)";
        }
        return msg.length() <= 300 ? msg : msg.substring(0, 300) + "...";
    }
}
