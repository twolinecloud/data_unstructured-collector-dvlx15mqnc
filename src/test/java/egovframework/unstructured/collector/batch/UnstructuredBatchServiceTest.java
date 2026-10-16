package egovframework.unstructured.collector.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import egovframework.unstructured.collector.batch.schedule.BatchSchedule;
import egovframework.unstructured.collector.common.config.VoiceProperties;
import egovframework.unstructured.collector.common.logging.LogCollectorClient;
import egovframework.unstructured.collector.common.transfer.TransmitHoldClient;
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
    private TransmitHoldClient holds;
    private VoiceProperties.Batch batch;

    @BeforeEach
    void setUp() {
        voice = mock(VoiceCollectService.class);
        image = mock(ImageCollectService.class);
        logc = mock(LogCollectorClient.class);
        holds = mock(TransmitHoldClient.class);
        batch = mock(VoiceProperties.Batch.class);
        when(batch.periodicLagMin()).thenReturn(20);
        when(batch.jobId()).thenReturn("VOICE_ANALYSIS");
        when(batch.testJobId()).thenReturn("TEST_BATCH");
        when(logc.isEnabled()).thenReturn(true);
        // 다시 연 배치는 자기 T4 건만 처리한다 — 기본은 T4 비어 있음(구간 전체)
        try {
            when(logc.fileProcs(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(OM.readTree("{\"rows\":[]}"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private UnstructuredBatchService service(boolean includeImage) {
        VoiceProperties vp = mock(VoiceProperties.class);
        when(vp.batch()).thenReturn(batch);
        UnstructuredBatchProperties props = new UnstructuredBatchProperties(
                new UnstructuredBatchProperties.Admin("", Duration.ofSeconds(2), Duration.ofSeconds(3)),
                new UnstructuredBatchProperties.Schedule(300000, "Asia/Seoul"),
                new UnstructuredBatchProperties.Batch(includeImage, 1500));
        UnstructuredBatchService s = new UnstructuredBatchService(voice, image, vp, logc, props, holds);
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
        // 비식별 실패 — 보존된 전사로 비식별부터 다시(STT 는 다시 돌리지 않는다)
        assertThat(UnstructuredBatchService.resumeOf("DEIDENT")).isEqualTo(ResumeMode.FROM_SEND);
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
    @DisplayName("주기 창 자정 직후 — 거슬러 보는 폭 = 주기 + 10분(최소 20분): 10분 20 · 30분 40 · 1시간 70 → 앞 회차 창과 맞물린다")
    void periodicLookbackFollowsPeriod() {
        UnstructuredBatchService s = service(false);
        LocalDateTime justAfter = LocalDateTime.of(2026, 10, 2, 0, 1);
        assertThat(s.planScheduled(new BatchSchedule("UNSTRUCTURED", "Y", "INTERVAL_BASED", "00:10"), justAfter).window().from())
                .isEqualTo(LocalDateTime.of(2026, 10, 1, 23, 41));
        // 30분 주기 — 23:30 회차 다음(끝난 시각 + 30분 ≈ 00:01)이 23:21 부터 봐 23:30 ~ 24:00 를 덮는다(예전 20분이면 23:41 부터 → 11분 빠짐)
        assertThat(s.planScheduled(new BatchSchedule("UNSTRUCTURED", "Y", "INTERVAL_BASED", "00:30"), justAfter).window().from())
                .isEqualTo(LocalDateTime.of(2026, 10, 1, 23, 21));
        assertThat(s.planScheduled(new BatchSchedule("UNSTRUCTURED", "Y", "INTERVAL_BASED", "01:00:00"), justAfter).window().from())
                .isEqualTo(LocalDateTime.of(2026, 10, 1, 22, 51));
        // 낮에는 그대로 오늘 00:00 부터
        assertThat(s.planScheduled(new BatchSchedule("UNSTRUCTURED", "Y", "INTERVAL_BASED", "01:00"), NOW).window().from())
                .isEqualTo(LocalDateTime.of(2026, 10, 2, 0, 0));
    }

    @Test
    @DisplayName("바로 실행 — 워터마크 ~ targetToDtm · 미래/이미 수집한 구간은 400 · 비우면 지금-lag")
    void runWindow() {
        UnstructuredBatchService s = service(false);
        when(logc.watermark("UNSTRUCTURED", "VOICE_ANALYSIS"))
                .thenReturn(new LogCollectorClient.Watermark(LocalDateTime.of(2026, 10, 2, 0, 0), "20261002UNS001"));
        var p = s.planRun(LocalDateTime.of(2026, 10, 2, 10, 0), NOW);
        assertThat(p.window().from()).isEqualTo(LocalDateTime.of(2026, 10, 2, 0, 0));
        assertThat(p.window().to()).isEqualTo(LocalDateTime.of(2026, 10, 2, 10, 0));
        assertThat(p.window().execTypeCd()).isEqualTo("MANUAL");
        assertThat(s.planRun(null, NOW).window().to()).isEqualTo(NOW.minusMinutes(20));

        assertRejected(() -> s.planRun(NOW.plusMinutes(5), NOW), UnstructuredBatchService.MSG_TO_FUTURE, "targetToDtm 이 미래다");

        // 워터마크 충돌 거절문은 <b>화면에 그대로 뜬다</b>(admin-api 가 400 과 함께 올린다).
        //   사용자가 정한 문구 그대로 — 사용자가 바로 할 수 있는 말만 남긴다.
        //   targetToDtm · 배치 ID 같은 내부 값은 메시지에서 빼고 detail(서버 로그)로 돌렸다(2026-10-15).
        //   ① 워터마크가 더 나중 ② 워터마크와 같음(하루 두 번째 바로 실행) — 화면 문구는 같고, 로그에서 갈린다.
        assertRejected(() -> s.planRun(LocalDateTime.of(2026, 10, 1, 23, 0), NOW),
                "최근 수집 시점(2026-10-02 00:00) 이후의 시간을 선택해 주세요. 과거 구간은 '긴급 재처리'를 이용 바랍니다.",
                "워터마크 2026-10-02T00:00(20261002UNS001) · targetToDtm 2026-10-01T23:00 (워터마크가 더 나중)");
        assertRejected(() -> s.planRun(LocalDateTime.of(2026, 10, 2, 0, 0), NOW),
                "최근 수집 시점(2026-10-02 00:00) 이후의 시간을 선택해 주세요. 과거 구간은 '긴급 재처리'를 이용 바랍니다.",
                "워터마크 2026-10-02T00:00(20261002UNS001) · targetToDtm 2026-10-02T00:00 (같음)");

        when(logc.watermark(anyString(), anyString())).thenReturn(null);   // 첫 실행 — 최근 30일
        assertThat(s.planRun(NOW, NOW).window().from()).isEqualTo(NOW.minusDays(30));
    }

    @Test
    @DisplayName("재처리 — 원배치(T1)의 수집 구간 그대로 · 원 execId 의 보존물 · 시험 배치면 시험으로")
    void reprocessUsesOriginWindow() throws Exception {
        UnstructuredBatchService s = service(true);
        when(logc.pendingChain(anyString())).thenAnswer(inv -> List.of(chainBatch(inv.getArgument(0))));
        when(logc.batchDetail("20260915UNS003")).thenReturn(detail("20260915UNS003", "VOICE_ANALYSIS", "UNSTRUCTURED",
                "2026-09-14T00:00:00", "2026-09-15T00:00:00"));
        var p = s.planReprocess("20260915UNS003", "UNSTRUCTURED", "SEND");
        assertThat(p.window().from()).isEqualTo(LocalDateTime.of(2026, 9, 14, 0, 0));
        assertThat(p.window().to()).isEqualTo(LocalDateTime.of(2026, 9, 15, 0, 0));
        assertThat(p.resume()).isEqualTo(ResumeMode.FROM_SEND);
        assertThat(p.originExecId()).isEqualTo("20260915UNS003");
        assertThat(p.testRun()).isFalse();
        assertThat(p.triggerBy()).isEqualTo("ADMIN/reprocess:20260915UNS003");
        assertThat(p.includeImage()).as("재처리는 음성만").isFalse();

        when(logc.batchDetail("20261001TST002")).thenReturn(detail("20261001TST002", "TEST_BATCH", "UNSTRUCTURED",
                "2026-09-30T00:00:00", "2026-10-01T00:00:00"));
        assertThat(s.planReprocess("20261001TST002", "UNSTRUCTURED", null).testRun()).isTrue();
    }

    @Test
    @DisplayName("재처리 400 — 비정형 아님 · execId 없음 · 원배치 없음 · 정형 배치 · 구간 없음")
    void reprocessRejects() throws Exception {
        UnstructuredBatchService s = service(false);
        assertRejected(() -> s.planReprocess("X", "STRUCTURED", "SEND"), UnstructuredBatchService.MSG_NOT_UNSTRUCTURED,
                "dataTypeCd=UNSTRUCTURED 만 받는다: STRUCTURED");
        assertRejected(() -> s.planReprocess(" ", "UNSTRUCTURED", "SEND"), UnstructuredBatchService.MSG_NO_ORIGIN, "execId 필수");
        assertRejected(() -> s.planReprocess("NONE", "UNSTRUCTURED", "SEND"), UnstructuredBatchService.MSG_ORIGIN_NOT_FOUND,
                "원배치를 찾을 수 없다: execId=NONE");
        assertRejected(() -> s.planReprocess("NONE", "UNSTRUCTURED", "BOGUS"), UnstructuredBatchService.MSG_BAD_STEP,
                "비정형에 없는 단계(C05): BOGUS");
        when(logc.batchDetail("20261002STR001")).thenReturn(detail("20261002STR001", "STRUCTURED_BATCH", "STRUCTURED",
                "2026-10-01T00:00:00", "2026-10-02T00:00:00"));
        assertRejected(() -> s.planReprocess("20261002STR001", "UNSTRUCTURED", null), UnstructuredBatchService.MSG_NOT_UNSTRUCTURED_BATCH,
                "execId=20261002STR001 data_type_cd=STRUCTURED");
        when(logc.batchDetail("20261002UNS009")).thenReturn(detail("20261002UNS009", "VOICE_ANALYSIS", "UNSTRUCTURED", null, null));
        assertRejected(() -> s.planReprocess("20261002UNS009", "UNSTRUCTURED", null), UnstructuredBatchService.MSG_ORIGIN_NO_WINDOW,
                "원배치 수집 구간이 없다: execId=20261002UNS009");
    }

    @Test
    @DisplayName("재처리는 원배치 reopen 만 — 실패/부분성공/취소가 아니거나 재처리 체인을 못 읽으면 거절(새 실행 ID 로 돌지 않는다)")
    void reprocessOnlyReopens() throws Exception {
        UnstructuredBatchService s = service(false);
        when(logc.batchDetail("20261016UNS001")).thenReturn(OM.readTree("""
                {"batch":{"exec_id":"20261016UNS001","job_id":"VOICE_ANALYSIS","data_type_cd":"UNSTRUCTURED","exec_sts_cd":"SUCCESS",
                 "target_from_dtm":"2026-10-15T00:00:00","target_to_dtm":"2026-10-16T00:00:00"},"steps":[]}"""));
        assertRejected(() -> s.planReprocess("20261016UNS001", "UNSTRUCTURED", null), UnstructuredBatchService.MSG_ALREADY_DONE,
                "exec_sts_cd=SUCCESS");
        verify(logc, never()).pendingChain(anyString());

        when(logc.batchDetail("20261016UNS003")).thenReturn(detail("20261016UNS003", "VOICE_ANALYSIS", "UNSTRUCTURED",
                "2026-10-15T00:00:00", "2026-10-16T00:00:00"));
        when(logc.pendingChain("20261016UNS003")).thenReturn(null);
        assertRejected(() -> s.planReprocess("20261016UNS003", "UNSTRUCTURED", null), UnstructuredBatchService.MSG_CHAIN_UNAVAILABLE,
                "failed-chain)을 읽지 못했다");
        when(logc.pendingChain("20261016UNS003")).thenReturn(List.of());
        assertRejected(() -> s.planReprocess("20261016UNS003", "UNSTRUCTURED", null), UnstructuredBatchService.MSG_ALREADY_DONE,
                "재처리 체인이 비었다");
        when(logc.pendingChain("20261016UNS003")).thenReturn(List.of(chainBatch("20261016UNS003")));
        assertThat(s.planReprocess("20261016UNS003", "UNSTRUCTURED", null).originExecId()).isEqualTo("20261016UNS003");
    }

    @Test
    @DisplayName("재처리 — 전송 대기(WAIT) 배치도 받는다(PL 협의 · 화면 팝업 승인) · 실행 중 · 전송 중 배치는 409")
    void reprocessAcceptsWaitRejectsBusy() throws Exception {
        UnstructuredBatchService s = service(false);
        when(logc.pendingChain(anyString())).thenAnswer(inv -> List.of(chainBatch(inv.getArgument(0))));
        when(logc.batchDetail("H")).thenReturn(detailWithSend("H", "PARTIAL", "WAIT"));
        assertThat(s.planReprocess("H", "UNSTRUCTURED", "COLLECT").originExecId()).isEqualTo("H");
        when(logc.batchDetail("R")).thenReturn(detailWithSend("R", "RUNNING", null));
        assertThatThrownBy(() -> s.planReprocess("R", "UNSTRUCTURED", null))
                .isInstanceOf(UnstructuredJobRunner.AlreadyRunningException.class).hasMessage(UnstructuredBatchService.MSG_BUSY);
        when(logc.batchDetail("S")).thenReturn(detailWithSend("S", "FAIL", "RUNNING"));
        assertThatThrownBy(() -> s.planReprocess("S", "UNSTRUCTURED", "SEND"))
                .isInstanceOf(UnstructuredJobRunner.AlreadyRunningException.class);
    }

    @Test
    @DisplayName("재처리 체인 — 하위 대기(WAIT) 포함 오래된 순: 커넥터 보류분 먼저 보내고 → 실패 배치면 다시 돌기 · 실행 중 · 상태 못 읽음 · 장부에 없는 대기는 건너뜀")
    void chainIncludesWaitReleaseFirst() throws Exception {
        UnstructuredBatchService s = service(false);
        when(logc.pendingChain("T")).thenReturn(List.of(chainBatch("P1"), chainBatch("P2"), chainBatch("P3"), chainBatch("P4"),
                chainBatch("P5"), chainBatch("T")));
        when(logc.batchDetail("P1")).thenReturn(detailWithSend("P1", "PARTIAL", "WAIT"));   // 보류분 전송 → 실패 건 다시 돌기
        when(logc.batchDetail("P2")).thenReturn(detailWithSend("P2", "SUCCESS", "WAIT"));   // 보류분 전송만
        when(logc.batchDetail("P3")).thenReturn(detailWithSend("P3", "RUNNING", "FAIL"));   // 실행 중 — 건너뜀
        // P4 — 상세를 못 읽음(null) → 손대지 않는다
        when(logc.batchDetail("P5")).thenReturn(detailWithSend("P5", "PARTIAL", "WAIT"));   // 장부에 없는 대기 — 보내지 않는다
        when(logc.batchDetail("T")).thenReturn(detailWithSend("T", "FAIL", null));
        when(holds.isEnabled()).thenReturn(true);
        when(holds.holds()).thenReturn(java.util.Set.of("P1", "P2"));
        when(holds.release(anyString(), anyString())).thenReturn(new TransmitHoldClient.Release(200, true, "SUCCESS", "학습셋 drop 마감", null));
        when(logc.reopenBatch(anyString(), anyString(), any(), any())).thenReturn(true);
        when(voice.defaultWorkers()).thenReturn(Workers.sequential());
        VoiceBatchResult vr = new VoiceBatchResult("T", true, "MANUAL", 2, 2, 0, 0, 10L, Map.of(), List.of(), List.of(), false);
        when(voice.run(any(), isNull(), anyString(), anyBoolean(), any(), anyString(), any(Workers.class), isNull(), any()))
                .thenReturn(vr);
        var plan = new UnstructuredBatchService.Plan(
                egovframework.unstructured.collector.common.model.BatchWindow.manual(LocalDateTime.of(2026, 10, 15, 0, 0),
                        LocalDateTime.of(2026, 10, 16, 0, 0)), ResumeMode.FULL, "T", false, "admin01/reprocess:T", false);

        String sum = s.execute(plan, (id, c) -> { });

        assertThat(sum).contains("P1 커넥터 전송 성공(HTTP 200 · SUCCESS · 학습셋 drop 마감) → 음성 FROM_SEND")
                .contains("P2 커넥터 전송 성공").contains("P3 건너뜀(실행 · 전송 중)").contains("P4 건너뜀(상태 확인 실패)")
                .contains("P5 건너뜀(전송 대기인데 커넥터 보류 장부에 없음").contains("T 음성 FULL");
        InOrder o = inOrder(holds, logc);
        o.verify(holds).release("P1", "admin01");                                   // 보류분 먼저
        o.verify(logc).reopenBatch(eq("P1"), eq("admin01/reprocess:T"), any(), any());   // 그다음 다시 돌기
        o.verify(holds).release("P2", "admin01");
        o.verify(logc).reopenBatch(eq("T"), eq("admin01/reprocess:T"), any(), any());
        verify(logc, never()).reopenBatch(eq("P2"), anyString(), any(), any());       // 성공 배치는 다시 열지 않는다
        verify(logc, never()).reopenBatch(eq("P3"), anyString(), any(), any());
        verify(logc, never()).reopenBatch(eq("P5"), anyString(), any(), any());       // 보류분 행방을 모르면 T2 를 지우지 않는다
        verify(holds, never()).release(eq("P5"), anyString());
    }

    @Test
    @DisplayName("보류분 전송이 실패하면 그 배치는 다시 돌지 않는다(다시 열면 T2 가 지워져 보류 흔적이 사라진다)")
    void releaseFailureStopsThatBatch() throws Exception {
        UnstructuredBatchService s = service(false);
        when(logc.batchDetail("P1")).thenReturn(detailWithSend("P1", "PARTIAL", "WAIT"));
        when(holds.isEnabled()).thenReturn(true);
        when(holds.release("P1", "admin01")).thenReturn(new TransmitHoldClient.Release(409, false, null, "지금 전송 중", null));

        String line = s.processPending("P1", UnstructuredBatchService.PendingMode.REPROCESS, ResumeMode.FROM_SEND, null, null,
                java.util.Set.of("P1"), "admin01/reprocess:T", "admin01");

        assertThat(line).startsWith("P1 커넥터 전송 실패(HTTP 409").endsWith("다시 돌지 않음");
        verify(logc, never()).reopenBatch(anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("클라우드 전송 모드 — 장부에 없는 전송 실패만 같은 EXEC_ID 로 전송부터 · 장부를 모르면 · 다시 열 수 없으면 · 보낼 것 없으면 건너뜀 · 시험 배치는 시험으로")
    void cloudSendModeResendsOnlyMissedSends() throws Exception {
        UnstructuredBatchService s = service(false);
        when(holds.isEnabled()).thenReturn(true);
        when(logc.reopenBatch(anyString(), anyString(), any(), any())).thenReturn(true);
        when(voice.defaultWorkers()).thenReturn(Workers.sequential());
        VoiceBatchResult vr = new VoiceBatchResult("X", true, "MANUAL", 1, 1, 0, 0, 10L, Map.of(), List.of(), List.of(), false);
        when(voice.run(any(), isNull(), anyString(), anyBoolean(), any(), anyString(), any(Workers.class), isNull(), any()))
                .thenReturn(vr);
        var mode = UnstructuredBatchService.PendingMode.CLOUD_SEND;
        java.util.Set<String> empty = java.util.Set.of();

        when(logc.batchDetail("A")).thenReturn(detailWithSend("A", "TEST_BATCH", "PARTIAL", "FAIL"));
        assertThat(s.processPending("A", mode, ResumeMode.FROM_SEND, null, null, empty, "u/cloud-send:A", "u"))
                .isEqualTo("A 음성 FROM_SEND 대상1 성공1 실패0 건너뜀0 · 원래 실패 0");
        verify(voice).run(any(), isNull(), eq("u/cloud-send:A"), eq(true), eq(ResumeMode.FROM_SEND), eq("A"), any(Workers.class),
                isNull(), any());
        assertThat(s.processPending("A", mode, ResumeMode.FROM_SEND, null, null, null, "u/cloud-send:A", "u"))
                .isEqualTo("A 커넥터 보류 장부 확인 실패 — 다시 보내지 않음");

        when(logc.batchDetail("B")).thenReturn(detailWithSend("B", "SUCCESS", "FAIL"));
        assertThat(s.processPending("B", mode, ResumeMode.FROM_SEND, null, null, empty, "t", "u"))
                .isEqualTo("B 다시 보낼 경로 없음(배치 SUCCESS · 전송 FAIL)");
        when(logc.batchDetail("C")).thenReturn(detailWithSend("C", "PARTIAL", "SUCCESS"));
        assertThat(s.processPending("C", mode, ResumeMode.FROM_SEND, null, null, empty, "t", "u"))
                .isEqualTo("C 건너뜀(처리할 것 없음 · 배치 PARTIAL · 전송 SUCCESS)");
        assertThat(UnstructuredBatchService.requesterOf("admin01/reprocess:T")).isEqualTo("admin01");
        assertThat(UnstructuredBatchService.requesterOf("ADMIN")).isEqualTo("ADMIN");
        assertThat(UnstructuredBatchService.requesterOf(null)).isEqualTo("admin");
    }

    @Test
    @DisplayName("전송 단계 상태 — SEND 중 마지막 순번 · 없으면 null")
    void sendStepStatusPicksLastSend() throws Exception {
        assertThat(UnstructuredBatchService.sendStepStatus(OM.readTree("""
                {"steps":[{"step_type_cd":"SEND","step_seq":4,"step_sts_cd":"FAIL"},{"step_type_cd":"COLLECT","step_seq":1,"step_sts_cd":"SUCCESS"},
                 {"step_type_cd":"SEND","step_seq":5,"step_sts_cd":"WAIT"}]}"""))).isEqualTo("WAIT");
        assertThat(UnstructuredBatchService.sendStepStatus(OM.readTree("{\"steps\":[]}"))).isNull();
        assertThat(UnstructuredBatchService.sendStepStatus(null)).isNull();
    }

    private static JsonNode detailWithSend(String execId, String t1, String send) throws Exception {
        return detailWithSend(execId, "VOICE_ANALYSIS", t1, send);
    }

    private static JsonNode detailWithSend(String execId, String job, String t1, String send) throws Exception {
        String steps = send == null ? "[]" : "[{\"step_type_cd\":\"SEND\",\"step_seq\":4,\"step_sts_cd\":\"" + send + "\"}]";
        return OM.readTree("""
                {"batch":{"exec_id":"%s","job_id":"%s","data_type_cd":"UNSTRUCTURED","exec_sts_cd":"%s",
                 "target_from_dtm":"2026-10-15T00:00:00","target_to_dtm":"2026-10-16T00:00:00"},"steps":%s}""".formatted(execId, job, t1, steps));
    }

    private static LogCollectorClient.ChainBatch chainBatch(String execId) {
        return new LogCollectorClient.ChainBatch(execId, "FAIL", "VOICE_ANALYSIS", null, null,
                LocalDateTime.of(2026, 10, 15, 0, 0), LocalDateTime.of(2026, 10, 16, 0, 0));
    }

    @Test
    @DisplayName("화면 안내 — 400 문구는 공백 포함 60자(토스트 두 줄) 이내(워터마크 안내만 사용자가 정한 원문 그대로) · 영문 식별자 · 실행 ID 없음")
    void userMessagesFitTwoLines() {
        List<String> all = List.of(UnstructuredBatchService.MSG_TO_FUTURE, UnstructuredBatchService.MSG_TO_FORMAT,
                UnstructuredBatchService.MSG_NO_ORIGIN, UnstructuredBatchService.MSG_ORIGIN_NOT_FOUND,
                UnstructuredBatchService.MSG_NOT_UNSTRUCTURED_BATCH, UnstructuredBatchService.MSG_ORIGIN_NO_WINDOW,
                UnstructuredBatchService.MSG_BAD_STEP, UnstructuredBatchService.MSG_NOT_UNSTRUCTURED,
                UnstructuredBatchService.MSG_BAD_BODY, UnstructuredBatchService.MSG_ALREADY_DONE,
                UnstructuredBatchService.MSG_CHAIN_UNAVAILABLE, UserFacingRejection.GENERIC);
        for (String m : all) {
            assertThat(m.length()).as("%s", m).isLessThanOrEqualTo(UserFacingRejection.MAX_MESSAGE_LEN);
            assertThat(m).as("내부 변수 · 실행 ID 가 섞이지 않는다").doesNotContainPattern("[A-Za-z]");
        }
        // 워터마크 안내 — 사용자 지정 문구 그대로(69자). 내부 값이 없다는 것만 같다
        assertThat(UnstructuredBatchService.watermarkMessage(LocalDateTime.of(2026, 10, 15, 11, 0)))
                .isEqualTo("최근 수집 시점(2026-10-15 11:00) 이후의 시간을 선택해 주세요. 과거 구간은 '긴급 재처리'를 이용 바랍니다.")
                .hasSize(69)
                .doesNotContainPattern("[A-Za-z]");
    }

    /** 400 거절 — 화면 문구는 정확히 그 안내, 자세한 사유(내부 값)는 detail 에. */
    private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String message, String detailPart) {
        assertThatThrownBy(call).isInstanceOf(UserFacingRejection.class).hasMessage(message)
                .satisfies(e -> assertThat(((UserFacingRejection) e).detail()).contains(detailPart));
    }

    @Test
    @DisplayName("실행 — 음성만(기본) · include-image 면 음성 → 이미지 순차 · 이미지 실패가 음성 결과를 덮지 않음")
    void executeOrder() {
        VoiceBatchResult vr = new VoiceBatchResult("20261002UNS001", true, "DAILY", 3, 3, 0, 0, 100L, Map.of(), List.of(),
                List.of(), false);
        when(voice.defaultWorkers()).thenReturn(Workers.sequential());
        when(voice.run(any(), isNull(), anyString(), anyBoolean(), any(), any(), any(Workers.class), any())).thenReturn(vr);
        var plan = new UnstructuredBatchService.Plan(
                egovframework.unstructured.collector.common.model.BatchWindow.daily(NOW), ResumeMode.FULL, null, false,
                "SCHEDULER", false);

        String only = service(false).execute(plan, (id, c) -> { });
        assertThat(only).startsWith("음성 execId=20261002UNS001");
        verify(image, never()).run(any());

        var withImage = new UnstructuredBatchService.Plan(plan.window(), ResumeMode.FULL, null, false, "SCHEDULER", true);
        when(image.run(any())).thenThrow(new IllegalStateException("이미지 수집이 이미 돌고 있습니다"));
        String both = service(true).execute(withImage, (id, c) -> { });
        assertThat(both).contains("음성 execId=20261002UNS001").contains("이미지 실패");
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
