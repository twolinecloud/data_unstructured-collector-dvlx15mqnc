package egovframework.unstructured.collector.voice.stt.genon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * PCM WAV — 이어 붙이기(시험 음원) · 나누기(제논 600초 한도). 조각 수는 최소 · 길이는 고르게 · 경계는 가장 조용한 20ms ·
 * 조각을 다시 이으면 원본과 바이트가 같다.
 *
 * <p>나누기 시험은 표본 빈도를 1kHz 로 낮춰 30분짜리도 3.6MB 로 만든다 — 규칙은 빈도와 상관없다.</p>
 */
class PcmWavTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("이어 붙이기 — 합성 낭독 22초를 되풀이해 정확히 그 길이(16kHz · 모노 · 16bit · 머리 44B)")
    void tileMakesExactLength() throws IOException {
        Path out = dir.resolve("tiled.wav");
        PcmWav.Info info;
        try (InputStream in = asset()) {
            info = PcmWav.tile(in, out, 61.5);
        }
        assertThat(info.rate()).isEqualTo(16_000);
        assertThat(info.channels()).isEqualTo(1);
        assertThat(info.dataLen()).isEqualTo(Math.round(61.5 * 16_000) * 2);
        assertThat(Files.size(out)).isEqualTo(44 + info.dataLen());

        PcmWav.Info back = PcmWav.read(out);
        assertThat(back.dataOffset()).isEqualTo(44);
        assertThat(back.durationSec()).isCloseTo(61.5, within(0.001));
        AudioInspector.Check c = AudioInspector.check(out, null, null, 20L * 1024 * 1024, 600);
        assertThat(c.ok()).isTrue();
        assertThat(c.info().codec()).isEqualTo("pcm_s16le");
        assertThat(c.info().durationSec()).isCloseTo(61.5, within(0.01));

        // 원본 표본이 그대로 되풀이된다 — 22초 지점부터 다시 처음 표본
        byte[] all = Files.readAllBytes(out);
        PcmWav.Info src;
        Path srcFile = dir.resolve("src.wav");
        try (InputStream in = asset()) {
            Files.copy(in, srcFile);
        }
        src = PcmWav.read(srcFile);
        byte[] srcPcm = Arrays.copyOfRange(Files.readAllBytes(srcFile), (int) src.dataOffset(),
                (int) (src.dataOffset() + src.dataLen()));
        assertThat(Arrays.copyOfRange(all, 44, 44 + 4000)).isEqualTo(Arrays.copyOf(srcPcm, 4000));
        assertThat(Arrays.copyOfRange(all, 44 + srcPcm.length, 44 + srcPcm.length + 4000))
                .isEqualTo(Arrays.copyOf(srcPcm, 4000));
        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).noneMatch(n -> n.startsWith("tile-src"));
        }
    }

    @Test
    @DisplayName("조각 수 — 600초는 하나 · 601초는 둘(고르게) · 20분은 600 × 2 · 30분은 600 × 3 · 어떤 조각도 600초를 넘지 않는다")
    void planUsesFewestEvenPieces() throws IOException {
        assertThat(durations(plan(600, 600))).containsExactly(600.0);
        List<Double> d601 = durations(plan(601, 600));
        assertThat(d601).hasSize(2).allSatisfy(s -> assertThat(s).isBetween(285.0, 316.0));
        assertThat(d601.stream().mapToDouble(Double::doubleValue).sum()).isCloseTo(601.0, within(0.001));
        assertThat(durations(plan(1200, 600))).containsExactly(600.0, 600.0);
        assertThat(durations(plan(1800, 600))).containsExactly(600.0, 600.0, 600.0);
        assertThat(durations(plan(1500, 600))).hasSize(3).allSatisfy(s -> assertThat(s).isLessThanOrEqualTo(600.0));
        assertThat(durations(plan(1800, 590))).hasSize(4).allSatisfy(s -> assertThat(s).isLessThanOrEqualTo(590.0));
        assertThat(durations(plan(20, 600))).containsExactly(20.0);
    }

    @Test
    @DisplayName("경계 — 고른 자리 ±15초 안에서 가장 조용한 20ms 로 옮긴다(말 한가운데를 자르지 않게)")
    void cutsAtQuietestFrame() throws IOException {
        // 1000초 · 둘로 나누면 고른 자리 500초 — 507초에 0.1초 무음을 두면 그 자리에서 잘라야 한다
        Path f = noiseWav("quiet.wav", 1000, 1_000, 507.0);
        PcmWav.Info info = PcmWav.read(f);
        List<PcmWav.Piece> pieces = PcmWav.plan(f, info, 600, 15);
        assertThat(pieces).hasSize(2);
        assertThat(pieces.get(0).endSec()).isBetween(507.0, 507.1);
        assertThat(pieces.get(1).startSec()).isEqualTo(pieces.get(0).endSec());
        assertThat(pieces.get(1).endSec()).isEqualTo(1000.0);

        // 무음이 옮길 폭 밖(530초)이면 폭 안에서 가장 조용한 곳 — 어쨌든 485~515초 안
        Path g = noiseWav("far.wav", 1000, 1_000, 530.0);
        List<PcmWav.Piece> p2 = PcmWav.plan(g, PcmWav.read(g), 600, 15);
        assertThat(p2.get(0).endSec()).isBetween(485.0, 515.0);
    }

    @Test
    @DisplayName("쓰기 — 조각마다 독립된 WAV(머리 44B) · 조각 표본을 이으면 원본과 바이트가 같다")
    void piecesConcatenateToOriginal() throws IOException {
        Path f = noiseWav("whole.wav", 1250, 1_000, -1);
        PcmWav.Info info = PcmWav.read(f);
        List<PcmWav.Piece> pieces = PcmWav.plan(f, info, 600, 15);
        assertThat(pieces).hasSize(3);
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        for (PcmWav.Piece p : pieces) {
            Path part = dir.resolve("part" + p.index() + ".wav");
            PcmWav.write(f, info, p, part);
            PcmWav.Info pi = PcmWav.read(part);
            assertThat(pi.dataOffset()).isEqualTo(44);
            assertThat(pi.durationSec()).isCloseTo(p.durationSec(), within(0.002)).isLessThanOrEqualTo(600.0);
            byte[] b = Files.readAllBytes(part);
            joined.write(b, 44, b.length - 44);
        }
        byte[] orig = Files.readAllBytes(f);
        assertThat(joined.toByteArray()).isEqualTo(Arrays.copyOfRange(orig, 44, orig.length));
    }

    @Test
    @DisplayName("반으로 — 가운데 ±15초 안 가장 조용한 곳에서 둘로(제논이 길이로 거절한 조각을 다시 보낼 때)")
    void halveSplitsNearMiddle() throws IOException {
        Path f = noiseWav("halve.wav", 600, 1_000, 296.0);
        PcmWav.Info info = PcmWav.read(f);
        PcmWav.Piece whole = PcmWav.plan(f, info, 600, 15).get(0);
        List<PcmWav.Piece> two = PcmWav.halve(f, info, whole, 15);
        assertThat(two).hasSize(2);
        assertThat(two.get(0).startSec()).isZero();
        assertThat(two.get(0).endSec()).isBetween(296.0, 296.1);
        assertThat(two.get(1).startSec()).isEqualTo(two.get(0).endSec());
        assertThat(two.get(1).endSec()).isEqualTo(600.0);
    }

    @Test
    @DisplayName("PCM 16bit WAV 가 아니면 읽지 않는다(MP3 · 8bit)")
    void rejectsNonPcm16() throws IOException {
        Path mp3 = Files.write(dir.resolve("a.mp3"), AudioSampleGenerator.silentMp3(3));
        assertThrows(IllegalArgumentException.class, () -> PcmWav.read(mp3));
        byte[] h = PcmWav.header(8_000, 1, 100);
        ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN).putShort(34, (short) 8).putShort(32, (short) 1)
                .putInt(28, 8_000);
        Path pcm8 = dir.resolve("b.wav");
        Files.write(pcm8, concat(h, new byte[100]));
        assertThrows(IllegalArgumentException.class, () -> PcmWav.read(pcm8));
    }

    // ------------------------------------------------------------------ 도우미

    private static InputStream asset() {
        InputStream in = PcmWavTest.class.getClassLoader().getResourceAsStream(AudioSampleGenerator.SPEECH_ASSET);
        assertThat(in).as("합성 낭독 자산").isNotNull();
        return in;
    }

    private List<PcmWav.Piece> plan(int seconds, double maxSec) throws IOException {
        Path f = noiseWav("p" + seconds + "_" + (int) maxSec + ".wav", seconds, 1_000, -1);
        return PcmWav.plan(f, PcmWav.read(f), maxSec, 15);
    }

    private static List<Double> durations(List<PcmWav.Piece> pieces) {
        return pieces.stream().map(p -> Math.round(p.durationSec() * 1000d) / 1000d).toList();
    }

    /** 잡음 WAV — {@code quietAtSec} 가 0 이상이면 그 자리부터 0.1초를 무음으로. */
    private Path noiseWav(String name, int seconds, int rate, double quietAtSec) throws IOException {
        int n = seconds * rate;
        ByteBuffer pcm = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN);
        Random rnd = new Random(seconds * 31L + rate);
        int quietFrom = quietAtSec < 0 ? -1 : (int) Math.round(quietAtSec * rate);
        int quietTo = quietFrom + rate / 10;
        for (int i = 0; i < n; i++) {
            boolean quiet = i >= quietFrom && i < quietTo;
            pcm.putShort((short) (quiet ? 0 : 2000 + rnd.nextInt(8000) * (rnd.nextBoolean() ? 1 : -1)));
        }
        Path p = dir.resolve(name);
        Files.write(p, concat(PcmWav.header(rate, 1, (long) n * 2), pcm.array()));
        return p;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
