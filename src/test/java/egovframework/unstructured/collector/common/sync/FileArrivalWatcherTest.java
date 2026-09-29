package egovframework.unstructured.collector.common.sync;

import egovframework.unstructured.collector.common.config.VoiceProperties;
import egovframework.unstructured.collector.common.model.VoiceFile;
import egovframework.unstructured.collector.common.model.VoiceKind;
import egovframework.unstructured.collector.common.model.VoiceTarget;
import egovframework.unstructured.collector.common.util.SilentWav;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 수신 파일 탐색 — <b>브로커가 알려준 이름을 우선하는지</b>가 핵심이다.
 *
 * <p>파일명을 우리가 정책으로 추측하면, 브로커(또는 실제 XVARM)가 다른 이름으로 만들었을 때
 * 영영 찾지 못하고 타임아웃이 난다. 에러 메시지도 "대기 타임아웃" 이라 원인이 드러나지 않는다.</p>
 */
class FileArrivalWatcherTest {

    @TempDir
    Path tmp;

    private VoiceProperties props(Path meet, Path phone) {
        return new VoiceProperties(
                new VoiceProperties.Source(VoiceProperties.SourceMode.MOCK, "", "",
                        new VoiceProperties.Schema("", "", "", "", ""),
                        new VoiceProperties.Flag("Y", "Y", "N", "Y"),
                        VoiceProperties.XvarmMode.MOCK_DEV, new VoiceProperties.XvarmMock("sm", "xvarm"),
                        new VoiceProperties.LocalH2("jdbc:h2:mem:t", "sa", "", 10, 0),
                        new VoiceProperties.DirectDb("jdbc:postgresql://localhost:1/x", "", "u", "", 1000, 1, 0, 60000)),
                new VoiceProperties.Broker(VoiceProperties.BrokerMode.MOCK, "", java.util.List.of(), 10, 3),
                new VoiceProperties.Phone(VoiceProperties.PhoneMode.MOCK),
                new VoiceProperties.Sync(20, 3, EsbFileNamingPolicy.Policy.ORIGINAL),
                new VoiceProperties.Dirs(tmp.toString(), meet.toString(), phone.toString(), tmp.resolve("w").toString(),
                        tmp.resolve("out/meet").toString(), tmp.resolve("out/phone").toString(), tmp.resolve("xv").toString()),
                new VoiceProperties.Decrypt(VoiceProperties.DecryptMode.SKIP, ""),
                new VoiceProperties.Stt(VoiceProperties.SttMode.MOCK, "", 30),
                new VoiceProperties.Batch("0 0 2 * * *", "0 */10 * * * *", 20, false,
                        List.of("0", "1"), 500, "VOICE_ANALYSIS", "TEST_BATCH", "UNSTRUCTURED", false),
                new VoiceProperties.Sim(false));
    }

    private VoiceTarget meetTarget() {
        return new VoiceTarget(VoiceKind.MEET, "CORR1", "TARE-0001", "DOC1", "FK1",
                true, null, "/data001", "mock_meet_001.m4a", null, LocalDateTime.now());
    }

    private FileArrivalWatcher watcher(Path meet) throws Exception {
        Path phone = tmp.resolve("phone");
        Files.createDirectories(meet);
        Files.createDirectories(phone);
        VoiceProperties p = props(meet, phone);
        egovframework.unstructured.collector.common.config.VoiceDirState dirs = new egovframework.unstructured.collector.common.config.VoiceDirState(p, new egovframework.unstructured.collector.common.config.DeployEnvPreset(new org.springframework.mock.env.MockEnvironment(), p, ""));
        dirs.resetToConfigured();
        return new FileArrivalWatcher(p, dirs, new EsbFileNamingPolicy());
    }

