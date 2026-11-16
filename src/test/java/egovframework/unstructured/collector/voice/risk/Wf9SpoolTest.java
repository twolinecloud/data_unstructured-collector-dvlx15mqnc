package egovframework.unstructured.collector.voice.risk;

import egovframework.unstructured.collector.common.model.VoiceTarget;
import egovframework.unstructured.collector.voice.stt.genon.AudioSampleGenerator;
import egovframework.unstructured.collector.voice.stt.genon.GenonSttProperties;
import egovframework.unstructured.collector.voice.stt.genon.PcmWav;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static egovframework.unstructured.collector.voice.risk.Wf9TestSupport.meet;
import static egovframework.unstructured.collector.voice.risk.Wf9TestSupport.phone;
import static org.assertj.core.api.Assertions.assertThat;

/** WF9 스풀 — 넣기(배치를 멈추지 않는다) · 중복 · 상한 · 앞 600초(D9) · 꺼내기 · 정리. 음원은 합성 음이다. */
class Wf9SpoolTest {

    @TempDir
    Path tmp;

    private Path root;
    private Path audio;
    private final AtomicReference<String> blocked = new AtomicReference<>();
    private final Wf9TestSupport.MutableClock clock = new Wf9TestSupport.MutableClock(Instant.parse("2026-11-16T03:00:00Z"));

    @BeforeEach
    void setUp() throws Exception {
        root = tmp.resolve("xvram/wf9-spool");
        audio = Files.write(tmp.resolve("decrypted_m.wav"), AudioSampleGenerator.sineWav(3, 8000, 440));
    }

    private Wf9Spool spool(Wf9TestSupport.Props p) {
        return spool(p, Wf9TestSupport.genon());
    }

    private Wf9Spool spool(Wf9TestSupport.Props p, GenonSttProperties genon) {
        return new Wf9Spool(p.build(), () -> root, genon, blocked::get, clock);
    }

    @Test
    @DisplayName("넣기 — 사본 · job.json(대체 ID · 일시 · 구분) · 같은 녹음은 대기 중 한 번만")
    void offerWritesJob() throws Exception {
        Wf9Spool s = spool(new Wf9TestSupport.Props());

        assertThat(s.offer("E1", meet("TARE0000000001"), audio, false)).isTrue();
        assertThat(s.offer("E2", meet("TARE0000000001"), audio, false)).as("대기 중 중복").isFalse();
        assertThat(s.offer("E1", phone("VRFC0000000001"), audio, false)).isTrue();

        List<Wf9Spool.Entry> list = s.list();
        assertThat(list).hasSize(2);
        Wf9Job j = list.stream().map(Wf9Spool.Entry::job).filter(x -> "MEET".equals(x.kind())).findFirst().orElseThrow();
        assertThat(j.recFileId()).isEqualTo("TARE0000000001");
        assertThat(j.corrNo()).isEqualTo("2026000123");
        assertThat(j.execId()).isEqualTo("E1");
        assertThat(j.occurredLocal()).isEqualTo("2026-11-16T10:30");
        assertThat(j.contactType()).isEqualTo("접견");
        assertThat(j.contactSeCd()).isEqualTo("VISIT");
        assertThat(j.contactId()).startsWith("C-").hasSize(34).doesNotContain("TARE").doesNotContain("2026000123");
        assertThat(j.cutSec()).isNull();
        assertThat(j.attempts()).isZero();
        assertThat(j.createdAtMs()).isEqualTo(clock.millis());
        Path dir = list.stream().filter(e -> e.job() == j).findFirst().orElseThrow().dir();
        assertThat(Files.readAllBytes(dir.resolve(Wf9Spool.AUDIO_FILE))).isEqualTo(Files.readAllBytes(audio));
        Wf9Job p = list.stream().map(Wf9Spool.Entry::job).filter(x -> "PHONE".equals(x.kind())).findFirst().orElseThrow();
        assertThat(p.contactType()).isEqualTo("전화");
        assertThat(p.contactSeCd()).isEqualTo("CALL");
        assertThat(p.occurredLocal()).isEqualTo("2026-11-16T14:05:07");
        assertThat(Files.readString(dir.resolve(Wf9Spool.JOB_FILE))).as("job.json 에 계정 · 토큰 같은 것은 없다")
                .doesNotContain("password").doesNotContain("token");
    }

