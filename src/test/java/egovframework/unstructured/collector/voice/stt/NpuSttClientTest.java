package egovframework.unstructured.collector.voice.stt;

import com.fasterxml.jackson.databind.ObjectMapper;
import egovframework.unstructured.collector.common.model.SttResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NPU 응답 파싱 — <b>Whisper 표준 JSON 을 읽는다.</b>
 *
 * <p>실제 NPU 사양이 아직 없다(계획서 Q9). 그래서 이 테스트가 <b>우리가 무엇을 가정하고 있는지</b>를
 * 고정한다 — 사양이 나와 모양이 다르면 여기가 먼저 깨져서 알려 준다.</p>
 */
class NpuSttClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Whisper 표준 응답 — text·language·duration·segments 를 그대로 읽는다")
    void parsesWhisperJson() throws Exception {
        String whisper = """
                {"text":"안녕하세요. 반갑습니다.","language":"ko","duration":7.84,
                 "segments":[
                   {"id":0,"seek":0,"start":0.0,"end":3.2,"text":"안녕하세요.",
                    "tokens":[1,2],"avg_logprob":-0.3,"no_speech_prob":0.01},
                   {"id":1,"seek":0,"start":3.2,"end":7.84,"text":"반갑습니다.",
                    "tokens":[3],"avg_logprob":-0.2,"no_speech_prob":0.02}]}""";

        SttResult r = NpuSttClient.parse(mapper.readTree(whisper), "a.wav");

        assertThat(r.text()).isEqualTo("안녕하세요. 반갑습니다.");
        assertThat(r.language()).isEqualTo("ko");
        assertThat(r.duration()).isEqualTo(7.84);
        assertThat(r.engine()).isEqualTo("NPU");
        assertThat(r.fromSource()).isFalse();
        assertThat(r.segments()).hasSize(2);
        assertThat(r.segments().get(1))
                .isEqualTo(new SttResult.Segment(1, 3.2, 7.84, "반갑습니다."));
        // 정수 초는 버림이 아니라 반올림이다 — 버리면 합계가 계속 짧아진다.
        assertThat(r.durationSec()).isEqualTo(8);
    }

    @Test
    @DisplayName("구간을 주지 않는 응답 — 전문만 있어도 처리한다(옛 durationSec 정수 폴백 포함)")
    void fallsBackWhenNoSegments() throws Exception {
        SttResult r = NpuSttClient.parse(
                mapper.readTree("{\"text\":\"구간 없는 응답\",\"durationSec\":12}"), "b.wav");

        assertThat(r.text()).isEqualTo("구간 없는 응답");
        assertThat(r.segments()).isEmpty();
        assertThat(r.hasSegments()).isFalse();
        assertThat(r.language()).isNull();
        // duration 이 없으면 예전 가정이던 durationSec(정수)로 떨어진다
        assertThat(r.duration()).isEqualTo(12.0);
    }

    @Test
    @DisplayName("전문이 비고 구간만 온 응답 — 구간을 이어 붙여 전문을 만든다")
    void joinsSegmentsWhenTextMissing() throws Exception {
        SttResult r = NpuSttClient.parse(mapper.readTree("""
                {"segments":[{"start":0,"end":1,"text":"첫 줄"},
                             {"start":1,"end":2,"text":"둘째 줄"}]}"""), "c.wav");

        assertThat(r.text()).isEqualTo("첫 줄\n둘째 줄");
        assertThat(r.segments()).hasSize(2);
        // id 가 없으면 순번으로 채운다
        assertThat(r.segments()).extracting(SttResult.Segment::id).containsExactly(0, 1);
    }

    @Test
    @DisplayName("빈 응답이어도 예외를 던지지 않는다 — 비었는지는 호출부가 isEmpty 로 판단한다")
    void emptyResponseIsNotAnError() throws Exception {
        SttResult r = NpuSttClient.parse(mapper.readTree("{}"), "d.wav");

        assertThat(r.isEmpty()).isTrue();
        assertThat(r.segments()).isEmpty();
        assertThat(r.duration()).isZero();
    }
}
