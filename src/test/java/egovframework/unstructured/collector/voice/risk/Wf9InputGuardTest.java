package egovframework.unstructured.collector.voice.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.Consumer;

import static egovframework.unstructured.collector.voice.risk.Wf9TestSupport.OM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WF9 입력 규격 검사 — 대시보드 AI API 연동 가이드 v4(11-25) 6장 입력 예 · 7장 선택 필드 · 상태 값, 2026-11-20 전처리 실응답.
 * 글은 가이드의 합성 예문이거나 지어낸 값이다.
 */
class Wf9InputGuardTest {

    /** 가이드 v4 6쪽 입력 JSON(합성값) — function 을 감싼 message 모양. */
    private static ObjectNode sample() throws IOException {
        return (ObjectNode) OM.readTree("""
                {"function":"contact_risk_analysis","input":{
                  "contact_records":[{"contact_id":"GUIDE-ONLY-CONTACT-01","contact_type":"전화","occurred_at":"2026-10-30T10:00:00+09:00"}],
                  "speech_results":[{"contact_id":"GUIDE-ONLY-CONTACT-01","segments":[
                      {"segment_id":"SEG-01","speaker":"OTHER","start_ms":0,"end_ms":2000,"text":"요즘 밤에 잠은 잘 자니?"},
                      {"segment_id":"SEG-02","speaker":"INMATE","start_ms":2200,"end_ms":5000,"text":"밤에 잠들기가 어려워서 자주 깨어요."},
                      {"segment_id":"SEG-03","speaker":"OTHER","start_ms":5200,"end_ms":7000,"text":"진료는 신청했니?"},
                      {"segment_id":"SEG-04","speaker":"INMATE","start_ms":7200,"end_ms":10000,"text":"직원에게 말해서 진료를 신청했고 지금은 기다리고 있어요."}],
                    "prosody_events":[{"start_ms":2200.25,"end_ms":5000,"feature":"intensity","direction":"up","level_z":1.8}],
                    "prosody_status":"analyzed",
                    "audio_events":[{"start_ms":2300.5,"end_ms":4800.75,"event_type":"CRYING","score":0.82},
                                    {"start_ms":11000,"end_ms":12000,"event_type":"SCREAMING"}],
                    "audio_status":"analyzed","duration_ms":12000}]}}
                """);
    }

    private static ObjectNode sr(ObjectNode doc) {
        return (ObjectNode) doc.path("input").path("speech_results").path(0);
    }

    private static ObjectNode seg(ObjectNode doc, int i) {
        return (ObjectNode) sr(doc).path("segments").path(i);
    }

    private static ObjectNode rec(ObjectNode doc) {
        return (ObjectNode) doc.path("input").path("contact_records").path(0);
    }

    private static ObjectNode prosody(ObjectNode doc) {
        return (ObjectNode) sr(doc).path("prosody_events").path(0);
    }

    private static ObjectNode audio(ObjectNode doc) {
        return (ObjectNode) sr(doc).path("audio_events").path(0);
    }

    private static void violates(Consumer<ObjectNode> breakIt, String expectedTail) throws IOException {
        ObjectNode d = sample();
        breakIt.accept(d);
        assertThatThrownBy(() -> Wf9InputGuard.check(d)).isInstanceOfSatisfying(Wf9Failure.class, f -> {
            assertThat(f.stage()).isEqualTo(Wf9Failure.Stage.INPUT);
            assertThat(f.retryable()).isFalse();
            assertThat(f.getMessage()).isEqualTo("WF9 입력 규격 위반 — " + expectedTail)
                    .doesNotContain("잠들기").doesNotContain("GUIDE-ONLY");
        });
    }

    @Test
    @DisplayName("가이드 v4 6쪽 입력 예 — 그대로 통과 · 채운 값 · 경고 없음 · 손대지 않음")
    void guideSamplePasses() throws IOException {
        ObjectNode d = sample();
        String before = OM.writeValueAsString(d);

        Wf9InputGuard.Report r = Wf9InputGuard.check(d);

        assertThat(r.contacts()).isEqualTo(1);
        assertThat(r.segments()).isEqualTo(4);
        assertThat(r.filled()).isEmpty();
        assertThat(r.warnings()).isEmpty();
        assertThat(OM.writeValueAsString(d)).isEqualTo(before);
    }

