package egovframework.unstructured.collector.mock;

import com.fasterxml.jackson.databind.JsonNode;
import egovframework.unstructured.collector.batch.UnstructuredBatchService;
import egovframework.unstructured.collector.batch.UnstructuredJobRunner;
import egovframework.unstructured.collector.common.logging.LogCollectorClient;
import egovframework.unstructured.collector.common.model.BatchWindow;
import egovframework.unstructured.collector.common.model.FileProcOutcome;
import egovframework.unstructured.collector.common.model.VoiceKind;
import egovframework.unstructured.collector.common.transfer.AgentConnectorClient;
import egovframework.unstructured.collector.common.transfer.AgentConnectorMockReceiver;
import egovframework.unstructured.collector.common.transfer.AgentConnectorProperties;
import egovframework.unstructured.collector.common.transfer.TransferRuns;
import egovframework.unstructured.collector.voice.batch.ResumeMode;
import egovframework.unstructured.collector.voice.batch.VoiceBatchResult;
import egovframework.unstructured.collector.voice.batch.VoiceCollectService;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>전송 재처리 시나리오</b> — 에이전트 커넥터 시뮬레이터 '긴급 재처리(PPP 전송)' 탭을 비정형 수집기에 그대로 옮겼다(2026-10-05).
 *
 * <ul>
 *   <li><b>시나리오 1 · 전 청크 실패</b> — 수집 · STT 까지는 성공했으나 에이전트 커넥터가 1번 청크부터 503(수신 서버 장애) → 한 청크도
 *       받아들여지지 않아 배치 <b>FAIL</b>. 전사 보존물({@code stt_temp})이 남아 재처리는 수집 · STT 를 건너뛰고 <b>처음부터 다시</b> 보낸다.</li>
 *   <li><b>시나리오 2 · 부분 전송 실패</b> — 3번째 청크에서 503 → 앞 2청크는 받아들여져 배치 <b>PARTIAL</b>. 재처리는
 *       <b>원배치 런을 이어 받아 3번 청크부터</b> 보내고 마지막 청크로 마감한다(이어달리기).</li>
 * </ul>
 * <p>① 오류 상황 재현(더미 생성 → 장애 · 청크 2건 → 실행) → ② 에이전트 커넥터 정상화 → ③ 긴급 재처리(admin 과 같은 경로 — 단계 SEND).
 * 용도(SIM/REAL)를 고른다 — SIM 은 시험 실행(TST), REAL(대시보드)은 DMY 더미로 실제 배치(UNS) — 로그 컬렉터 이력 · 대시보드에 남는다.</p>
 */
@Log4j2
@Service
@Profile({"dev", "local"})
public class TransferReprocessSimService {

    /** 시나리오. */
    public enum Scenario {
        ALL_FAIL("시나리오 1 · 전 청크 실패", "에이전트 커넥터 다운 — 1번 청크부터 503"),
        PARTIAL("시나리오 2 · 부분 전송 실패", "3번 청크부터 503");

        final String label;
        final String fault;

        Scenario(String label, String fault) {
            this.label = label;
            this.fault = fault;
        }
    }

    /** 재현한 배치 — 재처리가 같은 구간 · 같은 용도로 이어 간다(로그 컬렉터 미연동일 때). */
    private record Armed(Scenario scenario, DummyTarget target, BatchWindow window, List<VoiceKind> kinds, boolean testRun) {}

    private final DummyDataService dummy;
    private final VoiceCollectService voice;
    private final UnstructuredBatchService batch;
    private final UnstructuredJobRunner runner;
    private final AgentConnectorClient connector;
    private final AgentConnectorMockReceiver receiver;
    private final TransferRuns runs;
    private final LogCollectorClient logCollector;
    private final egovframework.unstructured.collector.voice.batch.BatchProgress progress;
    private final Map<String, Armed> armed = new ConcurrentHashMap<>();

    public TransferReprocessSimService(DummyDataService dummy, VoiceCollectService voice, UnstructuredBatchService batch,
                                    UnstructuredJobRunner runner, AgentConnectorClient connector, AgentConnectorMockReceiver receiver,
                                    TransferRuns runs, LogCollectorClient logCollector,
                                    egovframework.unstructured.collector.voice.batch.BatchProgress progress) {
        this.dummy = dummy;
        this.voice = voice;
        this.batch = batch;
        this.runner = runner;
        this.connector = connector;
        this.receiver = receiver;
        this.runs = runs;
        this.logCollector = logCollector;
        this.progress = progress;
    }

