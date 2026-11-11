package egovframework.unstructured.collector.voice.stt.genon;

import egovframework.unstructured.collector.common.util.AudioFormatDetector;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 제논 STT 사전 검증 — 포맷 · 크기 · 재생 시간을 <b>보내기 전에</b> 본다.
 *
 * <p>제논도 같은 한도로 거절한다(2026-11-06 실측: 20.5 MiB → 400 {@code audio_size_limit_exceeded},
 * 실제 601초 → 400 {@code audio_duration_limit_exceeded}, 텍스트 → 400 {@code unsupported_audio_format}).
 * 그래도 앞에서 막는 까닭은 둘이다 — 20MB 를 외부망으로 다 올린 뒤에야 거절을 받으면 동시 1건 한도의 차례를
 * 그만큼 잡아먹고, 실패 사유도 제논 코드보다 "몇 초라서 안 됨" 이 읽기 쉽다.</p>
 *
 * <p><b>재생 시간은 디코딩하지 않고 헤더로 잰다</b> — WAV 는 fmt · data 청크, MP3 는 첫 프레임 헤더(Xing · Info ·
 * VBRI 가 있으면 프레임 수, 없으면 고정 비트레이트로 나눔), M4A 는 moov/mvhd. WAV 의 data 청크가 파일보다 길다고
 * 적혀 있으면(잘린 파일) <b>실제 남은 바이트로 잰다</b> — 제논도 실제 길이로 판정했다(헤더만 610초인 1초 파일 → 200).</p>
 *
 * <p><b>못 재면 막지 않는다</b>(경고만) — 길이 판정은 제논이 다시 한다. 막는 것은 확실히 넘는 것뿐이다.</p>
 */
public final class AudioInspector {

    /** 보낼 수 있는 포맷 — 가이드 기준 WAV · MP3 · M4A. */
    public static final Set<String> FORMATS = Set.of("wav", "mp3", "m4a");

    /**
     * 받는 MIME — 브라우저 · OS 가 붙이는 이름이 제각각이라 넓게 받는다.
     * 비었거나 {@code application/octet-stream} 이면 '모름' 으로 보고 내용(매직 넘버)으로 가린다.
     */
    public static final Set<String> MIME_OK = Set.of(
            "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave", "audio/x-pn-wav",
            "audio/mpeg", "audio/mp3", "audio/mpeg3", "audio/x-mpeg-3", "audio/x-mp3",
            "audio/mp4", "audio/m4a", "audio/x-m4a");

    private static final String OCTET = "application/octet-stream";

    private AudioInspector() {
    }

    /**
     * 파일에서 읽은 것.
     *
     * @param format       내용(매직 넘버)으로 본 포맷 — wav · mp3 · m4a · mp4 · ogg · unknown
     * @param extension    파일 이름의 확장자(소문자 · 점 없이). 이름을 안 받았으면 null, 확장자가 없으면 빈 문자열
     * @param mime         넘어온 MIME(소문자 · 매개변수 뗌). 없으면 null
     * @param sizeBytes    파일 크기. 못 읽었으면 -1
     * @param durationSec  재생 시간(초). 못 쟀으면 null
     * @param durationFrom 무엇으로 쟀는지 — wav:data · wav:fact · mp3:xing · mp3:vbri · mp3:cbr · mp4:mvhd
     * @param sampleRate   표본 빈도(Hz). 모르면 null
     * @param channels     채널 수. 모르면 null
     * @param codec        코덱 표기(pcm_s16le · mp3 · …). 모르면 null
     */
    public record Info(String format, String extension, String mime, long sizeBytes, Double durationSec,
                       String durationFrom, Integer sampleRate, Integer channels, String codec) {
    }

    /** 위반 · 경고 한 줄 — {@code code} 는 화면 · 테스트가 가르는 값이고 {@code message} 는 사람이 읽는다. */
    public record Issue(String code, String message) {
    }

