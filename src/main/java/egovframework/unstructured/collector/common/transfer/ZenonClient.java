package egovframework.unstructured.collector.common.transfer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 제논(Zenon) AI 전송 — 파이프라인 3단계 {@code SEND}.
 *
 * <p><b>REST</b>: {@code POST {base-url}{receive-path}} · {@code multipart/form-data}</p>
 * <ul>
 *   <li>{@code file} — 보낼 파일(음성은 STT 전사 JSON, 이미지는 복호화한 사진)</li>
 *   <li>{@code metadata} — JSON 문자열: {@code exec_id} · {@code inmate_no} · {@code type}(VOICE|IMAGE) · {@code file_name} 과 부가 항목</li>
 * </ul>
 * <p>응답 {@code 200} 이고 {@code code == "SUCCESS"} 일 때만 성공이다. 그 밖(4xx·5xx·연결 실패·다른 code)은
 * {@link ZenonSendException} — 그 건만 SEND 실패로 남고, 보존된 전사로 {@code FROM_SEND} 재처리가 이어 간다.</p>
 *
 * <p><b>MOCK</b>(개발계 기본): 네트워크 없이 수신증만 만든다. 수신증은 REST 든 MOCK 이든 최근 {@code keep-receipts}
 * 개를 메모리에 남긴다 — 시뮬레이터 검증 화면이 "이 배치가 제논에 무엇을 보냈는가" 를 보여 주는 근거다.</p>
 */
@Log4j2
@Component
public class ZenonClient {

    /** 수신증 — 제논 응답(REST) 또는 수집기가 만든 것(MOCK). */
    public record Receipt(String code, String execId, String type, String inmateNo, String fileName,
                          long fileSizeBytes, String receivedAt, String mode, String endpoint,
                          Map<String, Object> metadata, String preview) {

        /** T4·배치 결과에 남길 위치 표기 — 디스크 경로 대신 "어디로 보냈는가". */
        public String location() {
            return "zenon:" + (mode.equals("MOCK") ? "mock" : endpoint) + "/" + fileName;
        }
    }

    /** 보낼 문서. */
    public record Document(String type, String execId, String inmateNo, String fileName, byte[] content,
                           String contentType, Map<String, Object> extra, String preview) {}

    /** 제논 전송 실패 — 그 건만 SEND 실패다. */
    public static class ZenonSendException extends RuntimeException {
        public ZenonSendException(String message) {
            super(message);
        }

        public ZenonSendException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final ZenonProperties props;
    private final ObjectMapper objectMapper;
    private final RestTemplate rest;
    /** 더미 키 표식(SF) 장애 — MOCK 모드에서 이 건의 전송을 한 번만 거부한다(REST 면 tools/zenon-mock 서버가 같은 표식에 503). */
    private final egovframework.unstructured.collector.mock.ScenarioFaults scenarioFaults;
    private final Deque<Receipt> receipts = new ConcurrentLinkedDeque<>();

    @Autowired
    public ZenonClient(ZenonProperties props, ObjectMapper objectMapper,
                       egovframework.unstructured.collector.mock.ScenarioFaults scenarioFaults) {
        this(props, objectMapper, restTemplate(props), scenarioFaults);
    }

    /** 시험용 — RestTemplate 을 넘겨받는다(MockRestServiceServer). 키 표식 장애는 쓰지 않는다. */
    ZenonClient(ZenonProperties props, ObjectMapper objectMapper, RestTemplate rest) {
        this(props, objectMapper, rest, egovframework.unstructured.collector.mock.ScenarioFaults.inactive());
    }

    ZenonClient(ZenonProperties props, ObjectMapper objectMapper, RestTemplate rest,
                egovframework.unstructured.collector.mock.ScenarioFaults scenarioFaults) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.rest = rest;
        this.scenarioFaults = scenarioFaults;
        log.info("[Zenon] 전송 모드 {} · {}", props.mode(), endpoint());
    }

