package egovframework.unstructured.collector.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import egovframework.unstructured.collector.batch.UnstructuredJobRunner;
import egovframework.unstructured.collector.common.config.VoiceDirState;
import egovframework.unstructured.collector.common.logging.LogCollectorClient;
import egovframework.unstructured.collector.common.model.BatchWindow;
import egovframework.unstructured.collector.common.transfer.TransmitHoldClient;
import egovframework.unstructured.collector.image.batch.ImageCollectService;
import egovframework.unstructured.collector.voice.batch.BatchProgress;
import egovframework.unstructured.collector.voice.batch.VoiceBatchResult;
import egovframework.unstructured.collector.voice.batch.VoiceCollectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 개발계 E2E 데이터 주입 — 상태 순서대로 좁은 구간에 더미를 만들어 실제 배치로 돌리고, 옛 더미 쓰레기 · 이전 묶음을 먼저 정리한다. */
class E2eDummyServiceTest {

    private static final ObjectMapper OM = new ObjectMapper();

    @TempDir
    Path root;

    private DummyDataService dummy;
    private VoiceCollectService voice;
    private LogCollectorClient logc;
    private TransmitHoldClient holds;
    private UnstructuredJobRunner runner;
    private BatchProgress progress;
    private ImageCollectService image;
    private E2eDummyService service;
    private int seq;