    @Test
    @DisplayName("2026-11-20 전처리 실응답(소수 ms · UNKNOWN · skipped_short · analyzed · words) — 그대로 통과")
    void realProcessResponsePasses() throws IOException {
        JsonNode d;
        try (InputStream in = getClass().getResourceAsStream("/genon/process_22s_20261120.json")) {
            d = OM.readTree(in);
        }
        String before = OM.writeValueAsString(d);

        Wf9InputGuard.Report r = Wf9InputGuard.check(d);

        assertThat(r.segments()).isEqualTo(9);
        assertThat(r.filled()).isEmpty();
        assertThat(r.warnings()).isEmpty();
        assertThat(OM.writeValueAsString(d)).isEqualTo(before);
    }

    @Test
    @DisplayName("상태 값 — 가이드 7장 목록(no_words · unreadable_audio 포함)은 통과 · 생략하면 not_run")
    void statusValues() throws IOException {
        for (String p : Wf9InputGuard.PROSODY_STATUSES) {
            ObjectNode d = sample();
            sr(d).put("prosody_status", p);
            Wf9InputGuard.check(d);
        }
        for (String a : Wf9InputGuard.AUDIO_STATUSES) {
            ObjectNode d = sample();
            sr(d).put("audio_status", a);
            Wf9InputGuard.check(d);
        }
        assertThat(Wf9InputGuard.PROSODY_STATUSES)
                .containsExactlyInAnyOrder("not_run", "analyzed", "skipped_short", "unreadable_audio", "failed", "no_words");
        assertThat(Wf9InputGuard.AUDIO_STATUSES).containsExactlyInAnyOrder("not_run", "analyzed", "unreadable_audio", "failed");
        violates(d -> sr(d).put("prosody_status", "completed"), "speech_results[0].prosody_status 정해진 상태 값이 아니다");
        violates(d -> sr(d).put("audio_status", "skipped_short"), "speech_results[0].audio_status 정해진 상태 값이 아니다");
        violates(d -> sr(d).put("audio_status", 1), "speech_results[0].audio_status 정해진 상태 값이 아니다");
    }

    @Test
    @DisplayName("빠진 값 채움 — function · segment_id(겹치지 않게) · speaker UNKNOWN · text · 이벤트 [] · 상태 not_run · 숫자 글 ms · duration_ms 는 그대로")
    void fillsMissing() throws IOException {
        ObjectNode d = sample();
        d.remove("function");
        ObjectNode s = sr(d);
        s.remove("prosody_events");
        s.remove("audio_events");
        s.remove("prosody_status");
        s.putNull("audio_status");
        s.remove("duration_ms");
        seg(d, 0).remove("segment_id");          // 1번째 자리 — SEG-0001
        seg(d, 1).put("segment_id", "SEG-0003");  // 3번째가 채울 이름을 이미 쓴다
        seg(d, 2).remove("segment_id");          // → SEG-0004 로 비켜 간다
        seg(d, 3).put("segment_id", "SEG-X");
        seg(d, 0).remove("speaker");
        seg(d, 1).putNull("text");
        seg(d, 3).put("end_ms", "10000.5");

        Wf9InputGuard.Report r = Wf9InputGuard.check(d);

        assertThat(r.filled()).containsExactlyInAnyOrder("function", "segment_id", "speaker", "text", "end_ms(글→수)",
                "prosody_events", "audio_events", "prosody_status", "audio_status");
        assertThat(d.path("function").asText()).isEqualTo("contact_risk_analysis");
        assertThat(seg(d, 0).path("segment_id").asText()).isEqualTo("SEG-0001");
        assertThat(seg(d, 2).path("segment_id").asText()).isEqualTo("SEG-0004");
        assertThat(seg(d, 0).path("speaker").asText()).isEqualTo("UNKNOWN");
        assertThat(seg(d, 1).path("text").asText()).isEmpty();
        assertThat(seg(d, 3).path("end_ms").isNumber()).isTrue();
        assertThat(s.path("prosody_events").isArray()).isTrue();
        assertThat(s.path("audio_status").asText()).isEqualTo("not_run");
        assertThat(s.has("duration_ms")).as("선택 필드 — 지어내지 않는다").isFalse();
    }

    @Test
    @DisplayName("구간이 없으면 [] 로 채우고 보낸다(무음 녹음)")
    void noSegments() throws IOException {
        ObjectNode d = sample();
        sr(d).remove("segments");
        Wf9InputGuard.Report r = Wf9InputGuard.check(d);
        assertThat(r.segments()).isZero();
        assertThat(r.filled()).contains("segments");
    }

