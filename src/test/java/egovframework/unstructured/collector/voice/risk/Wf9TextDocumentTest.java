package egovframework.unstructured.collector.voice.risk;

import com.fasterxml.jackson.databind.JsonNode;
import egovframework.unstructured.collector.common.model.SttResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static egovframework.unstructured.collector.voice.risk.Wf9TestSupport.MASKED_1;
import static egovframework.unstructured.collector.voice.risk.Wf9TestSupport.MASKED_2;
import static egovframework.unstructured.collector.voice.risk.Wf9TestSupport.OM;
import static egovframework.unstructured.collector.voice.risk.Wf9TestSupport.masked;
import static org.assertj.core.api.Assertions.assertThat;

/** WF9 텍스트 전용 입력 문서 — 가린 STT 구간 → 가이드 v4 6장 모양 · 입력 규격 검사 통과. 전사는 지어낸 가린 글이다. */
class Wf9TextDocumentTest {

    private static final String CID = "C-0123456789abcdef0123456789abcdef";
    private static final String AT = "2026-11-16T10:30+09:00";

    private static Wf9TextDocument.Built build(SttResult stt, int analyzeMaxSec) {
        return Wf9TextDocument.of(OM, CID, "접견", AT, stt, analyzeMaxSec);
    }

    private static JsonNode speech(Wf9TextDocument.Built b) {
        return b.document().path("input").path("speech_results").path(0);
    }

    @Test
    @DisplayName("구간 그대로 — SEG-0001.. · UNKNOWN · 초→밀리초 · 가린 글 · 운율/음향 [] + not_run · 길이 · 기록 정보")
    void mapsSegments() {
        Wf9TextDocument.Built b = build(masked(), 0);

        assertThat(b.segments()).isEqualTo(2);
        assertThat(b.cutSec()).isNull();
        JsonNode doc = b.document();
        assertThat(doc.path("function").asText()).isEqualTo("contact_risk_analysis");
        JsonNode rec = doc.path("input").path("contact_records");
        assertThat(rec).hasSize(1);
        assertThat(rec.get(0).path("contact_id").asText()).isEqualTo(CID);
        assertThat(rec.get(0).path("contact_type").asText()).isEqualTo("접견");
        assertThat(rec.get(0).path("occurred_at").asText()).isEqualTo(AT);

        JsonNode sr = speech(b);
        assertThat(doc.path("input").path("speech_results")).hasSize(1);
        assertThat(sr.path("contact_id").asText()).isEqualTo(CID);
        JsonNode s = sr.path("segments");
        assertThat(s.get(0).path("segment_id").asText()).isEqualTo("SEG-0001");
        assertThat(s.get(1).path("segment_id").asText()).isEqualTo("SEG-0002");
        assertThat(s.findValuesAsText("speaker")).containsOnly("UNKNOWN");
        assertThat(s.get(0).path("start_ms").asLong()).isZero();
        assertThat(s.get(0).path("end_ms").asLong()).isEqualTo(2000);
        assertThat(s.get(1).path("start_ms").asLong()).isEqualTo(2500);
        assertThat(s.get(1).path("end_ms").asLong()).isEqualTo(4000);
        assertThat(s.findValuesAsText("text")).containsExactly(MASKED_1, MASKED_2);
        assertThat(sr.path("prosody_events").isArray()).isTrue();
        assertThat(sr.path("prosody_events")).isEmpty();
        assertThat(sr.path("prosody_status").asText()).isEqualTo("not_run");
        assertThat(sr.path("audio_events").isArray()).isTrue();
        assertThat(sr.path("audio_events")).isEmpty();
        assertThat(sr.path("audio_status").asText()).isEqualTo("not_run");
        assertThat(sr.path("duration_ms").asLong()).isEqualTo(4200);
        assertThat(sr.has("transcript")).as("전문은 따로 싣지 않는다(구간에 이미 있다)").isFalse();
        assertThat(sr.has("words")).isFalse();
    }

    @Test
    @DisplayName("입력 규격 검사를 그대로 통과 — 채운 값 · 경고 없음 · 위반 없음")
    void passesInputGuard() {
        JsonNode doc = build(masked(), 0).document();
        String before = doc.toString();

        Wf9InputGuard.Report r = Wf9InputGuard.check(doc);

        assertThat(r.contacts()).isEqualTo(1);
        assertThat(r.segments()).isEqualTo(2);
        assertThat(r.filled()).isEmpty();
        assertThat(r.warnings()).isEmpty();
        assertThat(doc.toString()).as("검사가 고친 것이 없다").isEqualTo(before);
    }

