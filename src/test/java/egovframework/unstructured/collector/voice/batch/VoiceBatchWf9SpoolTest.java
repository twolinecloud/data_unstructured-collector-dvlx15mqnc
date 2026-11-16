package egovframework.unstructured.collector.voice.batch;

import egovframework.unstructured.collector.common.model.BatchWindow;
import egovframework.unstructured.collector.common.model.FileProcOutcome;
import egovframework.unstructured.collector.voice.risk.RiskWf9Pipeline;
import egovframework.unstructured.collector.voice.risk.Wf9Job;
import egovframework.unstructured.collector.voice.risk.Wf9Spool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배치 연결 — WF9 를 켜면 복호화 직후 건마다 음성 사본이 스풀에 들어가고, 배치 결과는 그대로다(T1~T5 · 전송 · 성공 수).
 *
 * <p>스풀 이후(제논 · 커넥터 · 로그 컬렉터)는 부르지 않는다 — 처리 파이프라인은 목으로 바꾸고 작업자는 돌지 않게 첫 순회를 늦춘다.
 * 대상은 시뮬레이터 시드(가상 10건 + 주기 4건)다. 그중 보라미 STT 재사용 건은 음성이 없어 WF9 에서 빠진다.</p>
 */
@SpringBootTest
@ActiveProfiles("local")
class VoiceBatchWf9SpoolTest {

    @TempDir
    static java.nio.file.Path tmp;

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
        registry.add("voice.risk-wf9.enabled", () -> "true");
        registry.add("voice.risk-wf9.skip-test-runs", () -> "false");   // 이 시험의 배치는 시험 실행(TST)이다
        registry.add("voice.risk-wf9.initial-delay-sec", () -> "3600");  // 작업자는 돌지 않는다
    }

    /** 끝까지 갈 수 있는 설정으로 본다(blocker → null). 처리는 부르지 않는다. */
    @MockitoBean
    private RiskWf9Pipeline pipeline;

    @Autowired
    private VoiceCollectService service;
    @Autowired
    private Wf9Spool spool;
    @Autowired
    private IdempotencyGuard idempotency;
    @Autowired
    private egovframework.unstructured.collector.voice.source.SimulationDataService sim;

    @BeforeEach
    void reset() {
        sim.seed();
        idempotency.clearAll();
    }

    @Test
    @DisplayName("음성을 복호화한 건마다 사본 하나(보라미 STT 재사용 건은 빠짐) — 녹음 ID · 교정번호 · 구분 · 일시 · 배치 ID, 위치는 복호화 폴더 옆 · 배치 결과는 그대로")
    void spoolsAfterDecrypt() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        VoiceBatchResult result = service.run(BatchWindow.manual(now.minusDays(2), now.plusDays(1)), null, "TEST", true);

        assertThat(result.execStsCd()).isEqualTo("SUCCESS");
        assertThat(result.successCnt()).isEqualTo(14);
        assertThat(spool.root()).isEqualTo(tmp.resolve("wf9-spool"));

        // 보라미 STT 재사용 건(시드의 전화 1건)은 복호화를 건너뛰어 음성이 없다 → 넣지 않는다
        List<FileProcOutcome> withAudio = result.outcomes().stream().filter(o -> !o.target().hasSourceStt()).toList();
        assertThat(withAudio).hasSize(13);
        List<Wf9Spool.Entry> entries = spool.list();
        assertThat(entries).hasSize(13);
        Map<String, Wf9Job> byRec = entries.stream().map(Wf9Spool.Entry::job)
                .collect(Collectors.toMap(Wf9Job::recFileId, j -> j));
        assertThat(result.outcomes()).filteredOn(o -> o.target().hasSourceStt())
                .allSatisfy(o -> assertThat(byRec).doesNotContainKey(o.target().idempotencyKey()));
        for (FileProcOutcome o : withAudio) {
            Wf9Job j = byRec.get(o.target().idempotencyKey());
            assertThat(j).as(o.target().shortId()).isNotNull();
            assertThat(j.corrNo()).isEqualTo(o.target().corrNo());
            assertThat(j.kind()).isEqualTo(o.target().kind().name());
            assertThat(j.occurredLocal()).isEqualTo(o.target().occurredAt().withNano(0));
            assertThat(j.execId()).isEqualTo(result.execId());
        }
        assertThat(entries).allSatisfy(e -> assertThat(Files.size(e.dir().resolve(e.job().audioFile()))).isPositive());

        // 같은 녹음을 다시 처리해도(멱등 장부를 비운 재수집) 대기 중인 작업은 그대로 하나다.
        idempotency.clearAll();
        VoiceBatchResult again = service.run(BatchWindow.manual(now.minusDays(2), now.plusDays(1)), null, "TEST", true);
        assertThat(again.successCnt()).isEqualTo(14);
        List<Wf9Spool.Entry> after = spool.list();
        assertThat(after).as("대기 중인 녹음은 다시 넣지 않는다").hasSize(13);
        assertThat(after).allSatisfy(e -> assertThat(e.job().execId()).isEqualTo(result.execId()));
    }
}
