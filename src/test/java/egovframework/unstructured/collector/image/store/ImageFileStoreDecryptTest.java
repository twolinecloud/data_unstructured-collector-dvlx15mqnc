package egovframework.unstructured.collector.image.store;

import egovframework.unstructured.collector.common.config.VoiceDirState;
import egovframework.unstructured.collector.common.config.VoiceModeState;
import egovframework.unstructured.collector.common.config.VoiceProperties;
import egovframework.unstructured.collector.common.decrypt.MediaDecryptor;
import egovframework.unstructured.collector.common.util.SampleImage;
import egovframework.unstructured.collector.image.config.ImageProperties;
import egovframework.unstructured.collector.image.model.ImageFormat;
import egovframework.unstructured.collector.image.model.ImageTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 사진 복호화 판단 — 사진 평문 정책({@code image.decrypt=SKIP} · 기본)과 예전 동작({@code ENC_YN}).
 *
 * <ul>
 *   <li>SKIP — 음성 복호화 모드 · 원천 표시와 상관없이 받은 그대로 둔다(키도 안 읽는다). 이미지가 아니면 평문 정책 안내로 실패</li>
 *   <li>ENC_YN — 복호화 모드 REAL 이고 원천이 암호화로 표시된 건만 RVS 키로 푼다(예전과 같다)</li>
 * </ul>
 */
class ImageFileStoreDecryptTest {

    @TempDir
    Path dir;

    private Path key;
    private byte[] jpeg;
    private byte[] cipher;
    private final VoiceModeState mode = mock(VoiceModeState.class);

    @BeforeEach
    void setUp() throws Exception {
        key = dir.resolve("rvs_key.txt");
        Files.writeString(key, String.join("\n",
                "secretkey=LOCAL_TEST_KEY_0123456789abcdefg", "iv=0000000000000000", "algorithm=AES",
                "ciphermode=CBC", "padding=PKCS5Padding", "charset=UTF-8"));
        jpeg = SampleImage.jpeg("T-0001");
        cipher = MediaDecryptor.fromKeyFile(key).encrypt(jpeg);
        assertThat(ImageFormat.detect(jpeg)).isEqualTo(ImageFormat.JPG);
        assertThat(ImageFormat.detect(cipher)).as("암호문은 이미지로 안 읽힌다").isNull();
    }

    private ImageFileStore store(ImageProperties.Decrypt policy, VoiceProperties.DecryptMode voiceMode, String keyPath) {
        VoiceProperties voice = mock(VoiceProperties.class);
        when(voice.decrypt()).thenReturn(new VoiceProperties.Decrypt(voiceMode, keyPath));
        when(mode.decrypt()).thenReturn(voiceMode);
        ImageProperties props = new ImageProperties("1", "", 4, 2000, List.of("Y", "I", "C"), false, null, "IMAGE_COLLECT", policy);
        return new ImageFileStore(voice, mode, mock(VoiceDirState.class), props);
    }

    private static ImageTarget target(boolean encrypted) {
        return new ImageTarget("T-0001", 2, "F1", "D1", "/xvarm/T-0001_2.jpg", "T-0001_2.jpg", encrypted);
    }

    @Test
    @DisplayName("SKIP(기본) — 복호화 REAL · 원천 'Y' 여도 받은 그대로 둔다 · 키 경로가 비어도 된다")
    void skipKeepsReceivedBytes() throws Exception {
        ImageFileStore s = store(ImageProperties.Decrypt.SKIP, VoiceProperties.DecryptMode.REAL, "");

        assertThat(s.photosEncrypted()).as("더미 사진도 평문으로 만든다").isFalse();
        assertThat(s.decrypts(target(true))).isFalse();
        assertThat(s.skipReason(target(true))).isEqualTo("사진 평문 정책 image.decrypt=SKIP");
        assertThat(s.decrypt(target(true), jpeg)).as("받은 그대로").isSameAs(jpeg);
        assertThat(s.decrypt(target(false), jpeg)).isSameAs(jpeg);
    }

    @Test
    @DisplayName("SKIP — 암호문이 오면 풀지 않고 그 건만 실패 · 안내에 평문 정책과 원천 표시")
    void skipRejectsCipherWithPlainPolicyHint() {
        ImageFileStore s = store(ImageProperties.Decrypt.SKIP, VoiceProperties.DecryptMode.REAL, key.toString());

        assertThatThrownBy(() -> s.decrypt(target(true), cipher))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("사진은 평문으로 받는다")
                .hasMessageContaining("image.decrypt=SKIP")
                .hasMessageContaining("CMMN_FILE_ENC_YN=Y")
                .hasMessageContaining("원천 파일 확인");
        assertThatThrownBy(() -> s.decrypt(target(false), cipher))
                .hasMessageContaining("CMMN_FILE_ENC_YN=N");
    }

    @Test
    @DisplayName("ENC_YN(예전 동작) · REAL · 원천 'Y' — RVS 키로 풀어 원문과 같다")
    void encYnDecryptsFlaggedUnderReal() throws Exception {
        ImageFileStore s = store(ImageProperties.Decrypt.ENC_YN, VoiceProperties.DecryptMode.REAL, key.toString());

        assertThat(s.photosEncrypted()).isTrue();
        assertThat(s.decrypts(target(true))).isTrue();
        assertThat(s.skipReason(target(true))).isNull();
        assertThat(s.decrypt(target(true), cipher)).isEqualTo(jpeg);
    }

    @Test
    @DisplayName("ENC_YN · REAL · 원천 'N' — 풀지 않고 그대로(평문을 풀면 깨진다)")
    void encYnPassesThroughUnflagged() throws Exception {
        ImageFileStore s = store(ImageProperties.Decrypt.ENC_YN, VoiceProperties.DecryptMode.REAL, key.toString());

        assertThat(s.decrypts(target(false))).isFalse();
        assertThat(s.skipReason(target(false))).isEqualTo("원천 표시 CMMN_FILE_ENC_YN=N");
        assertThat(s.decrypt(target(false), jpeg)).isSameAs(jpeg);
    }

    @Test
    @DisplayName("ENC_YN · 복호화 모드 SKIP — 원천 'Y' 여도 풀지 않는다 · 암호문이면 모드 안내로 실패")
    void encYnWithoutRealModeDoesNotDecrypt() throws Exception {
        ImageFileStore s = store(ImageProperties.Decrypt.ENC_YN, VoiceProperties.DecryptMode.SKIP, key.toString());

        assertThat(s.photosEncrypted()).isFalse();
        assertThat(s.skipReason(target(true))).isEqualTo("복호화 모드 SKIP");
        assertThat(s.decrypt(target(true), jpeg)).isSameAs(jpeg);
        assertThatThrownBy(() -> s.decrypt(target(true), cipher))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("복호화 안 함(복호화 모드 SKIP)")
                .hasMessageContaining("복호화 모드 REAL")
                .hasMessageContaining("CMMN_FILE_ENC_YN=Y");
    }

    @Test
    @DisplayName("ENC_YN · REAL · 원천 'Y' 인데 키 경로가 없으면 예전처럼 키 안내로 실패")
    void encYnNeedsKeyWhenDecrypting() {
        ImageFileStore s = store(ImageProperties.Decrypt.ENC_YN, VoiceProperties.DecryptMode.REAL, "");

        assertThatThrownBy(() -> s.decrypt(target(true), cipher))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("voice.decrypt.rvs-key-path");
    }
}
