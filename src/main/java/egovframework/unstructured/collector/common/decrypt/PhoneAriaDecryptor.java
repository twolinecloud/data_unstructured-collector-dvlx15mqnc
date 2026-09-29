package egovframework.unstructured.collector.common.decrypt;

import egovframework.unstructured.collector.common.model.VoiceFile;
import egovframework.unstructured.collector.common.model.VoiceKind;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 전화 녹음 복호화 — {@code JAVA ARIA-128 bit / AES-256}, 키는 {@code TELP_RECRD_FILE_ID}.
 *
 * <p><b>아직 구현하지 않았다.</b> 스펙 문서에 적힌 것은 알고리즘 이름 한 줄뿐이고,
 * 실제로 복호화하려면 아래가 모두 필요한데 하나도 확정되지 않았다.</p>
 * <ul>
 *   <li>ARIA-128 과 AES-256 중 <b>어느 것인지</b> — 문서는 둘을 슬래시로 병기하고 있다</li>
 *   <li>{@code TELP_RECRD_FILE_ID}(VARCHAR2 100) 에서 <b>키를 어떻게 파생</b>하는지
 *       (그대로? 해시? Base64 디코드?)</li>
 *   <li><b>모드와 IV</b> (CBC/GCM/CTR, IV 가 파일 앞부분에 붙는지 고정값인지)</li>
 *   <li>헤더·패딩 유무</li>
 * </ul>
 *
 * <p>추측으로 구현하면 "복호화는 했는데 재생이 안 되는" 파일이 조용히 STT 로 넘어간다.
 * 그래서 <b>확정 전까지는 명시적으로 실패</b>시킨다 — 잘못된 결과를 만드는 것보다 낫다.</p>
 *
 * <p>ARIA 는 JDK 표준 JCE 에 없다. 구현할 때 BouncyCastle
 * ({@code org.bouncycastle:bcprov-jdk18on}) 의존성이 추가로 필요하다.</p>
 */
@Log4j2
@Component
@Order(20)          // PhoneSimAesDecryptor(10) 가 Mock 데이터를 먼저 걸러 간다
public class PhoneAriaDecryptor implements AudioDecryptor {

    @Override
    public boolean supports(VoiceFile file) {
        return file.target().kind() == VoiceKind.PHONE
                && file.target().encrypted()
                && file.target().decryptKey() != null;
    }

    @Override
    public VoiceFile decrypt(VoiceFile file) {
        throw new UnsupportedOperationException(
                "전화 복호화 사양 미확정 — 알고리즘(ARIA-128/AES-256)·키 파생·모드/IV 가 정해지지 않았다. "
                        + "계획서 11장 Q13 참조. 확정 전에는 voice.decrypt.mode=SKIP 으로 둔다.");
    }

    @Override
    public String name() {
        return "PHONE_ARIA";
    }
}
