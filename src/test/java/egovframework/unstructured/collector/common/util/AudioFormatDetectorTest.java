package egovframework.unstructured.collector.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 포맷 판별 — ESB 수신 파일이 {@code .DAT} 로 오기 때문에 <b>파일명을 믿을 수 없다</b>는 것이
 * 이 클래스의 존재 이유다. 그 전제를 테스트로 못 박는다.
 */
class AudioFormatDetectorTest {

    @Test
    @DisplayName("확장자가 .DAT 여도 매직 넘버로 WAV 를 알아본다")
    void detectsWavFromMagicEvenWhenNamedDat() throws Exception {
        Path dat = Files.createTempFile("R_IF001_BOR_KAI_", ".DAT");
        Files.write(dat, SilentWav.of(1));

        // 원본 파일명을 모른다고 가정해도 매직 넘버만으로 판별된다
        assertThat(AudioFormatDetector.detect(dat, null)).isEqualTo("wav");
        Files.deleteIfExists(dat);
    }

    @Test
    @DisplayName("m4a 는 ftyp 박스의 브랜드로 구분한다")
    void detectsM4aFromFtypBrand() {
        byte[] head = new byte[16];
        System.arraycopy(new byte[]{0, 0, 0, 0x20}, 0, head, 0, 4);
        System.arraycopy("ftyp".getBytes(StandardCharsets.US_ASCII), 0, head, 4, 4);
        System.arraycopy("M4A ".getBytes(StandardCharsets.US_ASCII), 0, head, 8, 4);

        assertThat(AudioFormatDetector.byMagic(head)).isEqualTo("m4a");
    }

    @Test
    @DisplayName("mp4 브랜드는 mp4 로 구분한다")
    void detectsMp4FromFtypBrand() {
        byte[] head = new byte[16];
        System.arraycopy("ftyp".getBytes(StandardCharsets.US_ASCII), 0, head, 4, 4);
        System.arraycopy("isom".getBytes(StandardCharsets.US_ASCII), 0, head, 8, 4);

        assertThat(AudioFormatDetector.byMagic(head)).isEqualTo("mp4");
    }

    @Test
    @DisplayName("매직 넘버로 못 가리면 원본 파일명의 확장자로 되짚는다")
    void fallsBackToOriginalFileName() throws Exception {
        Path dat = Files.createTempFile("unknown", ".DAT");
        Files.write(dat, "NOT-AN-AUDIO-HEADER".getBytes(StandardCharsets.US_ASCII));

        assertThat(AudioFormatDetector.detect(dat, "mock_meet_001.m4a")).isEqualTo("m4a");
        assertThat(AudioFormatDetector.detect(dat, null)).isEqualTo(AudioFormatDetector.UNKNOWN);
        Files.deleteIfExists(dat);
    }

    @Test
    @DisplayName("무음 WAV 는 길이·헤더가 규격에 맞는다")
    void silentWavHasValidHeader() {
        byte[] wav = SilentWav.of(2);

        // 44바이트 헤더 + 16kHz * 2byte * 2초
        assertThat(wav).hasSize(44 + 16_000 * 2 * 2);
        assertThat(new String(wav, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(new String(wav, 8, 4, StandardCharsets.US_ASCII)).isEqualTo("WAVE");
        assertThat(AudioFormatDetector.byMagic(wav)).isEqualTo("wav");
    }
}
