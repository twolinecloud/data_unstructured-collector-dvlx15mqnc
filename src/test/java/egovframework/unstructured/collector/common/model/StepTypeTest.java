package egovframework.unstructured.collector.common.model;

import egovframework.unstructured.collector.batch.UnstructuredBatchService;
import egovframework.unstructured.collector.common.logging.LogCollectorClient;
import egovframework.unstructured.collector.image.model.ImageStage;
import egovframework.unstructured.collector.voice.batch.ResumeMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 비정형 파이프라인 3단계(C05) — 코드 · 순번 · 이미지 세부 단계 대응 · 재처리 · T4 오류 표기. */
class StepTypeTest {

    @Test
    @DisplayName("비정형은 3단계뿐 — COLLECT 1 · ANALYZE 2 · SEND 3. 정형 · 외부 체인의 단계는 거절한다")
    void onlyThreeSteps() {
        assertThat(Arrays.stream(StepType.values()).map(Enum::name)).containsExactly("COLLECT", "ANALYZE", "SEND");
        assertThat(StepType.COLLECT.seq()).isEqualTo((short) 1);
        assertThat(StepType.SEND.seq()).isEqualTo((short) 3);
        assertThat(StepType.parse(" analyze ")).contains(StepType.ANALYZE);
        for (String other : new String[] {"DEIDENT", "CLEANSE", "STORE"}) {
            assertThat(StepType.parse(other)).as(other).isEmpty();
            assertThatThrownBy(() -> StepType.of(other)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> UnstructuredBatchService.resumeOf(other)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(UnstructuredBatchService.resumeOf("COLLECT")).isEqualTo(ResumeMode.FULL);
        assertThat(UnstructuredBatchService.resumeOf("ANALYZE")).isEqualTo(ResumeMode.FROM_ANALYZE);
        assertThat(UnstructuredBatchService.resumeOf("SEND")).isEqualTo(ResumeMode.FROM_SEND);
        assertThat(UnstructuredBatchService.resumeOf(null)).isEqualTo(ResumeMode.FULL);
    }

    @Test
    @DisplayName("이미지 세부 단계 → 3단계 — 수집(조회 · FILEKEY · 수신) · 정제/분석(가상 지연 · 복호화) · 적재/전송(저장 · 매핑)")
    void imageStagesMapToThreeSteps() {
        assertThat(ImageStage.QUERY.stepType()).isEqualTo(StepType.COLLECT);
        assertThat(ImageStage.FILEKEY.stepType()).isEqualTo(StepType.COLLECT);
        assertThat(ImageStage.ACQUIRE.stepType()).isEqualTo(StepType.COLLECT);
        assertThat(ImageStage.VIRTUAL.stepType()).isEqualTo(StepType.ANALYZE);
        assertThat(ImageStage.DECRYPT.stepType()).isEqualTo(StepType.ANALYZE);
        assertThat(ImageStage.SAVE.stepType()).isEqualTo(StepType.SEND);
        assertThat(ImageStage.MAP.stepType()).isEqualTo(StepType.SEND);
    }

    @Test
    @DisplayName("T4 오류 표기 — [C12 코드] [단계] 상세(로그 컬렉터 정규화는 앞의 코드만 뗀다) · 음성 결과의 T4 단계")
    void steppedErrStackAndOutcomeStep() {
        assertThat(LogCollectorClient.FileProcReq.errStackOf(StepType.ANALYZE, "STT 호출 실패 — HTTP 500"))
                .isEqualTo("[DATA] [ANALYZE] STT 호출 실패 — HTTP 500");
        assertThat(LogCollectorClient.FileProcReq.errStackOf(StepType.COLLECT, "수신 파일 대기 타임아웃"))
                .isEqualTo("[TIMEOUT] [COLLECT] 수신 파일 대기 타임아웃");
        assertThat(LogCollectorClient.FileProcReq.errStackOf(StepType.SEND, null)).isNull();

        VoiceTarget t = new VoiceTarget(VoiceKind.MEET, "C", "K", null, null, false, null, null, "a.m4a", null, null);
        assertThat(FileProcOutcome.success(t, 1, 1, "z", 1).stepTypeForLog()).isEqualTo(StepType.SEND);
        assertThat(FileProcOutcome.fail(t, FileProcOutcome.STEP_ANALYZE, "x", 1).stepTypeForLog()).isEqualTo(StepType.ANALYZE);
        assertThat(FileProcOutcome.skipped(t, "x").stepTypeForLog()).isNull();
    }
}
