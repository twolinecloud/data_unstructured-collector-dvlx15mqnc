package egovframework.unstructured.collector.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import egovframework.unstructured.collector.batch.UnstructuredJobRunner;
import egovframework.unstructured.collector.common.config.VoiceDirState;
import egovframework.unstructured.collector.common.logging.LogCollectorClient;
import egovframework.unstructured.collector.common.model.BatchWindow;
import egovframework.unstructured.collector.common.transfer.TransmitHoldClient;
import egovframework.unstructured.collector.image.batch.ImageCollectService;
import egovframework.unstructured.collector.voice.batch.BatchProgress;
import egovframework.unstructured.collector.voice.batch.StageFaultState;
import egovframework.unstructured.collector.voice.batch.VerificationService;
import egovframework.unstructured.collector.voice.batch.VoiceBatchResult;
import egovframework.unstructured.collector.voice.batch.VoiceCollectService;
import egovframework.unstructured.collector.voice.transfer.VoiceDeidentClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 비식별 실패 배치 — 비식별 표식(DF) 더미를 지금 직전 10분 구간에 만들어 실제 배치로 돌리고, 이전에 만든 배치만 정리한다. */
class DeidentFailSimServiceTest {

    private static final ObjectMapper OM = new ObjectMapper();

    @TempDir
    Path root;

    private DummyDataService dummy;
    private VoiceCollectService voice;
    private VerificationService verification;
    private LogCollectorClient logc;
    private TransmitHoldClient holds;
    private UnstructuredJobRunner runner;
    private StageFaultState faults;
    private VoiceDeidentClient deident;
    private DeidentFailSimService service;
    private int seq;

    @BeforeEach
    void setUp() {
        dummy = mock(DummyDataService.class);
        voice = mock(VoiceCollectService.class);
        verification = mock(VerificationService.class);
        logc = mock(LogCollectorClient.class);
        holds = mock(TransmitHoldClient.class);
        runner = mock(UnstructuredJobRunner.class);
        deident = mock(VoiceDeidentClient.class);
        faults = new StageFaultState();
        VoiceDirState dirs = mock(VoiceDirState.class);
        when(dirs.baseDir()).thenReturn(root.toString());
        when(logc.isEnabled()).thenReturn(true);
        when(deident.enabled()).thenReturn(true);
        when(voice.pending(any(), anyList(), anyBoolean())).thenReturn(Map.of("pending", 0L));
        when(dummy.generate(any(), any(), any())).thenAnswer(inv -> Map.of("types", Map.of("MEET",
                Map.of("keys", List.of("DMY-MEET-20261105-DF-0001", "DMY-MEET-20261105-DF-0002", "DMY-MEET-20261105-0003")))));
        when(voice.run(any(), anyList(), eq(DeidentFailSimService.TRIGGER_BY), eq(false))).thenAnswer(inv -> {
            seq++;
            BatchWindow w = inv.getArgument(0);
            return new VoiceBatchResult("D" + seq, true, w.toString(), 3, 1, 2, 0, 1L, Map.of(), List.of(), List.of());
        });
        when(verification.t4Steps(anyString())).thenReturn(Map.of("byStep",
                Map.of("COLLECT", Map.of("SUCCESS", 3L), "DEIDENT", Map.of("FAIL", 2L))));
        E2eDummyService e2e = new E2eDummyService(dummy, voice, logc, holds, runner, mock(BatchProgress.class),
                mock(ImageCollectService.class), null, dirs, OM);
        service = new DeidentFailSimService(dummy, e2e, voice, verification, logc, holds, runner, mock(BatchProgress.class),
                mock(ImageCollectService.class), faults, deident, ScenarioFaults.activeForTest(), dirs, OM);
    }

