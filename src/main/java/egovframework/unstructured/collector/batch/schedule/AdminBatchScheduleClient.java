package egovframework.unstructured.collector.batch.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import egovframework.unstructured.collector.batch.UnstructuredBatchProperties;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.Optional;

/**
 * admin-api 배치 스케줄 설정 조회 — {@code GET {admin}/api/batch-schedules/{유형}}. data-collector {@code AdminBatchScheduleClient} 와 같은 규약.
 *
 * <p>DB 를 직접 읽지 않는다 — 설정의 주인은 admin-api 이고, 관리 화면이 보는 값(응답 {@code result})을 그대로 쓴다.
 * base-url 이 비면 비활성(조회 시 empty). 조회 실패도 empty — 스케줄러가 지금 스케줄을 유지한다.</p>
 */
@Log4j2
@Component
public class AdminBatchScheduleClient {

    private final RestClient client;   // null 이면 비활성
    private final String baseUrl;

    @Autowired
    public AdminBatchScheduleClient(UnstructuredBatchProperties props, RestClient.Builder builder) {
        UnstructuredBatchProperties.Admin a = props.admin();
        if (!a.configured()) {
            this.client = null;
            this.baseUrl = null;
            log.info("[Schedule] admin-api 주소 미설정 — 설정 조회 끔(refresh 본문 적용 · 수동 실행만)");
            return;
        }
        HttpClient hc = HttpClient.newBuilder().connectTimeout(a.connectTimeout()).build();
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(hc);
        rf.setReadTimeout(a.readTimeout());
        this.baseUrl = a.baseUrl().replaceAll("/+$", "");
        this.client = builder.baseUrl(this.baseUrl).requestFactory(rf).build();
        log.info("[Schedule] admin-api={}", this.baseUrl);
    }

    /** 테스트용 — 만들어 둔 클라이언트(목 서버 바인딩)를 그대로 쓴다. */
    AdminBatchScheduleClient(RestClient client, String baseUrl) {
        this.client = client;
        this.baseUrl = baseUrl;
    }

    public boolean isEnabled() {
        return client != null;
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** 유형(예 {@code UNSTRUCTURED}) 설정 조회. 비활성 · 실패 · 응답 이상이면 empty. */
    public Optional<BatchSchedule> fetch(String dataTypeCd) {
        if (client == null) {
            return Optional.empty();
        }
        try {
            JsonNode res = client.get().uri("/api/batch-schedules/{d}", dataTypeCd).retrieve().body(JsonNode.class);
            JsonNode r = res == null ? null : res.path("result");
            if (r == null || r.isMissingNode() || !r.hasNonNull("dataTypeCd")) {
                log.warn("[Schedule] 설정 조회 응답 이상 — {}", res);
                return Optional.empty();
            }
            return Optional.of(new BatchSchedule(r.path("dataTypeCd").asText(), r.path("activeYn").asText(),
                    r.path("execSchedTypeCd").asText(), r.path("schedVal").asText()));
        } catch (RuntimeException e) {
            log.warn("[Schedule] 설정 조회 실패(지금 스케줄 유지) — {}", e.getMessage());
            return Optional.empty();
        }
    }
}