    /**
     * 검사 결과.
     *
     * @param violations 하나라도 있으면 보내지 않는다 — EMPTY_FILE · FILE_UNREADABLE · UNSUPPORTED_FORMAT ·
     *                   SIZE_LIMIT_EXCEEDED · DURATION_LIMIT_EXCEEDED
     * @param warnings   보내기는 한다 — FORMAT_MISMATCH · MIME_UNKNOWN · CONTAINER_BRAND · DURATION_UNKNOWN · HEADER_TRUNCATED
     */
    public record Check(Info info, List<Issue> violations, List<Issue> warnings, long maxBytes, int maxDurationSec) {

        public Check {
            violations = List.copyOf(violations);
            warnings = List.copyOf(warnings);
        }

        public boolean ok() {
            return violations.isEmpty();
        }

        public boolean violates(String code) {
            return violations.stream().anyMatch(v -> v.code().equals(code));
        }

        /** 보낼 포맷 — 내용 기준. MP4 컨테이너는 m4a 로, 내용을 모르면 확장자로(사전 검증을 건너뛴 시험 호출만 해당). */
        public String sendFormat() {
            String f = info.format();
            if ("mp4".equals(f)) {
                return "m4a";
            }
            if (FORMATS.contains(f) || "ogg".equals(f)) {
                return f;
            }
            String ext = info.extension();
            return (ext != null && ext.matches("[a-z0-9]{1,5}")) ? ext : "bin";
        }

        /**
         * 제논으로 보낼 파일 이름 — <b>원래 이름은 외부로 내보내지 않는다</b>(수용자 이름 · 교정번호가 들어 있을 수 있고,
         * ESB 파일은 {@code .DAT} 라 확장자도 틀린다). 내용으로 정한 확장자만 붙인다.
         */
        public String uploadName() {
            return "audio." + sendFormat();
        }

        public String contentType() {
            return switch (sendFormat()) {
                case "wav" -> "audio/wav";
                case "mp3" -> "audio/mpeg";
                case "m4a" -> "audio/mp4";
                case "ogg" -> "audio/ogg";
                case "txt" -> "text/plain";
                default -> OCTET;
            };
        }

        /** 로그 · 화면 한 줄 — 예: {@code WAV · 93.8KB · 3.0초}. */
        public String summary() {
            String dur = info.durationSec() == null ? "길이 모름" : fmt("%.1f초", info.durationSec());
            return info.format().toUpperCase(Locale.ROOT) + " · " + human(info.sizeBytes()) + " · " + dur;
        }

        /** 위반 사유를 한 줄로. */
        public String violationText() {
            return String.join(" · ", violations.stream().map(Issue::message).toList());
        }
    }