    @Test
    @DisplayName("기본 — 접견 3건 중 2건 비식별 표식 · 지금 직전 10분 구간 · 실제 배치(test=false) · 비식별 실패 2건이면 요청대로")
    void createsOneBatch() throws Exception {
        Map<String, Object> out = service.run(null);

        ArgumentCaptor<DummyDataService.GenerateRequest> g = ArgumentCaptor.forClass(DummyDataService.GenerateRequest.class);
        verify(dummy).generate(g.capture(), any(), any());
        assertThat(g.getValue().target()).isEqualTo(DummyTarget.DASHBOARD);
        assertThat(g.getValue().dataTypes()).containsExactly(DummyDataType.MEET);
        assertThat(g.getValue().count()).isEqualTo(3);
        assertThat(g.getValue().failures()).isEqualTo(Map.of(FailureScenario.DEIDENT_FAIL, 2));
        ArgumentCaptor<BatchWindow> w = ArgumentCaptor.forClass(BatchWindow.class);
        verify(voice).run(w.capture(), anyList(), eq(DeidentFailSimService.TRIGGER_BY), eq(false));
        LocalDateTime now = LocalDateTime.now();
        assertThat(w.getValue().to()).isAfter(now.minusMinutes(3)).isBefore(now);
        assertThat(java.time.Duration.between(w.getValue().from(), w.getValue().to()).toMinutes()).isEqualTo(10);

        assertThat(out).containsEntry("execId", "D1").containsEntry("deidentFail", 2L).containsEntry("asRequested", true)
                .doesNotContainKey("warning");
        assertThat(Files.readString(root.resolve(DeidentFailSimService.FILE))).contains("D1");
    }

    @Test
    @DisplayName("정리 — 다음 실행이 이전에 만든 배치만 지운다 · 장부에 남은 건 먼저 보내고, 못 보내면 지우지 않는다")
    void cleansPrevious() {
        service.run(null);
        when(holds.holds()).thenReturn(Set.of("D1"));
        when(holds.release(eq("D1"), anyString())).thenReturn(new TransmitHoldClient.Release(500, false, null, "x", null));
        service.run(null);
        verify(logc, never()).deleteDummyBatches(anyList());

        when(holds.holds()).thenReturn(Set.of());
        service.run(null);
        verify(logc).deleteDummyBatches(List.of("D2"));
    }

    @Test
    @DisplayName("요청과 다름 — 비식별 실패가 모자라면 경고")
    void warnsWhenFewerFailures() {
        when(verification.t4Steps(anyString())).thenReturn(Map.of("byStep", Map.of("COLLECT", Map.of("FAIL", 3L))));
        Map<String, Object> out = service.run(null);
        assertThat(out).containsEntry("deidentFail", 0L).containsEntry("asRequested", false).containsKey("warning");
    }

    @Test
    @DisplayName("거절 — 건수 범위 400 · 단계별 장애가 걸려 있거나 비식별이 꺼져 있으면 만들지 않는다 · 다른 배치가 돌면 409")
    void rejects() {
        assertThatThrownBy(() -> service.run(new DeidentFailSimService.Request(0, 1, true))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.run(new DeidentFailSimService.Request(11, 1, true))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.run(new DeidentFailSimService.Request(3, 4, true))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.run(new DeidentFailSimService.Request(3, 0, true))).isInstanceOf(IllegalArgumentException.class);

        faults.set(StageFaultState.Stage.ANALYZE, StageFaultState.Mode.ALL, null);
        assertThatThrownBy(() -> service.run(null)).isInstanceOf(IllegalStateException.class).hasMessageContaining("단계별 장애");
        faults.clear();

        when(deident.enabled()).thenReturn(false);
        assertThatThrownBy(() -> service.run(null)).isInstanceOf(IllegalStateException.class).hasMessageContaining("VOICE_DEIDENT_ENABLED");
        when(deident.enabled()).thenReturn(true);

        when(runner.isRunning()).thenReturn(true);
        assertThatThrownBy(() -> service.run(null)).isInstanceOf(E2eDummyService.BusyException.class);
        verify(voice, never()).run(any(), anyList(), anyString(), anyBoolean());
    }
}
