package egovframework.unstructured.collector.common.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import egovframework.unstructured.collector.common.config.DeployEnvPreset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** T8 다시 집계 — {@code PUT …/batches/{execId}/deident-details}. 실패해도 수집을 막지 않는다. */
class LogCollectorDeidentDetailTest {

    private LogCollectorClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        client = new LogCollectorClient(new ObjectMapper(), mock(DeployEnvPreset.class));
        ReflectionTestUtils.setField(client, "baseUrl", "http://lc/logc");
        ReflectionTestUtils.setField(client, "enabled", true);
        ReflectionTestUtils.setField(client, "connectTimeoutSec", 1);
        ReflectionTestUtils.setField(client, "readTimeoutSec", 1);
        client.init();
        server = MockRestServiceServer.bindTo((RestTemplate) ReflectionTestUtils.getField(client, "restTemplate")).build();
    }

    @Test
    @DisplayName("PUT 으로 부르고 채운 항목 수를 돌려준다")
    void rebuild() {
        server.expect(requestTo("http://lc/logc/api/v1/logs/batches/20261119UNS002/deident-details"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess("{\"success\":true,\"code\":0,\"result\":{\"ids\":[11,12,13]}}", MediaType.APPLICATION_JSON));
        assertThat(client.rebuildDeidentDetails("20261119UNS002")).isEqualTo(3);
        server.verify();
    }

    @Test
    @DisplayName("실패 · 미연동 · 빈 ID 는 -1 — 예외를 던지지 않는다")
    void failureIsQuiet() {
        server.expect(requestTo("http://lc/logc/api/v1/logs/batches/E1/deident-details"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThat(client.rebuildDeidentDetails("E1")).isEqualTo(-1);
        assertThat(client.rebuildDeidentDetails(" ")).isEqualTo(-1);
        ReflectionTestUtils.setField(client, "enabled", false);
        assertThat(client.rebuildDeidentDetails("E1")).isEqualTo(-1);
        server.verify();
    }
}