    @Test
    @DisplayName("경고만 — occurred_at 오프셋 없음 · duration_ms 가 마지막 구간보다 짧음 · duration_ms null 은 통과")
    void warningsOnly() throws IOException {
        ObjectNode d = sample();
        rec(d).put("occurred_at", "2026-10-30T10:00:00");
        sr(d).put("duration_ms", 1000);

        assertThat(Wf9InputGuard.check(d).warnings()).containsExactlyInAnyOrder("occurred_at 오프셋 없음", "duration_ms < 마지막 end_ms");

        ObjectNode n = sample();
        sr(n).putNull("duration_ms");
        assertThat(Wf9InputGuard.check(n).warnings()).isEmpty();
    }

    @Test
    @DisplayName("contact_id 집합 — 빠짐 · 남음 · 겹침 · 비어 있음은 위반(경로 · 규칙만)")
    void contactIdConsistency() throws IOException {
        violates(d -> sr(d).put("contact_id", "C-other"), "speech_results[0].contact_id contact_records 에 없다");
        violates(d -> rec(d).put("contact_id", " "), "contact_records[0].contact_id 없다");
        violates(d -> ((ArrayNode) d.path("input").path("contact_records")).addObject().put("contact_id", "GUIDE-ONLY-CONTACT-01")
                .put("contact_type", "접견").put("occurred_at", "2026-10-30T10:00:00+09:00"), "contact_records[1].contact_id 겹친다");
        violates(d -> ((ArrayNode) d.path("input").path("contact_records")).addObject().put("contact_id", "SYN-2")
                .put("contact_type", "접견").put("occurred_at", "2026-10-30T11:00:00+09:00"),
                "speech_results contact_records 와 contact_id 집합이 다르다(짝 없는 기록이 있다)");
        violates(d -> ((ArrayNode) d.path("input").path("speech_results")).add(sr(d).deepCopy()), "speech_results[1].contact_id 겹친다");
        violates(d -> sr(d).remove("contact_id"), "speech_results[0].contact_id 없다");
    }

    @Test
    @DisplayName("기록 · 발화 — contact_type 접견 · 전화만 · occurred_at 일시 · 발화 ID 기록 안 중복 · 화자 3종만 · low_confidence")
    void recordAndSegmentFields() throws IOException {
        violates(d -> rec(d).put("contact_type", "면회"), "contact_records[0].contact_type 접견 · 전화가 아니다");
        violates(d -> rec(d).put("occurred_at", "어제 오전"),
                "contact_records[0].occurred_at ISO-8601 일시가 아니다(예 2026-11-20T10:00:00+09:00)");
        violates(d -> rec(d).remove("occurred_at"), "contact_records[0].occurred_at 없다");
        violates(d -> seg(d, 2).put("segment_id", "SEG-01"), "speech_results[0].segments[2].segment_id 같은 기록 안에서 겹친다");
        violates(d -> seg(d, 0).put("speaker", "SPEAKER_0"), "speech_results[0].segments[0].speaker INMATE · OTHER · UNKNOWN 이 아니다");
        violates(d -> seg(d, 0).put("speaker", 1), "speech_results[0].segments[0].speaker INMATE · OTHER · UNKNOWN 이 아니다");
        violates(d -> seg(d, 0).put("low_confidence", "yes"), "speech_results[0].segments[0].low_confidence true · false · null 이 아니다");
        ObjectNode ok = sample();
        seg(ok, 0).put("low_confidence", true);
        seg(ok, 1).putNull("low_confidence");
        Wf9InputGuard.check(ok);
    }

    @Test
    @DisplayName("ms 시각 — 없음 · 수 아님 · 음수 · start_ms ≥ end_ms(같음 포함)는 위반")
    void msTimes() throws IOException {
        violates(d -> seg(d, 0).remove("start_ms"), "speech_results[0].segments[0].start_ms 없다");
        violates(d -> seg(d, 0).put("start_ms", "4초"), "speech_results[0].segments[0].start_ms 밀리초 수가 아니다");
        violates(d -> seg(d, 0).put("start_ms", -1), "speech_results[0].segments[0].start_ms 0 이상 유한 수가 아니다");
        violates(d -> seg(d, 1).put("end_ms", 100), "speech_results[0].segments[1].end_ms start_ms 보다 크지 않다");
        violates(d -> seg(d, 1).put("end_ms", 2200), "speech_results[0].segments[1].end_ms start_ms 보다 크지 않다");
        violates(d -> sr(d).put("duration_ms", "길다"), "speech_results[0].duration_ms 밀리초 수가 아니다");
    }

