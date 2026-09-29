-- 로컬 H2 Admin DB — 이미지 파이프라인을 접속 정보 없이 돌려 보기 위한 최소 스키마.
--   개발계·운영 PostgreSQL 은 db/admin/V1__tb_src_inmate_photo.sql 을 관리자가 적용한다.
--   AdminDb 가 url 이 jdbc:h2: 일 때만 실행한다(DATABASE_TO_LOWER=TRUE — 이름은 소문자).
CREATE SCHEMA IF NOT EXISTS kcais;

-- 수용자 기본(정형 수집기 소유) — PHOTO_REF 일괄 반영을 시험할 만큼만
CREATE TABLE IF NOT EXISTS kcais.tb_src_inmate_bs (
    corr_no   VARCHAR(20)  NOT NULL,
    prsr_nm   VARCHAR(60),
    photo_ref VARCHAR(300),
    reg_dtm   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    mod_dtm   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_src_inmate_bs PRIMARY KEY (corr_no)
);

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
    reg_dtm            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    mod_dtm            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_src_inmate_photo PRIMARY KEY (corr_no)
);
