package egovframework.unstructured.collector.voice.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import egovframework.unstructured.collector.common.config.DeployEnvPreset;
import egovframework.unstructured.collector.common.logging.LogCollectorClient;
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

import static egovframework.unstructured.collector.voice.risk.Wf9TestSupport.MASKED_1;
import static egovframework.unstructured.collector.voice.risk.Wf9TestSupport.MASKED_2;
import static egovframework.unstructured.collector.voice.risk.Wf9TestSupport.OM;
import static egovframework.unstructured.collector.voice.risk.Wf9TestSupport.json;
import static egovframework.unstructured.collector.voice.risk.Wf9TestSupport.masked;
import static egovframework.unstructured.collector.voice.risk.Wf9TestSupport.meet;
import static egovframework.unstructured.collector.voice.risk.Wf9TestSupport.phone;
import static egovframework.unstructured.collector.voice.risk.Wf9TestSupport.sse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 끝까지(2026-11-27 텍스트 전용) — 스풀(가린 전사 문서) → 입력 규격 검사 → 실제 WF9 클라이언트(가짜 Code Serving SSE)
 * → 실제 {@link LogCollectorClient}(가짜 로그 컬렉터 PUT · T1 폐기 상태).
 * 음성 · 제논 전처리({@code audio/process}) · 커넥터 토큰화는 없다. 외부망은 부르지 않는다. 전사 · 교정번호는 지어낸 값이다.
 */
class RiskWf9FlowTest {

    private static final String REC = "TARE0000000001";
    private static final String CORR_NO = "2026000123";
    /** 가짜 WF9 가 돌려주는 가린 내용 — 되돌리지 않으므로 표에도 이대로 남아야 한다. */
    private static final String FINDING = "***이 ***-****-****로 외부 연락을 요청함";
    private static final String REVIEW = "*** 과의 관계 확인 필요";

    @TempDir
    Path tmp;

    private HttpServer server;
    private ExecutorService pool;
    private String base;
    private final List<String> otherPaths = new CopyOnWriteArrayList<>();
    private final List<JsonNode> chatBodies = new CopyOnWriteArrayList<>();
    private final List<String> chatAuth = new CopyOnWriteArrayList<>();
    private final List<String> putPaths = new CopyOnWriteArrayList<>();
    private final List<JsonNode> putBodies = new CopyOnWriteArrayList<>();
    private final List<String> purgePaths = new CopyOnWriteArrayList<>();
    private final List<JsonNode> purgeBodies = new CopyOnWriteArrayList<>();
    private volatile int purgeStatus = 200;
    private volatile int putStatus = 200;
    private volatile int chatStatus = 200;
    /** 가짜 WF9 가 2026-11-20 실응답(고정 자료 · findings 3건)을 돌려준다. */
    private volatile boolean realShape;

