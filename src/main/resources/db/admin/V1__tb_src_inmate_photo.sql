-- ═══════════════════════════════════════════════════════════════════════════
--  V1 — 수용자 사진 매핑 테이블 (Admin DB · correction_ai · 스키마 kcais)
--  unstructured-collector 의 수용자 이미지 파이프라인이 적재한다. 2026-09-29
--
--  ⚠ 앱이 만들지 않는다 — 관리자가 배포 전에 적용한다(로그 컬렉터 V-스크립트와 같은 운영 방식).
--    적용 전에는 이미지 파이프라인이 'DB 매핑' 단계에서 실패로 남는다(파일 저장까지는 된다).
--
--  왜 TB_SRC_INMATE_BS.PHOTO_REF 에 바로 쓰지 않나 — 정형 수집기가 같은 테이블을 배치로 다시 쓰므로
--    두 배치의 순서에 따라 사진 경로가 지워지거나 옛 값으로 되돌아간다(Race Condition). 사진은 이 테이블에
--    수용자당 한 행으로 두고, PHOTO_REF 는 필요할 때 이 테이블에서 일괄 반영한다(POST /api/v1/image/photo-ref/sync).
--
--  갱신 규칙: 같은 수용자에 더 오래된 사진(IMAGE_SN 이 작은 것)이 뒤늦게 들어와도 덮지 않는다(STALE).
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS kcais.tb_src_inmate_photo (
    corr_no            VARCHAR(20)   NOT NULL,
    image_sn           SMALLINT      NOT NULL,
    image_cmmn_file_id VARCHAR(20),
    doc_id             VARCHAR(50),
    filekey            VARCHAR(1000) NOT NULL,
    photo_path         VARCHAR(300)  NOT NULL,
    file_size          BIGINT,
    file_ext           VARCHAR(10),
    proc_dtm           TIMESTAMP     NOT NULL,
    last_batch_exec_id VARCHAR(30),
    reg_dtm            TIMESTAMP     NOT NULL DEFAULT now(),
    mod_dtm            TIMESTAMP     NOT NULL DEFAULT now(),
    CONSTRAINT pk_src_inmate_photo PRIMARY KEY (corr_no)
);

COMMENT ON TABLE  kcais.tb_src_inmate_photo                    IS '수용자 사진 매핑 — 보라미 최신 사진(TB_IRIM_BSIF_DS IMAGE_SE_CD=1)의 저장 경로. unstructured-collector 적재';
COMMENT ON COLUMN kcais.tb_src_inmate_photo.corr_no            IS '교정번호 (PK)';
COMMENT ON COLUMN kcais.tb_src_inmate_photo.image_sn           IS '보라미 이미지순번 — 수용자별 최대값(최신)';
COMMENT ON COLUMN kcais.tb_src_inmate_photo.image_cmmn_file_id IS '보라미 이미지공통파일ID (TB_SMSM_CMFI_BS.CMMN_FILE_ID)';
COMMENT ON COLUMN kcais.tb_src_inmate_photo.doc_id             IS '문서ID (TB_SMSM_CMFI_BS.DOC_ID = ASYSCONTENTELEMENT.ELEMENTID)';
COMMENT ON COLUMN kcais.tb_src_inmate_photo.filekey            IS 'XVARM 파일키 (ASYSCONTENTELEMENT.FILEKEY)';
COMMENT ON COLUMN kcais.tb_src_inmate_photo.photo_path         IS '복호화한 사진의 저장 경로';
COMMENT ON COLUMN kcais.tb_src_inmate_photo.file_size          IS '저장 파일 크기(byte)';
COMMENT ON COLUMN kcais.tb_src_inmate_photo.file_ext           IS '이미지 형식(매직 넘버 판별) — jpg/png/gif/bmp/tif';
COMMENT ON COLUMN kcais.tb_src_inmate_photo.proc_dtm           IS '처리 일시';
COMMENT ON COLUMN kcais.tb_src_inmate_photo.last_batch_exec_id IS '마지막으로 적재한 배치 실행ID';
