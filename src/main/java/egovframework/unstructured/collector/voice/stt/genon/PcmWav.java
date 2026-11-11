package egovframework.unstructured.collector.voice.stt.genon;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * PCM WAV 다루기 — <b>인코더 없이</b> 길게 이어 붙이고(시험 음원) 600초 한도 아래로 나눈다(제논 분할 전사).
 *
 * <p>파드에 ffmpeg 가 없다. 그래서 다루는 것은 <b>PCM 16bit WAV</b> 하나다 — 표본을 그대로 복사하면 되므로 자르는 자리만
 * 정하면 된다. MP3 · M4A 는 프레임 · 상자 구조를 다시 짜야 하므로 나누지 않는다(제논으로 보내기 전 사전 검증이 막는다).</p>
 *
 * <p><b>나누는 규칙</b>({@link #plan}) — 조각 수는 한도로 덮을 수 있는 가장 적은 수({@code ceil(전체 ÷ 한도)})이고, 길이는 고르게 나눈다
 * (20분 → 600초 × 2 · 30분 → 600초 × 3). 경계는 고른 자리 ±{@code searchSec} 안에서 <b>가장 조용한 20ms</b>로 옮긴다 — 말 한가운데를
 * 자르면 앞뒤 조각이 같은 낱말을 반씩 듣는다. 어떤 조각도 한도를 넘지 않게 옮길 수 있는 폭만 쓴다(30분을 600초 셋으로 나누면 옮길 폭이 없다).</p>
 */
public final class PcmWav {

    /** 경계를 고르는 창 — 20ms. 16kHz 모노면 320 표본. */
    static final double FRAME_SEC = 0.02;

    private PcmWav() {
    }

    /**
     * WAV 머리 — data 청크의 자리와 길이까지.
     *
     * @param dataOffset data 청크 본문이 시작하는 바이트
     * @param dataLen    data 청크 바이트 수(파일이 잘렸으면 실제로 있는 만큼)
     */
    public record Info(int rate, int channels, int bits, long dataOffset, long dataLen) {

        /** 표본 하나(모든 채널)의 바이트 수. */
        public int blockAlign() {
            return channels * bits / 8;
        }

        public long bytesPerSec() {
            return (long) rate * blockAlign();
        }

        public double durationSec() {
            return (double) dataLen / bytesPerSec();
        }
    }

    /**
     * 조각 하나 — data 청크 안의 바이트 구간 {@code [from, to)}.
     *
     * @param index 0 부터
     */
    public record Piece(int index, long from, long to, double startSec, double endSec) {

        public double durationSec() {
            return endSec - startSec;
        }
    }

    // ------------------------------------------------------------------ 읽기

    /**
     * 머리를 읽는다 — RIFF 청크를 따라가며 fmt · data 를 찾는다(fmt 가 18바이트이거나 LIST 가 끼어 있어도 된다).
     *
     * @throws IllegalArgumentException PCM 16bit WAV 가 아니다
     */
    public static Info read(Path file) throws IOException {
        try (SeekableByteChannel ch = Files.newByteChannel(file)) {
            long size = ch.size();
            byte[] riff = readAt(ch, 0, 12);
            if (riff.length < 12 || !ascii(riff, 0, "RIFF") || !ascii(riff, 8, "WAVE")) {
                throw new IllegalArgumentException("WAV(RIFF/WAVE) 가 아니다");
            }
            int fmtTag = -1;
            int channels = 0;
            int rate = 0;
            int bits = 0;
            long pos = 12;
            for (int guard = 0; pos + 8 <= size && guard < 4096; guard++) {
                byte[] h = readAt(ch, pos, 8);
                String id = new String(h, 0, 4, StandardCharsets.US_ASCII);
                long len = u32(h, 4);
                long body = pos + 8;
                if ("fmt ".equals(id)) {
                    byte[] f = readAt(ch, body, (int) Math.min(len, 40));
                    if (f.length >= 16) {
                        fmtTag = u16(f, 0);
                        channels = u16(f, 2);
                        rate = (int) u32(f, 4);
                        bits = u16(f, 14);
                        if (fmtTag == 0xFFFE && f.length >= 26) {
                            fmtTag = u16(f, 24);   // WAVE_FORMAT_EXTENSIBLE — 하위 형식 GUID 의 앞 2바이트
                        }
                    }
                } else if ("data".equals(id)) {
                    if (fmtTag != 1 || bits != 16 || channels < 1 || rate < 1) {
                        throw new IllegalArgumentException("PCM 16bit WAV 가 아니다(형식 %d · %dbit · %d채널 · %dHz)"
                                .formatted(fmtTag, bits, channels, rate));
                    }
                    long avail = Math.max(0, size - body);
                    // 0 · 0xFFFFFFFF 는 스트리밍 기록기가 길이를 못 채운 것 — 남은 바이트 전부를 데이터로 본다
                    long dataLen = len == 0 || len == 0xFFFFFFFFL ? avail : Math.min(len, avail);
                    int align = channels * 2;
                    return new Info(rate, channels, bits, body, dataLen - dataLen % align);
                }
                pos = body + len + (len & 1);   // 청크는 짝수 바이트로 맞춘다
            }
            throw new IllegalArgumentException("data 청크가 없다");
        }
    }

    // ------------------------------------------------------------------ 이어 붙이기

    /**
     * 원본 WAV 의 표본을 처음부터 되풀이해 {@code seconds} 길이의 WAV 를 만든다 — 시험 음원(합성 낭독 22초 → 20초 ~ 30분).
     * 형식(표본 수 · 채널 · 16bit)은 원본 그대로다. 흘려 쓰므로 30분(약 55MiB)도 메모리에 올리지 않는다.
     *
     * @return 만든 파일의 머리
     */
    public static Info tile(InputStream sourceWav, Path out, double seconds) throws IOException {
        if (!(seconds > 0)) {
            throw new IllegalArgumentException("길이는 0초보다 길어야 한다: " + seconds);
        }
        Path src = Files.createTempFile(out.toAbsolutePath().getParent(), "tile-src", ".wav");
        try {
            Files.copy(sourceWav, src, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Info s = read(src);
            if (s.dataLen() == 0) {
                throw new IllegalArgumentException("원본에 표본이 없다");
            }
            byte[] pcm = new byte[(int) s.dataLen()];
            try (SeekableByteChannel ch = Files.newByteChannel(src)) {
                ch.position(s.dataOffset());
                ByteBuffer bb = ByteBuffer.wrap(pcm);
                while (bb.hasRemaining() && ch.read(bb) > 0) {
                    // 다 읽을 때까지
                }
            }
            long total = Math.round(seconds * s.rate()) * s.blockAlign();
            try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(out), 1 << 16)) {
                os.write(header(s.rate(), s.channels(), total));
                for (long left = total; left > 0; left -= pcm.length) {
                    os.write(pcm, 0, (int) Math.min(pcm.length, left));
                }
            }
            return new Info(s.rate(), s.channels(), 16, 44, total);
        } finally {
            Files.deleteIfExists(src);
        }
    }

    // ------------------------------------------------------------------ 나누기

    /**
     * 나눌 자리를 정한다(파일은 만들지 않는다). {@code maxSec} 이하면 조각 하나다.
     *
     * @param maxSec    조각 하나의 상한(제논 600초). 어떤 조각도 넘지 않는다
     * @param searchSec 경계를 고른 자리에서 앞뒤로 옮겨 볼 폭 — 이 안에서 가장 조용한 20ms 를 고른다
     */
    public static List<Piece> plan(Path file, Info info, double maxSec, double searchSec) throws IOException {
        long align = info.blockAlign();
        long bps = info.bytesPerSec();
        long total = info.dataLen();
        long max = (long) Math.floor(maxSec * info.rate()) * align;
        if (max <= 0) {
            throw new IllegalArgumentException("조각 상한이 0 이하다: " + maxSec);
        }
        int n = (int) Math.max(1, (total + max - 1) / max);
        List<Piece> out = new ArrayList<>(n);
        long search = (long) Math.floor(Math.max(0d, searchSec) * info.rate()) * align;
        long frame = Math.max(align, (long) Math.floor(FRAME_SEC * info.rate()) * align);
        long start = 0;
        try (SeekableByteChannel ch = Files.newByteChannel(file)) {
            for (int k = 0; k < n - 1; k++) {
                long ideal = start + (total - start) / (n - k);
                ideal -= ideal % align;
                // 이 조각이 한도를 넘지 않고(hi) · 남은 조각들이 남은 길이를 한도 안에서 덮을 수 있는(lo) 자리만
                long lo = Math.max(Math.max(start + align, ideal - search), total - (long) (n - 1 - k) * max);
                long hi = Math.min(start + max, ideal + search);
                long cut = lo >= hi ? Math.min(Math.max(ideal, lo), start + max) : quietest(ch, info, lo, hi, frame);
                out.add(piece(k, start, cut, bps));
                start = cut;
            }
        }
        out.add(piece(n - 1, start, total, bps));
        return out;
    }

    /**
     * 조각 하나를 둘로 — 가운데 ±{@code searchSec}(조각 길이의 1/4 이하) 안 가장 조용한 20ms 에서.
     * 제논이 길이 한도로 거절한 조각을 다시 보낼 때 쓴다.
     */
    public static List<Piece> halve(Path file, Info info, Piece p, double searchSec) throws IOException {
        long align = info.blockAlign();
        long len = p.to() - p.from();
        long mid = p.from() + len / 2;
        mid -= mid % align;
        long search = Math.min((long) Math.floor(Math.max(0d, searchSec) * info.rate()) * align, len / 4);
        search -= search % align;
        long frame = Math.max(align, (long) Math.floor(FRAME_SEC * info.rate()) * align);
        long lo = Math.max(p.from() + align, mid - search);
        long hi = Math.min(p.to() - align, mid + search);
        long cut = mid;
        if (lo < hi) {
            try (SeekableByteChannel ch = Files.newByteChannel(file)) {
                cut = quietest(ch, info, lo, hi, frame);
            }
        }
        long bps = info.bytesPerSec();
        return List.of(piece(p.index(), p.from(), cut, bps), piece(p.index(), cut, p.to(), bps));
    }

    /** {@code [lo, hi]} 안에서 20ms 창의 에너지(절댓값 합)가 가장 작은 자리 — 같으면 뒤쪽(조각이 길어지는 쪽). */
    static long quietest(SeekableByteChannel ch, Info info, long lo, long hi, long frame) throws IOException {
        long align = info.blockAlign();
        long span = hi - lo + frame;
        byte[] buf = readAt(ch, info.dataOffset() + lo, (int) Math.min(span, Integer.MAX_VALUE - 8));
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        long best = hi;
        long bestEnergy = Long.MAX_VALUE;
        for (long at = lo; at <= hi; at += frame) {
            int off = (int) (at - lo);
            int end = (int) Math.min(buf.length, off + frame);
            if (end - off < align) {
                break;
            }
            long energy = 0;
            for (int i = off; i + 1 < end; i += 2) {
                energy += Math.abs(bb.getShort(i));
            }
            if (energy <= bestEnergy) {
                bestEnergy = energy;
                best = at;
            }
        }
        return best - best % align;
    }

    /**
     * 조각 하나를 독립된 WAV 로 쓴다 — 머리 44바이트 + 원본 data 의 그 구간 그대로.
     */
    public static void write(Path file, Info info, Piece p, Path out) throws IOException {
        long len = p.to() - p.from();
        try (FileChannel in = FileChannel.open(file, StandardOpenOption.READ);
             FileChannel o = FileChannel.open(out, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                     StandardOpenOption.WRITE)) {
            o.write(ByteBuffer.wrap(header(info.rate(), info.channels(), len)));
            long pos = info.dataOffset() + p.from();
            for (long left = len; left > 0; ) {
                long n = in.transferTo(pos, left, o);
                if (n <= 0) {
                    throw new IOException("조각을 다 옮기지 못했다 — " + left + " 바이트 남음");
                }
                pos += n;
                left -= n;
            }
        }
    }

    // ------------------------------------------------------------------ 바이트

    /** PCM 16bit WAV 머리 44바이트. */
    static byte[] header(int rate, int channels, long dataLen) {
        if (dataLen > 0xFFFFFFFFL - 36) {
            throw new IllegalArgumentException("WAV 한 파일에 담을 수 없는 길이(4GiB 초과)");
        }
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt((int) (36 + dataLen))
                .put("WAVE".getBytes(StandardCharsets.US_ASCII))
                .put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1)
                .putShort((short) channels).putInt(rate).putInt(rate * channels * 2)
                .putShort((short) (channels * 2)).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt((int) dataLen);
        return h.array();
    }

    private static Piece piece(int index, long from, long to, long bps) {
        return new Piece(index, from, to, round3((double) from / bps), round3((double) to / bps));
    }

    private static byte[] readAt(SeekableByteChannel ch, long pos, int n) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(Math.max(0, n));
        ch.position(pos);
        while (bb.hasRemaining()) {
            if (ch.read(bb) <= 0) {
                break;
            }
        }
        byte[] out = new byte[bb.position()];
        bb.flip();
        bb.get(out);
        return out;
    }

    private static boolean ascii(byte[] b, int off, String s) {
        return new String(b, off, 4, StandardCharsets.US_ASCII).equals(s);
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8;
    }

    private static long u32(byte[] b, int o) {
        return (b[o] & 0xFFL) | (b[o + 1] & 0xFFL) << 8 | (b[o + 2] & 0xFFL) << 16 | (b[o + 3] & 0xFFL) << 24;
    }

    private static double round3(double v) {
        return Math.round(v * 1000d) / 1000d;
    }
}
