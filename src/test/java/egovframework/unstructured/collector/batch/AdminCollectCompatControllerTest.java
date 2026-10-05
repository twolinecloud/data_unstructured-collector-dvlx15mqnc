package egovframework.unstructured.collector.batch;

import egovframework.unstructured.collector.common.model.BatchWindow;
import egovframework.unstructured.collector.voice.batch.ResumeMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PL 규약 호환 — data-collector {@code /internal/collect/structured-incremental} · {@code /internal/collect/reprocess} 와
 * 같은 경로 모양 · 쿼리 파라미터 · 응답 모양(200 Response{result.accepted} · 202 · 400 · 409).
 */
class AdminCollectCompatControllerTest {

    private UnstructuredBatchService service;
    private UnstructuredJobRunner runner;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(UnstructuredBatchService.class);
        runner = new UnstructuredJobRunner(() -> null);
        UnstructuredBatchProperties props = new UnstructuredBatchProperties(
                new UnstructuredBatchProperties.Admin("", Duration.ofSeconds(2), Duration.ofSeconds(3)),
                new UnstructuredBatchProperties.Schedule(300000, "Asia/Seoul"),
                new UnstructuredBatchProperties.Batch(false, 1000));
        mvc = MockMvcBuilders.standaloneSetup(new AdminCollectCompatController(service, runner, props)).build();
    }

    private static UnstructuredBatchService.Plan plan(ResumeMode resume, String origin, String triggerBy) {
        return new UnstructuredBatchService.Plan(BatchWindow.manual(LocalDateTime.of(2026, 10, 5, 0, 0),
                LocalDateTime.of(2026, 10, 5, 10, 0)), resume, origin, false, triggerBy, false);
    }

    @Test
    @DisplayName("바로 실행 — 200 Response{result:{accepted:true, kind, execId, status:RUNNING, hint}} · TRIGGER_BY = triggerBy · "
            + "실행 중 재요청은 200 accepted:false(data-collector 와 같다)")
    void incrementalAcceptedThenBusy() throws Exception {
        when(service.planRun(eq(LocalDateTime.of(2026, 10, 5, 10, 0)), any())).thenReturn(plan(ResumeMode.FULL, null, "ADMIN"));
        CountDownLatch hold = new CountDownLatch(1);
        ArgumentCaptor<UnstructuredBatchService.Plan> captor = ArgumentCaptor.forClass(UnstructuredBatchService.Plan.class);
        when(service.execute(captor.capture(), any())).thenAnswer(inv -> {
            BiConsumer<String, Boolean> sink = inv.getArgument(1);
            sink.accept("20261005UNS001", true);
            hold.await(5, TimeUnit.SECONDS);
            return "ok";
        });

        mvc.perform(post("/internal/collect/unstructured-incremental")
                        .param("execType", "MANUAL").param("triggerBy", "admin01").param("to", "2026-10-05T10:00:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.http_status_code").value(200))
                .andExpect(jsonPath("$.result.accepted").value(true))
                .andExpect(jsonPath("$.result.kind").value("UNSTRUCTURED_INCREMENTAL"))
                .andExpect(jsonPath("$.result.execId").value("20261005UNS001"))
                .andExpect(jsonPath("$.result.status").value("RUNNING"))
                .andExpect(jsonPath("$.result.hint").value(org.hamcrest.Matchers.containsString("/internal/collect/unstructured-incremental/status")));
        assertThat(captor.getValue().triggerBy()).isEqualTo("admin01");

        mvc.perform(post("/internal/collect/unstructured-incremental").param("to", "2026-10-05T10:00:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.accepted").value(false))
                .andExpect(jsonPath("$.result.reason").value("이미 실행 중"))
                .andExpect(jsonPath("$.result.running.execId").value("20261005UNS001"));

        mvc.perform(get("/internal/collect/unstructured-incremental/status").param("execId", "20261005UNS001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("RUNNING"))
                .andExpect(jsonPath("$.result.triggerBy").value("admin01"));
        hold.countDown();
    }

    @Test
    @DisplayName("바로 실행 400 — to 형식 오류 · 계획 거절(미래 · 이미 수집)")
    void incrementalBadRequest() throws Exception {
        mvc.perform(post("/internal/collect/unstructured-incremental").param("to", "10/05 10:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("to ")));
        when(service.planRun(any(), any())).thenThrow(new IllegalArgumentException("수집할 구간이 없다"));
        mvc.perform(post("/internal/collect/unstructured-incremental").param("to", "2026-10-05 10:00:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("수집할 구간이 없다"));
        verify(service, never()).execute(any(), any());
    }

    @Test
    @DisplayName("긴급 재처리 — 202 {execId, status, originExecId, until} · stepTypeCd 를 비우면 SEND(자동) · TRIGGER_BY = {triggerBy}/reprocess:{원}")
    void reprocessAccepted() throws Exception {
        when(service.planReprocess("20261005UNS001", "UNSTRUCTURED", "SEND"))
                .thenReturn(plan(ResumeMode.FROM_SEND, "20261005UNS001", "ADMIN/reprocess:20261005UNS001"));
        ArgumentCaptor<UnstructuredBatchService.Plan> captor = ArgumentCaptor.forClass(UnstructuredBatchService.Plan.class);
        when(service.execute(captor.capture(), any())).thenAnswer(inv -> {
            BiConsumer<String, Boolean> sink = inv.getArgument(1);
            sink.accept("20261005UNS002", true);
            return "ok";
        });

        mvc.perform(post("/internal/collect/reprocess").param("triggerBy", "admin").param("originExecId", "20261005UNS001"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.execId").value("20261005UNS002"))
                .andExpect(jsonPath("$.originExecId").value("20261005UNS001"))
                .andExpect(jsonPath("$.until").value("2026-10-05T10:00"))
                .andExpect(jsonPath("$.resume").value("FROM_SEND"))
                .andExpect(jsonPath("$.stepTypeCd").value("SEND"));
        verify(service).planReprocess("20261005UNS001", "UNSTRUCTURED", "SEND");
        assertThat(captor.getValue().triggerBy()).isEqualTo("admin/reprocess:20261005UNS001");
        assertThat(captor.getValue().originExecId()).isEqualTo("20261005UNS001");
    }

    @Test
    @DisplayName("긴급 재처리 400 — originExecId 없음 · 원배치 없음 / 단계 지정은 그대로 넘긴다")
    void reprocessBadRequest() throws Exception {
        mvc.perform(post("/internal/collect/reprocess").param("triggerBy", "admin"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("originExecId 필수")));
        when(service.planReprocess("X", "UNSTRUCTURED", "ANALYZE")).thenThrow(new IllegalArgumentException("원배치를 찾을 수 없다"));
        mvc.perform(post("/internal/collect/reprocess").param("originExecId", "X").param("stepTypeCd", "ANALYZE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("원배치를 찾을 수 없다"));
    }

    @Test
    @DisplayName("긴급 재처리 409 — 다른 실행이 돌고 있으면 {message, execId}")
    void reprocessConflict() throws Exception {
        when(service.planRun(any(), any())).thenReturn(plan(ResumeMode.FULL, null, "ADMIN"));
        CountDownLatch hold = new CountDownLatch(1);
        when(service.execute(any(), any())).thenAnswer(inv -> {
            BiConsumer<String, Boolean> sink = inv.getArgument(1);
            sink.accept("20261005UNS003", true);
            hold.await(5, TimeUnit.SECONDS);
            return "ok";
        });
        mvc.perform(post("/internal/collect/unstructured-incremental")).andExpect(status().isOk());
        when(service.planReprocess(any(), any(), any())).thenReturn(plan(ResumeMode.FROM_SEND, "O", "ADMIN/reprocess:O"));

        mvc.perform(post("/internal/collect/reprocess").param("originExecId", "O"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.execId").value("20261005UNS003"));
        hold.countDown();
    }
}
