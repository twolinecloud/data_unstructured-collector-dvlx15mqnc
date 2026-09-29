-- ════════════════════════════════════════════════════════════════════════════
--  borami-db(개발계 PostgreSQL) 누락 테이블 생성 · 시딩 — DBeaver 에서 바로 실행하는 판
-- ════════════════════════════════════════════════════════════════════════════
--  대상: sm.tb_smsm_cmfi_bs (공통파일기본) · xvarm.asyscontentelement (XVARM 콘텐츠 메타)
--  2026-09-12 확인 기준 borami-db 에는 이 두 테이블이 없어 접견 4단 조인이 돌지 않았다.
--
--  근거 문서
--    · 테이블 정의서: ref/c9uviulsfMXZ-비정형-140926-020258.pdf  — TB_SMSM_CMFI_BS 17컬럼 전체 (아래 컬럼 주석)
--    · 연계 규약    : ref/(발췌)교정청_표준인터페이스_설계서.pdf — ESB(FILE2FILE·DB2DB) 패턴, 테이블 정의는 없음
--    · ASYSCONTENTELEMENT 는 XVARM 솔루션 소유라 사양이 없다. 조인에 쓰는 ELEMENTID(=DOC_ID)·FILEKEY 만 둔다
--
--  voice-collector 는 voice.source.xvarm-mode=MOCK_DEV(기본) 일 때 같은 DDL 을 기동/생성 버튼에서 자동 실행한다
--  (src/main/resources/sql/xvarm_mock_tables_postgres.sql — DDL 을 이 파일과 같게 유지할 것).
--  시딩은 시뮬레이터 [시뮬레이션 데이터 생성] 이 하지만, 손으로 넣고 싶을 때를 위해 같은 값을 아래에 둔다.
--
--  실행 순서: ① DDL → ② 시딩(필요 시) → ③ 확인 쿼리
--  되돌리기: 맨 아래 [정리] 블록
-- ════════════════════════════════════════════════════════════════════════════

-- ─────────────────────────────────────────────────────────────────────────────
-- ① DDL
-- ─────────────────────────────────────────────────────────────────────────────
CREATE SCHEMA IF NOT EXISTS sm;

CREATE TABLE IF NOT EXISTS sm.tb_smsm_cmfi_bs (
    cmmn_file_id        VARCHAR(20)   NOT NULL,   -- 1  공통파일ID (= TB_RERD_TFIN_DS.TBLT_VTR_FILE_ID)
    doc_id              VARCHAR(20)   NOT NULL,   -- 2  문서ID → ASYSCONTENTELEMENT.ELEMENTID
    file_nm             VARCHAR(500),             -- 3  파일명
    corr_wrk_se_cd      VARCHAR(2),               -- 4  교정업무구분코드
    file_ty_cd          VARCHAR(1),               -- 5  파일유형코드
    file_dcf_cd         VARCHAR(7),               -- 6  파일상세분류코드
    wrk_idfc_vl         VARCHAR(100),             -- 7  업무식별값
    reg_dt              TIMESTAMP,                -- 8  등록일시
    sort_sq             NUMERIC(20,0),            -- 9  정렬순서
    rprs_yn             CHAR(1),                  -- 10 대표여부
    cmmn_file_enc_yn    CHAR(1),                  -- 11 공통파일암호화여부 — 'N' 인 파일을 복호화하면 원본이 깨진다
    del_yn              CHAR(1),                  -- 12 삭제여부
    atrz_file_use_yn    CHAR(1),                  -- 13 결재파일사용여부
    crt_dt              TIMESTAMP     NOT NULL,   -- 14 생성일시
    crt_usr_id          VARCHAR(40)   NOT NULL,   -- 15 생성사용자ID
    mdfcn_dt            TIMESTAMP     NOT NULL,   -- 16 수정일시
    mdfcn_usr_id        VARCHAR(40)   NOT NULL,   -- 17 수정사용자ID
    CONSTRAINT pk_tb_smsm_cmfi_bs PRIMARY KEY (cmmn_file_id)
);
COMMENT ON TABLE  sm.tb_smsm_cmfi_bs IS '공통파일기본 (voice-collector XVARM MOCK — 개발계 누락 테이블 보완)';
COMMENT ON COLUMN sm.tb_smsm_cmfi_bs.cmmn_file_id     IS '공통파일ID = 녹취파일내역.TBLT_VTR_FILE_ID';
COMMENT ON COLUMN sm.tb_smsm_cmfi_bs.doc_id           IS '문서ID = XVARM.ASYSCONTENTELEMENT.ELEMENTID';
COMMENT ON COLUMN sm.tb_smsm_cmfi_bs.cmmn_file_enc_yn IS '공통파일암호화여부 (Y/N) — 복호화 분기';
CREATE INDEX IF NOT EXISTS ix_smsm_cmfi_doc_id ON sm.tb_smsm_cmfi_bs (doc_id);

