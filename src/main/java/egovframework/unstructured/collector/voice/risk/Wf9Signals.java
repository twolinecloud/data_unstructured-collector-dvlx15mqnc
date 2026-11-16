package egovframework.unstructured.collector.voice.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import egovframework.unstructured.collector.common.logging.LogCollectorClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WF9 답 → 주요사항 행.
 *
 * <ol>
 *   <li>코드 울타리(```)가 있으면 첫 울타리 안만 본다 → 첫 '{' ~ 끝 '}' 를 JSON 으로 읽는다 → {@code findings} · {@code reviews}</li>
 *   <li>항목 하나 = 행 하나. 가이드가 항목 구조를 정하지 않아 '내용' · '위험 분류' 는 설정한 필드 이름 목록에서 앞에 둔 것부터 찾는다.
 *       '내용'을 못 찾으면 항목 JSON 을 그대로 쓴다(항목이 글이면 그 글)</li>
 *   <li>'내용'만 메모리 사전으로 되돌린다(D6) — 원 항목({@code signalData})은 가린 그대로 둔다</li>
 * </ol>
 */
final class Wf9Signals {

    /** 커넥터 토큰 모양 — {@code [NAME_1]} · {@code [CASE_NO_2]}(커넥터 · 로그 컬렉터와 같은 규칙). */
    static final Pattern TOKEN = Pattern.compile("\\[[A-Z][A-Z0-9_]*_\\d+]");
    private static final Pattern FENCE = Pattern.compile("```[^\\n`]*\\n?(.*?)```", Pattern.DOTALL);

    record Parsed(List<JsonNode> findings, List<JsonNode> reviews) {}

    /** 행으로 바꾼 결과 — 내용이 비어 버린 항목 수를 함께. */
    record Rows(List<LogCollectorClient.ContactRiskSignalReq> signals, int skipped) {}

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
        int[] skipped = {0};
        add(out, skipped, "FINDING", p.findings(), mapping, contentFields, riskClassFields);
        add(out, skipped, "REVIEW", p.reviews(), mapping, contentFields, riskClassFields);
        return new Rows(out, skipped[0]);
    }

    private static void add(List<LogCollectorClient.ContactRiskSignalReq> out, int[] skipped, String se, List<JsonNode> items,
                            Map<String, String> mapping, List<String> contentFields, List<String> riskClassFields) {
        for (JsonNode item : items) {
            String cn = item.isValueNode() ? item.asText() : firstText(item, contentFields);
            if (cn == null) {
                cn = item.toString();
            }
            cn = restore(cn, mapping).strip();
            if (cn.isEmpty()) {
                skipped[0]++;
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
