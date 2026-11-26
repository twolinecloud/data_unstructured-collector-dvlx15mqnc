package egovframework.unstructured.collector.voice.transfer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import egovframework.unstructured.collector.common.model.SttResult;
import egovframework.unstructured.collector.common.model.VoiceTarget;
import egovframework.unstructured.collector.common.transfer.AgentConnectorClient;
import egovframework.unstructured.collector.common.transfer.AgentConnectorProperties;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 음성 전사 비식별 — STT 를 마친 건마다 에이전트 커넥터 {@code POST /api/v1/pipeline/deident/voice} 를 부른다(T2 DEIDENT).
 *
 * <p><b>돌려받은 텍스트 · 구간만 보낸다.</b> 비식별에 실패한 건은 원문을 보내지 않는다 — 호출자가 그 건을 DEIDENT 실패로 남긴다
 * (전사는 임시 보관에 남아 재처리가 STT 없이 이어 간다).</p>
 *
 * <ul>
 *   <li>{@code agent-connector.voice-deident.enabled}(기본 true) — 꺼 두면 단계 자체를 건너뛴다(이전 동작: 전사 그대로 전송)</li>
 *   <li>{@code agent-connector.voice-deident.mask}(기본 true) — 커넥터의 {@code deidentEnabled}. false 면 커넥터가 단순 전달(BYPASS)하고
 *       T5 만 남긴다</li>
 *   <li>전송 모드가 MOCK 이면 커넥터를 부르지 않고 여기서 주민번호 · 전화번호 숫자만 가린다(시뮬레이터 화면용 — 밖으로 나가는 것 없음)</li>
 * </ul>
 *
 * <p><b>격리 응답(2026-11-26)</b>: 커넥터가 가린 글에서 개인정보가 남은 것을 찾으면 {@code isolated=true} · {@code text=null} 로 돌려준다
 * (T5 는 커넥터가 FAIL · RECONCILE 로 남긴다). 여기서는 {@code [RECONCILE]} 꼬리표를 단 {@link DeidentException} 으로 바꾼다 —
 * 호출자가 그 건을 DEIDENT 실패로 남기고 보내지 않으며, T4 · T2 사유의 오류 구분이 '정합성'(C12 RECONCILE)이 된다
 * ({@code LogCollectorClient.FileProcReq#errStackOf}). 문구에는 건수 · 유형만 싣는다.</p>
 */
@Log4j2
@Component
public class VoiceDeidentClient {

    /** 비식별 결과 — 가린 전문 · 같은 순서로 가린 구간(커넥터가 안 줬으면 null) · 모드 · T5 SEND_ID. */
    public record Result(String text, List<String> segments, String mode, Long sendId, Integer piiItemCnt) {}

    /** 비식별 실패 — 메시지에 전사 내용을 싣지 않는다. */
    public static class DeidentException extends RuntimeException {
        public DeidentException(String message) {
            super(message);
        }

        public DeidentException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final Pattern RRN = Pattern.compile("\\d{6}-?[1-4]\\d{6}");
    private static final Pattern PHONE = Pattern.compile("01[016789]-?\\d{3,4}-?\\d{4}");
    /** 잔존 유형 낱말 — 커넥터가 주는 값이지만 사유 문구(T4)에 그대로 들어가므로 모양을 거른다. */
    private static final Pattern RESIDUAL_TYPE = Pattern.compile("[A-Z][A-Z_]{1,19}");

    private final AgentConnectorClient connector;
    private final AgentConnectorProperties props;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final boolean mask;
    private final String path;
    private final RestTemplate rest;

    public VoiceDeidentClient(AgentConnectorClient connector, AgentConnectorProperties props, ObjectMapper objectMapper,
                              @Value("${agent-connector.voice-deident.enabled:true}") boolean enabled,
                              @Value("${agent-connector.voice-deident.mask:true}") boolean mask,
                              @Value("${agent-connector.voice-deident.path:/api/v1/pipeline/deident/voice}") String path) {
        this.connector = connector;
        this.props = props;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.mask = mask;
        this.path = path;
        JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(500, props.connectTimeoutMs()))).build());
        f.setReadTimeout(Duration.ofMillis(Math.max(1000, props.readTimeoutMs())));
        this.rest = new RestTemplate(f);
        log.info("[Deident] 음성 비식별 단계 {} — mask={} 경로={}", enabled ? "켜짐" : "꺼짐(전사 그대로 전송)", mask, path);
    }

    public boolean enabled() {
        return enabled;
    }

    public String mode() {
        if (!enabled) {
            return "OFF";
        }
        return connector.effectiveMode() == AgentConnectorProperties.Mode.MOCK ? "MOCK" : (mask ? "DEIDENT" : "BYPASS");
    }

    /**
     * 한 건 비식별.
     *
     * @throws DeidentException 커넥터 오류 · 빈 응답 · 주소 미설정 · 격리(잔존 개인정보) — 호출자는 원문을 보내지 않는다
     */
    public Result deident(String execId, VoiceTarget target, String inmatePid, SttResult stt) {
        List<String> segs = stt.hasSegments()
                ? stt.segments().stream().map(SttResult.Segment::text).toList() : null;
        if (connector.effectiveMode() == AgentConnectorProperties.Mode.MOCK) {
            return mockMask(stt.text(), segs);
        }
        if (!StringUtils.hasText(props.baseUrl())) {
            throw new DeidentException("에이전트 커넥터 주소가 비어 있다 — agent-connector.base-url(AGENT_CONNECTOR_BASE_URL)");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("execId", execId);
        body.put("kind", target.kind().name());
        body.put("recFileId", target.idempotencyKey());
        body.put("inmatePid", inmatePid);
        body.put("fileName", target.srcFileName());
        body.put("text", stt.text() == null ? "" : stt.text());
        body.put("deidentEnabled", mask);
        if (segs != null) {
            body.put("segments", segs);
        }
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setAccept(List.of(MediaType.APPLICATION_JSON));
        String url = props.baseUrl().trim().replaceAll("/+$", "") + path;
        JsonNode r;
        try {
            r = rest.postForObject(url, new HttpEntity<>(objectMapper.writeValueAsString(body), h), JsonNode.class);
        } catch (Exception e) {
            // 응답 본문(전사가 섞일 수 있음)을 싣지 않는다 — 예외 종류와 상태만
            String why = e instanceof org.springframework.web.client.HttpStatusCodeException hs
                    ? "HTTP " + hs.getStatusCode().value() : e.getClass().getSimpleName();
            throw new DeidentException("에이전트 커넥터 비식별 호출 실패 — " + why, e);
        }
        return readResponse(r, mask);
    }

    /**
     * 커넥터 응답을 결과로 — 격리면 {@code [RECONCILE]} 예외, 본문이 없으면 '비어 있음' 예외.
     *
     * <p>격리 검사를 본문 검사보다 먼저 한다. 둘 다 본문이 없지만 사유가 다르다 — 화면 [실패 로그]가 '응답이 비어 있음'
     * 이 아니라 잔존 격리로 읽혀야 담당자가 무엇을 볼지 안다.</p>
     */
    public static Result readResponse(JsonNode r, boolean mask) {
        if (r != null && r.path("isolated").asBoolean(false)) {
            throw new DeidentException("[RECONCILE] 비식별 후 잔존 개인정보 " + r.path("residualCount").asInt(0)
                    + "건(" + joinTypes(r.path("residualTypes")) + ") — 격리 · 전송 제외");
        }
        if (r == null || !r.hasNonNull("text")) {
            throw new DeidentException("에이전트 커넥터 비식별 응답이 비어 있음");
        }
        List<String> masked = null;
        if (r.path("segments").isArray()) {
            masked = new ArrayList<>();
            for (JsonNode n : r.path("segments")) {
                masked.add(n.isNull() ? null : n.asText());
            }
        }
        return new Result(r.get("text").asText(), masked, r.path("mode").asText(mask ? "DEIDENT" : "BYPASS"),
                r.hasNonNull("sendId") ? r.get("sendId").asLong() : null,
                r.hasNonNull("piiItemCnt") ? r.get("piiItemCnt").asInt() : null);
    }

    /** 잔존 유형을 쉼표로 — 유형 모양(대문자 · 밑줄)만, 최대 6개. 없으면 '유형 미상'. */
    private static String joinTypes(JsonNode types) {
        List<String> out = new ArrayList<>();
        if (types != null && types.isArray()) {
            for (JsonNode t : types) {
                String s = t.asText("");
                if (RESIDUAL_TYPE.matcher(s).matches() && !out.contains(s) && out.size() < 6) {
                    out.add(s);
                }
            }
        }
        return out.isEmpty() ? "유형 미상" : String.join(",", out);
    }

    /** MOCK — 주민번호 · 전화번호 숫자만 별표로(길이 유지). 외부 호출 없음. */
    static Result mockMask(String text, List<String> segs) {
        int[] found = {0};
        String out = maskDigits(text, found);
        List<String> outSegs = null;
        if (segs != null) {
            outSegs = new ArrayList<>(segs.size());
            for (String s : segs) {
                outSegs.add(maskDigits(s, new int[1]));
            }
        }
        return new Result(out, outSegs, "MOCK", null, found[0]);
    }

    private static String maskDigits(String s, int[] count) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        String out = s;
        for (Pattern p : List.of(RRN, PHONE)) {
            Matcher m = p.matcher(out);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                count[0]++;
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group().replaceAll("\\d", "*")));
            }
            m.appendTail(sb);
            out = sb.toString();
        }
        return out;
    }
}
