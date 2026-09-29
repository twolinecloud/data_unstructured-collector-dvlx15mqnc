package egovframework.unstructured.collector.common.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 로그·연동에 쓸 <b>비식별 수용자 ID(INMATE_PID)</b> 를 만든다.
 *
 * <p>교정번호({@code CORR_NO})를 로그나 T4 처리 이력에 그대로 실으면 안 된다 —
 * 그 자체가 수용자를 특정하는 식별자다. 그렇다고 매번 난수를 쓰면 같은 사람의 여러 건이
 * 서로 다른 ID 를 받아 통계가 무너진다.</p>
 *
 * <p>그래서 <b>결정적 해시</b>를 쓴다. 같은 교정번호는 항상 같은 PID 가 되고,
 * PID 만으로는 교정번호를 되돌릴 수 없다. salt 를 바꾸면 전체 PID 체계가 바뀌므로
 * 배포 환경마다 고정해 두어야 한다.</p>
 *
 * <p><b>재식별 매핑은 이 서비스의 일이 아니다</b> — {@code TB_IDENTITY_MAP}·
 * {@code TB_INMATE_HISTORY_MAP} 적재는 데이터 수집 서비스가 전담한다(R&amp;R 확정).
 * 여기서 만드는 것은 <b>로그 적재용 표식</b>일 뿐이다.</p>
 */
@Component
public class InmatePidGenerator {

    /**
     * 해시 salt. 환경마다 고정해야 같은 사람이 같은 PID 를 유지한다.
     * 운영에서는 Secret 으로 주입한다.
     *
     * <p>⚠ 기본값이 옛 서비스명(kcais-voice-collector)이지만 이관 뒤에도 바꾸지 않는다 — 바꾸면 같은
     * 수용자의 PID 가 달라져 이미 적재된 T4·T5 이력과 이어지지 않는다.</p>
     */
    @Value("${voice.inmate-pid.salt:kcais-voice-collector}")
    private String salt;

    /** 접두어 — 로그에서 이 값이 PID 임을 알아보기 위한 표식. */
    @Value("${voice.inmate-pid.prefix:VP}")
    private String prefix;

    /**
     * 교정번호로부터 PID 를 만든다.
     *
     * @return 예) {@code VP3f9a1c2b8d4e6071}
     */
    public String of(String corrNo) {
        if (corrNo == null || corrNo.isBlank()) {
            return prefix + "UNKNOWN";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((salt + "|" + corrNo).getBytes(StandardCharsets.UTF_8));
            // 16 hex = 64bit. 수용자 수 규모에서 충돌 확률은 무시할 수 있다.
            return prefix + HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없다", e);
        }
    }
}
