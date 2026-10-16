package egovframework.unstructured.collector.common.transfer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 에이전트 커넥터 <b>전송 보류 장부</b> — 클라우드 전송이 커넥터가 들고 있는 배치를 보낼 때 쓴다.
 *
 * <p>커넥터 전송 가드(T7 수동 확인 · 전송 중지 · 설정 없음)는 받은 음성 전사를 보내지 않고 장부에 올려 둔다(T2 SEND = WAIT).
 * 관리자가 [클라우드 전송]을 누르면 커넥터의 관리자 트리거가 장부의 배치를 보낸다 — 이 클라이언트는 그 두 API 만 부른다.</p>
 * <ul>
 *   <li>장부 목록: {@code GET {base}/api/v1/transmit/holds}</li>
 *   <li>전송 트리거: {@code POST {base}/api/v1/transmit/holds/{execId}/release}(헤더 {@code X-Requested-By}) —
 *       성공 · 실패 모두 200(본문 {@code success} 로 구분) · 404 장부에 없음 · 409 지금 전송 중</li>
 * </ul>
 *
 * <p>전송 트리거는 보낼 때까지 응답을 잡고 있어(학습셋 drop · 제논 수거) 읽기 제한을 따로 길게 둔다.
 * 수집기 내장 수신기(MOCK)일 때는 장부가 없다 — {@link #holds()} 가 null 이다.</p>
 */
@Log4j2
@Component
public class TransmitHoldClient {

    static final String HOLDS_PATH = "/api/v1/transmit/holds";

    private final AgentConnectorProperties props;
    private final RestTemplate restTemplate;

    /** 전송 트리거 결과. {@code httpStatus} 0 은 통신 실패, {@code body} 는 커넥터 응답(없으면 null). */
    public record Release(int httpStatus, boolean success, String stepStsCd, String message, JsonNode body) {

        /** 커넥터가 받아서 보냈다(성공) — 실패여도 장부는 남아 다시 누를 수 있다. */
        public boolean sent() {
            return httpStatus == 200 && success;
        }
    }

    public TransmitHoldClient(AgentConnectorProperties props, ObjectMapper objectMapper,
                              @Value("${agent-connector.release-read-timeout-sec:600}") int releaseReadTimeoutSec) {
        this.props = props;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(Math.max(1, props.connectTimeoutMs()))).build());
        factory.setReadTimeout(Duration.ofSeconds(Math.max(1, releaseReadTimeoutSec)));
        RestTemplate rt = new RestTemplate(factory);
        rt.getMessageConverters().removeIf(MappingJackson2HttpMessageConverter.class::isInstance);
        rt.getMessageConverters().add(new MappingJackson2HttpMessageConverter(objectMapper));
        this.restTemplate = rt;
    }

    /** 커넥터 장부를 부를 수 있는가 — REST 모드이고 주소가 있을 때. */
    public boolean isEnabled() {
        return props.mode() == AgentConnectorProperties.Mode.REST && StringUtils.hasText(props.baseUrl());
    }

    /**
     * 장부에 올라 있는 배치 ID — 오래된 것부터.
     *
     * @return 실행 ID 집합. MOCK · 주소 없음 · 호출 실패면 null(장부를 모름 — 호출 측은 장부 배치를 보내지 않는다)
     */
    public Set<String> holds() {
        if (!isEnabled()) {
            return null;
        }
        try {
            ResponseEntity<JsonNode> res = restTemplate.exchange(uri(HOLDS_PATH), HttpMethod.GET,
                    new HttpEntity<>(headers(null)), JsonNode.class);
            JsonNode body = res.getBody();
            if (body == null || !body.isArray()) {
                log.warn("[Hold] 장부 응답이 목록이 아니다 — {}", body);
                return null;
            }
            Set<String> ids = new LinkedHashSet<>();
            body.forEach(h -> {
                String id = h.path("execId").asText("");
                if (!id.isBlank()) {
                    ids.add(id);
                }
            });
            return ids;
        } catch (RuntimeException e) {
            log.warn("[Hold] 장부 조회 실패 — {}", e.getMessage());
            return null;
        }
    }

    /**
     * 장부의 배치를 지금 보낸다. 커넥터가 T2 SEND 를 RUNNING → SUCCESS/FAIL 로 마감하고, 성공이면 장부를 지운다.
     *
     * @param requestedBy 요청자(커넥터 감사 로그 · T2 기록) — 관리자 ID
     */
    public Release release(String execId, String requestedBy) {
        if (!isEnabled()) {
            return new Release(0, false, null, "커넥터 미연동(MOCK 또는 주소 없음)", null);
        }
        try {
            ResponseEntity<JsonNode> res = restTemplate.exchange(uri(HOLDS_PATH + "/" + execId.trim() + "/release"),
                    HttpMethod.POST, new HttpEntity<>(headers(requestedBy)), JsonNode.class);
            JsonNode b = res.getBody();
            return new Release(res.getStatusCode().value(), b != null && b.path("success").asBoolean(false),
                    b == null ? null : b.path("stepStsCd").asText(null), b == null ? null : b.path("message").asText(null), b);
        } catch (RestClientResponseException e) {
            log.warn("[Hold] 전송 트리거 거절 {} — execId={} {}", e.getStatusCode().value(), execId, e.getResponseBodyAsString());
            return new Release(e.getStatusCode().value(), false, null, e.getResponseBodyAsString(), null);
        } catch (RuntimeException e) {
            log.warn("[Hold] 전송 트리거 실패 — execId={} {}", execId, e.getMessage());
            return new Release(0, false, null, e.getMessage(), null);
        }
    }

    private URI uri(String path) {
        String base = props.baseUrl().trim();
        return URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + path);
    }

    private static HttpHeaders headers(String requestedBy) {
        HttpHeaders h = new HttpHeaders();
        h.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (StringUtils.hasText(requestedBy)) {
            h.set("X-Requested-By", requestedBy.trim());
        }
        return h;
    }
}
