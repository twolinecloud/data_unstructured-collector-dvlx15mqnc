package egovframework.unstructured.collector.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import egovframework.unstructured.collector.common.logging.LogCollectorClient;
import egovframework.unstructured.collector.common.transfer.AgentConnectorClient;
import egovframework.unstructured.collector.common.transfer.AgentConnectorProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 전사문 비식별 — 커넥터 {@code /api/v1/deid/run} 규격(쿼리 · 본문) · 성공만 200 · 504 DEID_TIMEOUT · 500 실패에 원문이 새지 않음 ·
 * 전송 모드 MOCK 이면 커넥터를 부르지 않는다.
 */
class TranscriptDeidRunClientTest {

    private static final ObjectMapper M = new ObjectMapper();

    private HttpServer server;
    private final List<String[]> received = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String reply;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            try {
                received.add(new String[]{ex.getRequestURI().getRawQuery(), ex.getRequestURI().getPath(),
                        new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)});
                byte[] b = reply.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(status, b.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(b);
                }
            } finally {
                ex.close();
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("성공 — STT 원천 규격으로 보내고(적재 안 함) 가린 글 · 토큰 수만 꺼낸다(사전은 버림)")
    void success() throws Exception {
        reply = """
                {"documents":[{"docId":"STT_SESSION:S1"}],"problems":[],"execId":"20261111UNS007","success":true,
                 "items":[{"inmatePid":"VP1","deidentStsCd":"SUCCESS","piiItemCnt":1,
                   "deidentedDataset":{"sttSessionId":"S1","STT_VISIT":{"TRANSCRIPT_TEXT":"보호자 [NAME_1] 씨 [PHONE_1] · [NAME_1] · [NAME_2] · 계좌 ***-***"}},
                   "reidMapping":{"origin_mapping":"{\\"[NAME_1]\\":\\"김철수\\"}"}}]}""";
        TranscriptDeidRunClient.Result r = client(AgentConnectorProperties.Mode.REST)
                .deidentify("20261111UNS007", "S1", "보호자 김철수 씨 010-1234-5678");

        String[] req = received.get(0);
        assertThat(req[1]).isEqualTo("/api/v1/deid/run");
        assertThat(req[0]).contains("persist=false").contains("includeMapping=false").contains("srcSysCd=STT")
                .contains("docId=STT_SESSION%3AS1").doesNotContain("airMode");
        JsonNode body = M.readTree(req[2]);
        assertThat(body.path("execId").asText()).isEqualTo("20261111UNS007");
        assertThat(body.path("sttSessionId").asText()).isEqualTo("S1");
        assertThat(body.path("STT_VISIT").path("TRANSCRIPT_TEXT").asText()).isEqualTo("보호자 김철수 씨 010-1234-5678");

        assertThat(r.mode()).isEqualTo("REST");
        assertThat(r.text()).startsWith("보호자 [NAME_1] 씨").doesNotContain("김철수");
        assertThat(r.tokens()).isEqualTo(4);
        assertThat(r.uniqueTokens()).isEqualTo(3);
        assertThat(r.tokenTypes()).containsEntry("NAME", 2).containsEntry("PHONE", 1);
        assertThat(r.masks()).isEqualTo(2);
        assertThat(r.piiItemCnt()).isEqualTo(1);
        assertThat(r.connectorExecId()).isEqualTo("20261111UNS007");
        assertThat(r.toString()).as("사전(원문)을 들지 않는다").doesNotContain("김철수");

        // SIM — execId 를 싣지 않는다(커넥터가 로컬 번호)
        client(AgentConnectorProperties.Mode.REST).deidentify(null, "S2", "글");
        assertThat(M.readTree(received.get(1)[2]).has("execId")).isFalse();
    }

    @Test
    @DisplayName("504 DEID_TIMEOUT — 타임아웃으로 분류되는 사유 · 코드 · 상태")
    void timeout504() {
        status = 504;
        reply = "{\"status\":504,\"code\":\"DEID_TIMEOUT\",\"message\":\"현재 비식별 처리 작업이 많아 지연되고 있습니다. 잠시 후 다시 시도해 주세요.\"}";
        TranscriptDeidRunClient.DeidRunException e = assertThrows(TranscriptDeidRunClient.DeidRunException.class,
                () -> client(AgentConnectorProperties.Mode.REST).deidentify(null, "S1", "글"));
        assertThat(e.httpStatus()).isEqualTo(504);
        assertThat(e.code()).isEqualTo("DEID_TIMEOUT");
        assertThat(e.getMessage()).contains("타임아웃").contains("DEID_TIMEOUT");
        assertThat(LogCollectorClient.FileProcReq.errStackOf(e.getMessage())).startsWith("[TIMEOUT]");

        reply = "<html>504 Gateway Time-out</html>";
        TranscriptDeidRunClient.DeidRunException bare = assertThrows(TranscriptDeidRunClient.DeidRunException.class,
                () -> client(AgentConnectorProperties.Mode.REST).deidentify(null, "S1", "글"));
        assertThat(bare.code()).isNull();
        assertThat(bare.getMessage()).contains("앞단 프록시");
    }

    @Test
    @DisplayName("500 success:false — 실패 건의 deidentedDataset(원문)은 읽지 않는다 · 사유는 코드 · 오류 한 줄")
    void failureDoesNotLeakText() {
        status = 500;
        reply = """
                {"success":false,"piiLeakRisk":true,"failedCount":1,
                 "message":"비식별 실패 1/1건 — 실패한 레코드의 deidentedDataset 에는 원문이 그대로 남아 있습니다.",
                 "items":[{"deidentStsCd":"FAIL","failedFieldCnt":1,
                   "deidentedDataset":{"STT_VISIT":{"TRANSCRIPT_TEXT":"보호자 김철수 씨 010-1234-5678"}},
                   "errors":[{"path":"$.STT_VISIT.TRANSCRIPT_TEXT","error":"Read timed out"}]}]}""";
        TranscriptDeidRunClient.DeidRunException e = assertThrows(TranscriptDeidRunClient.DeidRunException.class,
                () -> client(AgentConnectorProperties.Mode.REST).deidentify(null, "S1", "보호자 김철수 씨 010-1234-5678"));
        assertThat(e.httpStatus()).isEqualTo(500);
        assertThat(e.getMessage()).contains("HTTP 500").contains("Read timed out")
                .doesNotContain("김철수").doesNotContain("010-1234-5678");
        assertThat(LogCollectorClient.FileProcReq.errStackOf(e.getMessage())).startsWith("[TIMEOUT]");

        // 성공 응답인데 가린 글 자리가 없으면(매핑 룰 없음) 실패
        status = 200;
        reply = "{\"success\":true,\"items\":[{\"deidentStsCd\":\"SUCCESS\",\"deidentedDataset\":{}}]}";
        assertThat(assertThrows(TranscriptDeidRunClient.DeidRunException.class,
                () -> client(AgentConnectorProperties.Mode.REST).deidentify(null, "S1", "글")).getMessage())
                .contains("STT_VISIT.TRANSCRIPT_TEXT");
    }

    @Test
    @DisplayName("연결 실패 · 주소 없음 — 연결로 분류 · 설정 오류")
    void connectionAndConfig() {
        int port = server.getAddress().getPort();
        server.stop(0);
        TranscriptDeidRunClient.DeidRunException e = assertThrows(TranscriptDeidRunClient.DeidRunException.class,
                () -> client(AgentConnectorProperties.Mode.REST, "http://127.0.0.1:" + port).deidentify(null, "S1", "글"));
        assertThat(LogCollectorClient.FileProcReq.errStackOf(e.getMessage())).startsWith("[CONNECTION]");

        TranscriptDeidRunClient.DeidRunException cfg = assertThrows(TranscriptDeidRunClient.DeidRunException.class,
                () -> client(AgentConnectorProperties.Mode.REST, "").deidentify(null, "S1", "글"));
        assertThat(cfg.code()).isEqualTo("CONFIG");
        server = null;
    }

    @Test
    @DisplayName("전송 모드 MOCK — 커넥터를 부르지 않고 전화 · 주민번호 · 이메일만 토큰으로(같은 값은 같은 토큰)")
    void mockMode() {
        TranscriptDeidRunClient c = client(AgentConnectorProperties.Mode.MOCK);
        TranscriptDeidRunClient.Result r = c.deidentify(null, "S1",
                "연락처 010-1234-5678 · 다시 010-1234-5678 · 010 9876 5432 아님 · a@b.kr · 850314-1234567");
        assertThat(received).isEmpty();
        assertThat(r.mode()).isEqualTo("MOCK");
        assertThat(r.text()).isEqualTo("연락처 [PHONE_1] · 다시 [PHONE_1] · 010 9876 5432 아님 · [EMAIL_1] · [RRN_1]");
        assertThat(r.tokens()).isEqualTo(4);
        assertThat(r.uniqueTokens()).isEqualTo(3);
        assertThat(c.status()).containsEntry("mode", "MOCK").containsEntry("persist", false);
    }

    // ------------------------------------------------------------------ 도우미

    private TranscriptDeidRunClient client(AgentConnectorProperties.Mode mode) {
        return client(mode, "http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static TranscriptDeidRunClient client(AgentConnectorProperties.Mode mode, String baseUrl) {
        AgentConnectorClient connector = mock(AgentConnectorClient.class);
        when(connector.effectiveMode()).thenReturn(mode);
        AgentConnectorProperties cp = new AgentConnectorProperties(mode, baseUrl, "/api/v1/learn/transfer", true,
                "UNSTRUCTURED", "VOICE", 50, 52_428_800L, 1000, 5000, 500, 536_870_912L, 60);
        SttDeidProperties p = new SttDeidProperties("VOICE_STT_DEID", "음성 STT·비식별 검증", "/api/v1/deid/run", 10, "",
                true, 20, new SttDeidProperties.Daily(true, 3, 5, 20, 300));
        return new TranscriptDeidRunClient(connector, cp, p, M);
    }
}
