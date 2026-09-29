package egovframework.unstructured.collector.voice.batch;

import egovframework.unstructured.collector.common.model.BatchWindow;
import egovframework.unstructured.collector.common.model.FileProcOutcome;
import egovframework.unstructured.collector.voice.source.SimulationDataService;
import egovframework.unstructured.collector.voice.stt.MockSttClient;
import egovframework.unstructured.collector.voice.stt.SttTempStore;
import org.junit.jupiter.api.AfterEach;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * DEIDENT 단계 — STT 와 최종 저장 사이. 비식별 여부({@code deidentEnabled})와 상관없이 늘 지난다.
 *
 * <p>이 테스트에는 비식별 커넥터가 없다({@code agent-connector.enabled=false}, 테스트 설정). 그러면 수집기가
 * 단순 전달을 스스로 한다 — 비식별을 켜고 부르면 할 수 없는 일이므로 그 건은 DEIDENT 실패다.</p>
 */
@SpringBootTest
@ActiveProfiles("local")
class DeidentStepTest {

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
        registry.add("voice.dirs.output-meet", () -> tmp.resolve("xenon/meet").toString());
        registry.add("voice.dirs.output-phone", () -> tmp.resolve("xenon/phone").toString());
        registry.add("voice.dirs.xvarm-original", () -> tmp.resolve("xvarm_original").toString());
        registry.add("voice.sync.wait-timeout-sec", () -> "15");
        registry.add("voice.sync.stable-check-ms", () -> "30");
    }

    @Autowired
    private VoiceCollectService collect;
    @Autowired
    private SimulationDataService sim;
    @Autowired
    private IdempotencyGuard idempotency;
    @Autowired
    private StageFaultState faults;
    @Autowired
    private SttTempStore sttTemp;
    @MockitoSpyBean
    private MockSttClient mockStt;

    @BeforeEach
    void seed() {
        idempotency.clearAll();
        sttTemp.clearAll();
        faults.clear();
        sim.seedPerf(3, 3, 0);
    }

    @AfterEach
    void off() {
        faults.clear();
    }

    private static BatchWindow yesterday() {
        LocalDate today = LocalDate.now();
        return BatchWindow.manual(today.minusDays(1).atStartOfDay(), today.atStartOfDay());
    }

    @Test
    @DisplayName("단순 전달(false) — DEIDENT 단계를 거쳐 전건 성공, T2 는 COLLECT · ANALYZE · DEIDENT · SEND")
    void bypassPassesThroughDeidentStep() {
        VoiceBatchResult r = collect.run(yesterday(), null, "TEST", true, ResumeMode.FULL, null, 1, false);

        assertThat(r.successCnt()).isEqualTo(6);
        assertThat(r.steps()).extracting(VoiceBatchResult.StepLog::stepTypeCd)
                .containsExactly(FileProcOutcome.STEP_COLLECT, FileProcOutcome.STEP_ANALYZE,
                        FileProcOutcome.STEP_DEIDENT, FileProcOutcome.STEP_SEND);
        VoiceBatchResult.StepLog deid = r.steps().get(2);
        assertThat(deid.stepStsCd()).isEqualTo("SUCCESS");
        assertThat(deid.inCnt()).isEqualTo(6);
        assertThat(deid.outCnt()).isEqualTo(6);
    }

    @Test
    @DisplayName("비식별 단계 장애 → 재처리(FROM_DEIDENT) 를 false 로 — STT 를 다시 돌지 않고 보존된 전사로 단순 전달 마감")
    void resumeFromDeidentWithBypassUsesTranscript() {
        faults.set(StageFaultState.Stage.DEIDENT, StageFaultState.Mode.ALL, null);
        VoiceBatchResult failed = collect.run(yesterday(), null, "TEST", true, ResumeMode.FULL, null, 1, true);

        assertThat(failed.successCnt()).isZero();
        assertThat(failed.outcomes()).allSatisfy(o -> assertThat(o.failedAt(FileProcOutcome.STEP_DEIDENT)).isTrue());
        assertThat(failed.steps()).extracting(VoiceBatchResult.StepLog::stepTypeCd).contains(FileProcOutcome.STEP_DEIDENT)
                .doesNotContain(FileProcOutcome.STEP_SEND);
        assertThat(((Number) sttTemp.status().get("total")).intValue()).as("전사 결과가 보존돼야 이어 간다").isEqualTo(6);

        faults.clear();
        clearInvocations(mockStt);
        VoiceBatchResult resumed = collect.run(yesterday(), null, "RESUME", true, ResumeMode.FROM_DEIDENT,
                failed.execId(), 1, false);

        assertThat(resumed.successCnt()).isEqualTo(6);
        verify(mockStt, never()).transcribe(any());   // 비식별 재연산은 물론 STT 도 다시 하지 않는다
        assertThat(resumed.steps()).extracting(VoiceBatchResult.StepLog::stepTypeCd)
                .doesNotContain(FileProcOutcome.STEP_ANALYZE)   // STT 를 타지 않았다
                .contains(FileProcOutcome.STEP_DEIDENT, FileProcOutcome.STEP_SEND);
        assertThat(((Number) sttTemp.status().get("total")).intValue()).as("마감했으니 보존물은 지운다").isZero();
    }

    @Test
    @DisplayName("비식별을 켰는데 커넥터가 없으면 그 건은 DEIDENT 실패 — 할 수 없는 일을 했다고 적지 않는다")
    void deidentWithoutConnectorFailsAtDeident() {
        VoiceBatchResult r = collect.run(yesterday(), null, "TEST", true, ResumeMode.FULL, null, 1, true);

        assertThat(r.successCnt()).isZero();
        assertThat(r.outcomes()).allSatisfy(o -> {
            assertThat(o.failedAt(FileProcOutcome.STEP_DEIDENT)).isTrue();
            assertThat(o.errMsg()).contains("비식별 커넥터 비활성");
        });
        assertThat(r.steps().get(2).stepStsCd()).isEqualTo("FAIL");
    }
}
