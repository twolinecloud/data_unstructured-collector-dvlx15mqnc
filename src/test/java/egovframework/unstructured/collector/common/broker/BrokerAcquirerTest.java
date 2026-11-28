package egovframework.unstructured.collector.common.broker;

import egovframework.unstructured.collector.common.config.VoiceDirState;
import egovframework.unstructured.collector.common.config.VoiceModeState;
import egovframework.unstructured.collector.common.inbound.SourceFileAcquirer;
import egovframework.unstructured.collector.common.model.VoiceFile;
import egovframework.unstructured.collector.common.model.VoiceKind;
import egovframework.unstructured.collector.common.model.VoiceTarget;
import egovframework.unstructured.collector.common.sync.FileArrivalWatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 브로커 방식 파일 확보 — 2026-11-28 XVARM 제거 1단계로 {@code VoiceCollectService} · {@code ImageCollectService} 에서
 * <b>그대로</b> 옮긴 순서(요청 전 잔재 삭제 → 추출 요청 → 수신 폴더 밖 판정 → 도착 대기)를 지킨다.
 *
 * <p>순서가 바뀌면 깨지는 것: 잔재 삭제가 요청 뒤로 가면 브로커가 방금 만든 파일을 지운다. 수신 폴더 밖 판정이 빠지면
 * 300초 타임아웃까지 원인 없이 기다린다.</p>
 */
class BrokerAcquirerTest {

    @TempDir
    Path tmp;

    private XvarmBrokerClient broker;
    private MockXvarmBrokerClient mockBroker;
    private FileArrivalWatcher watcher;
    private VoiceDirState dirs;
    private VoiceModeState modeState;
    private BrokerAcquirer acquirer;
    private Path meetDir;

    @BeforeEach
    void setUp() throws Exception {
        broker = mock(XvarmBrokerClient.class);
        mockBroker = mock(MockXvarmBrokerClient.class);
        watcher = mock(FileArrivalWatcher.class);
        dirs = mock(VoiceDirState.class);
        modeState = mock(VoiceModeState.class);
        meetDir = Files.createDirectories(tmp.resolve("meet"));
        when(dirs.receiveMeet()).thenReturn(meetDir.toString());
        when(dirs.receiveDir(VoiceKind.MEET)).thenReturn(meetDir);
        when(broker.mode()).thenReturn("REST");
        when(mockBroker.mode()).thenReturn("MOCK");
        when(modeState.brokerBaseUrl()).thenReturn("http://broker:8080/");
        acquirer = new BrokerAcquirer(broker, mockBroker, watcher, dirs, modeState);
    }

    private static VoiceTarget meet() {
        return new VoiceTarget(VoiceKind.MEET, "CORR1", "TARE-0001", "DOC1", "FK1",
                true, null, "/data001", "mock_meet_001.m4a", null, LocalDateTime.now());
    }

    @Test
    @DisplayName("접견 — 요청 전 잔재 삭제 → 추출 요청 → 브로커가 알려준 이름으로 대기, 이 순서 그대로")
    void meetClearsThenExtractsThenAwaitsBrokerName() {
        VoiceTarget t = meet();
        BooleanSupplier canceled = () -> false;
        // 브로커 쪽 경로(우리 파일시스템에 없음) — 판정을 건너뛰고 이름만 쓴다
        when(broker.extract(t, "EXEC1")).thenReturn(
                new XvarmBrokerClient.ExtractResult("REQ1", "/borami/out/R_IF001_BOR_KAI_1.DAT", 10L));
        VoiceFile arrived = new VoiceFile(t, meetDir.resolve("R_IF001_BOR_KAI_1.DAT"), 10L, "wav", false);
        when(watcher.await(t, "R_IF001_BOR_KAI_1.DAT", canceled)).thenReturn(arrived);

        VoiceFile f = acquirer.acquireMeet(t, "EXEC1", canceled);

        assertThat(f).isSameAs(arrived);
        InOrder order = inOrder(watcher, broker);
        order.verify(watcher).clearStale(t);
        order.verify(broker).extract(t, "EXEC1");
        order.verify(watcher).await(t, "R_IF001_BOR_KAI_1.DAT", canceled);
    }

