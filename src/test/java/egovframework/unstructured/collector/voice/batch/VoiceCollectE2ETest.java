package egovframework.unstructured.collector.voice.batch;

import egovframework.unstructured.collector.common.model.BatchWindow;
import egovframework.unstructured.collector.common.model.FileProcOutcome;
import egovframework.unstructured.collector.common.model.ProcStatus;
import egovframework.unstructured.collector.common.model.VoiceKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전 구간 Mock E2E — <b>Phase 1 의 완료 판정 기준</b>이다.
 *
 * <p>보라미도 XVARM 도 NPU 도 없는 상태에서 배치가 끝까지 도는지 본다.
 * 대상 선별 → 파일 확보 → 복호화(통과) → STT → 처리 이력까지의 배선이 전부 이어져 있어야 통과한다.</p>
 *
 * <p>여기서 검증할 것은 <b>우리 쪽 파이프라인이 완성됐는가</b>이다 — 이 서비스는 STT 까지가 범위이고
 * 텍스트를 외부로 보내지 않는다.</p>
 */
@SpringBootTest
@ActiveProfiles("local")
class VoiceCollectE2ETest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void dirs(DynamicPropertyRegistry registry) {
        // local 프로파일 기본은 DIRECT_JDBC + REST(8082 브로커) 다 — 시뮬레이터용 기본값이다.
        // 이 테스트는 외부 의존 없이 도는 전 구간 Mock 이 전제이므로 두 스위치를 MOCK 으로 고정한다.
        registry.add("voice.source.mode", () -> "MOCK");
        registry.add("voice.broker.mode", () -> "MOCK");
        // 테스트가 ./work 를 오염시키지 않게 임시 디렉터리로 돌린다.
        // 로그 컬렉터를 끈다 — 테스트는 외부 프로세스에 기대면 안 된다.
        // local 프로파일 기본은 enabled=true + localhost:8090 이라, 그 포트에서 무언가 듣고 있지만
        // 응답하지 않으면 호출마다 30초씩 멈춰 빌드가 통째로 늘어진다(실제로 그렇게 늘어졌다).
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
    private egovframework.unstructured.collector.common.transfer.ZenonClient zenon;

    @Autowired
    private IdempotencyGuard idempotency;

    @Autowired
    private egovframework.unstructured.collector.voice.source.SimulationDataService sim;

    private BatchWindow wideWindow() {
        LocalDateTime now = LocalDateTime.now();
        return BatchWindow.manual(now.minusDays(2), now.plusDays(1));
    }

    @BeforeEach
    void resetIdempotency() {
        sim.seed();              // H2 를 시연 기본 10건으로
        idempotency.clearAll();
    }

    @Test
    @DisplayName("Mock 전 구간이 이어져 배치가 끝까지 돈다")
    void runsEndToEnd() {
        VoiceBatchResult result = service.run(wideWindow(), null, "TEST");

        // 넓은 창 = 일배치용(접견 5·전화 5, 어제) + 주기배치용(접견 2·전화 2, 최근 10분) = 14
        assertThat(result.targetCnt()).as("시뮬레이션 대상 접견 7 + 전화 7").isEqualTo(14);
        assertThat(result.failCnt()).as("실패 없이 전부 처리돼야 한다").isZero();
        assertThat(result.successCnt()).isEqualTo(14);
        assertThat(result.execStsCd()).isEqualTo("SUCCESS");
        assertThat(result.execId()).isNotBlank();
    }

    @Test
    @DisplayName("STT 텍스트가 실제로 만들어진다 — 빈 텍스트면 처리한 의미가 없다")
    void producesSttText() {
        VoiceBatchResult result = service.run(wideWindow(), List.of(VoiceKind.PHONE), "TEST");

        assertThat(result.outcomes()).isNotEmpty();
        assertThat(result.outcomes())
                .filteredOn(FileProcOutcome::isSuccess)
                .allSatisfy(o -> assertThat(o.sttChars()).isPositive());
    }

    @Test
    @DisplayName("STT 결과는 제논(Zenon)으로 보낸다 — 건마다 수신증 하나, PV 에는 결과 폴더(xenon)가 생기지 않는다")
    void sendsSttResultsToZenon() throws Exception {
        VoiceBatchResult result = service.run(wideWindow(), null, "TEST");

        assertThat(result.zenon()).containsEntry("mode", "MOCK").containsEntry("sent", String.valueOf(result.successCnt()));
        List<egovframework.unstructured.collector.common.transfer.ZenonClient.Receipt> receipts = zenon.receipts(result.execId());
        assertThat(receipts).hasSize(result.successCnt());
        for (FileProcOutcome o : result.outcomes()) {
            assertThat(o.sttPath()).startsWith("zenon:").endsWith(".json");
            var r = receipts.stream().filter(x -> o.target().idempotencyKey().equals(x.metadata().get("idempotency_key")))
                    .findFirst().orElseThrow();
            assertThat(r.code()).isEqualTo("SUCCESS");
            assertThat(r.type()).isEqualTo("VOICE");
            assertThat(r.inmateNo()).isEqualTo(o.target().corrNo());
            assertThat(r.metadata()).containsEntry("exec_id", result.execId()).containsEntry("kind", o.target().kind().name());
        }
        assertThat(tmp.resolve("xenon")).as("결과를 PV 에 남기지 않는다").doesNotExist();
    }