    /**
     * ① 오류 상황 재현 — 더미(접견 · 전화)를 만들고, 에이전트 커넥터 장애를 걸고, 청크를 작게(기본 2건) 해서 배치를 돌린다.
     *
     * @param target       SIMULATOR(SIM · 시험 실행 TST) · DASHBOARD(DMY · 실제 배치 UNS)
     * @param perType      유형별 건수(접견 · 전화 각각) — 기본 3 → 6건
     * @param chunkRecords 청크 레코드 수 — 기본 2 → 3청크
     * @param targetDate   대상일 — 비우면 어제
     */
    public Map<String, Object> arm(Scenario scenario, DummyTarget target, Integer perType, Integer chunkRecords,
                                   LocalDate targetDate) {
        requireIdle();
        Scenario sc = scenario == null ? Scenario.ALL_FAIL : scenario;
        DummyTarget tg = target == null ? DummyTarget.SIMULATOR : target;
        int n = perType == null ? 3 : Math.max(1, Math.min(50, perType));
        int chunk = chunkRecords == null ? 2 : Math.max(1, Math.min(100, chunkRecords));
        LocalDate date = targetDate == null ? LocalDate.now().minusDays(1) : targetDate;

        Map<String, Object> gen = dummy.generate(new DummyDataService.GenerateRequest(tg,
                List.of(DummyDataType.MEET, DummyDataType.PHONE), date, n, Map.of(), true, null));
        receiver.setFault(sc == Scenario.ALL_FAIL ? AgentConnectorMockReceiver.FaultMode.DOWN : AgentConnectorMockReceiver.FaultMode.FAIL_FROM_SEQ,
                3);
        connector.overrideChunkRecords(chunk);
        // 장애 흉내는 내장 수신기에만 걸린다 — 설정이 REST(개발계 실연동)여도 이 시나리오 동안은 MOCK 으로 보낸다(③ 재처리 · 초기화 때 되돌림)
        connector.overrideMode(AgentConnectorProperties.Mode.MOCK);
        boolean testRun = tg == DummyTarget.SIMULATOR;
        // 구간 = 방금 만든 더미의 발생 시각 범위 — 같은 날의 다른 데이터(남은 SIM · DMY · 실제 행)가 섞이지 않게 좁힌다.
        //   재처리는 원배치 T1 의 구간을 그대로 쓰므로 같은 건만 다시 돈다.
        BatchWindow window = windowOf(gen, date);
        List<VoiceKind> kinds = List.of(VoiceKind.MEET, VoiceKind.PHONE);
        log.info("[TransferSim] 전송 재처리 재현 — {} · {} · 대상일 {} · 유형별 {}건 · 청크 {}건 · 장애 {}", sc, tg, date, n, chunk, sc.fault);
        VoiceBatchResult r = voice.run(window, kinds, "SIM/transfer-" + sc.name(), testRun, ResumeMode.FULL, null,
                voice.defaultWorkers());
        armed.put(r.execId(), new Armed(sc, tg, window, kinds, testRun));

        Map<String, Object> m = view(r.execId(), r.execStsCd(), r);
        m.put("scenario", sc.name());
        m.put("scenarioLabel", sc.label);
        m.put("target", tg.name());
        m.put("testRun", testRun);
        m.put("targetDate", date.toString());
        m.put("generated", Map.of("totals", gen.get("totals"), "counts", gen.get("counts"), "prefix", gen.get("prefix")));
        m.put("summary", "에이전트 커넥터 " + sc.fault + " · 청크 " + chunk + "건 — " + r.summary());
        m.put("next", "② 에이전트 커넥터 정상화 → ③ 긴급 재처리(단계 SEND — 전사 보존물로 수집·STT 를 건너뛰고 "
                + (sc == Scenario.PARTIAL ? "원배치 런을 3번 청크부터 이어서" : "처음부터 다시") + " 보낸다)");
        return m;
    }

