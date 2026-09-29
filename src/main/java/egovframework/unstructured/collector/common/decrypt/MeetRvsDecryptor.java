package egovframework.unstructured.collector.common.decrypt;

import egovframework.unstructured.collector.common.config.VoiceDirState;
import egovframework.unstructured.collector.common.config.VoiceProperties;
import egovframework.unstructured.collector.common.model.VoiceFile;
import egovframework.unstructured.collector.common.model.VoiceKind;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Path;

/**
 * 접견 녹음 복호화 — R플레이어({@code rvs-media-decryptor.html})와 같은 규약의 <b>AES/CBC/PKCS5Padding</b>.
 *
 * <p>XVARM 은 파일을 임시 폴더로 <b>내려받기만</b> 하고 복호화는 화면의 R플레이어가 한다
 * (2026-09-11 회의 {@code [00:01:43]}~{@code [00:02:09]}). 배치가 STT 로 넘기기 직전에
 * 여기서 같은 일을 한다.</p>
 *
 * <p><b>키는 코드에 박지 않는다.</b> {@code voice.decrypt.rvs-key-path} 가 가리키는 파일에서
 * 읽는다. 소스에 넣으면 레포에 그대로 올라가고, 키가 바뀔 때 재배포해야 한다.
 * <b>경로가 비어 있으면 실패시킨다</b> — 조용히 통과시키면 암호화된 파일이 STT 로 넘어가
 * "복호화는 됐다는데 인식이 안 된다" 가 된다.</p>
 *
 * <p><b>키는 한 번만 읽는다.</b> 1,300건마다 파일을 여는 것은 낭비이고, 배치 도중 키 파일이
 * 바뀌면 같은 배치 안에서 건마다 다른 키를 쓰게 된다.</p>
 */
@Log4j2
@Component
@Order(10)
@RequiredArgsConstructor
public class MeetRvsDecryptor implements AudioDecryptor {

    /** 복호화 산출물 접두 — 참조 구현(R플레이어)이 내려주는 이름과 맞춘다. */
    private static final String PREFIX = "decrypted_";

    private final VoiceProperties props;
    private final VoiceDirState dirs;

    /** 지연 초기화 — REAL 모드로 처음 쓸 때 읽는다. SKIP 으로만 도는 환경은 키가 없어도 기동한다. */
    private volatile MediaDecryptor decryptor;

    @Override
    public boolean supports(VoiceFile file) {
        return file.target().kind() == VoiceKind.MEET && file.target().encrypted();
    }

    @Override
    public VoiceFile decrypt(VoiceFile file) {
        MediaDecryptor md = decryptor();
        Path src = file.path();
        // 작업 폴더에 둔다 — 수신 폴더에 쓰면 다음 배치가 그것을 새 수신 파일로 본다.
        //   배치가 끝나면 VoiceCollectService 의 toClean 이 지운다(성공·실패 무관).
        Path dest = Path.of(dirs.work()).resolve(PREFIX + src.getFileName());
        long t0 = System.currentTimeMillis();
        try {
            long size = md.decryptToFile(src, dest);
            log.info("[Decrypt:MEET_RVS] {} → {} ({} bytes, {}ms)",
                    src.getFileName(), dest.getFileName(), size, System.currentTimeMillis() - t0);
            return file.withPath(dest, size, true);
        } catch (javax.crypto.BadPaddingException | javax.crypto.IllegalBlockSizeException e) {
            // 키·IV 가 맞지 않거나 입력이 애초에 암호문이 아니다. 둘을 구분할 방법이 없으므로
            // 둘 다 적어 준다 — 이 메시지가 T4 에 그대로 남는다.
            throw new IllegalStateException(
                    "접견 복호화 실패 — 키·IV 가 맞지 않거나 암호화된 파일이 아닙니다 (%s · %s). [%s]"
                            .formatted(src.getFileName(), md.describe(), e.getClass().getSimpleName()), e);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "접견 복호화 실패 — %s (%s)".formatted(src.getFileName(), rootMessage(e)), e);
        }
    }

    /** 키 파일을 한 번만 읽어 붙든다. 실패하면 사유를 그대로 올린다(기동이 아니라 첫 사용에서 난다). */
    private MediaDecryptor decryptor() {
        MediaDecryptor d = decryptor;
        if (d != null) {
            return d;
        }
        synchronized (this) {
            if (decryptor == null) {
                String keyPath = props.decrypt().rvsKeyPath();
                if (!StringUtils.hasText(keyPath)) {
                    throw new IllegalStateException(
                            "접견 복호화 키 경로가 비어 있습니다 — voice.decrypt.rvs-key-path (env VOICE_RVS_KEY_PATH) 를 설정하십시오. "
                                    + "복호화를 건너뛰려면 모드를 SKIP 으로 두십시오");
                }
                decryptor = MediaDecryptor.fromKeyFile(Path.of(keyPath.trim()));
                log.info("[Decrypt:MEET_RVS] 키 적재 — {} · {}", keyPath, decryptor.describe());
            }
            return decryptor;
        }
    }

    @Override
    public String name() {
        return "MEET_RVS";
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String m = cur.getMessage();
        return m == null ? cur.getClass().getSimpleName() : m.replaceAll("\\s+", " ").trim();
    }
}
