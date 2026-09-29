package egovframework.unstructured.collector.voice.batch;

import egovframework.unstructured.collector.voice.batch.StageFaultState.Mode;
import egovframework.unstructured.collector.voice.batch.StageFaultState.Stage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 단계별 장애 주입 — <b>부분 성공은 확률이 아니라 건수다.</b>
 *
 * <p>같은 버튼을 눌렀는데 화면이 매번 다르면 시연도 회귀검증도 안 된다. "10건 중 1건" 이
 * 기대 동작이면 정확히 1건이 떨어져야 한다.</p>
 */
class StageFaultStateTest {

    private StageFaultState fault;

    @BeforeEach
    void setUp() {
        fault = new StageFaultState();
    }

    @Test
    @DisplayName("기본은 전 단계 OFF — 아무것도 떨어뜨리지 않는다")
    void offByDefault() {
        fault.beginBatch(10);

        assertThat(fault.anyOn()).isFalse();
        for (Stage s : Stage.values()) {
            assertThat(fault.shouldFail(s)).isFalse();
        }
    }

    @Test
    @DisplayName("ALL — 부르는 족족 실패시킨다")
    void allFails() {
        fault.set(Stage.ANALYZE, Mode.ALL, null);
        fault.beginBatch(10);

        for (int i = 0; i < 10; i++) {
            assertThat(fault.shouldFail(Stage.ANALYZE)).as("%d번째", i).isTrue();
        }
        // 다른 단계는 건드리지 않는다 — 전역 스위치를 걷어낸 이유다
        assertThat(fault.shouldFail(Stage.COLLECT)).isFalse();
        assertThat(fault.shouldFail(Stage.SEND)).isFalse();
    }

    @Test
    @DisplayName("PARTIAL 10% — 10건 중 정확히 1건만 실패한다")
    void partialFailsExactCount() {
        fault.set(Stage.SEND, Mode.PARTIAL, 10);
        fault.beginBatch(10);

        int failed = 0;
        for (int i = 0; i < 10; i++) {
            if (fault.shouldFail(Stage.SEND)) {
                failed++;
            }
        }
        assertThat(failed).isEqualTo(1);
    }

    @Test
    @DisplayName("작은 배치에서도 최소 1건 — 0건이면 스위치가 안 먹는 것처럼 보인다")
    void partialGuaranteesAtLeastOne() {
        fault.set(Stage.ANALYZE, Mode.PARTIAL, 10);
        fault.beginBatch(2);            // 2 × 10% = 0.2 → 반올림하면 0

        int failed = 0;
        for (int i = 0; i < 2; i++) {
            if (fault.shouldFail(Stage.ANALYZE)) {
                failed++;
            }
        }
        assertThat(failed).isEqualTo(1);
    }

    @Test
    @DisplayName("배치마다 다시 센다 — 앞 배치가 소진한 할당량이 남지 않는다")
    void quotaResetsPerBatch() {
        fault.set(Stage.SEND, Mode.PARTIAL, 50);

        fault.beginBatch(4);
        int first = count(Stage.SEND, 4);
        fault.beginBatch(4);
        int second = count(Stage.SEND, 4);

        assertThat(first).isEqualTo(2);
        assertThat(second).as("두 번째 배치도 같아야 한다").isEqualTo(2);
    }

    @Test
    @DisplayName("전체 해제 — 시나리오를 바꿀 때 앞 설정이 남지 않는다")
    void clearTurnsEverythingOff() {
        fault.set(Stage.COLLECT, Mode.ALL, null);
        fault.set(Stage.SEND, Mode.PARTIAL, 20);
        assertThat(fault.anyOn()).isTrue();

        fault.clear();
        fault.beginBatch(10);

        assertThat(fault.anyOn()).isFalse();
        assertThat(count(Stage.COLLECT, 10)).isZero();
        assertThat(count(Stage.SEND, 10)).isZero();
    }

    @Test
    @DisplayName("실패 비율은 1~100 — 벗어나면 설정 시점에 거른다")
    void rejectsBadPercent() {
        assertThatThrownBy(() -> fault.set(Stage.ANALYZE, Mode.PARTIAL, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1~100");
        assertThatThrownBy(() -> fault.set(Stage.ANALYZE, Mode.PARTIAL, 101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("현황 — 어느 단계가 켜져 있고 몇 건을 떨어뜨렸는지 남는다")
    void snapshotReportsInjected() {
        fault.set(Stage.ANALYZE, Mode.PARTIAL, 50);
        fault.beginBatch(4);
        count(Stage.ANALYZE, 4);

        @SuppressWarnings("unchecked")
        var analyze = (java.util.Map<String, Object>) fault.snapshot().get("ANALYZE");
        assertThat(analyze).containsEntry("mode", "PARTIAL")
                .containsEntry("failPercent", 50)
                .containsEntry("injected", 2);
    }

    private int count(Stage stage, int n) {
        int failed = 0;
        for (int i = 0; i < n; i++) {
            if (fault.shouldFail(stage)) {
                failed++;
            }
        }
        return failed;
    }
}
