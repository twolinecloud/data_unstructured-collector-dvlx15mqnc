package egovframework.unstructured.collector.voice.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import egovframework.unstructured.collector.common.config.DeployEnvPreset;
import egovframework.unstructured.collector.common.logging.LogCollectorClient;
import egovframework.unstructured.collector.voice.stt.genon.AudioSampleGenerator;
import egovframework.unstructured.collector.voice.stt.genon.GenonSttClient;
import egovframework.unstructured.collector.voice.stt.genon.GenonSttProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static egovframework.unstructured.collector.voice.risk.Wf9TestSupport.OM;
import static egovframework.unstructured.collector.voice.risk.Wf9TestSupport.json;
import static egovframework.unstructured.collector.voice.risk.Wf9TestSupport.meet;
import static egovframework.unstructured.collector.voice.risk.Wf9TestSupport.phone;
import static egovframework.unstructured.collector.voice.risk.Wf9TestSupport.sse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 끝까지 — 스풀 → 실제 {@link GenonSttClient}(REST · 가짜 제논 audio/process) → 실제 커넥터 클라이언트(가짜 커넥터)
 * → 실제 포털 클라이언트(가짜 로그인 · chat SSE) → 실제 {@link LogCollectorClient}(가짜 로그 컬렉터 PUT).
 * 외부망은 부르지 않는다. 음원은 합성 음, 이름 · 번호 · 교정번호는 지어낸 값이다.
 */
class RiskWf9FlowTest {

    private static final String NAME = "홍길동";
    private static final String PHONE = "010-1234-5678";
    private static final String CORR_NO = "2026000123";
    private static final String REC = "TARE0000000001";

    @TempDir
    Path tmp;

    private HttpServer server;
    private ExecutorService pool;
    private String base;
    private final List<String> genonBodies = new CopyOnWriteArrayList<>();
    private final List<String> genonAuth = new CopyOnWriteArrayList<>();
    private final List<JsonNode> deidBodies = new CopyOnWriteArrayList<>();
    private final List<JsonNode> chatBodies = new CopyOnWriteArrayList<>();
    private final List<String> putPaths = new CopyOnWriteArrayList<>();
    private final List<JsonNode> putBodies = new CopyOnWriteArrayList<>();
    private final List<String> purgePaths = new CopyOnWriteArrayList<>();
    private final List<JsonNode> purgeBodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger logins = new AtomicInteger();
    private volatile int deidStatus = 200;
    private volatile int putStatus = 200;
    private volatile int purgeStatus = 200;
    /** 가짜 제논 · WF9 가 2026-11-20 실응답(고정 자료)을 돌려준다. */
    private volatile boolean realShape;

