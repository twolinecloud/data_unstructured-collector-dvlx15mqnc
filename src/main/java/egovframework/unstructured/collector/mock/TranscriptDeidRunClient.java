package egovframework.unstructured.collector.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import egovframework.unstructured.collector.common.transfer.AgentConnectorClient;
import egovframework.unstructured.collector.common.transfer.AgentConnectorProperties;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 전사문 비식별 — 에이전트 커넥터 {@code POST /api/v1/deid/run}(챗봇 질문과 같은 운영 비식별 경로)을 부른다. 시뮬레이터 11번 탭 전용.
 *
 * <p><b>STT 원천 규격으로 보낸다</b> — 본문 {@code {"sttSessionId":…, "STT_VISIT":{"TRANSCRIPT_TEXT":…}}} 은 커넥터 매핑 룰
 * 11번(스마트접견 STT · AI-R)이 가리는 자리다. 출처 · 문서키는 쿼리로 못 박는다({@code srcSysCd=STT} · {@code docId=STT_SESSION:…}).</p>
 *
 * <p><b>적재하지 않는다</b>({@code persist=false} · {@code includeMapping=false}) — 커넥터는 사전을 읽기만 하고 아무것도 쓰지 않는다.
 * 그래도 응답의 {@code items[].reidMapping} 에는 토큰 ↔ 원문이 실려 온다. 그래서 가린 글 · 상태 · 개수만 꺼내고 나머지는 버린다.
 * 비식별이 실패한 건의 {@code deidentedDataset} 은 원문 그대로라 읽지 않는다. 글 · 응답 본문은 로그에 남기지 않는다.</p>
 *
 * <p>상태 — 200 + {@code success:true} 만 성공이다. 504 {@code DEID_TIMEOUT}(커넥터의 AI-R 대기 한도 초과)과 500 {@code success:false}
 * 는 사유를 붙여 {@link DeidRunException} 으로 올린다. 전송 모드가 MOCK 이면 커넥터를 부르지 않고 여기서 전화 · 주민번호 · 이메일만
 * 토큰으로 바꾼다(이름은 못 찾는다).</p>
 */
@Log4j2
@Component
@Profile({"dev", "local"})
public class TranscriptDeidRunClient {

    /** 커넥터 토큰 — {@code [NAME_1]} · {@code [PHONE_NUM_2]}. 유형에 밑줄이 들어갈 수 있다. */
    public static final Pattern TOKEN = Pattern.compile("\\[([A-Z][A-Z0-9_]*)_(\\d+)]");
    /** 별표 마스킹 — 토큰이 아니라 별표로 가린 자리(엔진 · 룰에 따라). */
    public static final Pattern MASK = Pattern.compile("\\*{2,}");

