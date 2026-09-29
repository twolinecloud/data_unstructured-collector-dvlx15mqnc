package egovframework.unstructured.collector.voice.transfer;

import egovframework.unstructured.collector.common.config.DeployEnvPreset;
import egovframework.unstructured.collector.common.model.VoiceKind;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 에이전트 커넥터 이관 — STT 산출물을 넘긴다.
 *
 * <p><b>1차 저장은 우리 몫, 이관은 커넥터 몫이다.</b> 배치는 STT 결과를
 * {@code {ROOT}/xenon/{meet|phone}/{execId}/} 에 먼저 남기고(여기까지가 이 서비스의 완결),
 * 그 뒤 커넥터의 이관 API 를 부른다. 이관이 실패해도 <b>배치는 성공으로 둔다</b> —
 * 우리 구간의 산출물은 이미 디스크에 있고, 못 넘긴 것은 폴더를 보고 다시 넘기면 된다.
 * 이관 실패로 STT 를 다시 돌리는 것은 낭비다.</p>
 *
 * <p><b>비식별은 켜지 않는다</b>({@code deidentificationEnabled=false}). 적용하지 않기로
 * 결정되어 커넥터가 ADID/AIR 를 호출하지 않고 원본 그대로 적재한다.</p>
 */
@Log4j2
@Component
public class AgentConnectorClient {

    /** 이관 결과 한 장 — 화면·로그가 그대로 읽는다. */
    public record TransferResult(boolean enabled, boolean success, int sent, String baseUrl,
                                 String message, String dir) {

        static TransferResult off(String why) {
            return new TransferResult(false, true, 0, null, why, null);
        }
    }

    private final RestTemplate rest;
    private final DeployEnvPreset env;

    @Value("${agent-connector.enabled:true}")
    private boolean enabled;

    /** 비면 환경 프리셋 — 로컬 {@code http://localhost:8080} · K8s 커넥터 서비스명. */
    @Value("${agent-connector.base-url:}")
    private String baseUrlConfigured;

    @Value("${agent-connector.deidentification-enabled:false}")
    private boolean deidentificationEnabled;

