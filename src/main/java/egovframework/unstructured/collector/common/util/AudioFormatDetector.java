package egovframework.unstructured.collector.common.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 오디오 포맷 판별 — <b>파일명을 믿지 않는다</b>.
 *
 * <p>표준 A 의 수신 파일 명명 규칙은 확장자가 {@code .DAT} 다. ESB 를 거쳐 온 파일은
 * 원본이 m4a 였는지 wav 였는지 이름만 봐서는 알 수 없다. 그래서 <b>매직 넘버</b>를 먼저 보고,
 * 실패하면 DB 메타의 원본 파일명으로 되짚는다.</p>
 */
public final class AudioFormatDetector {

    public static final String UNKNOWN = "unknown";

    private AudioFormatDetector() {
    }

    /**
     * 파일 앞부분을 읽어 포맷을 정한다.
     *
     * @param file        검사할 파일
     * @param srcFileName DB 메타의 원본 파일명(매직 넘버로 못 가릴 때 쓰는 보조 근거). null 허용
     */
    public static String detect(Path file, String srcFileName) throws IOException {
        byte[] head = readHead(file, 16);
        String byMagic = byMagic(head);
        if (!UNKNOWN.equals(byMagic)) {
            return byMagic;
        }
        return byExtension(srcFileName);
    }

    /** 매직 넘버만으로 판별한다(테스트에서 직접 쓴다). */
    public static String byMagic(byte[] head) {
        if (head.length >= 12) {
            // RIFF....WAVE
            if (match(head, 0, "RIFF") && match(head, 8, "WAVE")) {
                return "wav";
            }
            // ....ftyp  (ISO BMFF — m4a / mp4)
            if (match(head, 4, "ftyp")) {
                String brand = new String(head, 8, 4).toLowerCase(Locale.ROOT);
                return brand.startsWith("m4a") ? "m4a" : "mp4";
            }
        }
        if (head.length >= 3) {
            // ID3 태그 또는 MPEG 프레임 동기
            if (match(head, 0, "ID3")) {
                return "mp3";
            }
            if ((head[0] & 0xFF) == 0xFF && (head[1] & 0xE0) == 0xE0) {
                return "mp3";
            }
        }
        if (head.length >= 4 && match(head, 0, "OggS")) {
            return "ogg";
        }
        return UNKNOWN;
    }

    /** 원본 파일명의 확장자로 되짚는다. */
    public static String byExtension(String fileName) {
        if (fileName == null) {
            return UNKNOWN;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return UNKNOWN;
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "wav", "m4a", "mp4", "mp3", "ogg", "flac", "amr" -> ext;
            default -> UNKNOWN;
        };
    }

    private static byte[] readHead(Path file, int n) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[n];
            int read = in.readNBytes(buf, 0, n);
            if (read == n) {
                return buf;
            }
            byte[] shorter = new byte[Math.max(read, 0)];
            System.arraycopy(buf, 0, shorter, 0, Math.max(read, 0));
            return shorter;
        }
    }

    private static boolean match(byte[] b, int off, String s) {
        if (b.length < off + s.length()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (b[off + i] != (byte) s.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
