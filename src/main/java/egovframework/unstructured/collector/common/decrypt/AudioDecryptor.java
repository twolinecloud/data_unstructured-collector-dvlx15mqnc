package egovframework.unstructured.collector.common.decrypt;

import egovframework.unstructured.collector.common.model.VoiceFile;

/**
 * 암호화된 음성 파일을 푼다.
 *
 * <p><b>접견과 전화의 방식이 다르다.</b></p>
 * <ul>
 *   <li>전화 — {@code TELP_RECRD_FILE_ID} 를 키로 {@code JAVA ARIA-128 bit / AES-256}</li>
 *   <li>접견 — XVARM 은 다운로드만 하고 복호화는 R플레이어(HTML/JS)가 한다.
 *       그 로직을 백엔드로 포팅해야 한다(2026-09-11 회의 {@code [00:02:05]}).</li>
 * </ul>
 *
 * <p><b>⚠ 전화 복호화의 주체가 확정되지 않았다</b>(계획서 Q13). 2026-07-28 메타빌드 협의 메일에는
 * 연계 구간에서 "파일 전송/<b>복호화</b>"가 끝난다고 되어 있고, 2026-09-11 회의에서는 우리가
 * 복호화하는 것으로 이야기됐다. 어느 쪽으로 결론이 나도 코드를 고치지 않도록
 * {@link NoopDecryptor} 를 두고 스위치로 전환한다.</p>
 */
public interface AudioDecryptor {

    /** 이 구현이 해당 파일을 처리할 수 있는가. */
    boolean supports(VoiceFile file);

    /**
     * 복호화한다.
     *
     * @return 복호화 산출물을 가리키는 새 {@link VoiceFile}
     */
    VoiceFile decrypt(VoiceFile file);

    String name();
}
