package egovframework.unstructured.collector.voice.batch;

import egovframework.unstructured.collector.common.model.BatchWindow;
import egovframework.unstructured.collector.common.model.FileProcOutcome;
import egovframework.unstructured.collector.common.model.ProcStatus;
import egovframework.unstructured.collector.common.model.VoiceTarget;
import egovframework.unstructured.collector.common.transfer.AgentConnectorClient;
import egovframework.unstructured.collector.voice.source.SimulationDataService;
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
import java.util.concurrent.atomic.AtomicReference;

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
    @MockitoSpyBean
    private VoiceDeidentClient deident;

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
    }
}
