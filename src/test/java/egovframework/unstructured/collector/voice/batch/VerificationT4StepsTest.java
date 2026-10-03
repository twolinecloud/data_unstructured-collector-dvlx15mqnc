package egovframework.unstructured.collector.voice.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import egovframework.unstructured.collector.common.config.DeployEnvPreset;
import egovframework.unstructured.collector.common.config.VoiceDirState;
import egovframework.unstructured.collector.common.logging.LogCollectorClient;
import egovframework.unstructured.collector.common.transfer.ZenonClient;
import egovframework.unstructured.collector.voice.stt.SttTempStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** T4 단계별 이력 — V16 미적용 DB(ERR_STACK 추정) · 옛 로그 컬렉터(조회 API 없음). 컬럼값 경로는 ImageLogHistoryTest(실제 HTTP). */
class VerificationT4StepsTest {

    private LogCollectorClient logc;
    private VerificationService svc;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        logc = mock(LogCollectorClient.class);
        when(logc.isEnabled()).thenReturn(true);
        svc = new VerificationService(mock(VoiceDirState.class), logc, mock(DeployEnvPreset.class), mock(SttTempStore.class), mock(ZenonClient.class));
    }

    @Test
    @DisplayName("V16 미적용(stepColumn=false) — 실패는 ERR_STACK 의 [단계], 성공은 SEND 로 추정하고 source=ERR_STACK")
    @SuppressWarnings("unchecked")
    void derivesStepFromErrStackWithoutV16() throws Exception {
        when(logc.fileProcs(eq("20261003UNS001"), anyInt())).thenReturn(om.readTree("""
                {"execId":"20261003UNS001","stepColumn":false,"byStatus":{"FAIL":3,"SUCCESS":1},"byStep":{},"truncated":false,
                 "rows":[
                   {"file_proc_id":"F1","rec_file_id":"DMY-MEET-20261002-CF-0001","proc_sts_cd":"FAIL","err_stack":"[DATA] [COLLECT] 수신 거부"},
                   {"file_proc_id":"F2","rec_file_id":"DMY-MEET-20261002-AF-0002","proc_sts_cd":"FAIL","err_stack":"[DATA] [ANALYZE] STT 실패"},
                   {"file_proc_id":"F3","rec_file_id":"DMY-MEET-20261002-XX-0003","proc_sts_cd":"FAIL","err_stack":"[SYSTEM] 알 수 없음"},
                   {"file_proc_id":"F4","rec_file_id":"DMY-MEET-20261002-0004","proc_sts_cd":"SUCCESS"}]}
                """));

        Map<String, Object> t = svc.t4Steps("20261003UNS001");

        assertThat(t).containsEntry("available", true).containsEntry("stepColumn", false).containsEntry("source", "ERR_STACK");
        Map<String, Map<String, Long>> by = (Map<String, Map<String, Long>>) t.get("byStep");
        assertThat(by.keySet()).as("COLLECT · ANALYZE · SEND 순서, 단계를 모르는 건은 뒤에").containsExactly("COLLECT", "ANALYZE", "SEND", "(없음)");
        assertThat(by.get("SEND")).containsEntry("SUCCESS", 1L);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) t.get("rows");
        assertThat(rows).extracting(r -> r.get("stepTypeCd")).containsExactly("COLLECT", "ANALYZE", null, "SEND");
        assertThat(rows).extracting(r -> r.get("stepSource")).containsExactly("ERR_STACK", "ERR_STACK", null, "ERR_STACK");
    }

    @Test
    @DisplayName("T4 조회 API 가 없는 옛 로그 컬렉터 · 미연동 — available=false 와 이유")
    void unavailable() {
        when(logc.fileProcs(eq("20261003UNS002"), anyInt())).thenReturn(null);
        assertThat(svc.t4Steps("20261003UNS002")).containsEntry("available", false).containsKey("reason");
        when(logc.isEnabled()).thenReturn(false);
        assertThat(svc.t4Steps("20261003UNS002")).containsEntry("available", false);
    }

    @Test
    @DisplayName("ERR_STACK 단계 읽기 — [코드] [단계] · 코드 없이 [단계] · 단계 없음")
    void stepOf() {
        assertThat(VerificationService.stepOf("[DATA] [SEND] 제논 HTTP 503")).isEqualTo("SEND");
        assertThat(VerificationService.stepOf(" [ANALYZE] STT 실패")).isEqualTo("ANALYZE");
        assertThat(VerificationService.stepOf("[TIMEOUT] 수신 대기 초과")).isNull();
        assertThat(VerificationService.stepOf(null)).isNull();
    }
}