    @Test
    @DisplayName("접견 — 브로커가 우리 수신 폴더 밖에 만들었으면 기다리지 않고 곧바로 실패한다")
    void meetFailsFastWhenBrokerWroteOutsideReceiveDir() throws Exception {
        VoiceTarget t = meet();
        Path outside = Files.createDirectories(tmp.resolve("broker-work")).resolve("mock_meet_001.m4a");
        Files.writeString(outside, "x");
        when(broker.extract(t, "EXEC1")).thenReturn(
                new XvarmBrokerClient.ExtractResult("REQ1", outside.toString(), 1L));

        assertThatThrownBy(() -> acquirer.acquireMeet(t, "EXEC1", () -> false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("수신 폴더 밖");
        verify(watcher, never()).await(any(), any(), any());
    }

    @Test
    @DisplayName("사진 SIM 검증 — 수집기 내장 Mock 브로커로 받고(설정 브로커는 부르지 않음) 표본 기록에 cURL 한 줄")
    void photoSimUsesBuiltInMockAndRecordsTrace() {
        String name = "img_SIMIMG00001_2.jpg";
        XvarmBrokerClient.FileRequest fr = new XvarmBrokerClient.FileRequest("DOC12", "FK12", "RID12", name);
        when(mockBroker.extractFile(fr)).thenReturn(
                new XvarmBrokerClient.ExtractResult("RID12", meetDir.resolve(name).toString(), 5L));
        BooleanSupplier canceled = () -> false;
        when(watcher.awaitFile(meetDir, name, canceled)).thenReturn(meetDir.resolve(name));
        List<String[]> trace = new ArrayList<>();

        Path got = acquirer.acquirePhoto(new SourceFileAcquirer.PhotoRequest("DOC12", "FK12", "RID12", name, true),
                canceled, (title, kind, command, result) -> trace.add(new String[]{title, kind, command, result}));

        assertThat(got).isEqualTo(meetDir.resolve(name));
        InOrder order = inOrder(watcher, mockBroker);
        order.verify(watcher).clearStale(meetDir, name);
        order.verify(mockBroker).extractFile(fr);
        order.verify(watcher).awaitFile(meetDir, name, canceled);
        verify(broker, never()).extractFile(any());
        assertThat(trace).hasSize(1);
        assertThat(trace.get(0)[0]).contains("(MOCK)");
        assertThat(trace.get(0)[1]).isEqualTo("HTTP");
        assertThat(trace.get(0)[2]).contains("수집기 내장 Mock").contains("/api/v1/xvarm/extract").contains("\"fileKey\":\"FK12\"");
    }

    @Test
    @DisplayName("사진 실제 수집 — 설정 브로커로 받고, 기록할 곳이 없으면(null) 남기지 않는다 · 경로가 비면 요청한 이름으로 기다린다")
    void photoRealUsesConfiguredBrokerWithoutTrace() {
        String name = "img_C1_1.jpg";
        XvarmBrokerClient.FileRequest fr = new XvarmBrokerClient.FileRequest("DOC1", "FK1", "RID1", name);
        when(broker.extractFile(fr)).thenReturn(new XvarmBrokerClient.ExtractResult("RID1", "", 0L));
        BooleanSupplier canceled = () -> false;
        when(watcher.awaitFile(meetDir, name, canceled)).thenReturn(meetDir.resolve(name));

        Path got = acquirer.acquirePhoto(new SourceFileAcquirer.PhotoRequest("DOC1", "FK1", "RID1", name, false),
                canceled, null);

        assertThat(got).isEqualTo(meetDir.resolve(name));
        verify(mockBroker, never()).extractFile(any());
        verify(modeState, never()).brokerBaseUrl();
    }

    @Test
    @DisplayName("표시 — 확보 방식 BROKER(설정 브로커) · FILEKEY 필요 · SIM 이면 수집기 내장 Mock 이라고 밝힌다")
    void labels() {
        assertThat(acquirer.mode()).isEqualTo("BROKER(REST)");
        assertThat(acquirer.requiresFileKey()).isTrue();
        assertThat(acquirer.photoSourceLabel(false)).isEqualTo("REST");
        assertThat(acquirer.photoSourceLabel(true)).startsWith("MOCK(SIM 검증").contains("REST");

        when(broker.mode()).thenReturn("MOCK");
        assertThat(acquirer.photoSourceLabel(true)).isEqualTo("MOCK");
    }

    @Test
    @DisplayName("사진 매핑 filekey 자리 — 브로커 방식은 지금처럼 XVARM FILEKEY 그대로(받은 파일 경로가 아니다)")
    void photoRecordKeyIsXvarmFileKey() {
        SourceFileAcquirer.PhotoRequest req = new SourceFileAcquirer.PhotoRequest("DOC1", "/ecm/vol1/FK1", "RID1", "img_C1_1.bin", false);

        assertThat(acquirer.photoRecordKey(req, meetDir.resolve("img_C1_1.bin"))).isEqualTo("/ecm/vol1/FK1");
        assertThat(acquirer.photoRecordKey(new SourceFileAcquirer.PhotoRequest("DOC1", "/ecm/vol1/FK1", "RID2", "img_C1_1.bin", true),
                meetDir.resolve("img_C1_1.bin"))).as("SIM 검증도 같다").isEqualTo("/ecm/vol1/FK1");
    }

    @Test
    @DisplayName("파일명 뽑기 — 사진은 / · \\ 어느 구분자든 자르고, 비면 요청한 이름")
    void fileNameHelpers() {
        assertThat(BrokerAcquirer.fileNameOf("/borami/out/R_1.DAT")).isEqualTo("R_1.DAT");
        assertThat(BrokerAcquirer.fileNameOf(null)).isNull();
        assertThat(BrokerAcquirer.fileNameOf("  ")).isNull();
        assertThat(BrokerAcquirer.photoFileNameOf("D:\\esb\\meet\\img_a.jpg", "fb")).isEqualTo("img_a.jpg");
        assertThat(BrokerAcquirer.photoFileNameOf("/k8s/esb/meet/img_b.jpg", "fb")).isEqualTo("img_b.jpg");
        assertThat(BrokerAcquirer.photoFileNameOf("/k8s/esb/meet/", "fb")).isEqualTo("fb");
        assertThat(BrokerAcquirer.photoFileNameOf(null, "fb")).isEqualTo("fb");
    }
}
