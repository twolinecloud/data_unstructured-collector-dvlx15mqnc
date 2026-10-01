package egovframework.unstructured.collector.common.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 제논(Zenon) 전송 — 파이프라인 3단계 SEND. 수신 API 계약(multipart: file + metadata JSON → code=SUCCESS)과 실패 판정.
 */
class ZenonClientTest {

    private final ObjectMapper om = new ObjectMapper();

    private static ZenonProperties props(ZenonProperties.Mode mode) {
        return new ZenonProperties(mode, "http://zenon.test:8000", "/api/v1/zenon/receive", 1000, 1000, 50);
    }

    private static ZenonClient.Document doc(String execId) {
        return new ZenonClient.Document("VOICE", execId, "SIM00000000000001", "meet-SIM-MEET-001.json",
                "{\"transcript\":{\"text\":\"안녕하세요\"}}".getBytes(StandardCharsets.UTF_8), "application/json",
                Map.of("kind", "MEET", "idempotency_key", "SIM-MEET-001"), "안녕하세요");
    }

    @Test
    @DisplayName("REST — multipart(file + metadata)로 보내고 code=SUCCESS 면 수신증을 남긴다")
    void restSuccess() {
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();
        server.expect(requestTo("http://zenon.test:8000/api/v1/zenon/receive"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("name=\"file\"; filename=\"meet-SIM-MEET-001.json\""),
                        org.hamcrest.Matchers.containsString("name=\"metadata\""),
                        org.hamcrest.Matchers.containsString("\"exec_id\":\"20261001TST001\""),
                        org.hamcrest.Matchers.containsString("\"type\":\"VOICE\""),
                        org.hamcrest.Matchers.containsString("\"inmate_no\":\"SIM00000000000001\""))))
                .andRespond(withSuccess("{\"code\":\"SUCCESS\",\"exec_id\":\"20261001TST001\",\"received_at\":\"2026-10-01T10:00:00\","
                        + "\"file_name\":\"meet-SIM-MEET-001.json\",\"file_size_bytes\":36}", MediaType.APPLICATION_JSON));
        ZenonClient c = new ZenonClient(props(ZenonProperties.Mode.REST), om, rt);

        ZenonClient.Receipt r = c.send(doc("20261001TST001"));

        server.verify();
        assertThat(r.code()).isEqualTo("SUCCESS");
        assertThat(r.mode()).isEqualTo("REST");
        assertThat(r.fileSizeBytes()).isEqualTo(36L);
        assertThat(r.location()).isEqualTo("zenon:http://zenon.test:8000/api/v1/zenon/receive/meet-SIM-MEET-001.json");
        assertThat(c.receipts("20261001TST001")).hasSize(1);
    }

    @Test
    @DisplayName("REST — 5xx · 4xx · code≠SUCCESS 는 전송 실패(예외) — 그 건만 SEND 실패로 남는다")
    void restFailures() {
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();
        server.expect(requestTo("http://zenon.test:8000/api/v1/zenon/receive"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("{\"code\":\"ERROR\"}"));
        server.expect(requestTo("http://zenon.test:8000/api/v1/zenon/receive"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"code\":\"BAD_REQUEST\"}"));
        server.expect(requestTo("http://zenon.test:8000/api/v1/zenon/receive"))
                .andRespond(withSuccess("{\"code\":\"REJECTED\"}", MediaType.APPLICATION_JSON));
        ZenonClient c = new ZenonClient(props(ZenonProperties.Mode.REST), om, rt);

        assertThatThrownBy(() -> c.send(doc("X1"))).isInstanceOf(ZenonClient.ZenonSendException.class).hasMessageContaining("HTTP 500");
        assertThatThrownBy(() -> c.send(doc("X2"))).isInstanceOf(ZenonClient.ZenonSendException.class).hasMessageContaining("HTTP 400");
        assertThatThrownBy(() -> c.send(doc("X3"))).isInstanceOf(ZenonClient.ZenonSendException.class).hasMessageContaining("REJECTED");
        assertThat(c.receipts(null)).as("실패한 건은 수신증이 없다").isEmpty();
    }

    @Test
    @DisplayName("REST 인데 주소가 비면 보내지 않고 실패")
    void restWithoutBaseUrl() {
        ZenonClient c = new ZenonClient(new ZenonProperties(ZenonProperties.Mode.REST, "", "/api/v1/zenon/receive",
                1000, 1000, 50), om, new RestTemplate());

        assertThatThrownBy(() -> c.send(doc("X"))).isInstanceOf(ZenonClient.ZenonSendException.class)
                .hasMessageContaining("base-url");
    }

    @Test
    @DisplayName("MOCK — 네트워크 없이 수신증만 · 시험 배치(TST) 수신증은 초기화로 지운다")
    void mockKeepsReceiptsAndClearsTest() {
        ZenonClient c = new ZenonClient(props(ZenonProperties.Mode.MOCK), om, new RestTemplate());

        c.send(doc("20261001TST001"));
        c.send(doc("20261001VOC001"));

        assertThat(c.receipts(null)).hasSize(2);
        assertThat(c.receipts("20261001TST001").get(0).location()).isEqualTo("zenon:mock/meet-SIM-MEET-001.json");
        assertThat(c.clearTestReceipts()).isEqualTo(1);
        assertThat(c.receipts(null)).extracting(ZenonClient.Receipt::execId).containsExactly("20261001VOC001");
    }
}
