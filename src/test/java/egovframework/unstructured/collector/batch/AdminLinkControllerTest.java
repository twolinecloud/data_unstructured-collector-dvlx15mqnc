package egovframework.unstructured.collector.batch;

import egovframework.unstructured.collector.batch.schedule.BatchSchedule;
import egovframework.unstructured.collector.batch.schedule.UnstructuredBatchScheduler;
import egovframework.unstructured.collector.common.model.BatchWindow;
import egovframework.unstructured.collector.voice.batch.ResumeMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * admin 연동 엔드포인트 — admin-api 의 호출 규약(경로 · 본문)과 응답(200/202/409/400).
 */
class AdminLinkControllerTest {

    private UnstructuredBatchScheduler scheduler;
    private UnstructuredBatchService service;
    private UnstructuredJobRunner runner;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        scheduler = mock(UnstructuredBatchScheduler.class);
        service = mock(UnstructuredBatchService.class);
        runner = new UnstructuredJobRunner(() -> null);
        UnstructuredBatchProperties props = new UnstructuredBatchProperties(
                new UnstructuredBatchProperties.Admin("", Duration.ofSeconds(2), Duration.ofSeconds(3)),
                new UnstructuredBatchProperties.Schedule(300000, "Asia/Seoul"),
                new UnstructuredBatchProperties.Batch(false, 1000));
        mvc = MockMvcBuilders.standaloneSetup(new AdminLinkController(scheduler, service, runner, props)).build();
        when(scheduler.snapshot()).thenReturn(Map.of("schedVal", "02:00"));
    }

    private static UnstructuredBatchService.Plan plan(ResumeMode resume, String origin) {
        return new UnstructuredBatchService.Plan(BatchWindow.manual(LocalDateTime.of(2026, 10, 2, 0, 0),
                LocalDateTime.of(2026, 10, 2, 10, 0)), resume, origin, false, "ADMIN", false);
    }

    // ── refresh ──

    @Test
    @DisplayName("refresh 본문 — 그대로 적용, 응답 applied:UNSTRUCTURED (data-collector 와 같은 모양)")
    void refreshWithBody() throws Exception {
        when(scheduler.applySchedule(any(), eq(UnstructuredBatchScheduler.Source.REFRESH)))
                .thenReturn(UnstructuredBatchScheduler.Apply.APPLIED);
        mvc.perform(post("/internal/schedule/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dataTypeCd\":\"UNSTRUCTURED\",\"activeYn\":\"Y\",\"execSchedTypeCd\":\"FIXED_TIME\",\"schedVal\":\"02:00\",\"extra\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.result").value("applied:UNSTRUCTURED"));
        verify(scheduler).applySchedule(eq(new BatchSchedule("UNSTRUCTURED", "Y", "FIXED_TIME", "02:00")),
                eq(UnstructuredBatchScheduler.Source.REFRESH));
        verify(scheduler, never()).reschedule(any());
    }

    @Test
    @DisplayName("refresh 본문 없음 · 일부만 — admin 재조회(폴백), 응답 rescheduled")
    void refreshFallback() throws Exception {
        mvc.perform(post("/internal/schedule/refresh")).andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("rescheduled"));
        mvc.perform(post("/internal/schedule/refresh").contentType(MediaType.APPLICATION_JSON).content("{\"dataTypeCd\":\"UNSTRUCTURED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("rescheduled"));
        verify(scheduler, org.mockito.Mockito.times(2)).reschedule(UnstructuredBatchScheduler.Source.REFRESH_FETCH);
    }

    @Test
    @DisplayName("refresh 다른 유형은 무시하고 200 · 잘못된 값은 400")
    void refreshIgnoredAndInvalid() throws Exception {
        when(scheduler.applySchedule(any(), any())).thenReturn(UnstructuredBatchScheduler.Apply.IGNORED);
        mvc.perform(post("/internal/schedule/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dataTypeCd\":\"STRUCTURED\",\"activeYn\":\"Y\",\"execSchedTypeCd\":\"FIXED_TIME\",\"schedVal\":\"03:10\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("ignored:STRUCTURED"));
        when(scheduler.applySchedule(any(), any())).thenReturn(UnstructuredBatchScheduler.Apply.INVALID);
        mvc.perform(post("/internal/schedule/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dataTypeCd\":\"UNSTRUCTURED\",\"activeYn\":\"Y\",\"execSchedTypeCd\":\"INTERVAL_BASED\",\"schedVal\":\"00:00\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
    }

    // ── run ──

    @Test
    @DisplayName("바로 실행 — 202 + 로그 컬렉터 execId(COLLECTOR) · 실행 중 재요청은 409 + 실행 중 execId")
    void runAcceptedThenConflict() throws Exception {
        when(service.planRun(eq(LocalDateTime.of(2026, 10, 2, 10, 0)), any())).thenReturn(plan(ResumeMode.FULL, null));
        CountDownLatch hold = new CountDownLatch(1);
        when(service.execute(any(), any())).thenAnswer(inv -> {
            BiConsumer<String, Boolean> sink = inv.getArgument(1);
            sink.accept("20261002VOC005", true);
            hold.await(5, TimeUnit.SECONDS);
            return "ok";
        });
        String body = "{\"dataTypeCd\":\"UNSTRUCTURED\",\"targetToDtm\":\"2026-10-02T10:00:00\"}";
        mvc.perform(post("/internal/batch/run").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.execId").value("20261002VOC005"))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.execIdSource").value("COLLECTOR"))
                .andExpect(jsonPath("$.dataTypeCd").value("UNSTRUCTURED"));

        mvc.perform(post("/internal/batch/run").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.execId").value("20261002VOC005"))
                .andExpect(jsonPath("$.kind").value("RUN"));
        hold.countDown();
    }

    @Test
    @DisplayName("바로 실행 400 — 비정형 아님 · 시각 형식 오류 · 계획 거절(미래 · 이미 수집)")
    void runBadRequest() throws Exception {
        mvc.perform(post("/internal/batch/run").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dataTypeCd\":\"STRUCTURED\",\"targetToDtm\":\"2026-10-02T10:00:00\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/internal/batch/run").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dataTypeCd\":\"UNSTRUCTURED\",\"targetToDtm\":\"10/02 10:00\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("형식")));
        when(service.planRun(any(), any())).thenThrow(new IllegalArgumentException("targetToDtm 이 미래다"));
        mvc.perform(post("/internal/batch/run").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dataTypeCd\":\"UNSTRUCTURED\",\"targetToDtm\":\"2026-10-02 10:00:00\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("targetToDtm 이 미래다"));
        verify(service, never()).execute(any(), any());
    }

    // ── reprocess ──

    @Test
    @DisplayName("긴급 재처리 — admin ReprocessRequest 본문 · 202 + 새 execId · 원 execId · 단계")
    void reprocessAccepted() throws Exception {
        when(service.planReprocess("20260915VOC003", "UNSTRUCTURED", "SEND")).thenReturn(plan(ResumeMode.FROM_SEND, "20260915VOC003"));
        when(service.execute(any(), any())).thenAnswer(inv -> {
            BiConsumer<String, Boolean> sink = inv.getArgument(1);
            sink.accept("20261002VOC006", true);
            return "ok";
        });
        mvc.perform(post("/internal/batch/reprocess").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"execId\":\"20260915VOC003\",\"dataTypeCd\":\"UNSTRUCTURED\",\"stepTypeCd\":\"SEND\",\"stepSeq\":3}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.execId").value("20261002VOC006"))
                .andExpect(jsonPath("$.originExecId").value("20260915VOC003"))
                .andExpect(jsonPath("$.resume").value("FROM_SEND"))
                .andExpect(jsonPath("$.stepTypeCd").value("SEND"))
                .andExpect(jsonPath("$.stepSeq").value(3));
    }

    @Test
    @DisplayName("긴급 재처리 400 — 본문 없음 · 계획 거절(단계 · 원배치)")
    void reprocessBadRequest() throws Exception {
        mvc.perform(post("/internal/batch/reprocess")).andExpect(status().isBadRequest());
        when(service.planReprocess(anyString(), anyString(), any())).thenThrow(new IllegalArgumentException("비정형에 없는 단계(C05): DEIDENT"));
        mvc.perform(post("/internal/batch/reprocess").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"execId\":\"20260915VOC003\",\"dataTypeCd\":\"UNSTRUCTURED\",\"stepTypeCd\":\"DEIDENT\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("DEIDENT")));
    }

    @Test
    @DisplayName("상태 — 작업 · 스케줄을 함께")
    void statusShowsJobAndSchedule() throws Exception {
        mvc.perform(get("/internal/batch/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.job.status").value("NONE"))
                .andExpect(jsonPath("$.schedule.schedVal").value("02:00"))
                .andExpect(jsonPath("$.includeImage").value(false));
        assertThat(AdminLinkController.parseDtm("2026-10-02T10:00")).isEqualTo(LocalDateTime.of(2026, 10, 2, 10, 0));
        assertThat(AdminLinkController.parseDtm(null)).isNull();
        verify(service, never()).planReprocess(isNull(), any(), any());
    }
}
