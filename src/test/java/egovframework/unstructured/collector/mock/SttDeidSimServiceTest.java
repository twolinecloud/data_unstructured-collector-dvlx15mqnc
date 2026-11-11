package egovframework.unstructured.collector.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import egovframework.unstructured.collector.common.config.DeployEnvPreset;
import egovframework.unstructured.collector.common.config.VoiceDirState;
import egovframework.unstructured.collector.common.config.VoiceProperties;
import egovframework.unstructured.collector.common.logging.FakeLogCollector;
import egovframework.unstructured.collector.common.logging.LogCollectorClient;
import egovframework.unstructured.collector.common.transfer.AgentConnectorClient;
import egovframework.unstructured.collector.common.transfer.AgentConnectorProperties;
import egovframework.unstructured.collector.common.util.InmatePidGenerator;
import egovframework.unstructured.collector.voice.stt.genon.GenonSttClient;
import egovframework.unstructured.collector.voice.stt.genon.GenonSttProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 음성 STT · 비식별 파이프라인 — 제논은 MOCK(네트워크 없음), 커넥터는 내장 MOCK 또는 가짜 커넥터(실제 HTTP),
 * 로그 컬렉터는 {@link FakeLogCollector}(실제 HTTP · H2 표)로 돌려 <b>대시보드가 읽는 T1 · T2 · T4</b> 를 그대로 본다.
 *
 * <ul>
 *   <li>SIM — 로그 컬렉터를 부르지 않고 적재 파일도 없다(화면 결과만)</li>
 *   <li>REAL — T1(작업 VOICE_STT_DEID · 비정형 · 구간 없음) · T2(COLLECT · ANALYZE · DEIDENT · STORE) · T4 · 가린 글만 적재</li>
 *   <li>커넥터 504 DEID_TIMEOUT — DEIDENT 실패로 T1 · T2 · T4 에 [TIMEOUT] [DEIDENT]</li>
 *   <li>600초를 넘으면 조각내 잇고 · 일 단위는 3~5건 · 실행 중 화면 실행은 거절(409) · 일 단위는 줄을 선다</li>
 * </ul>
 */
class SttDeidSimServiceTest {

    private static final ObjectMapper OM = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @TempDir
    Path root;

    private FakeLogCollector logc;
    private LogCollectorClient lc;
    private final List<SttDeidSimService> services = new ArrayList<>();

