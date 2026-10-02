package egovframework.unstructured.collector.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import egovframework.unstructured.collector.batch.schedule.BatchSchedule;
import egovframework.unstructured.collector.common.config.VoiceProperties;
import egovframework.unstructured.collector.common.logging.LogCollectorClient;
import egovframework.unstructured.collector.image.batch.ImageCollectService;
import egovframework.unstructured.collector.voice.batch.ResumeMode;
import egovframework.unstructured.collector.voice.batch.VoiceBatchResult;
import egovframework.unstructured.collector.voice.batch.VoiceCollectService;
import egovframework.unstructured.collector.voice.batch.Workers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 비정형 오케스트레이터 — 창 계산 · 재처리 단계 매핑 · 원배치 구간 · 음성 → 이미지 순서. */
class UnstructuredBatchServiceTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 2, 10, 30);

    private VoiceCollectService voice;
    private ImageCollectService image;
    private LogCollectorClient logc;
    private VoiceProperties.Batch batch;

    @BeforeEach
    void setUp() {
        voice = mock(VoiceCollectService.class);
        image = mock(ImageCollectService.class);
        logc = mock(LogCollectorClient.class);
        batch = mock(VoiceProperties.Batch.class);
        when(batch.periodicLagMin()).thenReturn(20);
        when(batch.jobId()).thenReturn("VOICE_ANALYSIS");
        when(batch.testJobId()).thenReturn("TEST_BATCH");
        when(logc.isEnabled()).thenReturn(true);
    }

    private UnstructuredBatchService service(boolean includeImage) {
        VoiceProperties vp = mock(VoiceProperties.class);
        when(vp.batch()).thenReturn(batch);
        UnstructuredBatchProperties props = new UnstructuredBatchProperties(
                new UnstructuredBatchProperties.Admin("", Duration.ofSeconds(2), Duration.ofSeconds(3)),
                new UnstructuredBatchProperties.Schedule(300000, "Asia/Seoul"),
                new UnstructuredBatchProperties.Batch(includeImage, 1500));
        UnstructuredBatchService s = new UnstructuredBatchService(voice, image, vp, logc, props);
        ReflectionTestUtils.setField(s, "catchupLookbackDays", 30);
        return s;
    }

    @Test
    @DisplayName("재처리 단계 매핑 — 비움·COLLECT=FULL · ANALYZE=FROM_ANALYZE · SEND=FROM_SEND · 그 밖은 400")
    void stepMapping() {
        assertThat(UnstructuredBatchService.resumeOf(null)).isEqualTo(ResumeMode.FULL);
        assertThat(UnstructuredBatchService.resumeOf(" ")).isEqualTo(ResumeMode.FULL);
        assertThat(UnstructuredBatchService.resumeOf("COLLECT")).isEqualTo(ResumeMode.FULL);
        assertThat(UnstructuredBatchService.resumeOf("analyze")).isEqualTo(ResumeMode.FROM_ANALYZE);
        assertThat(UnstructuredBatchService.resumeOf("SEND")).isEqualTo(ResumeMode.FROM_SEND);
        assertThatThrownBy(() -> UnstructuredBatchService.resumeOf("DEIDENT")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UnstructuredBatchService.resumeOf("CLEANSE")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("스케줄 창 — FIXED_TIME 은 일배치(어제), INTERVAL_BASED 는 주기(오늘 00:00 ~ 지금)")
    void scheduledWindow() {
        UnstructuredBatchService s = service(false);
        var daily = s.planScheduled(new BatchSchedule("UNSTRUCTURED", "Y", "FIXED_TIME", "02:00"), NOW);
        assertThat(daily.window().from()).isEqualTo(LocalDateTime.of(2026, 10, 1, 0, 0));
        assertThat(daily.window().to()).isEqualTo(LocalDateTime.of(2026, 10, 2, 0, 0));
        assertThat(daily.window().execTypeCd()).isEqualTo("SCHEDULED");
        var periodic = s.planScheduled(new BatchSchedule("UNSTRUCTURED", "Y", "INTERVAL_BASED", "00:10"), NOW);
        assertThat(periodic.window().from()).isEqualTo(LocalDateTime.of(2026, 10, 2, 0, 0));
        assertThat(periodic.window().to()).isEqualTo(NOW);
        assertThat(periodic.triggerBy()).isEqualTo("SCHEDULER");
    }

    @Test
    @DisplayName("바로 실행 — 워터마크 ~ targetToDtm · 미래/이미 수집한 구간은 400 · 비우면 지금-lag")
    void runWindow() {
        UnstructuredBatchService s = service(false);
        when(logc.watermark("UNSTRUCTURED", "VOICE_ANALYSIS"))
                .thenReturn(new LogCollectorClient.Watermark(LocalDateTime.of(2026, 10, 2, 0, 0), "20261002VOC001"));
        var p = s.planRun(LocalDateTime.of(2026, 10, 2, 10, 0), NOW);
        assertThat(p.window().from()).isEqualTo(LocalDateTime.of(2026, 10, 2, 0, 0));
        assertThat(p.window().to()).isEqualTo(LocalDateTime.of(2026, 10, 2, 10, 0));
        assertThat(p.window().execTypeCd()).isEqualTo("MANUAL");
        assertThat(s.planRun(null, NOW).window().to()).isEqualTo(NOW.minusMinutes(20));

        assertThatThrownBy(() -> s.planRun(NOW.plusMinutes(5), NOW)).hasMessageContaining("미래");
        assertThatThrownBy(() -> s.planRun(LocalDateTime.of(2026, 10, 1, 23, 0), NOW)).hasMessageContaining("이미 그 시각까지");

        when(logc.watermark(anyString(), anyString())).thenReturn(null);   // 첫 실행 — 최근 30일
        assertThat(s.planRun(NOW, NOW).window().from()).isEqualTo(NOW.minusDays(30));
    }

    @Test
    @DisplayName("재처리 — 원배치(T1)의 수집 구간 그대로 · 원 execId 의 보존물 · 시험 배치면 시험으로")
    void reprocessUsesOriginWindow() throws Exception {
        UnstructuredBatchService s = service(true);
        when(logc.batchDetail("20260915VOC003")).thenReturn(detail("20260915VOC003", "VOICE_ANALYSIS", "UNSTRUCTURED",
                "2026-09-14T00:00:00", "2026-09-15T00:00:00"));
        var p = s.planReprocess("20260915VOC003", "UNSTRUCTURED", "SEND");
        assertThat(p.window().from()).isEqualTo(LocalDateTime.of(2026, 9, 14, 0, 0));
        assertThat(p.window().to()).isEqualTo(LocalDateTime.of(2026, 9, 15, 0, 0));
        assertThat(p.resume()).isEqualTo(ResumeMode.FROM_SEND);
        assertThat(p.originExecId()).isEqualTo("20260915VOC003");
        assertThat(p.testRun()).isFalse();
        assertThat(p.triggerBy()).isEqualTo("ADMIN/reprocess:20260915VOC003");
        assertThat(p.includeImage()).as("재처리는 음성만").isFalse();

        when(logc.batchDetail("20261001TST002")).thenReturn(detail("20261001TST002", "TEST_BATCH", "UNSTRUCTURED",
                "2026-09-30T00:00:00", "2026-10-01T00:00:00"));
        assertThat(s.planReprocess("20261001TST002", "UNSTRUCTURED", null).testRun()).isTrue();
    }

    @Test
    @DisplayName("재처리 400 — 비정형 아님 · execId 없음 · 원배치 없음 · 정형 배치 · 구간 없음")
    void reprocessRejects() throws Exception {
        UnstructuredBatchService s = service(false);
        assertThatThrownBy(() -> s.planReprocess("X", "STRUCTURED", "SEND")).hasMessageContaining("UNSTRUCTURED");
        assertThatThrownBy(() -> s.planReprocess(" ", "UNSTRUCTURED", "SEND")).hasMessageContaining("execId");
        assertThatThrownBy(() -> s.planReprocess("NONE", "UNSTRUCTURED", "SEND")).hasMessageContaining("찾을 수 없다");
        when(logc.batchDetail("20261002STR001")).thenReturn(detail("20261002STR001", "STRUCTURED_BATCH", "STRUCTURED",
                "2026-10-01T00:00:00", "2026-10-02T00:00:00"));
        assertThatThrownBy(() -> s.planReprocess("20261002STR001", "UNSTRUCTURED", null)).hasMessageContaining("비정형 배치가 아니다");
        when(logc.batchDetail("20261002VOC009")).thenReturn(detail("20261002VOC009", "VOICE_ANALYSIS", "UNSTRUCTURED", null, null));
        assertThatThrownBy(() -> s.planReprocess("20261002VOC009", "UNSTRUCTURED", null)).hasMessageContaining("구간이 없다");
    }

    @Test
    @DisplayName("실행 — 음성만(기본) · include-image 면 음성 → 이미지 순차 · 이미지 실패가 음성 결과를 덮지 않음")
    void executeOrder() {
        VoiceBatchResult vr = new VoiceBatchResult("20261002VOC001", true, "DAILY", 3, 3, 0, 0, 100L, Map.of(), List.of(),
                List.of(), false);
        when(voice.defaultWorkers()).thenReturn(Workers.sequential());
        when(voice.run(any(), isNull(), anyString(), anyBoolean(), any(), any(), any(Workers.class), any())).thenReturn(vr);
        var plan = new UnstructuredBatchService.Plan(
                egovframework.unstructured.collector.common.model.BatchWindow.daily(NOW), ResumeMode.FULL, null, false,
                "SCHEDULER", false);

        String only = service(false).execute(plan, (id, c) -> { });
        assertThat(only).startsWith("음성 execId=20261002VOC001");
        verify(image, never()).run(any());

        var withImage = new UnstructuredBatchService.Plan(plan.window(), ResumeMode.FULL, null, false, "SCHEDULER", true);
        when(image.run(any())).thenThrow(new IllegalStateException("이미지 수집이 이미 돌고 있습니다"));
        String both = service(true).execute(withImage, (id, c) -> { });
        assertThat(both).contains("음성 execId=20261002VOC001").contains("이미지 실패");
        InOrder order = inOrder(voice, image);
        order.verify(voice).run(any(), isNull(), eq("SCHEDULER"), eq(false), eq(ResumeMode.FULL), isNull(), any(Workers.class), any());
        order.verify(image).run(any());
    }

    private static JsonNode detail(String execId, String jobId, String type, String from, String to) throws Exception {
        String f = from == null ? "null" : "\"" + from + "\"";
        String t = to == null ? "null" : "\"" + to + "\"";
        return OM.readTree("""
                {"batch":{"exec_id":"%s","job_id":"%s","data_type_cd":"%s","exec_sts_cd":"FAIL",
                 "target_from_dtm":%s,"target_to_dtm":%s},"steps":[]}""".formatted(execId, jobId, type, f, t));
    }
}