    @Test
    @DisplayName("words — word 글 · 시각 쌍은 함께 · start < end · score 0~1 또는 null")
    void wordsRules() throws IOException {
        Consumer<ObjectNode> addWords = d -> seg(d, 0).putArray("words").addObject().put("word", "요즘").put("start_ms", 0)
                .put("end_ms", 400).put("score", 0.9);
        ObjectNode ok = sample();
        addWords.accept(ok);
        ((ArrayNode) seg(ok, 0).path("words")).addObject().put("word", "밤에").putNull("start_ms").putNull("end_ms").putNull("score");
        ((ArrayNode) seg(ok, 0).path("words")).addObject().put("word", "잠은");   // 시각 생략
        Wf9InputGuard.check(ok);

        String w = "speech_results[0].segments[0].words[0]";
        violates(addWords.andThen(d -> ((ObjectNode) seg(d, 0).path("words").path(0)).remove("end_ms")),
                w + " start_ms · end_ms 는 함께 넣거나 생략한다");
        violates(addWords.andThen(d -> ((ObjectNode) seg(d, 0).path("words").path(0)).put("end_ms", 0)), w + ".end_ms start_ms 보다 크지 않다");
        violates(addWords.andThen(d -> ((ObjectNode) seg(d, 0).path("words").path(0)).put("score", 1.2)), w + ".score 0~1 수 · null 이 아니다");
        violates(addWords.andThen(d -> ((ObjectNode) seg(d, 0).path("words").path(0)).remove("word")), w + ".word 글이 아니다");
        violates(d -> seg(d, 0).put("words", "x"), "speech_results[0].segments[0].words 배열이 아니다");
    }

    @Test
    @DisplayName("운율 · 음향 사건 — ms 구간 · feature · direction · level_z · event_type · score")
    void eventRules() throws IOException {
        String p = "speech_results[0].prosody_events[0]";
        String a = "speech_results[0].audio_events[0]";
        violates(d -> prosody(d).put("feature", "volume"), p + ".feature 정해진 값이 아니다");
        violates(d -> prosody(d).put("direction", "flat"), p + ".direction 정해진 값이 아니다");
        violates(d -> prosody(d).put("level_z", "높음"), p + ".level_z 수 · null 이 아니다");
        violates(d -> prosody(d).put("end_ms", 2200.25), p + ".end_ms start_ms 보다 크지 않다");
        violates(d -> prosody(d).put("start_ms", "2200"), p + ".start_ms 밀리초 수가 아니다");
        violates(d -> audio(d).put("event_type", "crying"), a + ".event_type 정해진 값이 아니다");
        violates(d -> audio(d).put("score", -0.1), a + ".score 0~1 수 · null 이 아니다");
        violates(d -> audio(d).remove("end_ms"), a + ".end_ms 없다");
        violates(d -> sr(d).put("prosody_events", "x"), "speech_results[0].prosody_events 배열이 아니다");
        ObjectNode ok = sample();
        prosody(ok).putNull("level_z");
        audio(ok).putNull("score");
        Wf9InputGuard.check(ok);
        assertThat(Wf9InputGuard.AUDIO_EVENT_TYPES).hasSize(12).contains("LAUGHTER", "BREATHING");
    }

    @Test
    @DisplayName("뼈대 — 문서 · function · input · 배열 · 형 위반")
    void skeleton() throws IOException {
        assertThatThrownBy(() -> Wf9InputGuard.check(OM.readTree("[]"))).hasMessage("WF9 입력 규격 위반 — 문서 객체가 아니다");
        assertThatThrownBy(() -> Wf9InputGuard.check(null)).isInstanceOf(Wf9Failure.class);
        violates(d -> d.put("function", "summary"), "function contact_risk_analysis 이 아니다");
        violates(d -> d.remove("input"), "input 객체가 아니다");
        violates(d -> ((ObjectNode) d.path("input")).putArray("contact_records"), "input.contact_records 비었다");
        violates(d -> ((ObjectNode) d.path("input")).put("speech_results", "x"), "input.speech_results 배열이 아니다");
        violates(d -> sr(d).put("segments", "x"), "speech_results[0].segments 배열이 아니다");
        violates(d -> seg(d, 0).put("text", 3), "speech_results[0].segments[0].text 글이 아니다");
    }
}
