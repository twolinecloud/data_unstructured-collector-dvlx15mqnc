package egovframework.unstructured.collector.voice.batch;

import egovframework.unstructured.collector.common.config.FaultInjector;
import egovframework.unstructured.collector.common.config.MockDatasetState;
import egovframework.unstructured.collector.common.model.BatchWindow;
import egovframework.unstructured.collector.common.model.FileProcOutcome;
import egovframework.unstructured.collector.common.model.ProcStatus;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 장애 내성(Fault Tolerance)과 PII 삭제 보장 검증.
 *
 * <p><b>확인하려는 것 두 가지</b></p>
 * <ol>
 *   <li><b>배치가 뻗지 않는다</b> — 파일 1건이 터져도 예외가 밖으로 나가지 않고
 *       {@code failCnt} 만 올리며 끝까지 돈다. 1,300건 배치에서 한 건 때문에 전체가 멈추면
 *       나머지를 전부 다시 처리해야 한다.</li>
 *   <li><b>실패해도 원본이 남지 않는다</b> — 정책은 성공·실패를 가리지 않고 즉시 삭제다
 *       (계획서 5.3-(4)). STT 가 실패하는 경로에서도 복호화된 음성이 디스크에 남으면 안 된다.
 *       {@code processOne} 의 {@code finally} 가 그걸 보장한다. 잔여는 이 테스트가 직접 센다
 *       (수신·작업 디렉터리 바로 아래 파일 수 — 하위 {@code .processed} 표식은 PII 가 아니다).</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("local")
