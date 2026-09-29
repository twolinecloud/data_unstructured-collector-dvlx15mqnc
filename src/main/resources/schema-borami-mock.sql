-- ════════════════════════════════════════════════════════════════════════════
--  보라미 Mock 스키마 — 음성 수집이 조회하는 4개 테이블 + XVARM 메타
-- ════════════════════════════════════════════════════════════════════════════
--  출처: c9uviulsfMXZ-비정형-120926-021740.pdf (교정본부 제공 전수 컬럼 정의)
--
--  ⚠ 실제 보라미는 Oracle(VARCHAR2·NUMBER·DATE)이고 이 스키마는 H2/PostgreSQL 용이다.
--    조회 SQL 이 두 DB 에서 모두 도는지 검증하려는 것이 목적이므로,
--    타입은 대응되는 것으로 바꾸되 길이는 원본 그대로 두었다.
--      VARCHAR2(n B) → VARCHAR(n) · NUMBER(p,s) → NUMERIC(p,s) · CHAR → CHAR(n)
--
--  ⚠ CHAR 컬럼 주의: 보라미의 날짜 일부가 CHAR(YYYYMMDD 문자열)다.
--    고정길이 CHAR 는 공백 패딩이 붙어 비교에서 어긋날 수 있다. 증분 기준 컬럼이
--    확정되지 않아(계획서 Q11) 현재 조회는 DATE 타입인 CRT_DT 를 쓴다.
--
--  ⚠ 이 스키마는 개발계 전용이다. 실제 보라미 접속 시 SQL_INIT_MODE=never 로 둘 것.
-- ════════════════════════════════════════════════════════════════════════════

DROP TABLE IF EXISTS TB_IMSC_PTPR_DT;
DROP TABLE IF EXISTS TB_RERD_TFIN_DS;
DROP TABLE IF EXISTS TB_SMSM_CMFI_BS;
DROP TABLE IF EXISTS TB_IMPH_UCDR_DS;
DROP TABLE IF EXISTS ASYSCONTENTELEMENT;

-- ── 특이수용자상세 (전체 18컬럼 중 조회에 쓰는 것) ──────────────────────────
CREATE TABLE TB_IMSC_PTPR_DT (
    CORR_NO                       VARCHAR(18)   NOT NULL,   -- 교정번호
    PTCR_PRSR_DTL_SN              NUMERIC(3,0)  NOT NULL,   -- 특이수용자상세순번
    SPECL_MNG_SE_CD               VARCHAR(1),               -- 특별관리구분코드 0조직 1마약 2관심 3엄격 5일일중점
    PTCR_PRSR_SE_CD               VARCHAR(3)    NOT NULL,   -- 특이수용자구분코드
    PTCR_PRSR_APNT_CORR_INSTT_CD  VARCHAR(7),               -- 지정교정기관코드
    PTCR_PRSR_APNT_YMD            CHAR(8)       NOT NULL,   -- 지정일자
    PTCR_PRSR_RMV_YMD             CHAR(8),                  -- 해제일자 — NULL 이면 지정 상태
    CRT_DT                        TIMESTAMP     NOT NULL,   -- 생성일시
    CRT_USR_ID                    VARCHAR(40)   NOT NULL,
    MDFCN_DT                      TIMESTAMP     NOT NULL,   -- 수정일시
    MDFCN_USR_ID                  VARCHAR(40)   NOT NULL,
    CONSTRAINT PK_TB_IMSC_PTPR_DT PRIMARY KEY (CORR_NO, PTCR_PRSR_DTL_SN)
);

