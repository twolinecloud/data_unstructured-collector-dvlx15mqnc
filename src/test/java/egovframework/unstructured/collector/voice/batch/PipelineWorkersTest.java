package egovframework.unstructured.collector.voice.batch;

import egovframework.unstructured.collector.common.config.SourcePoolPeak;
import egovframework.unstructured.collector.common.model.BatchWindow;
import egovframework.unstructured.collector.common.model.FileProcOutcome;
import egovframework.unstructured.collector.voice.source.SimulationDataService;
import egovframework.unstructured.collector.voice.stt.MockSttClient;
import egovframework.unstructured.collector.voice.stt.MockSttLatency;
import egovframework.unstructured.collector.common.sync.FileArrivalWatcher;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * 워커 이원화 — <b>XVARM 확보 워커</b>와 <b>STT 처리 워커</b>를 나눠 돌리는 생산자-소비자.
 *
 * <ul>
 *   <li>확보 워커 1 이면 확보(수신 폴더 도착 대기)는 한 번에 한 건만 간다 — STT 워커가 4여도</li>
 *   <li>STT 는 여러 건이 겹쳐 돈다</li>
 *   <li>STT·확보 동안 트랜잭션도, 원천 DB 커넥션도 잡고 있지 않다 — 워커를 늘려도 커넥션 풀이 고갈되지 않는 근거</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("local")
class PipelineWorkersTest {

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
        registry.add("voice.sync.stable-check-ms", () -> "30");
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
    @Autowired
    private SourcePoolPeak pools;
    @MockitoSpyBean
    private FileArrivalWatcher watcher;
    @MockitoSpyBean
    private MockSttClient stt;

    private final AtomicInteger acquiring = new AtomicInteger();
    private final AtomicInteger maxAcquiring = new AtomicInteger();
    private final AtomicInteger transcribing = new AtomicInteger();
    private final AtomicInteger maxTranscribing = new AtomicInteger();
    /** STT·확보 도중에 본 것 — 트랜잭션이 열려 있었는가 · 원천 풀에서 빌려 간 연결 수. */
    private final List<String> heldDuringWork = new CopyOnWriteArrayList<>();

    @BeforeEach
    void seed() {
        idempotency.clearAll();
        sim.seedPerf(5, 5, 0);
        doAnswer(inv -> watch(acquiring, maxAcquiring, "확보", inv::callRealMethod))
                .when(watcher).await(any(), any(), any());   // 배치는 중단 여부를 함께 넘기는 3-인자로 기다린다
        doAnswer(inv -> watch(transcribing, maxTranscribing, "STT", inv::callRealMethod))
                .when(stt).transcribe(any());
    }

    @AfterEach
    void reset() {
        latency.clear();
    }

    private Object watch(AtomicInteger now, AtomicInteger max, String what, Call real) throws Throwable {
        max.accumulateAndGet(now.incrementAndGet(), Math::max);
        try {
            int borrowed = borrowedConnections();
            if (TransactionSynchronizationManager.isActualTransactionActive() || borrowed > 0) {
                heldDuringWork.add(what + " — 트랜잭션 " + TransactionSynchronizationManager.isActualTransactionActive()
                        + " · 빌린 연결 " + borrowed);
            }
            return real.call();
        } finally {
            now.decrementAndGet();
        }
    }

    @FunctionalInterface
    private interface Call {
        Object call() throws Throwable;
    }

    @SuppressWarnings("unchecked")
    private int borrowedConnections() {
        List<Map<String, Object>> list = (List<Map<String, Object>>) pools.state().get("pools");
        return list.stream().mapToInt(m -> ((Number) m.get("active")).intValue()).sum();
    }

    private static BatchWindow yesterday() {
        LocalDate today = LocalDate.now();
        return BatchWindow.manual(today.minusDays(1).atStartOfDay(), today.atStartOfDay());
    }

    @Test
    @DisplayName("확보 워커 1 · STT 워커 4 — 확보는 한 번에 한 건, STT 는 겹쳐 돈다, 전건 성공 · 결과는 대상 순서")
    void acquireOneAtATimeSttInParallel() {
        latency.apply(MockSttLatency.Mode.FIXED, 250, 250, 0, 0);   // 실제 대기

        VoiceBatchResult r = collect.run(yesterday(), null, "TEST", true, ResumeMode.FULL, null, new Workers(1, 4));

        assertThat(r.successCnt()).isEqualTo(10);
        assertThat(maxAcquiring.get()).as("XVARM 확보 워커 1 — 확보가 겹치지 않는다").isEqualTo(1);
        assertThat(maxTranscribing.get()).as("STT 워커 4 — STT 는 겹쳐 돈다").isGreaterThanOrEqualTo(2);
        assertThat(r.outcomes()).extracting(o -> o.target().idempotencyKey())
                .as("한 건도 빠지거나 겹치지 않는다").hasSize(10).doesNotHaveDuplicates();
        assertThat(r.steps()).extracting(VoiceBatchResult.StepLog::stepTypeCd)
                .containsExactly(FileProcOutcome.STEP_COLLECT, FileProcOutcome.STEP_ANALYZE, FileProcOutcome.STEP_SEND);
        Map<String, Object> p = progress.snapshot();
        assertThat(p.get("pipeline")).isEqualTo(true);
        assertThat(p.get("acquireWorkers")).isEqualTo(1);
        assertThat(p.get("sttWorkers")).isEqualTo(4);
    }

    @Test
    @DisplayName("STT·확보 도중에는 트랜잭션도 원천 DB 커넥션도 잡지 않는다 — 배치가 끝나면 빌린 연결 0")
    void noConnectionHeldDuringSttOrAcquire() {
        latency.apply(MockSttLatency.Mode.FIXED, 100, 100, 0, 0);

        VoiceBatchResult r = collect.run(yesterday(), null, "TEST", true, ResumeMode.FULL, null, new Workers(2, 8));

        assertThat(r.successCnt()).isEqualTo(10);
        assertThat(heldDuringWork).as("STT·확보 중 커넥션/트랜잭션 점유").isEmpty();
        assertThat(pools.state().get("returned")).isEqualTo(true);
        assertThat(borrowedConnections()).isZero();
    }

    @Test
    @DisplayName("둘 다 1 이면 종전 순차 — 생산자-소비자를 타지 않는다")
    void bothOneIsSequential() {
        VoiceBatchResult r = collect.run(yesterday(), null, "TEST", true, ResumeMode.FULL, null, Workers.sequential());

        assertThat(r.successCnt()).isEqualTo(10);
        assertThat(progress.snapshot().get("pipeline")).isEqualTo(false);
        assertThat(maxTranscribing.get()).isEqualTo(1);
    }
}
