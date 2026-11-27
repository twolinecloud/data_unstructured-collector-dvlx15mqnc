package egovframework.unstructured.collector.voice.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import egovframework.unstructured.collector.common.model.SttResult;

import java.util.Locale;

/**
 * WF9 텍스트 전용 입력 문서 — 본 배치가 커넥터 비식별을 마친 전사({@link SttResult})로 만든다(2026-11-27).
 *
 * <p><b>왜</b>: 예전에는 복호화한 음성을 제논 전처리({@code audio/process})로 보내 전사 · 운율 · 음향 분석을 받고, 그 결과를
 * 커넥터로 한 번 더 가렸다. 그 길은 <b>원본 음성이 외부망으로 나간다</b>. 이제는 본 배치가 이미 만든 전사 · 구간을 쓰고,
 * 그 전사는 본 배치의 DEIDENT 단계(커넥터 {@code /api/v1/pipeline/deident/voice})에서 이미 가렸다 — 이중 비식별 없이 가린 글만 나간다.</p>
 *
 * <p>모양은 대시보드 AI API 연동 가이드 v4 6 · 7장(접견 · 전화 분석)을 따른다. 가이드 2쪽 호출 예시도 이 꼴(텍스트 + 운율 · 음향 미수행)이고,
 * 2026-11-27 실호출(가상 토큰 대화 · HTTP 200 · 422 없음)로 받아짐을 확인했다.</p>
 * <ul>
 *   <li>{@code segments[{segment_id, speaker, start_ms, end_ms, text}]} — STT 구간 그대로. 시각은 초 → 밀리초(반올림)</li>
 *   <li>{@code speaker} 는 {@code UNKNOWN} — 우리 STT 는 화자를 가르지 않는다. 가이드대로 미확인을 수용자 · 상대로 바꾸지 않는다</li>
 *   <li>운율 · 음향 사건은 분석하지 않았다 — {@code prosody_events}/{@code audio_events} = [] · 상태 = {@code not_run}(가이드 7쪽)</li>
 *   <li>구간이 없거나(엔진이 안 줌 · 보라미 STT 재사용) 가린 구간 글이 모두 비었으면(줄 맞춤 실패) 가린 전문을 구간 하나로 싣는다</li>
 *   <li>{@code analyze-max-sec}(D9) — 그 시각 이후에 시작하는 구간은 싣지 않는다(앞부분만 분석)</li>
 * </ul>
 *
 * <p>녹음 ID · 교정번호는 싣지 않는다 — 기록 ID 는 스풀이 만든 무작위 대체 ID({@link Wf9Job#contactId()})다.</p>
 */
final class Wf9TextDocument {

    static final String SPEAKER = "UNKNOWN";
    static final String NOT_RUN = "not_run";

    /**
     * @param document 가이드 v4 6장 모양의 문서
     * @param segments 실은 구간 수
     * @param cutSec   D9 로 앞부분만 실었으면 그 길이(초), 아니면 null
     */
    record Built(ObjectNode document, int segments, Double cutSec) {}

    private Wf9TextDocument() {
    }

    /**
     * @param masked        커넥터가 가린 전사 — 원문 전사를 넘기면 안 된다(호출자 {@link Wf9Spool#offer} 가 모드로 막는다)
     * @param contactType   접견 · 전화
     * @param occurredAt    발생 일시(ISO-8601 · KST 오프셋 포함)
     * @param analyzeMaxSec 앞에서부터 실을 길이(초) — 0 이하면 다 싣는다
     */
    static Built of(ObjectMapper om, String contactId, String contactType, String occurredAt, SttResult masked,
                    int analyzeMaxSec) {
        ObjectNode doc = om.createObjectNode();
        doc.put("function", Wf9InputGuard.FUNCTION);
        ObjectNode input = doc.putObject("input");
        input.putArray("contact_records").addObject()
                .put("contact_id", contactId)
                .put("contact_type", contactType)
                .put("occurred_at", occurredAt);
        ObjectNode sr = input.putArray("speech_results").addObject();
        sr.put("contact_id", contactId);
        ArrayNode segs = sr.putArray("segments");

        long limitMs = analyzeMaxSec > 0 ? analyzeMaxSec * 1000L : Long.MAX_VALUE;
        boolean cut = false;
        if (masked.hasSegments() && masked.segments().stream().anyMatch(s -> s.text() != null && !s.text().isBlank())) {
            for (SttResult.Segment s : masked.segments()) {
                long start = Math.round(Math.max(0, s.start()) * 1000);
                if (start >= limitMs) {
                    cut = true;
                    break;
                }
                long end = Math.round(Math.max(0, s.end()) * 1000);
                if (end <= start) {
                    end = start + 1;   // 가이드: start_ms < end_ms — 길이 0 인 구간은 1ms 로 둔다(글은 버리지 않는다)
                }
                segment(segs, segs.size() + 1, start, end, s.text());
            }
        } else {
            long end = Math.max(1, Math.round(Math.max(0, masked.duration()) * 1000));
            segment(segs, 1, 0, end, masked.text());
        }

        sr.putArray("prosody_events");
        sr.put("prosody_status", NOT_RUN);
        sr.putArray("audio_events");
        sr.put("audio_status", NOT_RUN);
        if (masked.duration() > 0) {
            sr.put("duration_ms", Math.round(masked.duration() * 1000));
        }
        return new Built(doc, segs.size(), cut ? (double) analyzeMaxSec : null);
    }

    private static void segment(ArrayNode segs, int n, long startMs, long endMs, String text) {
        segs.addObject()
                .put("segment_id", String.format(Locale.ROOT, "SEG-%04d", n))
                .put("speaker", SPEAKER)
                .put("start_ms", startMs)
                .put("end_ms", endMs)
                .put("text", text == null ? "" : text);
    }
}