CREATE SCHEMA IF NOT EXISTS xvarm;

CREATE TABLE IF NOT EXISTS xvarm.asyscontentelement (
    elementid   VARCHAR(20)    NOT NULL,   -- = TB_SMSM_CMFI_BS.DOC_ID
    filekey     VARCHAR(1000),             -- 물리 파일 경로 추적 키 (시뮬레이션: 원본 더미 파일의 절대 경로)
    CONSTRAINT pk_asyscontentelement PRIMARY KEY (elementid)
);
COMMENT ON TABLE xvarm.asyscontentelement IS 'XVARM 콘텐츠 메타 (voice-collector XVARM MOCK — 개발계 누락 테이블 보완)';

-- ─────────────────────────────────────────────────────────────────────────────
-- ② 시딩 — 시뮬레이터 [시뮬레이션 데이터 생성] 과 같은 값 (접견 5건분). 이미 있으면 건너뛴다.
--    파일키는 수집기 원본 스토리지 경로다 (Windows C:/XVARM_ORIGINAL_VOICE_FILES/meet · K8s /k8s/XVARM_ORIGINAL_VOICE_FILES/meet).
--    개발계 파드 기준으로 적어 두었다 — 로컬이면 경로만 바꿔 쓴다.
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO sm.tb_smsm_cmfi_bs
    (cmmn_file_id, doc_id, file_nm, corr_wrk_se_cd, file_ty_cd, reg_dt, rprs_yn, cmmn_file_enc_yn, del_yn,
     crt_dt, crt_usr_id, mdfcn_dt, mdfcn_usr_id)
VALUES
    ('SIMCMFI0001', 'SIMDOC0001', 'mock_meet_001.m4a', '01', 'A', now(), 'Y', 'Y', 'N', now(), 'simadm', now(), 'simadm'),
    ('SIMCMFI0002', 'SIMDOC0002', 'mock_meet_002.m4a', '01', 'A', now(), 'Y', 'N', 'N', now(), 'simadm', now(), 'simadm'),  -- 암호화 N (복호화 통과 확인용)
    ('SIMCMFI0003', 'SIMDOC0003', 'mock_meet_003.m4a', '01', 'A', now(), 'Y', 'Y', 'N', now(), 'simadm', now(), 'simadm'),
    ('SIMCMFI0004', 'SIMDOC0004', 'mock_meet_004.m4a', '01', 'A', now(), 'Y', 'Y', 'N', now(), 'simadm', now(), 'simadm'),
    ('SIMCMFI0005', 'SIMDOC0005', 'mock_meet_005.m4a', '01', 'A', now(), 'Y', 'Y', 'N', now(), 'simadm', now(), 'simadm')
ON CONFLICT (cmmn_file_id) DO NOTHING;

INSERT INTO xvarm.asyscontentelement (elementid, filekey) VALUES
    ('SIMDOC0001', '/k8s/XVARM_ORIGINAL_VOICE_FILES/meet/mock_meet_001.m4a'),
    ('SIMDOC0002', '/k8s/XVARM_ORIGINAL_VOICE_FILES/meet/mock_meet_002.m4a'),
    ('SIMDOC0003', '/k8s/XVARM_ORIGINAL_VOICE_FILES/meet/mock_meet_003.m4a'),
    ('SIMDOC0004', '/k8s/XVARM_ORIGINAL_VOICE_FILES/meet/mock_meet_004.m4a'),
    ('SIMDOC0005', '/k8s/XVARM_ORIGINAL_VOICE_FILES/meet/mock_meet_005.m4a')
