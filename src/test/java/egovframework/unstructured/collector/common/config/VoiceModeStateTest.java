package egovframework.unstructured.collector.common.config;

import egovframework.unstructured.collector.common.sync.EsbFileNamingPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 런타임 모드 전환 — 재시작 없이 바뀌고, 잘못된 값은 거부한다. */
class VoiceModeStateTest {

    private VoiceModeState state;

    @BeforeEach
    void setUp() {
        VoiceProperties p = props();
        state = new VoiceModeState(p, new egovframework.unstructured.collector.common.config.DeployEnvPreset(new org.springframework.mock.env.MockEnvironment(), p, ""));
        state.resetToConfigured();
    }

    private VoiceProperties props() {
        return new VoiceProperties(
                new VoiceProperties.Source(VoiceProperties.SourceMode.MOCK, "", "",
                        new VoiceProperties.Schema("", "", "", "", ""),
                        new VoiceProperties.Flag("Y", "Y", "N", "Y"),
                        VoiceProperties.XvarmMode.MOCK_DEV, new VoiceProperties.XvarmMock("sm", "xvarm"),
                        new VoiceProperties.LocalH2("jdbc:h2:mem:t", "sa", "", 10, 0),
                        new VoiceProperties.DirectDb("jdbc:postgresql://localhost:1/x", "", "u", "", 1000, 1, 0, 60000)),
                new VoiceProperties.Broker(VoiceProperties.BrokerMode.MOCK, "", java.util.List.of(), 100, 10),
                new VoiceProperties.Phone(VoiceProperties.PhoneMode.MOCK),
                new VoiceProperties.Sync(10, 5, EsbFileNamingPolicy.Policy.ORIGINAL),
                new VoiceProperties.Dirs("b", "m", "p", "w", ""),
                new VoiceProperties.Decrypt(VoiceProperties.DecryptMode.SKIP, ""),
                new VoiceProperties.Stt(VoiceProperties.SttMode.MOCK, "", 30),
                new VoiceProperties.Batch(20, false,
                        List.of("0", "1"), 500, "VOICE_ANALYSIS", "TEST_BATCH", "UNSTRUCTURED", false),
                new VoiceProperties.Sim(false));
    }

    @Test
    @DisplayName("초기값은 설정에서 온다")
    void initialFromProperties() {
        assertThat(state.snapshot())
                .containsEntry("source", "MOCK")
                .containsEntry("broker", "MOCK")
                .containsEntry("decrypt", "SKIP")
                .containsEntry("stt", "MOCK");
    }

    @Test
    @DisplayName("스위치를 바꾸면 즉시 반영된다")
    void switchesApplyImmediately() {
        String before = state.set("stt", "NPU");

        assertThat(before).isEqualTo("MOCK");
        assertThat(state.stt()).isEqualTo(VoiceProperties.SttMode.NPU);
        assertThat(state.snapshot()).containsEntry("stt", "NPU");
    }

    @Test
    @DisplayName("소문자로 줘도 받는다 — 화면에서 오는 값을 관대하게 처리")
    void acceptsLowerCase() {
        state.set("SOURCE", "direct_jdbc");

        assertThat(state.source()).isEqualTo(VoiceProperties.SourceMode.DIRECT_JDBC);
    }

    @Test
    @DisplayName("없는 스위치는 거부한다")
    void rejectsUnknownSwitch() {
        assertThatThrownBy(() -> state.set("nope", "MOCK"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("알 수 없는 스위치");
    }

    @Test
    @DisplayName("허용되지 않는 값은 거부하고 무엇이 가능한지 알려준다")
    void rejectsInvalidValueWithHint() {
        assertThatThrownBy(() -> state.set("stt", "WHISPER"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MOCK")
                .hasMessageContaining("NPU");
    }

    @Test
    @DisplayName("거부된 변경은 상태를 건드리지 않는다")
    void failedChangeLeavesStateIntact() {
        try {
            state.set("stt", "INVALID");
        } catch (IllegalArgumentException ignored) {
            // 기대한 예외
        }
        assertThat(state.stt()).isEqualTo(VoiceProperties.SttMode.MOCK);
    }

    @Test
    @DisplayName("초기화하면 설정값으로 돌아간다 — 런타임 변경은 프로세스에만 남는다")
    void resetRestoresConfigured() {
        state.set("stt", "NPU");
        state.set("source", "ESB_HTTP2DB");

        state.resetToConfigured();

        assertThat(state.snapshot()).isEqualTo(state.configured());
    }
}
