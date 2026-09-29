package egovframework.unstructured.collector.common.decrypt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.BadPaddingException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 복호화 규약 — <b>AES/CBC/PKCS5Padding · 키/IV 는 문자열의 UTF-8 바이트 그대로</b>.
 *
 * <p>참조 구현({@code rvs-media-decryptor.html})과 같은 결과가 나오는지를 고정한다.
 * 규약이 어긋나면 복호화는 "성공" 하는데 내용이 쓰레기가 되므로, 왕복만이 아니라
 * <b>틀린 키가 실제로 실패하는지</b>까지 본다.</p>
 *
 * <p>여기 쓰는 키는 <b>테스트 전용 더미</b>다. 운영 키는 레포에 없다
 * ({@code voice.decrypt.rvs-key-path} 가 가리키는 파일에서 읽는다).</p>
 */
class MediaDecryptorTest {

    /** 32바이트(AES-256). 운영 키와 길이만 같고 값은 다르다. */
    private static final String KEY_32 = "0123456789abcdef0123456789abcdef";
    private static final String IV_16 = "0000000000000000";

    private static MediaDecryptor decryptor(String key, String iv) {
        return MediaDecryptor.of(key, iv, "AES", "CBC", "PKCS5Padding", StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("왕복 — 암호화한 것을 같은 키로 풀면 원본 그대로다")
    void roundTrip() throws Exception {
        MediaDecryptor md = decryptor(KEY_32, IV_16);
        byte[] plain = "여보세요. 접견 시작하겠습니다. 010-1234-5678".getBytes(StandardCharsets.UTF_8);

        byte[] cipher = md.encrypt(plain);
        assertThat(cipher).isNotEqualTo(plain);
        assertThat(md.decrypt(cipher)).isEqualTo(plain);
    }

    @Test
    @DisplayName("블록 경계를 넘는 바이너리 — 버퍼(64KB)보다 큰 파일도 스트리밍으로 정확히 푼다")
    void streamsLargeBinary(@TempDir Path tmp) throws Exception {
        MediaDecryptor md = decryptor(KEY_32, IV_16);
        // 버퍼 여러 번 + 블록에 딱 떨어지지 않는 크기 — 패딩과 이어 붙이기를 동시에 본다
        byte[] plain = new byte[64 * 1024 * 3 + 777];
        new Random(42).nextBytes(plain);

        Path enc = tmp.resolve("meet.enc");
        Path dec = tmp.resolve("out").resolve("decrypted_meet.m4a");
        Files.write(enc, md.encrypt(plain));

        long written = md.decryptToFile(enc, dec);

        assertThat(written).isEqualTo(plain.length);
        assertThat(Files.readAllBytes(dec)).isEqualTo(plain);
    }

    @Test
    @DisplayName("틀린 키 — 조용히 잘린 파일을 남기지 않고 예외를 던진다")
    void wrongKeyFailsLoudly(@TempDir Path tmp) throws Exception {
        byte[] plain = "원본 오디오".getBytes(StandardCharsets.UTF_8);
        Path enc = tmp.resolve("a.enc");
        Files.write(enc, decryptor(KEY_32, IV_16).encrypt(plain));

        MediaDecryptor wrong = decryptor("ffffffffffffffffffffffffffffffff", IV_16);
        Path dest = tmp.resolve("out").resolve("a.m4a");

        assertThatThrownBy(() -> wrong.decryptToFile(enc, dest))
                .isInstanceOf(BadPaddingException.class);
        // 반쯤 쓴 산출물도, 임시 파일도 남지 않아야 한다 — 남으면 다음 단계가 정상으로 집어 간다
        assertThat(Files.exists(dest)).isFalse();
        assertThat(Files.exists(dest.resolveSibling(dest.getFileName() + ".part"))).isFalse();
    }

    @Test
    @DisplayName("암호문이 아닌 입력 — 실패로 잡는다")
    void plainInputFails(@TempDir Path tmp) throws Exception {
        Path notEncrypted = tmp.resolve("raw.m4a");
        Files.write(notEncrypted, "이건 그냥 텍스트다".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> decryptor(KEY_32, IV_16).decryptToFile(notEncrypted, tmp.resolve("x.m4a")))
                .isInstanceOfAny(BadPaddingException.class, javax.crypto.IllegalBlockSizeException.class);
    }

    @Test
    @DisplayName("스트림 API — 파일을 거치지 않아도 같은 결과")
    void streamApi() throws Exception {
        MediaDecryptor md = decryptor(KEY_32, IV_16);
        byte[] plain = "스트림".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        long n = md.decryptStream(new ByteArrayInputStream(md.encrypt(plain)), out);

        assertThat(n).isEqualTo(plain.length);
        assertThat(out.toByteArray()).isEqualTo(plain);
    }

    // ── 키 파일 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("키 파일 — rvs_key.txt 형식을 읽고 값의 앞뒤 공백을 떼어 낸다")
    void readsKeyFile(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("rvs_key.txt");
        // 값 뒤의 공백은 실제로 자주 섞인다. 떼어 내지 않으면 길이가 달라져 키가 바뀐다.
        Files.writeString(f, """
                # 주석은 무시한다
                secretkey=%s\s
                iv=%s
                algorithm=AES
                ciphermode=CBC
                padding=PKCS5Padding
                charset=UTF-8
                """.formatted(KEY_32, IV_16), StandardCharsets.UTF_8);

        MediaDecryptor md = MediaDecryptor.fromKeyFile(f);

        assertThat(md.transformation()).isEqualTo("AES/CBC/PKCS5Padding");
        assertThat(md.describe()).contains("32바이트", "256비트").doesNotContain(KEY_32);
        // 같은 값으로 직접 만든 것과 결과가 같아야 한다 — 파싱이 값을 건드리지 않았다는 뜻
        byte[] plain = "키 파일 왕복".getBytes(StandardCharsets.UTF_8);
        assertThat(md.decrypt(decryptor(KEY_32, IV_16).encrypt(plain))).isEqualTo(plain);
    }

    @Test
    @DisplayName("키 파일이 없거나 항목이 빠지면 — 무엇이 문제인지 말하고 멈춘다")
    void keyFileProblemsAreExplicit(@TempDir Path tmp) throws Exception {
        assertThatThrownBy(() -> MediaDecryptor.fromKeyFile(tmp.resolve("없는파일.txt")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("복호화 키 파일이 없습니다")
                .hasMessageContaining("rvs-key-path");

        Path noIv = tmp.resolve("k.txt");
        Files.writeString(noIv, "secretkey=" + KEY_32 + "\n", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> MediaDecryptor.fromKeyFile(noIv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'iv' 가 없습니다");
    }

    @Test
    @DisplayName("길이가 틀린 키·IV — 만들 때 바로 걸러 낸다")
    void rejectsBadLengths() {
        assertThatThrownBy(() -> decryptor("짧은키", IV_16))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AES 키 길이");

        assertThatThrownBy(() -> decryptor(KEY_32, "00000000"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IV 길이");
    }
}
