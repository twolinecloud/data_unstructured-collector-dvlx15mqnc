package egovframework.unstructured.collector.voice.transfer;

import egovframework.unstructured.collector.common.config.DeployEnvPreset;
import egovframework.unstructured.collector.common.model.VoiceKind;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 비식별 커넥터 호출 — {@code deidentEnabled} 를 그대로 넘기고, 커넥터가 없거나 구버전일 때의 규칙을 지킨다.
 */
class AgentConnectorClientDeidentTest {

    private RestTemplate rest;
    private MockRestServiceServer server;
    private AgentConnectorClient client;

    @BeforeEach
    void setUp() {
        rest = new RestTemplate();
        server = MockRestServiceServer.bindTo(rest).build();
        client = new AgentConnectorClient(rest, mock(DeployEnvPreset.class));
        ReflectionTestUtils.setField(client, "enabled", true);
        ReflectionTestUtils.setField(client, "baseUrlConfigured", "http://agent");
    }

    @Test
    @DisplayName("요청에 deidentEnabled · recFileId · inmatePid 를 싣고, 비식별된 text · sendId 를 받는다")
    void callsConnectorAndParses() {
        server.expect(requestTo("http://agent/api/v1/pipeline/deident/voice"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.deidentEnabled").value(true))
                .andExpect(jsonPath("$.recFileId").value("SIM-PHONE-001"))
                .andExpect(jsonPath("$.inmatePid").value("VP1"))
                .andRespond(withSuccess("{\"text\":\"연락처 010-****-5678\",\"mode\":\"DEIDENT\",\"displayStatus\":\"DEIDENT_SUCCESS\","
                        + "\"solutionCd\":\"AIR\",\"sendId\":55,\"t5Logged\":true,\"piiItemCnt\":1}", MediaType.APPLICATION_JSON));

        AgentConnectorClient.DeidentResult r = client.deident("E1", VoiceKind.PHONE, "SIM-PHONE-001", "VP1", "f.wav",
                "연락처 010-1234-5678", true);

        assertThat(r.text()).isEqualTo("연락처 010-****-5678");
        assertThat(r.displayStatus()).isEqualTo("DEIDENT_SUCCESS");
        assertThat(r.sendId()).isEqualTo(55L);
        assertThat(r.t5Logged()).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("커넥터 구버전(404) — 단순 전달이면 수집기가 그대로 넘기고, 비식별을 켰으면 실패")
    void oldConnector() {
        server.expect(requestTo("http://agent/api/v1/pipeline/deident/voice")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        AgentConnectorClient.DeidentResult r = client.deident("E1", VoiceKind.MEET, "K", "VP", "f", "원문", false);
        assertThat(r.text()).isEqualTo("원문");
        assertThat(r.displayStatus()).isEqualTo("SEND");
        assertThat(r.t5Logged()).isFalse();

        server.reset();
        server.expect(requestTo("http://agent/api/v1/pipeline/deident/voice")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> client.deident("E1", VoiceKind.MEET, "K", "VP", "f", "원문", true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("구버전");
    }

    @Test
    @DisplayName("커넥터 비활성 — 단순 전달은 수집기에서, 비식별은 실패. 커넥터를 부르지 않는다")
    void disabledConnector() {
        ReflectionTestUtils.setField(client, "enabled", false);
        assertThat(client.deident("E1", VoiceKind.MEET, "K", "VP", "f", "원문", false).text()).isEqualTo("원문");
        assertThatThrownBy(() -> client.deident("E1", VoiceKind.MEET, "K", "VP", "f", "원문", true))
                .isInstanceOf(IllegalStateException.class);
        server.verify();   // 아무 요청도 없어야 한다
    }

    @Test
    @DisplayName("커넥터 5xx — 예외(그 건은 DEIDENT 실패로 남아 재처리가 잇는다)")
    void serverErrorThrows() {
        server.expect(requestTo("http://agent/api/v1/pipeline/deident/voice")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThatThrownBy(() -> client.deident("E1", VoiceKind.MEET, "K", "VP", "f", "원문", false))
                .isInstanceOf(RuntimeException.class);
    }
}