    private final Wf9TestSupport.MutableClock clock = new Wf9TestSupport.MutableClock(Instant.parse("2026-11-16T03:00:00Z"));
    private Wf9Spool spool;
    private RiskWf9Worker worker;
    private RiskWf9Pipeline pipeline;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pool = Executors.newCachedThreadPool();
        server.setExecutor(pool);
        // 그 밖의 경로(예전 제논 전처리 · 커넥터 speech-results)로 오면 적어 두고 404 — 오지 않아야 한다
        server.createContext("/", ex -> {
            otherPaths.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath());
            json(ex, 404, "{}");
        });
        server.createContext("/api/gateway/code_serving/709/10721/run", this::chat);
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
        server.createContext("/logc/api/v1/logs/batches/", ex -> {
            purgePaths.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath());
            purgeBodies.add(OM.readTree(ex.getRequestBody().readAllBytes()));
            if (purgeStatus != 200) {
                json(ex, purgeStatus, "{\"success\":false,\"code\":" + purgeStatus + ",\"message\":\"실패\"}");
                return;
            }
            json(ex, 200, "{\"success\":true,\"code\":0,\"result\":null}");
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        build(RiskWf9Properties.PortalMode.REST, false);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        pool.shutdownNow();
    }

    /** @param defaultFields 내용 · 위험 분류 필드 목록을 운영 기본값(@DefaultValue)으로 */
    private void build(RiskWf9Properties.PortalMode portalMode, boolean defaultFields) {
        Wf9TestSupport.Props p = new Wf9TestSupport.Props();
        p.portalMode = portalMode;
        p.servingBaseUrl = base + "/api/gateway/code_serving";
        if (defaultFields) {
            RiskWf9Properties d = new Binder(new MapConfigurationPropertySource(Map.of()))
                    .bindOrCreate("voice.risk-wf9", RiskWf9Properties.class);
            p.contentFields = d.contentFields();
            p.riskClassFields = d.riskClassFields();
        }
        RiskWf9Properties props = p.build();
        GenonPortalClient portal = new GenonPortalClient(props, OM);
        LogCollectorClient lc = new LogCollectorClient(OM, mock(DeployEnvPreset.class));
        ReflectionTestUtils.setField(lc, "baseUrl", base + "/logc");
        ReflectionTestUtils.setField(lc, "enabled", true);
        ReflectionTestUtils.setField(lc, "connectTimeoutSec", 2);
        ReflectionTestUtils.setField(lc, "readTimeoutSec", 5);
        ReflectionTestUtils.invokeMethod(lc, "init");
        pipeline = new RiskWf9Pipeline(portal, lc, props, OM);
        spool = new Wf9Spool(props, () -> tmp.resolve("spool"), pipeline::blocker, clock);
        worker = new RiskWf9Worker(props, spool, pipeline::blocker, pipeline::run, () -> false, clock);
    }

    private boolean offer(String execId, egovframework.unstructured.collector.common.model.VoiceTarget t) {
        return spool.offer(execId, t, masked(), Wf9Spool.DEIDENT_MODE, false);
    }

    private Path docOf(Wf9Spool.Entry e) {
        return e.dir().resolve(e.job().docFile());
    }

    // ------------------------------------------------------------------ 가짜 서버

    /** 가짜 WF9 — 울타리 친 JSON 을 토큰 셋으로 나눠 흘린다. */
    private void chat(HttpExchange ex) throws IOException {
        chatAuth.add(ex.getRequestHeaders().getFirst("Authorization") + " | " + ex.getRequestHeaders().getFirst("Accept"));
        chatBodies.add(OM.readTree(ex.getRequestBody().readAllBytes()));
        if (chatStatus != 200) {
            json(ex, chatStatus, "{\"detail\":\"홍길동 인증 실패\",\"code\":\"invalid_token\"}");
            return;
        }
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
                "findings", List.of(Map.of("content", FINDING, "riskClassCode", "EXT_CONTACT",
                        "evidence", List.of(Map.of("segment_id", "SEG-0001", "speaker", "UNKNOWN", "start_ms", 0, "end_ms", 2000)))),
                "reviews", List.of(Map.of("description", REVIEW)))) + "\n```";
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

    private static String resource(String path) throws IOException {
        try (InputStream in = RiskWf9FlowTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("시험 자료 없음 — " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** T1 폐기 완료 — 그 배치로 times 번 · 폐기 상태 SUCCESS · 폐기 일시 KST 초까지 · 두 필드만. */
    private void assertPurgeMarked(String execId, int times) {
        assertThat(purgePaths).hasSize(times);
        if (times > 0) {
            assertThat(purgePaths).containsOnly("POST /logc/api/v1/logs/batches/" + execId + "/purge-status");
        }
        for (JsonNode b : purgeBodies) {
            assertThat(b.path("purgeStsCd").asText()).isEqualTo(LogCollectorClient.PURGE_SUCCESS);
            assertThat(b.path("purgeDtm").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");
            assertThat(b.size()).isEqualTo(2);
        }
    }

    // ------------------------------------------------------------------ 시험

    @Test
    @DisplayName("끝까지 — WF9 엔 가린 구간 글만(UNKNOWN · not_run · 대체 ID) · 음성 · 전처리 · 토큰화 호출 없음 · 표엔 가린 내용 그대로 · 폐기 완료 1번")
    void endToEnd() throws Exception {
        assertThat(offer("20261116UNS001", meet(REC))).isTrue();

        RiskWf9Worker.Report r = worker.drain();

        assertThat(r.succeeded()).isEqualTo(1);
        assertThat(spool.list()).isEmpty();
        assertThat(otherPaths).as("제논 audio/process · 커넥터 speech-results 를 부르지 않는다").isEmpty();

        // ① WF9(Code Serving) — 서빙별 AuthKey · SSE · 가린 문서의 JSON 문자열만
        assertThat(chatAuth).containsExactly("Bearer wf9-test-key | text/event-stream");
        JsonNode c = chatBodies.get(0);
        assertThat(c.path("endpoint").asText()).isEqualTo("pt6i_10rw_jsd9");
        String message = c.path("message").asText();
        assertThat(message).doesNotContain(CORR_NO).doesNotContain(REC).doesNotContain(".wav").doesNotContain(".m4a").doesNotContain("[NAME");
        JsonNode in = OM.readTree(message);
        assertThat(in.path("function").asText()).isEqualTo("contact_risk_analysis");
        JsonNode rec = in.path("input").path("contact_records").path(0);
        assertThat(rec.path("contact_id").asText()).matches("C-[0-9a-f]{32}");
        assertThat(rec.path("contact_type").asText()).isEqualTo("접견");
        assertThat(rec.path("occurred_at").asText()).isEqualTo("2026-11-16T10:30+09:00");
        JsonNode sr = in.path("input").path("speech_results").path(0);
        assertThat(sr.path("contact_id").asText()).isEqualTo(rec.path("contact_id").asText());
        JsonNode segs = sr.path("segments");
        assertThat(segs).hasSize(2);
        assertThat(segs.get(0).path("segment_id").asText()).isEqualTo("SEG-0001");
        assertThat(segs.get(0).path("speaker").asText()).isEqualTo("UNKNOWN");
        assertThat(segs.get(0).path("start_ms").asLong()).isZero();
        assertThat(segs.get(0).path("end_ms").asLong()).isEqualTo(2000);
        assertThat(segs.get(0).path("text").asText()).isEqualTo(MASKED_1);
        assertThat(segs.get(1).path("start_ms").asLong()).isEqualTo(2500);
        assertThat(segs.get(1).path("text").asText()).isEqualTo(MASKED_2);
        assertThat(sr.path("prosody_events")).isEmpty();
        assertThat(sr.path("prosody_status").asText()).isEqualTo("not_run");
        assertThat(sr.path("audio_events")).isEmpty();
        assertThat(sr.path("audio_status").asText()).isEqualTo("not_run");
        assertThat(sr.path("duration_ms").asLong()).isEqualTo(4200);

        // ② 로그 컬렉터 — 녹음 단위 통째 교체 · 내용은 되돌리지 않는다(가린 그대로)
        assertThat(putPaths).containsExactly("PUT /logc/api/v1/logs/contact-risk-signals/" + REC);
        JsonNode put = putBodies.get(0);
        assertThat(put.path("corrNo").asText()).isEqualTo(CORR_NO);
        assertThat(put.path("contactSeCd").asText()).isEqualTo("VISIT");
        assertThat(put.path("contactDtm").asText()).isEqualTo("2026-11-16T10:30:00");
        assertThat(put.path("execId").asText()).isEqualTo("20261116UNS001");
        JsonNode s = put.path("signals");
        assertThat(s).hasSize(2);
        assertThat(s.get(0).path("signalSeCd").asText()).isEqualTo("FINDING");
        assertThat(s.get(0).path("signalCn").asText()).as("되돌리지 않는다").isEqualTo(FINDING);
        assertThat(s.get(0).path("riskClassCd").asText()).isEqualTo("EXT_CONTACT");
        assertThat(s.get(0).path("signalData").path("content").asText()).isEqualTo(FINDING);
        assertThat(s.get(1).path("signalSeCd").asText()).isEqualTo("REVIEW");
        assertThat(s.get(1).path("signalCn").asText()).isEqualTo(REVIEW);
        assertThat(s.get(1).path("signalSn").isNull()).as("순번은 로그 컬렉터가 매긴다").isTrue();

        // T1 폐기 — 제논 실호출은 WF9 한 번뿐(비저장이라 폐기 신호가 오지 않는다 · PL 합의 11-20)
        assertPurgeMarked("20261116UNS001", 1);
    }

    @Test
    @DisplayName("실응답 모양(2026-11-20) — 전화 · findings 3건 → 기본 필드 목록으로 3행 · 근거 구간 그대로")
    void realShapeEndToEnd() throws Exception {
        realShape = true;
        build(RiskWf9Properties.PortalMode.REST, true);
        assertThat(offer("20261120UNS001", phone(REC))).isTrue();

        RiskWf9Worker.Report r = worker.drain();

        assertThat(r.succeeded()).isEqualTo(1);
        JsonNode rec = OM.readTree(chatBodies.get(0).path("message").asText()).path("input").path("contact_records").path(0);
        assertThat(rec.path("contact_type").asText()).isEqualTo("전화");
        assertThat(rec.path("occurred_at").asText()).isEqualTo("2026-11-16T14:05:07+09:00");

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
        assertPurgeMarked("20261120UNS001", 1);
    }

    @Test
    @DisplayName("입력 규격 위반(화자 SPEAKER_A) → WF9 · 적재 없이 바로 포기 · 문서 삭제 · 폐기 표시 없음(보낸 것 없음)")
    void inputViolationStopsBeforeGenonChat() throws Exception {
        offer("E1", meet(REC));
        Path doc = docOf(spool.list().get(0));
        Files.writeString(doc, Files.readString(doc).replace("\"UNKNOWN\"", "\"SPEAKER_A\""));

        RiskWf9Worker.Report r = worker.drain();

        assertThat(r.gaveUp()).isEqualTo(1);
        assertThat(chatBodies).as("외부망으로 보내지 않는다").isEmpty();
        assertThat(putBodies).isEmpty();
        assertThat(spool.list()).isEmpty();
        assertPurgeMarked("E1", 0);
    }

    @Test
    @DisplayName("가린 문서가 깨짐 → 바로 포기(INPUT) · 메시지는 예외 이름만")
    void unreadableDocGivesUp() throws Exception {
        offer("E1", meet(REC));
        Wf9Spool.Entry e = spool.list().get(0);
        Files.writeString(docOf(e), "{ 홍길동");

        assertThat(org.assertj.core.api.Assertions.catchThrowableOfType(() -> pipeline.run(e.job(), docOf(e)), Wf9Failure.class))
                .satisfies(f -> {
                    assertThat(f.stage()).isEqualTo(Wf9Failure.Stage.INPUT);
                    assertThat(f.getMessage()).startsWith("가린 문서를 읽지 못함").doesNotContain("홍길동");
                });
        assertThat(chatBodies).isEmpty();
    }

    @Test
    @DisplayName("Code Serving 401(AuthKey) → 다시 하기(문서 남김) · 메시지에 무엇을 볼지만 · 키 값 · 응답 본문 없음 · 폐기 표시 없음")
    void authRejectedRetries() throws Exception {
        chatStatus = 401;
        offer("E1", meet(REC));

        assertThat(worker.drain().retried()).isEqualTo(1);
        Wf9Job j = spool.list().get(0).job();
        assertThat(j.lastError()).startsWith("[PORTAL] 제논 WF9 HTTP 401").contains("GENON_WF9_AUTH_KEY")
                .doesNotContain("wf9-test-key").doesNotContain("홍길동");
        assertThat(putBodies).isEmpty();
        assertPurgeMarked("E1", 0);
    }

    @Test
    @DisplayName("로그 컬렉터 503 → 다시 하기(문서 남김) · 400 → 바로 포기 · 다시 하면 같은 가린 문서로 WF9 만 다시")
    void storeFailures() throws Exception {
        putStatus = 503;
        offer("E1", meet(REC));

        assertThat(worker.drain().retried()).isEqualTo(1);
        Wf9Job j = spool.list().get(0).job();
        assertThat(j.attempts()).isEqualTo(1);
        assertThat(j.lastError()).isEqualTo("[STORE] 주요사항 적재 실패 — HTTP 503");

        putStatus = 400;
        clock.advanceSec(300);
        assertThat(worker.drain().gaveUp()).isEqualTo(1);
        assertThat(spool.list()).isEmpty();
        assertThat(chatBodies).hasSize(2);
        assertThat(chatBodies.get(1).path("message").asText()).isEqualTo(chatBodies.get(0).path("message").asText());
        assertThat(otherPaths).isEmpty();
        assertPurgeMarked("E1", 2);   // 적재 실패와 무관 — WF9 정상 응답마다
    }

    @Test
    @DisplayName("포털 MOCK — 외부망 없이 빈 결과 → 그 녹음의 행을 지우기만(signals []) · 제논에 보낸 것이 없으니 폐기 표시 없음")
    void portalMockStoresEmpty() throws Exception {
        build(RiskWf9Properties.PortalMode.MOCK, false);
        offer("E1", meet(REC));
        Wf9Spool.Entry e = spool.list().get(0);

        RiskWf9Pipeline.Outcome o = pipeline.run(e.job(), docOf(e));

        assertThat(chatBodies).isEmpty();
        assertThat(putPaths).containsExactly("PUT /logc/api/v1/logs/contact-risk-signals/" + REC);
        assertThat(putBodies.get(0).path("signals")).isEmpty();
        assertThat(o.inserted()).isZero();
        assertThat(o.purgeMarked()).isFalse();
        assertPurgeMarked("E1", 0);
    }

    @Test
    @DisplayName("purgeMarked — 로그 컬렉터가 받으면 true · 못 적어도(404 · 500) false 일 뿐 주요사항은 그대로 적재(작업 계속)")
    void purgeFailureDoesNotBlockStore() throws Exception {
        offer("E1", meet(REC));
        Wf9Spool.Entry e = spool.list().get(0);

        RiskWf9Pipeline.Outcome ok = pipeline.run(e.job(), docOf(e));
        assertThat(ok.purgeMarked()).isTrue();
        assertPurgeMarked("E1", 1);

        for (int status : new int[]{404, 500}) {
            purgeStatus = status;
            RiskWf9Pipeline.Outcome failed = pipeline.run(e.job(), docOf(e));
            assertThat(failed.purgeMarked()).as("HTTP " + status).isFalse();
            assertThat(failed.inserted()).isEqualTo(2);
        }
        assertThat(purgePaths).hasSize(3);
        assertThat(putPaths).hasSize(3);
    }
}
