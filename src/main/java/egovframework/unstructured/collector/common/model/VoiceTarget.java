package egovframework.unstructured.collector.common.model;

import java.time.LocalDateTime;

/**
 * 수집 대상 1건 — 보라미 조회 결과를 담는다.
 *
 * <p><b>PII 를 일부러 담지 않는다.</b> {@code TB_IMPH_UCDR_DS} 에는 수신자명({@code RCVER_NM})·
 * 지인관계명({@code ACQT_RLTNS_NM})·국제전화번호({@code INTRL_TELNO}) 같은 개인정보가 그대로 있지만,
 * 이 서비스가 처리에 쓰는 것은 <b>파일을 찾는 키와 STT 텍스트뿐</b>이다. 메타 PII 를 들고 다니면
 * 로그·에러스택에 섞여 나갈 위험만 는다.</p>
 *
 * @param kind           접견 / 전화
 * @param corrNo         교정번호 {@code CORR_NO}
 * @param idempotencyKey 중복 처리 방지 키 — 접견 {@code TARE_FILE_NO}, 전화 {@code VRFC_ESTL_ID}
 * @param docId          접견 전용 — {@code TB_SMSM_CMFI_BS.DOC_ID}
 * @param fileKey        접견 전용 — {@code XVARM.ASYSCONTENTELEMENT.FILEKEY}
 * @param encrypted      암호화 여부 — 접견은 {@code CMMN_FILE_ENC_YN}. <b>N 인 파일을 복호화하면 원본이 깨진다.</b>
 * @param decryptKey     전화 전용 — {@code TELP_RECRD_FILE_ID} 가 복호화 KEY 를 겸한다
 * @param srcFilePath    원본 경로명 ({@code TARE_FLPTH_NM} / {@code TELP_RECRD_FLPTH_NM})
 * @param srcFileName    원본 파일명 — ESB 수신 파일이 {@code .DAT} 로 바뀌므로 포맷 판별에 쓴다
 * @param sourceSttPath  전화 전용 — {@code TELP_STT_FLPTH_NM}. 값이 있으면 보라미가 이미 STT 한 것일 수 있다(Q1)
 * @param occurredAt     접견일자 / 통화시작일시
 */
public record VoiceTarget(
        VoiceKind kind,
        String corrNo,
        String idempotencyKey,
        String docId,
        String fileKey,
        boolean encrypted,
        String decryptKey,
        String srcFilePath,
        String srcFileName,
        String sourceSttPath,
        LocalDateTime occurredAt
) {

    /** 로그·파일명에 쓸 짧은 식별자. 교정번호는 그 자체로 민감하므로 노출하지 않는다. */
    public String shortId() {
        return kind.name().toLowerCase() + "-" + idempotencyKey;
    }

    /** 보라미가 이미 STT 결과를 가지고 있는가 — 사실이면 우리가 다시 돌릴 필요가 없다. */
    public boolean hasSourceStt() {
        return sourceSttPath != null && !sourceSttPath.isBlank();
    }
}
