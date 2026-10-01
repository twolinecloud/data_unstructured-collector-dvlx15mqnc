package egovframework.unstructured.collector.voice.perf;

import egovframework.unstructured.collector.voice.source.SimulationDataService;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 성능 시험 — 기본 부하 한 회차와 임계 시험(워커 램프업)을 끝까지 돌린다.
 *
 * <p>로컬 H2 · 브로커 MOCK · 로그 컬렉터 미연동. 정합성 점검은 컬렉터가 없어 '확인 불가' 로 남는 것이 맞다.</p>
 */
@SpringBootTest
@ActiveProfiles("local")
class PerfRunServiceTest {

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
    private PerfRunService perf;
    @Autowired
    private SimulationDataService sim;

    @Test
    @DisplayName("기본 부하 — 접견 5 · 전화 5 · 기 STT 20% · 워커 2 — 전건 성공, 1건은 STT Bypass, SIM 은 지워지고 이력이 한 줄 남는다")
    @SuppressWarnings("unchecked")
    void basicRunEndToEnd() throws Exception {
        perf.clearHistory();

        Map<String, Object> started = perf.start(new PerfRequest("A", 5, 5, 20, 1, 2, "FIXED", 60L, 40L, null, null, true));   // 실제 대기 모드
        assertThat(started.get("active")).isEqualTo(true);
        assertThat(started.get("kind")).isEqualTo("BASIC");
        // 도는 동안에는 두 번째(임계 시험도)를 받지 않는다
        assertThatThrownBy(() -> perf.startRamp(null)).isInstanceOf(IllegalStateException.class);

        Map<String, Object> cur = waitDone();
        assertThat(cur.get("phase")).as("오류: %s", cur.get("error")).isEqualTo("DONE");

        Map<String, Object> r = (Map<String, Object>) cur.get("result");
        assertThat(r.get("total")).isEqualTo(10);
        assertThat(r.get("success")).isEqualTo(10);
        assertThat(r.get("sourceSttPhones")).isEqualTo(1);
        assertThat((Double) r.get("tps")).isPositive();

        List<Map<String, Object>> stages = (List<Map<String, Object>>) r.get("stages");
        assertThat(stages).extracting(s -> s.get("key"))
                .containsExactly("ACQUIRE", "DECRYPT", "FORMAT", "STT", "TEMP", "SEND");
        assertThat(((Number) stages.get(3).get("count")).intValue()).as("기 STT 1건은 STT 를 부르지 않는다").isEqualTo(9);
        assertThat((Double) stages.get(3).get("avgMs")).isGreaterThanOrEqualTo(40d);
        Map<String, Object> hikari = (Map<String, Object>) r.get("hikari");
        assertThat((Integer) hikari.get("peak")).isGreaterThanOrEqualTo(1);
        // 배치가 끝난 직후 — 빌려 간 원천 연결이 모두 돌아왔다(사용 중 0 · 대기 0)
        assertThat(((Map<String, Object>) hikari.get("after")).get("returned")).isEqualTo(true);
        assertThat(r.get("acquireWorkers")).isEqualTo(1);
        assertThat(r.get("sttWorkers")).isEqualTo(2);
        assertThat(r.get("pipeline")).isEqualTo(true);
        assertThat(((Map<String, Object>) r.get("checks")).get("available")).isEqualTo(false);

        Map<String, Object> rows = (Map<String, Object>) sim.status().get("rows");
        assertThat(rows.get("meet")).isEqualTo(0);
        assertThat(rows.get("phone")).isEqualTo(0);

        Map<String, Object> h = perf.history();
        assertThat(h.get("total")).isEqualTo(1);
        assertThat(Files.isRegularFile(Path.of((String) h.get("file")))).isTrue();
        assertThat(perf.clearHistory().get("deleted")).isEqualTo(1);
    }

