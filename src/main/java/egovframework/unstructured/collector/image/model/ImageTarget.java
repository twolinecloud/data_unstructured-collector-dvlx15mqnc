package egovframework.unstructured.collector.image.model;

/**
 * 수집할 수용자 사진 한 건 — 보라미 조회 3단계의 결과.
 *
 * <ol>
 *   <li>{@code TB_IRIM_BSIF_DS} — {@code IMAGE_SE_CD='1'} 중 수용자별 {@code IMAGE_SN} 최대(최신) 1건의 {@code IMAGE_CMMN_FILE_ID}</li>
 *   <li>{@code TB_SMSM_CMFI_BS} — {@code CMMN_FILE_ID = IMAGE_CMMN_FILE_ID} 의 {@code DOC_ID}(· 파일명 · 암호화 여부)</li>
 *   <li>{@code ASYSCONTENTELEMENT} — {@code ELEMENTID = DOC_ID} 의 {@code FILEKEY}</li>
 * </ol>
 *
 * @param docId     2단계 결과. 공통파일기본에 없으면 null
 * @param fileKey   3단계 결과. XVARM 에 없으면 null
 * @param encrypted 공통파일기본의 암호화 여부 — {@code 'N'} 인 파일을 복호화하면 원본이 깨진다
 */
public record ImageTarget(
        String corrNo,
        int imageSn,
        String imageCmmnFileId,
        String docId,
        String fileKey,
        String fileName,
        boolean encrypted
) {

    /** 1단계만 채운 건 — 2·3단계가 채운다. */
    public static ImageTarget latest(String corrNo, int imageSn, String imageCmmnFileId) {
        return new ImageTarget(corrNo, imageSn, imageCmmnFileId, null, null, null, true);
    }

    public ImageTarget withFile(String docId, String fileKey, String fileName, boolean encrypted) {
        return new ImageTarget(corrNo, imageSn, imageCmmnFileId, docId, fileKey, fileName, encrypted);
    }

    /** 브로커 요청 키 — {@code IMG-{execId}-{교정번호}-{순번}}. 배치마다 달라 브로커 멱등 캐시에 걸리지 않는다. */
    public String requestId(String execId) {
        return "IMG-" + execId + "-" + corrNo + "-" + imageSn;
    }

    /** 수신 폴더에 떨어질 이름 — 접견 음성 파일과 섞이지 않게 접두를 둔다. */
    public String receiveName() {
        return "img_" + corrNo + "_" + imageSn + ".bin";
    }

    public String shortId() {
        return corrNo + "#" + imageSn;
    }
}