ON CONFLICT (elementid) DO NOTHING;

-- (참고) 4단 조인이 서려면 앞 두 단(im.tb_imsc_ptpr_dt · re.tb_rerd_tfin_ds)에도 짝이 되는 행이 있어야 한다.
--   · 특이수용자 SIM00000000000001~05 (SPECL_MNG_SE_CD 1/2/3/0/5, PTCR_PRSR_RMV_YMD NULL)
--   · 녹취파일내역 SIM-MEET-001~005 (CORR_NO = 위 수용자, TBLT_VTR_FILE_ID = SIMCMFI0001~05, DEL_YN/RECRD_FILE_DEL_YN 'N',
--     CRT_DT: 001~003 = 어제 09:10/09:20/09:30 · 004~005 = 지금-6분/지금-3분)
--   시뮬레이터 [시뮬레이션 데이터 생성] 이 이 행들과 전화 5건(im.tb_imph_ucdr_ds SIM-PHONE-001~005)까지 한 번에 넣는다.

-- ─────────────────────────────────────────────────────────────────────────────
-- ③ 확인
-- ─────────────────────────────────────────────────────────────────────────────
SELECT 'sm.tb_smsm_cmfi_bs' AS tbl, COUNT(*) FROM sm.tb_smsm_cmfi_bs
UNION ALL
SELECT 'xvarm.asyscontentelement', COUNT(*) FROM xvarm.asyscontentelement;

-- 접견 4단 조인 (수집기 selectMeetTargets 와 같은 구조)
SELECT R.TARE_FILE_NO, R.CORR_NO, F.DOC_ID, X.FILEKEY, F.CMMN_FILE_ENC_YN, R.TARE_FILE_NM, R.CRT_DT
FROM re.tb_rerd_tfin_ds R
INNER JOIN im.tb_imsc_ptpr_dt P ON P.CORR_NO = R.CORR_NO AND P.PTCR_PRSR_RMV_YMD IS NULL
                                AND P.SPECL_MNG_SE_CD IN ('0','1','2','3','5')
INNER JOIN sm.tb_smsm_cmfi_bs   F ON F.CMMN_FILE_ID = R.TBLT_VTR_FILE_ID AND F.DEL_YN = 'N'
LEFT  JOIN xvarm.asyscontentelement X ON X.ELEMENTID = F.DOC_ID
WHERE R.DEL_YN = 'N' AND R.RECRD_FILE_DEL_YN = 'N' AND R.TBLT_VTR_FILE_ID IS NOT NULL
ORDER BY R.CRT_DT, R.TARE_FILE_NO;

-- ─────────────────────────────────────────────────────────────────────────────
-- [정리] 시뮬레이션 행만 지운다 (SIM 접두 — 운영 행은 걸리지 않는다). 테이블 자체는 남긴다.
-- ─────────────────────────────────────────────────────────────────────────────
-- DELETE FROM xvarm.asyscontentelement WHERE elementid LIKE 'SIMDOC%';
-- DELETE FROM sm.tb_smsm_cmfi_bs       WHERE cmmn_file_id LIKE 'SIMCMFI%';
-- DELETE FROM re.tb_rerd_tfin_ds       WHERE tare_file_no LIKE 'SIM-MEET-%';
-- DELETE FROM im.tb_imph_ucdr_ds       WHERE vrfc_estl_id LIKE 'SIM-PHONE-%';
-- DELETE FROM im.tb_imsc_ptpr_dt       WHERE corr_no LIKE 'SIM%';
-- 테이블까지 없애려면: DROP TABLE xvarm.asyscontentelement; DROP TABLE sm.tb_smsm_cmfi_bs;
