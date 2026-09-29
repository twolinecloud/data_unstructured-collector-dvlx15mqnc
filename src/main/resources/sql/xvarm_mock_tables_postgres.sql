-- ════════════════════════════════════════════════════════════════════════════
--  XVARM DB MOCK (개발계) — 개발계 borami-db(PostgreSQL)에 누락된 두 테이블을 만든다
-- ════════════════════════════════════════════════════════════════════════════
--  SimulationDataService.ensureXvarmMockTables() 가 실행한다(voice.source.xvarm-mode=MOCK_DEV 이고 DB 가 PostgreSQL 일 때).
--  __SM__ / __XVARM__ 는 voice.source.xvarm-mock.schema-smsm / schema-xvarm (기본 sm / xvarm) 로 치환된다.
--  사람이 DBeaver 에서 돌리는 판은 ref/borami_missing_tables.sql — 이 파일과 DDL 을 같게 유지할 것.
--
--  컬럼 정의 근거: ref/c9uviulsfMXZ-비정형-140926-020258.pdf (테이블 정의서) — TB_SMSM_CMFI_BS 17컬럼 전체.
--  ASYSCONTENTELEMENT 는 XVARM 솔루션 소유 테이블이라 사양이 없다 — 조인에 쓰는 ELEMENTID·FILEKEY 만 둔다.
--  ⚠ 문장은 세미콜론으로 나눈다. 문자열 안에 세미콜론을 쓰지 말 것.
-- ════════════════════════════════════════════════════════════════════════════

CREATE SCHEMA IF NOT EXISTS __SM__;

CREATE TABLE IF NOT EXISTS __SM__.tb_smsm_cmfi_bs (
    cmmn_file_id        VARCHAR(20)   NOT NULL,   -- 공통파일ID (= TB_RERD_TFIN_DS.TBLT_VTR_FILE_ID)
    doc_id              VARCHAR(20)   NOT NULL,   -- 문서ID → ASYSCONTENTELEMENT.ELEMENTID
    file_nm             VARCHAR(500),             -- 파일명
    corr_wrk_se_cd      VARCHAR(2),               -- 교정업무구분코드
    file_ty_cd          VARCHAR(1),               -- 파일유형코드
    file_dcf_cd         VARCHAR(7),               -- 파일상세분류코드
    wrk_idfc_vl         VARCHAR(100),             -- 업무식별값
    reg_dt              TIMESTAMP,                -- 등록일시
    sort_sq             NUMERIC(20,0),            -- 정렬순서
    rprs_yn             CHAR(1),                  -- 대표여부
    cmmn_file_enc_yn    CHAR(1),                  -- 공통파일암호화여부 — 'N' 인 파일을 복호화하면 원본이 깨진다
    del_yn              CHAR(1),                  -- 삭제여부
    atrz_file_use_yn    CHAR(1),                  -- 결재파일사용여부
    crt_dt              TIMESTAMP     NOT NULL,   -- 생성일시
    crt_usr_id          VARCHAR(40)   NOT NULL,   -- 생성사용자ID
    mdfcn_dt            TIMESTAMP     NOT NULL,   -- 수정일시
    mdfcn_usr_id        VARCHAR(40)   NOT NULL,   -- 수정사용자ID
    CONSTRAINT pk_tb_smsm_cmfi_bs PRIMARY KEY (cmmn_file_id)
);

CREATE INDEX IF NOT EXISTS ix_smsm_cmfi_doc_id ON __SM__.tb_smsm_cmfi_bs (doc_id);

CREATE SCHEMA IF NOT EXISTS __XVARM__;

CREATE TABLE IF NOT EXISTS __XVARM__.asyscontentelement (
    elementid   VARCHAR(20)    NOT NULL,   -- = TB_SMSM_CMFI_BS.DOC_ID
    filekey     VARCHAR(1000),             -- 물리 파일 경로 추적 키 (시뮬레이션: 원본 더미 파일의 절대 경로)
    CONSTRAINT pk_asyscontentelement PRIMARY KEY (elementid)
);
