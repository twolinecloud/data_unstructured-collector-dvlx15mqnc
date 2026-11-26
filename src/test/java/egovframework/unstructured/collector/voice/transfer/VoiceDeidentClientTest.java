package egovframework.unstructured.collector.voice.transfer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import egovframework.unstructured.collector.common.model.SttResult;
import egovframework.unstructured.collector.common.model.VoiceTarget;
import egovframework.unstructured.collector.common.transfer.AgentConnectorClient;
import egovframework.unstructured.collector.common.transfer.AgentConnectorProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 음성 전사 비식별 클라이언트 — 커넥터 계약(요청 본문 · 응답 text/segments), 실패 시 원문 미전송(예외), MOCK 의 숫자 가림.
 */
class VoiceDeidentClientTest {

    private static final String RAW = "저 홍길동이고 010-1234-5678, 주민번호 900101-1234567 입니다";

    private final ObjectMapper om = new ObjectMapper();

    private static AgentConnectorProperties props(AgentConnectorProperties.Mode mode, String base) {
        return new AgentConnectorProperties(mode, base, "/api/v1/learn/transfer", true, "UNSTRUCTURED", "VOICE",
                50, 50L * 1024 * 1024, 1000, 5000, 100, 512L << 20, 60);
    }

    private static VoiceTarget target() {
        return new VoiceTarget(egovframework.unstructured.collector.common.model.VoiceKind.PHONE, "SIM00000000000001",
                "VRFC0001", "DOC1", "KEY1", true, null, "/src", "a.wav", null, java.time.LocalDateTime.now());
    }

    private VoiceDeidentClient client(AgentConnectorProperties p, boolean mask) {
        AgentConnectorClient connector = mock(AgentConnectorClient.class);
        when(connector.effectiveMode()).thenReturn(p.mode());
        return new VoiceDeidentClient(connector, p, om, true, mask, "/api/v1/pipeline/deident/voice");
    }