    @Test
    @DisplayName("브로커가 알려준 파일명을 우선한다 — 정책으로 추측한 이름과 달라도 찾는다")
    void hintWinsOverPolicy() throws Exception {
        Path meet = tmp.resolve("meet");
        FileArrivalWatcher w = watcher(meet);
        // 브로커가 우리 정책과 전혀 다른 이름으로 만들었다
        Files.write(meet.resolve("R_IF001_BOR_KAI_20260915120000000_12345.DAT"), SilentWav.of(1));

        VoiceFile f = w.await(meetTarget(), "R_IF001_BOR_KAI_20260915120000000_12345.DAT");

        assertThat(f.path().getFileName().toString()).endsWith(".DAT");
        assertThat(f.sizeBytes()).isPositive();
        // .DAT 라도 매직 넘버로 포맷을 알아낸다
        assertThat(f.format()).isEqualTo("wav");
    }

    @Test
    @DisplayName("힌트가 없으면 정책(ORIGINAL)으로 업무 파일명을 찾는다")
    void fallsBackToPolicy() throws Exception {
        Path meet = tmp.resolve("meet2");
        FileArrivalWatcher w = watcher(meet);
        Files.write(meet.resolve("mock_meet_001.m4a"), SilentWav.of(1));

        VoiceFile f = w.await(meetTarget());

        assertThat(f.path().getFileName().toString()).isEqualTo("mock_meet_001.m4a");
    }

    @Test
    @DisplayName("힌트가 빈 문자열이면 정책으로 되돌아간다")
    void blankHintFallsBack() throws Exception {
        Path meet = tmp.resolve("meet3");
        FileArrivalWatcher w = watcher(meet);
        Files.write(meet.resolve("mock_meet_001.m4a"), SilentWav.of(1));

        assertThat(w.await(meetTarget(), "  ").path().getFileName().toString())
                .isEqualTo("mock_meet_001.m4a");
    }