    @Test
    @DisplayName("임계 시험 — 워커 1 → 2 → 4 · 단계마다 같은 6건 — 모든 단계를 마치고 최적 워커와 이력이 남는다")
    @SuppressWarnings("unchecked")
    void rampRunsEveryStep() throws Exception {
        perf.clearRampHistory();

        Map<String, Object> started = perf.startRamp(new RampRequest(3, 3, 0, "FIXED", 150L, 150L, null, null,
                1, "MULTIPLY", 2, 4, 3, 0, true, null, null, null, null));
        assertThat(started.get("kind")).isEqualTo("RAMP");
        assertThat((List<Integer>) started.get("plan")).containsExactly(1, 2, 4);

        Map<String, Object> cur = waitDone();
        assertThat(cur.get("phase")).as("오류: %s", cur.get("error")).isEqualTo("DONE");
        Map<String, Object> r = (Map<String, Object>) cur.get("result");
        List<Map<String, Object>> steps = (List<Map<String, Object>>) r.get("steps");
        assertThat(steps).extracting(s -> s.get("workers")).containsExactly(1, 2, 4);
        assertThat(steps).allSatisfy(s -> assertThat(s.get("success")).isEqualTo(6));
        assertThat(steps).allSatisfy(s -> assertThat(s).containsKeys("acquireAvgMs", "acquireMaxMs", "tps", "totalSec"));
        // 고속 모드(기본) — 표의 STT 평균은 기다리지 않은 건당 처리 시간이다(실제 0ms 가 아니라)
        assertThat(steps).allSatisfy(s -> assertThat(((Number) s.get("sttAvgMs")).doubleValue()).isEqualTo(150.0));
        assertThat((String) r.get("stopReason")).contains("모든 단계");
        // STT 150ms × 6건 — 워커 1 은 0.9초 넘게, 워커 4 는 그보다 빨라야 한다
        assertThat((Double) steps.get(2).get("totalSec")).isLessThan((Double) steps.get(0).get("totalSec"));
        assertThat(((Map<String, Object>) r.get("best")).get("workers")).isNotNull();

        assertThat(perf.rampHistory().get("total")).isEqualTo(1);
        Map<String, Object> rows = (Map<String, Object>) sim.status().get("rows");
        assertThat(rows.get("meet")).isEqualTo(0);
    }

    @Test
    @DisplayName("임계 시험 조기 종료 — STT 타임아웃이 나면 첫 단계에서 멈춘다")
    @SuppressWarnings("unchecked")
    void rampStopsOnSttError() throws Exception {
        perf.startRamp(new RampRequest(3, 3, 0, "FIXED", 400L, 400L, null, 100L,
                1, "MULTIPLY", 2, 8, 3, 0, true, null, null, null, null));

        Map<String, Object> cur = waitDone();
        Map<String, Object> r = (Map<String, Object>) cur.get("result");
        List<Map<String, Object>> steps = (List<Map<String, Object>>) r.get("steps");
        assertThat(steps).hasSize(1);
        assertThat((String) r.get("stopReason")).contains("STT");
        assertThat(((Number) steps.get(0).get("sttErrors")).longValue()).isPositive();
    }

    @Test
    @DisplayName("고속 모드 — 건당 100초 × 8건 · STT 워커 2 는 기다리지 않고 끝나고, 총 소요는 파이프라인 모의로 약 400초")
    @SuppressWarnings("unchecked")
    void fastForwardAddsVirtualSttTime() throws Exception {
        long t0 = System.currentTimeMillis();
        perf.start(new PerfRequest("A", 4, 4, 0, 1, 2, "FIXED", 100_000L, 100_000L, null, null, null));
        Map<String, Object> cur = waitDone();
        assertThat(System.currentTimeMillis() - t0).as("실제로 800초를 기다리지 않는다").isLessThan(60_000L);

        Map<String, Object> r = (Map<String, Object>) cur.get("result");
        assertThat(r.get("fastForward")).isEqualTo(true);
        assertThat(r.get("success")).isEqualTo(8);
        assertThat((Double) r.get("virtualSttSec")).isEqualTo(400.0);
        double total = (Double) r.get("totalSec");
        // 확보(실제)와 STT(가상)가 겹친다 — 400초에 실제 소요가 통째로 더해지지 않고, 겹친 만큼 빠진다
        assertThat(total).isBetween(400.0, (Double) r.get("realSec") + 400.0 + 0.5);
        assertThat((Double) r.get("virtualSec")).isEqualTo(total - (Double) r.get("realSec"), org.assertj.core.data.Offset.offset(0.02));
        assertThat((Double) r.get("tps")).isEqualTo(8 / total, org.assertj.core.data.Offset.offset(0.001));
        Map<String, Object> stt = ((List<Map<String, Object>>) r.get("stages")).get(3);
        assertThat(stt.get("virtual")).isEqualTo(true);
        assertThat((Double) stt.get("avgMs")).isEqualTo(100_000.0);
    }

