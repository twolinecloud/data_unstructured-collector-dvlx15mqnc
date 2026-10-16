package egovframework.unstructured.collector.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import egovframework.unstructured.collector.common.logging.LogCollectorClient;
import egovframework.unstructured.collector.common.transfer.TransmitHoldClient;
import egovframework.unstructured.collector.voice.batch.ResumeMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 클라우드 전송 — 누른 배치 + 이전 전송 미완료 배치(대기 WAIT 포함 · PL 협의 2026-10-16) 오래된 순 · 누른 배치는 마지막.
 * 배치 한 건 처리(장부 → 커넥터 / 아니면 수집기 재전송)는 {@code UnstructuredBatchService.processPending} 이 하고, 그 검증은
 * {@code UnstructuredBatchServiceTest} 에 있다.
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
        when(logc.isEnabled()).thenReturn(true);
        when(holds.isEnabled()).thenReturn(true);
        service = new CloudSendService(logc, holds, batch);
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
    @DisplayName("계획 — 이전 전송 미완료 배치를 오래된 순으로(대기 WAIT 포함) · 지금 전송 중(RUNNING)만 빼고 · 누른 배치는 마지막")
    void planChainIncludesWait() throws Exception {
        when(logc.batchDetail("20261016UNS003")).thenReturn(detail("20261016UNS003", "VOICE_ANALYSIS", "SUCCESS", "WAIT"));
        when(holds.holds()).thenReturn(Set.of("20261016UNS003"));
        when(logc.sendChain("20261016UNS003")).thenReturn(List.of(
                chain("20261014UNS001", "PARTIAL", "FAIL"),
                chain("20261015UNS001", "SUCCESS", "WAIT"),        // 하위 대기 건 — 화면 팝업 승인을 받고 함께 보낸다
                chain("20261015UNS002", "SUCCESS", "RUNNING"),     // 지금 전송 중 — 중복 방지로 뺀다
                chain("20261015UNS003", "SUCCESS", "CANCELED"),
                chain("20261016UNS003", "SUCCESS", "WAIT")));      // 누른 배치 — 마지막에 따로
        CloudSendService.Plan p = service.plan(" 20261016UNS003 ", "UNSTRUCTURED", "admin01");
        assertThat(p.chain()).containsExactly("20261014UNS001", "20261015UNS001", "20261015UNS003", "20261016UNS003");
        assertThat(p.sendStsCd()).isEqualTo("WAIT");
        assertThat(p.triggerBy()).isEqualTo("admin01/cloud-send:20261016UNS003");
        assertThat(p.detail()).containsEntry("originExecId", "20261016UNS003").containsKey("chain");
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
    @DisplayName("실행 — 체인 순서대로 배치마다 processPending(보내기만 · 전송부터) · 장부는 한 번 읽어 같이 쓴다 · 응답 execId 는 누른 배치")
    void executeProcessesChainInOrder() {
        CloudSendService.Plan p = new CloudSendService.Plan("T", "WAIT", List.of("P1", "P2"), "admin01", "admin01/cloud-send:T");
        Set<String> held = Set.of("P2", "T");
        when(holds.holds()).thenReturn(held);
        when(batch.processPending(anyString(), any(), any(), isNull(), isNull(), any(), anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0) + " 처리");

        List<String> published = new ArrayList<>();
        String sum = service.execute(p, (id, c) -> published.add(id));

        assertThat(published).containsExactly("T");
        assertThat(sum).isEqualTo("클라우드 전송 3건 — P1 처리 / P2 처리 / T 처리");
        InOrder o = inOrder(batch);
        for (String id : List.of("P1", "P2", "T")) {
            o.verify(batch).processPending(eq(id), eq(UnstructuredBatchService.PendingMode.CLOUD_SEND), eq(ResumeMode.FROM_SEND),
                    isNull(), isNull(), eq(held), eq("admin01/cloud-send:T"), eq("admin01"));
        }
    }

    @Test
    @DisplayName("화면 안내 — 400 문구는 공백 포함 60자 이내 · 영문 식별자 · 실행 ID 없음")
    void messagesFitTwoLines() {
        for (String m : List.of(CloudSendService.MSG_NO_TARGET, CloudSendService.MSG_NOT_UNSTRUCTURED_BATCH,
                CloudSendService.MSG_IMAGE_NOT_SENT, CloudSendService.MSG_NOTHING_TO_SEND, CloudSendService.MSG_SEND_DONE,
                CloudSendService.MSG_HOLD_NOT_FOUND, CloudSendService.MSG_CANNOT_RESEND, CloudSendService.MSG_SEND_UNAVAILABLE,
                UnstructuredBatchService.MSG_BUSY)) {
            assertThat(m.length()).as("%s", m).isLessThanOrEqualTo(UserFacingRejection.MAX_MESSAGE_LEN);
            assertThat(m).as("내부 변수 · 실행 ID 가 섞이지 않는다").doesNotContainPattern("[A-Za-z]");
        }
    }

    private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String message) {
        assertThatThrownBy(call).isInstanceOf(UserFacingRejection.class).hasMessage(message);
    }
}
