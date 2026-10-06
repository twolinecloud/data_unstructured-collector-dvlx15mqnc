package egovframework.unstructured.collector.voice.batch;

import egovframework.unstructured.collector.voice.controller.VoiceBatchController;
import egovframework.unstructured.collector.voice.controller.VoiceMockController;
import egovframework.unstructured.collector.common.model.BatchWindow;
import egovframework.unstructured.collector.common.model.VoiceKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시뮬레이터 흐름 — 0건 확인 · 미처리 전체 Catch-up · 부분 실패 · 초기화 · 검증 패널.
 *
 * <p>화면 버튼이 부르는 API 를 그대로 부른다. 여기서 깨지면 화면에서도 깨진다.</p>
 */
@SpringBootTest
@ActiveProfiles("local")
class SimulatorWorkflowTest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void dirs(DynamicPropertyRegistry registry) {
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
    }

    @Autowired
    private VoiceCollectService service;
    @Autowired
    private VoiceBatchController batches;
    @Autowired
    private VoiceMockController mock;
    @Autowired
    private StageFaultState faults;
    @Autowired
    private egovframework.unstructured.collector.voice.source.SimulationDataService sim;
    @Autowired
    private IdempotencyGuard idempotency;
    @Autowired
    private egovframework.unstructured.collector.common.transfer.AgentConnectorClient connector;
    @Autowired
    private egovframework.unstructured.collector.voice.stt.SttTempStore sttTemp;

    @BeforeEach
    void seed() {
        faults.clear();
        sim.seed();
        idempotency.clearAll();
    }

    @AfterEach
    void clearFaults() {
        faults.clear();
    }

    private static long num(Map<String, Object> m, String k) {
        return ((Number) m.get(k)).longValue();
    }

    // ── 0건 확인 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("미처리 건수 — 돌리기 전엔 전부 미처리, 돌린 뒤엔 0건(화면이 '생성할까요?' 를 묻는 조건)")
    void pendingDropsToZeroAfterRun() {
        Map<String, Object> before = batches.pending("daily", null, null, true);
        assertThat(num(before, "total")).isEqualTo(10);
        assertThat(num(before, "pending")).isEqualTo(10);

        service.run(BatchWindow.daily(LocalDateTime.now()), null, "TEST", true);

        Map<String, Object> after = batches.pending("daily", null, null, true);
        assertThat(num(after, "total")).as("대상 자체는 그대로 있다").isEqualTo(10);
        assertThat(num(after, "processed")).isEqualTo(10);
        assertThat(num(after, "pending")).as("전부 처리됨 — 그대로 누르면 전부 건너뜀").isZero();
    }

    @Test
    @DisplayName("미처리 건수는 배치를 열지 않는다 — 멱등 표식이 생기지 않는다")
    void pendingHasNoSideEffects() {
        batches.pending("on-demand", null, null, true);
        batches.pending("daily", null, null, true);

        assertThat(num(batches.pending("daily", null, null, true), "pending")).isEqualTo(10);
    }

    // ── [바로 실행] Catch-up ──────────────────────────────────────────────

    @Test
    @DisplayName("[바로 실행]은 일배치 몫(어제)·주기배치 몫(오늘)을 가리지 않고 미처리 전부를 처리한다")
    void onDemandCatchesUpEverything() {
        Map<String, Object> p = batches.pending("on-demand", null, null, true);
        assertThat(num(p, "pending")).as("어제 5+5 · 오늘 2+2").isEqualTo(14);

        VoiceBatchResult r = batches.onDemand(null, true, null);

        assertThat(r.targetCnt()).isEqualTo(14);
        assertThat(r.successCnt()).isEqualTo(14);
        assertThat(r.failCnt()).isZero();
        assertThat(num(batches.pending("on-demand", null, null, true), "pending")).isZero();
    }

    @Test
    @DisplayName("일배치를 먼저 돌려도 [바로 실행]이 남은 주기배치 몫 4건을 주워 간다")
    void onDemandAfterDailyPicksUpTheRest() {
        service.run(BatchWindow.daily(LocalDateTime.now()), null, "TEST", true);

        VoiceBatchResult r = batches.onDemand(null, true, null);

        assertThat(r.successCnt()).isEqualTo(4);
        assertThat(r.skippedCnt()).as("일배치가 끝낸 10건은 멱등으로 건너뜀").isEqualTo(10);
    }

    // ── 부분 실패 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("부분 실패 10% — 접견 5건 중 정확히 1건만 떨어지고 배치는 PARTIAL")
    void partialFailureFailsOnlyTheQuota() {
        faults.set(StageFaultState.Stage.ANALYZE, StageFaultState.Mode.PARTIAL, 10);

        VoiceBatchResult r = service.run(BatchWindow.daily(LocalDateTime.now()),
                List.of(VoiceKind.MEET), "TEST", true);

        assertThat(r.targetCnt()).isEqualTo(5);
        assertThat(r.failCnt()).as("5 × 10% = 0.5 → 1건").isEqualTo(1);
        assertThat(r.successCnt()).isEqualTo(4);
        assertThat(r.execStsCd()).isEqualTo("PARTIAL");
    }

    @Test
    @DisplayName("전체 실패 — 같은 5건이 전부 떨어지고 FAIL")
    void allFailureFailsEverything() {
        faults.set(StageFaultState.Stage.ANALYZE, StageFaultState.Mode.ALL, null);

        VoiceBatchResult r = service.run(BatchWindow.daily(LocalDateTime.now()),
                List.of(VoiceKind.MEET), "TEST", true);

        assertThat(r.failCnt()).isEqualTo(5);
        assertThat(r.execStsCd()).isEqualTo("FAIL");
    }

    // ── 에이전트 커넥터 전송 실패 → 재처리(FROM_SEND) ─────────────────────────────────

    @Test
    @DisplayName("에이전트 커넥터 전송 실패 → 전사 보존 · 수신증 없음 → FROM_SEND 재처리가 보존된 전사로 다시 보내고 보존물을 지운다")
    @SuppressWarnings("unchecked")
    void sendFailureResumesFromTranscript() {
        faults.set(StageFaultState.Stage.SEND, StageFaultState.Mode.ALL, null);

        VoiceBatchResult first = service.run(BatchWindow.daily(LocalDateTime.now()),
                List.of(VoiceKind.MEET), "TEST", true);

        assertThat(first.failCnt()).isEqualTo(5);
        assertThat(first.outcomes()).allSatisfy(o -> assertThat(o.failedStep()).isEqualTo(egovframework.unstructured.collector.common.model.FileProcOutcome.STEP_SEND));
        assertThat(first.steps()).extracting(VoiceBatchResult.StepLog::stepTypeCd)
                .containsExactly("COLLECT", "ANALYZE", "SEND");
        assertThat(connector.receipts(first.execId())).as("실패한 건은 보내지 않았다").isEmpty();
        assertThat(((Number) sttTemp.status().get("total")).intValue()).as("전사 보존물이 남는다").isEqualTo(5);

        List<String> failedKeys = first.outcomes().stream().map(o -> o.target().idempotencyKey()).toList();
        faults.clear();
        // 재처리 창(이틀)에는 첫 실행이 다루지 않은 주기용 SIM 2건도 들어온다 — 그 2건은 STT 부터, 실패했던 5건은 전송부터
        VoiceBatchResult again = batches.resume(ResumeMode.FROM_SEND, first.execId(), List.of(VoiceKind.MEET), true, 2880);

        assertThat(again.failCnt()).isZero();
        assertThat(again.successCnt()).isEqualTo(7);
        assertThat(connector.receipts(again.execId())).extracting(r -> String.valueOf(r.metadata().get("idempotency_key")))
                .as("전송에서 깨졌던 5건이 이번에 에이전트 커넥터로 갔다").containsAll(failedKeys);
        assertThat(((Number) sttTemp.status().get("total")).intValue()).as("보낸 뒤 보존물 정리(Purge)").isZero();
    }

    // ── 초기화 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("초기화가 재처리 보존물(stt_temp · 복호화 오디오)까지 지운다")
    void resetClearsResumeLeftovers() throws Exception {
        Path temp = tmp.resolve("stt_temp").resolve("20260927TST001").resolve("meet-SIM-MEET-001.json");
        Files.createDirectories(temp.getParent());
        Files.writeString(temp, "{\"transcript\":{\"text\":\"남은 전사\"}}");
        Path audio = tmp.resolve("work").resolve("decrypted_mock_meet_001.m4a");
        Files.createDirectories(audio.getParent());
        Files.write(audio, new byte[] {1, 2, 3});
        Path other = tmp.resolve("work").resolve("keep_me.txt");
        Files.writeString(other, "다른 파일");

        Map<String, Object> res = mock.deleteTestData();

        @SuppressWarnings("unchecked")
        Map<String, Object> local = (Map<String, Object>) res.get("local");
        assertThat(temp).doesNotExist();
        assertThat(audio).doesNotExist();
        assertThat(other).as("우리 접두사가 아닌 파일은 건드리지 않는다").exists();
        assertThat(((Number) local.get("sttTempFiles")).intValue()).isEqualTo(1);
        assertThat(((Number) local.get("decryptedAudio")).intValue()).isEqualTo(1);
    }

    // ── 검증 패널 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("검증 패널 — PV 파일 현황과 손으로 돌릴 SQL·명령을 같이 준다")
    @SuppressWarnings("unchecked")
    void verifyReportsFilesAndCommands() {
        VoiceBatchResult r = service.run(BatchWindow.daily(LocalDateTime.now()), null, "TEST", true);

        Map<String, Object> v = batches.verify(r.execId());

        Map<String, Object> db = (Map<String, Object>) v.get("db");
        assertThat(db).containsEntry("available", false);
        assertThat((String) db.get("reason")).as("컬렉터를 끈 구성").contains("미연동");

        // 결과는 PV 에 남기지 않는다 — 전부 성공했으니 보존물도 없고, 전송 수신증만 있다
        Map<String, Object> files = (Map<String, Object>) v.get("files");
        assertThat(files).doesNotContainKey("output");
        Map<String, Object> connector = (Map<String, Object>) v.get("transfer");
        assertThat(connector).containsEntry("mode", "MOCK");
        assertThat(((Number) connector.get("count")).intValue()).as("성공 건마다 수신증 하나").isEqualTo(r.successCnt());
        assertThat((Map<String, Long>) connector.get("byKind")).containsKeys("MEET", "PHONE");

        List<Map<String, String>> sql = (List<Map<String, String>>) v.get("sql");
        assertThat(sql).isNotEmpty();
        assertThat(sql).allSatisfy(q -> assertThat(q.get("text")).contains(r.execId()));
        assertThat(sql.get(0).get("text")).contains("kcais.tb_batch_exec_log");

        List<Map<String, String>> cli = (List<Map<String, String>>) v.get("cli");
        assertThat(cli).extracting(c -> c.get("text")).anyMatch(t -> t.startsWith("ls -la"));
        // 기준점은 T1(DB) 이다 — 파일(last_success.txt)을 가리키는 명령이 남아 있으면 안 된다
        assertThat(cli).extracting(c -> c.get("text")).noneMatch(t -> t.contains("last_success"));
        assertThat(sql).extracting(q -> q.get("text")).anyMatch(t -> t.contains("MAX(target_to_dtm)"));
        Map<String, Object> filesMap = (Map<String, Object>) v.get("files");
        assertThat(filesMap).doesNotContainKey("lastSuccess");
    }
}