    @Test
    @DisplayName("고속 모드 타임아웃 — 처리 시간 300초 > 타임아웃 200초면 기다리지 않고 전건 타임아웃 실패, 가상 시간은 타임아웃까지만")
    @SuppressWarnings("unchecked")
    void fastForwardTimeoutFailsImmediately() throws Exception {
        perf.start(new PerfRequest("B", 2, 2, 0, 1, 2, "FIXED", 300_000L, 300_000L, null, 200_000L, null));
        Map<String, Object> r = (Map<String, Object>) waitDone().get("result");
        assertThat(r.get("fail")).isEqualTo(4);
        assertThat(r.get("timeout")).isEqualTo(4L);
        assertThat(r.get("execStsCd")).isEqualTo("FAIL");
        assertThat((Double) r.get("virtualSttSec")).isEqualTo(400.0);   // 4건 × 200초 ÷ 워커 2
    }

    @Test
    @DisplayName("파이프라인 모의 — STT 가 병목이면 STT 합 ÷ 워커, 확보가 병목이면 확보 구간 + 마지막 한 건")
    void pipelineSimulation() {
        // 확보가 빠르다(0ms 에 4건 준비) · STT 100 × 4건 · STT 워커 2 → 200
        List<PerfStageMeter.PipelineItem> fast = List.of(item(0, 100), item(0, 100), item(0, 100), item(0, 100));
        assertThat(PerfRunService.pipelineMs(fast, 2, 0)).isEqualTo(200L);
        // 확보가 느리다(100ms 마다 한 건) · STT 50 · STT 워커 4 → 마지막 건 준비(400) + 50 = 450
        //   순차 합산(확보 400 + STT 200 ÷ 4 = 450)과 같지만, STT 가 길어지면 차이가 난다 ↓
        List<PerfStageMeter.PipelineItem> slow = List.of(item(100, 50), item(200, 50), item(300, 50), item(400, 50));
        assertThat(PerfRunService.pipelineMs(slow, 4, 400)).isEqualTo(450L);
        // 확보 100ms 마다 · STT 1,000 · STT 워커 4 → 겹쳐서 1,400 (확보 400 을 따로 더하면 1,400 + 400 이 된다)
        List<PerfStageMeter.PipelineItem> overlap = List.of(item(100, 1000), item(200, 1000), item(300, 1000), item(400, 1000));
        assertThat(PerfRunService.pipelineMs(overlap, 4, 400)).isEqualTo(1400L);
        // 확보에서 끝난 건만 있으면 확보 구간이 곧 끝
        assertThat(PerfRunService.pipelineMs(List.of(), 4, 350)).isEqualTo(350L);
    }

    private static PerfStageMeter.PipelineItem item(long readyMs, long virtualMs) {
        return new PerfStageMeter.PipelineItem(readyMs, 0L, virtualMs);
    }

    @Test
    @DisplayName("가상 STT 분배 — 건수가 많으면 합 ÷ 워커, 워커보다 적으면 가장 긴 한 건")
    void distributeOverWorkers() {
        assertThat(PerfRunService.distribute(List.of(5L, 5L, 5L, 5L), 2)).isEqualTo(10L);
        assertThat(PerfRunService.distribute(List.of(10L), 16)).isEqualTo(10L);
        assertThat(PerfRunService.distribute(List.of(3L, 3L, 3L), 1)).isEqualTo(9L);
        assertThat(PerfRunService.distribute(List.of(), 4)).isZero();
    }

    @Test
    @DisplayName("범위 밖이면 시작하지 않는다 — 합계 301건")
    void rejectsOutOfRange() {
        assertThatThrownBy(() -> perf.start(new PerfRequest("A", 151, 150, 3, 1, 4, "FIXED", 0L, 0L, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Map<String, Object> waitDone() throws InterruptedException {
        long until = System.currentTimeMillis() + 120_000;
        Map<String, Object> cur = perf.current();
        while (System.currentTimeMillis() < until) {
            cur = perf.current();
            if (Boolean.FALSE.equals(cur.get("active"))) {
                return cur;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("성능 시험이 120초 안에 끝나지 않았다 — " + cur);
    }
}