    @Test
    @DisplayName("파일이 오지 않으면 타임아웃 — 기다린 경로를 메시지에 담는다")
    void timesOutWithContext() throws Exception {
        Path meet = tmp.resolve("meet4");
        FileArrivalWatcher w = watcher(meet);

        assertThatThrownBy(() -> w.await(meetTarget(), "never-arrives.m4a"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("never-arrives.m4a")
                .hasMessageContaining("대기 타임아웃");
    }

    @Test
    @DisplayName("0 바이트 파일은 도착으로 보지 않는다 — 쓰는 중일 수 있다")
    void emptyFileIsNotArrived() throws Exception {
        Path meet = tmp.resolve("meet5");
        FileArrivalWatcher w = watcher(meet);
        Files.createFile(meet.resolve("empty.m4a"));

        assertThatThrownBy(() -> w.await(meetTarget(), "empty.m4a"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("isArrived 는 기다리지 않고 즉시 확인한다")
    void isArrivedDoesNotBlock() throws Exception {
        Path meet = tmp.resolve("meet6");
        FileArrivalWatcher w = watcher(meet);

        assertThat(w.isArrived(meetTarget())).isFalse();
        Files.write(meet.resolve("mock_meet_001.m4a"), SilentWav.of(1));
        assertThat(w.isArrived(meetTarget())).isTrue();
    }

    // ── 지난 배치의 잔재 ──────────────────────────────────────────────────
    // [초기화] → [생성] → [실행] 이 '수신 파일 대기 타임아웃' 으로 끝나던 자리.
    // 수집은 언제나 "요청 → 대기" 순서라, 요청 전에 그 이름이 있으면 그것은 옛 파일이다.
    //
    // 대기 중에 "요청 시각보다 오래된 파일" 을 골라내는 방식은 쓰지 않는다. 벽시계와
    // 파일시스템 mtime 을 비교하는 셈인데, mtime 해상도가 거친 파일시스템에서는 방금 쓴
    // 파일이 요청 시각보다 이전으로 보여 갓 만들어진 파일을 지워 버린다(CI 에서 그랬다).

    @Test
    @DisplayName("요청 전에 남아 있던 파일을 치운다 — 그 이름이 비어야 브로커가 새로 만들 수 있다")
    void clearStaleRemovesLeftover() throws Exception {
        Path meet = tmp.resolve("meet-stale");
        FileArrivalWatcher w = watcher(meet);
        Path leftover = meet.resolve("mock_meet_001.m4a");
        Files.write(leftover, SilentWav.of(1));

        assertThat(w.clearStale(meetTarget())).isTrue();
        assertThat(leftover).doesNotExist();
    }

    @Test
    @DisplayName("치울 것이 없으면 아무 일도 하지 않는다")
    void clearStaleIsQuietWhenNothingIsThere() throws Exception {
        Path meet = tmp.resolve("meet-clean");
        FileArrivalWatcher w = watcher(meet);

        assertThat(w.clearStale(meetTarget())).isFalse();
    }

    @Test
    @DisplayName("치운 뒤에 온 파일은 그대로 받는다 — 잔재 정리가 새 파일을 잡아먹으면 안 된다")
    void acceptsFileWrittenAfterClearing() throws Exception {
        Path meet = tmp.resolve("meet-fresh");
        FileArrivalWatcher w = watcher(meet);
        Files.write(meet.resolve("mock_meet_001.m4a"), SilentWav.of(1));
        w.clearStale(meetTarget());
        // 브로커가 같은 이름으로 새로 만든다
        Files.write(meet.resolve("mock_meet_001.m4a"), SilentWav.of(1));

        VoiceFile f = w.await(meetTarget(), "mock_meet_001.m4a");

        assertThat(f.sizeBytes()).isPositive();
    }

    @Test
    @DisplayName("타임아웃 메시지가 폴더 현황을 말한다 — '기다렸다'만 적으면 원인을 못 찾는다")
    void timeoutMessageShowsWhatIsInTheDirectory() throws Exception {
        Path meet = tmp.resolve("meet-diag");
        FileArrivalWatcher w = watcher(meet);
        Files.write(meet.resolve("엉뚱한이름.m4a"), SilentWav.of(1));

        assertThatThrownBy(() -> w.await(meetTarget(), "mock_meet_001.m4a"))
                .hasMessageContaining("폴더 현황")
                .hasMessageContaining("엉뚱한이름.m4a");
    }

    @Test
    @DisplayName("폴더가 비었으면 그렇게 말한다 — 아무도 파일을 만들지 않았다는 뜻")
    void timeoutMessageSaysEmpty() throws Exception {
        Path meet = tmp.resolve("meet-empty");
        FileArrivalWatcher w = watcher(meet);

        assertThatThrownBy(() -> w.await(meetTarget(), "mock_meet_001.m4a"))
                .hasMessageContaining("비어 있음");
    }

    @Test
    @DisplayName("파일 시각이 과거로 보여도 받는다 — 도착 판정에 시계를 쓰지 않는다")
    void acceptsFileWhoseTimestampLooksOld() throws Exception {
        // 왜 이 테스트가 있는가: 한때 '요청 시각보다 오래된 파일' 을 잔재로 보고 지웠다. 벽시계와
        //   파일시스템 mtime 을 비교하는 방식이었는데, 컨테이너의 overlayfs 처럼 mtime 해상도가
        //   거친 곳에서는 방금 쓴 파일의 mtime 이 내림되어 요청 시각보다 이전으로 보인다.
        //   그래서 갓 만들어진 파일을 지워 버렸고 수집 단계가 대기 타임아웃으로 죽었다
        //   (Jenkins, COLLECT errCnt=5 · elapsedSec=75 = 15초 × 5건).
        //
        //   ⚠ 이 테스트가 그 사고를 그대로 재현하지는 못한다. 당시 검사는 호출 측이 시각을
        //   넘길 때만 켜졌고 여기서 쓰는 2-인자 호출은 꺼져 있었다 — 실제로 잡아낸 것은
        //   Linux 에서 돌린 E2E 였다. 여기서 지키는 것은 "도착 판정이 시계를 보지 않는다" 는
        //   성질이고, 누군가 그 비교를 되살리면 이 테스트가 막는다.
        Path meet = tmp.resolve("meet-coarse");
        FileArrivalWatcher w = watcher(meet);
        Path file = meet.resolve("mock_meet_001.m4a");
        Files.write(file, SilentWav.of(1));
        long flooredToSecond = (System.currentTimeMillis() / 1000L) * 1000L;
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(flooredToSecond));

        VoiceFile f = w.await(meetTarget(), "mock_meet_001.m4a");

        assertThat(f.sizeBytes()).as("mtime 이 내림됐다는 이유로 버리면 안 된다").isPositive();
    }
}
