package egovframework.unstructured.collector.common.decrypt;

import egovframework.unstructured.collector.common.config.VoiceModeState;
import egovframework.unstructured.collector.common.config.VoiceProperties.DecryptMode;
import egovframework.unstructured.collector.common.model.VoiceFile;
import egovframework.unstructured.collector.voice.perf.PerfStage;
import egovframework.unstructured.collector.voice.perf.PerfStageMeter;
import egovframework.unstructured.collector.common.util.AudioFormatDetector;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/**
 * 복호화 라우터 — 파일 하나에 어떤 복호화기를 쓸지 정한다.
 *
 * <p><b>판정 순서</b></p>
 * <ol>
 *   <li>{@code mode = SKIP} → 무조건 통과. 복호화 주체가 확정되기 전의 기본값이다(Q13).</li>
 *   <li>{@code encrypted = false} → 통과. {@code CMMN_FILE_ENC_YN='N'} 인 파일을
 *       복호화하면 멀쩡한 원본이 깨진다.</li>
 *   <li>그 외 → {@code supports()} 가 참인 첫 구현. 없으면 통과.</li>
 * </ol>
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class DecryptService {

    private final VoiceModeState state;
    private final List<AudioDecryptor> decryptors;
    private final NoopDecryptor noop;
    private final PerfStageMeter meter;

    public VoiceFile decrypt(VoiceFile file) {
        if (state.decrypt() == DecryptMode.SKIP) {
            return noop.decrypt(file);
        }
        if (!file.target().encrypted()) {
            log.debug("[Decrypt] 암호화되지 않은 파일 — 통과 ({})", file.path().getFileName());
            return noop.decrypt(file);
        }
        AudioDecryptor chosen = decryptors.stream()
                .filter(d -> !(d instanceof NoopDecryptor))
                .filter(d -> d.supports(file))
                .findFirst()
                .orElse(noop);
        log.info("[Decrypt] {} 적용 — {}", chosen.name(), file.path().getFileName());
        // 성능 테스트가 복호화와 포맷 판별을 따로 본다 — 꺼져 있으면 시계를 읽지 않는다.
        long m = meter.start();
        VoiceFile decrypted = chosen.decrypt(file);
        meter.add(PerfStage.DECRYPT, m);
        m = meter.start();
        VoiceFile out = redetectFormat(decrypted);
        meter.add(PerfStage.FORMAT, m);
        return out;
    }

    /**
     * 복호화한 파일의 포맷을 <b>다시 판별한다</b>.
     *
     * <p>수신 시점의 파일은 암호문이라 매직 넘버가 읽히지 않아 확장자로 떨어진다 — 접견은
     * {@code .m4a} 라 내용이 WAV 여도 {@code m4a} 로 기록됐다. 그 라벨이 그대로 STT 로 넘어가는데,
     * MOCK 은 쓰지 않지만 NPU 는 {@code format} 을 그대로 받는다(사양 확정 시 Q9). 내용이 드러난
     * 지금 다시 보고 덮는다.</p>
     *
     * <p>판별에 실패하면 <b>원래 값을 둔다</b>. 포맷 라벨 하나 때문에 복호화까지 끝난 건을
     * 실패시킬 이유가 없다.</p>
     */
    private VoiceFile redetectFormat(VoiceFile decrypted) {
        if (!decrypted.decrypted()) {
            return decrypted;
        }
        try {
            String now = AudioFormatDetector.detect(decrypted.path(), decrypted.target().srcFileName());
            if (now == null || now.equals(decrypted.format()) || AudioFormatDetector.UNKNOWN.equals(now)) {
                return decrypted;
            }
            log.info("[Decrypt] 포맷 재판별 — {} · {} → {} (암호문일 때는 확장자로만 볼 수 있었다)",
                    decrypted.path().getFileName(), decrypted.format(), now);
            return decrypted.withFormat(now);
        } catch (IOException e) {
            log.debug("[Decrypt] 포맷 재판별 실패 — {} ({}) · 원래 값을 둔다",
                    decrypted.path().getFileName(), e.getMessage());
            return decrypted;
        }
    }

    /** 현재 모드(진단 표기용). */
    public String mode() {
        return state.decrypt().name();
    }
}
