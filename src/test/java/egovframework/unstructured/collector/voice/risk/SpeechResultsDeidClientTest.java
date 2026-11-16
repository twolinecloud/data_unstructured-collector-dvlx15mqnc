package egovframework.unstructured.collector.voice.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static egovframework.unstructured.collector.voice.risk.Wf9TestSupport.OM;
import static egovframework.unstructured.collector.voice.risk.Wf9TestSupport.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 커넥터 {@code POST /api/v1/deid/speech-results} 호출 — 가짜 커넥터로만. 모든 값은 가상이다. */
class SpeechResultsDeidClientTest {

    interface Handler {
        void handle(HttpExchange ex, int call) throws IOException;
    }

    private HttpServer server;
    private ExecutorService pool;
    private String base;
    private final AtomicInteger calls = new AtomicInteger();
    private final List<JsonNode> bodies = new CopyOnWriteArrayList<>();
    private volatile Handler handler;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pool = Executors.newCachedThreadPool();
        server.setExecutor(pool);
        server.createContext("/api/v1/deid/speech-results", ex -> {
            bodies.add(OM.readTree(ex.getRequestBody().readAllBytes()));
            handler.handle(ex, calls.incrementAndGet());
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        pool.shutdownNow();
    }

    private SpeechResultsDeidClient client(String baseUrl, boolean mock, int readTimeoutSec) {
        return new SpeechResultsDeidClient(OM, () -> baseUrl, () -> mock, "/api/v1/deid/speech-results", 1000, readTimeoutSec);
    }

    private static JsonNode doc() throws IOException {
        return OM.readTree("{\"function\":\"contact_risk_analysis\",\"input\":{\"speech_results\":[{\"transcript\":\"홍길동이 왔다\"}]}}");
    }

    @Test
    @DisplayName("200 — 가린 문서와 사전을 받는다 · 요청은 {recFileId, execId, document}")
    void ok() throws Exception {
        handler = (ex, n) -> json(ex, 200, """
                {"recFileId":"TARE0000000001","execId":"E1",
                 "document":{"function":"contact_risk_analysis","input":{"speech_results":[{"transcript":"[NAME_1]이 왔다"}]}},
                 "mapping":{"[NAME_1]":"홍길동","[N]":1},"tokensByLength":["[NAME_1]"],"stats":{"tokens":1}}""");

        SpeechResultsDeidClient.Result r = client(base, false, 5).deidentify("TARE0000000001", "E1", doc());

        assertThat(r.document().path("input").path("speech_results").path(0).path("transcript").asText()).isEqualTo("[NAME_1]이 왔다");
        assertThat(r.mapping()).containsExactly(java.util.Map.entry("[NAME_1]", "홍길동"));
        assertThat(r.toString()).doesNotContain("홍길동");
        JsonNode sent = bodies.get(0);
        assertThat(sent.path("recFileId").asText()).isEqualTo("TARE0000000001");
        assertThat(sent.path("execId").asText()).isEqualTo("E1");
        assertThat(sent.path("document").path("input").path("speech_results").path(0).path("transcript").asText())
                .isEqualTo("홍길동이 왔다");
    }

    @Test
    @DisplayName("422 · 400 → 바로 포기(규격 code 만 싣는다 · detail 없음) · 5xx → 다시 하기")
    void statusMapping() {
        handler = (ex, n) -> {
            switch (n) {
                case 1 -> json(ex, 422, "{\"status\":422,\"code\":\"DEID_RESIDUAL_PII\",\"message\":\"…\","
                        + "\"detail\":\"남은 자리 1곳 · 첫 자리 /input/speech_results/0/transcript (형식)\"}");
                case 2 -> json(ex, 400, "{\"status\":400,\"code\":\"DEID_INVALID_PARAMETER\",\"message\":\"음성 분석 결과가 너무 깁니다.\"}");
                case 3 -> json(ex, 503, "{\"status\":503,\"code\":\"DEID_SERVICE_UNAVAILABLE\",\"message\":\"…\"}");
                case 4 -> json(ex, 504, "<html>gateway timeout 홍길동</html>");
                default -> json(ex, 404, "");
            }
        };
        SpeechResultsDeidClient c = client(base, false, 5);

        assertThatThrownBy(() -> c.deidentify("R", "E", doc())).isInstanceOfSatisfying(Wf9Failure.class, f -> {
            assertThat(f.retryable()).isFalse();
            assertThat(f.stage()).isEqualTo(Wf9Failure.Stage.DEID);
            assertThat(f.getMessage()).isEqualTo("커넥터 비식별 HTTP 422 DEID_RESIDUAL_PII");
        });
        assertThatThrownBy(() -> c.deidentify("R", "E", doc())).isInstanceOfSatisfying(Wf9Failure.class,
                f -> assertThat(f.retryable()).isFalse());
        assertThatThrownBy(() -> c.deidentify("R", "E", doc())).isInstanceOfSatisfying(Wf9Failure.class, f -> {
            assertThat(f.retryable()).isTrue();
            assertThat(f.getMessage()).isEqualTo("커넥터 비식별 HTTP 503 DEID_SERVICE_UNAVAILABLE");
        });
        assertThatThrownBy(() -> c.deidentify("R", "E", doc())).isInstanceOfSatisfying(Wf9Failure.class, f -> {
            assertThat(f.retryable()).isTrue();
            assertThat(f.getMessage()).isEqualTo("커넥터 비식별 HTTP 504");
        });
        assertThatThrownBy(() -> c.deidentify("R", "E", doc())).as("엔드포인트 없음(옛 커넥터) — 배포되면 풀린다")
                .isInstanceOfSatisfying(Wf9Failure.class, f -> assertThat(f.retryable()).isTrue());
    }

    @Test
    @DisplayName("응답 모양이 다르면(mapping 없음 · JSON 아님) 다시 하기")
    void malformed() {
        handler = (ex, n) -> json(ex, 200, n == 1 ? "{\"document\":{}}" : "not json");
        SpeechResultsDeidClient c = client(base, false, 5);

        assertThatThrownBy(() -> c.deidentify("R", "E", doc())).hasMessageContaining("응답 모양");
        assertThatThrownBy(() -> c.deidentify("R", "E", doc())).isInstanceOfSatisfying(Wf9Failure.class,
                f -> assertThat(f.retryable()).isTrue());
    }

    @Test
    @DisplayName("연결 실패 · 응답 시한 초과 → 다시 하기")
    void connectionAndTimeout() throws Exception {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        assertThatThrownBy(() -> client("http://127.0.0.1:" + closedPort, false, 5).deidentify("R", "E", doc()))
                .isInstanceOfSatisfying(Wf9Failure.class, f -> {
                    assertThat(f.retryable()).isTrue();
                    assertThat(f.getMessage()).contains("연결 실패");
                });

        handler = (ex, n) -> {
            try {
                Thread.sleep(4_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            json(ex, 200, "{}");
        };
        assertThatThrownBy(() -> client(base, false, 1).deidentify("R", "E", doc()))
                .isInstanceOfSatisfying(Wf9Failure.class, f -> {
                    assertThat(f.retryable()).isTrue();
                    assertThat(f.getMessage()).contains("시간 초과");
                });
    }

    @Test
    @DisplayName("MOCK 전송 모드 · 주소 없음 → 막힘(부르지 않는다)")
    void blocked() {
        assertThat(client(base, true, 5).blocker()).contains("MOCK");
        assertThat(client(" ", false, 5).blocker()).contains("agent-connector.base-url");
        assertThat(client(base, false, 5).blocker()).isNull();
        assertThatThrownBy(() -> client(base, true, 5).deidentify("R", "E", doc())).isInstanceOf(Wf9Failure.class);
        assertThat(calls.get()).isZero();
    }
}
