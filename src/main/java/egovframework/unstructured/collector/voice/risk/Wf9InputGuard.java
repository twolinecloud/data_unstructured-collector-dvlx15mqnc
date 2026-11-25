package egovframework.unstructured.collector.voice.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * WF9 에 보내기 전 입력 규격 검사 — 커넥터가 가린 문서({@code {function, input: {contact_records, speech_results}}})를 본다.
 *
 * <p>기준: <b>대시보드 AI API 연동 가이드 v4(11-25) 6장 '접견 · 전화 분석(Workflow 9)' · 7장 '전사 · 운율 · 음향 상태와 반환값'</b>.</p>
 * <ul>
 *   <li>{@code contact_records} 와 {@code speech_results} 는 <b>같은 contact_id 집합</b> — 기록 ID 는 요청 안에서, 발화 ID 는
 *       같은 기록 안에서 겹치지 않는다</li>
 *   <li>{@code contact_type} 은 접견 · 전화, {@code occurred_at} 은 발생 일시 문자열(ISO-8601 — 오프셋 없으면 경고)</li>
 *   <li>모든 시간은 같은 음원 시작점 기준 밀리초 — <b>0 이상 유한 수 · start_ms &lt; end_ms</b>(구간 · 낱말 · 운율 · 음향 사건)</li>
 *   <li>화자는 INMATE · OTHER · UNKNOWN 만 — 미확인을 수용자로 바꾸지 않는다(그대로 둔다)</li>
 *   <li>선택 필드: {@code words}(word 글 · 시각 쌍은 함께 넣거나 생략 · score 0~1 또는 null) · {@code low_confidence}(true/false/null) ·
 *       {@code prosody_events}(feature · direction · level_z 수 또는 null) · {@code audio_events}(event_type · score 0~1 또는 null) ·
 *       {@code duration_ms}(수 또는 null)</li>
 *   <li>상태: prosody_status ∈ {@link #PROSODY_STATUSES} · audio_status ∈ {@link #AUDIO_STATUSES} — 생략 시 not_run</li>
 * </ul>
 *
 * <p><b>위반</b>(제논도 거절할 입력)은 {@link Wf9Failure.Stage#INPUT} 으로 바로 포기한다 — 외부망으로 보내지 않는다.
 * <b>빠진 값</b>은 가이드의 기본값으로 채운다(문서를 그 자리에서 고친다): {@code function} · {@code segments}([]) ·
 * {@code segment_id}(SEG-0001… · 겹치지 않게) · {@code speaker}(UNKNOWN) · {@code text}("") · {@code prosody_events}/{@code audio_events}([]) ·
 * {@code prosody_status}/{@code audio_status}(not_run) · 숫자 글로 온 구간 시각(수로).</p>
 *
 * <p>메시지 · 보고에는 <b>경로와 규칙만</b> 싣는다(값 · 전사 글은 싣지 않는다).</p>
 */
final class Wf9InputGuard {

    static final String FUNCTION = "contact_risk_analysis";
    static final Set<String> CONTACT_TYPES = Set.of("접견", "전화");
    static final Set<String> SPEAKERS = Set.of("INMATE", "OTHER", "UNKNOWN");
    static final Set<String> PROSODY_STATUSES = Set.of("not_run", "analyzed", "skipped_short", "unreadable_audio", "failed", "no_words");
    static final Set<String> AUDIO_STATUSES = Set.of("not_run", "analyzed", "unreadable_audio", "failed");
    static final Set<String> PROSODY_FEATURES = Set.of("speech_rate", "intonation", "intensity", "silence", "pitch");
    static final Set<String> DIRECTIONS = Set.of("up", "down");
    static final Set<String> AUDIO_EVENT_TYPES = Set.of("CRYING", "SCREAMING", "SHOUTING", "SIGH", "GROAN", "IMPACT", "DOOR", "MUSIC",
            "TELEVISION", "WHISPERING", "LAUGHTER", "BREATHING");

    /** 검사 결과 — 건수 · 채운 필드 · 경고(경로 · 규칙만). */
    record Report(int contacts, int segments, Set<String> filled, Set<String> warnings) {}

    private Wf9InputGuard() {
    }

    /**
     * @param doc 가린 문서 — 빠진 값은 이 객체에 채운다
     * @throws Wf9Failure INPUT · 다시 하지 않음
     */
    static Report check(JsonNode doc) {
        if (doc == null || !doc.isObject()) {
            throw violation("문서", "객체가 아니다");
        }
        ObjectNode root = (ObjectNode) doc;
        Set<String> filled = new LinkedHashSet<>();
        Set<String> warnings = new LinkedHashSet<>();

        JsonNode fn = root.get("function");
        if (fn == null || fn.isNull() || (fn.isTextual() && fn.asText().isBlank())) {
            root.put("function", FUNCTION);
            filled.add("function");
        } else if (!fn.isTextual() || !FUNCTION.equals(fn.asText().strip())) {
            throw violation("function", FUNCTION + " 이 아니다");
        }
        JsonNode inputNode = root.get("input");
        if (inputNode == null || !inputNode.isObject()) {
            throw violation("input", "객체가 아니다");
        }
        ObjectNode input = (ObjectNode) inputNode;

        // ── contact_records
        ArrayNode records = array(input, "contact_records", "input.contact_records");
        if (records.isEmpty()) {
            throw violation("input.contact_records", "비었다");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (int i = 0; i < records.size(); i++) {
            String at = "contact_records[" + i + "]";
            JsonNode rec = records.get(i);
            if (!rec.isObject()) {
                throw violation(at, "객체가 아니다");
            }
            String id = text(rec, "contact_id");
            if (id == null) {
                throw violation(at + ".contact_id", "없다");
            }
            if (!ids.add(id)) {
                throw violation(at + ".contact_id", "겹친다");
            }
            String type = text(rec, "contact_type");
            if (type == null || !CONTACT_TYPES.contains(type)) {
                throw violation(at + ".contact_type", "접견 · 전화가 아니다");
            }
            occurredAt(text(rec, "occurred_at"), at + ".occurred_at", warnings);
        }

        // ── speech_results
        ArrayNode results = array(input, "speech_results", "input.speech_results");
        if (results.isEmpty()) {
            throw violation("input.speech_results", "비었다");
        }
        Set<String> seen = new HashSet<>();
        int segments = 0;
        for (int i = 0; i < results.size(); i++) {
            String at = "speech_results[" + i + "]";
            JsonNode srNode = results.get(i);
            if (!srNode.isObject()) {
                throw violation(at, "객체가 아니다");
            }
            ObjectNode sr = (ObjectNode) srNode;
            String id = text(sr, "contact_id");
            if (id == null) {
                throw violation(at + ".contact_id", "없다");
            }
            if (!ids.contains(id)) {
                throw violation(at + ".contact_id", "contact_records 에 없다");
            }
            if (!seen.add(id)) {
                throw violation(at + ".contact_id", "겹친다");
            }
            segments += segments(sr, at, filled);
            events(sr, at, "prosody_events", filled);
            events(sr, at, "audio_events", filled);
            status(sr, at, "prosody_status", PROSODY_STATUSES, filled);
            status(sr, at, "audio_status", AUDIO_STATUSES, filled);
            duration(sr, at, warnings);
        }
        if (seen.size() != ids.size()) {
            throw violation("speech_results", "contact_records 와 contact_id 집합이 다르다(짝 없는 기록이 있다)");
        }
        return new Report(records.size(), segments, filled, warnings);
    }

    /** {@code occurred_at} — 발생 일시 문자열. ISO-8601 오프셋 포함이 기본이고, 오프셋이 없으면 경고만. */
    private static void occurredAt(String v, String at, Set<String> warnings) {
        if (v == null) {
            throw violation(at, "없다");
        }
        try {
            OffsetDateTime.parse(v);
            return;
        } catch (DateTimeParseException ignored) {
            // 오프셋 없는 꼴인지 본다
        }
        try {
            LocalDateTime.parse(v);
            warnings.add("occurred_at 오프셋 없음");
        } catch (DateTimeParseException e) {
            throw violation(at, "ISO-8601 일시가 아니다(예 2026-11-20T10:00:00+09:00)");
        }
    }

    /** 구간 검사 · 빈 값 채움 — 구간 수를 돌려준다. */
    private static int segments(ObjectNode sr, String at, Set<String> filled) {
        JsonNode segsNode = sr.get("segments");
        if (segsNode == null || segsNode.isNull()) {
            sr.putArray("segments");
            filled.add("segments");
            return 0;
        }
        if (!segsNode.isArray()) {
            throw violation(at + ".segments", "배열이 아니다");
        }
        ArrayNode segs = (ArrayNode) segsNode;
        // 이미 있는 발화 ID 를 먼저 모은다 — 빈 자리를 채울 때 겹치지 않게
        Set<String> segIds = new HashSet<>();
        for (int j = 0; j < segs.size(); j++) {
            String sid = segs.get(j).isObject() ? text(segs.get(j), "segment_id") : null;
            if (sid != null && !segIds.add(sid)) {
                throw violation(at + ".segments[" + j + "].segment_id", "같은 기록 안에서 겹친다");
            }
        }
        for (int j = 0; j < segs.size(); j++) {
            String sat = at + ".segments[" + j + "]";
            JsonNode segNode = segs.get(j);
            if (!segNode.isObject()) {
                throw violation(sat, "객체가 아니다");
            }
            ObjectNode seg = (ObjectNode) segNode;
            span(seg, sat, true, filled);
            JsonNode text = seg.get("text");
            if (text == null || text.isNull()) {
                seg.put("text", "");
                filled.add("text");
            } else if (!text.isTextual()) {
                throw violation(sat + ".text", "글이 아니다");
            }
            if (text(seg, "segment_id") == null) {
                JsonNode sid = seg.get("segment_id");
                if (sid != null && !sid.isNull() && !sid.isTextual()) {
                    throw violation(sat + ".segment_id", "글이 아니다");
                }
                int n = j + 1;
                String made;
                do {
                    made = String.format(Locale.ROOT, "SEG-%04d", n++);
                } while (!segIds.add(made));
                seg.put("segment_id", made);
                filled.add("segment_id");
            }
            JsonNode sp = seg.get("speaker");
            if (sp == null || sp.isNull() || (sp.isTextual() && sp.asText().isBlank())) {
                seg.put("speaker", "UNKNOWN");
                filled.add("speaker");
            } else if (!sp.isTextual() || !SPEAKERS.contains(sp.asText().strip())) {
                throw violation(sat + ".speaker", "INMATE · OTHER · UNKNOWN 이 아니다");
            }
            JsonNode lc = seg.get("low_confidence");
            if (lc != null && !lc.isNull() && !lc.isBoolean()) {
                throw violation(sat + ".low_confidence", "true · false · null 이 아니다");
            }
            words(seg, sat);
        }
        return segs.size();
    }

    /** 발화의 {@code words} — 선택. word 글 · 시각 쌍은 함께 넣거나 생략(null) · score 0~1 또는 null. */
    private static void words(ObjectNode seg, String sat) {
        JsonNode ws = seg.get("words");
        if (ws == null || ws.isNull()) {
            return;
        }
        if (!ws.isArray()) {
            throw violation(sat + ".words", "배열이 아니다");
        }
        for (int k = 0; k < ws.size(); k++) {
            String wat = sat + ".words[" + k + "]";
            JsonNode w = ws.get(k);
            if (!w.isObject()) {
                throw violation(wat, "객체가 아니다");
            }
            if (!w.path("word").isTextual()) {
                throw violation(wat + ".word", "글이 아니다");
            }
            boolean hasStart = present(w, "start_ms");
            boolean hasEnd = present(w, "end_ms");
            if (hasStart != hasEnd) {
                throw violation(wat, "start_ms · end_ms 는 함께 넣거나 생략한다");
            }
            if (hasStart) {
                double s = number(w.get("start_ms"), wat + ".start_ms");
                double e = number(w.get("end_ms"), wat + ".end_ms");
                if (!(s < e)) {
                    throw violation(wat + ".end_ms", "start_ms 보다 크지 않다");
                }
            }
            score(w, wat);
        }
    }

    /** 운율 · 음향 사건 — 없으면 []. 있으면 ms 구간과 정해진 값. */
    private static void events(ObjectNode sr, String at, String field, Set<String> filled) {
        JsonNode v = sr.get(field);
        if (v == null || v.isNull()) {
            sr.putArray(field);
            filled.add(field);
            return;
        }
        if (!v.isArray()) {
            throw violation(at + "." + field, "배열이 아니다");
        }
        boolean prosody = field.equals("prosody_events");
        for (int k = 0; k < v.size(); k++) {
            String eat = at + "." + field + "[" + k + "]";
            JsonNode e = v.get(k);
            if (!e.isObject()) {
                throw violation(eat, "객체가 아니다");
            }
            span((ObjectNode) e, eat, false, filled);
            if (prosody) {
                oneOf(e, "feature", PROSODY_FEATURES, eat);
                oneOf(e, "direction", DIRECTIONS, eat);
                JsonNode z = e.get("level_z");
                if (z != null && !z.isNull() && !(z.isNumber() && Double.isFinite(z.asDouble()))) {
                    throw violation(eat + ".level_z", "수 · null 이 아니다");
                }
            } else {
                oneOf(e, "event_type", AUDIO_EVENT_TYPES, eat);
                score(e, eat);
            }
        }
    }

    /** 상태 — 없으면 not_run. 있으면 정해진 값. */
    private static void status(ObjectNode sr, String at, String field, Set<String> allowed, Set<String> filled) {
        JsonNode v = sr.get(field);
        if (v == null || v.isNull() || (v.isTextual() && v.asText().isBlank())) {
            sr.put(field, "not_run");
            filled.add(field);
        } else if (!v.isTextual() || !allowed.contains(v.asText().strip())) {
            throw violation(at + "." + field, "정해진 상태 값이 아니다");
        }
    }

    /** {@code duration_ms} — 선택(수 또는 null). 마지막 구간보다 1초 넘게 짧으면 경고만. */
    private static void duration(ObjectNode sr, String at, Set<String> warnings) {
        JsonNode d = sr.get("duration_ms");
        if (d == null || d.isNull()) {
            return;
        }
        double v = number(d, at + ".duration_ms");
        double maxEnd = 0;
        for (JsonNode s : sr.path("segments")) {
            maxEnd = Math.max(maxEnd, s.path("end_ms").asDouble(0));
        }
        if (maxEnd > v + 1000) {
            warnings.add("duration_ms < 마지막 end_ms");
        }
    }

    /**
     * ms 구간 — 둘 다 있어야 하고 0 이상 유한 수 · start_ms &lt; end_ms.
     *
     * @param coerceText 숫자 글을 수로 바꿔 채운다(발화 구간만 — 사건은 그대로 엄격히)
     */
    private static void span(ObjectNode o, String at, boolean coerceText, Set<String> filled) {
        double s = ms(o, "start_ms", at, coerceText, filled);
        double e = ms(o, "end_ms", at, coerceText, filled);
        if (!(s < e)) {
            throw violation(at + ".end_ms", "start_ms 보다 크지 않다");
        }
    }

    private static double ms(ObjectNode o, String f, String at, boolean coerceText, Set<String> filled) {
        JsonNode v = o.get(f);
        if (v == null || v.isNull()) {
            throw violation(at + "." + f, "없다");
        }
        if (coerceText && v.isTextual() && v.asText().strip().matches("\\d{1,12}(\\.\\d{1,9})?")) {
            double d = Double.parseDouble(v.asText().strip());
            o.put(f, d);
            filled.add(f + "(글→수)");
            return d;
        }
        return number(v, at + "." + f);
    }

    /** 0 이상 유한 수. */
    private static double number(JsonNode v, String path) {
        if (v == null || !v.isNumber()) {
            throw violation(path, "밀리초 수가 아니다");
        }
        double d = v.asDouble();
        if (!Double.isFinite(d) || d < 0) {
            throw violation(path, "0 이상 유한 수가 아니다");
        }
        return d;
    }

    private static void score(JsonNode o, String at) {
        JsonNode s = o.get("score");
        if (s != null && !s.isNull() && !(s.isNumber() && s.asDouble() >= 0 && s.asDouble() <= 1)) {
            throw violation(at + ".score", "0~1 수 · null 이 아니다");
        }
    }

    private static void oneOf(JsonNode o, String f, Set<String> allowed, String at) {
        JsonNode v = o.get(f);
        if (v == null || !v.isTextual() || !allowed.contains(v.asText())) {
            throw violation(at + "." + f, "정해진 값이 아니다");
        }
    }

    private static boolean present(JsonNode o, String f) {
        JsonNode v = o.get(f);
        return v != null && !v.isNull();
    }

    private static ArrayNode array(ObjectNode o, String f, String at) {
        JsonNode v = o.get(f);
        if (v == null || !v.isArray()) {
            throw violation(at, "배열이 아니다");
        }
        return (ArrayNode) v;
    }

    /** 비지 않은 글이면 다듬어 돌려주고, 아니면 null. */
    private static String text(JsonNode o, String f) {
        JsonNode v = o.get(f);
        return v != null && v.isTextual() && !v.asText().isBlank() ? v.asText().strip() : null;
    }

    private static Wf9Failure violation(String path, String rule) {
        return Wf9Failure.permanent(Wf9Failure.Stage.INPUT, "WF9 입력 규격 위반 — " + path + " " + rule);
    }
}