    @Test
    @DisplayName("넣지 않는 경우 — 꺼짐 · 시험 실행 · 막힘 · 빈 값 · 음성 없음 · 위치를 못 정함(예외 없이 false)")
    void offerSkips() throws Exception {
        Wf9TestSupport.Props off = new Wf9TestSupport.Props();
        off.enabled = false;
        assertThat(spool(off).offer("E", meet("A1"), audio, false)).isFalse();
        assertThat(Files.exists(root)).isFalse();

        Wf9Spool s = spool(new Wf9TestSupport.Props());
        assertThat(s.offer("E", meet("A2"), audio, true)).as("시험 실행").isFalse();
        blocked.set("에이전트 커넥터가 MOCK 모드");
        assertThat(s.offer("E", meet("A3"), audio, false)).as("막힘").isFalse();
        blocked.set(null);
        VoiceTarget noCorr = new VoiceTarget(meet("A4").kind(), " ", "A4", null, null, false, null, null, null, null,
                meet("A4").occurredAt());
        assertThat(s.offer("E", noCorr, audio, false)).isFalse();
        VoiceTarget noTime = new VoiceTarget(meet("A5").kind(), "2026000123", "A5", null, null, false, null, null, null, null, null);
        assertThat(s.offer("E", noTime, audio, false)).isFalse();
        assertThat(s.offer("E", meet("A6"), tmp.resolve("none.wav"), false)).isFalse();
        assertThat(s.list()).isEmpty();

        Wf9TestSupport.Props p = new Wf9TestSupport.Props();
        p.skipTestRuns = false;
        assertThat(spool(p).offer("TST", meet("A7"), audio, true)).as("시험 실행도 넣게 설정").isTrue();

        Wf9Spool broken = new Wf9Spool(new Wf9TestSupport.Props().build(), () -> {
            throw new IllegalStateException("작업 폴더 없음");
        }, Wf9TestSupport.genon(), () -> null, clock);
        assertThat(broken.offer("E", meet("A8"), audio, false)).as("예외를 던지지 않는다").isFalse();
    }

    @Test
    @DisplayName("상한 — 작업 수 · 디스크(MB)를 넘으면 넣지 않는다")
    void capacity() throws Exception {
        Wf9TestSupport.Props one = new Wf9TestSupport.Props();
        one.maxPending = 1;
        Wf9Spool s = spool(one);
        assertThat(s.offer("E", meet("B1"), audio, false)).isTrue();
        assertThat(s.offer("E", meet("B2"), audio, false)).isFalse();

        root = tmp.resolve("other");
        Wf9TestSupport.Props zero = new Wf9TestSupport.Props();
        zero.maxSpoolMb = 0;
        assertThat(spool(zero).offer("E", meet("B3"), audio, false)).isFalse();
    }

    @Test
    @DisplayName("D9 — 한도보다 긴 PCM WAV 는 앞부분만 담는다(길이 · 크기 한도 중 짧은 쪽)")
    void longWavCut() throws Exception {
        Path five = Files.write(tmp.resolve("five.wav"), AudioSampleGenerator.sineWav(5, 8000, 440));
        Wf9TestSupport.Props p = new Wf9TestSupport.Props();
        p.analyzeMaxSec = 2;
        Wf9Spool s = spool(p);

        assertThat(s.offer("E", meet("C1"), five, false)).isTrue();
        Wf9Spool.Entry e = s.list().get(0);
        assertThat(e.job().cutSec()).isEqualTo(2.0);
        PcmWav.Info info = PcmWav.read(e.dir().resolve(Wf9Spool.AUDIO_FILE));
        assertThat(info.durationSec()).isEqualTo(2.0);
        assertThat(info.rate()).isEqualTo(8000);

        root = tmp.resolve("by-size");
        long oneSecond = 44 + 8000 * 2;
        Wf9Spool small = spool(new Wf9TestSupport.Props(),
                Wf9TestSupport.genon(GenonSttProperties.Mode.MOCK, "http://127.0.0.1:1", oneSecond, 600));
        assertThat(small.offer("E", meet("C2"), five, false)).isTrue();
        Wf9Spool.Entry e2 = small.list().get(0);
        assertThat(e2.job().cutSec()).isEqualTo(1.0);
        assertThat(Files.size(e2.dir().resolve(Wf9Spool.AUDIO_FILE))).isEqualTo(oneSecond);
    }

