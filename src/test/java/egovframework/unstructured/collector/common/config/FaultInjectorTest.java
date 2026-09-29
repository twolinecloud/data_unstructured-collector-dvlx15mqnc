package egovframework.unstructured.collector.common.config;

import egovframework.unstructured.collector.common.config.FaultInjector.Stage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 장애 주입기 — 꺼져 있을 때 아무 영향이 없고, 켜면 확률대로 동작한다. */
class FaultInjectorTest {

    private FaultInjector injector;

    @BeforeEach
    void setUp() {
        injector = new FaultInjector();
        injector.resetCounters();
    }

    @Test
    @DisplayName("기본은 꺼져 있다 — 켜져 있는 줄 모르고 시연하면 실패를 버그로 오해한다")
    void disabledByDefault() {
        assertThat(injector.isEnabled()).isFalse();
        assertThatCode(() -> injector.maybeInject(Stage.STT)).doesNotThrowAnyException();
        assertThat(injector.injectedFailures()).isZero();
    }

    @Test
    @DisplayName("100% 로 켜면 반드시 실패한다")
    void alwaysFailsAtHundredPercent() {
        injector.configure(true, 100, 0, 0L);

        assertThatThrownBy(() -> injector.maybeInject(Stage.STT))
                .isInstanceOf(FaultInjector.InjectedFaultException.class)
                .hasMessageContaining("장애 시뮬레이션")
                .hasMessageContaining("STT");
    }

    @Test
    @DisplayName("0% 면 켜져 있어도 실패하지 않는다")
    void neverFailsAtZeroPercent() {
        injector.configure(true, 0, 0, 0L);

        for (int i = 0; i < 50; i++) {
            assertThatCode(() -> injector.maybeInject(Stage.STT)).doesNotThrowAnyException();
        }
        assertThat(injector.injectedFailures()).isZero();
    }

    @Test
    @DisplayName("주입 건수를 센다 — 실패가 주입된 것인지 진짜인지 구분하는 근거")
    void countsInjections() {
        injector.configure(true, 100, 0, 0L);

        for (int i = 0; i < 3; i++) {
            try {
                injector.maybeInject(Stage.STT);
            } catch (FaultInjector.InjectedFaultException expected) {
                // 의도된 예외
            }
        }

        assertThat(injector.injectedFailures()).isEqualTo(3);
    }

    @Test
    @DisplayName("지연도 주입된다")
    void injectsDelay() {
        injector.configure(true, 0, 100, 150L);

        long t0 = System.currentTimeMillis();
        injector.maybeInject(Stage.STT);
        long elapsed = System.currentTimeMillis() - t0;

        assertThat(elapsed).isGreaterThanOrEqualTo(150L);
        assertThat(injector.injectedDelays()).isEqualTo(1);
    }

    @Test
    @DisplayName("잘못된 확률은 거부한다")
    void rejectsInvalidPercent() {
        assertThatThrownBy(() -> injector.configure(true, 101, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0~100");
        assertThatThrownBy(() -> injector.configure(true, null, -1, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("지연 상한을 넘기면 거부한다 — 실수로 배치를 몇 시간 묶어 두지 않게")
    void rejectsExcessiveDelay() {
        assertThatThrownBy(() -> injector.configure(true, null, null, 60_001L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0~60000");
    }

    @Test
    @DisplayName("null 인 항목은 기존 값을 유지한다 — 부분 변경이 가능해야 한다")
    void partialConfigure() {
        injector.configure(true, 42, 7, 500L);
        injector.configure(null, null, null, null);

        assertThat(injector.snapshot())
                .containsEntry("enabled", true)
                .containsEntry("failPercent", 42)
                .containsEntry("delayPercent", 7)
                .containsEntry("delayMs", 500L);
    }
}
