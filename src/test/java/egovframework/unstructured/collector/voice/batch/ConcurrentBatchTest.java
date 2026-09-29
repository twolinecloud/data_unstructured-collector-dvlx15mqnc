package egovframework.unstructured.collector.voice.batch;

import egovframework.unstructured.collector.common.logging.LogCollectorClient;
import egovframework.unstructured.collector.common.model.BatchWindow;
import egovframework.unstructured.collector.common.model.FileProcOutcome;
import egovframework.unstructured.collector.common.model.VoiceTarget;
import egovframework.unstructured.collector.voice.source.SimulationDataService;
import egovframework.unstructured.collector.voice.stt.MockSttLatency;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 동시 처리(워커 N개) — <b>성능 테스트용 길이 순차와 같은 결과를 내는가.</b>
 *
 * <ul>
 *   <li>T2 단계 행은 단계마다 <b>하나</b>만 연다 — 두 워커가 동시에 첫 STT 에 닿아도</li>
 *   <li>결과는 대상 순서 그대로 — T4 행 순서가 순차와 같다</li>
 *   <li>실제로 겹쳐 돈다 — 지연 200ms × 12건을 워커 4개면 순차(2.4초)보다 훨씬 빨리 끝난다</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("local")
class ConcurrentBatchTest {

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
        registry.add("voice.dirs.output-meet", () -> tmp.resolve("xenon/meet").toString());
        registry.add("voice.dirs.output-phone", () -> tmp.resolve("xenon/phone").toString());
        registry.add("voice.dirs.xvarm-original", () -> tmp.resolve("xvarm_original").toString());
        registry.add("voice.sync.wait-timeout-sec", () -> "15");
        registry.add("voice.sync.stable-check-ms", () -> "50");
    }

    @Autowired
    private VoiceCollectService collect;
    @Autowired
    private SimulationDataService sim;
    @Autowired
    private IdempotencyGuard idempotency;
    @Autowired
    private MockSttLatency latency;
    @Autowired
    private BatchProgress progress;
    @MockitoSpyBean
    private LogCollectorClient logCollector;

    @BeforeEach
    void seed() {
        idempotency.clearAll();
        sim.seedPerf(6, 6, 20);   // 전화 6건 중 20% → 1건(4번)이 보라미 기존 STT 를 가진다
        clearInvocations(logCollector);
    }

    @AfterEach
    void reset() {
        latency.clear();
    }

    private static BatchWindow yesterday() {
        LocalDate today = LocalDate.now();
        return BatchWindow.manual(today.minusDays(1).atStartOfDay(), today.atStartOfDay());
    }

    @Test
    @DisplayName("워커 4개 · 12건 — 전건 성공, T2 단계 행은 단계마다 하나, 결과는 대상 순서 그대로")
    void concurrentRunMatchesSequentialShape() {
        latency.apply(MockSttLatency.Mode.FIXED, 200, 200, 0, 0);

        long t0 = System.currentTimeMillis();
        VoiceBatchResult r = collect.run(yesterday(), null, "TEST", true, ResumeMode.FULL, null, 4);
        long ms = System.currentTimeMillis() - t0;

        assertThat(r.targetCnt()).isEqualTo(12);
        assertThat(r.successCnt()).isEqualTo(12);
        assertThat(r.failCnt()).isZero();
        // 단계 행 — COLLECT·ANALYZE·DEIDENT·SEND 가 한 번씩만 열린다
        verify(logCollector, times(1)).createStep(anyString(), eq((short) 1), eq(FileProcOutcome.STEP_COLLECT));
        verify(logCollector, times(1)).createStep(anyString(), eq((short) 2), eq(FileProcOutcome.STEP_ANALYZE));
        verify(logCollector, times(1)).createStep(anyString(), eq((short) 3), eq(FileProcOutcome.STEP_DEIDENT));
        verify(logCollector, times(1)).createStep(anyString(), eq((short) 4), eq(FileProcOutcome.STEP_SEND));
        assertThat(r.steps()).extracting(VoiceBatchResult.StepLog::stepTypeCd)
                .containsExactly(FileProcOutcome.STEP_COLLECT, FileProcOutcome.STEP_ANALYZE,
                        FileProcOutcome.STEP_DEIDENT, FileProcOutcome.STEP_SEND);
        // T2 소요 시간은 실제 경과를 넘지 않는다(건별 합이 아니라 벽시계로 자른다)
        r.steps().forEach(s -> assertThat(s.elapsedSec() * 1000L).isLessThanOrEqualTo(r.elapsedMs()));

        // 결과 순서 — 순차로 돌렸을 때와 같은 대상 순서
        List<String> keys = r.outcomes().stream().map(o -> o.target().idempotencyKey()).toList();
        VoiceBatchResult again = collect.run(yesterday(), null, "TEST", true, ResumeMode.FULL, null, 1);
        assertThat(again.outcomes().stream().map(o -> o.target().idempotencyKey()).toList()).isEqualTo(keys);
        assertThat(again.skippedCnt()).isEqualTo(12);   // 두 번째는 전부 멱등 건너뜀

        // 겹쳐 돌았다 — 순차라면 STT 지연만 12 × 200ms = 2.4초다
        assertThat(ms).as("워커 4개면 STT 지연이 겹친다").isLessThan(2_400L);
        Map<String, Object> p = progress.snapshot();
        assertThat(p.get("concurrency")).isEqualTo(1);   // 마지막 실행(순차)의 값
        assertThat(p.get("active")).isEqualTo(0);
    }

    @Test
    @DisplayName("STT 가상 지연이 타임아웃을 넘으면 그 건만 실패 — 사유는 SttTimeoutException, 나머지는 성공")
    void sttTimeoutFailsOnlyThatFile() {
        // 고정 300ms · 타임아웃 100ms → 전건 타임아웃
        latency.apply(MockSttLatency.Mode.FIXED, 300, 300, 0, 100);

        VoiceBatchResult r = collect.run(yesterday(), null, "TEST", true, ResumeMode.FULL, null, 4);

        // 기 STT 가 있는 전화 한 건은 STT 를 부르지 않는다(Bypass) → 그 한 건만 성공
        List<FileProcOutcome> failed = r.outcomes().stream().filter(o -> !o.isSuccess()).toList();
        assertThat(r.successCnt()).isEqualTo(1);
        assertThat(failed).hasSize(11);
        assertThat(failed).allSatisfy(o -> {
            assertThat(o.errMsg()).startsWith("SttTimeoutException");
            assertThat(o.failedAt(FileProcOutcome.STEP_ANALYZE)).isTrue();
        });
        assertThat(r.execStsCd()).isEqualTo("PARTIAL");
    }

    @Test
    @DisplayName("동시 처리 중 중단 — 시작하지 않은 건은 건너뜀, 합계는 대상 수와 같다")
    void cancelDuringConcurrentRun() throws Exception {
        latency.apply(MockSttLatency.Mode.FIXED, 300, 300, 0, 0);
        Thread canceller = new Thread(() -> {
            try {
                long until = System.currentTimeMillis() + 10_000;
                while (System.currentTimeMillis() < until) {
                    if (progress.isRunning() && ((Number) progress.snapshot().get("done")).intValue() >= 2) {
                        progress.cancel();
                        return;
                    }
                    Thread.sleep(20);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        canceller.start();

        VoiceBatchResult r = collect.run(yesterday(), null, "TEST", true, ResumeMode.FULL, null, 2);
        canceller.join();

        assertThat(r.canceled()).isTrue();
        assertThat(r.successCnt() + r.failCnt() + r.skippedCnt()).isEqualTo(12);
        assertThat(r.skippedCnt()).isPositive();
        assertThat(r.outcomes()).extracting(FileProcOutcome::target).extracting(VoiceTarget::idempotencyKey)
                .doesNotHaveDuplicates();
    }
}