    private static RestTemplate restTemplate(ZenonProperties p) {
        JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(500, p.connectTimeoutMs()))).build());
        f.setReadTimeout(Duration.ofMillis(Math.max(1_000, p.readTimeoutMs())));
        return new RestTemplate(f);
    }

    public String mode() {
        return props.mode().name();
    }

    /** 메모리에 남기는 수신증 상한 — 이보다 많이 보낸 배치는 수신증으로 건수를 대조할 수 없다. */
    public int keepLimit() {
        return props.keepReceipts();
    }

    /** 수신 API 전체 주소(REST) — MOCK 이면 표기용 설명. */
    public String endpoint() {
        if (props.mode() == ZenonProperties.Mode.MOCK) {
            return "MOCK(수집기 내장 — 네트워크 없음)";
        }
        return (StringUtils.hasText(props.baseUrl()) ? props.baseUrl().replaceAll("/+$", "") : "(base-url 비어 있음)")
                + props.receivePath();
    }

    /**
     * 한 건을 보낸다.
     *
     * @throws ZenonSendException 전송 실패(연결 · 4xx · 5xx · code≠SUCCESS)
     */
    public Receipt send(Document d) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("exec_id", d.execId());
        meta.put("inmate_no", d.inmateNo());
        meta.put("type", d.type());
        meta.put("file_name", d.fileName());
        if (d.extra() != null) {
            meta.putAll(d.extra());
        }
        Receipt r = props.mode() == ZenonProperties.Mode.MOCK ? mock(d, meta) : rest(d, meta);
        keep(r);
        log.info("[Zenon] 전송 {} — {} {} ({} bytes) → {}", r.code(), d.type(), d.fileName(), d.content().length,
                props.mode() == ZenonProperties.Mode.MOCK ? "MOCK" : endpoint());
        return r;
    }

    private Receipt mock(Document d, Map<String, Object> meta) {
        // 더미 시나리오 SEND_FAIL — 데이터 성질이 아니라 전송 실패라 수신 쪽이 거부하게 한다. 표식 건만 · 한 번만.
        Object key = d.extra() == null ? null : d.extra().get("idempotency_key");
        if (scenarioFaults.failOnce(egovframework.unstructured.collector.mock.FailureScenario.SEND_FAIL,
                key == null ? null : key.toString())) {
            throw new ZenonSendException("제논 HTTP 503 — (MOCK) 수신 거부 [더미 시나리오 SEND_FAIL · 1회]");
        }
        return new Receipt("SUCCESS", d.execId(), d.type(), d.inmateNo(), d.fileName(), d.content().length,
                LocalDateTime.now().withNano(0).toString(), "MOCK", endpoint(), meta, d.preview());
    }

    private Receipt rest(Document d, Map<String, Object> meta) {
        if (!StringUtils.hasText(props.baseUrl())) {
            throw new ZenonSendException("제논 주소가 비어 있다 — zenon.base-url(ZENON_BASE_URL)");
        }
        String metaJson;
        try {
            metaJson = objectMapper.writeValueAsString(meta);
        } catch (JsonProcessingException e) {
            throw new ZenonSendException("메타데이터 직렬화 실패 — " + e.getMessage(), e);
        }
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.parseMediaType(
                StringUtils.hasText(d.contentType()) ? d.contentType() : MediaType.APPLICATION_OCTET_STREAM_VALUE));
        ByteArrayResource file = new ByteArrayResource(d.content()) {
            @Override
            public String getFilename() {
                return d.fileName();
            }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(file, fileHeaders));
        body.add("metadata", metaJson);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        String url = endpoint();
        try {
            ResponseEntity<String> res = rest.postForEntity(url, new HttpEntity<>(body, headers), String.class);
            JsonNode n = res.getBody() == null ? objectMapper.nullNode() : objectMapper.readTree(res.getBody());
            String code = n.path("code").asText("");
            if (!"SUCCESS".equals(code)) {
                throw new ZenonSendException("제논 응답 code=" + (code.isEmpty() ? "(없음)" : code) + " — " + shorten(res.getBody()));
            }
            return new Receipt(code, n.path("exec_id").asText(d.execId()), d.type(), d.inmateNo(),
                    n.path("file_name").asText(d.fileName()), n.path("file_size_bytes").asLong(d.content().length),
                    n.path("received_at").asText(LocalDateTime.now().withNano(0).toString()), "REST", url, meta,
                    d.preview());
        } catch (RestClientResponseException e) {
            throw new ZenonSendException("제논 HTTP " + e.getStatusCode().value() + " — " + shorten(e.getResponseBodyAsString()), e);
        } catch (ZenonSendException e) {
            throw e;
        } catch (Exception e) {
            throw new ZenonSendException("제논 호출 실패 — " + e.getClass().getSimpleName() + ": " + shorten(e.getMessage()), e);
        }
    }

    private void keep(Receipt r) {
        receipts.addFirst(r);
        int max = Math.max(10, props.keepReceipts());
        while (receipts.size() > max) {
            receipts.pollLast();
        }
    }

    /** 이 배치가 보낸 수신증(최근 것부터) — 메모리에 남아 있는 만큼. */
    public List<Receipt> receipts(String execId) {
        List<Receipt> out = new ArrayList<>();
        for (Receipt r : receipts) {
            if (execId == null || execId.equals(r.execId())) {
                out.add(r);
            }
        }
        return out;
    }

    /** 시험 배치(EXEC_ID 에 TST)의 수신증을 지운다 — [시뮬레이션 데이터 초기화]. */
    public int clearTestReceipts() {
        int before = receipts.size();
        receipts.removeIf(r -> r.execId() != null && r.execId().toUpperCase().contains("TST"));
        return before - receipts.size();
    }

    /** 상태 — 화면 · 헬스용. */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", mode());
        m.put("endpoint", endpoint());
        m.put("receiptsKept", receipts.size());
        return m;
    }

    private static String shorten(String s) {
        if (s == null) {
            return "(본문 없음)";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= 200 ? t : t.substring(0, 200) + "…";
    }
}