    /** ② 에이전트 커넥터 정상화 — 수신기를 200 으로 되돌린다. */
    public Map<String, Object> fix() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fault", receiver.setFault(AgentConnectorMockReceiver.FaultMode.UP, 0));
        m.put("message", "에이전트 커넥터 수신기 정상화(200 OK) — 이제 ③ 긴급 재처리를 누르세요");
        return m;
    }

    /**
     * ③ 긴급 재처리 — admin 과 같은 경로({@code /internal/batch/reprocess} · 단계 SEND)로 원배치를 다시 돈다. 끝날 때까지 기다린다.
     * 로그 컬렉터가 없으면(원배치 구간을 못 읽음) 재현 때 기억한 구간으로 같은 규칙(FROM_SEND · 원배치 ID)을 직접 돈다.
     */
    public Map<String, Object> reprocess(String originExecId, String stepTypeCd, long waitMs) {
        requireIdle();
        if (originExecId == null || originExecId.isBlank()) {
            throw new IllegalArgumentException("execId 필수 — 먼저 ① 오류 상황 재현");
        }
        String origin = originExecId.trim();
        String step = stepTypeCd == null || stepTypeCd.isBlank() ? "SEND" : stepTypeCd.trim().toUpperCase();
        Map<String, Object> m;
        if (logCollector.isEnabled()) {
            UnstructuredBatchService.Plan plan = batch.planReprocess(origin, UnstructuredBatchService.DATA_TYPE, step);
            Map<String, Object> detail = new LinkedHashMap<>(plan.detail());
            detail.put("stepTypeCd", step);
            detail.put("stepSeq", 3);
            detail.put("via", "simulator — /internal/batch/reprocess 와 같은 경로");
            UnstructuredJobRunner.Job job = runner.submitAndAwaitExecId(UnstructuredJobRunner.Kind.REPROCESS, plan.triggerBy(),
                    detail, sink -> batch.execute(plan, sink::publish), 10_000);
            Map<String, Object> st = waitDone(job.handle(), waitMs);
            String newId = String.valueOf(st.get("execId"));
            m = view(newId, execSts(newId), null);
            m.put("job", st);
            m.put("path", "POST /internal/batch/reprocess {execId:" + origin + ", dataTypeCd:UNSTRUCTURED, stepTypeCd:" + step + ", stepSeq:3}");
        } else {
            Armed a = armed.get(origin);
            if (a == null) {
                throw new IllegalArgumentException("원배치 구간을 모른다 — 로그 컬렉터 미연동이고 이 화면에서 재현한 배치도 아니다: " + origin);
            }
            VoiceBatchResult r = voice.run(a.window(), a.kinds(), "ADMIN/reprocess:" + origin, a.testRun(),
                    UnstructuredBatchService.resumeOf(step), origin, voice.defaultWorkers());
            m = view(r.execId(), r.execStsCd(), r);
            m.put("path", "(로그 컬렉터 미연동) 같은 규칙으로 직접 — resume " + UnstructuredBatchService.resumeOf(step) + " · 원배치 " + origin);
        }
        m.put("originExecId", origin);
        m.put("resume", UnstructuredBatchService.resumeOf(step).name());
        Map<String, Object> originRun = runs.get(origin) == null ? null : runView(runs.get(origin));
        m.put("originRun", originRun);
        m.put("originLedger", receiver.ledger(origin));
        connector.overrideMode(null);   // 시나리오 끝 — 설정 모드(개발계 REST)로 되돌린다
        return m;
    }

    /** 상태 — 원배치 · 런 · 수신 장부. */
    public Map<String, Object> state(String execId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("execId", execId);
        m.put("fault", receiver.fault());
        m.put("chunkRecords", connector.chunkRecords());
        if (execId != null && !execId.isBlank()) {
            TransferRuns.RunState r = runs.get(execId);
            m.put("run", r == null ? null : runView(r));
            m.put("ledger", receiver.ledger(execId));
            m.put("receipts", connector.receipts(execId).size());
            m.put("execStsCd", execSts(execId));
        }
        m.put("recentRuns", runs.recent(10).stream().map(TransferReprocessSimService::runView).toList());
        return m;
    }

    /** 상태 초기화 — 장애 해제 · 청크 레코드 수 설정값으로. */
    public Map<String, Object> reset() {
        receiver.setFault(AgentConnectorMockReceiver.FaultMode.UP, 0);
        connector.overrideChunkRecords(null);
        connector.overrideMode(null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fault", receiver.fault());
        m.put("chunkRecords", connector.chunkRecords());
        m.put("message", "초기화 — 에이전트 커넥터 정상 · 청크 레코드 수 설정값(" + connector.chunkRecords() + ")");
        return m;
    }

    // ── 내부 ─────────────────────────────────────────────────────────────

    /** 생성 결과의 CRT_DT 범위 → [처음, 끝 + 1초). 못 읽으면 대상일 하루. */
    static BatchWindow windowOf(Map<String, Object> gen, LocalDate date) {
        Object r = gen == null ? null : gen.get("crtDtRange");
        if (r instanceof Map<?, ?> m && m.get("from") != null && m.get("to") != null) {
            try {
                java.time.LocalDateTime from = egovframework.unstructured.collector.common.util.FlexibleLocalDateTimeDeserializer
                        .parse(String.valueOf(m.get("from")));
                java.time.LocalDateTime to = egovframework.unstructured.collector.common.util.FlexibleLocalDateTimeDeserializer
                        .parse(String.valueOf(m.get("to")));
                if (from != null && to != null && !to.isBefore(from)) {
                    return BatchWindow.manual(from, to.plusSeconds(1));
                }
            } catch (IllegalArgumentException ignored) {
                // 대상일 하루로
            }
        }
        return BatchWindow.manual(date.atStartOfDay(), date.plusDays(1).atStartOfDay());
    }

    private void requireIdle() {
        if (runner.isRunning() || progress.isRunning()) {
            throw new IllegalStateException("다른 배치가 실행 중이다 — 끝난 뒤에 다시");
        }
    }

    private Map<String, Object> waitDone(String handle, long waitMs) {
        long until = System.currentTimeMillis() + Math.max(5_000, waitMs);
        Map<String, Object> st = runner.status(handle);
        while ("RUNNING".equals(st.get("status")) && System.currentTimeMillis() < until) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            st = runner.status(handle);
        }
        return st;
    }

    private String execSts(String execId) {
        if (!logCollector.isEnabled() || execId == null) {
            return null;
        }
        try {
            JsonNode d = logCollector.batchDetail(execId);
            JsonNode b = d == null ? null : d.path("batch");
            return b == null || b.isMissingNode() ? null : b.path("exec_sts_cd").asText(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 배치 하나의 결과 + 그 배치가 보낸 전송 런 · 수신 장부 · 건별. */
    private Map<String, Object> view(String execId, String execStsCd, VoiceBatchResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("execId", execId);
        m.put("execStsCd", execStsCd);
        AgentConnectorClient.Report rep = connector.lastReport();
        if (rep != null && execId != null && execId.equals(rep.execId())) {
            m.put("transfer", rep.view());
            m.put("ledger", receiver.ledger(rep.runId()));
            TransferRuns.RunState run = runs.get(rep.runId());
            m.put("run", run == null ? null : runView(run));
        }
        m.put("fault", receiver.fault());
        if (r != null) {
            m.put("targetCnt", r.targetCnt());
            m.put("successCnt", r.successCnt());
            m.put("failCnt", r.failCnt());
            m.put("skippedCnt", r.skippedCnt());
            m.put("steps", r.steps());
            List<Map<String, Object>> items = new ArrayList<>();
            for (FileProcOutcome o : r.outcomes()) {
                Map<String, Object> it = new LinkedHashMap<>();
                it.put("key", o.target().idempotencyKey());
                it.put("kind", o.target().kind().name());
                it.put("status", o.status().name());
                it.put("failedStep", o.failedStep());
                it.put("location", o.sttPath());
                it.put("errMsg", o.errMsg());
                items.add(it);
            }
            m.put("items", items);
        }
        return m;
    }

    static Map<String, Object> runView(TransferRuns.RunState r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", r.runId);
        m.put("deliveredSeq", r.deliveredSeq);
        m.put("deliveredRecords", r.deliveredRecords);
        m.put("closed", r.closed);
        m.put("failedSeq", r.failedSeq);
        m.put("failReason", r.failReason);
        m.put("execIds", r.execIds);
        m.put("updatedAt", r.updatedAt);
        return m;
    }
}