    /**
     * 검사한다. 예외를 던지지 않는다 — 읽지 못한 것도 위반(FILE_UNREADABLE)으로 돌려준다.
     *
     * @param file           검사할 파일
     * @param fileName       원래 파일 이름(확장자 검사용). 배치처럼 이름을 믿을 수 없으면 null — 확장자 검사를 건너뛴다
     * @param mime           넘어온 MIME. 모르면 null — MIME 검사를 건너뛴다
     * @param maxBytes       크기 상한. 넘으면(초과) 막는다 — 같으면 통과
     * @param maxDurationSec 재생 시간 상한(초). 넘으면 막는다 — 같으면 통과
     */
    public static Check check(Path file, String fileName, String mime, long maxBytes, int maxDurationSec) {
        List<Issue> bad = new ArrayList<>();
        List<Issue> warn = new ArrayList<>();
        String ext = extension(fileName);
        String mimeNorm = normalizeMime(mime);

        long size;
        byte[] head;
        try {
            size = Files.size(file);
            head = readHead(file, 16);
        } catch (IOException e) {
            bad.add(new Issue("FILE_UNREADABLE", "파일을 읽지 못함 — " + e.getClass().getSimpleName()));
            return new Check(new Info(AudioFormatDetector.UNKNOWN, ext, mimeNorm, -1, null, null, null, null, null),
                    bad, warn, maxBytes, maxDurationSec);
        }
        if (size == 0) {
            bad.add(new Issue("EMPTY_FILE", "빈 파일(0 바이트)"));
        }
        String format = AudioFormatDetector.byMagic(head);

        // ── 포맷: 확장자 · MIME · 내용 셋 다 본다
        if (ext != null) {
            if (ext.isEmpty()) {
                bad.add(new Issue("UNSUPPORTED_FORMAT", "확장자가 없음 — WAV · MP3 · M4A 만 보낼 수 있다"));
            } else if (!FORMATS.contains(ext)) {
                bad.add(new Issue("UNSUPPORTED_FORMAT", "확장자 ." + ext + " 은 보낼 수 없음 — WAV · MP3 · M4A 만"));
            }
        }
        if (mimeNorm != null) {
            if (OCTET.equals(mimeNorm)) {
                warn.add(new Issue("MIME_UNKNOWN", "MIME 이 " + OCTET + " — 내용으로 판정했다"));
            } else if (!MIME_OK.contains(mimeNorm)) {
                bad.add(new Issue("UNSUPPORTED_FORMAT", "MIME " + mimeNorm + " 은 보낼 수 없음 — WAV · MP3 · M4A 만"));
            }
        }
        boolean audio = FORMATS.contains(format) || "mp4".equals(format);
        if (size > 0 && !audio) {
            bad.add(new Issue("UNSUPPORTED_FORMAT", "ogg".equals(format)
                    ? "내용이 OGG — WAV · MP3 · M4A 만 보낼 수 있다"
                    : "내용이 WAV · MP3 · M4A 가 아님(앞 4바이트 " + hex(head, 4) + ")"));
        }
        if ("mp4".equals(format)) {
            warn.add(new Issue("CONTAINER_BRAND", "ftyp 브랜드가 M4A 가 아님 — MP4 컨테이너로 보고 m4a 로 보낸다"));
        }
        if (audio && ext != null && FORMATS.contains(ext) && !ext.equals("mp4".equals(format) ? "m4a" : format)) {
            warn.add(new Issue("FORMAT_MISMATCH", "확장자는 " + ext + " 인데 내용은 " + format + " — 내용대로 보낸다"));
        }

        // ── 크기
        if (size > maxBytes) {
            bad.add(new Issue("SIZE_LIMIT_EXCEEDED", fmt("파일 크기 %s(%,d B) — 상한 %s(%,d B) 초과",
                    human(size), size, human(maxBytes), maxBytes)));
        }

        // ── 재생 시간
        Dur dur = Dur.UNKNOWN;
        if (audio && size > 0) {
            try (SeekableByteChannel ch = Files.newByteChannel(file)) {
                dur = switch (format) {
                    case "wav" -> wav(ch, size);
                    case "mp3" -> mp3(ch, size);
                    default -> mp4(ch, size);
                };
            } catch (IOException | RuntimeException e) {
                dur = Dur.unknown("헤더를 읽다 실패 — " + e.getClass().getSimpleName());
            }
            if (dur.sec == null) {
                warn.add(new Issue("DURATION_UNKNOWN", "재생 시간을 헤더로 알 수 없음(" + dur.note + ") — 제논이 판정한다"));
            } else {
                if (dur.note != null) {
                    warn.add(new Issue("HEADER_TRUNCATED", dur.note));
                }
                if (dur.sec > maxDurationSec) {
                    bad.add(new Issue("DURATION_LIMIT_EXCEEDED", fmt("재생 시간 %.1f초 — 상한 %d초 초과",
                            dur.sec, maxDurationSec)));
                }
            }
        }
        Info info = new Info(format, ext, mimeNorm, size, dur.sec == null ? null : round3(dur.sec),
                dur.from, dur.rate, dur.channels, dur.codec);
        return new Check(info, bad, warn, maxBytes, maxDurationSec);
    }

    // ------------------------------------------------------------------ WAV

