package egovframework.unstructured.collector.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import egovframework.unstructured.collector.batch.UnstructuredJobRunner;
import egovframework.unstructured.collector.common.config.VoiceDirState;
import egovframework.unstructured.collector.common.logging.LogCollectorClient;
import egovframework.unstructured.collector.common.model.BatchWindow;
import egovframework.unstructured.collector.common.transfer.TransmitHoldClient;
import egovframework.unstructured.collector.image.batch.ImageCollectService;
import egovframework.unstructured.collector.mock.CloudSendSimService.State;
import egovframework.unstructured.collector.voice.batch.BatchProgress;
import egovframework.unstructured.collector.voice.batch.VoiceBatchResult;
import egovframework.unstructured.collector.voice.batch.VoiceCollectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 클라우드 전송 시뮬레이션 — 상태별 개수 · 돌아가며 섞는 순서 · 구간 · 재현 상태(claim → 마감) · 정리. */
class CloudSendSimServiceTest {

    private static final ObjectMapper OM = new ObjectMapper();

    @TempDir
    Path root;

    private DummyDataService dummy;
    private VoiceCollectService voice;
    private LogCollectorClient logc;
    private TransmitHoldClient holds;
    private CloudSendSimService service;

    /** 배치별 전송 단계 상태(가짜 로그 컬렉터) · 보류 장부. */
    private final Map<String, String> send = new HashMap<>();
    private final Set<String> held = new HashSet<>();
    private int seq;