    @Test
    @DisplayName("T2 단계 요약 — 3단계(COLLECT · ANALYZE · SEND) 세 행, 전부 성공")
    void recordsCollectAndAnalyzeSteps() {
        VoiceBatchResult result = service.run(wideWindow(), null, "TEST");

        // SEND 는 제논 전송 구간이다 — 여기까지 남아야 파이프라인 로그가 ANALYZE 에서 끊기지 않는다.
        assertThat(result.steps()).extracting(VoiceBatchResult.StepLog::stepTypeCd)
                .containsExactly("COLLECT", "ANALYZE", "SEND");
        assertThat(result.steps()).allSatisfy(st -> {
            assertThat(st.stepStsCd()).isEqualTo("SUCCESS");
            assertThat(st.inCnt()).isEqualTo(14);
            assertThat(st.outCnt()).isEqualTo(14);
            assertThat(st.errCnt()).isZero();
        });
    }

    @Test
    @DisplayName("보라미가 이미 가진 STT 는 오디오를 거치지 않고 재사용한다(Q1 시나리오)")
    void reusesSourceSttWhenAvailable() {
        VoiceBatchResult result = service.run(wideWindow(), List.of(VoiceKind.PHONE), "TEST");

        // 전화 7건(일배치 5 + 주기 2) 중 1건이 기존 STT 보유 시나리오다. 그 건도 성공해야 한다.
        assertThat(result.successCnt()).isEqualTo(7);
        assertThat(result.failCnt()).isZero();
    }

    @Test
    @DisplayName("두 번 돌려도 같은 건을 다시 처리하지 않는다 — 주기배치 창이 겹치기 때문")
    void isIdempotentAcrossRuns() {
        VoiceBatchResult first = service.run(wideWindow(), null, "TEST");
        assertThat(first.successCnt()).isEqualTo(14);

        VoiceBatchResult second = service.run(wideWindow(), null, "TEST");

        assertThat(second.skippedCnt()).as("두 번째 실행은 전부 건너뛴다").isEqualTo(14);
        assertThat(second.successCnt()).isZero();
        assertThat(second.outcomes())
                .allSatisfy(o -> assertThat(o.status()).isEqualTo(ProcStatus.SKIPPED));
    }

    @Test
    @DisplayName("멱등 표식을 지우면 다시 처리할 수 있다 — 시연 반복용")
    void canReprocessAfterClearing() {
        service.run(wideWindow(), null, "TEST");
        idempotency.clearAll();

        VoiceBatchResult again = service.run(wideWindow(), null, "TEST");

        assertThat(again.successCnt()).isEqualTo(14);
        assertThat(again.skippedCnt()).isZero();
    }

    @Test
    @DisplayName("종류를 지정하면 그것만 처리한다")
    void filtersByKind() {
        VoiceBatchResult meetOnly = service.run(wideWindow(), List.of(VoiceKind.MEET), "TEST");

        assertThat(meetOnly.targetCnt()).isEqualTo(7);
        assertThat(meetOnly.outcomes())
                .allSatisfy(o -> assertThat(o.target().kind()).isEqualTo(VoiceKind.MEET));
    }

    @Test
    @DisplayName("시간창별 대상 — 일배치 창은 접견 5·전화 5, 10분 주기 창은 접견 2·전화 2 (총 14건, 10초 안에 끝난다)")
    void mockRespectsWindows() {
        LocalDateTime now = LocalDateTime.now();
        long t0 = System.currentTimeMillis();
        VoiceBatchResult daily = service.run(BatchWindow.daily(now), null, "TEST");
        VoiceBatchResult periodic = service.run(BatchWindow.periodic(now, 20), null, "TEST");
        long elapsed = System.currentTimeMillis() - t0;

        assertThat(daily.targetCnt()).isEqualTo(10);
        assertThat(daily.outcomes()).filteredOn(o -> o.target().kind() == VoiceKind.MEET).hasSize(5);
        assertThat(daily.outcomes()).filteredOn(o -> o.target().kind() == VoiceKind.PHONE).hasSize(5);
        assertThat(periodic.targetCnt()).isEqualTo(4);
        assertThat(periodic.outcomes()).filteredOn(o -> o.target().kind() == VoiceKind.MEET).hasSize(2);
        assertThat(periodic.outcomes()).filteredOn(o -> o.target().kind() == VoiceKind.PHONE).hasSize(2);
        assertThat(daily.successCnt() + periodic.successCnt()).isEqualTo(14);
        assertThat(elapsed).as("시연용 12건은 두 배치 합쳐 10초 안에 끝나야 한다").isLessThan(10_000L);
    }

    @Test
    @DisplayName("SKIPPED 건은 T4 집계에서 빠진다 — 정합성 대사가 어긋나지 않게")
    void skippedNotCountedAsSuccess() {
        service.run(wideWindow(), null, "TEST");

        VoiceBatchResult second = service.run(wideWindow(), null, "TEST");

        // T1.SUCCESS_CNT == Σ(T3·T4·T5) 규칙상, 건너뛴 건을 성공으로 세면 대사가 깨진다
        assertThat(second.successCnt()).isZero();
        assertThat(second.execStsCd()).isEqualTo("SUCCESS");   // 실패가 없으므로 SUCCESS
    }
}