    /**
     * RIFF 청크를 따라가며 fmt · fact · data 를 찾는다. data 는 대개 마지막이지만 LIST 등이 앞에 끼는 파일이 많아
     * 44 바이트 고정 헤더로 가정하지 않는다.
     */
    static Dur wav(SeekableByteChannel ch, long size) throws IOException {
        long pos = 12;
        int fmtTag = -1;
        Integer channels = null;
        Integer rate = null;
        int bits = 0;
        long byteRate = 0;
        int blockAlign = 0;
        long factSamples = -1;
        long dataOff = -1;
        long dataLen = 0;
        for (int guard = 0; pos + 8 <= size && guard < 4096; guard++) {
            byte[] h = read(ch, pos, 8);
            if (h.length < 8) {
                break;
            }
            String id = new String(h, 0, 4, StandardCharsets.US_ASCII);
            long len = u32le(h, 4);
            long body = pos + 8;
            if ("fmt ".equals(id)) {
                byte[] f = read(ch, body, (int) Math.min(len, 40));
                if (f.length >= 16) {
                    fmtTag = u16le(f, 0);
                    channels = u16le(f, 2);
                    rate = (int) u32le(f, 4);
                    byteRate = u32le(f, 8);
                    blockAlign = u16le(f, 12);
                    bits = u16le(f, 14);
                    if (fmtTag == 0xFFFE && f.length >= 26) {
                        fmtTag = u16le(f, 24);   // WAVE_FORMAT_EXTENSIBLE — 하위 형식 GUID 의 앞 2바이트
                    }
                }
            } else if ("fact".equals(id)) {
                byte[] f = read(ch, body, 4);
                if (f.length == 4) {
                    factSamples = u32le(f, 0);
                }
            } else if ("data".equals(id)) {
                dataOff = body;
                dataLen = len;
                if (rate != null) {
                    break;
                }
            }
            pos = body + len + (len & 1);   // 청크는 짝수 바이트로 맞춘다
        }
        if (dataOff < 0 || rate == null || rate <= 0) {
            return Dur.unknown(dataOff < 0 ? "data 청크 없음" : "fmt 청크 없음");
        }
        long avail = Math.max(0, size - dataOff);
        // 0 · 0xFFFFFFFF 는 스트리밍 기록기가 길이를 못 채운 것 — 남은 바이트 전부를 데이터로 본다
        boolean unset = dataLen == 0 || dataLen == 0xFFFFFFFFL;
        long effective = unset ? avail : Math.min(dataLen, avail);
        String codec = codecOf(fmtTag, bits);

        double sec;
        String from;
        if (fmtTag != 1 && fmtTag != 3 && factSamples > 0) {
            sec = (double) factSamples / rate;
            from = "wav:fact";
        } else if (byteRate > 0) {
            sec = (double) effective / byteRate;
            from = "wav:data";
        } else if (blockAlign > 0) {
            sec = (double) effective / ((long) blockAlign * rate);
            from = "wav:data";
        } else {
            return Dur.unknown("byteRate · blockAlign 이 0");
        }
        String note = null;
        if (!unset && dataLen > avail && byteRate > 0) {
            note = fmt("헤더는 %.1f초라고 적었지만 실제 데이터는 %.1f초 — 잘린 파일로 보고 실제 길이로 판정",
                    (double) dataLen / byteRate, sec);
        }
        return new Dur(sec, from, rate, channels, codec, note);
    }

    private static String codecOf(int fmtTag, int bits) {
        return switch (fmtTag) {
            case 1 -> bits == 8 ? "pcm_u8" : "pcm_s" + bits + "le";
            case 3 -> "pcm_f" + bits + "le";
            case 6 -> "alaw";
            case 7 -> "mulaw";
            case 0x11 -> "adpcm_ima";
            case 0x55 -> "mp3";
            default -> fmtTag < 0 ? null : "0x%04x".formatted(fmtTag);
        };
    }

    // ------------------------------------------------------------------ MP3

    /** kbps — [MPEG1 L1, MPEG1 L2, MPEG1 L3, MPEG2·2.5 L1, MPEG2·2.5 L2·L3][비트레이트 색인]. */
    private static final int[][] KBPS = {
            {0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, 0},
            {0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 0},
            {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0},
            {0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256, 0},
            {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0}};