class ResilienceE2ETest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void dirs(DynamicPropertyRegistry registry) {
        // local 프로파일 기본(DIRECT_JDBC + REST)과 무관하게 전 구간 Mock 으로 고정한다.
        registry.add("voice.source.mode", () -> "MOCK");
        registry.add("voice.broker.mode", () -> "MOCK");
        registry.add("voice.dirs.base-dir", () -> tmp.toString());
        registry.add("voice.dirs.receive-meet", () -> tmp.resolve("raw/meet").toString());
        registry.add("voice.dirs.receive-phone", () -> tmp.resolve("raw/phone").toString());
        registry.add("voice.dirs.work", () -> tmp.resolve("work").toString());
        registry.add("voice.dirs.output-meet", () -> tmp.resolve("xenon/voice").toString());
        registry.add("voice.dirs.output-phone", () -> tmp.resolve("xenon/phone").toString());
        registry.add("voice.dirs.xvarm-original", () -> tmp.resolve("xvarm_original").toString());
        registry.add("voice.sync.wait-timeout-sec", () -> "15");
        registry.add("voice.sync.stable-check-ms", () -> "30");
        registry.add("log-collector.enabled", () -> "false");
        // 이 클래스가 지키는 것은 PII 보장이다 — '실패해도 복호화 원본을 남기지 않는다'.
        //   Resume 를 위한 보존(voice.resume.keep-on-failure, 기본 true)은 그 보장과 정면으로
        //   맞서므로 여기서는 끄고 본다. 보존 쪽 동작은 SttTempStore·StageFault 테스트와
        //   실기동 검증이 따로 덮는다.
        registry.add("voice.resume.keep-on-failure", () -> "false");
    }

    @Autowired
    private VoiceCollectService service;

    @Autowired
    private IdempotencyGuard idempotency;

    @Autowired
    private FaultInjector faultInjector;

    @Autowired
    private MockDatasetState dataset;

    @Autowired
    private egovframework.unstructured.collector.voice.source.SimulationDataService sim;

    /** 시뮬레이션 기본 대상(넓은 창) — 일배치용 접견 5·전화 5 + 주기배치용 접견 2·전화 2. */
    private static final int TOTAL = 14;

    /**
     * 그중 1건(전화 3번)은 <b>보라미가 이미 가진 STT</b> 를 쓴다(계획서 Q1 시나리오).
     * 오디오를 만지지 않으니 STT 엔진을 타지 않고, 따라서 STT 구간 장애 주입의 영향도 받지 않는다.
     */
    private static final int SOURCE_STT = 1;

    /** STT 엔진을 실제로 타는 건수 = 장애 주입이 닿는 범위. */
    private static final int STT_DEPENDENT = TOTAL - SOURCE_STT;   // 13

    private BatchWindow wide() {
        LocalDateTime now = LocalDateTime.now();
        return BatchWindow.manual(now.minusDays(2), now.plusDays(1));
    }

    /** 수신(접견·전화)·작업 디렉터리 <b>바로 아래</b> 일반 파일 수 — 0 이어야 PII 즉시 삭제가 지켜진 것이다. */
    private static int residue() {
        int n = 0;
        for (String d : new String[] {"raw/meet", "raw/phone", "work"}) {
            Path p = tmp.resolve(d);
            if (!Files.isDirectory(p)) {
                continue;
            }
            try (Stream<Path> s = Files.list(p)) {
                n += (int) s.filter(Files::isRegularFile).count();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return n;
    }

    @BeforeEach
    void reset() {
        sim.seed();              // H2 를 시연 기본 10건으로 되돌린다(앞 테스트가 대용량으로 늘렸을 수 있다)
        idempotency.clearAll();
        faultInjector.configure(false, 10, 10, 2_000L);
        faultInjector.resetCounters();
    }

    @AfterEach
    void turnOffChaos() {
        // 전역 상태다. 끄지 않으면 다른 테스트가 영향을 받는다.
        faultInjector.configure(false, 10, 10, 2_000L);
    }

    @Test
    @DisplayName("STT 가 100% 실패해도 배치는 예외 없이 완주한다")
    void batchCompletesEvenWhenEveryFileFails() {
        faultInjector.configure(true, 100, 0, 0L);

        VoiceBatchResult[] holder = new VoiceBatchResult[1];
        assertThatCode(() -> holder[0] = service.run(wide(), null, "TEST"))
                .as("건별 실패가 배치 밖으로 새어 나오면 안 된다")
                .doesNotThrowAnyException();

        VoiceBatchResult r = holder[0];
        assertThat(r.targetCnt()).isEqualTo(TOTAL);
        assertThat(r.failCnt()).as("STT 를 타는 건은 전부 실패").isEqualTo(STT_DEPENDENT);
        assertThat(r.successCnt())
                .as("보라미 기존 STT 를 쓰는 건은 STT 엔진을 타지 않아 장애와 무관하다")
                .isEqualTo(SOURCE_STT);
        assertThat(r.execStsCd()).isEqualTo("PARTIAL");
        assertThat(faultInjector.injectedFailures()).isPositive();
    }

    @Test
    @DisplayName("STT 가 전부 실패한 배치에서도 원본 음성이 남지 않는다 — 성공·실패 무관 즉시 삭제")
    void noPiiResidueEvenWhenAllFail() {
        faultInjector.configure(true, 100, 0, 0L);

        VoiceBatchResult r = service.run(wide(), null, "TEST");

        assertThat(r.failCnt()).isEqualTo(STT_DEPENDENT);
        assertThat(residue())
                .as("실패 경로에서도 복호화 원본이 지워져야 한다")
                .isZero();
        // 실패한 건은 STT 출력도 남기지 않는다 — 성공 1건(기존 STT)만 전화 출력 폴더에 있다
        assertThat(r.outcomes()).filteredOn(o -> o.status() == ProcStatus.FAIL)
                .allSatisfy(o -> assertThat(o.sttPath()).isNull());
        assertThat(r.outcomes()).filteredOn(FileProcOutcome::isSuccess)
                .allSatisfy(o -> assertThat(Files.isRegularFile(Path.of(o.sttPath()))).isTrue());
    }

    @Test
    @DisplayName("성공한 배치에서도 원본 음성이 남지 않는다")
    void noPiiResidueOnSuccess() {
        VoiceBatchResult r = service.run(wide(), null, "TEST");

        assertThat(r.successCnt()).isEqualTo(TOTAL);
        assertThat(residue()).isZero();
        // STT 텍스트는 배치 폴더 {output}/{execId}/ 에 남는다
        assertThat(r.outputDirs()).containsKeys("MEET", "PHONE");
        assertThat(r.outputDirs().get("MEET")).endsWith("/xenon/voice/" + r.execId());
        assertThat(r.outputDirs().get("PHONE")).endsWith("/xenon/phone/" + r.execId());
        assertThat(r.outcomes()).allSatisfy(o -> {
            assertThat(o.sttPath()).startsWith(r.outputDirs().get(o.target().kind().name()));
            assertThat(Files.isRegularFile(Path.of(o.sttPath()))).isTrue();
        });
    }

    @Test
    @DisplayName("일부만 실패해도 나머지는 정상 처리된다 — 한 건이 전체를 막지 않는다")
    void partialFailureStillProcessesRest() {
        // 50% 로 두면 확률상 성공·실패가 섞인다. 어느 쪽이든 합계는 대상 수와 같아야 한다.
        faultInjector.configure(true, 50, 0, 0L);

        VoiceBatchResult r = service.run(wide(), null, "TEST");

        assertThat(r.successCnt() + r.failCnt()).isEqualTo(r.targetCnt());
        assertThat(r.outcomes()).hasSize(TOTAL);
        assertThat(residue()).as("섞여 있어도 잔여는 0").isZero();
    }

    @Test
    @DisplayName("지연이 주입돼도 배치는 완료된다")
    void survivesInjectedDelay() {
        faultInjector.configure(true, 0, 100, 100L);
        sim.seed(2, 2);          // 일배치 2·2 + 주기 2·2 = 8 (전화 기존 STT 건 없음 → 전부 STT 를 탄다)

        long t0 = System.currentTimeMillis();
        VoiceBatchResult r = service.run(wide(), null, "TEST");
        long elapsed = System.currentTimeMillis() - t0;

        // 일배치용 2·2 + 주기배치용 2·2
        assertThat(r.successCnt()).isEqualTo(8);
        assertThat(elapsed).as("건당 100ms 이상 지연이 실제로 걸렸다").isGreaterThanOrEqualTo(800L);
        assertThat(faultInjector.injectedDelays()).isPositive();
    }

    @Test
    @DisplayName("실패한 건은 멱등 표식이 남지 않는다 — 다음 배치에서 다시 시도해야 한다")
    void failedItemsAreRetriable() {
        faultInjector.configure(true, 100, 0, 0L);
        VoiceBatchResult first = service.run(wide(), null, "TEST");
        assertThat(first.failCnt()).isEqualTo(STT_DEPENDENT);

        // 장애를 끄고 다시 돌린다. 실패했던 건은 표식이 없으니 재시도되어야 하고,
        // 성공했던 1건(기존 STT)만 건너뛰어야 한다.
        faultInjector.configure(false, 0, 0, 0L);
        VoiceBatchResult second = service.run(wide(), null, "TEST");

        assertThat(second.successCnt())
                .as("실패했던 건은 표식이 없어 다시 처리된다")
                .isEqualTo(STT_DEPENDENT);
        assertThat(second.skippedCnt())
                .as("앞서 성공한 건만 건너뛴다")
                .isEqualTo(SOURCE_STT);
        assertThat(second.failCnt()).isZero();
    }

    @Test
    @DisplayName("대용량으로 늘려도 건별 처리가 유지된다 — 스트리밍·청크 구조 확인")
    void handlesLargerDataset() {
        sim.seed(50, 50);        // 로컬 H2 에 일배치 50·50 시딩

        VoiceBatchResult r = service.run(wide(), null, "TEST");

        assertThat(r.targetCnt()).isEqualTo(104);   // 일배치용 100 + 주기배치용 4
        assertThat(r.successCnt()).isEqualTo(104);
        assertThat(residue()).isZero();
        // 대량 모드로 전환되어 Mock WAV 가 짧아진다(디스크 절약)
        assertThat(dataset.isBulk()).isFalse();   // 104건은 임계(200) 미만
    }

    @Test
    @DisplayName("200건을 넘으면 대량 모드로 전환되어 Mock 파일이 작아진다")
    void switchesToBulkMode() {
        dataset.set(150, 150);

        assertThat(dataset.total()).isEqualTo(304);
        assertThat(dataset.isBulk()).isTrue();
        assertThat(dataset.wavSeconds()).isEqualTo(1);
    }

    @Test
    @DisplayName("T2 단계 요약 — STT 가 전부 실패하면 COLLECT 는 SUCCESS, ANALYZE 는 PARTIAL, DEIDENT·SEND 는 통과분만")
    void stepSummaryReflectsWhereItFailed() {
        faultInjector.configure(true, 100, 0, 0L);

        VoiceBatchResult r = service.run(wide(), null, "TEST");

        assertThat(r.steps()).extracting(VoiceBatchResult.StepLog::stepTypeCd)
                .containsExactly("COLLECT", "ANALYZE", "DEIDENT", "SEND");
        VoiceBatchResult.StepLog collect = r.steps().get(0);
        VoiceBatchResult.StepLog analyze = r.steps().get(1);
        VoiceBatchResult.StepLog deident = r.steps().get(2);
        VoiceBatchResult.StepLog send = r.steps().get(3);
        assertThat(collect.stepStsCd()).isEqualTo("SUCCESS");
        assertThat(collect.inCnt()).isEqualTo(TOTAL);
        assertThat(collect.outCnt()).isEqualTo(TOTAL);
        assertThat(analyze.inCnt()).isEqualTo(TOTAL);
        assertThat(analyze.outCnt()).isEqualTo(SOURCE_STT);
        assertThat(analyze.errCnt()).isEqualTo(STT_DEPENDENT);
        assertThat(analyze.stepStsCd()).isEqualTo("PARTIAL");
        // STT 를 통과한 것만 비식별 구간으로 — 단순 전달이라 깨지지 않는다
        assertThat(deident.inCnt()).isEqualTo(SOURCE_STT);
        assertThat(deident.outCnt()).isEqualTo(SOURCE_STT);
        assertThat(deident.stepStsCd()).isEqualTo("SUCCESS");
        // 비식별을 통과한 것만 저장 구간으로 넘어간다 — 저장 자체는 깨지지 않았으므로 SUCCESS
        assertThat(send.inCnt()).isEqualTo(SOURCE_STT);
        assertThat(send.outCnt()).isEqualTo(SOURCE_STT);
        assertThat(send.errCnt()).isZero();
        assertThat(send.stepStsCd()).isEqualTo("SUCCESS");
        assertThat(r.outcomes()).filteredOn(o -> o.status() == ProcStatus.FAIL)
                .allSatisfy(o -> assertThat(o.failedStep()).isEqualTo(FileProcOutcome.STEP_ANALYZE));
        // 컬렉터 미연동(log-collector.enabled=false) — 요약은 만들되 적재는 안 된 것으로 표시
        assertThat(collect.logged()).isFalse();
        assertThat(r.execIdFromCollector()).isFalse();
    }
}
