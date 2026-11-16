package egovframework.unstructured.collector.voice.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import egovframework.unstructured.collector.common.transfer.AgentConnectorClient;
import egovframework.unstructured.collector.common.transfer.AgentConnectorProperties;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 커넥터 {@code POST /api/v1/deid/speech-results} — 제논 전처리 응답을 그대로 넘기고, 구조를 지킨 채 글만 토큰으로 바꾼
 * 문서와 토큰→원문 사전을 받는다.
 *
 * <p>사전은 <b>이 작업의 메모리에만</b> 둔다(D6) — WF9 결과의 '내용'을 되돌리는 데만 쓰고 디스크 · 로그에 남기지 않는다.</p>
 *
 * <ul>
 *   <li>400 · 422 — 같은 입력이면 다시 보내도 같다(형식 오류 · 잔존 개인정보) → 바로 포기</li>
 *   <li>그 밖의 HTTP 오류 · 연결 실패 · 시간 초과 · 응답 모양 이상 → 나중에 다시</li>
 *   <li>예외 메시지에는 상태 · 규격 코드({@code code}) · 예외 이름만 — 응답 본문(남은 자리 · 원문)을 싣지 않는다</li>
 * </ul>
 */
@Log4j2
@Component
public class SpeechResultsDeidClient {

    /** 비식별 결과 — 가린 문서 · 토큰→원문(메모리 전용). */
    public record Result(JsonNode document, Map<String, String> mapping) {

        /** 사전을 싣지 않는다. */
        @Override
        public String toString() {
            return "Result[tokens=" + mapping.size() + "]";
        }
    }

    private final ObjectMapper om;
    private final Supplier<String> baseUrl;
    private final Supplier<Boolean> mock;
    private final String path;
    private final RestTemplate rest;

    @Autowired
    public SpeechResultsDeidClient(AgentConnectorClient connector, AgentConnectorProperties connectorProps,
                                   RiskWf9Properties props, ObjectMapper objectMapper) {
        this(objectMapper, connectorProps::baseUrl,
                () -> connector.effectiveMode() == AgentConnectorProperties.Mode.MOCK,
                props.deidPath(), connectorProps.connectTimeoutMs(), props.deidTimeoutSec());
    }

    SpeechResultsDeidClient(ObjectMapper om, Supplier<String> baseUrl, Supplier<Boolean> mock, String path,
                            int connectTimeoutMs, int readTimeoutSec) {
        this.om = om;
        this.baseUrl = baseUrl;
        this.mock = mock;
        this.path = path == null || path.isBlank() ? "/api/v1/deid/speech-results" : path.strip();
        JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(500, connectTimeoutMs))).build());
        f.setReadTimeout(Duration.ofSeconds(Math.max(1, readTimeoutSec)));
        this.rest = new RestTemplate(f);
    }

    /** 부를 수 없는 까닭 — 부를 수 있으면 null. MOCK 전송 모드에는 커넥터가 없다. */
    public String blocker() {
        if (Boolean.TRUE.equals(mock.get())) {
            return "에이전트 커넥터가 MOCK 모드(비식별할 곳이 없다)";
        }
        if (!StringUtils.hasText(baseUrl.get())) {
            return "에이전트 커넥터 주소가 비었다(agent-connector.base-url)";
        }
        return null;
    }

    /**
     * 한 건 비식별.
     *
     * @throws Wf9Failure DEID — 400 · 422 는 다시 하지 않는다
     */
    public Result deidentify(String recFileId, String execId, JsonNode document) {
        String why = blocker();
        if (why != null) {
            throw Wf9Failure.retry(Wf9Failure.Stage.DEID, why);
        }
        ObjectNode body = om.createObjectNode();
        body.put("recFileId", recFileId);
        body.put("execId", execId);
        body.set("document", document);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setAccept(List.of(MediaType.APPLICATION_JSON));
        String url = baseUrl.get().strip().replaceAll("/+$", "") + path;
        byte[] raw;
        try {
            raw = rest.postForObject(url, new HttpEntity<>(om.writeValueAsBytes(body), h), byte[].class);
        } catch (HttpStatusCodeException e) {
            int s = e.getStatusCode().value();
            String msg = "커넥터 비식별 HTTP " + s + codeOf(e.getResponseBodyAsByteArray());
            if (s == 400 || s == 422) {
                throw Wf9Failure.permanent(Wf9Failure.Stage.DEID, msg);
            }
            throw Wf9Failure.retry(Wf9Failure.Stage.DEID, msg);
        } catch (ResourceAccessException e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            throw Wf9Failure.retry(Wf9Failure.Stage.DEID, "커넥터 비식별 연결 실패 · 시간 초과 — " + c.getClass().getSimpleName());
        } catch (Exception e) {
            throw Wf9Failure.retry(Wf9Failure.Stage.DEID, "커넥터 비식별 호출 실패 — " + e.getClass().getSimpleName());
        }
        JsonNode r;
        try {
            r = raw == null ? null : om.readTree(raw);
        } catch (Exception e) {
            r = null;
        }
        if (r == null || !r.path("document").isContainerNode() || !r.path("mapping").isObject()) {
            throw Wf9Failure.retry(Wf9Failure.Stage.DEID, "커넥터 비식별 응답 모양이 다르다(document · mapping)");
        }
        Map<String, String> mapping = new LinkedHashMap<>();
        r.path("mapping").fields().forEachRemaining(e -> {
            if (e.getValue().isTextual()) {
                mapping.put(e.getKey(), e.getValue().asText());
            }
        });
        return new Result(r.get("document"), mapping);
    }

    /** 규격 본문의 {@code code} 만(예: {@code DEID_RESIDUAL_PII}) — detail · message 는 싣지 않는다. */
    private String codeOf(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        try {
            JsonNode n = om.readTree(new String(body, StandardCharsets.UTF_8));
            String code = n.path("code").asText("");
            return code.matches("[A-Z][A-Z0-9_]{1,63}") ? " " + code : "";
        } catch (Exception e) {
            return "";
        }
    }
}