    /** 표본 빈도 — [MPEG2.5, (예약), MPEG2, MPEG1][색인]. */
    private static final int[][] RATES = {
            {11025, 12000, 8000}, null, {22050, 24000, 16000}, {44100, 48000, 32000}};

    /** MPEG 오디오 프레임 헤더 하나. */
    record Frame(int version, int layer, int kbps, int rate, int channels, int samples, int length, boolean crc) {

        /** {@code b[off..off+4)} 가 프레임 헤더면 읽고, 아니면 null. 자유 비트레이트(색인 0)는 길이를 몰라 받지 않는다. */
        static Frame parse(byte[] b, int off) {
            if (off + 4 > b.length || (b[off] & 0xFF) != 0xFF || (b[off + 1] & 0xE0) != 0xE0) {
                return null;
            }
            int b2 = b[off + 1] & 0xFF;
            int b3 = b[off + 2] & 0xFF;
            int b4 = b[off + 3] & 0xFF;
            int ver = (b2 >> 3) & 0x03;      // 0=2.5 · 1=예약 · 2=2 · 3=1
            int layerBits = (b2 >> 1) & 0x03; // 1=III · 2=II · 3=I
            int brIdx = (b3 >> 4) & 0x0F;
            int srIdx = (b3 >> 2) & 0x03;
            if (ver == 1 || layerBits == 0 || brIdx == 0 || brIdx == 15 || srIdx == 3) {
                return null;
            }
            int layer = 4 - layerBits;       // 1 · 2 · 3
            int row = ver == 3 ? layer - 1 : (layer == 1 ? 3 : 4);
            int kbps = KBPS[row][brIdx];
            int rate = RATES[ver][srIdx];
            int pad = (b3 >> 1) & 0x01;
            int channels = ((b4 >> 6) & 0x03) == 3 ? 1 : 2;
            int samples;
            int length;
            if (layer == 1) {
                samples = 384;
                length = (12 * kbps * 1000 / rate + pad) * 4;
            } else if (layer == 3 && ver != 3) {
                samples = 576;
                length = 72 * kbps * 1000 / rate + pad;
            } else {
                samples = 1152;
                length = 144 * kbps * 1000 / rate + pad;
            }
            return length < 4 ? null : new Frame(ver, layer, kbps, rate, channels, samples, length, (b2 & 0x01) == 0);
        }
    }

