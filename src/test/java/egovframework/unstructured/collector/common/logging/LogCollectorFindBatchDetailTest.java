package egovframework.unstructured.collector.common.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import egovframework.unstructured.collector.common.config.DeployEnvPreset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 계획 단계 배치 상세({@link LogCollectorClient#findBatchDetail}) — <b>'없음'과 '못 읽음'을 가른다.</b>
 * 예전에는 둘 다 null 이라 로그 컬렉터 장애가 "선택한 배치를 찾을 수 없습니다"(400)로 보였다.
 */
class LogCollectorFindBatchDetailTest {

    private LogCollectorClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        client = new LogCollectorClient(new ObjectMapper().findAndRegisterModules(), mock(DeployEnvPreset.class));
        ReflectionTestUtils.setField(client, "baseUrl", "http://lc/logc");
        ReflectionTestUtils.setField(client, "enabled", true);
        ReflectionTestUtils.setField(client, "connectTimeoutSec", 1);
        ReflectionTestUtils.setField(client, "readTimeoutSec", 1);
        client.init();
        server = MockRestServiceServer.bindTo((RestTemplate) ReflectionTestUtils.getField(client, "restTemplate")).build();
    }

    @Test
    @DisplayName("200 — 상세(result)를 돌려준다")
    void found() {
        server.expect(requestTo("http://lc/logc/api/v1/logs/batches/20261020UNS001"))
                .andRespond(withSuccess("{\"success\":true,\"code\":0,\"result\":{\"batch\":{\"exec_id\":\"20261020UNS001\"}}}",
                        MediaType.APPLICATION_JSON));
        assertThat(client.findBatchDetail("20261020UNS001").path("batch").path("exec_id").asText()).isEqualTo("20261020UNS001");
        server.verify();
    }

    @Test
    @DisplayName("404 — 배치가 없다 → null(호출 측이 400 '찾을 수 없습니다')")
    void notFoundIsNull() {
        server.expect(requestTo("http://lc/logc/api/v1/logs/batches/NONE"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":true,\"http_status_code\":404,\"message\":\"배치 실행 이력 없음: NONE\"}"));
        assertThat(client.findBatchDetail("NONE")).isNull();
        server.verify();
    }

    @Test
    @DisplayName("5xx · 빈 응답 — 못 읽음 → UnavailableException(호출 측이 503)")
    void serverErrorOrEmptyThrows() {
        server.expect(requestTo("http://lc/logc/api/v1/logs/batches/E1")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThatThrownBy(() -> client.findBatchDetail("E1")).isInstanceOf(LogCollectorClient.UnavailableException.class)
                .hasMessageContaining("/api/v1/logs/batches/E1");

        server.reset();
        server.expect(requestTo("http://lc/logc/api/v1/logs/batches/E2")).andRespond(withStatus(HttpStatus.OK));
        assertThatThrownBy(() -> client.findBatchDetail("E2")).isInstanceOf(LogCollectorClient.UnavailableException.class)
                .hasMessageContaining("빈 응답");
    }

    @Test
    @DisplayName("미연동 — 부르지 않고 null(예전처럼 400 '찾을 수 없습니다 (로그 컬렉터 미연동)')")
    void disabledIsNull() {
        ReflectionTestUtils.setField(client, "enabled", false);
        assertThat(client.findBatchDetail("20261020UNS001")).isNull();
        server.verify();
    }
}
