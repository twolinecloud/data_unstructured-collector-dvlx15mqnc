package egovframework.unstructured.collector.voice.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
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
import static egovframework.unstructured.collector.voice.risk.Wf9TestSupport.send;
import static egovframework.unstructured.collector.voice.risk.Wf9TestSupport.sse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 제논 WF9 — 가짜 Code Serving({@code /api/gateway/code_serving/709/10721/run} SSE)으로만. AuthKey 는 시험용 가짜 값이다. */
class GenonPortalClientTest {

    interface Run {
        void handle(HttpExchange ex, int call) throws IOException;
    }

    private static final String RUN_PATH = "/api/gateway/code_serving/709/10721/run";

    private HttpServer server;
    private ExecutorService pool;
    private String base;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger stray = new AtomicInteger();
    private final List<String> heads = new CopyOnWriteArrayList<>();
    private final List<String> methods = new CopyOnWriteArrayList<>();
    private final List<JsonNode> bodies = new CopyOnWriteArrayList<>();
    private volatile Run run;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pool = Executors.newCachedThreadPool();
        server.setExecutor(pool);
        server.createContext(RUN_PATH, ex -> {
            methods.add(ex.getRequestMethod());
            heads.add(ex.getRequestHeaders().getFirst("Authorization") + " | " + ex.getRequestHeaders().getFirst("Content-Type")
                    + " | " + ex.getRequestHeaders().getFirst("Accept"));
            bodies.add(OM.readTree(ex.getRequestBody().readAllBytes()));
            run.handle(ex, calls.incrementAndGet());
        });
        // 옛 genportal 경로 · 다른 서빙 — 오면 안 된다
        server.createContext("/", ex -> {
            stray.incrementAndGet();
            json(ex, 404, "{}");
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/gateway/code_serving";
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        pool.shutdownNow();
    }

    private GenonPortalClient client(int timeoutSec) {
        Wf9TestSupport.Props p = new Wf9TestSupport.Props();
        p.servingBaseUrl = base;
        p.portalTimeoutSec = timeoutSec;
        return new GenonPortalClient(p.build(), OM);
    }

    private static void stream(HttpExchange ex, String... parts) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        ex.sendResponseHeaders(200, 0);
        try (OutputStream o = ex.getResponseBody()) {
            for (String p : parts) {
                o.write(p.getBytes(StandardCharsets.UTF_8));
                o.flush();
            }
        }
    }

    @Test
    @DisplayName("POST …/709/10721/run — Bearer AuthKey · application/json · text/event-stream · 본문 endpoint · message · file_ids [] · result.text 우선")
    void resultPreferred() {
        run = (ex, n) -> stream(ex,
                sse("request_id", "req-1"), sse("session_id", "s-1"),
                sse("message_id", Map.of("role", "assistant", "id", "m-1")),
                sse("token", "{\"findings\""), sse("token", ": [], \"reviews\": []}"),
                sse("result", Map.of("text", "{\"findings\": [], \"reviews\": []}")),
                sse("duration", "2.5"), sse("end", "[DONE]"));

        String text = client(10).wf9("{\"function\":\"contact_risk_analysis\"}");

        assertThat(text).isEqualTo("{\"findings\": [], \"reviews\": []}");
        assertThat(methods).containsExactly("POST");
        assertThat(heads.get(0)).isEqualTo("Bearer wf9-test-key | application/json | text/event-stream");
        JsonNode body = bodies.get(0);
        assertThat(body.path("endpoint").asText()).isEqualTo("pt6i_10rw_jsd9");
        assertThat(body.path("file_ids").isArray()).isTrue();
        assertThat(body.path("file_ids")).isEmpty();
        assertThat(body.path("message").asText()).isEqualTo("{\"function\":\"contact_risk_analysis\"}");
        assertThat(body.has("session_id")).as("새 대화").isFalse();
        assertThat(stray.get()).as("로그인 · 다른 경로 호출 없음").isZero();
    }