    /**
     * ID3v2 를 건너뛰고 첫 프레임을 찾는다. <b>다음 프레임 자리에도 동기가 맞아야</b> 믿는다 — 태그 · 앨범 그림 안의
     * 우연한 0xFFE 를 프레임으로 읽으면 길이가 터무니없이 나온다.
     */
    static Dur mp3(SeekableByteChannel ch, long size) throws IOException {
        long start = 0;
        for (int guard = 0; guard < 4; guard++) {   // 태그가 둘 붙은 파일도 있다
            byte[] t = read(ch, start, 10);
            if (t.length < 10 || t[0] != 'I' || t[1] != 'D' || t[2] != '3') {
                break;
            }
            long tag = ((t[6] & 0x7FL) << 21) | ((t[7] & 0x7FL) << 14) | ((t[8] & 0x7FL) << 7) | (t[9] & 0x7FL);
            start += 10 + tag + (((t[5] & 0x10) != 0) ? 10 : 0);
        }
        byte[] buf = read(ch, start, 65_536);
        for (int i = 0; i + 4 <= buf.length; i++) {
            Frame f = Frame.parse(buf, i);
            if (f == null) {
                continue;
            }
            long frameAt = start + i;
            long next = frameAt + f.length();
            if (next + 4 <= size && Frame.parse(read(ch, next, 4), 0) == null) {
                continue;   // 오탐
            }
            byte[] fr = read(ch, frameAt, Math.min(f.length() + 64, 2048));
            String codec = "mp3" + (f.layer() == 3 ? "" : "_l" + f.layer());
            // Xing · Info — 사이드 정보 뒤에 있다(MPEG1 모노 17 · 스테레오 32, MPEG2 모노 9 · 스테레오 17)
            int side = f.version() == 3 ? (f.channels() == 1 ? 17 : 32) : (f.channels() == 1 ? 9 : 17);
            int x = 4 + (f.crc() ? 2 : 0) + side;
            if (f.layer() == 3 && x + 12 <= fr.length) {
                String tag = new String(fr, x, 4, StandardCharsets.US_ASCII);
                if (("Xing".equals(tag) || "Info".equals(tag)) && (u32be(fr, x + 4) & 0x1) != 0) {
                    long frames = u32be(fr, x + 8);
                    if (frames > 0) {
                        return new Dur((double) frames * f.samples() / f.rate(), "mp3:xing", f.rate(), f.channels(), codec, null);
                    }
                }
            }
            // VBRI(Fraunhofer) — 프레임 머리 + 32
            if (36 + 18 <= fr.length && "VBRI".equals(new String(fr, 36, 4, StandardCharsets.US_ASCII))) {
                long frames = u32be(fr, 36 + 14);
                if (frames > 0) {
                    return new Dur((double) frames * f.samples() / f.rate(), "mp3:vbri", f.rate(), f.channels(), codec, null);
                }
            }
            // 고정 비트레이트로 본다 — 끝의 ID3v1(128B)은 오디오가 아니다
            long end = size;
            if (size >= 128) {
                byte[] tail = read(ch, size - 128, 3);
                if (tail.length == 3 && tail[0] == 'T' && tail[1] == 'A' && tail[2] == 'G') {
                    end -= 128;
                }
            }
            double sec = (end - frameAt) * 8d / (f.kbps() * 1000d);
            return new Dur(sec, "mp3:cbr", f.rate(), f.channels(), codec, null);
        }
        return Dur.unknown("MPEG 프레임을 찾지 못함");
    }

    // ------------------------------------------------------------------ MP4 · M4A

    /** 최상위 상자를 훑어 moov 를 찾는다 — moov 가 mdat 뒤에 있어도 mdat 을 건너뛰어 찾는다. */
    static Dur mp4(SeekableByteChannel ch, long size) throws IOException {
        long pos = 0;
        for (int guard = 0; pos + 8 <= size && guard < 10_000; guard++) {
            Box b = box(ch, pos, size);
            if (b == null) {
                return Dur.unknown("상자 크기가 깨졌다");
            }
            if ("moov".equals(b.type)) {
                return mvhd(ch, b.bodyStart, b.end);
            }
            pos = b.end;
        }
        return Dur.unknown("moov 상자 없음");
    }

    private static Dur mvhd(SeekableByteChannel ch, long from, long to) throws IOException {
        long pos = from;
        for (int guard = 0; pos + 8 <= to && guard < 10_000; guard++) {
            Box b = box(ch, pos, to);
            if (b == null) {
                break;
            }
            if ("mvhd".equals(b.type)) {
                byte[] m = read(ch, b.bodyStart, 32);
                if (m.length < 20) {
                    break;
                }
                long scale;
                long dur;
                if (m[0] == 1) {   // 버전 1 — 시각 · 길이가 64비트
                    if (m.length < 32) {
                        break;
                    }
                    scale = u32be(m, 20);
                    dur = u64be(m, 24);
                } else {
                    scale = u32be(m, 12);
                    dur = u32be(m, 16);
                }
                if (scale <= 0 || dur <= 0 || dur == 0xFFFFFFFFL) {
                    return Dur.unknown("mvhd 길이가 비었다(조각 MP4 등)");
                }
                return new Dur((double) dur / scale, "mp4:mvhd", null, null, null, null);
            }
            pos = b.end;
        }
        return Dur.unknown("mvhd 상자 없음");
    }

    private record Box(String type, long bodyStart, long end) {
    }

