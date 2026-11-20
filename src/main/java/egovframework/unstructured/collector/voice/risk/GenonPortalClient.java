package egovframework.unstructured.collector.voice.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 제논 채팅 WF9 — genportal 로그인(토큰 캐시 · 401 이면 한 번 다시 로그인) → {@code POST /chat} SSE 를 {@code end} 까지 읽는다.
 *
 * <p>가이드(GenOS 채팅 API 연동 · 음성 API 외부망 호출 가이드 11-05 · 11-19판 4장): 본문 {@code {endpoint, message, file_ids: []}},
 * 응답은 {@code data: {"event": …, "data": …}} 줄. {@code token} 을 이어 붙이고 {@code result.text} 가 있으면 그것을 쓴다.
 * {@code error} 사건은 코드 · 사유 낱말만 남기고 4xx 면 다시 하지 않는다({@link #errorEvent}).</p>
 *
 * <ul>
 *   <li>계정 · 토큰은 로그에 남기지 않는다. 리다이렉트를 따라가지 않는다(토큰이 다른 곳으로 가지 않게)</li>
 *   <li>보내는 {@code message} 는 커넥터가 가린 문서다 — 이 클래스는 내용을 로그에 남기지 않는다</li>
 *   <li>응답 전체(SSE end 까지)에 시한을 둔다 — 넘으면 읽던 흐름을 닫고 다시 하기로 넘긴다</li>
 * </ul>
 */
@Log4j2
@Component
public class GenonPortalClient {

    /** 가이드의 WF9 최종 응답 예시 — MOCK 이 돌려준다(가상 위험 신호를 지어내지 않는다). */
    static final String MOCK_RESULT = "{\"findings\": [], \"reviews\": []}";

    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "wf9-sse-watchdog");
        t.setDaemon(true);
        return t;
    });

    private final RiskWf9Properties props;
    private final ObjectMapper om;
    private final HttpClient http;
    private final String base;
    private final Object loginLock = new Object();
    private volatile String token;

    @Autowired
    public GenonPortalClient(RiskWf9Properties props, ObjectMapper objectMapper) {
        this.props = props;
        this.om = objectMapper;
        String b = props.portalBaseUrl() == null ? "" : props.portalBaseUrl().strip();
        this.base = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(Math.max(1, props.portalConnectTimeoutSec())))
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(ProxySelector.getDefault())
                .build();
    }

    /** 부를 수 없는 까닭 — 부를 수 있으면 null(MOCK 은 늘 부를 수 있다). */
    public String blocker() {
        if (props.portalMode() == RiskWf9Properties.PortalMode.MOCK) {
            return null;
        }
        if (!(base.startsWith("https://") || base.startsWith("http://"))) {
            return "제논 포털 주소가 http(s) 가 아니다(voice.risk-wf9.portal-base-url)";
        }
        if (!props.hasPortalAccount()) {
            return "제논 포털 계정이 비었다(GENON_PORTAL_USER_ID · GENON_PORTAL_PASSWORD)";
        }
        if (props.portalEndpoint() == null || props.portalEndpoint().isBlank()) {
            return "WF9 endpoint 가 비었다(voice.risk-wf9.portal-endpoint)";
        }
        return null;
    }

    public String mode() {
        return props.portalMode().name();
    }

    /**
     * WF9 한 번 — 답(글)을 돌려준다.
     *
     * @param message 커넥터가 가린 전처리 문서의 JSON 문자열
     * @throws Wf9Failure PORTAL — 400 계열(401 · 429 제외)은 다시 하지 않는다
     */
    public String wf9(String message) {
        if (props.portalMode() == RiskWf9Properties.PortalMode.MOCK) {
            return MOCK_RESULT;
        }
        String why = blocker();
        if (why != null) {
            throw Wf9Failure.retry(Wf9Failure.Stage.PORTAL, why);
        }
        String t = token();
        try {
            return chat(t, message);
        } catch (Unauthorized e) {
            log.info("[WF9] 제논 토큰 만료 — 다시 로그인");
            invalidate(t);
            try {
                return chat(token(), message);
            } catch (Unauthorized again) {
                invalidate(null);
                throw Wf9Failure.retry(Wf9Failure.Stage.PORTAL, "제논 WF9 인증 실패 — 다시 로그인해도 HTTP 401");
            }
        }
    }

    // ------------------------------------------------------------------ 로그인

    private String token() {
        String t = token;
        if (t != null) {
            return t;
        }
        synchronized (loginLock) {
            if (token == null) {
                token = login();
            }
            return token;
        }
    }

    private void invalidate(String used) {
        synchronized (loginLock) {
            if (used == null || used.equals(token)) {
                token = null;
            }
        }
    }

    private String login() {
        ObjectNode body = om.createObjectNode();
        body.put("user_id", props.portalUserId());
        body.put("password", props.portalPassword());
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(base + "/auth/login"))
                    .timeout(Duration.ofSeconds(Math.max(5, props.portalConnectTimeoutSec() * 3L)))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(om.writeValueAsBytes(body)))
                    .build();
        } catch (Exception e) {
            throw Wf9Failure.retry(Wf9Failure.Stage.PORTAL, "제논 로그인 요청을 만들지 못함 — " + e.getClass().getSimpleName());
        }
        HttpResponse<byte[]> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw Wf9Failure.retry(Wf9Failure.Stage.PORTAL, "제논 로그인 중 중단됨");
        } catch (IOException e) {
            throw Wf9Failure.retry(Wf9Failure.Stage.PORTAL, "제논 로그인 연결 실패 · 시간 초과 — " + e.getClass().getSimpleName());
        }
        JsonNode r = parse(res.body());
        String access = r.path("data").path("access_token").asText("");
        if (res.statusCode() != 200 || r.path("code").asInt(-1) != 0 || access.isBlank()) {
            throw Wf9Failure.retry(Wf9Failure.Stage.PORTAL, "제논 로그인 실패 — HTTP " + res.statusCode()
                    + (r.path("code").isNumber() ? " · code " + r.path("code").asInt() : ""));
        }
        log.info("[WF9] 제논 로그인 — 토큰 받음");
        return access;
    }

    // ------------------------------------------------------------------ 채팅 SSE

    private static final class Unauthorized extends RuntimeException {
        Unauthorized() {
            super("401", null, false, false);
        }
    }

    private String chat(String bearer, String message) {
        ObjectNode body = om.createObjectNode();
        body.put("message", message);
        body.putArray("file_ids");
        body.put("endpoint", props.portalEndpoint().strip());
        int limitSec = Math.max(1, props.portalTimeoutSec());
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(base + "/chat"))
                    .timeout(Duration.ofSeconds(limitSec))
                    .header("Authorization", "Bearer " + bearer)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(om.writeValueAsBytes(body)))
                    .build();
        } catch (Exception e) {
            throw Wf9Failure.retry(Wf9Failure.Stage.PORTAL, "WF9 요청을 만들지 못함 — " + e.getClass().getSimpleName());
        }
        long t0 = System.nanoTime();
        HttpResponse<InputStream> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpConnectTimeoutException e) {
            throw Wf9Failure.retry(Wf9Failure.Stage.PORTAL, "제논 WF9 연결 시간 초과");
        } catch (HttpTimeoutException e) {
            throw Wf9Failure.retry(Wf9Failure.Stage.PORTAL, "제논 WF9 응답 시간 초과 — " + limitSec + "초");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw Wf9Failure.retry(Wf9Failure.Stage.PORTAL, "제논 WF9 호출 중 중단됨");
        } catch (IOException e) {
            throw Wf9Failure.retry(Wf9Failure.Stage.PORTAL, "제논 WF9 연결 실패 — " + e.getClass().getSimpleName());
        }
        int status = res.statusCode();
        if (status != 200) {
            closeQuietly(res.body());
            if (status == 401) {
                throw new Unauthorized();
            }
            String msg = "제논 WF9 HTTP " + status;
            if (status >= 400 && status < 500 && status != 408 && status != 429) {
                throw Wf9Failure.permanent(Wf9Failure.Stage.PORTAL, msg);
            }
            throw Wf9Failure.retry(Wf9Failure.Stage.PORTAL, msg);
        }
        long leftMs = Math.max(1, limitSec * 1000L - (System.nanoTime() - t0) / 1_000_000);
        InputStream in = res.body();
        // 시한 감시 — 흐름을 닫고 읽는 스레드에 끼어든다(막힌 읽기는 닫기만으로 깨지 않을 수 있다).
        //   다 읽은 뒤에는 끼어들지 않게 잠금 안에서 '끝남'을 먼저 본다
        Thread reader = Thread.currentThread();
        Object lock = new Object();
        AtomicBoolean done = new AtomicBoolean();
        AtomicBoolean timedOut = new AtomicBoolean();
        ScheduledFuture<?> dog = WATCHDOG.schedule(() -> {
            synchronized (lock) {
                if (done.get()) {
                    return;
                }
                timedOut.set(true);
                closeQuietly(in);
                reader.interrupt();
            }
        }, leftMs, TimeUnit.MILLISECONDS);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            Sse sse = read(r);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            log.info("[WF9] 제논 WF9 응답 — {}자 · 사건 {}개 · {}ms", sse.text().length(), sse.events(), ms);
            return sse.text();
        } catch (IOException e) {
            if (timedOut.get()) {
                throw Wf9Failure.retry(Wf9Failure.Stage.PORTAL, "제논 WF9 응답 시간 초과 — " + limitSec + "초(end 전)");
            }
            throw Wf9Failure.retry(Wf9Failure.Stage.PORTAL, "제논 WF9 응답을 읽다 끊김 — " + e.getClass().getSimpleName());
        } finally {
            synchronized (lock) {
                done.set(true);
            }
            dog.cancel(false);
            if (timedOut.get()) {
                Thread.interrupted();   // 감시가 건 끼어들기를 지운다 — 작업자의 다음 대기가 깨지지 않게
            }
        }
    }

    /** SSE 를 읽은 결과 — 답 글과 받은 이벤트 수. */
    record Sse(String text, int events) {}

    /**
     * SSE 를 {@code end} 까지 읽는다. 사건은 빈 줄로 끝나고, 한 사건의 {@code data:} 줄이 여럿이면 줄바꿈으로 잇는다.
     * {@code data} 가 JSON 이 아니면 그 사건은 버린다. {@code [DONE]} 만 있는 사건도 끝으로 본다.
     *
     * @throws Wf9Failure end 없이 끝남 · 빈 답 · {@code error} 사건
     */
    Sse read(BufferedReader r) throws IOException {
        StringBuilder tokens = new StringBuilder();
        String[] result = {null};
        int[] events = {0};
        List<String> data = new ArrayList<>();
        boolean ended = false;
        String line;
        while (!ended && (line = r.readLine()) != null) {
            if (line.isEmpty()) {
                ended = dispatch(data, tokens, result, events);
            } else if (line.startsWith("data:")) {
                String v = line.substring(5);
                data.add(v.startsWith(" ") ? v.substring(1) : v);
            }
            // 주석(:) · event: · id: · retry: 는 쓰지 않는다 — 사건 이름은 data JSON 안에 있다
        }
        if (!ended && !data.isEmpty()) {
            ended = dispatch(data, tokens, result, events);
        }
        if (!ended) {
            throw Wf9Failure.retry(Wf9Failure.Stage.PORTAL, "제논 WF9 응답이 end 없이 끝났다");
        }
        String text = result[0] != null && !result[0].isBlank() ? result[0] : tokens.toString();
        if (text.isBlank()) {
            throw Wf9Failure.retry(Wf9Failure.Stage.PORTAL, "제논 WF9 답이 비었다");
        }
        return new Sse(text, events[0]);
    }

    /** 사건 하나를 처리한다 — 끝이면 true. */
    private boolean dispatch(List<String> data, StringBuilder tokens, String[] result, int[] events) {
        if (data.isEmpty()) {
            return false;
        }
        String payload = String.join("\n", data);
        data.clear();
        if ("[DONE]".equals(payload.strip())) {
            return true;
        }
        JsonNode n;
        try {
            n = om.readTree(payload);
        } catch (Exception e) {
            return false;
        }
        if (n == null || !n.isObject()) {
            return false;
        }
        events[0]++;
        JsonNode d = n.path("data");
        switch (n.path("event").asText("")) {
            case "token" -> {
                if (d.isTextual()) {
                    tokens.append(d.asText());
                }
            }
            case "result" -> {
                if (d.isObject() && d.path("text").isTextual()) {
                    result[0] = d.path("text").asText();
                } else if (d.isTextual()) {
                    result[0] = d.asText();
                }
            }
            case "error" -> throw errorEvent(d);
            case "end" -> {
                return true;
            }
            default -> {
                // request_id · session_id · message_id · messages · duration — 쓰지 않는다
            }
        }
        return false;
    }

    /** 오류 사건 설명 끝의 {@code (코드 422·invalid_input)} — 숫자 코드와 사유 낱말(영문 · 숫자 · _ . -)만 꺼낸다. */
    private static final Pattern ERROR_REASON =
            Pattern.compile("코드\\s*(\\d{3})\\s*[·・:/,\\-]\\s*([A-Za-z][A-Za-z0-9_.\\-]{0,39})");

    /**
     * {@code error} 사건 → 실패. HTTP 는 200 인 채 SSE 안에서 온다.
     * 실측(2026-11-20 가이드 11-19판): {@code {"code": 422, "message": "입력 형식이 올바르지 않습니다.\n\n(코드 422·invalid_input)"}}.
     *
     * <p>메시지에는 <b>코드와 사유 낱말만</b> 싣는다 — 설명 글은 보낸 문서 조각을 되풀이할 수 있어 싣지 않는다.
     * 4xx(408 · 429 제외)는 같은 입력을 다시 보내도 같으므로 HTTP 오류와 같이 바로 포기하고, 코드가 없거나 그 밖이면 다시 하기로 넘긴다.</p>
     */
    static Wf9Failure errorEvent(JsonNode d) {
        String message = d != null && d.path("message").isTextual() ? d.path("message").asText() : "";
        Matcher m = ERROR_REASON.matcher(message);
        boolean found = m.find();
        int code = -1;
        JsonNode c = d == null ? null : d.path("code");
        if (c != null && c.isIntegralNumber()) {
            code = c.asInt();
        } else if (c != null && c.isTextual() && c.asText().strip().matches("\\d{3}")) {
            code = Integer.parseInt(c.asText().strip());
        } else if (found) {
            code = Integer.parseInt(m.group(1));
        }
        String msg = "제논 WF9 오류 사건(error)" + (code > 0 ? " — " + code : "") + (found ? " · " + m.group(2) : "");
        if (code >= 400 && code < 500 && code != 408 && code != 429) {
            return Wf9Failure.permanent(Wf9Failure.Stage.PORTAL, msg);
        }
        return Wf9Failure.retry(Wf9Failure.Stage.PORTAL, msg);
    }

    private JsonNode parse(byte[] raw) {
        try {
            JsonNode n = raw == null || raw.length == 0 ? null : om.readTree(raw);
            return n == null ? om.createObjectNode() : n;
        } catch (Exception e) {
            return om.createObjectNode();
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            if (in != null) {
                in.close();
            }
        } catch (IOException ignored) {
            // 닫기 실패는 무시한다
        }
    }
}