    private final Wf9TestSupport.MutableClock clock = new Wf9TestSupport.MutableClock(Instant.parse("2026-11-16T03:00:00Z"));
    private Wf9Spool spool;
    private RiskWf9Worker worker;
    private RiskWf9Pipeline pipeline;
    private Path audio;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pool = Executors.newCachedThreadPool();
        server.setExecutor(pool);
        server.createContext("/genon/audio/process", this::genonProcess);
        server.createContext("/connector/api/v1/deid/speech-results", this::connector);
        server.createContext("/api/genportal/auth/login", ex -> {
            ex.getRequestBody().readAllBytes();
            json(ex, 200, "{\"code\":0,\"data\":{\"access_token\":\"tok-" + logins.incrementAndGet() + "\"}}");
        });
        server.createContext("/api/genportal/chat", this::chat);
        server.createContext("/logc/api/v1/logs/contact-risk-signals/", ex -> {
            putPaths.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath());
            putBodies.add(OM.readTree(ex.getRequestBody().readAllBytes()));
            if (putStatus != 200) {
                json(ex, putStatus, "{\"success\":false,\"message\":\"거절\"}");
                return;
            }
            json(ex, 200, "{\"success\":true,\"code\":0,\"result\":{\"recFileId\":\"" + REC + "\",\"deleted\":0,\"inserted\":"
                    + putBodies.get(putBodies.size() - 1).path("signals").size() + "}}");
        });
        // T1 폐기 상태(POST /batches/{execId}/ppp-callback) — 로그 컬렉터 실응답처럼 성공은 result null · 배치 없으면 404
        server.createContext("/logc/api/v1/logs/batches/", ex -> {
            purgePaths.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath());
            purgeBodies.add(OM.readTree(ex.getRequestBody().readAllBytes()));
            if (purgeStatus != 200) {
                json(ex, purgeStatus, "{\"success\":false,\"code\":404,\"message\":\"배치 실행 이력 없음\"}");
                return;
            }
            json(ex, 200, "{\"success\":true,\"code\":0,\"result\":null}");
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        audio = Files.write(tmp.resolve("decrypted_m.wav"), AudioSampleGenerator.sineWav(3, 8000, 440));
        build(RiskWf9Properties.PortalMode.REST);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        pool.shutdownNow();
    }

    private void build(RiskWf9Properties.PortalMode portalMode) {
        build(portalMode, false, GenonSttProperties.Mode.REST);
    }

    private void build(RiskWf9Properties.PortalMode portalMode, boolean defaultFields) {
        build(portalMode, defaultFields, GenonSttProperties.Mode.REST);
    }

    /**
     * @param defaultFields 내용 · 위험 분류 필드 목록을 운영 기본값(@DefaultValue)으로
     * @param genonMode     제논 전처리 — MOCK 이면 가짜 제논도 부르지 않는다(제논에 보낸 것 없음)
     */
    private void build(RiskWf9Properties.PortalMode portalMode, boolean defaultFields, GenonSttProperties.Mode genonMode) {
        Wf9TestSupport.Props p = new Wf9TestSupport.Props();
        p.portalMode = portalMode;
        p.portalBaseUrl = base + "/api/genportal";
        if (defaultFields) {
            RiskWf9Properties d = new Binder(new MapConfigurationPropertySource(Map.of()))
                    .bindOrCreate("voice.risk-wf9", RiskWf9Properties.class);
            p.contentFields = d.contentFields();
            p.riskClassFields = d.riskClassFields();
        }
        RiskWf9Properties props = p.build();
        GenonSttProperties gp = Wf9TestSupport.genon(genonMode, base + "/genon", 20_971_520L, 600);
        GenonSttClient genon = new GenonSttClient(gp, OM);
        SpeechResultsDeidClient deid = new SpeechResultsDeidClient(OM, () -> base + "/connector", () -> false,
                props.deidPath(), 1000, 10);
        GenonPortalClient portal = new GenonPortalClient(props, OM);
        LogCollectorClient lc = new LogCollectorClient(OM, mock(DeployEnvPreset.class));
        ReflectionTestUtils.setField(lc, "baseUrl", base + "/logc");
        ReflectionTestUtils.setField(lc, "enabled", true);
        ReflectionTestUtils.setField(lc, "connectTimeoutSec", 2);
        ReflectionTestUtils.setField(lc, "readTimeoutSec", 5);
        ReflectionTestUtils.invokeMethod(lc, "init");
        pipeline = new RiskWf9Pipeline(genon, deid, portal, lc, props, OM);
        spool = new Wf9Spool(props, () -> tmp.resolve("spool"), gp, pipeline::blocker, clock);
        worker = new RiskWf9Worker(props, spool, pipeline::blocker, pipeline::run, () -> false, clock);
    }

    // ------------------------------------------------------------------ 가짜 서버

    private void genonProcess(HttpExchange ex) throws IOException {
        genonAuth.add(ex.getRequestHeaders().getFirst("Authorization"));
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        genonBodies.add(body);
        String cid = field(body, "contact_id");
        if (realShape) {
            // 2026-11-20 실응답(합성 낭독 22초) — 받은 필드로 기록 정보만 바꾸고, 가상 이름을 한 구간의 글 · 낱말에 섞는다
            ObjectNode r = (ObjectNode) OM.readTree(resource("/genon/process_22s_20261120.json"));
            ((ObjectNode) r.path("input").path("contact_records").path(0)).put("contact_id", cid)
                    .put("contact_type", field(body, "contact_type")).put("occurred_at", field(body, "occurred_at"));
            ObjectNode sr = (ObjectNode) r.path("input").path("speech_results").path(0);
            sr.put("contact_id", cid);
            ObjectNode seg = (ObjectNode) sr.path("segments").path(5);
            seg.put("text", NAME + "에게 다음 주에 면회 갈게요.");
            ((ArrayNode) seg.path("words")).insertObject(0).put("word", NAME + "에게").put("start_ms", 10270.0)
                    .put("end_ms", 10700.0).put("score", 0.9);
            json(ex, 200, OM.writeValueAsString(r));
            return;
        }
        ObjectNode r = OM.createObjectNode();
        r.put("function", "contact_risk_analysis");
        ObjectNode in = r.putObject("input");
        in.putArray("contact_records").addObject().put("contact_id", cid).put("contact_type", field(body, "contact_type"))
                .put("occurred_at", field(body, "occurred_at"));
        ObjectNode sr = in.putArray("speech_results").addObject();
        String said = NAME + "이 " + PHONE + " 로 연락하래요";
        sr.put("contact_id", cid).put("transcript", said);
        sr.putArray("segments").addObject().put("start_ms", 0).put("end_ms", 2000).put("text", said);
        sr.putArray("prosody_events");
        sr.put("prosody_status", "not_run").put("audio_status", "analyzed").put("duration_ms", 3000);
        json(ex, 200, OM.writeValueAsString(r));
    }

    /** 가짜 커넥터 — 문서 글에서 가상 이름 · 번호를 토큰으로 바꾸고 사전을 돌려준다. */
    private void connector(HttpExchange ex) throws IOException {
        JsonNode req = OM.readTree(ex.getRequestBody().readAllBytes());
        deidBodies.add(req);
        if (deidStatus != 200) {
            json(ex, deidStatus, "{\"status\":" + deidStatus + ",\"code\":\"DEID_RESIDUAL_PII\",\"message\":\"…\","
                    + "\"detail\":\"남은 자리 1곳 · 첫 자리 /input/speech_results/0/transcript (형식)\"}");
            return;
        }
        String masked = OM.writeValueAsString(req.path("document")).replace(NAME, "[NAME_1]").replace(PHONE, "[PHONE_1]");
        ObjectNode r = OM.createObjectNode();
        r.put("recFileId", req.path("recFileId").asText());
        r.put("execId", req.path("execId").asText());
        r.set("document", OM.readTree(masked));
        r.putObject("mapping").put("[NAME_1]", NAME).put("[PHONE_1]", PHONE);
        r.putArray("tokensByLength").add("[PHONE_1]").add("[NAME_1]");
        r.putObject("stats").put("tokens", 2);
        json(ex, 200, OM.writeValueAsString(r));
    }

    /** 가짜 WF9 — 울타리 친 JSON 을 토큰 셋으로 나눠 흘린다. */
    private void chat(HttpExchange ex) throws IOException {
        chatBodies.add(OM.readTree(ex.getRequestBody().readAllBytes()));
        if (realShape) {
            // 2026-11-20 실응답(가상 위험 대화 → 자살 · 도주 · 부정물품 수수) — token 일부 뒤 result.text 로 답 전체
            String real = resource("/genon/wf9_findings_20261120.json");
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream o = ex.getResponseBody()) {
                for (String part : List.of(sse("request_id", "req-1"), sse("session_id", "s-1"), sse("token", real.substring(0, 20)),
                        sse("result", Map.of("text", real)), sse("duration", "10.8"), sse("end", "[DONE]"))) {
                    o.write(part.getBytes(StandardCharsets.UTF_8));
                    o.flush();
                }
            }
            return;
        }
        String answer = "```json\n" + OM.writeValueAsString(Map.of(
                "findings", List.of(Map.of("content", "[NAME_1]이 [PHONE_1]로 외부 연락을 요청함", "riskClassCode", "EXT_CONTACT")),
                "reviews", List.of(Map.of("description", "[NAME_1]과의 관계 확인 필요")))) + "\n```";
        int a = answer.length() / 3;
        ex.getResponseHeaders().add("Content-Type", "text/event-stream");
        ex.sendResponseHeaders(200, 0);
        try (OutputStream o = ex.getResponseBody()) {
            for (String part : List.of(sse("session_id", "s-1"), sse("token", answer.substring(0, a)),
                    sse("token", answer.substring(a, 2 * a)), sse("token", answer.substring(2 * a)), sse("end", "[DONE]"))) {
                o.write(part.getBytes(StandardCharsets.UTF_8));
                o.flush();
            }
        }
    }

    private static String field(String multipart, String name) {
        Matcher m = Pattern.compile("name=\"" + name + "\"\\r\\n(?:[^\\r\\n]+\\r\\n)*\\r\\n([^\\r\\n]*)\\r\\n").matcher(multipart);
        return m.find() ? m.group(1) : null;
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = RiskWf9FlowTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("시험 자료 없음 — " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** T1 폐기 완료 — 그 배치로 times 번 · 폐기 상태 SUCCESS · 폐기 일시 KST 초까지 · PPP 처리 상태는 보내지 않음. */
    private void assertPurgeMarked(String execId, int times) {
        assertThat(purgePaths).hasSize(times).containsOnly("POST /logc/api/v1/logs/batches/" + execId + "/ppp-callback");
        for (JsonNode b : purgeBodies) {
            assertThat(b.path("purgeStsCd").asText()).isEqualTo(LogCollectorClient.PURGE_SUCCESS);
            assertThat(b.path("purgeDtm").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");
            assertThat(b.path("pppProcStsCd").isNull() || b.path("pppProcStsCd").isMissingNode()).isTrue();
        }
    }

    // ------------------------------------------------------------------ 시험

    @Test
    @DisplayName("끝까지 — 제논엔 대체 ID 만 · WF9 엔 가린 문서만 · 표엔 내용만 되돌려 녹음 단위로 · 스풀 비움")
    void endToEnd() throws Exception {
        assertThat(spool.offer("20261116UNS001", meet(REC), audio, false)).isTrue();

        RiskWf9Worker.Report r = worker.drain();

        assertThat(r.succeeded()).isEqualTo(1);
        assertThat(spool.list()).isEmpty();

        // ① 제논 전처리 — 접견 · KST 오프셋 · 무작위 대체 ID · 음성 키. 녹음 ID · 교정번호는 보내지 않는다
        String g = genonBodies.get(0);
        assertThat(field(g, "contact_type")).isEqualTo("접견");
        assertThat(field(g, "occurred_at")).isEqualTo("2026-11-16T10:30:00+09:00");
        assertThat(field(g, "contact_id")).matches("C-[0-9a-f]{32}");
        assertThat(g).contains("filename=\"audio.wav\"").doesNotContain(REC).doesNotContain(CORR_NO).doesNotContain("decrypted_m");
        assertThat(genonAuth.get(0)).isEqualTo("Bearer test-key");

        // ② 커넥터 — 녹음 ID · 배치 · 제논 응답 그대로
        JsonNode d = deidBodies.get(0);
        assertThat(d.path("recFileId").asText()).isEqualTo(REC);
        assertThat(d.path("execId").asText()).isEqualTo("20261116UNS001");
        assertThat(d.path("document").path("function").asText()).isEqualTo("contact_risk_analysis");

        // ③ WF9 — 가린 문서의 JSON 문자열만
        JsonNode c = chatBodies.get(0);
        assertThat(c.path("endpoint").asText()).isEqualTo("pt6i_10rw_jsd9");
        String message = c.path("message").asText();
        assertThat(message).contains("[NAME_1]").contains("[PHONE_1]").doesNotContain(NAME).doesNotContain(PHONE)
                .doesNotContain(CORR_NO).doesNotContain(REC);
        assertThat(OM.readTree(message).path("input").path("speech_results").path(0).path("transcript").asText())
                .isEqualTo("[NAME_1]이 [PHONE_1] 로 연락하래요");

        // ⑤ 로그 컬렉터 — 녹음 단위 통째 교체 · 내용만 되돌림(D6) · 원 항목은 가린 그대로
        assertThat(putPaths).containsExactly("PUT /logc/api/v1/logs/contact-risk-signals/" + REC);
        JsonNode put = putBodies.get(0);
        assertThat(put.path("corrNo").asText()).isEqualTo(CORR_NO);
        assertThat(put.path("contactSeCd").asText()).isEqualTo("VISIT");
        assertThat(put.path("contactDtm").asText()).isEqualTo("2026-11-16T10:30:00");
        assertThat(put.path("execId").asText()).isEqualTo("20261116UNS001");
        assertThat(put.path("regUserId").isNull() || put.path("regUserId").isMissingNode()).isTrue();
        JsonNode s = put.path("signals");
        assertThat(s).hasSize(2);
        assertThat(s.get(0).path("signalSeCd").asText()).isEqualTo("FINDING");
        assertThat(s.get(0).path("signalCn").asText()).isEqualTo(NAME + "이 " + PHONE + "로 외부 연락을 요청함");
        assertThat(s.get(0).path("riskClassCd").asText()).isEqualTo("EXT_CONTACT");
        assertThat(s.get(0).path("signalData").path("content").asText()).isEqualTo("[NAME_1]이 [PHONE_1]로 외부 연락을 요청함");
        assertThat(s.get(1).path("signalSeCd").asText()).isEqualTo("REVIEW");
        assertThat(s.get(1).path("signalCn").asText()).isEqualTo(NAME + "과의 관계 확인 필요");
        assertThat(s.get(1).path("riskClassCd").isNull()).isTrue();
        assertThat(s.get(1).path("signalSn").isNull()).as("순번은 로그 컬렉터가 매긴다").isTrue();

        // T1 폐기 — 제논 실호출 정상 응답(전처리 · WF9)마다 바로 폐기 완료(비저장이라 폐기 신호가 오지 않는다 · PL 합의 11-20)
        assertPurgeMarked("20261116UNS001", 2);
    }

    @Test
    @DisplayName("실응답 모양(2026-11-20 가이드 11-19판) — 전처리 구간 · 낱말 그대로 커넥터로 · WF9 엔 낱말까지 가린 문서 · findings 3건 → 기본 필드 목록으로 3행")
    void realShapeEndToEnd() throws Exception {
        realShape = true;
        build(RiskWf9Properties.PortalMode.REST, true);
        assertThat(spool.offer("20261120UNS001", phone(REC), audio, false)).isTrue();

        RiskWf9Worker.Report r = worker.drain();

        assertThat(r.succeeded()).isEqualTo(1);
        assertThat(spool.list()).isEmpty();
        assertThat(field(genonBodies.get(0), "contact_type")).isEqualTo("전화");
        assertThat(field(genonBodies.get(0), "occurred_at")).isEqualTo("2026-11-16T14:05:07+09:00");

        // ② 커넥터 — 제논 전처리 본문 그대로(구간 9개 · segment_id · speaker · 낱말 시각 · 운율 상태)
        JsonNode sr = deidBodies.get(0).path("document").path("input").path("speech_results").path(0);
        assertThat(sr.path("segments")).hasSize(9);
        assertThat(sr.path("segments").path(0).path("segment_id").asText()).isEqualTo("SEG-0001");
        assertThat(sr.path("segments").path(0).path("speaker").asText()).isEqualTo("UNKNOWN");
        assertThat(sr.path("segments").path(5).path("words").path(0).path("word").asText()).isEqualTo(NAME + "에게");
        assertThat(sr.path("prosody_status").asText()).isEqualTo("skipped_short");

        // ③ WF9 — 낱말까지 가린 문서만
        String message = chatBodies.get(0).path("message").asText();
        assertThat(message).doesNotContain(NAME).contains("[NAME_1]에게");
        assertThat(OM.readTree(message).path("input").path("speech_results").path(0).path("segments").path(5)
                .path("words").path(0).path("word").asText()).isEqualTo("[NAME_1]에게");

        // ⑤ 로그 컬렉터 — 한 녹음의 findings 3건이 서로 덮어쓰지 않고 3행(순번은 로그 컬렉터가 1..3 · 분류 코드는 로그 컬렉터가 맞춘다)
        assertThat(putPaths).containsExactly("PUT /logc/api/v1/logs/contact-risk-signals/" + REC);
        JsonNode put = putBodies.get(0);
        assertThat(put.path("contactSeCd").asText()).isEqualTo("CALL");
        assertThat(put.path("corrNo").asText()).isEqualTo("2026000456");
        assertThat(put.path("contactDtm").asText()).isEqualTo("2026-11-16T14:05:07");
        JsonNode s = put.path("signals");
        assertThat(s).hasSize(3);
        assertThat(s.findValuesAsText("signalSeCd")).containsOnly("FINDING");
        assertThat(s.findValuesAsText("riskClassCd")).containsExactly("자살", "도주", "부정물품 수수");
        assertThat(s.findValuesAsText("signalCn"))
                .containsExactly("전화통화 중 자살 암시 발언", "전화통화 중 도주 암시 발언", "전화통화 중 부정물품 수수 암시 발언");
        JsonNode ev = s.get(1).path("signalData").path("evidence");
        assertThat(ev).hasSize(2);
        assertThat(ev.get(1).path("segment_id").asText()).isEqualTo("SEG-0003");
        assertThat(ev.get(1).path("start_ms").asDouble()).isEqualTo(5100.0);
        assertThat(ev.get(1).get("speaker").isNull()).isTrue();
        for (JsonNode one : s) {
            assertThat(one.path("signalSn").isNull()).isTrue();
        }
        assertPurgeMarked("20261120UNS001", 2);
    }

    @Test
    @DisplayName("커넥터 422(잔존) → 제논 WF9 · 주요사항 적재를 부르지 않고 바로 포기 · 사본 삭제 · 이미 전처리로 보낸 음성 몫의 폐기 완료는 적힘")
    void residualStopsBeforeGenonChat() throws Exception {
        deidStatus = 422;
        spool.offer("E1", meet(REC), audio, false);

        RiskWf9Worker.Report r = worker.drain();

        assertThat(r.gaveUp()).isEqualTo(1);
        assertThat(chatBodies).isEmpty();
        assertThat(putBodies).isEmpty();
        assertThat(spool.list()).isEmpty();
        assertPurgeMarked("E1", 1);
    }

    @Test
    @DisplayName("로그 컬렉터 503 → 다시 하기(사본 남김) · 400 → 바로 포기")
    void storeFailures() throws Exception {
        putStatus = 503;
        spool.offer("E1", meet(REC), audio, false);

        assertThat(worker.drain().retried()).isEqualTo(1);
        Wf9Job j = spool.list().get(0).job();
        assertThat(j.attempts()).isEqualTo(1);
        assertThat(j.lastError()).isEqualTo("[STORE] 주요사항 적재 실패 — HTTP 503");

        putStatus = 400;
        clock.advanceSec(300);
        assertThat(worker.drain().gaveUp()).isEqualTo(1);
        assertThat(spool.list()).isEmpty();
        assertThat(genonBodies).as("다시 하면 ①부터 — 사전을 디스크에 두지 않는다").hasSize(2);
        assertThat(purgePaths).as("적재 실패와 무관 — 제논 실호출 정상 응답마다(2회 × 전처리 · WF9)").hasSize(4);
    }

    @Test
    @DisplayName("포털 MOCK — 외부망 없이 빈 결과 → 그 녹음의 행을 지우기만(signals [])")
    void portalMockStoresEmpty() throws Exception {
        build(RiskWf9Properties.PortalMode.MOCK);
        spool.offer("E1", meet(REC), audio, false);

        assertThat(worker.drain().succeeded()).isEqualTo(1);
        assertThat(chatBodies).isEmpty();
        assertThat(logins.get()).isZero();
        assertThat(putBodies.get(0).path("signals")).isEmpty();
        assertPurgeMarked("E1", 1);   // 제논 실호출은 전처리 한 번뿐
    }

    @Test
    @DisplayName("제논 전처리 · 포털 둘 다 MOCK — 제논에 보낸 것이 없으니 T1 폐기 상태를 적지 않는다")
    void allMockLeavesPurgeAlone() throws Exception {
        build(RiskWf9Properties.PortalMode.MOCK, false, GenonSttProperties.Mode.MOCK);
        spool.offer("E1", meet(REC), audio, false);
        Wf9Spool.Entry e = spool.list().get(0);

        RiskWf9Pipeline.Outcome o = pipeline.run(e.job(), e.dir().resolve(e.job().audioFile()));

        assertThat(genonBodies).isEmpty();
        assertThat(chatBodies).isEmpty();
        assertThat(putPaths).containsExactly("PUT /logc/api/v1/logs/contact-risk-signals/" + REC);
        assertThat(purgePaths).isEmpty();
        assertThat(o.purgeMarked()).isFalse();
    }

    @Test
    @DisplayName("purgeMarked — 로그 컬렉터가 받으면 true · 못 적어도(404 배치 없음) false 일 뿐 주요사항은 그대로 적재(작업 계속)")
    void purgeFailureDoesNotBlockStore() throws Exception {
        spool.offer("E1", meet(REC), audio, false);
        Wf9Spool.Entry e = spool.list().get(0);
        Path copy = e.dir().resolve(e.job().audioFile());

        RiskWf9Pipeline.Outcome ok = pipeline.run(e.job(), copy);
        assertThat(ok.purgeMarked()).isTrue();
        assertPurgeMarked("E1", 2);

        purgeStatus = 404;
        RiskWf9Pipeline.Outcome failed = pipeline.run(e.job(), copy);
        assertThat(failed.purgeMarked()).isFalse();
        assertThat(failed.inserted()).isEqualTo(2);
        assertThat(purgePaths).hasSize(4);
        assertThat(putPaths).hasSize(2);
    }
}
