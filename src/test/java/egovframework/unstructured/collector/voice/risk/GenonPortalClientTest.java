package egovframework.unstructured.collector.voice.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static egovframework.unstructured.collector.voice.risk.Wf9TestSupport.OM;
import static egovframework.unstructured.collector.voice.risk.Wf9TestSupport.json;
import static egovframework.unstructured.collector.voice.risk.Wf9TestSupport.sse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 제논 채팅 WF9 — 가짜 genportal(로그인 · chat SSE)로만. 계정은 시험용 가짜 값이다. */
class GenonPortalClientTest {

    interface Chat {
        void handle(HttpExchange ex, int call) throws IOException;
    }

    private HttpServer server;
    private ExecutorService pool;
    private String base;
    private final AtomicInteger logins = new AtomicInteger();
    private final AtomicInteger chats = new AtomicInteger();
    private final List<String> auths = new CopyOnWriteArrayList<>();
    private final List<JsonNode> chatBodies = new CopyOnWriteArrayList<>();
    private final List<JsonNode> loginBodies = new CopyOnWriteArrayList<>();
    private volatile String loginResponse;
    private volatile Chat chat;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pool = Executors.newCachedThreadPool();
        server.setExecutor(pool);
        server.createContext("/api/genportal/auth/login", ex -> {
            loginBodies.add(OM.readTree(ex.getRequestBody().readAllBytes()));
            int n = logins.incrementAndGet();
            json(ex, 200, loginResponse != null ? loginResponse
                    : "{\"code\":0,\"errMsg\":\"success\",\"data\":{\"access_token\":\"tok-" + n + "\",\"refresh_token\":\"r\"}}");
        });
        server.createContext("/api/genportal/chat", ex -> {
            auths.add(ex.getRequestHeaders().getFirst("Authorization") + " | " + ex.getRequestHeaders().getFirst("Accept"));
            chatBodies.add(OM.readTree(ex.getRequestBody().readAllBytes()));
            chat.handle(ex, chats.incrementAndGet());
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/genportal";
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        pool.shutdownNow();
    }

    private GenonPortalClient client(int timeoutSec) {
        Wf9TestSupport.Props p = new Wf9TestSupport.Props();
        p.portalBaseUrl = base;
        p.portalTimeoutSec = timeoutSec;
        return new GenonPortalClient(p.build(), OM);
    }

    private static void stream(HttpExchange ex, String... parts) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "text/event-stream");
        ex.sendResponseHeaders(200, 0);
        try (OutputStream o = ex.getResponseBody()) {
            for (String p : parts) {
                o.write(p.getBytes(StandardCharsets.UTF_8));
                o.flush();
            }
        }
    }

    @Test
    @DisplayName("로그인 → chat SSE — result.text 를 쓰고, 요청은 endpoint · file_ids [] · message · Bearer · text/event-stream")
    void resultPreferred() {
        chat = (ex, n) -> stream(ex,
                sse("request_id", "req-1"), sse("session_id", "s-1"),
                sse("message_id", Map.of("role", "assistant", "id", "m-1")),
                sse("token", "{\"findings\""), sse("token", ": [], \"reviews\": []}"),
                sse("result", Map.of("text", "{\"findings\": [], \"reviews\": []}")),
                sse("duration", "2.5"), sse("end", "[DONE]"));

        String text = client(10).wf9("{\"function\":\"contact_risk_analysis\"}");

        assertThat(text).isEqualTo("{\"findings\": [], \"reviews\": []}");
        assertThat(logins.get()).isEqualTo(1);
        assertThat(loginBodies.get(0).path("user_id").asText()).isEqualTo("wf9-test-user");
        assertThat(auths.get(0)).isEqualTo("Bearer tok-1 | text/event-stream");
        JsonNode body = chatBodies.get(0);
        assertThat(body.path("endpoint").asText()).isEqualTo("pt6i_10rw_jsd9");
        assertThat(body.path("file_ids").isArray()).isTrue();
        assertThat(body.path("file_ids")).isEmpty();
        assertThat(body.path("message").asText()).isEqualTo("{\"function\":\"contact_risk_analysis\"}");
        assertThat(body.has("session_id")).as("새 대화").isFalse();
    }

    @Test
    @DisplayName("result 가 없으면 token 을 잇는다 — 여러 줄 data · 주석 줄 · 끝 빈 줄 없는 마지막 사건")
    void tokensJoined() {
        chat = (ex, n) -> stream(ex, ": keep-alive\n\n", "data: {\"event\":\"token\",\n", "data: \"data\":\"가\"}\n\n",
                "event: message\n", sse("token", "나"), "data: 깨진 줄\n\n", "data: {\"event\":\"end\",\"data\":\"[DONE]\"}");

        assertThat(client(10).wf9("m")).isEqualTo("가나");
    }

    @Test
    @DisplayName("[DONE] 만 있는 사건도 끝으로 본다")
    void bareDone() {
        chat = (ex, n) -> stream(ex, sse("token", "{\"findings\":[]}"), "data: [DONE]\n\n");

        assertThat(client(10).wf9("m")).isEqualTo("{\"findings\":[]}");
    }

    @Test
    @DisplayName("401 이면 한 번 다시 로그인해 새 토큰으로 보낸다 — 토큰은 캐시한다")
    void reloginOn401() {
        chat = (ex, n) -> {
            if (n == 1) {
                json(ex, 401, "{\"detail\":\"Not authenticated\"}");
                return;
            }
            stream(ex, sse("token", "{\"reviews\":[]}"), sse("end", "[DONE]"));
        };
        GenonPortalClient c = client(10);

        assertThat(c.wf9("m")).isEqualTo("{\"reviews\":[]}");
        assertThat(logins.get()).isEqualTo(2);
        assertThat(auths.get(1)).startsWith("Bearer tok-2");

        c.wf9("m");
        assertThat(logins.get()).as("다음 호출은 캐시한 토큰").isEqualTo(2);
    }

    @Test
    @DisplayName("다시 로그인해도 401 이면 PORTAL 실패(다시 하기)")
    void unauthorizedTwice() {
        chat = (ex, n) -> json(ex, 401, "{\"detail\":\"Not authenticated\"}");

        assertThatThrownBy(() -> client(10).wf9("m"))
                .isInstanceOfSatisfying(Wf9Failure.class, f -> {
                    assertThat(f.stage()).isEqualTo(Wf9Failure.Stage.PORTAL);
                    assertThat(f.retryable()).isTrue();
                });
        assertThat(logins.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("400 은 바로 포기 · 429 · 503 은 다시 하기 — 메시지에 상대 본문이 없다")
    void httpErrors() {
        chat = (ex, n) -> json(ex, n == 1 ? 400 : n == 2 ? 429 : 503, "{\"detail\":\"홍길동 메시지가 너무 깁니다\"}");
        GenonPortalClient c = client(10);

        assertThatThrownBy(() -> c.wf9("m")).isInstanceOfSatisfying(Wf9Failure.class, f -> {
            assertThat(f.retryable()).isFalse();
            assertThat(f.getMessage()).isEqualTo("제논 WF9 HTTP 400");
        });
        assertThatThrownBy(() -> c.wf9("m")).isInstanceOfSatisfying(Wf9Failure.class, f -> assertThat(f.retryable()).isTrue());
        assertThatThrownBy(() -> c.wf9("m")).isInstanceOfSatisfying(Wf9Failure.class, f -> {
            assertThat(f.retryable()).isTrue();
            assertThat(f.getMessage()).doesNotContain("홍길동");
        });
    }

    @Test
    @DisplayName("end 없이 끊김 · 빈 답 · error 사건 → 다시 하기")
    void brokenStreams() {
        chat = (ex, n) -> {
            switch (n) {
                case 1 -> stream(ex, sse("token", "{\"findings\":"));
                case 2 -> stream(ex, sse("session_id", "s"), sse("end", "[DONE]"));
                default -> stream(ex, sse("token", "가"), sse("error", Map.of("message", "홍길동 실패")), sse("end", "[DONE]"));
            }
        };
        GenonPortalClient c = client(10);

        assertThatThrownBy(() -> c.wf9("m")).hasMessageContaining("end 없이");
        assertThatThrownBy(() -> c.wf9("m")).hasMessageContaining("비었다");
        assertThatThrownBy(() -> c.wf9("m"))
                .isInstanceOfSatisfying(Wf9Failure.class, f -> {
                    assertThat(f.retryable()).isTrue();
                    assertThat(f.getMessage()).contains("error").doesNotContain("홍길동");
                });
    }

    @Test
    @DisplayName("SSE 가 end 전에 멈추면 시한에 끊고 다시 하기 — 작업자 스레드에 끼어들기가 남지 않는다")
    void stalledStreamTimesOut() {
        chat = (ex, n) -> {
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            OutputStream o = ex.getResponseBody();
            o.write(sse("token", "가").getBytes(StandardCharsets.UTF_8));
            o.flush();
            try {
                Thread.sleep(8_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ex.close();
        };
        long t0 = System.nanoTime();

        assertThatThrownBy(() -> client(1).wf9("m"))
                .isInstanceOfSatisfying(Wf9Failure.class, f -> {
                    assertThat(f.retryable()).isTrue();
                    assertThat(f.getMessage()).contains("시간 초과");
                });
        assertThat((System.nanoTime() - t0) / 1_000_000).as("시한(1초) 근처에서 끊는다").isLessThan(5_000);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    @DisplayName("로그인 실패(code ≠ 0) → PORTAL 실패 · 채팅은 부르지 않는다")
    void loginFailure() {
        loginResponse = "{\"code\":1,\"errMsg\":\"invalid\"}";
        chat = (ex, n) -> stream(ex, sse("end", "[DONE]"));

        assertThatThrownBy(() -> client(10).wf9("m"))
                .isInstanceOfSatisfying(Wf9Failure.class, f -> {
                    assertThat(f.getMessage()).contains("로그인 실패").contains("code 1");
                    assertThat(f.retryable()).isTrue();
                });
        assertThat(chats.get()).isZero();
    }

    @Test
    @DisplayName("MOCK — 네트워크 없이 가이드의 빈 결과 · REST 인데 계정이 없으면 막힘(값은 싣지 않는다)")
    void mockAndBlocker() {
        Wf9TestSupport.Props p = new Wf9TestSupport.Props();
        p.portalMode = RiskWf9Properties.PortalMode.MOCK;
        GenonPortalClient mock = new GenonPortalClient(p.build(), OM);
        assertThat(mock.blocker()).isNull();
        assertThat(mock.wf9("m")).isEqualTo(GenonPortalClient.MOCK_RESULT);

        Wf9TestSupport.Props q = new Wf9TestSupport.Props();
        q.portalPassword = "";
        GenonPortalClient noAccount = new GenonPortalClient(q.build(), OM);
        assertThat(noAccount.blocker()).contains("GENON_PORTAL_USER_ID");
        assertThatThrownBy(() -> noAccount.wf9("m")).isInstanceOf(Wf9Failure.class);
        assertThat(q.build().toString()).doesNotContain("wf9-test-user");
        assertThat(logins.get()).isZero();
    }
}
