package egovframework.unstructured.collector.mock;

import egovframework.unstructured.collector.batch.UnstructuredJobRunner;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimHoldServiceTest {

    private final UnstructuredJobRunner runner = new UnstructuredJobRunner(() -> null);
    private final SimHoldService service = new SimHoldService(runner);

    @Test
    void holdTakesTheRealRunnerLockUntilReleased() throws Exception {
        Map<String, Object> st = service.hold(30, "test");
        assertThat(st.get("holding")).isEqualTo(true);
        assertThat((Long) st.get("remainingSec")).isBetween(28L, 30L);
        assertThat(runner.isRunning()).isTrue();

        // 점유 중 진짜 배치 접수 — 실제로 돌 때와 같이 거절된다(호환 경로는 이걸 accepted:false 로, 나머지는 409 로 바꾼다)
        assertThatThrownBy(() -> runner.submit(UnstructuredJobRunner.Kind.RUN, "t", Map.of(), s -> "x"))
                .isInstanceOf(UnstructuredJobRunner.AlreadyRunningException.class);
        // 가상 점유를 또 거는 것도 거절
        assertThatThrownBy(() -> service.hold(10, "t")).isInstanceOf(IllegalStateException.class);

        Map<String, Object> rel = service.release();
        assertThat(rel.get("released")).isEqualTo(true);
        awaitIdle();
        assertThat(service.status().get("holding")).isEqualTo(false);
        // 잠금이 풀려 다음 배치가 접수된다
        UnstructuredJobRunner.Job next = runner.submit(UnstructuredJobRunner.Kind.RUN, "t", Map.of(), s -> "ok");
        next.done().get();
        assertThat(next.snapshot().get("status")).isEqualTo("DONE");
    }

    @Test
    void holdEndsByItselfAfterDuration() throws Exception {
        service.hold(1, "test");
        long t0 = System.currentTimeMillis();
        while (runner.isRunning() && System.currentTimeMillis() - t0 < 5000) {
            Thread.sleep(50);
        }
        assertThat(runner.isRunning()).isFalse();
        assertThat(String.valueOf(runner.status().get("result"))).contains("점유 종료");
    }

    @Test
    void releaseWithoutHoldIsHarmless() {
        assertThat(service.release().get("released")).isEqualTo(false);
    }

    @Test
    void durationOutOfRangeIsRejected() {
        assertThatThrownBy(() -> service.hold(0, "t")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.hold(601, "t")).isInstanceOf(IllegalArgumentException.class);
        assertThat(runner.isRunning()).isFalse();
    }

    private void awaitIdle() throws InterruptedException {
        long t0 = System.currentTimeMillis();
        while (runner.isRunning() && System.currentTimeMillis() - t0 < 3000) {
            Thread.sleep(20);
        }
    }
}
