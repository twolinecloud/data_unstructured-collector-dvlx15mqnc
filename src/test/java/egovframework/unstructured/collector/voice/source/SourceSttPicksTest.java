package egovframework.unstructured.collector.voice.source;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 성능 시험 시딩 — 전화 중 기 STT(Bypass) 건을 비율만큼, 번호 전체에 고르게 고른다. */
class SourceSttPicksTest {

    @Test
    @DisplayName("150건 3% → 5건을 고르게 · 0% → 없음 · 100% → 전부")
    void spreadsEvenly() {
        assertThat(SimulationDataService.sourceSttPicks(150, 3)).containsExactly(16, 46, 76, 106, 136);
        assertThat(SimulationDataService.sourceSttPicks(150, 0)).isEmpty();
        assertThat(SimulationDataService.sourceSttPicks(5, 100)).containsExactly(1, 2, 3, 4, 5);
        assertThat(SimulationDataService.sourceSttPicks(0, 3)).isEmpty();
    }
}
