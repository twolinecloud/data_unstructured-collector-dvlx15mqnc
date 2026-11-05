package egovframework.unstructured.collector.voice.batch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재처리 시작점 → 로그 컬렉터 reopen 의 {@code fromStep} 매핑.
 *
 * <p>전부 지우면 다시 돌지 않는 앞 단계가 사라지고 화면의 수집 건수도 이번 회차 값으로 줄어든다
 * (수집 건수 = COLLECT 단계 OUT_CNT) — '대상 4 / 수집 2' 로 어긋나던 원인(D17 · 2026-11-05).</p>
 */
class ResumeModeReopenStepTest {

    @Test
    @DisplayName("FULL 은 null — 처음부터 다시 도니 전부 지운다")
    void fullClearsEverything() {
        assertThat(ResumeMode.FULL.reopenFromStep()).isNull();
    }

    @Test
    @DisplayName("FROM_ANALYZE 는 ANALYZE — 수집 기록은 남는다")
    void fromAnalyzeKeepsCollect() {
        assertThat(ResumeMode.FROM_ANALYZE.reopenFromStep()).isEqualTo("ANALYZE");
    }

    @Test
    @DisplayName("FROM_SEND 는 DEIDENT — 전사부터 다시 도는 경로가 비식별을 다시 하므로 SEND 가 아니다")
    void fromSendStartsAtDeident() {
        assertThat(ResumeMode.FROM_SEND.reopenFromStep())
                .as("SEND 로 두면 옛 비식별 기록이 남아 건수가 겹친다")
                .isEqualTo("DEIDENT");
    }

    @Test
    @DisplayName("어느 모드든 수집(COLLECT)을 지우는 것은 FULL 뿐이다")
    void onlyFullDropsCollect() {
        for (ResumeMode m : ResumeMode.values()) {
            if (m == ResumeMode.FULL) {
                assertThat(m.reopenFromStep()).isNull();
            } else {
                assertThat(m.reopenFromStep()).isNotNull().isNotEqualTo("COLLECT");
            }
        }
    }
}
