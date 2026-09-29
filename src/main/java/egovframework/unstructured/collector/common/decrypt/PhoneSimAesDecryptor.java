package egovframework.unstructured.collector.common.decrypt;

import egovframework.unstructured.collector.common.config.VoiceDirState;
import egovframework.unstructured.collector.common.config.VoiceModeState;
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
 * 전화 녹취 복호화 — <b>시뮬레이션 전용</b>. 실제 ARIA 사양이 아니다.
 *
 * <p><b>이것이 무엇이 아닌지부터</b>: 전화 녹취의 실제 복호화 사양은 아직 확정되지 않았다
 * (알고리즘 ARIA-128/AES-256 · 키 파생 · 모드/IV — 계획서 11장 Q13). 그 경로는
 * {@link PhoneAriaDecryptor} 가 그대로 막고 있다. 이 클래스는 <b>우리 Mock 제공자가 만든
 * 더미 파일</b>만 푼다.</p>
 *
 * <p><b>왜 필요한가</b>: 전화 메타는 {@code CMMN_FILE_ENC_YN='Y'} 로 seed 되는데
 * {@link egovframework.unstructured.collector.common.sync.MockPhoneFileProvider} 는 평문 WAV 를 떨궜다.
 * 그래서 복호화를 REAL 로 올리는 순간 전화 건이 전부 "사양 미확정" 으로 실패해,
 * 로컬에서 수집 → 복호화 → STT 전 구간을 REAL 로 돌려 볼 수가 없었다.
 * 이제 Mock 제공자가 접견과 같은 AES/CBC 로 암호화하고, 여기서 같은 키로 푼다.</p>
 *
 * <p><b>운영으로 새지 않는 이유</b>: {@code phone.mode == MOCK} 일 때만 걸린다.
 * 실물 연계({@code ESB})에서는 {@code supports()} 가 거짓이라 {@link PhoneAriaDecryptor} 로
 * 넘어가고, 거기서 사양이 없다고 분명히 말한다.</p>
 */
@Log4j2
@Component
@Order(10)          // ARIA 스텁(20)보다 먼저 본다 — Mock 데이터는 여기서 걸러야 한다
@RequiredArgsConstructor
public class PhoneSimAesDecryptor implements AudioDecryptor {

    private static final String PREFIX = "decrypted_";

    private final VoiceProperties props;
    private final VoiceDirState dirs;
    private final VoiceModeState modeState;

    private volatile MediaDecryptor decryptor;

    @Override
    public boolean supports(VoiceFile file) {
        return file.target().kind() == VoiceKind.PHONE
                && file.target().encrypted()
                && modeState.phone() == VoiceProperties.PhoneMode.MOCK;
    }

    @Override
    public VoiceFile decrypt(VoiceFile file) {
        MediaDecryptor md = decryptor();
        Path src = file.path();
        // 작업 폴더에 둔다 — 수신 폴더에 쓰면 다음 배치가 그것을 새 수신 파일로 본다.
        Path dest = Path.of(dirs.work()).resolve(PREFIX + src.getFileName());
        long t0 = System.currentTimeMillis();
        try {
            long size = md.decryptToFile(src, dest);
            log.info("[Decrypt:PHONE_SIM_AES] {} → {} ({} bytes, {}ms)",
                    src.getFileName(), dest.getFileName(), size, System.currentTimeMillis() - t0);
            return file.withPath(dest, size, true);
        } catch (javax.crypto.BadPaddingException | javax.crypto.IllegalBlockSizeException e) {
            throw new IllegalStateException(
                    "전화 복호화 실패(시뮬레이션) — 키·IV 가 맞지 않거나 암호화된 파일이 아닙니다 (%s · %s). "
                            .formatted(src.getFileName(), md.describe())
                            + "Mock 제공자가 같은 키로 암호화하므로, 이 오류는 키 파일이 중간에 바뀌었다는 뜻입니다. "
                            + "[%s]".formatted(e.getClass().getSimpleName()), e);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "전화 복호화 실패(시뮬레이션) — %s (%s)".formatted(src.getFileName(), e.getMessage()), e);
        }
    }

    /** 키 파일을 한 번만 읽어 붙든다 — 접견과 같은 키를 쓴다(Mock 제공자가 그 키로 암호화했다). */
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
                            "복호화 키 경로가 비어 있습니다 — voice.decrypt.rvs-key-path (env VOICE_RVS_KEY_PATH). "
                                    + "복호화를 건너뛰려면 모드를 SKIP 으로 두십시오");
                }
                decryptor = MediaDecryptor.fromKeyFile(Path.of(keyPath.trim()));
                log.info("[Decrypt:PHONE_SIM_AES] 키 적재 — {} · {}", keyPath, decryptor.describe());
            }
            return decryptor;
        }
    }

    @Override
    public String name() {
        return "PHONE_SIM_AES";
    }
}
