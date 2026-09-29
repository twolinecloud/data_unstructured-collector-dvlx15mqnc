package egovframework.unstructured.collector.common.decrypt;

import egovframework.unstructured.collector.common.model.VoiceFile;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

/**
 * 아무것도 하지 않는 복호화기 — 파일을 그대로 통과시킨다.
 *
 * <p><b>두 경우에 쓴다.</b></p>
 * <ol>
 *   <li>{@code CMMN_FILE_ENC_YN = 'N'} — 애초에 암호화되지 않은 파일이다.
 *       <b>이걸 복호화하면 멀쩡한 원본이 깨진다.</b></li>
 *   <li>{@code voice.decrypt.mode = SKIP} — 복호화 주체가 확정되기 전(Q13)의 기본값.
 *       연계 구간에서 이미 평문으로 들어온다면 이 상태가 최종 형태가 된다.</li>
 * </ol>
 */
@Log4j2
@Component
public class NoopDecryptor implements AudioDecryptor {

    @Override
    public boolean supports(VoiceFile file) {
        return true;   // 최후의 기본값 — DecryptService 가 가장 마지막에 고른다
    }

    @Override
    public VoiceFile decrypt(VoiceFile file) {
        log.debug("[Decrypt:NOOP] 통과 — {}", file.path().getFileName());
        return file;
    }

    @Override
    public String name() {
        return "NOOP";
    }
}