    @Test
    @DisplayName("result 가 없으면 token 을 잇는다 — 여러 줄 data · 주석 줄 · 끝 빈 줄 없는 마지막 사건")
    void tokensJoined() {
        run = (ex, n) -> stream(ex, ": keep-alive\n\n", "data: {\"event\":\"token\",\n", "data: \"data\":\"가\"}\n\n",
                "event: message\n", sse("token", "나"), "data: 깨진 줄\n\n", "data: {\"event\":\"end\",\"data\":\"[DONE]\"}");

        assertThat(client(10).wf9("m")).isEqualTo("가나");
    }

    @Test
    @DisplayName("[DONE] 만 있는 사건도 끝으로 본다")
    void bareDone() {
        run = (ex, n) -> stream(ex, sse("token", "{\"findings\":[]}"), "data: [DONE]\n\n");

        assertThat(client(10).wf9("m")).isEqualTo("{\"findings\":[]}");
    }

    @Test
    @DisplayName("401 · 403 은 다시 하기(AuthKey 를 고치면 풀린다) · 다시 로그인하지 않는다 · 메시지엔 상태 · 코드 낱말 · 볼 곳만")
    void authErrors() {
        run = (ex, n) -> json(ex, n == 1 ? 401 : 403,
                n == 1 ? "{\"detail\":\"홍길동 Invalid token\",\"error\":\"invalid_token\"}" : "{\"message\":\"forbidden 홍길동\"}");
        GenonPortalClient c = client(10);

        assertThatThrownBy(() -> c.wf9("m")).isInstanceOfSatisfying(Wf9Failure.class, f -> {
            assertThat(f.stage()).isEqualTo(Wf9Failure.Stage.PORTAL);
            assertThat(f.retryable()).isTrue();
            assertThat(f.getMessage()).isEqualTo("제논 WF9 HTTP 401 · invalid_token(AuthKey 확인 — GENON_WF9_AUTH_KEY · 서빙 709)");
        });
        assertThatThrownBy(() -> c.wf9("m")).isInstanceOfSatisfying(Wf9Failure.class, f -> {
            assertThat(f.retryable()).isTrue();
            assertThat(f.getMessage()).startsWith("제논 WF9 HTTP 403").doesNotContain("홍길동").doesNotContain("wf9-test-key");
        });
        assertThat(calls.get()).as("한 번씩만 — 재로그인 없음").isEqualTo(2);
    }

    @Test
    @DisplayName("400 · 413 · 415 · 422 는 바로 포기 · 404 · 408 · 429 · 500 · 502 · 503 은 다시 하기 — 메시지에 상대 설명 글이 없다")
    void httpErrors() {
        int[] statuses = {400, 413, 415, 422, 404, 408, 429, 500, 502, 503};
        run = (ex, n) -> json(ex, statuses[n - 1], "{\"detail\":\"홍길동 메시지가 너무 깁니다 (코드 422·invalid_input)\"}");
        GenonPortalClient c = client(10);

        for (int s : statuses) {
            assertThatThrownBy(() -> c.wf9("m")).isInstanceOfSatisfying(Wf9Failure.class, f -> {
                assertThat(f.retryable()).as("HTTP " + s).isEqualTo(!GenonPortalClient.permanentStatus(s));
                assertThat(f.getMessage()).startsWith("제논 WF9 HTTP " + s).doesNotContain("홍길동").doesNotContain("메시지가");
            });
        }
        assertThat(GenonPortalClient.permanentStatus(400)).isTrue();
        assertThat(GenonPortalClient.permanentStatus(422)).isTrue();
        assertThat(GenonPortalClient.permanentStatus(401)).isFalse();
        assertThat(GenonPortalClient.permanentStatus(404)).isFalse();
        assertThat(GenonPortalClient.permanentStatus(429)).isFalse();
    }