    @Test
    @DisplayName("REST — 계약대로 보내고(전문 · 구간 · deidentEnabled) 돌려받은 텍스트 · 구간을 쓴다")
    void restContract() throws Exception {
        AtomicReference<JsonNode> got = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/pipeline/deident/voice", ex -> {
            got.set(om.readTree(ex.getRequestBody()));
            byte[] resp = om.writeValueAsBytes(Map.of("text", "저 ***이고 ***-****-****", "segments", List.of("A*", "B*"),
                    "mode", "DEIDENT", "sendId", 77, "piiItemCnt", 3));
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, resp.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        try {
            VoiceDeidentClient c = client(props(AgentConnectorProperties.Mode.REST,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/"), true);
            SttResult stt = new SttResult(RAW, "MOCK", 3.0, false, "ko",
                    List.of(new SttResult.Segment(0, 0, 1, "A1"), new SttResult.Segment(1, 1, 2, "B2")));

            VoiceDeidentClient.Result r = c.deident("20261013UNS001", target(), "VP01", stt);

            assertThat(got.get().path("execId").asText()).isEqualTo("20261013UNS001");
            assertThat(got.get().path("recFileId").asText()).isEqualTo("VRFC0001");
            assertThat(got.get().path("kind").asText()).isEqualTo("PHONE");
            assertThat(got.get().path("inmatePid").asText()).isEqualTo("VP01");
            assertThat(got.get().path("deidentEnabled").asBoolean()).isTrue();
            assertThat(got.get().path("segments")).hasSize(2);
            assertThat(r.text()).isEqualTo("저 ***이고 ***-****-****");
            assertThat(r.segments()).containsExactly("A*", "B*");
            assertThat(r.sendId()).isEqualTo(77L);

            SttResult masked = stt.withDeidentified(r.text(), r.segments());
            assertThat(masked.segments()).extracting(SttResult.Segment::text).containsExactly("A*", "B*");
            assertThat(masked.segments()).extracting(SttResult.Segment::start).containsExactly(0.0, 1.0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("커넥터 오류 — 예외(호출자가 원문을 보내지 않음) · 메시지에 전사 · 응답 본문을 싣지 않는다")
    void restErrorThrowsWithoutText() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/pipeline/deident/voice", ex -> {
            ex.getRequestBody().readAllBytes();
            byte[] resp = ("{\"echo\":\"" + RAW + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.sendResponseHeaders(500, resp.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        try {
            VoiceDeidentClient c = client(props(AgentConnectorProperties.Mode.REST,
                    "http://127.0.0.1:" + server.getAddress().getPort()), true);
            assertThatThrownBy(() -> c.deident("E", target(), "VP", new SttResult(RAW, "MOCK", 1, false)))
                    .isInstanceOf(VoiceDeidentClient.DeidentException.class)
                    .hasMessageContaining("HTTP 500")
                    .hasMessageNotContaining("010-1234-5678")
                    .hasMessageNotContaining("홍길동");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("REST 인데 주소가 비었으면 — 예외(원문을 보내지 않는다)")
    void restWithoutBaseUrl() {
        VoiceDeidentClient c = client(props(AgentConnectorProperties.Mode.REST, ""), true);
        assertThatThrownBy(() -> c.deident("E", target(), "VP", new SttResult(RAW, "MOCK", 1, false)))
                .isInstanceOf(VoiceDeidentClient.DeidentException.class);
    }

    @Test
    @DisplayName("MOCK — 커넥터를 부르지 않고 주민번호 · 전화번호 숫자만 가린다(길이 유지) · 구간도")
    void mockMasksDigits() {
        VoiceDeidentClient c = client(props(AgentConnectorProperties.Mode.MOCK, ""), true);
        SttResult stt = new SttResult(RAW, "MOCK", 3.0, false, "ko",
                List.of(new SttResult.Segment(0, 0, 1, "010-1234-5678"), new SttResult.Segment(1, 1, 2, "안녕")));

        VoiceDeidentClient.Result r = c.deident("E", target(), "VP", stt);

        assertThat(r.text()).doesNotContain("010-1234-5678").doesNotContain("900101-1234567").hasSameSizeAs(RAW);
        assertThat(r.segments()).containsExactly("***-****-****", "안녕");
        assertThat(r.piiItemCnt()).isEqualTo(2);
        assertThat(c.mode()).isEqualTo("MOCK");
    }

    @Test
    @DisplayName("격리 응답(isolated · text=null) — [RECONCILE] 예외 · 문구는 건수 · 유형만(값 없음) · 원문을 보내지 않는다")
    void isolatedResponseThrowsReconcile() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/pipeline/deident/voice", ex -> {
            ex.getRequestBody().readAllBytes();
            byte[] resp = ("{\"isolated\":true,\"text\":null,\"segments\":null,\"deidentStsCd\":\"FAIL\","
                    + "\"displayStatus\":\"DEIDENT_ISOLATED\",\"errTypeCd\":\"RECONCILE\",\"residualCount\":1,"
                    + "\"residualTypes\":[\"PHONE\"],\"sendId\":12345}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, resp.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        try {
            VoiceDeidentClient c = client(props(AgentConnectorProperties.Mode.REST,
                    "http://127.0.0.1:" + server.getAddress().getPort()), true);
            assertThatThrownBy(() -> c.deident("E", target(), "VP", new SttResult(RAW, "MOCK", 1, false)))
                    .isInstanceOf(VoiceDeidentClient.DeidentException.class)
                    .hasMessage("[RECONCILE] 비식별 후 잔존 개인정보 1건(PHONE) — 격리 · 전송 제외")
                    .hasMessageNotContaining("010-1234-5678")
                    .hasMessageNotContaining("홍길동");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("응답 해석 — 격리 문구에는 유형 모양만(중복 없이 · 최대 6) · 유형이 없으면 '유형 미상' · 격리가 아니고 본문도 없으면 종전 '비어 있음'")
    void readResponseEdges() throws Exception {
        assertThatThrownBy(() -> VoiceDeidentClient.readResponse(om.readTree(
                "{\"isolated\":true,\"residualCount\":2,\"residualTypes\":[\"PHONE\",\"010-1234-5678\",\"PHONE\",\"rrn\",\"RRN\"]}"),
                true))
                .isInstanceOf(VoiceDeidentClient.DeidentException.class)
                .hasMessage("[RECONCILE] 비식별 후 잔존 개인정보 2건(PHONE,RRN) — 격리 · 전송 제외");
        assertThatThrownBy(() -> VoiceDeidentClient.readResponse(om.readTree("{\"isolated\":true}"), true))
                .hasMessage("[RECONCILE] 비식별 후 잔존 개인정보 0건(유형 미상) — 격리 · 전송 제외");
        assertThatThrownBy(() -> VoiceDeidentClient.readResponse(om.readTree("{\"isolated\":false,\"text\":null}"), true))
                .isInstanceOf(VoiceDeidentClient.DeidentException.class)
                .hasMessage("에이전트 커넥터 비식별 응답이 비어 있음");
        assertThatThrownBy(() -> VoiceDeidentClient.readResponse(null, true))
                .hasMessage("에이전트 커넥터 비식별 응답이 비어 있음");

        VoiceDeidentClient.Result ok = VoiceDeidentClient.readResponse(om.readTree(
                "{\"isolated\":false,\"text\":\"저 ***이고\",\"mode\":\"DEIDENT\",\"sendId\":3,\"piiItemCnt\":1,"
                        + "\"errTypeCd\":null,\"residualCount\":null,\"residualTypes\":null}"), true);
        assertThat(ok.text()).isEqualTo("저 ***이고");
        assertThat(ok.sendId()).isEqualTo(3L);
        assertThat(ok.piiItemCnt()).isEqualTo(1);
        assertThat(ok.segments()).isNull();
    }

    @Test
    @DisplayName("꺼짐 — 단계 표기 OFF")
    void disabled() {
        AgentConnectorProperties p = props(AgentConnectorProperties.Mode.REST, "http://x");
        AgentConnectorClient connector = mock(AgentConnectorClient.class);
        VoiceDeidentClient off = new VoiceDeidentClient(connector, p, om, false, true, "/p");
        assertThat(off.enabled()).isFalse();
        assertThat(off.mode()).isEqualTo("OFF");
    }
}
