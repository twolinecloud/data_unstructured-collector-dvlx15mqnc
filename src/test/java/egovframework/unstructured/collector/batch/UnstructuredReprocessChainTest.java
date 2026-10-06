package egovframework.unstructured.collector.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import egovframework.unstructured.collector.common.config.VoiceProperties;
import egovframework.unstructured.collector.common.logging.LogCollectorClient;
import egovframework.unstructured.collector.common.model.BatchWindow;
import egovframework.unstructured.collector.image.batch.ImageCollectService;
import egovframework.unstructured.collector.voice.batch.ResumeMode;
import egovframework.unstructured.collector.voice.batch.VoiceBatchResult;
import egovframework.unstructured.collector.voice.batch.VoiceCollectService;
import egovframework.unstructured.collector.voice.batch.Workers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 긴급 재처리 체인(2026-10-06) — 음성: 누른 배치까지 이전 실패를 오래된 순으로 같은 실행 ID 로 다시 열어 · 이미지: 실패 이미지 배치 전부 ·
 * 중간 실패해도 계속 · 체인을 못 쓰면 예전처럼 새 실행 ID.
 */
class UnstructuredReprocessChainTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final LocalDateTime D15 = LocalDateTime.of(2026, 10, 15, 0, 0);

    private VoiceCollectService voice;
    private ImageCollectService image;
    private LogCollectorClient logc;
    private UnstructuredBatchService service;

    @BeforeEach
    void setUp() {
        voice = mock(VoiceCollectService.class);
        image = mock(ImageCollectService.class);
        logc = mock(LogCollectorClient.class);
        VoiceProperties.Batch batch = mock(VoiceProperties.Batch.class);
        when(batch.jobId()).thenReturn("VOICE_ANALYSIS");
        when(batch.testJobId()).thenReturn("TEST_BATCH");
        when(batch.periodicLagMin()).thenReturn(20);
        VoiceProperties vp = mock(VoiceProperties.class);
        when(vp.batch()).thenReturn(batch);
        when(logc.isEnabled()).thenReturn(true);
        when(logc.reopenBatch(anyString(), any(), any(), any())).thenReturn(true);
        when(voice.defaultWorkers()).thenReturn(Workers.sequential());
        when(voice.run(any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> result(((VoiceCollectService.Reopened) inv.getArgument(8)).execId()));
        service = new UnstructuredBatchService(voice, image, vp, logc, new UnstructuredBatchProperties(
                new UnstructuredBatchProperties.Admin("", Duration.ofSeconds(2), Duration.ofSeconds(3)),
                new UnstructuredBatchProperties.Schedule(300000, "Asia/Seoul"),
                new UnstructuredBatchProperties.Batch(true, 1500)));
        ReflectionTestUtils.setField(service, "catchupLookbackDays", 30);
    }

    private static VoiceBatchResult result(String execId) {
        return new VoiceBatchResult(execId, true, "w", 3, 3, 0, 0, 10L, Map.of(), List.of(), List.of());
    }

    private static LogCollectorClient.ChainBatch voiceBatch(String id, int day) {
        return new LogCollectorClient.ChainBatch(id, "FAIL", "VOICE_ANALYSIS", "음성", D15.plusDays(day).plusHours(2),
                D15.plusDays(day - 1), D15.plusDays(day));
    }

    private JsonNode t4(String... failedRecs) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String r : failedRecs) {
            rows.add(Map.of("rec_file_id", r, "inmate_pid", "P-" + r, "file_nm", r + ".m4a", "proc_sts_cd", "FAIL"));
        }
        rows.add(Map.of("rec_file_id", "OK", "inmate_pid", "P-OK", "proc_sts_cd", "SUCCESS"));
        return OM.valueToTree(Map.of("rows", rows));
    }

    private UnstructuredBatchService.Plan voicePlan(String origin, ResumeMode mode) {
        return new UnstructuredBatchService.Plan(BatchWindow.manual(D15, D15.plusDays(1)), mode, origin, false,
                "ADMIN/reprocess:" + origin, false);
    }

    @Test
    @DisplayName("음성 17 클릭 — 15 → 16 → 17 을 같은 실행 ID 로 다시 열어 각자 구간으로 · 17 은 요청 단계, 앞 배치는 전송부터 · 원래 실패 키를 넘긴다")
    @SuppressWarnings("unchecked")
    void voiceChainOldestFirstSameIds() throws Exception {
        when(logc.failedChain("UNS17", false)).thenReturn(List.of(voiceBatch("UNS15", 0), voiceBatch("UNS16", 1), voiceBatch("UNS17", 2)));
        when(logc.fileProcs(anyString(), anyInt())).thenAnswer(inv -> t4("K-" + inv.getArgument(0)));
        BiConsumer<String, Boolean> onExecId = mock(BiConsumer.class);

        String sum = service.execute(voicePlan("UNS17", ResumeMode.FROM_ANALYZE), onExecId);

        verify(onExecId).accept("UNS17", true);
        InOrder o = inOrder(logc, voice);
        for (String id : List.of("UNS15", "UNS16", "UNS17")) {
            o.verify(logc).reopenBatch(eq(id), eq("ADMIN/reprocess:UNS17"), any(), any());
            ArgumentCaptor<BatchWindow> w = ArgumentCaptor.forClass(BatchWindow.class);
            ArgumentCaptor<ResumeMode> mode = ArgumentCaptor.forClass(ResumeMode.class);
            ArgumentCaptor<VoiceCollectService.Reopened> re = ArgumentCaptor.forClass(VoiceCollectService.Reopened.class);
            o.verify(voice).run(w.capture(), isNull(), eq("ADMIN/reprocess:UNS17"), eq(false), mode.capture(), eq(id), any(), isNull(),
                    re.capture());
            assertThat(re.getValue().execId()).isEqualTo(id);
            assertThat(re.getValue().failedKeys()).containsExactly("K-" + id);   // 성공 행은 넘기지 않는다
            assertThat(mode.getValue()).isEqualTo(id.equals("UNS17") ? ResumeMode.FROM_ANALYZE : ResumeMode.FROM_SEND);
        }
        verify(voice, never()).run(any(), any(), any(), anyBoolean(), any(), any(), any(), any());   // 새 실행 ID 경로는 안 탄다
        assertThat(sum).contains("체인(음성) 3건").contains("UNS15").contains("UNS17");
    }

    @Test
    @DisplayName("중간 배치가 실패해도 다음 배치로 계속 — 실패한 배치만 FAIL 로 닫는다 · 다시 열기 실패는 건너뛴다")
    void continuesAfterFailure() {
        when(logc.failedChain("UNS17", false)).thenReturn(List.of(voiceBatch("UNS15", 0), voiceBatch("UNS16", 1), voiceBatch("UNS17", 2)));
        when(logc.reopenBatch(eq("UNS15"), any(), any(), any())).thenReturn(false);
        org.mockito.Mockito.doThrow(new IllegalStateException("broker down"))
                .when(voice).run(any(), any(), any(), anyBoolean(), any(), eq("UNS16"), any(), any(), any());

        String sum = service.execute(voicePlan("UNS17", ResumeMode.FROM_SEND), null);

        verify(voice, never()).run(any(), any(), any(), anyBoolean(), any(), eq("UNS15"), any(), any(), any());
        verify(logc).finishBatch(eq("UNS16"), eq("FAIL"), anyInt(), isNull(), isNull(), isNull(), anyString());
        verify(voice).run(any(), any(), any(), anyBoolean(), any(), eq("UNS17"), any(), any(), any());
        assertThat(sum).contains("UNS15 다시 열기 실패").contains("UNS16 실패").contains("UNS17 음성");
    }

    @Test
    @DisplayName("이미지 — 실패 이미지 배치 전부(all=true)를 오래된 순으로 다시 열어 · 원배치 T4 실패 행을 넘긴다")
    @SuppressWarnings("unchecked")
    void imageAllFailedBatches() throws Exception {
        when(logc.failedChain("IMG15", true)).thenReturn(List.of(
                new LogCollectorClient.ChainBatch("IMG15", "PARTIAL", "IMAGE_COLLECT", "수용자 이미지 수집", D15, null, null),
                new LogCollectorClient.ChainBatch("IMG17", "FAIL", "IMAGE_COLLECT", "수용자 이미지 수집", D15.plusDays(2), null, null)));
        when(logc.fileProcs(anyString(), anyInt())).thenAnswer(inv -> t4("F-" + inv.getArgument(0)));
        when(image.runReopened(any(), anyString(), any())).thenReturn(mock(ImageCollectService.ImageRunResult.class));
        UnstructuredBatchService.Plan p = new UnstructuredBatchService.Plan(BatchWindow.manual(D15, D15.plusSeconds(1)),
                ResumeMode.FULL, "IMG15", false, "ADMIN/reprocess:IMG15", false, true);

        service.execute(p, null);

        InOrder o = inOrder(logc, image);
        for (String id : List.of("IMG15", "IMG17")) {
            o.verify(logc).reopenBatch(eq(id), any(), isNull(), isNull());
            ArgumentCaptor<List<JsonNode>> rows = ArgumentCaptor.forClass(List.class);
            o.verify(image).runReopened(any(), eq(id), rows.capture());
            assertThat(rows.getValue()).extracting(n -> n.path("rec_file_id").asText()).containsExactly("F-" + id);
        }
        verify(image, never()).run(any(), any());
    }

    @Test
    @DisplayName("체인을 못 쓰면(옛 로그 컬렉터 null · 원배치가 실패가 아님 빈 목록) 예전처럼 새 실행 ID 로 원배치 구간을 돈다")
    void fallsBackWithoutChain() {
        when(logc.failedChain("UNS17", false)).thenReturn(null);
        when(voice.run(any(), any(), any(), anyBoolean(), any(), any(), any(), any())).thenReturn(result("NEW"));

        service.execute(voicePlan("UNS17", ResumeMode.FROM_SEND), null);

        verify(voice, times(1)).run(any(), isNull(), anyString(), eq(false), eq(ResumeMode.FROM_SEND), eq("UNS17"), any(), any());
        verify(logc, never()).reopenBatch(anyString(), any(), any(), any());
    }
}
