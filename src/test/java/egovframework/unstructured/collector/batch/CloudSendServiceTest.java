package egovframework.unstructured.collector.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import egovframework.unstructured.collector.common.config.VoiceProperties;
import egovframework.unstructured.collector.common.logging.LogCollectorClient;
import egovframework.unstructured.collector.common.transfer.TransmitHoldClient;
import egovframework.unstructured.collector.voice.batch.ResumeMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 클라우드 전송 — 누른 배치 + 이전 전송 실패 배치(오래된 순) · 전송 대기(WAIT)는 누른 배치만 · 데이터를 가진 쪽(커넥터 장부 · 수집기)이 보낸다.
 */
class CloudSendServiceTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private LogCollectorClient logc;
    private TransmitHoldClient holds;
    private UnstructuredBatchService batch;
    private CloudSendService service;

    @BeforeEach
    void setUp() {
        logc = mock(LogCollectorClient.class);
        holds = mock(TransmitHoldClient.class);
        batch = mock(UnstructuredBatchService.class);
        VoiceProperties vp = mock(VoiceProperties.class);
        VoiceProperties.Batch vb = mock(VoiceProperties.Batch.class);
        when(vp.batch()).thenReturn(vb);
        when(vb.testJobId()).thenReturn("TEST_BATCH");
        when(logc.isEnabled()).thenReturn(true);
        when(holds.isEnabled()).thenReturn(true);
        service = new CloudSendService(logc, holds, batch, vp);
    }

    /** 배치 상세 — T1 상태 · 작업 · 전송 단계 상태(null 이면 전송 단계 없음). */
    private static JsonNode detail(String execId, String job, String t1, String send) throws Exception {
        String steps = send == null ? "[{\"step_type_cd\":\"COLLECT\",\"step_seq\":1,\"step_sts_cd\":\"FAIL\"}]"
                : "[{\"step_type_cd\":\"COLLECT\",\"step_seq\":1,\"step_sts_cd\":\"SUCCESS\"},"
                + "{\"step_type_cd\":\"SEND\",\"step_seq\":4,\"step_sts_cd\":\"" + send + "\"}]";
        return OM.readTree("""
                {"batch":{"exec_id":"%s","job_id":"%s","job_nm":"음성","data_type_cd":"UNSTRUCTURED","exec_sts_cd":"%s",
                 "target_from_dtm":"2026-10-15T00:00:00","target_to_dtm":"2026-10-16T00:00:00"},"steps":%s}"""
                .formatted(execId, job, t1, steps));
    }

    private static LogCollectorClient.ChainBatch chain(String execId, String t1, String send) {
        return new LogCollectorClient.ChainBatch(execId, t1, "VOICE_ANALYSIS", "음성", null,
                LocalDateTime.of(2026, 10, 15, 0, 0), LocalDateTime.of(2026, 10, 16, 0, 0), send);
    }

    @Test
    @DisplayName("계획 — 누른 배치(전송 대기)는 마지막에, 이전 전송 실패 배치는 오래된 순 · 체인의 WAIT/RUNNING 은 넣지 않는다")
    void planChainExcludesWait() throws Exception {
        when(logc.batchDetail("20261016UNS003")).thenReturn(detail("20261016UNS003", "VOICE_ANALYSIS", "SUCCESS", "WAIT"));
        when(holds.holds()).thenReturn(Set.of("20261016UNS003"));
        when(logc.sendChain("20261016UNS003")).thenReturn(List.of(
                chain("20261014UNS001", "PARTIAL", "FAIL"),
                chain("20261015UNS001", "SUCCESS", "WAIT"),        // 옛 컬렉터가 줄 수 있는 WAIT — 계획에서 뺀다
                chain("20261015UNS002", "SUCCESS", "CANCELED"),
                chain("20261016UNS003", "SUCCESS", "WAIT")));      // 누른 배치 — 마지막에 따로
        CloudSendService.Plan p = service.plan(" 20261016UNS003 ", "UNSTRUCTURED", "admin01");
        assertThat(p.chain()).containsExactly("20261014UNS001", "20261015UNS002", "20261016UNS003");
        assertThat(p.sendStsCd()).isEqualTo("WAIT");
        assertThat(p.triggerBy()).isEqualTo("admin01/cloud-send:20261016UNS003");
        assertThat(service.plan("20261016UNS003", "UNSTRUCTURED", " ").requestedBy()).isEqualTo("admin");
    }

    @Test
    @DisplayName("계획 400 · 409 — 비정형 아님 · execId 없음 · 없음 · 이미지 · 전송 단계 없음 · 이미 보냄 · 실행/전송 중 · 장부 없음 · 체인 못 읽음")
    void planRejects() throws Exception {
        assertRejected(() -> service.plan("X", "STRUCTURED", null), UnstructuredBatchService.MSG_NOT_UNSTRUCTURED);
        assertRejected(() -> service.plan(" ", "UNSTRUCTURED", null), CloudSendService.MSG_NO_TARGET);
        assertRejected(() -> service.plan("NONE", "UNSTRUCTURED", null), UnstructuredBatchService.MSG_ORIGIN_NOT_FOUND);

        when(logc.batchDetail("20261016UNS002")).thenReturn(OM.readTree("""
                {"batch":{"exec_id":"20261016UNS002","job_id":"IMAGE_COLLECT","job_nm":"수용자 이미지 수집","data_type_cd":"UNSTRUCTURED",
                 "exec_sts_cd":"FAIL"},"steps":[]}"""));
        assertRejected(() -> service.plan("20261016UNS002", "UNSTRUCTURED", null), CloudSendService.MSG_IMAGE_NOT_SENT);

        when(logc.batchDetail("A")).thenReturn(detail("A", "VOICE_ANALYSIS", "FAIL", null));
        assertRejected(() -> service.plan("A", "UNSTRUCTURED", null), CloudSendService.MSG_NOTHING_TO_SEND);
        when(logc.batchDetail("B")).thenReturn(detail("B", "VOICE_ANALYSIS", "PARTIAL", "SUCCESS"));
        assertRejected(() -> service.plan("B", "UNSTRUCTURED", null), CloudSendService.MSG_SEND_DONE);
        when(logc.batchDetail("C")).thenReturn(detail("C", "VOICE_ANALYSIS", "RUNNING", "FAIL"));
        assertThatThrownBy(() -> service.plan("C", "UNSTRUCTURED", null)).isInstanceOf(UnstructuredJobRunner.AlreadyRunningException.class)
                .hasMessage(UnstructuredBatchService.MSG_BUSY);
        when(logc.batchDetail("D")).thenReturn(detail("D", "VOICE_ANALYSIS", "SUCCESS", "RUNNING"));
        assertThatThrownBy(() -> service.plan("D", "UNSTRUCTURED", null)).isInstanceOf(UnstructuredJobRunner.AlreadyRunningException.class);

        // 전송 대기인데 장부에 없다 · 장부를 못 읽는다 — 보내지 않는다
        when(logc.batchDetail("E")).thenReturn(detail("E", "VOICE_ANALYSIS", "SUCCESS", "WAIT"));
        when(holds.holds()).thenReturn(Set.of());
        assertRejected(() -> service.plan("E", "UNSTRUCTURED", null), CloudSendService.MSG_HOLD_NOT_FOUND);
        when(holds.holds()).thenReturn(null);
        assertRejected(() -> service.plan("E", "UNSTRUCTURED", null), CloudSendService.MSG_SEND_UNAVAILABLE);

        // 전송 실패인데 장부에도 없고 배치가 성공 — 다시 열 수 없다
        when(holds.holds()).thenReturn(Set.of());
        when(logc.batchDetail("F")).thenReturn(detail("F", "VOICE_ANALYSIS", "SUCCESS", "FAIL"));
        assertRejected(() -> service.plan("F", "UNSTRUCTURED", null), CloudSendService.MSG_CANNOT_RESEND);

        // 전송 체인을 못 읽는다
        when(logc.batchDetail("G")).thenReturn(detail("G", "VOICE_ANALYSIS", "PARTIAL", "FAIL"));
        when(logc.sendChain("G")).thenReturn(null);
        assertRejected(() -> service.plan("G", "UNSTRUCTURED", null), CloudSendService.MSG_SEND_UNAVAILABLE);
    }

    @Test
    @DisplayName("실행 — 장부에 있으면 커넥터 전송 트리거 · 없으면 같은 EXEC_ID 로 전송부터 다시 · 그새 WAIT/RUNNING 이 된 이전 배치는 건너뜀")
    void executeRoutesAndSkips() throws Exception {
        CloudSendService.Plan p = new CloudSendService.Plan("T", "WAIT", List.of("P1", "P2", "P3", "P4"), "admin01", "admin01/cloud-send:T");
        when(holds.holds()).thenReturn(Set.of("P2", "T"));
        when(logc.batchDetail("P1")).thenReturn(detail("P1", "VOICE_ANALYSIS", "PARTIAL", "FAIL"));     // 수집기가 다시 보낸다
        when(logc.batchDetail("P2")).thenReturn(detail("P2", "VOICE_ANALYSIS", "SUCCESS", "FAIL"));     // 커넥터 장부 재시도
        when(logc.batchDetail("P3")).thenReturn(detail("P3", "VOICE_ANALYSIS", "PARTIAL", "WAIT"));     // 그새 보류됨 — 자동으로 안 보낸다
        when(logc.batchDetail("P4")).thenReturn(detail("P4", "TEST_BATCH", "FAIL", "RUNNING"));         // 전송 중 — 건너뜀
        when(logc.batchDetail("T")).thenReturn(detail("T", "VOICE_ANALYSIS", "SUCCESS", "WAIT"));       // 누른 배치 — 관리자 승인
        when(holds.release(anyString(), anyString())).thenReturn(
                new TransmitHoldClient.Release(200, true, "SUCCESS", "학습셋 drop 마감", null));
        when(batch.reopenAndRunVoice(eq("P1"), any(), any(), anyString(), anyBoolean(), any(), isNull())).thenReturn("P1 음성 FROM_SEND 성공1");

        List<String> published = new ArrayList<>();
        String sum = service.execute(p, (id, c) -> published.add(id));

        assertThat(published).containsExactly("T");
        assertThat(sum).startsWith("클라우드 전송 5건").contains("P1 음성 FROM_SEND").contains("P2 커넥터 전송 성공")
                .contains("P3 건너뜀(전송 대기").contains("P4 건너뜀(실행 · 전송 중)").contains("T 커넥터 전송 성공");
        verify(batch).reopenAndRunVoice("P1", LocalDateTime.of(2026, 10, 15, 0, 0), LocalDateTime.of(2026, 10, 16, 0, 0),
                "admin01/cloud-send:T", false, ResumeMode.FROM_SEND, null);
        verify(holds).release("P2", "admin01");
        verify(holds).release("T", "admin01");
        verify(holds, never()).release(eq("P3"), anyString());
        verify(batch, never()).reopenAndRunVoice(eq("P3"), any(), any(), anyString(), anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("실행 — 커넥터 장부를 못 읽으면 수집기가 다시 보내지 않는다(커넥터가 든 건을 두 번 보낼 수 있다)")
    void executeWithoutLedgerDoesNotResend() throws Exception {
        CloudSendService.Plan p = new CloudSendService.Plan("T", "FAIL", List.of(), "admin01", "admin01/cloud-send:T");
        when(holds.holds()).thenReturn(null);
        when(logc.batchDetail("T")).thenReturn(detail("T", "VOICE_ANALYSIS", "PARTIAL", "FAIL"));
        assertThat(service.execute(p, null)).contains("T 건너뜀(커넥터 보류 장부 확인 실패)");
        verify(batch, never()).reopenAndRunVoice(anyString(), any(), any(), anyString(), anyBoolean(), any(), any());

        // MOCK(장부 없음) — 수집기가 다시 보낸다 · 시험 배치는 시험으로
        when(holds.isEnabled()).thenReturn(false);
        when(logc.batchDetail("T")).thenReturn(detail("T", "TEST_BATCH", "PARTIAL", "FAIL"));
        when(batch.reopenAndRunVoice(eq("T"), any(), any(), anyString(), anyBoolean(), any(), isNull())).thenReturn("T 음성 FROM_SEND");
        assertThat(service.execute(p, null)).contains("T 음성 FROM_SEND");
        verify(batch).reopenAndRunVoice(eq("T"), any(), any(), eq("admin01/cloud-send:T"), eq(true), eq(ResumeMode.FROM_SEND), isNull());
    }

    @Test
    @DisplayName("화면 안내 — 400 문구는 공백 포함 60자 이내 · 영문 식별자 · 실행 ID 없음")
    void messagesFitTwoLines() {
        for (String m : List.of(CloudSendService.MSG_NO_TARGET, CloudSendService.MSG_NOT_UNSTRUCTURED_BATCH,
                CloudSendService.MSG_IMAGE_NOT_SENT, CloudSendService.MSG_NOTHING_TO_SEND, CloudSendService.MSG_SEND_DONE,
                CloudSendService.MSG_HOLD_NOT_FOUND, CloudSendService.MSG_CANNOT_RESEND, CloudSendService.MSG_SEND_UNAVAILABLE,
                UnstructuredBatchService.MSG_ON_HOLD, UnstructuredBatchService.MSG_BUSY)) {
            assertThat(m.length()).as("%s", m).isLessThanOrEqualTo(UserFacingRejection.MAX_MESSAGE_LEN);
            assertThat(m).as("내부 변수 · 실행 ID 가 섞이지 않는다").doesNotContainPattern("[A-Za-z]");
        }
    }

    private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String message) {
        assertThatThrownBy(call).isInstanceOf(UserFacingRejection.class).hasMessage(message);
    }
}
