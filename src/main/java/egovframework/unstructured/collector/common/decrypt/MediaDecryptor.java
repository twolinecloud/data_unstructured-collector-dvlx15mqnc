package egovframework.unstructured.collector.common.decrypt;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 음성 파일 복호화 — <b>AES/CBC/PKCS5Padding</b>.
 *
 * <p>XVARM 은 파일을 임시 폴더로 <b>내려받기만</b> 하고 복호화는 화면의 R플레이어가 한다
 * (2026-09-11 회의). 배치로 처리하려면 그 로직이 백엔드에 있어야 한다 — 이 클래스가 그 자리다.
 * 참조 구현({@code rvs-media-decryptor.html})과 같은 규약이다: <b>파일 전체가 한 덩어리로
 * 암호화</b>돼 있고 헤더도 청크 구분도 없다.</p>
 *
 * <p><b>왜 CipherInputStream 을 쓰지 않는가</b>: 그 클래스는 {@code close()} 에서 나는
 * {@code BadPaddingException} 을 <b>삼킨다</b>. 키가 틀리면 예외 대신 <b>잘린 파일</b>이 남고,
 * 배치는 성공으로 끝난 뒤 STT 가 깨진 오디오를 받는다 — 원인을 찾기 가장 어려운 모양이다.
 * 그래서 {@code update}/{@code doFinal} 을 직접 돌린다. 스트리밍은 그대로 유지된다
 * (접견 파일이 건당 5MB · 배치당 1,300건이라 통째로 메모리에 올릴 수 없다).</p>
 *
 * <p><b>이 클래스는 키를 로그에 남기지 않는다.</b> {@link #describe()} 도 길이와 알고리즘만 말한다.</p>
 */
public final class MediaDecryptor {

    /** 스트리밍 버퍼. 작으면 호출이 잦고 크면 파드 메모리를 먹는다 — 64KB 면 둘 다 아니다. */
    private static final int BUFFER = 64 * 1024;

    /** AES 가 받는 키 길이(바이트) — 128 · 192 · 256 비트. */
    private static final int[] AES_KEY_BYTES = {16, 24, 32};

    private final String transformation;
    private final SecretKeySpec key;
    private final IvParameterSpec iv;
    private final int keyBytes;

    private MediaDecryptor(String transformation, SecretKeySpec key, IvParameterSpec iv, int keyBytes) {
        this.transformation = transformation;
        this.key = key;
        this.iv = iv;
        this.keyBytes = keyBytes;
    }

    // ── 생성 ──────────────────────────────────────────────────────────────

    /**
     * 키 파일({@code rvs_key.txt})에서 만든다.
     *
     * <p>형식은 {@code key=value} 한 줄씩이다.</p>
     * <pre>
     *   secretkey=...
     *   iv=0000000000000000
     *   algorithm=AES
     *   ciphermode=CBC
     *   padding=PKCS5Padding
     *   charset=UTF-8
     * </pre>
     *
     * <p>값은 {@code trim} 한다 — 파일 끝의 개행이나 편집기가 남긴 공백 한 칸이 키에 섞이면
     * 길이가 달라져 {@code InvalidKeyException} 이 나거나, 더 나쁘게는 조용히 다른 키가 된다.</p>
     *
     * @throws IllegalStateException 파일이 없거나 필수 항목이 빠졌을 때 — <b>무엇이 빠졌는지</b> 말한다
     */
    public static MediaDecryptor fromKeyFile(Path keyFile) {
        if (keyFile == null || !Files.isRegularFile(keyFile)) {
            throw new IllegalStateException("복호화 키 파일이 없습니다 — " + keyFile
                    + " (voice.decrypt.rvs-key-path 를 확인하십시오)");
        }
        Map<String, String> p = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(keyFile, StandardCharsets.UTF_8)) {
                String s = line.strip();
                if (s.isEmpty() || s.startsWith("#")) {
                    continue;
                }
                int eq = s.indexOf('=');
                if (eq > 0) {
                    p.put(s.substring(0, eq).strip().toLowerCase(), s.substring(eq + 1).strip());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("복호화 키 파일을 읽지 못했습니다 — " + keyFile + " (" + e.getMessage() + ")", e);
        }
        String secret = required(p, "secretkey", keyFile);
        String ivText = required(p, "iv", keyFile);
        return of(secret, ivText,
                p.getOrDefault("algorithm", "AES"),
                p.getOrDefault("ciphermode", "CBC"),
                p.getOrDefault("padding", "PKCS5Padding"),
                Charset.forName(p.getOrDefault("charset", "UTF-8")));
    }

    /**
     * 값으로 직접 만든다(테스트·다른 경로용).
     *
     * <p><b>키와 IV 는 문자열의 바이트 그대로 쓴다</b> — Base64 도 16진수도 아니다.
     * 참조 구현이 {@code TextEncoder().encode()} 로 그렇게 한다. 여기서 규약이 어긋나면
     * 복호화는 "성공" 하는데 내용이 쓰레기가 된다.</p>
     */
    public static MediaDecryptor of(String secretKey, String ivText,
                                    String algorithm, String cipherMode, String padding, Charset charset) {
        byte[] k = secretKey.getBytes(charset);
        byte[] v = ivText.getBytes(charset);
        if ("AES".equalsIgnoreCase(algorithm) && !isAesKeyLength(k.length)) {
            throw new IllegalStateException(
                    "AES 키 길이가 맞지 않습니다 — %d바이트 (16·24·32 중 하나여야 합니다). 키 앞뒤 공백이나 개행을 확인하십시오"
                            .formatted(k.length));
        }
        // CBC 의 IV 는 블록 길이와 같아야 한다. 16이 아니면 InvalidAlgorithmParameterException 이
        // 나는데, 그 메시지만으로는 '키 파일의 iv 값' 을 봐야 한다는 것을 알기 어렵다.
        if ("CBC".equalsIgnoreCase(cipherMode) && v.length != 16) {
            throw new IllegalStateException(
                    "IV 길이가 맞지 않습니다 — %d바이트 (CBC 는 16바이트여야 합니다)".formatted(v.length));
        }
        String transformation = algorithm + "/" + cipherMode + "/" + padding;
        try {
            Cipher.getInstance(transformation);      // 기동 시점에 깨뜨린다 — 첫 배치에서 알면 늦다
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("지원하지 않는 암호 방식입니다 — " + transformation, e);
        }
        return new MediaDecryptor(transformation, new SecretKeySpec(k, algorithm), new IvParameterSpec(v), k.length);
    }

    // ── 복호화 ────────────────────────────────────────────────────────────

    /**
     * 파일을 풀어 {@code dest} 에 쓴다.
     *
     * <p><b>임시 파일에 쓴 뒤 옮긴다.</b> 도중에 실패했을 때 반쯤 쓴 파일이 목적지에 남으면
     * 다음 단계가 그것을 정상 산출물로 집어 간다. 끝까지 성공한 것만 제자리에 놓는다.</p>
     *
     * @return 쓴 바이트 수(복호화 후 크기)
     * @throws GeneralSecurityException 키·IV 가 맞지 않거나 입력이 암호문이 아닐 때
     */
    public long decryptToFile(Path src, Path dest) throws IOException, GeneralSecurityException {
        Files.createDirectories(dest.getParent());
        Path tmp = dest.resolveSibling(dest.getFileName() + ".part");
        long written;
        try {
            try (InputStream in = Files.newInputStream(src);
                 OutputStream out = Files.newOutputStream(tmp)) {
                written = decryptStream(in, out);
            }
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        return written;
    }

    /**
     * 스트림을 풀어 쓴다.
     *
     * <p>{@code update} 로 흘려보내다 마지막에 {@code doFinal} 로 패딩을 확인한다. 키가 틀리면
     * 거기서 {@code BadPaddingException} 이 <b>실제로 던져진다</b> — 이것이 CipherInputStream 을
     * 쓰지 않는 이유다.</p>
     */
    public long decryptStream(InputStream in, OutputStream out) throws IOException, GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(transformation);
        cipher.init(Cipher.DECRYPT_MODE, key, iv);

        byte[] buf = new byte[BUFFER];
        long written = 0L;
        int read;
        while ((read = in.read(buf)) != -1) {
            byte[] part = cipher.update(buf, 0, read);
            if (part != null && part.length > 0) {
                out.write(part);
                written += part.length;
            }
        }
        byte[] last = cipher.doFinal();
        if (last != null && last.length > 0) {
            out.write(last);
            written += last.length;
        }
        out.flush();
        return written;
    }

    /** 작은 입력용(테스트·단문). 큰 파일은 {@link #decryptToFile} 을 쓴다. */
    public byte[] decrypt(byte[] cipherText) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(transformation);
        cipher.init(Cipher.DECRYPT_MODE, key, iv);
        return cipher.doFinal(cipherText);
    }

    /** 같은 규약으로 암호화한다 — <b>테스트가 왕복을 확인하려고</b> 쓴다. 운영 경로는 복호화만 한다. */
    public byte[] encrypt(byte[] plain) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(transformation);
        cipher.init(Cipher.ENCRYPT_MODE, key, iv);
        return cipher.doFinal(plain);
    }

    // ── 진단 ──────────────────────────────────────────────────────────────

    /** 로그·상태 표기용. <b>키 값은 담지 않는다</b> — 길이만 말한다. */
    public String describe() {
        return "%s (키 %d바이트/%d비트)".formatted(transformation, keyBytes, keyBytes * 8);
    }

    public String transformation() {
        return transformation;
    }

    private static boolean isAesKeyLength(int len) {
        for (int ok : AES_KEY_BYTES) {
            if (ok == len) {
                return true;
            }
        }
        return false;
    }

    private static String required(Map<String, String> p, String name, Path file) {
        String v = p.get(name);
        if (v == null || v.isEmpty()) {
            throw new IllegalStateException("복호화 키 파일에 '%s' 가 없습니다 — %s".formatted(name, file));
        }
        return v;
    }
}
