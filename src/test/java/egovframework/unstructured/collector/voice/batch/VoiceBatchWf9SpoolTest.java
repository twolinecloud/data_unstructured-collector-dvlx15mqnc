package egovframework.unstructured.collector.voice.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import egovframework.unstructured.collector.common.model.BatchWindow;
import egovframework.unstructured.collector.common.model.FileProcOutcome;
import egovframework.unstructured.collector.common.model.SttResult;
import egovframework.unstructured.collector.voice.risk.RiskWf9Pipeline;
import egovframework.unstructured.collector.voice.risk.Wf9Job;
import egovframework.unstructured.collector.voice.risk.Wf9Spool;
import egovframework.unstructured.collector.voice.transfer.VoiceDeidentClient;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * 배치 연결(2026-11-27 텍스트 전용) — WF9 를 켜면 DEIDENT 단계 뒤 건마다 <b>가린 전사로 만든 문서</b>가 스풀에 들어가고,
 * 배치 결과는 그대로다(T1~T5 · 전송 · 성공 수). 음성 사본은 없다.
 *
 * <p>커넥터 비식별은 목이다 — mode=DEIDENT 로 답하고 글을 "가린 …" 으로 바꿔 돌려준다(실제 마스킹 흉내).
 * 스풀 이후(제논 · 로그 컬렉터)는 부르지 않는다 — 처리 파이프라인은 목으로 바꾸고 작업자는 돌지 않게 첫 순회를 늦춘다.
 * 대상은 시뮬레이터 시드(가상 10건 + 주기 4건)다. 보라미 STT 재사용 건도 DEIDENT 를 거치므로 이제 WF9 에 들어간다.</p>
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

    /** 커넥터 비식별 — 받은 전사(원문)는 적어 두고 "가린 …" 으로 바꿔 돌려준다. */
    @MockitoBean
    private VoiceDeidentClient deident;

    @Autowired
    private VoiceCollectService service;
    @Autowired
    private Wf9Spool spool;
    @Autowired
    private IdempotencyGuard idempotency;
    @Autowired
    private egovframework.unstructured.collector.voice.source.SimulationDataService sim;

    private final ObjectMapper om = new ObjectMapper();
    private final List<String> originals = new CopyOnWriteArrayList<>();

    @BeforeEach
    void reset() throws Exception {
        sim.seed();
        idempotency.clearAll();
        for (Wf9Spool.Entry e : spool.list()) {
            spool.delete(e);
        }
        originals.clear();
        when(deident.enabled()).thenReturn(true);
        when(deident.mode()).thenReturn(Wf9Spool.DEIDENT_MODE);
        doAnswer(inv -> {
            SttResult stt = inv.getArgument(3);
            originals.add(stt.text());
            List<String> segs = stt.hasSegments()
                    ? stt.segments().stream().map(s -> "가린 구간 " + s.id()).toList() : null;
            return new VoiceDeidentClient.Result("가린 전문", segs, Wf9Spool.DEIDENT_MODE, null, 1);
        }).when(deident).deident(anyString(), any(), any(), any());
    }

    private VoiceBatchResult runBatch() {
        LocalDateTime now = LocalDateTime.now();
        return service.run(BatchWindow.manual(now.minusDays(2), now.plusDays(1)), null, "TEST", true);
    }

    @Test
    @DisplayName("DEIDENT 뒤 건마다 가린 문서 하나(보라미 STT 재사용 건 포함) — 녹음 ID · 교정번호 · 구분 · 일시 · 배치 ID · 원문 전사 없음 · 배치 결과는 그대로")
    void spoolsMaskedDocAfterDeident() throws Exception {
        VoiceBatchResult result = runBatch();

        assertThat(result.execStsCd()).isEqualTo("SUCCESS");
        assertThat(result.successCnt()).isEqualTo(14);
        assertThat(spool.root()).isEqualTo(tmp.resolve("wf9-spool"));

        List<Wf9Spool.Entry> entries = spool.list();
        assertThat(entries).hasSize(14);
        assertThat(result.outcomes()).as("보라미 STT 재사용 건이 시드에 있다").anyMatch(o -> o.target().hasSourceStt());
        Map<String, Wf9Job> byRec = entries.stream().map(Wf9Spool.Entry::job)
                .collect(Collectors.toMap(Wf9Job::recFileId, j -> j));
        for (FileProcOutcome o : result.outcomes()) {
            Wf9Job j = byRec.get(o.target().idempotencyKey());
            assertThat(j).as(o.target().shortId()).isNotNull();
            assertThat(j.corrNo()).isEqualTo(o.target().corrNo());
            assertThat(j.kind()).isEqualTo(o.target().kind().name());
            assertThat(j.occurredLocal()).isEqualTo(o.target().occurredAt().withNano(0));
            assertThat(j.execId()).isEqualTo(result.execId());
            assertThat(j.docFile()).isEqualTo("doc.json");
        }
        for (Wf9Spool.Entry e : entries) {
            try (var files = Files.list(e.dir())) {
                assertThat(files.map(f -> f.getFileName().toString()).toList())
                        .as("음성 사본이 없다").containsExactlyInAnyOrder("job.json", "doc.json");
            }
            String raw = Files.readString(e.dir().resolve("doc.json"));
            assertThat(raw).doesNotContain(e.job().recFileId()).doesNotContain(e.job().corrNo());
            for (String original : originals) {
                if (original != null && original.strip().length() >= 4) {
                    assertThat(raw).as("원문 전사가 섞이지 않는다").doesNotContain(original.strip());
                }
            }
            JsonNode segs = om.readTree(raw).path("input").path("speech_results").path(0).path("segments");
            assertThat(segs).isNotEmpty();
            for (JsonNode s : segs) {
                assertThat(s.path("text").asText()).startsWith("가린 ");
                assertThat(s.path("speaker").asText()).isEqualTo("UNKNOWN");
            }
        }

        // 같은 녹음을 다시 처리해도(멱등 장부를 비운 재수집) 대기 중인 작업은 그대로 하나다.
        idempotency.clearAll();
        VoiceBatchResult again = runBatch();
        assertThat(again.successCnt()).isEqualTo(14);
        List<Wf9Spool.Entry> after = spool.list();
        assertThat(after).as("대기 중인 녹음은 다시 넣지 않는다").hasSize(14);
        assertThat(after).allSatisfy(e -> assertThat(e.job().execId()).isEqualTo(result.execId()));
    }

    @Test
    @DisplayName("커넥터가 실제로 가리지 않은 전사(mode=MOCK · BYPASS)는 WF9 로 보내지 않는다 — 배치 결과는 그대로")
    void nonDeidentModeSpoolsNothing() throws Exception {
        for (String mode : new String[]{"MOCK", "BYPASS"}) {
            idempotency.clearAll();
            doAnswer(inv -> {
                SttResult stt = inv.getArgument(3);
                return new VoiceDeidentClient.Result(stt.text(), null, mode, null, 0);
            }).when(deident).deident(anyString(), any(), any(), any());

            VoiceBatchResult result = runBatch();

            assertThat(result.execStsCd()).as(mode).isEqualTo("SUCCESS");
            assertThat(result.successCnt()).as(mode).isEqualTo(14);
            assertThat(spool.list()).as(mode).isEmpty();
        }
    }
}