    @Test
    @DisplayName("404 — 서빙 ID · 리비전을 보라고 적는다 · 본문 없는 오류 · 깨진 본문도 상태만")
    void notFoundAndOddBodies() {
        run = (ex, n) -> {
            switch (n) {
                case 1 -> json(ex, 404, "{\"detail\":\"Not Found\"}");
                case 2 -> send(ex, 502, "text/html", "<html>홍길동 Bad Gateway</html>");
                default -> send(ex, 500, "application/json", "");
            }
        };
        GenonPortalClient c = client(10);

        assertThatThrownBy(() -> c.wf9("m")).hasMessage("제논 WF9 HTTP 404(서빙 주소 확인 — 709/10721)");
        assertThatThrownBy(() -> c.wf9("m")).hasMessage("제논 WF9 HTTP 502");
        assertThatThrownBy(() -> c.wf9("m")).hasMessage("제논 WF9 HTTP 500");
    }

    @Test
    @DisplayName("HTTP 200 인데 SSE 가 아니다(JSON 오류) — 코드로 판정 · 설명 글은 싣지 않는다")
    void okButNotSse() {
        run = (ex, n) -> json(ex, 200, n == 1
                ? "{\"code\":422,\"message\":\"홍길동 입력 형식 (코드 422·invalid_input)\"}"
                : "{\"result\":\"홍길동\"}");
        GenonPortalClient c = client(10);

        assertThatThrownBy(() -> c.wf9("m")).isInstanceOfSatisfying(Wf9Failure.class, f -> {
            assertThat(f.retryable()).isFalse();
            assertThat(f.getMessage()).isEqualTo("제논 WF9 응답이 SSE 가 아니다(HTTP 200 · application/json) — 422 · invalid_input");
        });
        assertThatThrownBy(() -> c.wf9("m")).isInstanceOfSatisfying(Wf9Failure.class, f -> {
            assertThat(f.retryable()).isTrue();
            assertThat(f.getMessage()).doesNotContain("홍길동");
        });
    }

