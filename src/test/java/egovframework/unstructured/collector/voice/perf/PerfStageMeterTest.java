package egovframework.unstructured.collector.voice.perf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 단계 시간 — 끝나기 전의 대기(진행 중)와 최댓값·에러 수. 임계 시험의 조기 종료가 이것을 본다. */
class PerfStageMeterTest {

    @Test
    @DisplayName("꺼져 있으면 시계를 읽지 않고 아무것도 세지 않는다")
    void inactiveIsNoop() {
        PerfStageMeter m = new PerfStageMeter();
        assertThat(m.start(PerfStage.ACQUIRE)).isZero();
        m.add(PerfStage.ACQUIRE, 0L);
        m.error(PerfStage.STT);
        assertThat(m.errors(PerfStage.STT)).isZero();
        assertThat(m.oldestInFlightMs(PerfStage.ACQUIRE)).isZero();
    }

    @Test
    @DisplayName("진행 중인 대기는 끝나기 전에도 보이고, 끝나면 최댓값으로 옮겨 간다")
    void inFlightThenMax() throws Exception {
        PerfStageMeter m = new PerfStageMeter();
        m.begin();
        long t = m.start(PerfStage.ACQUIRE);
        Thread.sleep(60);
        assertThat(m.oldestInFlightMs(PerfStage.ACQUIRE)).isGreaterThanOrEqualTo(50d);
        assertThat(m.maxMs(PerfStage.ACQUIRE)).isZero();

        m.add(PerfStage.ACQUIRE, t);
        assertThat(m.oldestInFlightMs(PerfStage.ACQUIRE)).isZero();
        assertThat(m.maxMs(PerfStage.ACQUIRE)).isGreaterThanOrEqualTo(50d);

        m.error(PerfStage.STT);
        assertThat(m.errors(PerfStage.STT)).isEqualTo(1);
        m.begin();
        assertThat(m.errors(PerfStage.STT)).isZero();
        assertThat(m.maxMs(PerfStage.ACQUIRE)).isZero();
    }
}