    private static Box box(SeekableByteChannel ch, long pos, long limit) throws IOException {
        byte[] h = read(ch, pos, 16);
        if (h.length < 8) {
            return null;
        }
        long len = u32be(h, 0);
        String type = new String(h, 4, 4, StandardCharsets.US_ASCII);
        int hdr = 8;
        if (len == 1) {
            if (h.length < 16) {
                return null;
            }
            len = u64be(h, 8);
            hdr = 16;
        } else if (len == 0) {
            len = limit - pos;   // 끝까지
        }
        if (len < hdr || pos + len > limit) {
            return null;
        }
        return new Box(type, pos + hdr, pos + len);
    }

    // ------------------------------------------------------------------ 공통

    /** 잰 길이. {@code sec} 가 null 이면 못 잰 것이고 {@code note} 가 사유다. */
    record Dur(Double sec, String from, Integer rate, Integer channels, String codec, String note) {
        static final Dur UNKNOWN = new Dur(null, null, null, null, null, "오디오 아님");

        static Dur unknown(String why) {
            return new Dur(null, null, null, null, null, why);
        }
    }

    /** 확장자 — 이름이 null 이면 null(검사 안 함), 점이 없으면 빈 문자열. 경로가 붙어 와도 마지막 이름만 본다. */
    static String extension(String fileName) {
        if (fileName == null) {
            return null;
        }
        String n = fileName.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).trim();
        int dot = n.lastIndexOf('.');
        return (dot < 0 || dot == n.length() - 1) ? "" : n.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String normalizeMime(String mime) {
        if (mime == null || mime.isBlank()) {
            return null;
        }
        int semi = mime.indexOf(';');
        return (semi < 0 ? mime : mime.substring(0, semi)).trim().toLowerCase(Locale.ROOT);
    }

    /** 사람이 읽는 크기 — 1024 진법(제논 한도가 MiB 다). */
    public static String human(long bytes) {
        if (bytes < 0) {
            return "?";
        }
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024L * 1024) {
            return fmt("%.1fKB", bytes / 1024d);
        }
        return fmt("%.2fMB", bytes / (1024d * 1024));
    }

    /** 숫자 서식은 로캘을 타지 않게 — 독일어 로캘이면 소수점이 쉼표가 되어 문구 · 테스트가 흔들린다. */
    private static String fmt(String pattern, Object... args) {
        return String.format(Locale.ROOT, pattern, args);
    }

    private static double round3(double v) {
        return Math.round(v * 1000d) / 1000d;
    }

    private static byte[] readHead(Path file, int n) throws IOException {
        try (SeekableByteChannel ch = Files.newByteChannel(file)) {
            return read(ch, 0, n);
        }
    }

    private static byte[] read(SeekableByteChannel ch, long pos, int n) throws IOException {
        if (n <= 0 || pos < 0) {
            return new byte[0];
        }
        ByteBuffer bb = ByteBuffer.allocate(n);
        ch.position(pos);
        while (bb.hasRemaining()) {
            if (ch.read(bb) < 0) {
                break;
            }
        }
        return Arrays.copyOf(bb.array(), bb.position());
    }

    private static String hex(byte[] b, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, b.length); i++) {
            sb.append(sb.isEmpty() ? "" : " ").append("%02X".formatted(b[i] & 0xFF));
        }
        return sb.isEmpty() ? "없음" : sb.toString();
    }

    private static int u16le(byte[] b, int o) {
        return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8;
    }

    private static long u32le(byte[] b, int o) {
        return (b[o] & 0xFFL) | (b[o + 1] & 0xFFL) << 8 | (b[o + 2] & 0xFFL) << 16 | (b[o + 3] & 0xFFL) << 24;
    }

    private static long u32be(byte[] b, int o) {
        return (b[o] & 0xFFL) << 24 | (b[o + 1] & 0xFFL) << 16 | (b[o + 2] & 0xFFL) << 8 | (b[o + 3] & 0xFFL);
    }

    private static long u64be(byte[] b, int o) {
        return u32be(b, o) << 32 | u32be(b, o + 4);
    }
}
