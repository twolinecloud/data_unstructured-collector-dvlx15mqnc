package egovframework.unstructured.collector.common.broker;

import com.fasterxml.jackson.databind.JsonNode;
import egovframework.unstructured.collector.common.config.VoiceProperties;
import egovframework.unstructured.collector.common.model.VoiceTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * XVARM 브로커 REST 호출 — <b>운영 경로</b>.
 *
 * <p><b>API 규격은 우리 측 제안이다</b>(계획서 6.2). XVARM 의 실제 호출 인터페이스가
 * 확정되면(Q3) 브로커와 함께 맞춰야 한다. 그래도 배치·하류는 손대지 않는다.</p>
 *
 * <pre>
 *   POST /api/v1/xvarm/extract           → 202 {requestId, status, expectedPath}
 *   GET  /api/v1/xvarm/extract/{id}      → 200 {status: DONE|RUNNING|FAILED, filePath, fileSize}
 * </pre>
 *
 * <p><b>멱등</b>: {@code requestId} 를 우리가 만들어 보낸다({@code VOC-{execId}-{대상키}}). 같은 값으로
 * 재요청하면 브로커가 중복 추출하지 않고 기존 상태를 돌려준다. 키에 EXEC_ID 가 들어 있어 <b>재처리 배치는
 * 새로 추출받고</b>, 한 배치 안에서는 멱등 표식이 같은 대상을 두 번 요청하지 않게 막는다.</p>
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class RestXvarmBrokerClient implements XvarmBrokerClient {

    private final VoiceProperties props;
    private final egovframework.unstructured.collector.common.config.VoiceModeState modeState;
    private final RestTemplate voiceRestTemplate;
    private final egovframework.unstructured.collector.common.sync.EsbFileNamingPolicy namingPolicy;

    /**
     * 브로커에 붙어 상태를 물어본다 — <b>진단 전용</b>. 배치 경로에서는 쓰지 않는다.
     *
     * <p>여기서 확인하려는 것은 연결 여부보다 <b>출력 디렉터리가 우리 수신 폴더와 같은가</b>다.
     * 두 경로가 어긋나면 브로커는 "추출 완료"를 돌려주는데 우리는 빈 폴더를 보며 수신 대기
     * 타임아웃이 난다. 그 실패에는 원인을 알려 주는 신호가 전혀 없어서, 배치를 돌리기 전에
     * 눈으로 대조할 수단이 필요하다.</p>
     *
     * @return 브로커가 돌려준 상태. 붙지 못하면 {@code error} 가 채워진다
     */
    public Map<String, Object> probe() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        String base = baseUrl();
        out.put("baseUrl", base);
        if (!StringUtils.hasText(base)) {
            out.put("reachable", false);
            out.put("error", "브로커 주소가 비어 있다 — 시뮬레이터에서 주소를 고르거나 VOICE_BROKER_BASE_URL 을 주십시오");
            return out;
        }
        try {
            JsonNode s = get(base + "/api/v1/xvarm/status");
            out.put("reachable", true);
            out.put("adapter", s.path("adapter").asText(null));
            out.put("brokerOutputDir", s.path("outputDir").asText(null));
            out.put("totalJobs", s.path("totalJobs").asInt(0));
        } catch (Exception e) {
            out.put("reachable", false);
            out.put("error", e.getMessage());
        }
        return out;
    }

    @Override
    public ExtractResult extract(VoiceTarget target) {
        return extract(target, null);
    }

    @Override
    public ExtractResult extract(VoiceTarget target, String execId) {
        String base = requireBaseUrl();
        // 배치마다 다른 키 — 재처리 배치가 브로커의 멱등 캐시("이미 DONE")에 걸리지 않게 한다.
        String requestId = XvarmBrokerClient.requestIdOf(target, execId);

        // 원하는 파일명을 함께 보낸다. ESB 가 업무 파일명을 그대로 옮기는 규약(ORIGINAL)일 때
        // 수신 측에서 바로 찾을 수 있다. 브로커가 다른 이름으로 만들면 응답의 filePath 가
        // 기준이 되므로, 이건 요청일 뿐 강제는 아니다.
        String wantName = namingPolicy.expectedFileName(target, props.sync().namingPolicy());

        JsonNode accepted = post(base + "/api/v1/xvarm/extract", Map.of(
                "docId", nullSafe(target.docId()),
                "fileKey", nullSafe(target.fileKey()),
                "requestId", requestId,
                "fileName", nullSafe(wantName)));
        log.info("[Broker:REST] 추출 요청 — requestId={} status={}", requestId,
                accepted == null ? "(응답없음)" : accepted.path("status").asText(""));

        return poll(base, requestId);
    }

    @Override
    public ExtractResult extractFile(FileRequest req) {
        String base = requireBaseUrl();
        JsonNode accepted = post(base + "/api/v1/xvarm/extract", Map.of(
                "docId", nullSafe(req.docId()),
                "fileKey", nullSafe(req.fileKey()),
                "requestId", req.requestId(),
                "fileName", nullSafe(req.fileName())));
        log.info("[Broker:REST] 파일 추출 요청 — requestId={} status={}", req.requestId(),
                accepted == null ? "(응답없음)" : accepted.path("status").asText(""));
        return poll(base, req.requestId());
    }

    /** 완료될 때까지 상태를 묻는다. 타임아웃이면 예외 — 조용히 넘기면 없는 파일을 기다리게 된다. */
    private ExtractResult poll(String base, String requestId) {
        long intervalMs = props.broker().pollIntervalMs();
        long deadline = System.currentTimeMillis() + props.broker().pollTimeoutSec() * 1000L;

        while (System.currentTimeMillis() < deadline) {
            JsonNode s = get(base + "/api/v1/xvarm/extract/" + requestId);
            String status = (s == null) ? "" : s.path("status").asText("");
            if ("DONE".equalsIgnoreCase(status)) {
                return new ExtractResult(requestId, s.path("filePath").asText(null),
                        s.path("fileSize").asLong(0L));
            }
            if ("FAILED".equalsIgnoreCase(status)) {
                throw new IllegalStateException("XVARM 추출 실패 — requestId=" + requestId
                        + ", errorCode=" + (s == null ? "" : s.path("errorCode").asText("")));
            }
            sleep(intervalMs);
        }
        throw new IllegalStateException("XVARM 추출 대기 타임아웃 — requestId=" + requestId
                + " (" + props.broker().pollTimeoutSec() + "초)");
    }

    @Override
    public String mode() {
        return "REST";
    }

    /**
     * 지금 쓸 브로커 주소. <b>설정이 아니라 런타임 상태에서 읽는다</b> —
     * 시뮬레이터에서 주소를 바꾸면 재기동 없이 바로 반영되어야 하기 때문이다.
     */
    private String baseUrl() {
        return modeState.brokerBaseUrl();
    }

    private String requireBaseUrl() {
        String base = baseUrl();
        if (!StringUtils.hasText(base)) {
            throw new IllegalStateException(
                    "브로커 주소가 비어 있다 — REST 모드에서는 필수다. "
                            + "시뮬레이터의 [XVARM 브로커] 단계에서 주소를 고르거나, "
                            + "VOICE_BROKER_BASE_URL 환경변수를 주십시오.");
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private JsonNode post(String url, Object body) {
        return exchange(HttpMethod.POST, url, body);
    }

    private JsonNode get(String url) {
        return exchange(HttpMethod.GET, url, null);
    }

    private JsonNode exchange(HttpMethod method, String url, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        try {
            ResponseEntity<JsonNode> res =
                    voiceRestTemplate.exchange(url, method, new HttpEntity<>(body, headers), JsonNode.class);
            return res.getBody();
        } catch (ResourceAccessException e) {
            // 붙지 못한 것 — 스프링 기본 메시지는 'I/O error on POST request for "…": null' 이라
            // 무엇이 잘못됐는지 말해 주지 않는다. 접견 배치가 전건 실패하면 이 한 줄이 T4 에 그대로
            // 남으므로, 읽는 사람이 다음에 무엇을 볼지 알 수 있게 바꿔 적는다.
            throw new IllegalStateException(unreachableMessage(url, e), e);
        }
    }

    /** 브로커에 닿지 못한 이유를 사람이 읽을 수 있게 — 원인 예외의 클래스명까지 살려 둔다. */
    private static String unreachableMessage(String url, ResourceAccessException e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        String detail = cause.getMessage();
        if (!StringUtils.hasText(detail)) {
            detail = cause.getClass().getSimpleName();
        }
        String hint = cause instanceof java.net.UnknownHostException
                ? "주소를 찾지 못했습니다 — 서비스명·네임스페이스를 확인하십시오"
                : "브로커가 응답하지 않습니다 — 파드가 떠 있는지(K8s Service 에 엔드포인트가 붙었는지) 확인하십시오";
        return "XVARM 브로커에 붙지 못했습니다 (%s) — %s. [%s]".formatted(url, hint, detail);
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("추출 대기 중 인터럽트", e);
        }
    }
}
