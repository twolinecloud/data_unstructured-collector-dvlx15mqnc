package egovframework.unstructured.collector.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import egovframework.unstructured.collector.common.logging.LogCollectorClient;
import egovframework.unstructured.collector.common.model.SttResult;
import egovframework.unstructured.collector.common.model.VoiceTarget;
import egovframework.unstructured.collector.common.util.InmatePidGenerator;
import egovframework.unstructured.collector.voice.risk.RiskWf9Properties;
import egovframework.unstructured.collector.voice.risk.Wf9SendGate;
import egovframework.unstructured.collector.voice.risk.Wf9Spool;
import egovframework.unstructured.collector.voice.transfer.VoiceDeidentClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** WF9 위험 대화 샘플 — 배치 확인 · 비식별 결과(가린 글)만 스풀에 · 샘플 키만 지우기. */
class Wf9SampleSimServiceTest {

    private final LogCollectorClient logc = mock(LogCollectorClient.class);
    private final VoiceDeidentClient deid = mock(VoiceDeidentClient.class);
    private final Wf9Spool spool = mock(Wf9Spool.class);
    private final Wf9SendGate gate = mock(Wf9SendGate.class);
    private final InmatePidGenerator pid = mock(InmatePidGenerator.class);
    private final RiskWf9Properties props = mock(RiskWf9Properties.class);
    private Wf9SampleSimService svc;

    @BeforeEach
    void setUp() {
        when(props.enabled()).thenReturn(true);
        when(props.afterSend()).thenReturn(true);
        when(props.pollSec()).thenReturn(30);
        when(deid.enabled()).thenReturn(true);
        when(pid.of(anyString())).thenReturn("PID");
        svc = new Wf9SampleSimService(logc, deid, spool, gate, pid, props);
    }

    @Test
    @DisplayName("가린 글로 스풀에 넣는다 · 수동 전송 대기면 [클라우드 전송] 뒤라고 안내")
    void runSpoolsMaskedText() {
        when(logc.findBatchDetail("E1")).thenReturn(new ObjectMapper().createObjectNode());
        when(deid.deident(eq("E1"), any(), eq("PID"), any())).thenReturn(
                new VoiceDeidentClient.Result("가린 글", List.of("가린 1", "가린 2", "가린 3", "가린 4", "가린 5"), "DEIDENT", 7L, 0));
        when(spool.offer(eq("E1"), any(), any(), eq("DEIDENT"), eq(false))).thenReturn(true);
        when(gate.sendStatus("E1")).thenReturn("WAIT");

        Map<String, Object> out = svc.run(new Wf9SampleSimService.Request(" E1 ", "C123", null,
                LocalDateTime.of(2026, 11, 27, 10, 0, 5, 9), null));

        ArgumentCaptor<VoiceTarget> t = ArgumentCaptor.forClass(VoiceTarget.class);
        ArgumentCaptor<SttResult> s = ArgumentCaptor.forClass(SttResult.class);
        verify(spool).offer(eq("E1"), t.capture(), s.capture(), eq("DEIDENT"), eq(false));
        assertThat(t.getValue().corrNo()).isEqualTo("C123");
        assertThat(t.getValue().idempotencyKey()).startsWith("SIMWF9-");
        assertThat(t.getValue().occurredAt()).isEqualTo(LocalDateTime.of(2026, 11, 27, 10, 0, 5));
        assertThat(s.getValue().text()).as("원문이 아니라 커넥터가 돌려준 가린 글").isEqualTo("가린 글");
        assertThat(out.get("spooled")).isEqualTo(true);
        assertThat(out.get("batchSend")).isEqualTo("WAIT");
        assertThat((String) out.get("next")).contains("클라우드 전송");
    }

    @Test
    @DisplayName("execId · corrNo 누락 · 없는 배치는 거절 — 비식별 · 스풀을 부르지 않는다")
    void rejects() {
        assertThatThrownBy(() -> svc.run(new Wf9SampleSimService.Request(null, "C", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.run(new Wf9SampleSimService.Request("NOPE", "C", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("배치가 없습니다");
        verify(deid, never()).deident(any(), any(), any(), any());
        verify(spool, never()).offer(any(), any(), any(), any(), any(Boolean.class));
    }

    @Test
    @DisplayName("지우기는 샘플 키(SIMWF9-14자리)만 — 실제 녹음 ID 는 거절")
    void removeOnlySampleKeys() {
        when(logc.replaceContactRiskSignals(eq("SIMWF9-20261127150000"), any())).thenReturn(0);
        assertThat(svc.remove("SIMWF9-20261127150000", "C123", "MEET")).containsEntry("remaining", 0);
        assertThatThrownBy(() -> svc.remove("TARE0000000001", "C123", "MEET")).isInstanceOf(IllegalArgumentException.class);
    }
}
