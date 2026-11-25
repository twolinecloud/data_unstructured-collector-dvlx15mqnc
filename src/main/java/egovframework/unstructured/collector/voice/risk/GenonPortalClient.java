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
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 제논 WF9(접견·전화 위험 신호 분석) — Code Serving 을 바로 부른다. {@code POST {servingBaseUrl}/{servingId}/{servingRev}/run}
 * SSE 를 {@code end} 까지 읽는다.
 *
 * <p>2026-11-25 전환(대시보드 AI API 연동 가이드 v4 · 제논 메일 11-25): 예전엔 genportal 에 공통 계정으로 로그인해 받은 토큰으로
 * {@code /chat} 을 불렀다. 이제 서빙별 <b>AuthKey</b> 를 {@code Authorization: Bearer} 로 싣고 서빙 주소를 바로 부른다 — 로그인 · 토큰
 * 캐시 · 401 재로그인이 없다. 본문 {@code {endpoint, message, file_ids: []}} 과 응답({@code data: {"event": …, "data": …}} 줄 ·
 * {@code token} 이어 붙이기 · {@code result.text} 우선 · {@code end})은 그대로다.</p>
 *
 * <ul>
 *   <li><b>AuthKey 는 로그 · 예외 메시지에 남기지 않는다.</b> 리다이렉트를 따라가지 않는다(키가 다른 곳으로 가지 않게)</li>
 *   <li>보내는 {@code message} 는 커넥터가 가린 문서다 — 게이트웨이가 요청 본문을 남길 수 있어 가린 문서만 보내고,
 *       이 클래스는 본문을 로그에 남기지 않는다(크기 · 주소만)</li>
 *   <li>상대 오류 본문은 <b>코드 낱말만</b> 꺼낸다 — 설명 글은 보낸 문서 조각을 되풀이할 수 있다</li>
 *   <li>응답 전체(SSE end 까지)에 시한을 둔다 — 넘으면 읽던 흐름을 닫고 다시 하기로 넘긴다</li>
 * </ul>
 *
 * <p>상태 코드 — {@link #permanentStatus}: 400 · 413 · 415 · 422 는 같은 입력이면 다시 보내도 같아 바로 포기한다.
 * 401 · 403(AuthKey) · 404(서빙 ID · 리비전)는 설정을 고치면 풀리므로 다시 하기로 두고 메시지에 무엇을 볼지 적는다.
 * 408 · 429 · 5xx · 그 밖도 다시 하기.</p>
 */
@Log4j2
@Component
public class GenonPortalClient {

    /** 가이드의 WF9 최종 응답 예시 — MOCK 이 돌려준다(가상 위험 신호를 지어내지 않는다). */
    static final String MOCK_RESULT = "{\"findings\": [], \"reviews\": []}";

    /** 오류 응답 본문은 이만큼만 읽는다 — 코드 낱말만 쓰므로 길게 받을 까닭이 없다. */
    private static final int ERROR_BODY_MAX = 8 * 1024;

    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "wf9-sse-watchdog");
        t.setDaemon(true);
        return t;
    });

    private final RiskWf9Properties props;
    private final ObjectMapper om;
    private final HttpClient http;

    @Autowired
    public GenonPortalClient(RiskWf9Properties props, ObjectMapper objectMapper) {
        this.props = props;
        this.om = objectMapper;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(Math.max(1, props.portalConnectTimeoutSec())))
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(ProxySelector.getDefault())
                .build();
    }

    /** 부를 수 없는 까닭 — 부를 수 있으면 null(MOCK 은 늘 부를 수 있다). 값(키)은 싣지 않는다. */
    public String blocker() {
        if (props.portalMode() == RiskWf9Properties.PortalMode.MOCK) {
            return null;
        }
        String base = props.servingBaseUrl();
        if (!(base.startsWith("https://") || base.startsWith("http://"))) {
            return "제논 Code Serving 주소가 http(s) 가 아니다(GENON_WF9_SERVING_BASE_URL)";
        }
        if (!props.servingPathValid()) {
            return "제논 WF9 서빙 ID · 리비전은 숫자여야 한다(GENON_WF9_SERVING_ID · GENON_WF9_SERVING_REV)";
        }
        if (!props.hasAuthKey()) {
            return "제논 WF9 AuthKey 가 비었다(GENON_WF9_AUTH_KEY)";
        }
        if (!props.authKey().chars().allMatch(c -> c > 0x20 && c < 0x7f)) {
            return "제논 WF9 AuthKey 에 공백 · 제어 문자 · 한글이 섞였다(GENON_WF9_AUTH_KEY 값 확인)";
        }
        if (props.portalEndpoint().isEmpty()) {
            return "WF9 endpoint 가 비었다(GENON_WF9_ENDPOINT)";
        }
        return null;
    }

    public String mode() {
        return props.portalMode().name();
    }

    /** 부를 주소(키 없음) — 기동 로그 · 문서용. */
    public String target() {
        return props.servingRunUrl();
    }

    /**
     * WF9 한 번 — 답(글)을 돌려준다.
     *
     * @param message 커넥터가 가린 전처리 문서의 JSON 문자열({@link Wf9InputGuard} 를 지난 것)
     * @throws Wf9Failure PORTAL — 400 · 413 · 415 · 422(SSE error 사건 포함)는 다시 하지 않는다
     */
    public String wf9(String message) {
        if (props.portalMode() == RiskWf9Properties.PortalMode.MOCK) {
            return MOCK_RESULT;
        }
        String why = blocker();
        if (why != null) {
            throw Wf9Failure.retry(Wf9Failure.Stage.PORTAL, why);
        }
        if (message == null || message.isBlank()) {
            throw Wf9Failure.permanent(Wf9Failure.Stage.PORTAL, "제논 WF9 에 보낼 문서가 비었다");
        }
        return run(message);
    }

    // ------------------------------------------------------------------ Code Serving SSE

    private String run(String message) {
        ObjectNode body = om.createObjectNode();
        body.put("endpoint", props.portalEndpoint());
        body.put("message", message);
        body.putArray("file_ids");
        int limitSec = Math.max(1, props.portalTimeoutSec());
        byte[] raw;
        HttpRequest req;
        try {
            raw = om.writeValueAsBytes(body);
            req = HttpRequest.newBuilder(URI.create(props.servingRunUrl()))
                    .timeout(Duration.ofSeconds(limitSec))
                    .header("Authorization", "Bearer " + props.authKey())
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(raw))
                    .build();
        } catch (Exception e) {
            // 예외 메시지에 주소 · 머리글 값이 실릴 수 있어 이름만
            throw Wf9Failure.retry(Wf9Failure.Stage.PORTAL, "WF9 요청을 만들지 못함 — " + e.getClass().getSimpleName());
        }
        log.debug("[WF9] Code Serving 호출 — {} · 본문 {}바이트", props.servingRunUrl(), raw.length);
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
            throw httpError(status, readLimited(res.body()));
        }
        String ctype = contentType(res);
        if (!ctype.isEmpty() && !ctype.equals("text/event-stream")) {
            // 200 인데 SSE 가 아니다 — 게이트웨이가 JSON 으로 오류를 돌려준 경우를 먼저 본다
            JsonNode n = parse(readLimited(res.body()));
            int code = codeOf(n);
            String reason = reasonOf(n);
            String msg = "제논 WF9 응답이 SSE 가 아니다(HTTP 200 · " + ctype + ")"
                    + (code > 0 ? " — " + code : "") + (reason != null ? " · " + reason : "");
            if (permanentStatus(code)) {
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
            log.info("[WF9] 제논 WF9 응답 — 서빙 {}/{} · {}자 · 사건 {}개 · {}ms", props.servingId(), props.servingRev(),
                    sse.text().length(), sse.events(), ms);
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

    /**
     * 같은 입력이면 다시 보내도 같은 결과인 상태 — 바로 포기한다. 400(형식) · 413(너무 큼) · 415(형식) · 422(입력 규격).
     * 401 · 403 · 404 는 설정(AuthKey · 서빙 ID · 리비전)을 고치면 풀리므로 넣지 않는다.
     */
    static boolean permanentStatus(int status) {
        return status == 400 || status == 413 || status == 415 || status == 422;
    }

    /** HTTP 오류 → 실패. 상대 본문에서는 코드 낱말만 싣는다. */
    private Wf9Failure httpError(int status, byte[] body) {
        JsonNode n = parse(body);
        int code = codeOf(n);
        String reason = reasonOf(n);
        StringBuilder msg = new StringBuilder("제논 WF9 HTTP ").append(status);
        if (code > 0 && code != status) {
            msg.append(" · code ").append(code);
        }
        if (reason != null) {
            msg.append(" · ").append(reason);
        }
        switch (status) {
            case 401, 403 -> msg.append("(AuthKey 확인 — GENON_WF9_AUTH_KEY · 서빙 ").append(props.servingId()).append(')');
            case 404 -> msg.append("(서빙 주소 확인 — ").append(props.servingId()).append('/').append(props.servingRev()).append(')');
            case 413 -> msg.append("(문서가 너무 큼 — 분석 길이 voice.risk-wf9.analyze-max-sec)");
            default -> {
                // 그 밖은 상태 · 코드만
            }
        }
        if (permanentStatus(status)) {
            return Wf9Failure.permanent(Wf9Failure.Stage.PORTAL, msg.toString());
        }
        return Wf9Failure.retry(Wf9Failure.Stage.PORTAL, msg.toString());
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

    /** 오류 설명 끝의 {@code (코드 422·invalid_input)} — 숫자 코드와 사유 낱말(영문 · 숫자 · _ . -)만 꺼낸다. */
    private static final Pattern ERROR_REASON =
            Pattern.compile("코드\\s*(\\d{3})\\s*[·・:/,\\-]\\s*([A-Za-z][A-Za-z0-9_.\\-]{0,39})");

    /** 코드 낱말로 받아 줄 모양 — 영문으로 시작하는 짧은 낱말(설명 글 · 원문은 여기 맞지 않는다). */
    private static final Pattern REASON_WORD = Pattern.compile("[A-Za-z][A-Za-z0-9_.\\-]{0,39}");

    /**
     * {@code error} 사건 → 실패. HTTP 는 200 인 채 SSE 안에서 온다.
     * 실측(2026-11-20 가이드 11-19판): {@code {"code": 422, "message": "입력 형식이 올바르지 않습니다.\n\n(코드 422·invalid_input)"}}.
     *
     * <p>메시지에는 <b>코드와 사유 낱말만</b> 싣는다 — 설명 글은 보낸 문서 조각을 되풀이할 수 있어 싣지 않는다.
     * 판정은 HTTP 오류와 같다({@link #permanentStatus}) — 코드가 없거나 그 밖이면 다시 하기로 넘긴다.</p>
     */
    static Wf9Failure errorEvent(JsonNode d) {
        int code = codeOf(d);
        String reason = reasonOf(d);
        String msg = "제논 WF9 오류 사건(error)" + (code > 0 ? " — " + code : "") + (reason != null ? " · " + reason : "");
        if (permanentStatus(code)) {
            return Wf9Failure.permanent(Wf9Failure.Stage.PORTAL, msg);
        }
        return Wf9Failure.retry(Wf9Failure.Stage.PORTAL, msg);
    }

    /**
     * 숫자 코드 — {@code code} · {@code status} · {@code status_code} (숫자 또는 숫자 글) → 없으면 설명 끝 {@code (코드 NNN·…)}.
     * {@code error} 가 객체면 그 안도 본다. 없으면 -1.
     */
    static int codeOf(JsonNode d) {
        if (d == null || !d.isObject()) {
            return -1;
        }
        for (JsonNode o : List.of(d, d.path("error"))) {
            if (!o.isObject()) {
                continue;
            }
            for (String f : List.of("code", "status", "status_code")) {
                JsonNode c = o.path(f);
                if (c.isIntegralNumber() && c.asInt() >= 100 && c.asInt() <= 599) {
                    return c.asInt();
                }
                if (c.isTextual() && c.asText().strip().matches("[1-5]\\d{2}")) {
                    return Integer.parseInt(c.asText().strip());
                }
            }
        }
        Matcher m = ERROR_REASON.matcher(text(d));
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /**
     * 사유 낱말 — 설명 끝 {@code (코드 NNN·reason)} 의 reason → 없으면 {@code error} · {@code error_code} · {@code errorCode} ·
     * {@code type} 중 낱말 모양인 것(예 {@code invalid_token}). 설명 글 · 원문은 돌려주지 않는다. 없으면 null.
     */
    static String reasonOf(JsonNode d) {
        if (d == null || !d.isObject()) {
            return null;
        }
        Matcher m = ERROR_REASON.matcher(text(d));
        if (m.find()) {
            return m.group(2);
        }
        for (JsonNode o : List.of(d, d.path("error"))) {
            if (!o.isObject()) {
                continue;
            }
            for (String f : List.of("error", "error_code", "errorCode", "type", "code")) {
                JsonNode c = o.path(f);
                if (c.isTextual() && REASON_WORD.matcher(c.asText().strip()).matches()) {
                    return c.asText().strip();
                }
            }
        }
        return null;
    }

    /** 설명 글(코드 패턴을 찾는 데만 쓴다 — 밖으로 내보내지 않는다). */
    private static String text(JsonNode d) {
        StringBuilder b = new StringBuilder();
        for (JsonNode o : List.of(d, d.path("error"))) {
            for (String f : List.of("message", "detail", "msg", "errMsg")) {
                if (o.path(f).isTextual()) {
                    b.append(o.path(f).asText()).append('\n');
                }
            }
        }
        return b.toString();
    }

    /** 응답 Content-Type 의 형식 부분만(소문자 · 매개변수 뺌). 모양이 이상하면 "?" · 없으면 "". */
    private static String contentType(HttpResponse<?> res) {
        String v = res.headers().firstValue("Content-Type").orElse("").strip();
        if (v.isEmpty()) {
            return "";
        }
        int semi = v.indexOf(';');
        String t = (semi < 0 ? v : v.substring(0, semi)).strip().toLowerCase(Locale.ROOT);
        return t.matches("[a-z0-9.+\\-]{1,40}/[a-z0-9.+\\-]{1,40}") ? t : "?";
    }

    private static byte[] readLimited(InputStream in) {
        try (in) {
            return in == null ? new byte[0] : in.readNBytes(ERROR_BODY_MAX);
        } catch (IOException e) {
            return new byte[0];
        }
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