    public AgentConnectorClient(RestTemplate voiceRestTemplate, DeployEnvPreset env) {
        this.rest = voiceRestTemplate;
        this.env = env;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 비식별 기본값 — 요청이 {@code deidentEnabled} 를 비웠을 때(스케줄 배치 등). 설정
     * {@code agent-connector.deidentification-enabled}(기본 false = 단순 전달).
     */
    public boolean defaultDeidentEnabled() {
        return deidentificationEnabled;
    }

    /**
     * 비식별 결과 한 건.
     *
     * @param mode          DEIDENT(비식별 수행) · BYPASS(단순 전달)
     * @param displayStatus 화면 표기 — DEIDENT_SUCCESS · SEND
     * @param sendId        커넥터가 남긴 T5 SEND_ID — 이관 적재가 전송 상태를 닫을 때 쓴다. 없으면 null
     * @param t5Logged      T5 가 남았는가 — 커넥터 비활성·구버전이면 false
     */
    public record DeidentResult(String text, String mode, String displayStatus, String solutionCd,
                                Long sendId, boolean t5Logged, Integer piiItemCnt, String message) {

        static DeidentResult localBypass(String text, String why) {
            return new DeidentResult(text, "BYPASS", "SEND", "BYPASS", null, false, null, why);
        }
    }

    /**
     * 비식별 단계 — 한 건의 전사 텍스트를 커넥터에 넘겨 {@code deidentEnabled} 에 따라 비식별하거나 그대로 받는다.
     *
     * <ul>
     *   <li>커넥터가 꺼져 있으면({@code agent-connector.enabled=false}) 부르지 않고 <b>여기서 단순 전달</b>한다 —
     *       비식별을 켜고 불렀다면 실패다(할 수 없는 일을 했다고 적지 않는다). T5 는 남지 않는다</li>
     *   <li>커넥터가 이 API 를 모르면(404 · 구버전) {@code deidentEnabled=false} 일 때만 여기서 단순 전달한다 —
     *       배포 순서(수집기 먼저)에 개발계 배치가 통째로 멈추지 않게. T5 는 남지 않는다</li>
     *   <li>그 밖의 실패(연결 안 됨·5xx)는 예외 — 그 건은 DEIDENT 실패로 T4 에 남고, 보존된 전사(stt_temp)로
     *       재처리({@code FROM_DEIDENT})가 이어 간다</li>
     * </ul>
     *
     * @param recFileId T4 와 같은 파일 식별자 — T5 가 파일마다 한 행이 되는 키
     */
    public DeidentResult deident(String execId, VoiceKind kind, String recFileId, String inmatePid, String fileName,
                                 String text, boolean deidentEnabled) {
        if (!enabled) {
            if (deidentEnabled) {
                throw new IllegalStateException("비식별 커넥터 비활성(agent-connector.enabled=false) — 비식별을 수행할 수 없습니다");
            }
            return DeidentResult.localBypass(text, "커넥터 비활성 — 수집기에서 단순 전달(T5 미적재)");
        }
        String base = baseUrl();
        if (!StringUtils.hasText(base)) {
            throw new IllegalStateException("비식별 커넥터 주소가 비어 있습니다");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("execId", execId);
        body.put("kind", kind.name());
        body.put("recFileId", recFileId);
        body.put("inmatePid", inmatePid);
        body.put("fileName", fileName);
        body.put("text", text);
        body.put("deidentEnabled", deidentEnabled);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        String url = base.replaceAll("/+$", "") + "/api/v1/pipeline/deident/voice";
        try {
            JsonNode b = rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class).getBody();
            if (b == null || !b.hasNonNull("text")) {
                throw new IllegalStateException("비식별 커넥터 응답에 text 가 없습니다");
            }
            return new DeidentResult(b.path("text").asText(), b.path("mode").asText(deidentEnabled ? "DEIDENT" : "BYPASS"),
                    b.path("displayStatus").asText(deidentEnabled ? "DEIDENT_SUCCESS" : "SEND"),
                    b.path("solutionCd").asText(null),
                    b.hasNonNull("sendId") ? b.path("sendId").asLong() : null,
                    b.path("t5Logged").asBoolean(false),
                    b.hasNonNull("piiItemCnt") ? b.path("piiItemCnt").asInt() : null,
                    b.path("message").asText(""));
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            if (deidentEnabled) {
                throw new IllegalStateException("비식별 커넥터에 음성 비식별 API 가 없습니다(구버전) — " + base);
            }
            log.warn("[Deident] 커넥터에 음성 비식별 API 가 없다(구버전) — 수집기에서 단순 전달, T5 미적재 ({})", base);
            return DeidentResult.localBypass(text, "커넥터 구버전 — 수집기에서 단순 전달(T5 미적재)");
        }
    }

    public String baseUrl() {
        return StringUtils.hasText(baseUrlConfigured) ? baseUrlConfigured.trim() : env.agentConnectorBaseUrl();
    }

    /**
     * STT 산출물을 커넥터로 넘긴다.
     *
     * @param execId  이 배치의 실행 ID — 커넥터가 파일명 접두로 쓴다
     * @param outputs 넘길 산출물(종류 · 파일명 · 본문)
     */
    public TransferResult send(String execId, List<Output> outputs) {
        return send(execId, outputs, deidentificationEnabled);
    }

    /**
     * 이관 — 1차 저장을 마친 산출물을 넘긴다. 비식별은 이미 건마다 끝났으므로 커넥터는 받은 그대로 적재하고,
     * {@code sendId} 가 있는 건은 그 T5 행의 전송 상태를 닫는다.
     */
    public TransferResult send(String execId, List<Output> outputs, boolean deidentEnabled) {
        if (!enabled) {
            return TransferResult.off("이관 비활성(agent-connector.enabled=false)");
        }
        if (outputs == null || outputs.isEmpty()) {
            return TransferResult.off("넘길 산출물이 없습니다");
        }
        String base = baseUrl();
        if (!StringUtils.hasText(base)) {
            return new TransferResult(true, false, 0, null, "커넥터 주소가 비어 있습니다", null);
        }
        String url = base.replaceAll("/+$", "") + "/api/v1/pipeline/ingest/voice"
                + "?deidentificationEnabled=" + deidentEnabled;

        List<Map<String, Object>> files = new ArrayList<>(outputs.size());
        for (Output o : outputs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", o.kind().name());
            m.put("fileName", o.fileName());
            m.put("text", o.text());
            if (o.sendId() != null) {
                m.put("sendId", o.sendId());   // 비식별 단계가 남긴 T5 — 적재되면 전송 SUCCESS 로 닫힌다
            }
            files.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("execId", execId);
        body.put("source", "unstructured-collector");   // 커넥터는 로그에만 쓴다
        body.put("files", files);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        try {
            ResponseEntity<JsonNode> res =
                    rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
            JsonNode b = res.getBody();
            int stored = b == null ? 0 : b.path("stored").asInt(0);
            boolean ok = b != null && b.path("success").asBoolean(false);
            String dir = b == null ? null : b.path("dirs").path("meet").asText(null);
            String msg = b == null ? "응답 본문 없음" : b.path("message").asText("");
            log.info("[Transfer] 커넥터 이관 — execId={} {}건 → {} ({})", execId, stored, base, msg);
            return new TransferResult(true, ok, stored, base, msg, dir);
        } catch (ResourceAccessException e) {
            // 붙지 못한 것 — 배치를 실패시키지 않는다. 산출물은 우리 폴더에 그대로 있다.
            String why = e.getCause() == null || e.getCause().getMessage() == null
                    ? e.getClass().getSimpleName() : e.getCause().getMessage();
            log.warn("[Transfer] 커넥터에 붙지 못했습니다 — {} ({}) · 산출물은 1차 저장 폴더에 남아 있습니다", base, why);
            return new TransferResult(true, false, 0, base,
                    "커넥터에 붙지 못했습니다 — 파드가 떠 있는지 확인하십시오. [" + why + "]", null);
        } catch (Exception e) {
            log.warn("[Transfer] 커넥터 이관 실패 — {} ({})", base, e.toString());
            return new TransferResult(true, false, 0, base, "이관 실패 — " + e.getMessage(), null);
        }
    }

    /** 넘길 산출물 한 건. */
    /** 이관할 산출물 한 건 — {@code text} 는 비식별 단계를 지난 텍스트, {@code sendId} 는 그 단계의 T5. */
    public record Output(VoiceKind kind, String fileName, String text, Long sendId) {

        public Output(VoiceKind kind, String fileName, String text) {
            this(kind, fileName, text, null);
        }
    }
}
