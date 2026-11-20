package egovframework.unstructured.collector.voice.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import egovframework.unstructured.collector.common.logging.LogCollectorClient;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WF9 답 → 주요사항 행.
 *
 * <ol>
 *   <li>코드 울타리(```)가 있으면 첫 울타리 안만 본다 → 첫 '{' ~ 끝 '}' 를 JSON 으로 읽는다 → {@code findings} · {@code reviews}</li>
 *   <li>항목 하나 = 행 하나. '내용' · '위험 분류' 는 설정한 필드 이름 목록에서 앞에 둔 것부터 찾는다.
 *       '내용'을 못 찾으면 항목 JSON 을 그대로 쓴다(항목이 글이면 그 글)</li>
 *   <li>'내용'만 메모리 사전으로 되돌린다(D6) — 원 항목({@code signalData})은 가린 그대로 둔다</li>
 *   <li>위험 분류는 받은 값 그대로 넘긴다 — 코드표로 맞추는 일은 코드를 가진 로그 컬렉터가 한다(D4 · 코드표 밖은 ETC)</li>
 * </ol>
 *
 * <p><b>항목 규격</b> — 2026-11-18 우리 안({@code riskClassCode} · 코드표 값)에서 2026-11-20 제논 실응답(가이드 11-19판)으로 바꿨다:
 * <br>{@code {"contact_id", "occurred_at", "type": 접견·전화, "content": 화면 '내용', "risk_type": 한글 위험 유형, "evidence": [구간]}}
 * <br>구간 = {@code {start_ms, end_ms, segment_id, speaker, text}}. 목록의 <b>첫 이름</b>이 약속한 필드다(기본 {@code content} ·
 * {@code risk_type}). {@code type} 은 접견·전화 구분이라 위험 분류 후보에 두지 않는다. 약속한 '내용' 필드가 없는 항목은 '규격 밖'으로
 * 세고 그 항목이 쓴 필드 이름을 모아 경고로 남긴다(값은 싣지 않는다) — 응답 모양이 바뀌면 설정 {@code voice.risk-wf9.content-fields} ·
 * {@code risk-class-fields} 만 바꾸면 된다.</p>
 */
final class Wf9Signals {

    /** 커넥터 토큰 모양 — {@code [NAME_1]} · {@code [CASE_NO_2]}(커넥터 · 로그 컬렉터와 같은 규칙). */
    static final Pattern TOKEN = Pattern.compile("\\[[A-Z][A-Z0-9_]*_\\d+]");
    private static final Pattern FENCE = Pattern.compile("```[^\\n`]*\\n?(.*?)```", Pattern.DOTALL);
    /** 경고 로그에 싣는 필드 이름 — 짧은 이름 꼴만(값이 아니라 키지만 LLM 이 만든 것이라 거른다). */
    private static final Pattern FIELD_NAME = Pattern.compile("[\\p{IsHangul}A-Za-z0-9_]{1,40}");
    private static final int OFF_SPEC_FIELDS_MAX = 10;

    record Parsed(List<JsonNode> findings, List<JsonNode> reviews) {}

    /**
     * 행으로 바꾼 결과.
     *
     * @param skipped       내용이 비어 뺀 항목 수
     * @param offSpec       규격 밖 항목 수 — 약속한 '내용' 필드(목록 첫 이름)에 글이 없는 항목(글 · 배열 항목 포함)
     * @param offSpecFields 규격 밖 항목들이 쓴 필드 이름(처음 본 순서 · 최대 10개). 객체가 아니면 {@code (글)} · {@code (배열)} 따위
     */
    record Rows(List<LogCollectorClient.ContactRiskSignalReq> signals, int skipped, int offSpec, List<String> offSpecFields) {}

    private Wf9Signals() {
    }

    /** @throws Wf9Failure PARSE(다시 하기) — LLM 답은 다시 물으면 모양이 맞을 수 있다 */
    static Parsed parse(ObjectMapper om, String text) {
        String s = text == null ? "" : text.strip();
        Matcher f = FENCE.matcher(s);
        if (f.find()) {
            s = f.group(1).strip();
        }
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        if (a < 0 || b <= a) {
            throw Wf9Failure.retry(Wf9Failure.Stage.PARSE, "WF9 답에 JSON 객체가 없다");
        }
        JsonNode n;
        try {
            n = om.readTree(s.substring(a, b + 1));
        } catch (Exception e) {
            throw Wf9Failure.retry(Wf9Failure.Stage.PARSE, "WF9 답의 JSON 을 읽지 못함 — " + e.getClass().getSimpleName());
        }
        if (n == null || !n.isObject() || !(n.has("findings") || n.has("reviews"))) {
            throw Wf9Failure.retry(Wf9Failure.Stage.PARSE, "WF9 답에 findings · reviews 가 없다");
        }
        return new Parsed(items(n, "findings"), items(n, "reviews"));
    }

    private static List<JsonNode> items(JsonNode n, String field) {
        JsonNode a = n.get(field);
        if (a == null || a.isNull()) {
            return List.of();
        }
        if (!a.isArray()) {
            throw Wf9Failure.retry(Wf9Failure.Stage.PARSE, "WF9 답의 " + field + " 가 배열이 아니다");
        }
        List<JsonNode> out = new ArrayList<>(a.size());
        a.forEach(x -> {
            if (x != null && !x.isNull()) {
                out.add(x);
            }
        });
        return out;
    }

    static Rows toRows(Parsed p, Map<String, String> mapping, List<String> contentFields, List<String> riskClassFields) {
        List<LogCollectorClient.ContactRiskSignalReq> out = new ArrayList<>(p.findings().size() + p.reviews().size());
        Tally t = new Tally();
        add(out, t, "FINDING", p.findings(), mapping, contentFields, riskClassFields);
        add(out, t, "REVIEW", p.reviews(), mapping, contentFields, riskClassFields);
        return new Rows(out, t.skipped, t.offSpec, List.copyOf(t.offSpecFields));
    }

    /** 집계 — 뺀 항목 · 규격 밖 항목 · 그 필드 이름. */
    private static final class Tally {
        int skipped;
        int offSpec;
        final Set<String> offSpecFields = new LinkedHashSet<>();

        void offSpec(JsonNode item) {
            offSpec++;
            if (item.isObject()) {
                item.fieldNames().forEachRemaining(this::field);
            } else {
                field(item.isArray() ? "(배열)" : item.isTextual() ? "(글)" : "(" + item.getNodeType().name().toLowerCase(Locale.ROOT) + ")");
            }
        }

        private void field(String name) {
            if (offSpecFields.size() < OFF_SPEC_FIELDS_MAX) {
                offSpecFields.add(name.startsWith("(") || FIELD_NAME.matcher(name).matches() ? name : "(이름 형식 밖)");
            }
        }
    }

    /** 약속한 '내용' 필드(목록 첫 이름)에 글이 있는가. */
    static boolean inSpec(JsonNode item, List<String> contentFields) {
        if (contentFields.isEmpty() || item == null || !item.isObject()) {
            return false;
        }
        JsonNode v = item.get(contentFields.get(0));
        return v != null && v.isTextual() && !v.asText().isBlank();
    }

    private static void add(List<LogCollectorClient.ContactRiskSignalReq> out, Tally t, String se, List<JsonNode> items,
                            Map<String, String> mapping, List<String> contentFields, List<String> riskClassFields) {
        for (JsonNode item : items) {
            if (!inSpec(item, contentFields)) {
                t.offSpec(item);
            }
            String cn = item.isValueNode() ? item.asText() : firstText(item, contentFields);
            if (cn == null) {
                cn = item.toString();
            }
            cn = restore(cn, mapping).strip();
            if (cn.isEmpty()) {
                t.skipped++;
                continue;
            }
            String risk = item.isObject() ? firstText(item, riskClassFields) : null;
            out.add(new LogCollectorClient.ContactRiskSignalReq(se, null, risk == null ? null : risk.strip(), cn, item));
        }
    }

    /** 앞에 둔 필드부터 — 글 · 숫자 값만 받는다. */
    static String firstText(JsonNode item, List<String> fields) {
        if (item == null || !item.isObject()) {
            return null;
        }
        for (String f : fields) {
            JsonNode v = item.get(f);
            if (v != null && (v.isTextual() || v.isNumber()) && !v.asText().isBlank()) {
                return v.asText();
            }
        }
        return null;
    }

    /** 토큰을 원문으로 — 한 번 훑는다(되돌린 값 안에서 다시 바꾸지 않는다). 사전에 없는 토큰은 그대로 둔다. */
    static String restore(String text, Map<String, String> mapping) {
        if (text == null || text.isEmpty() || mapping == null || mapping.isEmpty()) {
            return text == null ? "" : text;
        }
        Matcher m = TOKEN.matcher(text);
        StringBuilder sb = new StringBuilder(text.length() + 16);
        while (m.find()) {
            String orig = mapping.get(m.group());
            m.appendReplacement(sb, Matcher.quoteReplacement(orig == null ? m.group() : orig));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