    @BeforeEach
    void setUp() throws Exception {
        dummy = mock(DummyDataService.class);
        voice = mock(VoiceCollectService.class);
        logc = mock(LogCollectorClient.class);
        holds = mock(TransmitHoldClient.class);
        runner = mock(UnstructuredJobRunner.class);
        progress = mock(BatchProgress.class);
        image = mock(ImageCollectService.class);
        VoiceDirState dirs = mock(VoiceDirState.class);
        when(dirs.baseDir()).thenReturn(root.toString());
        MockDataProperties props = new MockDataProperties(new MockDataProperties.Dashboard(true),
                new MockDataProperties.Daily(false, "0 30 1 * * *", List.of(), 5, 0, 0, 0, 7));
        when(logc.isEnabled()).thenReturn(true);
        when(logc.listBatches(anyString(), anyString(), anyInt())).thenReturn(OM.readTree("[]"));
        when(voice.pending(any(), anyList(), anyBoolean())).thenReturn(Map.of("pending", 0L));
        when(dummy.generate(any(), any(), any())).thenAnswer(inv -> Map.of("types", Map.of("MEET", Map.of("keys", List.of("DMY-MEET-K")))));
        when(voice.run(any(), anyList(), eq(E2eDummyService.TRIGGER_BY), eq(false))).thenAnswer(inv -> {
            seq++;
            BatchWindow w = inv.getArgument(0);
            // 상태별 결과 흉내 — 1 FAIL · 2 SUCCESS · 3 PARTIAL
            int ok = seq == 1 ? 0 : 2;
            int fail = seq == 2 ? 0 : (seq == 1 ? 2 : 1);
            return new VoiceBatchResult("E" + seq, true, w.toString(), ok + fail, ok, fail, 0, 1L, Map.of(), List.of(), List.of());
        });
        when(logc.batchDetail(anyString())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            String send = id.equals("E1") ? "FAIL" : "WAIT";
            return OM.readTree("{\"steps\":[{\"step_type_cd\":\"SEND\",\"step_sts_cd\":\"" + send + "\"}]}");
        });
        service = new E2eDummyService(dummy, voice, logc, holds, runner, progress, image, props, dirs, OM);
    }

    @Test
    @DisplayName("기본 — FAIL · WAIT · PARTIAL 을 이 순서로 만들어 실제 배치로 돌린다 · 구간은 어제 → 오늘 → 방금 · 묶음을 PV 에 남긴다")
    void createsInOrder() throws Exception {
        Map<String, Object> out = service.run(null);

        ArgumentCaptor<BatchWindow> w = ArgumentCaptor.forClass(BatchWindow.class);
        verify(voice, times(3)).run(w.capture(), anyList(), eq(E2eDummyService.TRIGGER_BY), eq(false));
        LocalDateTime now = LocalDateTime.now();
        assertThat(w.getAllValues().get(0).to().toLocalDate()).as("어제").isEqualTo(now.toLocalDate().minusDays(1));
        assertThat(w.getAllValues().get(2).to()).as("방금").isAfter(now.minusMinutes(3)).isBefore(now);
        assertThat(w.getAllValues()).allSatisfy(x -> assertThat(java.time.Duration.between(x.from(), x.to()).toMinutes()).isEqualTo(10));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> batches = (List<Map<String, Object>>) out.get("batches");
        assertThat(batches).extracting(b -> b.get("label") + ":" + b.get("requested") + ":" + b.get("execId") + ":" + b.get("asRequested"))
                .containsExactly("어제:FAIL:E1:true", "오늘 오전:WAIT:E2:true", "방금:PARTIAL:E3:true");
        assertThat(out).doesNotContainKey("warning");
        assertThat(Files.readString(root.resolve(E2eDummyService.FILE))).contains("E1").contains("E2").contains("E3");
        ArgumentCaptor<DummyDataService.GenerateRequest> g = ArgumentCaptor.forClass(DummyDataService.GenerateRequest.class);
        verify(dummy, times(3)).generate(g.capture(), any(), any());
        assertThat(g.getAllValues()).extracting(r -> r.count() + ":" + r.failures())
                .containsExactly("2:{SEND_FAIL=2}", "2:{}", "3:{ANALYZE_FAIL=1}");
        assertThat(g.getAllValues()).allSatisfy(r -> assertThat(r.target()).isEqualTo(DummyTarget.DASHBOARD));
    }

    @Test
    @DisplayName("구간 — 처리 안 된 다른 건이 있으면 15분씩 비킨다 · 계획 시각: 첫 배치 지금-1일 · 가운데 10:00(늦으면 지금 앞) · 마지막 지금-1분")
    void windowsAndLabels() {
        when(voice.pending(any(), anyList(), anyBoolean())).thenReturn(Map.of("pending", 1L), Map.of("pending", 0L));
        LocalDateTime[] w = service.freeWindow(LocalDateTime.of(2026, 10, 16, 10, 0), null);
        assertThat(w[1]).isEqualTo(LocalDateTime.of(2026, 10, 16, 9, 45));

        LocalDateTime now = LocalDateTime.of(2026, 10, 16, 16, 30, 12);
        assertThat(E2eDummyService.plannedEnd(0, 3, now)).isEqualTo(LocalDateTime.of(2026, 10, 15, 16, 30));
        assertThat(E2eDummyService.plannedEnd(1, 3, now)).isEqualTo(LocalDateTime.of(2026, 10, 16, 10, 0));
        assertThat(E2eDummyService.plannedEnd(2, 3, now)).isEqualTo(LocalDateTime.of(2026, 10, 16, 16, 29));
        assertThat(E2eDummyService.plannedEnd(1, 3, LocalDateTime.of(2026, 10, 16, 9, 0))).as("오전 10시 전이면 지금 앞")
                .isEqualTo(LocalDateTime.of(2026, 10, 16, 8, 35));
        assertThat(E2eDummyService.labelOf(0, 3)).isEqualTo("어제");
        assertThat(E2eDummyService.labelOf(1, 3)).isEqualTo("오늘 오전");
        assertThat(E2eDummyService.labelOf(0, 1)).isEqualTo("방금");
    }

    @Test
    @DisplayName("상태 값 — 대소문자 무관 · 비우면 FAIL · WAIT · PARTIAL · 모르는 값 · 7개 이상은 400 · 다른 배치가 돌면 409")
    void rejects() {
        assertThat(E2eDummyService.parse(List.of("fail", " Wait "))).containsExactly(E2eDummyService.Status.FAIL, E2eDummyService.Status.WAIT);
        assertThat(E2eDummyService.parse(null)).hasSize(3);
        assertThatThrownBy(() -> E2eDummyService.parse(List.of("SUCCESS"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> E2eDummyService.parse(List.of("FAIL", "FAIL", "FAIL", "FAIL", "FAIL", "FAIL", "FAIL")))
                .isInstanceOf(IllegalArgumentException.class);
        when(runner.isRunning()).thenReturn(true);
        assertThatThrownBy(() -> service.run(null)).isInstanceOf(E2eDummyService.BusyException.class);
        verify(dummy, never()).generate(any(), any(), any());
    }

    @Test
    @DisplayName("정리 — 이전 묶음 + 풀 수 없는 옛 더미(음성: 실패 원본 없음 · 이미지: 보존 기간 지난 DMYIMG) + 요청 배치. 장부에 남은 건 먼저 보내고, 못 보내면 지우지 않는다")
    void cleanupTargets() throws Exception {
        Files.createDirectories(root.resolve("mock"));
        Files.writeString(root.resolve(E2eDummyService.FILE), "{\"execIds\":[\"P1\",\"P2\"]}");
        // 이미지 T4 키는 공통파일 ID 형식(DMYIMGF<yyMMdd>…) — 개발계 E2E 에서 교정번호 형식만 보다가 정리가 빠졌다
        String oldImg = "DMYIMGF" + LocalDate.now().minusDays(9).format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd")) + "00012";
        String newImg = "DMYIMG" + LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd")) + "0001SF";
        when(logc.listBatches("UNSTRUCTURED", "FAIL,PARTIAL,CANCELED", 200)).thenReturn(OM.readTree("""
                [{"execId":"V1","jobId":"VOICE_ANALYSIS","targetFromDtm":"2026-10-02T00:00:00","targetToDtm":"2026-10-03T00:00:00"},
                 {"execId":"V2","jobId":"VOICE_ANALYSIS","targetFromDtm":"2026-10-15T00:00:00","targetToDtm":"2026-10-16T00:00:00"},
                 {"execId":"V3","jobId":"VOICE_ANALYSIS","targetFromDtm":"2026-10-02T00:00:00","targetToDtm":"2026-10-03T00:00:00"},
                 {"execId":"I1","jobId":"IMAGE_COLLECT"},
                 {"execId":"I2","jobId":"IMAGE_COLLECT"},
                 {"execId":"T1","jobId":"TEST_BATCH"}]"""));
        when(logc.fileProcs("V1", 5000)).thenReturn(t4("DMY-MEET-20261002-SF-0001:FAIL", "DMY-MEET-20261002-0002:SUCCESS"));
        when(logc.fileProcs("V2", 5000)).thenReturn(t4("DMY-MEET-20261015-SF-0001:FAIL"));
        when(logc.fileProcs("V3", 5000)).thenReturn(t4("DMY-MEET-20261002-0001:FAIL", "TF000123:SUCCESS"));   // 실데이터 섞임
        when(logc.fileProcs("I1", 5000)).thenReturn(t4(oldImg + ":FAIL"));
        when(logc.fileProcs("I2", 5000)).thenReturn(t4(newImg + ":FAIL"));
        when(voice.noReopenTargets(any(), eq(Set.of("DMY-MEET-20261002-SF-0001")), eq(false))).thenReturn(true);
        when(voice.noReopenTargets(any(), eq(Set.of("DMY-MEET-20261015-SF-0001")), eq(false))).thenReturn(false);
        when(holds.holds()).thenReturn(Set.of("P1", "P2"));
        when(holds.release("P1", "e2e-cleanup")).thenReturn(new TransmitHoldClient.Release(200, true, "SUCCESS", "ok", null));
        when(holds.release("P2", "e2e-cleanup")).thenReturn(new TransmitHoldClient.Release(409, false, null, "busy", null));
        when(logc.deleteDummyBatches(anyList())).thenReturn(OM.readTree("{\"deleted\":[\"P1\",\"V1\",\"I1\",\"X9\"],\"skipped\":[]}"));

        Map<String, Object> out = service.cleanup(List.of("X9"));

        assertThat(service.findUnresolvable()).containsOnlyKeys("V1", "I1");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> ids = ArgumentCaptor.forClass(List.class);
        verify(logc).deleteDummyBatches(ids.capture());
        assertThat(ids.getValue()).containsExactly("P1", "V1", "I1", "X9");   // P2 는 장부를 못 비워 남긴다
        assertThat(out.get("previousE2e")).asString().contains("P1").contains("P2");
    }

    @Test
    @DisplayName("이미지 키 대상일 — 교정번호(DMYIMG2610030001SF) · 공통파일 ID(DMYIMGF26100200012) 둘 다 · 아니면 없음")
    void imageKeyDate() {
        assertThat(E2eDummyService.imageDateOf("DMYIMG2610030001SF")).contains(LocalDate.of(2026, 10, 3));
        assertThat(E2eDummyService.imageDateOf("DMYIMGF26100200012")).contains(LocalDate.of(2026, 10, 2));
        assertThat(E2eDummyService.imageDateOf("TF000123")).isEmpty();
        assertThat(E2eDummyService.imageDateOf(null)).isEmpty();
    }

    private static JsonNode t4(String... keyStatus) throws Exception {
        List<Map<String, String>> rows = new ArrayList<>();
        for (String ks : keyStatus) {
            String[] p = ks.split(":");
            rows.add(Map.of("rec_file_id", p[0], "proc_sts_cd", p[1]));
        }
        return OM.valueToTree(Map.of("rows", rows));
    }
}
