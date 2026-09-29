package egovframework.unstructured.collector.voice.batch;

import egovframework.unstructured.collector.common.model.ProcStatus;
import egovframework.unstructured.collector.common.model.VoiceKind;
import egovframework.unstructured.collector.common.model.VoiceTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배치 중단 — <b>건과 건 사이에서만 끊는다.</b>
 *
 * <p>긴 배치를 화면에서 멈출 수 있어야 한다. 다만 처리 중인 한 건은 끝까지 간다 —
 * 복호화·STT 한가운데서 칼을 넣으면 잘린 파일과 반쪽 이력이 남고, 그 뒤처리가 배치를
 * 멈추는 것보다 비싸다.</p>
 */
class BatchProgressTest {

    private BatchProgress progress;

    private static VoiceTarget target(String key) {
        return new VoiceTarget(VoiceKind.MEET, "SIM00000000000001", key, "DOC1", "KEY1",
                true, null, "/src", key + ".m4a", null, LocalDateTime.now());
    }

    @BeforeEach
    void setUp() {
        progress = new BatchProgress();
    }

    @Test
    @DisplayName("돌고 있지 않으면 중단은 먹지 않는다 — 화면이 '멈췄다'고 오해하면 안 된다")
    void cancelDoesNothingWhenIdle() {
        assertThat(progress.cancel()).isFalse();
        assertThat(progress.isCancelRequested()).isFalse();
    }

    @Test
    @DisplayName("도는 중에는 중단이 먹고, 루프가 그것을 읽는다")
    void cancelIsVisibleToTheLoop() {
        progress.begin("20260922TST001", "DAILY", 10);

        assertThat(progress.cancel()).isTrue();
        assertThat(progress.isCancelRequested()).isTrue();
        assertThat(progress.snapshot()).containsEntry("canceled", true);
    }

    @Test
    @DisplayName("다음 배치는 깨끗하게 시작한다 — 앞 배치의 중단 표식이 남으면 시작하자마자 멈춘다")
    void nextBatchStartsClean() {
        progress.begin("20260922TST001", "DAILY", 10);
        progress.cancel();
        progress.end();

        progress.begin("20260922TST002", "DAILY", 10);

        assertThat(progress.isCancelRequested()).isFalse();
        assertThat(progress.snapshot()).containsEntry("canceled", false);
    }

    @Test
    @DisplayName("끝난 뒤에도 중단 표식은 남는다 — 화면이 '중단됨'으로 마무리를 보여 줘야 한다")
    void canceledStateSurvivesTheEnd() {
        progress.begin("20260922TST001", "DAILY", 3);
        progress.startFile(target("SIM-MEET-001"));
        progress.finishFile(ProcStatus.SUCCESS);
        progress.cancel();
        progress.end();

        var s = progress.snapshot();
        assertThat(s).containsEntry("canceled", true)
                .containsEntry("running", false)
                .containsEntry("total", 3);
    }
}
