package egovframework.unstructured.collector.common.model;

/**
 * 음성 종류 — 수집 경로가 완전히 다르다.
 *
 * <p>접견은 보라미 DB 4단 조인으로 파일 키를 찾아 XVARM 브로커에 추출을 요청해야 하고,
 * 전화는 별도 서버·별도 ESB 프로바이더를 통해 들어온다(2026-09-11 회의).</p>
 */
public enum VoiceKind {

    /** 접견 녹음 — TB_RERD_TFIN_DS → TB_SMSM_CMFI_BS → XVARM.ASYSCONTENTELEMENT */
    MEET("접견"),

    /** 전화 녹음 — TB_IMPH_UCDR_DS 단일 테이블 */
    PHONE("전화");

    private final String label;

    VoiceKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
