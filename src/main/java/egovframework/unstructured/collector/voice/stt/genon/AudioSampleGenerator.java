package egovframework.unstructured.collector.voice.stt.genon;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 제논 STT 시험 샘플 — 정상 · 경계 · 미지원을 <b>실제 파일</b>로 만든다(시뮬레이터 11번 탭 · 테스트).
 *
 * <p><b>인코더 없이 만든다</b> — 파드에 ffmpeg 가 없다. WAV 는 PCM 을 그대로 쓰고, MP3 · M4A 는 <b>무음 프레임</b>을
 * 손으로 짠다(MP3 = MPEG-1 Layer III · 48kHz · 32kbps · 모노 빈 프레임, M4A = AAC-LC · 16kHz · 모노 무음 프레임을
 * moov 가 앞에 오는 MP4 상자에 담음). 무음이라 제논은 빈 전사({@code text ""})를 돌려준다 — 포맷을 받는지만 보는
 * 샘플이다. 말소리 확인은 한국어 합성 낭독 22초(레포 고정 자산 · 사람 목소리 아님)로 한다.</p>
 *
 * <p>경계 샘플은 <b>한 가지만 넘게</b> 만든다 — 20MB 초과는 길이가 약 112초라 길이 한도를 통과하고, 600초 초과는
 * 18.6MiB 라 크기 한도를 통과한다. 둘을 섞으면 어느 검사가 막았는지 가릴 수 없다.</p>
 */
public final class AudioSampleGenerator {

    /** 샘플 종류 — 파일 이름은 고정이다(화면이 이름으로 고른다). */
    public enum Kind {
        WAV_3S("normal_3s_16k_mono.wav", "정상 3초 WAV — 16kHz · 16bit · 모노 · 440Hz", true),
        MP3_3S("normal_3s_silent.mp3", "정상 3초 MP3 — 무음 · 48kHz · 32kbps · 모노", true),
        M4A_3S("normal_3s_silent.m4a", "정상 3초 M4A — 무음 · AAC-LC · 16kHz · 모노", true),
        OVER_SIZE("over_size_20_5MiB.wav", "20MB 초과 — 20.5MiB · 48kHz 스테레오 · 약 112초", false),
        OVER_DURATION("over_duration_610s.wav", "600초 초과 — 610초 · 16kHz 모노 · 18.6MiB", false),
        UNSUPPORTED_OGG("unsupported.ogg", "미지원 포맷 — OGG", false),
        UNSUPPORTED_TXT("unsupported.txt", "미지원 포맷 — 텍스트", false),
        SPEECH_22S("speech_ko_22s.wav", "합성 낭독 22초 — 한국어 TTS(인식 확인용)", true);

        private final String fileName;
        private final String label;
        private final boolean expectPass;

        Kind(String fileName, String label, boolean expectPass) {
            this.fileName = fileName;
            this.label = label;
            this.expectPass = expectPass;
        }

        public String fileName() {
            return fileName;
        }

        public String label() {
            return label;
        }

        /** 사전 검증을 통과해야 하는 샘플인지. */
        public boolean expectPass() {
            return expectPass;
        }

        /** 파일 이름으로 찾는다. 없으면 null. */
        public static Kind byFileName(String name) {
            for (Kind k : values()) {
                if (k.fileName.equals(name)) {
                    return k;
                }
            }
            return null;
        }
    }

    /** 20.5 MiB — 상한(20 MiB = 20,971,520 B)을 0.5 MiB 넘긴다. */
    public static final long OVER_SIZE_BYTES = 21_495_808L;

    /** 레포 고정 자산 — Windows 한국어 합성 음성(Heami)으로 만든 22초 낭독. 실제 사람 목소리가 아니다. */
    public static final String SPEECH_ASSET = "static/samples/음성/tts_ko_dialog_22s.wav";

    /** AAC-LC 무음 프레임 — SCE · global_gain 160 · max_sfb 0 · END. 모노 · 1024 표본. */
    private static final byte[] AAC_SILENT_FRAME = {0x01, 0x40, 0x20, 0x07};

    private AudioSampleGenerator() {
    }

    /** 전부 만든다. */
    public static List<Path> writeAll(Path dir) throws IOException {
        List<Path> out = new ArrayList<>();
        for (Kind k : Kind.values()) {
            out.add(write(k, dir));
        }
        return out;
    }