    @Test
    @DisplayName("한도 밖인데 자를 수 없는 형식(MP3 610초)은 넣지 않는다 — 넣다 만 폴더도 남기지 않는다")
    void uncuttableSkipped() throws Exception {
        Path mp3 = Files.write(tmp.resolve("long.mp3"), AudioSampleGenerator.silentMp3(610));
        Path ok = Files.write(tmp.resolve("short.mp3"), AudioSampleGenerator.silentMp3(30));
        Wf9Spool s = spool(new Wf9TestSupport.Props());

        assertThat(s.offer("E", meet("D1"), mp3, false)).isFalse();
        assertThat(s.offer("E", meet("D2"), ok, false)).as("한도 안 MP3 는 통째로").isTrue();
        try (var files = Files.list(root)) {
            assertThat(files.map(f -> f.getFileName().toString()).toList()).hasSize(1).noneMatch(n -> n.startsWith("."));
        }
        assertThat(s.list().get(0).job().cutSec()).isNull();
    }

    @Test
    @DisplayName("꺼내기 — 넣은 순서 · 못 읽는 작업은 job=null · 고쳐 쓰기 · 지우기 · 넣다 만 폴더 정리")
    void listSaveDeleteSweep() throws Exception {
        Wf9Spool s = spool(new Wf9TestSupport.Props());
        s.offer("E", meet("F2"), audio, false);
        clock.advanceSec(-60);
        s.offer("E", meet("F1"), audio, false);
        clock.advanceSec(60);
        Path junk = Files.createDirectories(root.resolve("meet-junk"));
        Files.writeString(junk.resolve(Wf9Spool.JOB_FILE), "{깨짐");
        Path oldTmp = Files.createDirectories(root.resolve(Wf9Spool.TMP_PREFIX + "old"));
        Files.setLastModifiedTime(oldTmp, FileTime.fromMillis(clock.millis() - Wf9Spool.TMP_STALE_MS - 1000));
        Path newTmp = Files.createDirectories(root.resolve(Wf9Spool.TMP_PREFIX + "new"));
        Files.setLastModifiedTime(newTmp, FileTime.fromMillis(clock.millis()));

        List<Wf9Spool.Entry> list = s.list();
        assertThat(list).hasSize(3);
        assertThat(list.get(0).job()).as("못 읽는 것이 맨 앞").isNull();
        assertThat(list.get(1).job().recFileId()).isEqualTo("F1");
        assertThat(list.get(2).job().recFileId()).isEqualTo("F2");

        Wf9Spool.Entry f1 = list.get(1);
        s.save(f1, f1.job().failed(clock.millis() + 1000, "[DEID] 커넥터 비식별 HTTP 503"));
        Wf9Job again = s.list().get(1).job();
        assertThat(again.attempts()).isEqualTo(1);
        assertThat(again.nextAttemptAtMs()).isEqualTo(clock.millis() + 1000);
        assertThat(again.lastError()).isEqualTo("[DEID] 커넥터 비식별 HTTP 503");
        assertThat(again.contactId()).isEqualTo(f1.job().contactId());

        assertThat(s.delete(list.get(0))).isTrue();
        assertThat(s.sweepTemp()).isEqualTo(1);
        assertThat(Files.exists(oldTmp)).isFalse();
        assertThat(Files.exists(newTmp)).isTrue();
        assertThat(s.list()).hasSize(2);
    }

    @Test
    @DisplayName("폴더 이름 — 같은 녹음은 같은 이름 · 위험한 글자는 다듬고 해시로 겹치지 않게 · 접견/전화는 다른 이름")
    void jobIds() {
        assertThat(Wf9Spool.jobIdOf(meet("TARE01"))).isEqualTo(Wf9Spool.jobIdOf(meet("TARE01")));
        assertThat(Wf9Spool.jobIdOf(meet("TARE01"))).isNotEqualTo(Wf9Spool.jobIdOf(phone("TARE01")));
        String odd = Wf9Spool.jobIdOf(meet("../a b/c"));
        assertThat(odd).matches("meet-[A-Za-z0-9_.-]+-[0-9a-f]{10}").doesNotContain("/");
        assertThat(Wf9Spool.jobIdOf(meet("a/b"))).isNotEqualTo(Wf9Spool.jobIdOf(meet("a_b")));
    }
}
