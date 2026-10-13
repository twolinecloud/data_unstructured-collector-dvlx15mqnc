package egovframework.unstructured.collector.voice.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import egovframework.unstructured.collector.common.model.BatchWindow;
import egovframework.unstructured.collector.voice.source.SimulationDataService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시험 실행(test=true · EXEC_ID TST)도 전송 제어에서 빠지지 않는다 — 커넥터가 T7(수동 확인)으로 보류하면 SEND 는 SUCCESS 가 아니라
 * 전송 대기(WAIT)로 닫힌다. 커넥터(REST)는 실제와 같은 계약으로 흉내 낸다: 비식별 API 는 받은 텍스트를 돌려주고,
 * 학습 전송은 마지막 청크에 {@code held:true · holdReason:"수동 확인"} 으로 답한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class VoiceSendHeldTest {

    @TempDir
    static Path tmp;

    private static final ObjectMapper OM = new ObjectMapper();
    private static final List<String> EXEC_HEADERS = new CopyOnWriteArrayList<>();
    private static final HttpServer CONNECTOR = start();

    private static HttpServer start() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/api/v1/pipeline/deident/voice", ex -> {
                JsonNode req = OM.readTree(ex.getRequestBody());
                Map<String, Object> res = new LinkedHashMap<>();
                res.put("text", req.path("text").asText());
                res.put("mode", "DEIDENT");
                reply(ex, res);
            });
            s.createContext("/api/v1/learn/transfer", ex -> {
                boolean gz = "gzip".equalsIgnoreCase(ex.getRequestHeaders().getFirst("Content-Encoding"));
                (gz ? new GZIPInputStream(ex.getRequestBody()) : ex.getRequestBody()).readAllBytes();
                EXEC_HEADERS.add(String.valueOf(ex.getRequestHeaders().getFirst("X-Collect-Exec-Id")));
                boolean last = Boolean.parseBoolean(ex.getRequestHeaders().getFirst("X-Is-Last"));
                Map<String, Object> ack = new LinkedHashMap<>();
                ack.put("runId", ex.getRequestHeaders().getFirst("X-Run-Id"));
                ack.put("duplicate", false);
                ack.put("complete", last);
                if (last) {
                    ack.put("held", true);
                    ack.put("holdReason", "수동 확인");
                }
                reply(ex, ack);
            });
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, Object body) throws IOException {
        byte[] b = OM.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    @AfterAll
    static void stop() {
        CONNECTOR.stop(0);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("voice.source.mode", () -> "MOCK");
        registry.add("voice.broker.mode", () -> "MOCK");
        registry.add("log-collector.enabled", () -> "false");
        registry.add("voice.dirs.base-dir", () -> tmp.toString());
        registry.add("voice.dirs.receive-meet", () -> tmp.resolve("raw/meet").toString());
        registry.add("voice.dirs.receive-phone", () -> tmp.resolve("raw/phone").toString());
        registry.add("voice.dirs.work", () -> tmp.resolve("work").toString());
        registry.add("voice.dirs.xvarm-original", () -> tmp.resolve("xvarm_original").toString());
        registry.add("voice.sync.wait-timeout-sec", () -> "15");
        registry.add("voice.sync.stable-check-ms", () -> "50");
        registry.add("agent-connector.mode", () -> "REST");
        registry.add("agent-connector.base-url", () -> "http://127.0.0.1:" + CONNECTOR.getAddress().getPort());
    }

    @Autowired
    private VoiceCollectService collect;
    @Autowired
    private SimulationDataService sim;
    @Autowired
    private IdempotencyGuard idempotency;

    @BeforeEach
    void seed() {
        idempotency.clearAll();
        EXEC_HEADERS.clear();
        sim.seedPerf(2, 2, 0);
    }

    @Test
    @DisplayName("시험 실행(TST) · 커넥터 보류 — 전송은 받아들여졌지만 SEND 는 WAIT · 결과에 held · 청크마다 배치 키(X-Collect-Exec-Id)")
    void testRunHeldLeavesSendWaiting() {
        LocalDate today = LocalDate.now();
        BatchWindow w = BatchWindow.manual(today.minusDays(1).atStartOfDay(), today.atStartOfDay());

        VoiceBatchResult r = collect.run(w, null, "TEST", true, ResumeMode.FULL, null, 1);

        assertThat(r.execId()).as("시험 실행 — 작업코드 자리가 TST").contains("TST");
        assertThat(r.failCnt()).isZero();
        assertThat(r.successCnt()).isEqualTo(4);
        VoiceBatchResult.StepLog send = r.steps().stream().filter(s -> "SEND".equals(s.stepTypeCd())).findFirst().orElseThrow();
        assertThat(send.stepStsCd()).as("보류 — SUCCESS 가 아니라 전송 대기").isEqualTo("WAIT");
        assertThat(r.steps()).filteredOn(s -> !"SEND".equals(s.stepTypeCd()))
                .allSatisfy(s -> assertThat(s.stepStsCd()).isEqualTo("SUCCESS"));
        assertThat(r.transfer()).containsEntry("held", "true").containsEntry("holdReason", "수동 확인");
        assertThat(EXEC_HEADERS).isNotEmpty().containsOnly(r.execId());
    }
}
