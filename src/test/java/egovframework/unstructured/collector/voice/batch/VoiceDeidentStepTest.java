package egovframework.unstructured.collector.voice.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import egovframework.unstructured.collector.common.logging.LogCollectorClient;
import egovframework.unstructured.collector.common.model.BatchWindow;
import egovframework.unstructured.collector.common.model.FileProcOutcome;
import egovframework.unstructured.collector.common.model.ProcStatus;
import egovframework.unstructured.collector.common.model.StepType;
import egovframework.unstructured.collector.common.model.VoiceTarget;
import egovframework.unstructured.collector.common.transfer.AgentConnectorClient;
import egovframework.unstructured.collector.voice.source.SimulationDataService;
import egovframework.unstructured.collector.voice.stt.SttTempStore;
import egovframework.unstructured.collector.voice.transfer.VoiceDeidentClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/**
 * T2 DEIDENT — STT 다음 건마다 비식별을 거치고, <b>비식별에 실패한 건은 원문을 보내지 않는다</b>(그 건만 DEIDENT 실패 · 나머지는 계속).
 */
@SpringBootTest
@ActiveProfiles("local")
class VoiceDeidentStepTest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void dirs(DynamicPropertyRegistry registry) {
        registry.add("voice.source.mode", () -> "MOCK");
        registry.add("voice.broker.mode", () -> "MOCK");
        registry.add("log-collector.enabled", () -> "false");
        registry.add("voice.dirs.base-dir", () -> tmp.toString());
        registry.add("voice.dirs.receive-meet", () -> tmp.resolve("raw/meet").toString());
        registry.add("voice.dirs.receive-phone", () -> tmp.resolve("raw/phone").toString());
        registry.add("voice.dirs.work", () -> tmp.resolve("work").toString());
        registry.add("voice.dirs.xvarm-original", () -> tmp.resolve("xvarm_original").toString());
        registry.add("voice.sync.wait-timeout-sec", () -> "15");
        registry.add("voice.sync.stable-check-ms", () -> "50");
    }

    @Autowired
    private VoiceCollectService collect;
    @Autowired
    private SimulationDataService sim;
    @Autowired
    private IdempotencyGuard idempotency;
    @Autowired
    private AgentConnectorClient connector;
    @Autowired
    private SttTempStore sttTemp;
    @MockitoSpyBean
    private VoiceDeidentClient deident;

    /** 커넥터가 격리로 돌려준 응답(2026-11-26) — 본문 없이 건수 · 유형만. */
    private static JsonNode isolatedResponse() throws Exception {
        return new ObjectMapper().readTree("{\"isolated\":true,\"text\":null,\"segments\":null,\"deidentStsCd\":\"FAIL\","
                + "\"displayStatus\":\"DEIDENT_ISOLATED\",\"errTypeCd\":\"RECONCILE\",\"residualCount\":1,"
                + "\"residualTypes\":[\"PHONE\"]}");
    }

    @BeforeEach
    void seed() {
        idempotency.clearAll();
        sim.seedPerf(3, 3, 0);
        reset(deident);
    }

    private static BatchWindow yesterday() {
        LocalDate today = LocalDate.now();
        return BatchWindow.manual(today.minusDays(1).atStartOfDay(), today.atStartOfDay());
    }

    @Test
    @DisplayName("비식별 실패 1건 — 그 건은 DEIDENT 실패 · 보내지 않음(수신증 없음) · 나머지는 전송 · T2 DEIDENT PARTIAL")
    void deidentFailureDoesNotSendRaw() {
        AtomicReference<String> victim = new AtomicReference<>();
        doAnswer(inv -> {
            VoiceTarget t = inv.getArgument(1);
            if (victim.compareAndSet(null, t.idempotencyKey()) || t.idempotencyKey().equals(victim.get())) {
                throw new VoiceDeidentClient.DeidentException("에이전트 커넥터 비식별 호출 실패 — HTTP 500");
            }
            return inv.callRealMethod();
        }).when(deident).deident(anyString(), any(), anyString(), any());

        VoiceBatchResult r = collect.run(yesterday(), null, "TEST", true, ResumeMode.FULL, null, 1);

        assertThat(r.targetCnt()).isEqualTo(6);
        assertThat(r.failCnt()).isEqualTo(1);
        FileProcOutcome failed = r.outcomes().stream().filter(o -> o.status() == ProcStatus.FAIL).findFirst().orElseThrow();
        assertThat(failed.failedStep()).isEqualTo(FileProcOutcome.STEP_DEIDENT);
        assertThat(failed.target().idempotencyKey()).isEqualTo(victim.get());

        assertThat(connector.receipts(r.execId())).as("비식별 실패 건은 보내지 않았다").hasSize(5)
                .noneMatch(rc -> rc.fileName().contains(victim.get()));

        assertThat(r.steps()).extracting(VoiceBatchResult.StepLog::stepTypeCd)
                .containsExactly("COLLECT", "ANALYZE", "DEIDENT", "SEND");
        VoiceBatchResult.StepLog d = r.steps().get(2);
        assertThat(d.stepStsCd()).isEqualTo("PARTIAL");
        assertThat(d.inCnt()).isEqualTo(6);
        assertThat(d.outCnt()).isEqualTo(5);
        assertThat(d.errCnt()).isEqualTo(1);
        VoiceBatchResult.StepLog send = r.steps().get(3);
        assertThat(send.inCnt()).as("비식별을 통과한 건만 전송 구간으로").isEqualTo(5);
        assertThat(send.stepStsCd()).isEqualTo("SUCCESS");

        // 단계에 대표 사유가 실려야 화면의 '실패 로그' 가 뜬다 — 2026-10-28 전까지 음성은 여기에 null 을 넣고 있었다.
        assertThat(d.errStack()).as("실패한 단계는 [코드] [단계] 상세 한 줄을 남긴다")
                .startsWith("[DATA] [DEIDENT] ").contains("HTTP 500");
        assertThat(send.errStack()).as("실패가 없는 단계는 사유를 남기지 않는다").isNull();
        assertThat(r.steps().get(0).errStack()).isNull();
    }

    @Test
    @DisplayName("격리 1건 — 그 건은 DEIDENT 실패 · 보내지 않음 · 사유 [RECONCILE] [DEIDENT] · T2 DEIDENT · T1 PARTIAL · 전사는 임시 보관에 남는다")
    void isolatedFileIsNotSent() throws Exception {
        JsonNode isolated = isolatedResponse();
        AtomicReference<VoiceTarget> victim = new AtomicReference<>();
        doAnswer(inv -> {
            VoiceTarget t = inv.getArgument(1);
            if (victim.compareAndSet(null, t) || t.idempotencyKey().equals(victim.get().idempotencyKey())) {
                return VoiceDeidentClient.readResponse(isolated, true);   // 실제 해석 — 격리면 [RECONCILE] 예외
            }
            return inv.callRealMethod();
        }).when(deident).deident(anyString(), any(), anyString(), any());

        VoiceBatchResult r = collect.run(yesterday(), null, "TEST", true, ResumeMode.FULL, null, 1);

        assertThat(r.targetCnt()).isEqualTo(6);
        assertThat(r.failCnt()).isEqualTo(1);
        FileProcOutcome failed = r.outcomes().stream().filter(o -> o.status() == ProcStatus.FAIL).findFirst().orElseThrow();
        assertThat(failed.failedStep()).isEqualTo(FileProcOutcome.STEP_DEIDENT);
        assertThat(failed.target().idempotencyKey()).isEqualTo(victim.get().idempotencyKey());
        assertThat(failed.errMsg())
                .isEqualTo("DeidentException: [RECONCILE] 비식별 후 잔존 개인정보 1건(PHONE) — 격리 · 전송 제외");
        assertThat(LogCollectorClient.FileProcReq.errStackOf(StepType.DEIDENT, failed.errMsg()))
                .as("T4 사유 — 오류 구분 정합성(RECONCILE) · 실패 단계 DEIDENT")
                .isEqualTo("[RECONCILE] [DEIDENT] DeidentException: 비식별 후 잔존 개인정보 1건(PHONE) — 격리 · 전송 제외");

        assertThat(connector.receipts(r.execId())).as("격리 건은 보내지 않았다").hasSize(5)
                .noneMatch(rc -> rc.fileName().contains(victim.get().idempotencyKey()));
        VoiceBatchResult.StepLog d = r.steps().get(2);
        assertThat(d.stepTypeCd()).isEqualTo("DEIDENT");
        assertThat(d.stepStsCd()).isEqualTo("PARTIAL");
        assertThat(d.errStack()).startsWith("[RECONCILE] [DEIDENT] ").contains("잔존 개인정보 1건(PHONE)");
        assertThat(r.steps().get(3).inCnt()).as("비식별을 통과한 건만 전송 구간으로").isEqualTo(5);
        assertThat(r.execStsCd()).isEqualTo("PARTIAL");
        assertThat(sttTemp.find(victim.get(), r.execId()))
                .as("격리 건 전사는 임시 보관에 남는다(FROM_SEND 재처리가 쓴다 · 보존 기한은 후속)").isPresent();
    }

    @Test
    @DisplayName("격리 건을 같은 EXEC_ID 로 다시 열어 FROM_SEND — 보관 전사를 꺼내 비식별을 다시 거치고 또 격리 · 보내지 않는다")
    void reopenedFromSendIsolatesAgain() throws Exception {
        JsonNode isolated = isolatedResponse();
        AtomicReference<VoiceTarget> victim = new AtomicReference<>();
        AtomicInteger victimCalls = new AtomicInteger();
        doAnswer(inv -> {
            VoiceTarget t = inv.getArgument(1);
            if (victim.compareAndSet(null, t) || t.idempotencyKey().equals(victim.get().idempotencyKey())) {
                victimCalls.incrementAndGet();
                return VoiceDeidentClient.readResponse(isolated, true);
            }
            return inv.callRealMethod();
        }).when(deident).deident(anyString(), any(), anyString(), any());

        VoiceBatchResult first = collect.run(yesterday(), null, "TEST", true, ResumeMode.FULL, null, 1);
        assertThat(first.failCnt()).isEqualTo(1);
        String key = victim.get().idempotencyKey();
        Set<String> own = first.outcomes().stream().map(o -> o.target().idempotencyKey()).collect(Collectors.toSet());

        VoiceBatchResult again = collect.run(yesterday(), null, "TEST", true, ResumeMode.FROM_SEND, first.execId(),
                Workers.sequential(), null, new VoiceCollectService.Reopened(first.execId(), Set.of(key), own));

        assertThat(again.execId()).isEqualTo(first.execId());
        assertThat(victimCalls.get()).as("다시 연 배치도 비식별을 다시 부른다 — 보관 전사(원문)를 그대로 보내지 않는다").isEqualTo(2);
        FileProcOutcome failed = again.outcomes().stream().filter(o -> o.status() == ProcStatus.FAIL).findFirst().orElseThrow();
        assertThat(failed.target().idempotencyKey()).isEqualTo(key);
        assertThat(failed.failedStep()).isEqualTo(FileProcOutcome.STEP_DEIDENT);
        assertThat(failed.errMsg()).startsWith("DeidentException: [RECONCILE] ");
        assertThat(again.outcomes()).as("이미 보낸 5건은 건너뛴다").filteredOn(o -> o.status() == ProcStatus.SKIPPED).hasSize(5);
        assertThat(connector.receipts(again.execId())).noneMatch(rc -> rc.fileName().contains(key));
        assertThat(sttTemp.find(victim.get(), first.execId())).as("또 격리됐으니 전사는 그대로 남는다").isPresent();
    }

    @Test
    @DisplayName("전부 격리 — T2 DEIDENT FAIL · 전송 0 · T1 FAIL · 대표 사유 [RECONCILE]")
    void allIsolated() throws Exception {
        JsonNode isolated = isolatedResponse();
        doAnswer(inv -> VoiceDeidentClient.readResponse(isolated, true))
                .when(deident).deident(anyString(), any(), anyString(), any());

        VoiceBatchResult r = collect.run(yesterday(), null, "TEST", true, ResumeMode.FULL, null, 1);

        assertThat(r.targetCnt()).isEqualTo(6);
        assertThat(r.failCnt()).isEqualTo(6);
        assertThat(r.execStsCd()).isEqualTo("FAIL");
        assertThat(r.steps().get(2).stepStsCd()).isEqualTo("FAIL");
        assertThat(connector.receipts(r.execId())).isEmpty();
        assertThat(LogCollectorClient.FileProcReq.errStackOf(r.errMsg())).startsWith("[RECONCILE] DeidentException: ");
    }
}