    @BeforeEach
    void setUp() {
        dummy = mock(DummyDataService.class);
        voice = mock(VoiceCollectService.class);
        logc = mock(LogCollectorClient.class);
        holds = mock(TransmitHoldClient.class);
        UnstructuredJobRunner runner = mock(UnstructuredJobRunner.class);
        BatchProgress progress = mock(BatchProgress.class);
        ImageCollectService image = mock(ImageCollectService.class);
        VoiceDirState dirs = mock(VoiceDirState.class);
        when(dirs.baseDir()).thenReturn(root.toString());
        when(logc.isEnabled()).thenReturn(true);
        when(voice.pending(any(), anyList(), anyBoolean())).thenReturn(Map.of("pending", 0L));
        when(dummy.generate(any(), any(), any())).thenReturn(Map.of());
        when(holds.holds()).thenAnswer(inv -> Set.copyOf(held));
        // 실제 배치 흉내 — 전송 실패 표식 2건이면 FAIL(장부 없음), 아니면 보류(WAIT · 장부)
        when(voice.run(any(), anyList(), eq(CloudSendSimService.TRIGGER_BY), eq(false))).thenAnswer(inv -> {
            String id = "C" + (++seq);
            boolean fail = lastFailures == 2;
            send.put(id, fail ? "FAIL" : "WAIT");
            if (!fail) {
                held.add(id);
            }
            BatchWindow w = inv.getArgument(0);
            return new VoiceBatchResult(id, true, w.toString(), 2, fail ? 0 : 2, fail ? 2 : 0, 0, 1L, Map.of(), List.of(), List.of());
        });
        when(dummy.generate(any(), any(), any())).thenAnswer(inv -> {
            DummyDataService.GenerateRequest g = inv.getArgument(0);
            lastFailures = g.failures() == null ? 0 : g.failures().getOrDefault(FailureScenario.SEND_FAIL, 0);
            return Map.of();
        });
        when(logc.batchDetail(anyString())).thenAnswer(inv -> detail(inv.getArgument(0)));
        when(logc.claimSendStep(anyString())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            String cur = send.get(id);
            if (cur == null || "RUNNING".equals(cur) || "SUCCESS".equals(cur)) {
                return null;
            }
            send.put(id, "RUNNING");
            return id + "-S";
        });
        org.mockito.Mockito.doAnswer(inv -> {
            String sid = inv.getArgument(0);
            send.put(sid.substring(0, sid.length() - 2), inv.getArgument(1));
            return null;
        }).when(logc).finishStep(anyString(), anyString(), any(), any(), any(), any(), any());
        service = new CloudSendSimService(dummy, voice, logc, holds, runner, progress, image, dirs, OM);
    }

    private int lastFailures;

    private JsonNode detail(String id) throws Exception {
        String sts = send.get(id);
        if (sts == null) {
            return OM.readTree("{\"batch\":{},\"steps\":[]}");
        }
        return OM.readTree("{\"batch\":{\"exec_id\":\"" + id + "\",\"exec_sts_cd\":\"SUCCESS\"},\"steps\":[{\"step_log_id\":\"" + id
                + "-S\",\"step_type_cd\":\"SEND\",\"step_seq\":4,\"step_sts_cd\":\"" + sts + "\",\"in_cnt\":3,\"out_cnt\":2,\"err_cnt\":1}]}");
    }

    @Test
    @DisplayName("개수 — 본문이 없으면 상태마다 2 · 빠진 상태는 0 · 0~10 · 합계 1~30 · 모르는 상태는 400")
    void parseCounts() {
        assertThat(CloudSendSimService.parseCounts(null)).containsEntry(State.WAIT, 2).containsEntry(State.CANCELED, 2).hasSize(5);
        assertThat(CloudSendSimService.parseCounts(Map.of("wait", 3))).containsEntry(State.WAIT, 3).containsEntry(State.FAIL, 0);
        assertThatThrownBy(() -> CloudSendSimService.parseCounts(Map.of("WAIT", 11))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CloudSendSimService.parseCounts(Map.of("WAIT", 0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CloudSendSimService.parseCounts(Map.of("DONE", 1))).isInstanceOf(IllegalArgumentException.class);
        Map<String, Integer> big = new LinkedHashMap<>();
        for (State s : State.values()) {
            big.put(s.name(), 7);
        }
        assertThatThrownBy(() -> CloudSendSimService.parseCounts(big)).hasMessageContaining("30");
    }

    @Test
    @DisplayName("순서 — 전송중 → 취소 → 부분 성공 → 실패 → 대기 를 돌아가며, 다 찬 상태는 건너뛴다(마지막이 대기)")
    void roundRobin() {
        assertThat(CloudSendSimService.order(CloudSendSimService.parseCounts(null))).containsExactly(
                State.RUNNING, State.CANCELED, State.PARTIAL, State.FAIL, State.WAIT,
                State.RUNNING, State.CANCELED, State.PARTIAL, State.FAIL, State.WAIT);
        assertThat(CloudSendSimService.order(CloudSendSimService.parseCounts(Map.of("WAIT", 3, "FAIL", 1))))
                .containsExactly(State.FAIL, State.WAIT, State.WAIT, State.WAIT);
    }

    @Test
    @DisplayName("구간 — 오래된 것부터 · 10분 · 2분 간격 · 마지막은 지금-1분에 끝 · 처리 안 된 건이 있으면 비키고 자정을 걸치지 않는다")
    void windows() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 17, 7, 30, 40);
        List<LocalDateTime[]> w = CloudSendSimService.windows(3, now, (s, e) -> false);
        assertThat(w.get(2)).containsExactly(LocalDateTime.of(2026, 10, 17, 7, 19), LocalDateTime.of(2026, 10, 17, 7, 29));
        assertThat(w.get(1)[1]).isEqualTo(LocalDateTime.of(2026, 10, 17, 7, 17));
        assertThat(w.get(0)[1]).isEqualTo(LocalDateTime.of(2026, 10, 17, 7, 5));

        // 7:19 ~ 7:29 에 남의 건이 있으면 15분 앞으로
        List<LocalDateTime[]> moved = CloudSendSimService.windows(1, now, (s, e) -> e.equals(LocalDateTime.of(2026, 10, 17, 7, 29)));
        assertThat(moved.get(0)[1]).isEqualTo(LocalDateTime.of(2026, 10, 17, 7, 14));

        // 두 번째 구간 끝이 00:03 이면 23:53 ~ 00:03 이 자정을 걸친다 → 0시에 끝나는 23:50 ~ 00:00
        List<LocalDateTime[]> midnight = CloudSendSimService.windows(2, LocalDateTime.of(2026, 10, 17, 0, 16), (s, e) -> false);
        assertThat(midnight.get(1)).containsExactly(LocalDateTime.of(2026, 10, 17, 0, 5), LocalDateTime.of(2026, 10, 17, 0, 15));
        assertThat(midnight.get(0)).containsExactly(LocalDateTime.of(2026, 10, 16, 23, 50), LocalDateTime.of(2026, 10, 17, 0, 0));
    }

    @Test
    @DisplayName("생성 — 상태별로 실제 배치 → 재현 상태는 claim 뒤 마감(부분 성공 in3·out2·err1 · 취소 in3·out0) · 전송중은 시작만 · pick 은 최근 미완료")
    void runShapesStates() {
        Map<String, Object> out = service.run(new CloudSendSimService.Request(
                Map.of("RUNNING", 1, "CANCELED", 1, "PARTIAL", 1, "FAIL", 1, "WAIT", 1), false));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> b = (List<Map<String, Object>>) out.get("batches");
        assertThat(b).extracting(x -> x.get("state")).containsExactly("RUNNING", "CANCELED", "PARTIAL", "FAIL", "WAIT");
        assertThat(b).extracting(x -> x.get("send")).containsExactly("RUNNING", "CANCELED", "PARTIAL", "FAIL", "WAIT");
        assertThat(b).allSatisfy(x -> assertThat(x.get("asRequested")).isEqualTo(true));
        assertThat(b.get(3).get("held")).isEqualTo(false);
        assertThat(b.get(2).get("held")).isEqualTo(true);
        verify(logc).finishStep(eq("C3-S"), eq("PARTIAL"), eq(0), eq(3L), eq(2L), eq(1L), startsWith("[SEND]"));
        verify(logc).finishStep(eq("C2-S"), eq("CANCELED"), eq(0), eq(3L), eq(0L), eq(3L), startsWith("[SEND]"));
        verify(logc, never()).finishStep(eq("C1-S"), anyString(), any(), any(), any(), any(), any());
        verify(logc, never()).claimSendStep("C4");
        assertThat(out.get("pick")).isEqualTo("C5");
        assertThat(out).doesNotContainKey("warning");
    }

    @Test
    @DisplayName("전송 방식이 자동이면(보류 없음) 재현 상태를 만들지 않고 asRequested=false · 경고")
    void autoModeCannotShape() {
        when(voice.run(any(), anyList(), eq(CloudSendSimService.TRIGGER_BY), eq(false))).thenAnswer(inv -> {
            String id = "A" + (++seq);
            send.put(id, "SUCCESS");
            return new VoiceBatchResult(id, true, "w", 2, 2, 0, 0, 1L, Map.of(), List.of(), List.of());
        });
        Map<String, Object> out = service.run(new CloudSendSimService.Request(Map.of("CANCELED", 1), false));
        @SuppressWarnings("unchecked")
        Map<String, Object> b = ((List<Map<String, Object>>) out.get("batches")).get(0);
        assertThat(b.get("asRequested")).isEqualTo(false);
        assertThat((String) b.get("problem")).contains("자동");
        assertThat(out).containsKey("warning");
        verify(logc, never()).claimSendStep(anyString());
    }

    @Test
    @DisplayName("정리 — 이전 묶음의 전송중은 대기로 되돌리고 · 장부에 있는 건 먼저 보내고(못 보내면 남김) · 나머지를 지운다")
    void cleanupPrevious() throws Exception {
        service.run(new CloudSendSimService.Request(Map.of("RUNNING", 1, "FAIL", 1, "WAIT", 1), false));   // C1 RUNNING · C2 FAIL · C3 WAIT
        when(holds.release(eq("C1"), anyString())).thenReturn(new TransmitHoldClient.Release(200, true, "SUCCESS", "ok", null));
        when(holds.release(eq("C3"), anyString())).thenReturn(new TransmitHoldClient.Release(503, false, null, "down", null));
        when(logc.deleteDummyBatches(anyList())).thenReturn(OM.readTree("{\"deleted\":[\"C1\",\"C2\"],\"skipped\":[]}"));

        Map<String, Object> c = service.cleanupOnly();

        verify(logc).finishStep(eq("C1-S"), eq("WAIT"), eq(0), eq(3L), eq(0L), eq(0L), isNull());
        verify(logc).deleteDummyBatches(List.of("C1", "C2"));
        assertThat(c.get("previous")).isEqualTo(List.of("C1", "C2", "C3"));
    }

    @Test
    @DisplayName("전송중 풀기 — 이번 묶음의 RUNNING 중 장부에 있는 것만 대기로")
    void unstick() {
        service.run(new CloudSendSimService.Request(Map.of("RUNNING", 2, "WAIT", 1), false));   // C1 RUNNING · C2 WAIT · C3 RUNNING
        held.remove("C3");
        Map<String, Object> u = service.unstick();
        assertThat(send.get("C1")).isEqualTo("WAIT");
        assertThat(send.get("C3")).isEqualTo("RUNNING");
        assertThat(u.get("unstuck").toString()).contains("C1", "대기로 되돌림", "C3", "보류 장부에 없음");
        verify(logc, never()).finishStep(eq("C2-S"), anyString(), any(), anyLong(), anyLong(), anyLong(), any());
    }
}