    /**
     * 하나를 만든다 — 임시 이름으로 다 쓴 뒤 옮긴다(쓰는 도중의 파일을 화면이 집어 가지 않게).
     *
     * @return 만든 파일
     */
    public static Path write(Kind kind, Path dir) throws IOException {
        Files.createDirectories(dir);
        Path out = dir.resolve(kind.fileName());
        Path tmp = dir.resolve(kind.fileName() + ".tmp");
        switch (kind) {
            case WAV_3S -> Files.write(tmp, sineWav(3, 16_000, 440d));
            case MP3_3S -> Files.write(tmp, silentMp3(3));
            case M4A_3S -> Files.write(tmp, silentM4a(3));
            case OVER_SIZE -> writeSilentWav(tmp, 48_000, 2, OVER_SIZE_BYTES - 44);
            case OVER_DURATION -> writeSilentWav(tmp, 16_000, 1, 16_000L * 2 * 610);
            case UNSUPPORTED_OGG -> Files.write(tmp, fakeOgg());
            case UNSUPPORTED_TXT -> Files.writeString(tmp,
                    "이 파일은 오디오가 아닙니다 — 제논 STT 지원 포맷(WAV · MP3 · M4A) 검증용 텍스트입니다.\n",
                    StandardCharsets.UTF_8);
            case SPEECH_22S -> {
                try (InputStream in = AudioSampleGenerator.class.getClassLoader().getResourceAsStream(SPEECH_ASSET)) {
                    if (in == null) {
                        throw new IOException("합성 낭독 자산이 없다 — " + SPEECH_ASSET);
                    }
                    Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            default -> throw new IllegalArgumentException("모르는 샘플: " + kind);
        }
        try {
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
        }
        return out;
    }

    // ------------------------------------------------------------------ WAV

    /** 사인파 WAV — 16bit 모노. 앞뒤 20ms 는 서서히 키우고 줄인다(딱 끊기면 '틱' 소리가 난다). */
    public static byte[] sineWav(int seconds, int rate, double freq) {
        int n = seconds * rate;
        ByteBuffer bb = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN);
        wavHeader(bb, rate, 1, n * 2L);
        double fade = rate * 0.02;
        for (int i = 0; i < n; i++) {
            double env = Math.min(1d, Math.min(i, n - 1 - i) / fade);
            bb.putShort((short) Math.round(Math.sin(2 * Math.PI * freq * i / rate) * 0.3 * 32767 * env));
        }
        return bb.array();
    }

    /**
     * 무음 WAV 를 흘려 쓴다 — 20MB 를 메모리에 올리지 않는다. 0 을 직접 쓴다
     * ({@code setLength} 로 늘린 부분은 내용이 정의되지 않는다).
     *
     * @param dataLen data 청크 바이트 수(채널 × 2 의 배수)
     */
    public static void writeSilentWav(Path out, int rate, int channels, long dataLen) throws IOException {
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        wavHeader(h, rate, channels, dataLen);
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(out), 1 << 16)) {
            os.write(h.array());
            byte[] zero = new byte[1 << 16];
            for (long left = dataLen; left > 0; left -= zero.length) {
                os.write(zero, 0, (int) Math.min(zero.length, left));
            }
        }
    }

    private static void wavHeader(ByteBuffer bb, int rate, int channels, long dataLen) {
        bb.put(ascii("RIFF")).putInt((int) (36 + dataLen)).put(ascii("WAVE"))
                .put(ascii("fmt ")).putInt(16).putShort((short) 1).putShort((short) channels).putInt(rate)
                .putInt(rate * channels * 2).putShort((short) (channels * 2)).putShort((short) 16)
                .put(ascii("data")).putInt((int) dataLen);
    }

    // ------------------------------------------------------------------ MP3

    /**
     * 무음 MP3 — MPEG-1 Layer III · 48kHz · 32kbps · 모노 · CRC 없음. 프레임 하나가 96 바이트 · 1152 표본(24ms)이다.
     * 헤더 뒤가 전부 0 이라 사이드 정보의 part2_3_length 가 0 — 디코더가 무음으로 푼다. 고정 비트레이트라
     * 길이는 (바이트 × 8 ÷ 32000)초다.
     */
    public static byte[] silentMp3(double seconds) {
        int frames = (int) Math.ceil(seconds * 48_000 / 1152d);
        byte[] out = new byte[frames * 96];
        for (int f = 0; f < frames; f++) {
            int o = f * 96;
            out[o] = (byte) 0xFF;
            out[o + 1] = (byte) 0xFB;   // MPEG-1 · Layer III · CRC 없음
            out[o + 2] = (byte) 0x14;   // 32kbps · 48kHz · 채움 없음
            out[o + 3] = (byte) 0xC0;   // 모노
        }
        return out;
    }

    // ------------------------------------------------------------------ M4A

    /**
     * 무음 M4A — ftyp(M4A) · moov · mdat 순. moov 를 앞에 둬야 길이를 읽는 쪽이 파일 끝까지 가지 않는다.
     * stco(데이터 위치)가 moov 크기에 달렸으므로 moov 를 두 번 짠다 — 값만 바뀌고 크기는 같다.
     */
    public static byte[] silentM4a(double seconds) {
        int rate = 16_000;
        int frames = (int) Math.ceil(seconds * rate / 1024d);
        long samples = frames * 1024L;
        byte[] ftyp = box("ftyp", cat(ascii("M4A "), be32(0x200), ascii("M4A "), ascii("mp42"), ascii("isom")));
        byte[] moov = moov(rate, frames, samples, 0);
        long dataOffset = ftyp.length + moov.length + 8L;
        moov = moov(rate, frames, samples, dataOffset);
        ByteArrayOutputStream data = new ByteArrayOutputStream(frames * AAC_SILENT_FRAME.length);
        for (int i = 0; i < frames; i++) {
            data.writeBytes(AAC_SILENT_FRAME);
        }
        return cat(ftyp, moov, box("mdat", data.toByteArray()));
    }

    private static byte[] moov(int rate, int frames, long samples, long dataOffset) {
        long durMs = Math.round(samples * 1000d / rate);
        byte[] matrix = cat(be32(0x00010000), be32(0), be32(0), be32(0), be32(0x00010000), be32(0),
                be32(0), be32(0), be32(0x40000000));
        byte[] mvhd = fullBox("mvhd", 0, cat(be32(0), be32(0), be32(1000), be32(durMs), be32(0x00010000),
                be16(0x0100), new byte[10], matrix, new byte[24], be32(2)));
        byte[] tkhd = fullBox("tkhd", 0x000003, cat(be32(0), be32(0), be32(1), be32(0), be32(durMs), new byte[8],
                be16(0), be16(0), be16(0x0100), be16(0), matrix, be32(0), be32(0)));
        byte[] mdhd = fullBox("mdhd", 0, cat(be32(0), be32(0), be32(rate), be32(samples), be16(0x55C4), be16(0)));
        byte[] hdlr = fullBox("hdlr", 0, cat(be32(0), ascii("soun"), new byte[12], ascii("SoundHandler"), new byte[1]));
        byte[] smhd = fullBox("smhd", 0, cat(be16(0), be16(0)));
        byte[] dinf = box("dinf", fullBox("dref", 0, cat(be32(1), fullBox("url ", 1, new byte[0]))));

        int avgBps = (int) Math.round(AAC_SILENT_FRAME.length * 8d * rate / 1024d);
        byte[] dsi = {0x05, 0x02, 0x14, 0x08};   // AudioSpecificConfig — AAC-LC · 16kHz · 모노
        byte[] dcd = cat(new byte[]{0x04, 0x11, 0x40, 0x15}, new byte[]{0x00, 0x03, 0x00}, be32(avgBps), be32(avgBps), dsi);
        byte[] esd = cat(new byte[]{0x03, 0x19, 0x00, 0x01, 0x00}, dcd, new byte[]{0x06, 0x01, 0x02});
        byte[] esds = fullBox("esds", 0, esd);
        byte[] mp4a = box("mp4a", cat(new byte[6], be16(1), new byte[8], be16(1), be16(16), be16(0), be16(0),
                be32((long) rate << 16), esds));
        byte[] stsd = fullBox("stsd", 0, cat(be32(1), mp4a));
        byte[] stts = fullBox("stts", 0, cat(be32(1), be32(frames), be32(1024)));
        byte[] stsc = fullBox("stsc", 0, cat(be32(1), be32(1), be32(frames), be32(1)));
        byte[] stsz = fullBox("stsz", 0, cat(be32(AAC_SILENT_FRAME.length), be32(frames)));
        byte[] stco = fullBox("stco", 0, cat(be32(1), be32(dataOffset)));
        byte[] stbl = box("stbl", cat(stsd, stts, stsc, stsz, stco));
        byte[] minf = box("minf", cat(smhd, dinf, stbl));
        byte[] mdia = box("mdia", cat(mdhd, hdlr, minf));
        byte[] trak = box("trak", cat(tkhd, mdia));
        return box("moov", cat(mvhd, trak));
    }

    // ------------------------------------------------------------------ OGG

    /**
     * OGG 처럼 보이는 파일 — 첫 페이지 머리(OggS · BOS)와 Vorbis 식별 머리 모양만 있다. CRC 를 채우지 않았으므로
     * 재생은 안 된다. 사전 검증이 내용(매직 넘버)으로 OGG 를 알아보는지 보려는 샘플이다.
     */
    static byte[] fakeOgg() {
        byte[] ident = cat(new byte[]{0x01}, ascii("vorbis"), new byte[4], new byte[]{0x01},
                le32(16_000), le32(0), le32(32_000), le32(0), new byte[]{(byte) 0xB8, 0x01});
        byte[] page = cat(ascii("OggS"), new byte[]{0x00, 0x02}, new byte[8], le32(0x47454E4F), le32(0), le32(0),
                new byte[]{0x01, (byte) ident.length});
        return cat(page, ident, new byte[512]);
    }

    // ------------------------------------------------------------------ 바이트

    private static byte[] box(String type, byte[] payload) {
        return cat(be32(8L + payload.length), ascii(type), payload);
    }

    private static byte[] fullBox(String type, int flags, byte[] payload) {
        return box(type, cat(new byte[]{0x00, (byte) (flags >> 16), (byte) (flags >> 8), (byte) flags}, payload));
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            o.writeBytes(p);
        }
        return o.toByteArray();
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] be32(long v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    private static byte[] be16(int v) {
        return new byte[]{(byte) (v >>> 8), (byte) v};
    }

    private static byte[] le32(int v) {
        return new byte[]{(byte) v, (byte) (v >>> 8), (byte) (v >>> 16), (byte) (v >>> 24)};
    }
}