-- ── 녹취파일내역 (접견) ─────────────────────────────────────────────────────
CREATE TABLE TB_RERD_TFIN_DS (
    TARE_FILE_NO            VARCHAR(26)  NOT NULL,   -- 녹취파일번호 (멱등 키)
    CORR_INSTT_CD           VARCHAR(7)   NOT NULL,   -- 교정기관코드
    ADNC_SE_CD              VARCHAR(2)   NOT NULL,   -- 접견구분코드
    RCPT_YMD                CHAR(8)      NOT NULL,   -- 접수일자
    RCPT_SN                 NUMERIC(5,0) NOT NULL,   -- 접수순번
    CORR_NO                 VARCHAR(18)  NOT NULL,   -- 교정번호 (조인 키)
    ADNC_YMD                CHAR(8)      NOT NULL,   -- 접견일자
    TBLT_RECRD_FILE_ID      VARCHAR(20),             -- 태블릿녹음파일ID ★ 음성 전용 경로 후보
    TBLT_VTR_FILE_ID        VARCHAR(20),             -- 태블릿녹화파일ID → TB_SMSM_CMFI_BS.CMMN_FILE_ID
    CCTV_FILE_ID            VARCHAR(20),
    TARE_FILE_NM            VARCHAR(500),            -- 녹취파일명 (포맷 판별 보조)
    TARE_BGNG_HMS           CHAR(6),                 -- 녹취시작시분초
    TARE_END_HMS            CHAR(6),                 -- 녹취종료시분초
    TARE_FILE_MG_VL         VARCHAR(10),             -- 녹취파일크기값
    TARE_FLPTH_NM           VARCHAR(100),            -- 녹취파일경로명
    DEL_YN                  CHAR(1)      NOT NULL,   -- 삭제여부
    VTR_FILE_NM             VARCHAR(500),
    VTR_FLPTH_NM            VARCHAR(100),
    RECRD_FILE_DEL_YN       CHAR(1)      NOT NULL,   -- 녹음파일삭제여부
    RECRD_BKUP_FILE_DEL_YN  CHAR(1)      NOT NULL,   -- 녹음백업파일삭제여부
    CRT_DT                  TIMESTAMP    NOT NULL,
    CRT_USR_ID              VARCHAR(40)  NOT NULL,
    MDFCN_DT                TIMESTAMP    NOT NULL,
    MDFCN_USR_ID            VARCHAR(40)  NOT NULL,
    CONSTRAINT PK_TB_RERD_TFIN_DS PRIMARY KEY (TARE_FILE_NO)
);

-- ── 공통파일기본 ────────────────────────────────────────────────────────────
CREATE TABLE TB_SMSM_CMFI_BS (
    CMMN_FILE_ID       VARCHAR(20)  NOT NULL,   -- 공통파일ID (= TBLT_VTR_FILE_ID)
    DOC_ID             VARCHAR(20)  NOT NULL,   -- 문서ID → XVARM.ELEMENTID
    FILE_NM            VARCHAR(500),
    CORR_WRK_SE_CD     VARCHAR(2),
    FILE_TY_CD         VARCHAR(1),
    FILE_DCF_CD        VARCHAR(7),
    WRK_IDFC_VL        VARCHAR(100),
    REG_DT             TIMESTAMP,
    RPRS_YN            CHAR(1),
    CMMN_FILE_ENC_YN   CHAR(1),                 -- ★ 암호화여부 — 'N' 인 파일을 복호화하면 원본이 깨진다
    DEL_YN             CHAR(1),
    CRT_DT             TIMESTAMP    NOT NULL,
    CRT_USR_ID         VARCHAR(40)  NOT NULL,
    MDFCN_DT           TIMESTAMP    NOT NULL,
    MDFCN_USR_ID       VARCHAR(40)  NOT NULL,
    CONSTRAINT PK_TB_SMSM_CMFI_BS PRIMARY KEY (CMMN_FILE_ID)
);

