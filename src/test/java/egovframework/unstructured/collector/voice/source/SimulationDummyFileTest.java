package egovframework.unstructured.collector.voice.source;

import egovframework.unstructured.collector.common.config.VoiceDirState;
import egovframework.unstructured.collector.common.config.VoiceModeState;
import egovframework.unstructured.collector.common.config.VoiceProperties;
import egovframework.unstructured.collector.common.decrypt.MediaDecryptor;
import egovframework.unstructured.collector.common.model.VoiceKind;
import egovframework.unstructured.collector.common.util.AudioFormatDetector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시뮬레이션 더미 원본 — <b>메타가 '암호화됨' 이라고 말하면 실제로 암호화돼 있어야 한다.</b>
 *
 * <p><b>왜 이 테스트가 생겼나</b>: 더미 원본은 한 줄짜리 텍스트였다. 브로커가 {@code MOCK} 이면
 * 원본을 보지 않고 자기가 오디오를 만들어 떨구므로 그래도 파이프라인이 돌았고, 그래서
 * 이 어긋남이 오래 가려져 있었다. 브로커를 {@code REST} 로 올리는 순간 <b>이 파일이 그대로</b>
 * 수신 폴더로 오고, 복호화 REAL 이 평문에 AES 를 걸다가 {@code IllegalBlockSizeException} 으로
 * 접견 건이 전부 죽었다(개발계·로컬 양쪽에서 재현).</p>
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(properties = {
        "voice.source.mode=MOCK",
        "voice.broker.mode=MOCK",
        "voice.decrypt.mode=REAL",
        "log-collector.enabled=false",
        "agent-connector.enabled=false",
        "voice.sim.seed-on-startup=false"
})
class SimulationDummyFileTest {

    private static final String KEY = "LOCAL_TEST_KEY_0123456789abcdefg";
    private static final String IV = "0000000000000000";

    @TempDirHolder
    static Path root;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws Exception {
        root = Files.createTempDirectory("simdummy");
        Path key = root.resolve("rvs_key.txt");
        Files.writeString(key, String.join("\n",
                "secretkey=" + KEY, "iv=" + IV, "algorithm=AES",
                "ciphermode=CBC", "padding=PKCS5Padding", "charset=UTF-8"));
        registry.add("voice.dirs.base-dir", () -> root.toString().replace('\\', '/'));
        registry.add("voice.decrypt.rvs-key-path", key::toString);
    }

    /** {@code @TempDir} 은 static 필드에 @DynamicPropertySource 보다 늦게 붙어 쓸 수 없다. */
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @interface TempDirHolder {
    }

    @Autowired
    private SimulationDataService sim;

    @Autowired
    private VoiceDirState dirs;

    @Autowired
    private VoiceModeState modeState;

    @Test
    @DisplayName("암호화 대상은 실제로 암호문이다 — 같은 키로 풀면 원본 오디오가 나온다")
    void encryptedDummiesAreRealCipherText() throws Exception {
        modeState.set("decrypt", VoiceProperties.DecryptMode.REAL.name());
        sim.seed();

        Path meetDir = dirs.xvarmOriginalDir(VoiceKind.MEET);
        // 2번은 CMMN_FILE_ENC_YN='N' — 평문이어야 한다
        Path plain = meetDir.resolve("mock_meet_%03d.m4a".formatted(SimulationDataService.MEET_UNENCRYPTED));
        Path cipher = meetDir.resolve("mock_meet_001.m4a");

        assertThat(plain).exists();
        assertThat(cipher).exists();

        // 평문 건은 그대로 오디오로 읽혀야 한다
        assertThat(AudioFormatDetector.byMagic(Files.readAllBytes(plain)))
                .as("암호화 대상이 아닌 건은 그대로 오디오다").isEqualTo("wav");

        // 암호화 건은 오디오로 안 읽히고, 풀면 오디오가 된다
        MediaDecryptor md = MediaDecryptor.of(KEY, IV, "AES", "CBC", "PKCS5Padding",
                java.nio.charset.StandardCharsets.UTF_8);
        byte[] decrypted = md.decrypt(Files.readAllBytes(cipher));
        assertThat(AudioFormatDetector.byMagic(decrypted))
                .as("복호화하면 원본 오디오가 나와야 한다").isEqualTo("wav");
        assertThat(Files.readAllBytes(cipher))
                .as("암호문이 평문과 같으면 암호화가 안 된 것이다")
                .isNotEqualTo(decrypted);
    }

    @Test
    @DisplayName("전화 더미도 암호화된다 — 매퍼가 전화를 늘 ENCRYPTED=1 로 본다")
    void phoneDummiesAreEncryptedToo() throws Exception {
        modeState.set("decrypt", VoiceProperties.DecryptMode.REAL.name());
        sim.seed();

        Path f = dirs.xvarmOriginalDir(VoiceKind.PHONE).resolve("mock_phone_001.wav");
        assertThat(f).exists();
        assertThat(AudioFormatDetector.byMagic(Files.readAllBytes(f)))
                .as("암호문이면 오디오 매직 넘버가 아니다").isNotEqualTo("wav");

        MediaDecryptor md = MediaDecryptor.of(KEY, IV, "AES", "CBC", "PKCS5Padding",
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(AudioFormatDetector.byMagic(md.decrypt(Files.readAllBytes(f)))).isEqualTo("wav");
    }

    @Test
    @DisplayName("복호화가 SKIP 이면 평문으로 둔다 — 안 풀 파일을 암호화해 두면 STT 가 쓰레기를 받는다")
    void leavesPlainWhenDecryptIsSkip() throws Exception {
        modeState.set("decrypt", VoiceProperties.DecryptMode.SKIP.name());
        try {
            sim.seed();

            for (Path f : List.of(
                    dirs.xvarmOriginalDir(VoiceKind.MEET).resolve("mock_meet_001.m4a"),
                    dirs.xvarmOriginalDir(VoiceKind.PHONE).resolve("mock_phone_001.wav"))) {
                assertThat(AudioFormatDetector.byMagic(Files.readAllBytes(f)))
                        .as("%s 는 평문 오디오여야 한다", f.getFileName()).isEqualTo("wav");
            }
        } finally {
            modeState.set("decrypt", VoiceProperties.DecryptMode.REAL.name());
        }
    }
}