    @Test
    @DisplayName("end 없이 끊김 · 빈 답 · error 사건 → 다시 하기")
    void brokenStreams() {
        run = (ex, n) -> {
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
    @DisplayName("error 사건(실측 모양 {code, message}) — 400 · 422 는 바로 포기 · 503 · 429 · 401 은 다시 하기 · 메시지는 코드 · 사유 낱말만")
    void errorEventCodes() throws Exception {
        run = (ex, n) -> {
            switch (n) {
                // 2026-11-20 실측 — HTTP 200 SSE 안에 온다
                case 1 -> stream(ex, sse("session_id", "s"), sse("token", "{\"findings\":"),
                        sse("error", Map.of("code", 422, "message", "입력 형식이 올바르지 않습니다.\n\n(코드 422·invalid_input)")),
                        sse("end", "[DONE]"));
                case 2 -> stream(ex, sse("error", Map.of("code", 503, "message", "홍길동 처리 중 오류\n\n(코드 503·upstream_unavailable)")));
                case 3 -> stream(ex, sse("error", Map.of("code", "429", "message", "잠시 후 다시")));
                default -> stream(ex, sse("error", Map.of("message", "홍길동 문장 (코드 400·bad_request)")));
            }
        };
        GenonPortalClient c = client(10);

        assertThatThrownBy(() -> c.wf9("m")).isInstanceOfSatisfying(Wf9Failure.class, f -> {
            assertThat(f.stage()).isEqualTo(Wf9Failure.Stage.PORTAL);
            assertThat(f.retryable()).as("같은 입력이면 다시 보내도 422").isFalse();
            assertThat(f.getMessage()).isEqualTo("제논 WF9 오류 사건(error) — 422 · invalid_input");
        });
        assertThatThrownBy(() -> c.wf9("m")).isInstanceOfSatisfying(Wf9Failure.class, f -> {
            assertThat(f.retryable()).isTrue();
            assertThat(f.getMessage()).isEqualTo("제논 WF9 오류 사건(error) — 503 · upstream_unavailable");
        });
        assertThatThrownBy(() -> c.wf9("m")).isInstanceOfSatisfying(Wf9Failure.class, f -> {
            assertThat(f.retryable()).as("글로 온 429 도 혼잡 — 다시 하기").isTrue();
            assertThat(f.getMessage()).isEqualTo("제논 WF9 오류 사건(error) — 429");
        });
        assertThatThrownBy(() -> c.wf9("m")).isInstanceOfSatisfying(Wf9Failure.class, f -> {
            assertThat(f.retryable()).as("code 필드가 없으면 설명 끝 (코드 400·…) 으로").isFalse();
            assertThat(f.getMessage()).isEqualTo("제논 WF9 오류 사건(error) — 400 · bad_request").doesNotContain("홍길동");
        });

        Wf9Failure text = GenonPortalClient.errorEvent(OM.readTree("\"홍길동 오류\""));
        assertThat(text.retryable()).as("모양을 모르면 다시 하기").isTrue();
        assertThat(text.getMessage()).isEqualTo("제논 WF9 오류 사건(error)");
        assertThat(GenonPortalClient.errorEvent(OM.missingNode()).retryable()).isTrue();
        assertThat(GenonPortalClient.errorEvent(OM.readTree("{\"code\":408}")).retryable()).as("408 은 시간 초과 — 다시 하기").isTrue();
        assertThat(GenonPortalClient.errorEvent(OM.readTree("{\"code\":401}")).retryable()).as("401 은 AuthKey — 다시 하기").isTrue();
        assertThat(GenonPortalClient.errorEvent(OM.readTree("{\"error\":{\"code\":\"413\",\"type\":\"payload_too_large\"}}"))
                .getMessage()).isEqualTo("제논 WF9 오류 사건(error) — 413 · payload_too_large");
    }

    @Test
    @DisplayName("SSE 가 end 전에 멈추면 시한에 끊고 다시 하기 — 작업자 스레드에 끼어들기가 남지 않는다")
    void stalledStreamTimesOut() {
        run = (ex, n) -> {
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
    @DisplayName("연결 거부 → 다시 하기 · 예외 이름만")
    void connectionRefused() throws IOException {
        int port;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            port = s.getLocalPort();
        }
        Wf9TestSupport.Props p = new Wf9TestSupport.Props();
        p.servingBaseUrl = "http://127.0.0.1:" + port + "/api/gateway/code_serving";
        assertThatThrownBy(() -> new GenonPortalClient(p.build(), OM).wf9("m"))
                .isInstanceOfSatisfying(Wf9Failure.class, f -> {
                    assertThat(f.retryable()).isTrue();
                    assertThat(f.getMessage()).startsWith("제논 WF9 연결 실패 — ");
                });
    }

    @Test
    @DisplayName("MOCK — 네트워크 없이 가이드의 빈 결과 · REST 막힘: AuthKey 없음 · 서빙 ID/리비전 숫자 아님 · 키에 공백 · 주소 · endpoint 빔 — 키 값은 싣지 않는다")
    void mockAndBlockers() {
        Wf9TestSupport.Props p = new Wf9TestSupport.Props();
        p.portalMode = RiskWf9Properties.PortalMode.MOCK;
        p.authKey = "";
        GenonPortalClient mock = new GenonPortalClient(p.build(), OM);
        assertThat(mock.blocker()).isNull();
        assertThat(mock.wf9("m")).isEqualTo(GenonPortalClient.MOCK_RESULT);

        Wf9TestSupport.Props noKey = new Wf9TestSupport.Props();
        noKey.servingBaseUrl = base;
        noKey.authKey = "  ";
        GenonPortalClient c = new GenonPortalClient(noKey.build(), OM);
        assertThat(c.blocker()).contains("GENON_WF9_AUTH_KEY");
        assertThatThrownBy(() -> c.wf9("m")).isInstanceOfSatisfying(Wf9Failure.class, f -> assertThat(f.retryable()).isTrue());

        Wf9TestSupport.Props badRev = new Wf9TestSupport.Props();
        badRev.servingRev = "10721/../1";
        assertThat(new GenonPortalClient(badRev.build(), OM).blocker()).contains("GENON_WF9_SERVING_REV");

        Wf9TestSupport.Props spaced = new Wf9TestSupport.Props();
        spaced.authKey = "abc def";
        assertThat(new GenonPortalClient(spaced.build(), OM).blocker()).contains("공백").doesNotContain("abc");

        Wf9TestSupport.Props ftp = new Wf9TestSupport.Props();
        ftp.servingBaseUrl = "ftp://x";
        assertThat(new GenonPortalClient(ftp.build(), OM).blocker()).contains("http(s)");

        Wf9TestSupport.Props noEp = new Wf9TestSupport.Props();
        noEp.portalEndpoint = " ";
        assertThat(new GenonPortalClient(noEp.build(), OM).blocker()).contains("GENON_WF9_ENDPOINT");

        RiskWf9Properties full = new Wf9TestSupport.Props().build();
        assertThat(full.toString()).doesNotContain("wf9-test-key").contains("authKey=있음").contains("/709/10721/run");
        assertThat(new GenonPortalClient(full, OM).target()).endsWith("/api/gateway/code_serving/709/10721/run");
        assertThat(calls.get()).isZero();
    }

    @Test
    @DisplayName("AuthKey 만료일(가이드 v4 1쪽 2026-12-11) — 남은 날 · 당일 0 · 지나면 음수 · 비었거나 형식 틀리면 null")
    void authKeyExpiry() {
        RiskWf9Properties p = new Wf9TestSupport.Props().build();
        assertThat(p.authKeyDaysLeft(java.time.LocalDate.of(2026, 11, 27))).isEqualTo(14L);
        assertThat(p.authKeyDaysLeft(java.time.LocalDate.of(2026, 12, 11))).isZero();
        assertThat(p.authKeyDaysLeft(java.time.LocalDate.of(2026, 12, 12))).isEqualTo(-1L);
        Wf9TestSupport.Props blank = new Wf9TestSupport.Props();
        blank.authKeyExpiresOn = " ";
        assertThat(blank.build().authKeyDaysLeft(java.time.LocalDate.of(2026, 11, 27))).isNull();
        Wf9TestSupport.Props bad = new Wf9TestSupport.Props();
        bad.authKeyExpiresOn = "2026/12/11";
        assertThat(bad.build().authKeyDaysLeft(java.time.LocalDate.of(2026, 11, 27))).isNull();
    }

    @Test
    @DisplayName("빈 문서는 보내지 않는다 — 바로 포기")
    void blankMessage() {
        run = (ex, n) -> stream(ex, sse("end", "[DONE]"));
        assertThatThrownBy(() -> client(10).wf9(" ")).isInstanceOfSatisfying(Wf9Failure.class,
                f -> assertThat(f.retryable()).isFalse());
        assertThat(calls.get()).isZero();
    }

    @Test
    @DisplayName("로그에 AuthKey · 보낸 문서 · 상대 설명 글이 남지 않는다(DEBUG 까지)")
    void logsCarryNoSecretsOrContent() {
        Logger logger = (Logger) LogManager.getLogger(GenonPortalClient.class);
        List<String> lines = new CopyOnWriteArrayList<>();
        AbstractAppender app = new AbstractAppender("wf9-capture", null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                lines.add(event.getMessage().getFormattedMessage());
            }
        };
        app.start();
        Level before = logger.getLevel();
        logger.addAppender(app);
        logger.setLevel(Level.DEBUG);
        try {
            run = (ex, n) -> stream(ex, sse("result", Map.of("text", "{\"findings\":[]}")), sse("end", "[DONE]"));
            client(10).wf9("{\"input\":\"[NAME_1] 비밀 문장\"}");
            run = (ex, n) -> json(ex, 401, "{\"detail\":\"홍길동 비밀 문장\"}");
            assertThatThrownBy(() -> client(10).wf9("{\"input\":\"[NAME_1] 비밀 문장\"}")).isInstanceOf(Wf9Failure.class);
        } finally {
            logger.removeAppender(app);
            logger.setLevel(before);
            app.stop();
        }
        assertThat(lines).isNotEmpty();
        assertThat(String.join("\n", lines)).doesNotContain("wf9-test-key").doesNotContain("비밀").doesNotContain("[NAME_1]")
                .doesNotContain("홍길동");
    }
}