-- ── 사용자통화내역 (전화) ───────────────────────────────────────────────────
--   ⚠ RCVER_NM·ACQT_RLTNS_NM·INTRL_TELNO 는 개인정보다.
--     수집 서비스는 이 값들을 읽지도, 하류로 넘기지도 않는다(VoiceTarget 참조).
--     Mock 에도 실제 같은 값을 넣지 않는다.
CREATE TABLE TB_IMPH_UCDR_DS (
    VRFC_ESTL_ID            VARCHAR(50)   NOT NULL,  -- 검증고유ID (TUID) — 파일명·멱등 키
    PCALL_KND_CD            VARCHAR(5)    NOT NULL,
    TELP_USR_SCPT_SE_CD     VARCHAR(2)    NOT NULL,
    CORR_NO                 VARCHAR(18)   NOT NULL,  -- 교정번호 (조인 키)
    TELP_LST_SE_CD          VARCHAR(4)    NOT NULL,
    RCVER_NM                VARCHAR(100)  NOT NULL,  -- 수신자명 (PII)
    ACQT_RLTNS_NM           VARCHAR(40),             -- 지인관계명 (PII)
    INTRL_TELNO             VARCHAR(15)   NOT NULL,  -- 국제전화번호 (PII)
    TELP_PCALL_BGNG_DT      CHAR(14)      NOT NULL,  -- 통화시작일시
    TELP_PCALL_RSPNS_DT     CHAR(14)      NOT NULL DEFAULT '00000000000000',  -- 통화응답일시 (스펙 NOT NULL — 개발계 DB 와 같은 INSERT 가 돌게 둔다)
    TELP_PCALL_END_DT       CHAR(14)      NOT NULL,  -- 통화종료일시
    TELP_PCALL_TIME         NUMERIC(20,0) NOT NULL,  -- 통화시간(초)
    TELP_RSPNS_TIME         NUMERIC(20,0) NOT NULL DEFAULT 0,    -- 전화응답시간 (스펙 NOT NULL)
    TELP_PCALL_RSPNS_YN     VARCHAR(1)    NOT NULL DEFAULT 'Y',  -- 전화통화응답여부 (스펙 NOT NULL)
    TELP_PCALL_OCRN_AMT     NUMERIC(18,0) NOT NULL DEFAULT 0,    -- 전화통화발생금액 (스펙 NOT NULL)
    TELP_PCALL_RECRD_YN     CHAR(1)       NOT NULL,  -- ★ 녹음여부 — 'Y' 만 파일이 있다
    TELP_PTCR_PRSR_YN       CHAR(1)       NOT NULL,  -- ★ 전화특이수용자여부 — 지름길 필터 후보
    TELP_PTCR_PRSR_TCNT     NUMERIC(2,0),
    CORR_INSTT_CD           VARCHAR(7)    NOT NULL,
    TELP_USE_PLACE_NM       VARCHAR(100)  NOT NULL,
    TELP_RECRD_FLPTH_NM     VARCHAR(1000),           -- 녹음파일경로명
    TELP_RECRD_FILE_NM      VARCHAR(500),            -- 녹음파일명
    TELP_RECRD_FILE_ID      VARCHAR(100),            -- ★ 녹음파일ID = 복호화 KEY
    TELP_STT_FLPTH_NM       VARCHAR(2000),           -- ★ STT 파일경로명 — 이미 STT 가 있을 수 있다(Q1)
    DEL_DT                  TIMESTAMP,               -- 삭제일시
    CRT_DT                  TIMESTAMP     NOT NULL,
    CRT_USR_ID              VARCHAR(40)   NOT NULL,
    MDFCN_DT                TIMESTAMP     NOT NULL,
    MDFCN_USR_ID            VARCHAR(40)   NOT NULL,
    CONSTRAINT PK_TB_IMPH_UCDR_DS PRIMARY KEY (VRFC_ESTL_ID)
);

-- ── XVARM 콘텐츠 메타 ───────────────────────────────────────────────────────
--   실제로는 XVARM 스키마 소유다(XVARM.ASYSCONTENTELEMENT). H2/PostgreSQL Mock 에서는
--   스키마 없이 같은 이름의 테이블로 둔다. 실연동 시 SQL 의 테이블명만 스키마 수식으로 바꾼다.
CREATE TABLE ASYSCONTENTELEMENT (
    ELEMENTID  VARCHAR(20)   NOT NULL,   -- = TB_SMSM_CMFI_BS.DOC_ID
    FILEKEY    VARCHAR(1000),            -- 물리 파일 경로 추적 키
    CONSTRAINT PK_ASYSCONTENTELEMENT PRIMARY KEY (ELEMENTID)
);

CREATE INDEX IX_RERD_CRT_DT   ON TB_RERD_TFIN_DS (CRT_DT);
CREATE INDEX IX_RERD_CORR_NO  ON TB_RERD_TFIN_DS (CORR_NO);
CREATE INDEX IX_UCDR_CRT_DT   ON TB_IMPH_UCDR_DS (CRT_DT);
CREATE INDEX IX_UCDR_CORR_NO  ON TB_IMPH_UCDR_DS (CORR_NO);