    /** 가짜 커넥터 — 이름 · 번호를 토큰으로 바꿔 돌려준다(사전도 함께 — 수집기가 버리는지 본다). */
    private HttpServer connector;
    private volatile int connectorStatus = 200;
    private volatile CountDownLatch gate;
    private final List<String> connectorQueries = new CopyOnWriteArrayList<>();
    private final List<JsonNode> connectorBodies = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        logc = FakeLogCollector.start();
        lc = new LogCollectorClient(OM, mock(DeployEnvPreset.class));
        ReflectionTestUtils.setField(lc, "baseUrl", logc.baseUrl());
        ReflectionTestUtils.setField(lc, "enabled", true);
        ReflectionTestUtils.setField(lc, "connectTimeoutSec", 2);
        ReflectionTestUtils.setField(lc, "readTimeoutSec", 10);
        ReflectionTestUtils.invokeMethod(lc, "init");
        startConnector();
    }

    @AfterEach
    void tearDown() {
        CountDownLatch g = gate;
        if (g != null) {
            g.countDown();
        }
        services.forEach(SttDeidSimService::shutdown);
        connector.stop(0);
        logc.close();
    }

    @Test
    @DisplayName("SIM — 화면 결과만: 전사(MOCK) + 가상 문장 → 커넥터 내장 MOCK 토큰 · 로그 컬렉터 무호출 · 적재 파일 없음")
    void simKeepsResultsOnScreenOnly() throws Exception {
        SttDeidSimService s = service(AgentConnectorProperties.Mode.MOCK, props(true, 3, 5, 20, 300));
        String id = (String) s.submit("S20", null, "MEET", "SIM", null).get("runId");
        Map<String, Object> r = awaitIdle(s, id);

        assertThat(r.get("status")).isEqualTo("SUCCESS");
        assertThat(r.get("mode")).isEqualTo("SIM");
        assertThat(r.get("logged")).isEqualTo(false);
        assertThat(r.get("execId")).isNull();
        assertThat(notes(r)).anyMatch(n -> n.startsWith("SIM"));
        Map<String, Object> it = item(r, 0);
        assertThat(it.get("status")).isEqualTo("SUCCESS");
        assertThat(it.get("audio")).isEqualTo("16000Hz · 모노 · 16bit · 20.0초");
        assertThat(it.get("fileSize")).isEqualTo(44L + 20 * 16_000 * 2);
        Map<String, Object> stt = sub(it, "stt");
        assertThat(stt.get("engine")).isNotNull();
        assertThat(stt.get("chunkTotal")).isEqualTo(1);
        assertThat((List<?>) stt.get("chunks")).hasSize(1);
        assertThat((String) stt.get("text")).contains("제논 STT 모의 응답").contains("010-1234-5678");
        assertThat(stt.get("seededLines")).isEqualTo(SttDeidSimService.SEED_LINES);
        Map<String, Object> deid = sub(it, "deid");
        assertThat(deid.get("mode")).isEqualTo("MOCK");
        assertThat((String) deid.get("text")).contains("[PHONE_1]").doesNotContain("010-1234-5678")
                .contains("보호자 김철수 씨 연락처는 [PHONE_1] 입니다.");
        assertThat(deid.get("tokens")).isEqualTo(2);
        assertThat(deid.get("uniqueTokens")).isEqualTo(1);
        assertThat(it.get("storedAt")).isNull();

        assertThat(logc.rows("SELECT * FROM tb_batch_exec_log")).isEmpty();
        assertThat(connectorQueries).isEmpty();
        assertThat(root.resolve("base").resolve(SttDeidSimService.RESULT_DIR)).doesNotExist();
        assertThat(root.resolve("work").resolve(id)).as("작업 폴더(음원 · 조각)는 지운다").doesNotExist();
        assertThat(s.runs()).extracting(m -> m.get("runId")).containsExactly(id);
    }

    @Test
    @DisplayName("REAL — T1(VOICE_STT_DEID · 비정형 · MANUAL · 구간 없음) · T2 4단계 · T4(STORE) · 가린 글만 적재 · 커넥터에 실행 ID")
    void realLeavesBatchHistory() throws Exception {
        SttDeidSimService s = service(AgentConnectorProperties.Mode.REST, props(true, 3, 5, 20, 300));
        String id = (String) s.submit("S20", null, "PHONE", "REAL", true).get("runId");
        Map<String, Object> r = awaitIdle(s, id);

        assertThat(r.get("status")).isEqualTo("SUCCESS");
        assertThat(r.get("logged")).isEqualTo(true);
        String execId = (String) r.get("execId");
        assertThat(execId).matches("\\d{8}UNS\\d{3}");

        Map<String, Object> t1 = logc.rows("SELECT * FROM tb_batch_exec_log WHERE exec_id = ?", execId).get(0);
        assertThat(t1.get("job_id")).isEqualTo("VOICE_STT_DEID");
        assertThat(t1.get("job_nm")).isEqualTo("음성 STT·비식별 검증");
        assertThat(t1.get("data_type_cd")).isEqualTo("UNSTRUCTURED");
        assertThat(t1.get("exec_type_cd")).isEqualTo("MANUAL");
        assertThat(t1.get("trigger_by")).isEqualTo("SIMULATOR");
        assertThat(t1.get("target_to_dtm")).as("구간 없음 — 워터마크에 안 섞인다").isNull();
        assertThat(t1.get("exec_sts_cd")).isEqualTo("SUCCESS");
        assertThat(t1).containsEntry("target_cnt", 1L).containsEntry("success_cnt", 1L).containsEntry("fail_cnt", 0L);

        Map<String, Map<String, Object>> t2 = t2(execId);
        assertThat(t2).containsOnlyKeys("COLLECT", "ANALYZE", "DEIDENT", "STORE");
        t2.values().forEach(st -> assertThat(st).containsEntry("step_sts_cd", "SUCCESS")
                .containsEntry("in_cnt", 1L).containsEntry("out_cnt", 1L).containsEntry("err_cnt", 0L));

        Map<String, Object> it = item(r, 0);
        List<Map<String, Object>> t4 = logc.rows("SELECT * FROM tb_file_proc_log WHERE exec_id = ?", execId);
        assertThat(t4).hasSize(1);
        assertThat(t4.get(0)).containsEntry("rec_file_id", it.get("recFileId")).containsEntry("proc_sts_cd", "SUCCESS")
                .containsEntry("step_type_cd", "STORE").containsEntry("file_size", 44L + 20 * 16_000 * 2)
                .containsEntry("inmate_pid", it.get("inmatePid"));
        assertThat(t4.get(0).get("err_stack")).isNull();
        assertThat((String) t4.get(0).get("file_nm")).matches("PHONE_\\d{8}_\\d{6}_01_20s\\.wav");
        assertThat((String) t4.get(0).get("rec_file_id")).hasSizeLessThanOrEqualTo(50);

        // 커넥터 — 적재 안 함 · STT 출처 · 실행 ID 를 싣는다
        assertThat(connectorQueries).hasSize(1);
        assertThat(connectorQueries.get(0)).contains("persist=false").contains("includeMapping=false")
                .contains("srcSysCd=STT").contains("docId=STT_SESSION%3A" + it.get("sttSessionId"));
        assertThat(connectorBodies.get(0).path("execId").asText()).isEqualTo(execId);
        assertThat(connectorBodies.get(0).path("STT_VISIT").path("TRANSCRIPT_TEXT").asText())
                .endsWith(String.join("\n", SttDeidSimService.SEED_LINES));

        // 적재 — 가린 글만. 원문 전사 · 사전 · 원래 값 없음
        Path stored = Path.of((String) it.get("storedAt"));
        assertThat(stored).isEqualTo(root.resolve("base").resolve(SttDeidSimService.RESULT_DIR).resolve(execId)
                .resolve(it.get("recFileId") + ".json"));
        String json = Files.readString(stored, StandardCharsets.UTF_8);
        JsonNode doc = OM.readTree(json);
        assertThat(doc.path("deidentifiedText").asText()).contains("[NAME_1]").contains("[PHONE_1]").contains("[ACCOUNT_1]");
        assertThat(doc.has("transcript")).isFalse();
        assertThat(doc.path("seededPii").asBoolean()).isTrue();
        assertThat(json).doesNotContain("김철수").doesNotContain("이영희").doesNotContain("010-1234-5678")
                .doesNotContain("110-222-333444").doesNotContain("origin_mapping");
        Map<String, Object> deid = sub(it, "deid");
        assertThat(deid.get("connectorExecId")).isEqualTo(execId);
        assertThat(deid.get("tokenTypes")).isEqualTo(Map.of("PHONE", 1, "NAME", 2, "ACCOUNT", 1));
    }

    @Test
    @DisplayName("커넥터 504 DEID_TIMEOUT — DEIDENT 실패: T2 · T4 · T1 에 [TIMEOUT] [DEIDENT] · 적재 단계 없음")
    void deidTimeoutFailsTheBatch() throws Exception {
        connectorStatus = 504;
        SttDeidSimService s = service(AgentConnectorProperties.Mode.REST, props(true, 3, 5, 20, 300));
        String id = (String) s.submit("S20", null, "MEET", "REAL", null).get("runId");
        Map<String, Object> r = awaitIdle(s, id);

        assertThat(r.get("status")).isEqualTo("FAIL");
        String execId = (String) r.get("execId");
        Map<String, Object> it = item(r, 0);
        assertThat(it.get("failedStep")).isEqualTo("DEIDENT");
        assertThat((String) it.get("errStack")).startsWith("[TIMEOUT] [DEIDENT] 커넥터 비식별 타임아웃 — HTTP 504 DEID_TIMEOUT");
        assertThat(sub(it, "deid")).containsEntry("httpStatus", 504).containsEntry("code", "DEID_TIMEOUT");
        assertThat((String) sub(it, "stt").get("text")).as("STT 는 끝났다").isNotBlank();

        Map<String, Map<String, Object>> t2 = t2(execId);
        assertThat(t2).containsOnlyKeys("COLLECT", "ANALYZE", "DEIDENT");
        assertThat(t2.get("ANALYZE")).containsEntry("step_sts_cd", "SUCCESS");
        assertThat(t2.get("DEIDENT")).containsEntry("step_sts_cd", "FAIL").containsEntry("in_cnt", 1L)
                .containsEntry("out_cnt", 0L).containsEntry("err_cnt", 1L).containsEntry("err_stack", it.get("errStack"));
        Map<String, Object> t4 = logc.rows("SELECT * FROM tb_file_proc_log WHERE exec_id = ?", execId).get(0);
        assertThat(t4).containsEntry("proc_sts_cd", "FAIL").containsEntry("step_type_cd", "DEIDENT")
                .containsEntry("err_stack", it.get("errStack"));
        Map<String, Object> t1 = logc.rows("SELECT * FROM tb_batch_exec_log WHERE exec_id = ?", execId).get(0);
        assertThat(t1).containsEntry("exec_sts_cd", "FAIL").containsEntry("success_cnt", 0L).containsEntry("fail_cnt", 1L)
                .containsEntry("err_msg", it.get("errStack"));
        assertThat(root.resolve("base").resolve(SttDeidSimService.RESULT_DIR)).doesNotExist();
    }

    @Test
    @DisplayName("600초 초과 — 조각 2개로 전사해 잇는다(구간 시각은 원래 음원 기준) · 비식별은 이은 글 한 번")
    void longAudioIsSplitAndMerged() throws Exception {
        SttDeidSimService s = service(AgentConnectorProperties.Mode.MOCK, props(true, 3, 5, 20, 300));
        String id = (String) s.submit(null, 601, "MEET", "SIM", true).get("runId");
        Map<String, Object> r = awaitIdle(s, id);

        assertThat(r.get("status")).isEqualTo("SUCCESS");
        Map<String, Object> it = item(r, 0);
        assertThat(it.get("preset")).isNull();
        assertThat(it.get("seconds")).isEqualTo(601);
        Map<String, Object> stt = sub(it, "stt");
        assertThat(stt.get("chunkTotal")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chunks = (List<Map<String, Object>>) stt.get("chunks");
        assertThat(chunks).hasSize(2);
        assertThat((double) chunks.get(0).get("startSec")).isZero();
        assertThat((double) chunks.get(0).get("endSec")).isEqualTo((double) chunks.get(1).get("startSec"));
        assertThat((double) chunks.get(1).get("endSec")).isCloseTo(601.0, within(0.01));
        assertThat(chunks).allSatisfy(c -> assertThat((double) c.get("endSec") - (double) c.get("startSec"))
                .isLessThanOrEqualTo(600.0));
        assertThat(stt.get("segments")).isEqualTo(8);
        assertThat(((String) stt.get("text")).split("\n")).hasSize(2);
        Map<String, Object> deid = sub(it, "deid");
        assertThat(deid.get("tokens")).as("조각 두 개의 번호 + 가상 문장 번호 — 같은 값은 같은 토큰").isEqualTo(3);
        assertThat(deid.get("uniqueTokens")).isEqualTo(1);
        assertThat(root.resolve("work").resolve(id)).doesNotExist();
    }

    @Test
    @DisplayName("일 단위 — 3~5건 · 20~N초 · 스케줄러는 T1 SCHEDULED · 건마다 T4 · 꺼져 있으면 건너뜀")
    void dailyQueuesThreeToFiveShortItems() throws Exception {
        SttDeidSimService s = service(AgentConnectorProperties.Mode.MOCK, props(true, 3, 5, 20, 30));
        Map<String, Object> out = s.submitDaily(SttDeidSimService.Mode.REAL, "SCHEDULER");
        assertThat(out).containsEntry("mode", "REAL").containsEntry("triggerBy", "SCHEDULER")
                .containsEntry("execType", "SCHEDULED").containsEntry("queued", true);
        @SuppressWarnings("unchecked")
        List<String> labels = (List<String>) out.get("items");
        assertThat(labels).hasSizeBetween(3, 5).allMatch(l -> l.matches("(접견|전화) \\d+초"));

        Map<String, Object> r = awaitIdle(s, (String) out.get("runId"));
        assertThat(r.get("status")).isEqualTo("SUCCESS");
        assertThat(r.get("trigger")).isEqualTo("DAILY");
        int n = labels.size();
        assertThat((List<?>) r.get("items")).hasSize(n);
        for (int i = 0; i < n; i++) {
            assertThat((int) item(r, i).get("seconds")).isBetween(20, 30);
        }
        String execId = (String) r.get("execId");
        Map<String, Object> t1 = logc.rows("SELECT * FROM tb_batch_exec_log WHERE exec_id = ?", execId).get(0);
        assertThat(t1).containsEntry("exec_type_cd", "SCHEDULED").containsEntry("trigger_by", "SCHEDULER")
                .containsEntry("exec_sts_cd", "SUCCESS").containsEntry("target_cnt", (long) n)
                .containsEntry("success_cnt", (long) n);
        assertThat(t2(execId).get("STORE")).containsEntry("in_cnt", (long) n).containsEntry("out_cnt", (long) n);
        assertThat(logc.rows("SELECT * FROM tb_file_proc_log WHERE exec_id = ? AND proc_sts_cd = 'SUCCESS'", execId))
                .hasSize(n);
        assertThat(s.status().get("lastDaily")).isNotNull();

        SttDeidSimService off = service(AgentConnectorProperties.Mode.MOCK, props(false, 3, 5, 20, 30));
        assertThat(off.submitDaily(SttDeidSimService.Mode.REAL, "SCHEDULER")).containsKey("skipped");
        assertThat(off.runs()).isEmpty();
    }

    @Test
    @DisplayName("한 번에 하나 — 돌고 있으면 화면 실행은 409 · 일 단위는 줄을 서서 뒤이어 돈다")
    void busyRejectsScreenRunButDailyQueues() throws Exception {
        gate = new CountDownLatch(1);
        SttDeidSimService s = service(AgentConnectorProperties.Mode.REST, props(true, 3, 3, 20, 20));
        String first = (String) s.submit("S20", null, "MEET", "SIM", null).get("runId");
        long deadline = System.currentTimeMillis() + 30_000;
        while (connectorQueries.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(connectorQueries).as("첫 실행이 커넥터 응답을 기다리는 중").hasSize(1);
        assertThat(s.status()).containsEntry("busy", true);
        assertThat(sub(s.status(), "active")).containsEntry("runId", first).containsEntry("stage", "DEID");

        IllegalStateException busy = assertThrows(IllegalStateException.class,
                () -> s.submit("S20", null, "MEET", "SIM", null));
        assertThat(busy.getMessage()).contains("이미").contains(first);

        Map<String, Object> daily = s.submitDaily(SttDeidSimService.Mode.SIM, "SIMULATOR");
        assertThat(daily).containsEntry("ahead", 1).containsEntry("execType", "MANUAL");

        gate.countDown();
        Map<String, Object> r2 = awaitIdle(s, (String) daily.get("runId"));
        assertThat(s.run(first).get("status")).isEqualTo("SUCCESS");
        assertThat(r2.get("status")).isEqualTo("SUCCESS");
        assertThat((List<?>) r2.get("items")).hasSize(3);
        assertThat(s.runs()).extracting(m -> m.get("runId")).containsExactly(daily.get("runId"), first);
        assertThat(logc.rows("SELECT * FROM tb_batch_exec_log")).as("둘 다 SIM").isEmpty();
    }

    @Test
    @DisplayName("값 검사 — 프리셋 · 유형 · 모드 · 길이(1~3600초) · 없는 실행은 NoSuchElement")
    void rejectsBadInput() {
        SttDeidSimService s = service(AgentConnectorProperties.Mode.MOCK, props(true, 3, 5, 20, 300));
        assertThat(assertThrows(IllegalArgumentException.class, () -> s.submit("M45", null, null, null, null))
                .getMessage()).contains("S20 · M5 · M10 · M20 · M30");
        assertThrows(IllegalArgumentException.class, () -> s.submit(null, null, "VIDEO", null, null));
        assertThrows(IllegalArgumentException.class, () -> s.submit(null, null, null, "TEST", null));
        assertThrows(IllegalArgumentException.class, () -> s.submit(null, 0, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> s.submit(null, 3601, null, null, null));
        assertThat(s.runs()).isEmpty();
        assertThrows(java.util.NoSuchElementException.class, () -> s.run("SD00000000-000000-00"));
    }

    @Test
    @DisplayName("REAL 인데 로그 컬렉터 미연동 — 로컬 실행 ID 로 돌고 이력은 남기지 않는다(화면에 안내)")
    void realWithoutLogCollectorUsesLocalId() throws Exception {
        ReflectionTestUtils.setField(lc, "enabled", false);
        SttDeidSimService s = service(AgentConnectorProperties.Mode.MOCK, props(true, 3, 5, 20, 300));
        String id = (String) s.submit("S20", null, "MEET", "REAL", false).get("runId");
        Map<String, Object> r = awaitIdle(s, id);
        assertThat(r.get("status")).isEqualTo("SUCCESS");
        assertThat(r.get("logged")).isEqualTo(false);
        assertThat((String) r.get("execId")).matches("\\d{8}UNS\\d{6}");
        assertThat(notes(r)).anyMatch(n -> n.contains("로그 컬렉터 미연동"));
        assertThat(logc.rows("SELECT * FROM tb_batch_exec_log")).isEmpty();
        assertThat((List<?>) sub(item(r, 0), "stt").get("seededLines")).isEmpty();
    }

    // ------------------------------------------------------------------ 도우미

    private SttDeidSimService service(AgentConnectorProperties.Mode connectorMode, SttDeidProperties p) {
        GenonSttClient genon = new GenonSttClient(new GenonSttProperties(GenonSttProperties.Mode.MOCK, "", "", "2026-12-11", 10, 2,
                20_971_520L, 600, 1, 30, 3, 300, 600, 15), OM);
        AgentConnectorClient ac = mock(AgentConnectorClient.class);
        when(ac.effectiveMode()).thenReturn(connectorMode);
        AgentConnectorProperties cp = new AgentConnectorProperties(connectorMode,
                "http://127.0.0.1:" + connector.getAddress().getPort(), "/api/v1/learn/transfer", true,
                "UNSTRUCTURED", "VOICE", 50, 52_428_800L, 1000, 5000, 500, 536_870_912L, 60);
        TranscriptDeidRunClient deid = new TranscriptDeidRunClient(ac, cp, p, OM);
        InmatePidGenerator pids = mock(InmatePidGenerator.class);
        when(pids.of(anyString())).thenAnswer(inv -> "VP" + Integer.toHexString(inv.getArgument(0, String.class).hashCode()));
        VoiceDirState dirs = mock(VoiceDirState.class);
        when(dirs.baseDir()).thenReturn(root.resolve("base").toString());
        VoiceProperties vp = mock(VoiceProperties.class);
        when(vp.batch()).thenReturn(new VoiceProperties.Batch(20, false, List.of("0"), 500, "VOICE_ANALYSIS",
                "TEST_BATCH", "UNSTRUCTURED", false));
        SttDeidSimService s = new SttDeidSimService(genon, deid, lc, pids, dirs, vp, p, OM, root.resolve("work"));
        services.add(s);
        return s;
    }

    private static SttDeidProperties props(boolean daily, int minItems, int maxItems, int minSec, int maxSec) {
        return new SttDeidProperties("VOICE_STT_DEID", "음성 STT·비식별 검증", "/api/v1/deid/run", 10, "", true, 20,
                new SttDeidProperties.Daily(daily, minItems, maxItems, minSec, maxSec));
    }

    /** 줄 선 실행까지 다 끝날 때까지 — 그다음 그 실행을 읽는다. */
    private static Map<String, Object> awaitIdle(SttDeidSimService s, String runId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            if (((Number) s.status().get("pending")).intValue() == 0 && s.run(runId).get("endedAt") != null) {
                return s.run(runId);
            }
            Thread.sleep(25);
        }
        throw new AssertionError("60초 안에 끝나지 않음: " + s.run(runId));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> item(Map<String, Object> run, int i) {
        return ((List<Map<String, Object>>) run.get("items")).get(i);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sub(Map<String, Object> m, String key) {
        return (Map<String, Object>) m.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<String> notes(Map<String, Object> run) {
        return (List<String>) run.get("notes");
    }

    private Map<String, Map<String, Object>> t2(String execId) {
        return logc.rows("SELECT * FROM tb_batch_step_log WHERE exec_id = ?", execId).stream()
                .collect(Collectors.toMap(r -> (String) r.get("step_type_cd"), Function.identity()));
    }

    private void startConnector() throws IOException {
        connector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        connector.createContext("/", ex -> {
            try {
                connectorQueries.add(ex.getRequestURI().getRawQuery());
                JsonNode req = OM.readTree(ex.getRequestBody().readAllBytes());
                connectorBodies.add(req);
                CountDownLatch g = gate;
                if (g != null) {
                    g.await(20, TimeUnit.SECONDS);
                }
                String body;
                if (connectorStatus == 504) {
                    body = "{\"status\":504,\"code\":\"DEID_TIMEOUT\",\"message\":\"현재 비식별 처리 작업이 많아 지연되고 있습니다.\"}";
                } else {
                    String masked = req.path("STT_VISIT").path("TRANSCRIPT_TEXT").asText()
                            .replace("김철수", "[NAME_1]").replace("이영희", "[NAME_2]")
                            .replace("010-1234-5678", "[PHONE_1]").replace("110-222-333444", "[ACCOUNT_1]");
                    ObjectNode res = OM.createObjectNode();
                    res.put("success", true);
                    res.put("execId", req.path("execId").asText("20261111VOC001"));
                    ObjectNode item = res.putArray("items").addObject();
                    item.put("deidentStsCd", "SUCCESS");
                    item.put("piiItemCnt", 1);
                    item.putObject("deidentedDataset").putObject("STT_VISIT").put("TRANSCRIPT_TEXT", masked);
                    item.putObject("reidMapping").put("origin_mapping", "{\"[NAME_1]\":\"김철수\",\"[NAME_2]\":\"이영희\"}");
                    body = OM.writeValueAsString(res);
                }
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(connectorStatus, b.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(b);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                ex.close();
            }
        });
        connector.start();
    }
}
