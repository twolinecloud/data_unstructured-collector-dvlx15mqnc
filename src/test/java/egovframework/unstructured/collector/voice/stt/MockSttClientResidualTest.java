package egovframework.unstructured.collector.voice.stt;

import egovframework.unstructured.collector.common.config.FaultInjector;
import egovframework.unstructured.collector.common.model.SttResult;
import egovframework.unstructured.collector.common.model.VoiceFile;
import egovframework.unstructured.collector.common.model.VoiceKind;
import egovframework.unstructured.collector.common.model.VoiceTarget;
import egovframework.unstructured.collector.mock.ResidualInjection;
import egovframework.unstructured.collector.mock.ScenarioFaults;
import egovframework.unstructured.collector.voice.batch.BatchProgress;
import egovframework.unstructured.collector.voice.perf.PerfStageMeter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** MOCK STT 잔존 주입 — 표시된 키의 전사에만 가상 번호를 한글로 읽은 줄이 붙고, 표식이 꺼진 환경에서는 붙지 않는다. */
class MockSttClientResidualTest {

    private static final String MARKED = "DMY-MEET-20261126-0001";
    private static final String PLAIN = "DMY-MEET-20261126-0002";

    private static VoiceFile file(String key) {
        VoiceTarget t = new VoiceTarget(VoiceKind.MEET, "CORR1", key, "DOC1", "FK1", false, null,
                "/x", "a.m4a", null, LocalDateTime.of(2026, 11, 26, 10, 0));
        return new VoiceFile(t, Path.of("a.m4a"), 10L, "m4a", true);
    }

    private static MockSttClient client(ScenarioFaults faults, ResidualInjection inj) {
        return new MockSttClient(mock(FaultInjector.class), new MockSttLatency(0), mock(BatchProgress.class),
                mock(PerfStageMeter.class), faults, inj);
    }

    @Test
    @DisplayName("표시된 키만 잔존 줄 · 식별자 줄 바로 앞 · 구간도 하나 늘어난다")
    void marksOnlyMarkedKey() {
        ResidualInjection inj = new ResidualInjection();
        inj.mark(List.of(MARKED));
        MockSttClient stt = client(ScenarioFaults.activeForTest(), inj);

        SttResult marked = stt.transcribe(file(MARKED));
        SttResult plain = stt.transcribe(file(PLAIN));

        assertThat(marked.text()).contains(ResidualInjection.LINE);
        assertThat(plain.text()).doesNotContain(ResidualInjection.LINE);
        assertThat(marked.segments()).hasSize(plain.segments().size() + 1);
        List<String> lines = marked.text().lines().toList();
        assertThat(lines.get(lines.size() - 2)).isEqualTo(ResidualInjection.LINE);
    }

    @Test
    @DisplayName("표식이 꺼진 환경(운영)에서는 표시돼 있어도 붙지 않는다 · 정리하면 표시가 비워진다")
    void inactiveOrClearedAddsNothing() {
        ResidualInjection inj = new ResidualInjection();
        inj.mark(List.of(MARKED));
        assertThat(client(ScenarioFaults.inactive(), inj).transcribe(file(MARKED)).text()).doesNotContain(ResidualInjection.LINE);

        assertThat(inj.clear()).isEqualTo(1);
        assertThat(client(ScenarioFaults.activeForTest(), inj).transcribe(file(MARKED)).text()).doesNotContain(ResidualInjection.LINE);
    }
}
