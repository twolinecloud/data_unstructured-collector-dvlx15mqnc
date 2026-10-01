package egovframework.unstructured.collector.voice.batch;

import egovframework.unstructured.collector.voice.controller.VoiceBatchController;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 상한과 넓은 창 — <b>이미 처리된 건이 상한을 다 채워 새 건이 밀려나면 안 된다.</b>
 *
 * <p>조회는 {@code CRT_DT} 오름차순 + {@code FETCH FIRST {상한}} 이고, 처리했는지는 조회 <b>뒤에</b>
 * 멱등 표식으로 가린다. 창이 넓으면 앞쪽의 끝난 건이 상한을 다 채운다 — 운영 상한 500 에 하루 1,300건이면
 * 오전 500건이 끝난 뒤로 오후 건이 영영 조회되지 않는다.</p>
 *
 * <p>여기서는 상한을 <b>3</b>으로 줄여 같은 상황을 만든다. 14건을 [바로 실행]으로 거듭 돌리면
 * 매번 새 3건을 처리해 결국 14건을 모두 끝내야 한다. 고치기 전에는 두 번째 실행부터
 * 앞쪽 3건(이미 처리)만 다시 읽어 0건을 처리했다.</p>
 */
@SpringBootTest
@ActiveProfiles("local")
class PagingStarvationTest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void dirs(DynamicPropertyRegistry registry) {
        registry.add("voice.source.mode", () -> "MOCK");
        registry.add("voice.broker.mode", () -> "MOCK");
        registry.add("log-collector.enabled", () -> "false");
        registry.add("voice.batch.max-files-per-run", () -> "3");
        registry.add("voice.dirs.base-dir", () -> tmp.toString());
        registry.add("voice.dirs.receive-meet", () -> tmp.resolve("raw/meet").toString());
        registry.add("voice.dirs.receive-phone", () -> tmp.resolve("raw/phone").toString());
        registry.add("voice.dirs.work", () -> tmp.resolve("work").toString());
        registry.add("voice.dirs.xvarm-original", () -> tmp.resolve("xvarm_original").toString());
        registry.add("voice.sync.wait-timeout-sec", () -> "15");
        registry.add("voice.sync.stable-check-ms", () -> "50");
    }

    @Autowired
    private VoiceBatchController batches;
    @Autowired
    private egovframework.unstructured.collector.voice.source.SimulationDataService sim;
    @Autowired
    private IdempotencyGuard idempotency;

    @BeforeEach
    void seed() {
        sim.seed();
        idempotency.clearAll();
    }

    @Test
    @DisplayName("상한 3 · 14건 — [바로 실행]을 거듭하면 매번 새 건을 처리해 결국 전부 끝낸다")
    void repeatedRunsEventuallyFinishEverything() {
        int total = 0;
        int runs = 0;
        while (runs < 10) {
            VoiceBatchResult r = batches.onDemand(null, true, null);
            runs++;
            assertThat(r.failCnt()).isZero();
            if (r.successCnt() == 0) {
                break;
            }
            assertThat(r.successCnt()).as("%d회차 — 상한만큼씩", runs).isLessThanOrEqualTo(3);
            total += r.successCnt();
        }

        assertThat(total).as("14건이 전부 처리돼야 한다(고치기 전엔 3건에서 멈췄다)").isEqualTo(14);
        Map<String, Object> left = batches.pending("on-demand", null, null, true);
        assertThat(((Number) left.get("pending")).longValue()).isZero();
    }

    @Test
    @DisplayName("미처리 건수도 뒤쪽을 본다 — 앞쪽 3건이 끝났다고 '0건 · 생성할까요?' 를 묻지 않는다")
    void pendingCountLooksPastProcessedRows() {
        batches.onDemand(null, true, null);   // 앞쪽 3건 처리

        Map<String, Object> p = batches.pending("on-demand", null, null, true);

        // 이 값은 '다음 실행이 처리할 건수' 라 상한(3)으로 잘린다. 요점은 0 이 아니라는 것 —
        //   고치기 전에는 앞쪽 3건(처리 끝)만 읽어 0 이 나왔고, 화면이 데이터를 다시 만들자고 물었다.
        assertThat(((Number) p.get("pending")).longValue()).isEqualTo(3);
    }
}
