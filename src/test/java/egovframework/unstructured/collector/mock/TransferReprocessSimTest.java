package egovframework.unstructured.collector.mock;

import egovframework.unstructured.collector.common.transfer.AgentConnectorClient;
import egovframework.unstructured.collector.voice.controller.VoiceMockController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전송 재처리 시나리오(7번 탭) — 에이전트 커넥터 '긴급 재처리(PPP 전송)' 와 같은 흐름:
 * ① 재현(더미 → 에이전트 커넥터 장애 → 청크 2건 → 배치) → ② 정상화 → ③ 재처리(FROM_SEND · 원배치 런 이어달리기) → 수신 장부 마감.
 */
@SpringBootTest(properties = {
        "image.admin-db.url=jdbc:h2:mem:admin-zsim;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "image.admin-db.username=sa",
        "image.admin-db.password=",
        "voice.source.mode=MOCK",
        "voice.broker.mode=MOCK",
        "voice.phone.mode=MOCK",
        "voice.stt.mode=MOCK",
        "voice.decrypt.mode=REAL",
        "agent-connector.mode=MOCK",
        "log-collector.enabled=false",
        "voice.sim.seed-on-startup=false",
        "voice.sync.wait-timeout-sec=10",
        "voice.sync.stable-check-ms=20",
        "unstructured.mock.dashboard.enabled=true",
        "unstructured.mock.daily.enabled=false"
})
class TransferReprocessSimTest {

    private static Path root;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws Exception {
        root = Files.createTempDirectory("zsim");
        Path key = root.resolve("rvs_key.txt");
        Files.writeString(key, String.join("\n",
                "secretkey=LOCAL_TEST_KEY_0123456789abcdefg", "iv=0000000000000000", "algorithm=AES",
                "ciphermode=CBC", "padding=PKCS5Padding", "charset=UTF-8"));
        registry.add("voice.dirs.base-dir", () -> root.toString().replace('\\', '/'));
        registry.add("voice.decrypt.rvs-key-path", key::toString);
    }

    @Autowired
    private TransferReprocessSimService sim;
    @Autowired
    private DummyDataService dummy;
    @Autowired
    private VoiceMockController voiceMock;
    @Autowired
    private AgentConnectorClient connector;

    @BeforeEach
    @AfterEach
    void clean() {
        dummy.cleanDashboard();
        voiceMock.deleteSimData(DummyTarget.SIMULATOR);
        sim.reset();
    }

    @Test
    @DisplayName("시나리오 2 — 3번 청크에서 503 → PARTIAL · 정상화 → 재처리가 원배치 런을 3번부터 이어 마감(6건)")
    @SuppressWarnings("unchecked")
    void partialThenResumeFromThirdChunk() {
        Map<String, Object> a = sim.arm(TransferReprocessSimService.Scenario.PARTIAL, DummyTarget.DASHBOARD, 3, 2,
                LocalDate.now().minusDays(2));
        String origin = (String) a.get("execId");
        Map<String, Object> t = (Map<String, Object>) a.get("transfer");
        List<AgentConnectorClient.Chunk> chunks = (List<AgentConnectorClient.Chunk>) t.get("chunks");

        assertThat(origin).contains("UNS");   // 대시보드용 — 실제 배치(로컬 임시 ID 도 같은 자리)
        assertThat(a.get("successCnt")).isEqualTo(4);
        assertThat(a.get("failCnt")).isEqualTo(2);
        assertThat(chunks).extracting(AgentConnectorClient.Chunk::result).containsExactly("SENT", "SENT", "FAILED");
        assertThat(((Map<String, Object>) a.get("ledger")).get("receivedSeqs")).isEqualTo(List.of(1, 2));

        sim.fix();
        Map<String, Object> g = sim.reprocess(origin, "SEND", 60_000);
        Map<String, Object> gt = (Map<String, Object>) g.get("transfer");

        assertThat(g.get("resume")).isEqualTo("FROM_SEND");
        assertThat(gt.get("continued")).isEqualTo(true);
        assertThat(gt.get("runId")).isEqualTo(origin);
        assertThat(gt.get("fromSeq")).isEqualTo(3);
        assertThat(g.get("successCnt")).isEqualTo(2);
        assertThat(g.get("skippedCnt")).isEqualTo(4);
        Map<String, Object> led = (Map<String, Object>) g.get("originLedger");
        assertThat(led.get("state")).isEqualTo("COMPLETE");
        assertThat(led.get("receivedSeqs")).isEqualTo(List.of(1, 2, 3));
        assertThat(led.get("records")).isEqualTo(6L);
    }

    @Test
    @DisplayName("시나리오 1 — 1번 청크부터 503 → FAIL(뒤 청크 NOT_SENT) · 재처리는 같은 런을 1번부터 다시 보낸다 · 초기화하면 청크 설정값")
    @SuppressWarnings("unchecked")
    void allFailThenResendFromFirst() {
        Map<String, Object> a = sim.arm(TransferReprocessSimService.Scenario.ALL_FAIL, DummyTarget.SIMULATOR, 2, 2,
                LocalDate.now().minusDays(3));
        String origin = (String) a.get("execId");
        Map<String, Object> t = (Map<String, Object>) a.get("transfer");

        assertThat(origin).contains("TST");
        assertThat(a.get("successCnt")).isEqualTo(0);
        assertThat((List<AgentConnectorClient.Chunk>) t.get("chunks")).extracting(AgentConnectorClient.Chunk::result)
                .containsExactly("FAILED", "NOT_SENT");

        sim.fix();
        Map<String, Object> g = sim.reprocess(origin, "SEND", 60_000);
        Map<String, Object> gt = (Map<String, Object>) g.get("transfer");

        assertThat(gt.get("fromSeq")).isEqualTo(1);
        assertThat(gt.get("continued")).isEqualTo(true);
        assertThat(((Map<String, Object>) g.get("originLedger")).get("state")).isEqualTo("COMPLETE");

        sim.reset();
        assertThat(connector.chunkRecords()).isEqualTo(50);
    }
}
