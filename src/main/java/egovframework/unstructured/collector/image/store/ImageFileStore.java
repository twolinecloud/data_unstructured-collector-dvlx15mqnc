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
 * 받은 사진을 <b>복호화</b>해 Target 저장소에 둔다.
 *
 * <p><b>복호화 = 접견 음성과 같은 모듈</b>: {@link MediaDecryptor}(RVS 키 {@code voice.decrypt.rvs-key-path}) —
 * 접견 복호화기({@code MeetRvsDecryptor})가 쓰는 그 알고리즘·키다. 복호화 모드가 {@code SKIP} 이거나 공통파일기본의
 * 암호화 여부가 'N' 이면 받은 그대로 둔다.</p>
 *
 * <p>복호화 결과가 이미지로 판별되지 않으면 저장하지 않고 실패시킨다 — 깨진 사진이 매핑되면 화면에 그대로 걸린다.</p>
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
     * 복호화한다 — 메모리에서(사진은 수백 KB 다).
     *
     * @throws IllegalStateException 키가 없거나, 결과가 이미지가 아니다
     */
    public byte[] decrypt(ImageTarget t, byte[] received) throws Exception {
        byte[] plain = received;
        boolean real = modeState.decrypt() == VoiceProperties.DecryptMode.REAL;
        if (real && t.encrypted()) {
            plain = decryptor().decrypt(received);
        }
        if (ImageFormat.detect(plain) == null) {
            throw new IllegalStateException(real && t.encrypted()
                    ? "복호화 결과가 이미지가 아니다 — 키(voice.decrypt.rvs-key-path) 또는 암호화 여부(CMMN_FILE_ENC_YN) 확인"
                    : "받은 파일이 이미지가 아니다 — 암호화된 파일이면 복호화 모드를 REAL 로(지금 " + modeState.decrypt() + ")");
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
