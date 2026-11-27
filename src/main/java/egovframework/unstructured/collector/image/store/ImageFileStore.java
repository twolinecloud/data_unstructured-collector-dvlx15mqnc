package egovframework.unstructured.collector.image.store;

import egovframework.unstructured.collector.common.config.VoiceDirState;
import egovframework.unstructured.collector.common.config.VoiceModeState;
import egovframework.unstructured.collector.common.config.VoiceProperties;
import egovframework.unstructured.collector.common.decrypt.MediaDecryptor;
import egovframework.unstructured.collector.image.config.ImageProperties;
import egovframework.unstructured.collector.image.model.ImageFormat;
import egovframework.unstructured.collector.image.model.ImageTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * 받은 사진을 확인해 Target 저장소에 둔다 — <b>기본은 복호화하지 않는다</b>({@code image.decrypt=SKIP}).
 *
 * <p><b>사진 평문 정책</b>(2026-11-27): 사진은 평문으로 받는다. 받은 바이트를 그대로 저장하고 이미지인지(매직 넘버)만
 * 확인한다 — 원천의 {@code CMMN_FILE_ENC_YN} 이나 음성 복호화 모드({@code voice.decrypt.mode})와 상관없다. 암호문이 오면
 * 풀려고 하지 않고 그 건만 실패시킨다(원천 파일 확인 안내).</p>
 *
 * <p><b>예전 동작</b>({@code image.decrypt=ENC_YN}): 복호화 모드가 REAL 이고 원천이 암호화로 표시된 건만
 * {@link MediaDecryptor}(접견과 같은 RVS 키 {@code voice.decrypt.rvs-key-path})로 푼다.</p>
 *
 * <p>결과가 이미지로 판별되지 않으면 저장하지 않고 실패시킨다 — 깨진 사진이 매핑되면 화면에 그대로 걸린다.</p>
 *
 * <p>저장은 {@code {output}/{교정번호}/{교정번호}_{순번}.{확장자}}. 임시 파일에 쓰고 옮긴다 — 읽는 쪽이 반쯤 쓴 파일을
 * 보지 않게. 같은 수용자의 옛 사진 파일은 지운다(매핑은 최신 하나만 가리킨다).</p>
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class ImageFileStore {

    private final VoiceProperties voiceProps;
    private final VoiceModeState modeState;
    private final VoiceDirState dirs;
    private final ImageProperties props;

    private volatile CachedKey cachedKey;

    private record CachedKey(String path, long modified, MediaDecryptor decryptor) {}

    public record Saved(Path path, long size, String ext) {}

    /** 사진 저장소 루트 — 설정이 비면 {@code {ROOT_DIR}/image}. */
    public Path outputRoot() {
        String o = props.outputDir();
        return StringUtils.hasText(o) ? Path.of(o.trim()) : Path.of(dirs.baseDir(), "image");
    }

    /**
     * 사진을 암호문으로 다루는가 — {@code image.decrypt=ENC_YN} 이고 복호화 모드가 REAL 일 때만. 기본(SKIP)이면 늘 아니다.
     * 더미·시뮬레이션 사진을 만들 때도 이것으로 암호화 여부를 정한다(아니면 평문 · {@code CMMN_FILE_ENC_YN='N'}).
     */
    public boolean photosEncrypted() {
        return props.decrypt() == ImageProperties.Decrypt.ENC_YN
                && modeState.decrypt() == VoiceProperties.DecryptMode.REAL;
    }

    /**
     * 이 건을 복호화하지 않는 까닭 — 실행 기록·실패 사유용. 복호화 대상이면 null.
     * (정책 SKIP → 모드가 REAL 아님 → 원천 표시 'N' 순으로 본다)
     */
    public String skipReason(ImageTarget t) {
        if (props.decrypt() == ImageProperties.Decrypt.SKIP) {
            return "사진 평문 정책 image.decrypt=SKIP";
        }
        if (modeState.decrypt() != VoiceProperties.DecryptMode.REAL) {
            return "복호화 모드 " + modeState.decrypt();
        }
        return t.encrypted() ? null : "원천 표시 CMMN_FILE_ENC_YN=N";
    }

    /** 이 건을 복호화하는가 — {@link #skipReason} 가 없을 때(예전 동작 · REAL · 원천 암호화 표시)만. */
    public boolean decrypts(ImageTarget t) {
        return skipReason(t) == null;
    }

    /**
     * 받은 사진을 확인한다 — 복호화 대상({@link #decrypts})이면 메모리에서 풀고(사진은 수백 KB 다), 아니면 받은 그대로 둔다.
     *
     * @throws IllegalStateException 키가 없거나, 결과가 이미지가 아니다
     */
    public byte[] decrypt(ImageTarget t, byte[] received) throws Exception {
        String skip = skipReason(t);
        byte[] plain = skip == null ? decryptor().decrypt(received) : received;
        if (ImageFormat.detect(plain) == null) {
            String encYn = "CMMN_FILE_ENC_YN=" + (t.encrypted() ? "Y" : "N");
            throw new IllegalStateException(skip == null
                    ? "복호화 결과가 이미지가 아니다 — 키(voice.decrypt.rvs-key-path) 또는 암호화 여부(" + encYn + ") 확인"
                    : props.decrypt() == ImageProperties.Decrypt.SKIP
                    ? "받은 파일이 이미지가 아니다 — 사진은 평문으로 받는다(image.decrypt=SKIP · 복호화 안 함 · " + encYn
                            + ") · 암호문이면 원천 파일 확인"
                    : "받은 파일이 이미지가 아니다 — 복호화 안 함(" + skip + ") · 암호문이면 복호화 모드 REAL · 원천 "
                            + encYn + " 확인");
        }
        return plain;
    }

    /** 저장한다 — 임시 파일에 쓰고 옮긴다. 같은 수용자의 다른(옛) 사진 파일은 지운다. */
    public Saved save(ImageTarget t, byte[] plain) throws IOException {
        ImageFormat f = ImageFormat.detect(plain);
        String ext = f == null ? "bin" : f.ext();
        Path dir = outputRoot().resolve(safe(t.corrNo()));
        Files.createDirectories(dir);
        Path dest = dir.resolve(safe(t.corrNo()) + "_" + t.imageSn() + "." + ext);
        Path tmp = dir.resolve("." + dest.getFileName() + ".part");
        Files.write(tmp, plain);
        try {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);   // ATOMIC_MOVE 를 못 하는 파일시스템
        }
        try (Stream<Path> s = Files.list(dir)) {
            for (Path old : s.filter(p -> !p.equals(dest) && Files.isRegularFile(p)
                    && !p.getFileName().toString().startsWith(".")).toList()) {
                Files.deleteIfExists(old);
            }
        }
        return new Saved(dest, plain.length, ext);
    }

    /** 교정번호를 경로에 쓸 수 있게 — 영숫자·하이픈·언더스코어만 남긴다. */
    static String safe(String s) {
        return s == null ? "_" : s.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    /** 접견과 같은 RVS 키 복호화기 — 키 파일이 바뀌면 다시 읽는다. */
    private MediaDecryptor decryptor() throws IOException {
        String keyPath = voiceProps.decrypt().rvsKeyPath();
        if (!StringUtils.hasText(keyPath)) {
            throw new IllegalStateException("복호화 키 경로가 없다 — voice.decrypt.rvs-key-path");
        }
        Path p = Path.of(keyPath.trim());
        long mod = Files.getLastModifiedTime(p).toMillis();
        CachedKey c = cachedKey;
        if (c == null || !c.path().equals(p.toString()) || c.modified() != mod) {
            c = new CachedKey(p.toString(), mod, MediaDecryptor.fromKeyFile(p));
            cachedKey = c;
        }
        return c.decryptor();
    }

    /** 이 수용자의 저장 폴더를 지운다 — 시뮬레이션 정리용. */
    public int deleteCorrDir(String corrNo) {
        Path dir = outputRoot().resolve(safe(corrNo));
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        int n = 0;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path f : s.toList()) {
                Files.deleteIfExists(f);
                n++;
            }
            Files.deleteIfExists(dir);
        } catch (IOException e) {
            log.warn("[Image] 저장 폴더 정리 실패 — {} ({})", dir, e.getMessage());
        }
        return n;
    }
}