    @Test
    @DisplayName("시각 다듬기 — 반올림 · 음수는 0 · 끝이 시작보다 앞서거나 같으면 시작+1ms(글은 버리지 않는다)")
    void fixesTimes() {
        SttResult stt = new SttResult("가", "MOCK", 0, false, "ko", List.of(
                new SttResult.Segment(0, -0.2, 0.0004, "가린 하나"),
                new SttResult.Segment(1, 1.2346, 1.2346, "가린 둘"),
                new SttResult.Segment(2, 3.0, 2.0, "가린 셋")));

        Wf9TextDocument.Built b = build(stt, 0);

        JsonNode s = speech(b).path("segments");
        assertThat(s).hasSize(3);
        assertThat(s.get(0).path("start_ms").asLong()).isZero();
        assertThat(s.get(0).path("end_ms").asLong()).isEqualTo(1);
        assertThat(s.get(1).path("start_ms").asLong()).isEqualTo(1235);
        assertThat(s.get(1).path("end_ms").asLong()).isEqualTo(1236);
        assertThat(s.get(2).path("start_ms").asLong()).isEqualTo(3000);
        assertThat(s.get(2).path("end_ms").asLong()).isEqualTo(3001);
        assertThat(speech(b).has("duration_ms")).as("길이 0 이면 싣지 않는다").isFalse();
        assertThat(Wf9InputGuard.check(b.document()).segments()).isEqualTo(3);
    }

    @Test
    @DisplayName("D9 — analyze-max-sec 이후에 시작하는 구간부터 싣지 않고 cutSec 를 남긴다 · 0 이하는 다 싣는다")
    void analyzeMaxSecCuts() {
        Wf9TextDocument.Built cut = build(masked(), 2);
        assertThat(cut.segments()).isEqualTo(1);
        assertThat(cut.cutSec()).isEqualTo(2.0);
        assertThat(speech(cut).path("segments").get(0).path("text").asText()).isEqualTo(MASKED_1);

        Wf9TextDocument.Built all = build(masked(), 3);
        assertThat(all.segments()).as("2.5초에 시작 — 3초 안").isEqualTo(2);
        assertThat(all.cutSec()).isNull();
        assertThat(build(masked(), -1).segments()).isEqualTo(2);
    }

    @Test
    @DisplayName("구간이 없거나(보라미 STT 재사용 · 엔진이 안 줌) 가린 구간 글이 모두 비면 — 가린 전문을 구간 하나(0~길이)로")
    void fallsBackToWholeText() {
        SttResult noSegs = new SttResult("가린 전문 ***", "SOURCE", 12, true);
        Wf9TextDocument.Built a = build(noSegs, 600);
        JsonNode s = speech(a).path("segments");
        assertThat(a.segments()).isEqualTo(1);
        assertThat(s.get(0).path("segment_id").asText()).isEqualTo("SEG-0001");
        assertThat(s.get(0).path("speaker").asText()).isEqualTo("UNKNOWN");
        assertThat(s.get(0).path("start_ms").asLong()).isZero();
        assertThat(s.get(0).path("end_ms").asLong()).isEqualTo(12_000);
        assertThat(s.get(0).path("text").asText()).isEqualTo("가린 전문 ***");
        assertThat(a.cutSec()).isNull();

        // withDeidentified 가 줄을 못 맞추면 구간 글을 "" 로 비운다 — 그때도 가린 전문으로
        SttResult blankSegs = new SttResult("가린 전문", "MOCK", 0.0, false, "ko", List.of(
                new SttResult.Segment(0, 0.0, 1.0, ""), new SttResult.Segment(1, 1.0, 2.0, " ")));
        Wf9TextDocument.Built b = build(blankSegs, 600);
        assertThat(b.segments()).isEqualTo(1);
        assertThat(speech(b).path("segments").get(0).path("end_ms").asLong()).as("길이 모르면 1ms").isEqualTo(1);
        assertThat(speech(b).path("segments").get(0).path("text").asText()).isEqualTo("가린 전문");
        assertThat(Wf9InputGuard.check(b.document()).segments()).isEqualTo(1);
    }

    @Test
    @DisplayName("일부 구간만 비었으면 구간 그대로 싣고 빈 글은 \"\" 로 — null 도 \"\"")
    void keepsPartiallyBlankSegments() {
        SttResult stt = new SttResult("가린 둘", "MOCK", 3.0, false, "ko", List.of(
                new SttResult.Segment(0, 0.0, 1.0, null), new SttResult.Segment(1, 1.0, 2.0, "가린 둘")));

        JsonNode s = speech(build(stt, 0)).path("segments");

        assertThat(s).hasSize(2);
        assertThat(s.get(0).path("text").asText()).isEmpty();
        assertThat(s.get(1).path("text").asText()).isEqualTo("가린 둘");
    }
}