    /** MOCK — 숫자 꼴만. 같은 값은 같은 토큰. 주민번호를 먼저 본다(전화 꼴과 겹치지 않게). */
    private static final List<Map.Entry<String, Pattern>> MOCK_RULES = List.of(
            Map.entry("RRN", Pattern.compile("\\d{6}-?[1-4]\\d{6}")),
            Map.entry("PHONE", Pattern.compile("01[016789]-?\\d{3,4}-?\\d{4}")),
            Map.entry("EMAIL", Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")));

    /**
     * 비식별 결과 — 가린 글과 개수. 원문 · 사전은 들지 않는다.
     *
     * @param mode            REST(커넥터) · MOCK(수집기 내장)
     * @param tokens          토큰 자리 수(같은 토큰이 두 번이면 2)
     * @param uniqueTokens    서로 다른 토큰 수
     * @param tokenTypes      유형별 서로 다른 토큰 수(NAME → 2)
     * @param masks           별표 마스킹 자리 수
     * @param piiItemCnt      커넥터가 처리한 필드 수(전사문 하나면 1). MOCK 은 null
     * @param connectorExecId 커넥터 응답의 execId — 보낸 것이 없으면 커넥터가 만든 로컬 번호
     */
    public record Result(String mode, String endpoint, int httpStatus, long elapsedMs, String text, int inputChars,
                         int tokens, int uniqueTokens, Map<String, Integer> tokenTypes, int masks, Integer piiItemCnt,
                         String connectorExecId) {
    }

    /** 비식별 실패 — 메시지에 전사 내용 · 응답 본문을 싣지 않는다. */
    public static class DeidRunException extends RuntimeException {

        private final int httpStatus;
        private final String code;
        private final long elapsedMs;

        public DeidRunException(String message, int httpStatus, String code, long elapsedMs, Throwable cause) {
            super(message, cause);
            this.httpStatus = httpStatus;
            this.code = code;
            this.elapsedMs = elapsedMs;
        }

        /** 커넥터 HTTP 상태. 받지 못했으면 0. */
        public int httpStatus() {
            return httpStatus;
        }

        /** 커넥터 오류 코드(DEID_TIMEOUT 등). 없으면 null. */
        public String code() {
            return code;
        }

        public long elapsedMs() {
            return elapsedMs;
        }
    }

    private final AgentConnectorClient connector;
    private final AgentConnectorProperties connectorProps;
    private final SttDeidProperties props;
    private final ObjectMapper om;
    private final HttpClient http;

    public TranscriptDeidRunClient(AgentConnectorClient connector, AgentConnectorProperties connectorProps,
                                   SttDeidProperties props, ObjectMapper objectMapper) {
        this.connector = connector;
        this.connectorProps = connectorProps;
        this.props = props;
        this.om = objectMapper;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(Math.max(500, connectorProps.connectTimeoutMs())))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** 지금 부를 곳 — 전송 모드(시뮬레이터가 바꿀 수 있다)를 따른다. */
    public String mode() {
        return connector.effectiveMode() == AgentConnectorProperties.Mode.MOCK ? "MOCK" : "REST";
    }

    public String endpoint() {
        return "MOCK".equals(mode()) ? "수집기 내장 MOCK — 커넥터를 부르지 않음(전화 · 주민번호 · 이메일만)"
                : base() + props.deidPath();
    }

    /** 화면 표시용 — 주소 · 상한 · 적재 안 함. */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", mode());
        m.put("endpoint", endpoint());
        m.put("baseUrlSet", StringUtils.hasText(base()));
        m.put("readTimeoutSec", props.deidReadTimeoutSec());
        m.put("airMode", StringUtils.hasText(props.airMode()) ? props.airMode().trim() : "커넥터 기본(REAL)");
        m.put("persist", false);
        m.put("query", "persist=false&includeMapping=false&srcSysCd=STT&docId=STT_SESSION:{sttSessionId}");
        return m;
    }

    /**
     * 전사문 하나를 비식별한다.
     *
     * @param execId    REAL 실행이면 T1 실행 ID(커넥터 로그 추적용) — SIM 이면 null(커넥터가 로컬 번호를 만든다)
     * @param sessionId 문서키 재료 — {@code STT_SESSION:{sessionId}}. 실행마다 새 값이라 저장된 사전이 없다
     * @throws DeidRunException 주소 없음 · 연결 · 타임아웃 · 504 · 500 · 결과 글 없음
     */
    public Result deidentify(String execId, String sessionId, String text) {
        String body = text == null ? "" : text;
        if ("MOCK".equals(mode())) {
            return mock(body);
        }
        String base = base();
        if (!StringUtils.hasText(base)) {
            throw new DeidRunException("에이전트 커넥터 주소가 비어 있다 — agent-connector.base-url(AGENT_CONNECTOR_BASE_URL)",
                    0, "CONFIG", 0, null);
        }
        StringBuilder url = new StringBuilder(base).append(props.deidPath())
                .append("?persist=false&includeMapping=false&srcSysCd=STT&docId=").append(enc("STT_SESSION:" + sessionId));
        if (StringUtils.hasText(props.airMode())) {
            url.append("&airMode=").append(enc(props.airMode().trim()));
        }
        ObjectNode req = om.createObjectNode();
        if (execId != null) {
            req.put("execId", execId);
        }
        req.put("sttSessionId", sessionId);
        req.putObject("STT_VISIT").put("TRANSCRIPT_TEXT", body);

        long t0 = System.nanoTime();
        HttpResponse<byte[]> res;
        try {
            res = http.send(HttpRequest.newBuilder(URI.create(url.toString()))
                            .timeout(Duration.ofSeconds(Math.max(1, props.deidReadTimeoutSec())))
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(om.writeValueAsBytes(req)))
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpConnectTimeoutException e) {
            throw new DeidRunException("커넥터 비식별 연결 실패 — 연결 시간 초과", 0, null, ms(t0), e);
        } catch (HttpTimeoutException e) {
            throw new DeidRunException("커넥터 비식별 응답 타임아웃 — %d초".formatted(props.deidReadTimeoutSec()),
                    0, null, ms(t0), e);
        } catch (IOException e) {
            throw new DeidRunException("커넥터 비식별 연결 실패 — " + e.getClass().getSimpleName(), 0, null, ms(t0), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeidRunException("커넥터 비식별 호출 중 중단됨", 0, null, ms(t0), e);
        } catch (IllegalArgumentException e) {
            throw new DeidRunException("에이전트 커넥터 주소 설정 오류 — agent-connector.base-url 확인", 0, "CONFIG", ms(t0), e);
        }
        long ms = ms(t0);
        int s = res.statusCode();
        JsonNode r = parse(res.body());
        if (s == 504) {
            String code = r.path("code").asText("");
            throw new DeidRunException("커넥터 비식별 타임아웃 — HTTP 504 " + (code.isEmpty()
                    ? "(코드 없음 — 앞단 프록시가 먼저 끊었을 수 있다)" : code), 504, code.isEmpty() ? null : code, ms, null);
        }
        JsonNode item = r.path("items").path(0);
        if (s == 200 && r.path("success").asBoolean(false) && "SUCCESS".equals(item.path("deidentStsCd").asText(""))) {
            JsonNode masked = item.path("deidentedDataset").path("STT_VISIT").path("TRANSCRIPT_TEXT");
            if (!masked.isTextual()) {
                throw new DeidRunException("커넥터 비식별 응답에 가린 글이 없다(STT_VISIT.TRANSCRIPT_TEXT) — 커넥터 매핑 룰 확인",
                        s, null, ms, null);
            }
            Result out = counted("REST", endpoint(), s, ms, masked.asText(), body.length(),
                    item.hasNonNull("piiItemCnt") ? item.get("piiItemCnt").asInt() : null, r.path("execId").asText(null));
            log.info("[STT-DEID] 커넥터 비식별 — HTTP {} · {}ms · {}자 · 토큰 {}(서로 다른 {}) · 별표 {}", s, ms, body.length(),
                    out.tokens(), out.uniqueTokens(), out.masks());
            return out;
        }
        if (s == 200 || s == 500) {
            // success:false — 실패한 건의 deidentedDataset 에는 원문이 남아 있으므로 읽지 않는다. 사유는 코드 · 오류 한 줄만
            JsonNode e0 = item.path("errors").path(0);
            String code = firstNonBlank(r.path("errorCode").asText(null), e0.path("errorCode").asText(null));
            String why = firstNonBlank(e0.path("error").asText(null), r.path("problems").path(0).asText(null));
            String msg = "커넥터 비식별 실패 — HTTP " + s + (code == null ? "" : " " + code)
                    + (why == null ? "" : " · " + cap(why, 160));
            log.warn("[STT-DEID] {} · {}ms", msg, ms);
            throw new DeidRunException(msg, s, code, ms, null);
        }
        throw new DeidRunException("커넥터 비식별 호출 실패 — HTTP " + s, s, null, ms, null);
    }

    /** 가린 글의 토큰 · 별표를 센다. */
    static Result counted(String mode, String endpoint, int status, long ms, String masked, int inputChars,
                          Integer piiItemCnt, String connectorExecId) {
        Matcher m = TOKEN.matcher(masked);
        int tokens = 0;
        Set<String> unique = new HashSet<>();
        Map<String, Integer> types = new LinkedHashMap<>();
        while (m.find()) {
            tokens++;
            if (unique.add(m.group())) {
                types.merge(m.group(1), 1, Integer::sum);
            }
        }
        int masks = 0;
        Matcher k = MASK.matcher(masked);
        while (k.find()) {
            masks++;
        }
        return new Result(mode, endpoint, status, ms, masked, inputChars, tokens, unique.size(), types, masks,
                piiItemCnt, connectorExecId);
    }

    /** MOCK — 숫자 꼴(주민번호 · 전화)과 이메일만 토큰으로. 같은 값은 같은 토큰. 밖으로 나가는 것 없음. */
    static Result mock(String text) {
        long t0 = System.nanoTime();
        String out = text;
        for (Map.Entry<String, Pattern> rule : MOCK_RULES) {
            Map<String, String> seen = new LinkedHashMap<>();
            Matcher m = rule.getValue().matcher(out);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String token = seen.computeIfAbsent(m.group(), v -> "[" + rule.getKey() + "_" + (seen.size() + 1) + "]");
                m.appendReplacement(sb, Matcher.quoteReplacement(token));
            }
            m.appendTail(sb);
            out = sb.toString();
        }
        return counted("MOCK", "수집기 내장 MOCK", 200, ms(t0), out, text.length(), null, null);
    }

    private String base() {
        String b = connectorProps.baseUrl() == null ? "" : connectorProps.baseUrl().trim();
        return b.replaceAll("/+$", "");
    }

    private JsonNode parse(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return TextNode.valueOf("");
        }
        try {
            return om.readTree(raw);
        } catch (IOException e) {
            return TextNode.valueOf("");
        }
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.strip();
        }
        return b != null && !b.isBlank() ? b.strip() : null;
    }

    private static String cap(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private static long ms(long nanoStart) {
        return (System.nanoTime() - nanoStart) / 1_000_000L;
    }
}
