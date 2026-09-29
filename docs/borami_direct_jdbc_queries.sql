-- ════════════════════════════════════════════════════════════════════════════
--  voice-collector DIRECT_JDBC 조회 SQL — DBeaver 에서 바로 실행하는 판
-- ════════════════════════════════════════════════════════════════════════════
--  원본: src/main/resources/mappers/BoramiVoiceMapper.xml (selectMeetTargets · selectPhoneTargets)
--  MyBatis 의 ${p.tXxx}(테이블명) 과 #{p.xxx}(바인딩) 를 실제 값으로 풀어 썼다.
--
--  ▸ 스키마 = realdb 프로파일 기본값 (2026-09-12 borami-db 확인)
--      im.tb_imsc_ptpr_dt  특이수용자상세      re.tb_rerd_tfin_ds  녹취파일내역
--      sm.tb_smsm_cmfi_bs  공통파일기본         im.tb_imph_ucdr_ds  사용자통화내역
--      asyscontentelement  XVARM 콘텐츠 메타(스키마 미확인 → 접두 없음)
--    ⚠ sm.tb_smsm_cmfi_bs · asyscontentelement 는 2026-09-12 기준 borami-db 에 없다.
--      접견 쿼리는 그 두 테이블이 생기거나 I/F 뷰가 확정돼야 실DB 에서 돈다(계획서 Q15).
--  ▸ 플래그 = voice.source.flag (recorded-yes 'Y' · not-deleted 'N' · encrypted 'Y')
--  ▸ 특별관리구분코드 = voice.batch.specl-mng-se-cd ('0','1','2','3','5')
--  ▸ 시간창 = 일배치 예시 [어제 00:00, 오늘 00:00). 주기배치는 [지금-20분, 지금).
--  ▸ 상한 = voice.batch.max-files-per-run (dev 2000)
--
--  H2 Mock(로컬)에서 돌리려면 스키마 접두(im. / re. / sm.)만 지우면 된다.
-- ════════════════════════════════════════════════════════════════════════════

-- ── [접견(MEET) 조회] 4단 조인 — selectMeetTargets ──────────────────────────
--   ① 녹취파일내역 R  →  ② 특이수용자 P (CORR_NO, 해제 안 됨, 대상 코드)
--   →  ③ 공통파일기본 F (CMMN_FILE_ID = R.TBLT_VTR_FILE_ID, 삭제 안 됨)
--   →  ④ XVARM X (ELEMENTID = F.DOC_ID)  — FILEKEY 로 브로커에 추출을 지시한다
SELECT
      'MEET'                      AS KIND
    , R.CORR_NO                   AS CORR_NO
    , R.TARE_FILE_NO              AS IDEMPOTENCY_KEY       -- 멱등 키 · T4 REC_FILE_ID
    , F.DOC_ID                    AS DOC_ID
    , X.FILEKEY                   AS FILE_KEY
    , CASE WHEN F.CMMN_FILE_ENC_YN = 'Y' THEN 1 ELSE 0 END AS ENCRYPTED
    , CAST(NULL AS VARCHAR(1))    AS DECRYPT_KEY
    , R.TARE_FLPTH_NM             AS SRC_FILE_PATH
    , R.TARE_FILE_NM              AS SRC_FILE_NAME
    , CAST(NULL AS VARCHAR(1))    AS SOURCE_STT_PATH
    , R.CRT_DT                    AS OCCURRED_AT
FROM re.tb_rerd_tfin_ds R
INNER JOIN im.tb_imsc_ptpr_dt P
        ON P.CORR_NO = R.CORR_NO
       AND P.PTCR_PRSR_RMV_YMD IS NULL                         -- 해제된 특이수용자 제외
       AND P.SPECL_MNG_SE_CD IN ('0', '1', '2', '3', '5')      -- 조직·마약·관심·엄격·일일중점
INNER JOIN sm.tb_smsm_cmfi_bs F
        ON F.CMMN_FILE_ID = R.TBLT_VTR_FILE_ID
       AND F.DEL_YN = 'N'                                      -- 공통파일 삭제 안 됨
LEFT JOIN asyscontentelement X
       ON X.ELEMENTID = F.DOC_ID
WHERE R.CRT_DT >= TIMESTAMP '2026-09-15 00:00:00'              -- #{p.from}  (일배치: 어제 00:00)
  AND R.CRT_DT <  TIMESTAMP '2026-09-16 00:00:00'              -- #{p.to}    (일배치: 오늘 00:00)
  AND R.DEL_YN = 'N'                                           -- 녹취 삭제 안 됨
  AND R.RECRD_FILE_DEL_YN = 'N'                                -- 녹음파일 삭제 안 됨
  AND R.TBLT_VTR_FILE_ID IS NOT NULL
ORDER BY R.CRT_DT, R.TARE_FILE_NO
FETCH FIRST 2000 ROWS ONLY;                                    -- #{p.limit}


-- ── [전화(PHONE) 조회] 특이수용자 조인(정공법) — selectPhoneTargets ───────────
--   사용자통화내역 U  →  특이수용자 P. XVARM·브로커를 타지 않는다(별도 ESB 프로바이더).
SELECT
      'PHONE'                     AS KIND
    , U.CORR_NO                   AS CORR_NO
    , U.VRFC_ESTL_ID              AS IDEMPOTENCY_KEY       -- 멱등 키 · T4 REC_FILE_ID
    , CAST(NULL AS VARCHAR(1))    AS DOC_ID
    , CAST(NULL AS VARCHAR(1))    AS FILE_KEY
    , 1                           AS ENCRYPTED
    , U.TELP_RECRD_FILE_ID        AS DECRYPT_KEY           -- 복호화 KEY 를 겸한다
    , U.TELP_RECRD_FLPTH_NM       AS SRC_FILE_PATH
    , U.TELP_RECRD_FILE_NM        AS SRC_FILE_NAME
    , U.TELP_STT_FLPTH_NM         AS SOURCE_STT_PATH       -- 값이 있으면 보라미 기존 STT 재사용(Q1)
    , U.CRT_DT                    AS OCCURRED_AT
FROM im.tb_imph_ucdr_ds U
INNER JOIN im.tb_imsc_ptpr_dt P
        ON P.CORR_NO = U.CORR_NO
       AND P.PTCR_PRSR_RMV_YMD IS NULL
       AND P.SPECL_MNG_SE_CD IN ('0', '1', '2', '3', '5')
WHERE U.CRT_DT >= TIMESTAMP '2026-09-15 00:00:00'              -- #{p.from}
  AND U.CRT_DT <  TIMESTAMP '2026-09-16 00:00:00'              -- #{p.to}
  AND U.TELP_PCALL_RECRD_YN = 'Y'                              -- 녹음된 통화만 (실DB 관측: 전부 'N')
  AND U.DEL_DT IS NULL
  AND U.TELP_RECRD_FILE_ID IS NOT NULL                         -- (실DB 관측: 전부 NULL → 0건이 정상)
ORDER BY U.CRT_DT, U.VRFC_ESTL_ID
FETCH FIRST 2000 ROWS ONLY;


-- ── [전화(PHONE) 지름길] TELP_PTCR_PRSR_YN 플래그만 — selectPhoneTargetsByFlag (대사용) ──
--   ⚠ 실DB 관측값이 '0' 이라 realdb 프로파일은 flag.ptcr-yes=0 으로 둔다. 정공법과 건수가 같아야 플래그를 믿을 수 있다.
SELECT
      'PHONE'                     AS KIND
    , U.CORR_NO                   AS CORR_NO
    , U.VRFC_ESTL_ID              AS IDEMPOTENCY_KEY
    , CAST(NULL AS VARCHAR(1))    AS DOC_ID
    , CAST(NULL AS VARCHAR(1))    AS FILE_KEY
    , 1                           AS ENCRYPTED
    , U.TELP_RECRD_FILE_ID        AS DECRYPT_KEY
    , U.TELP_RECRD_FLPTH_NM       AS SRC_FILE_PATH
    , U.TELP_RECRD_FILE_NM        AS SRC_FILE_NAME
    , U.TELP_STT_FLPTH_NM         AS SOURCE_STT_PATH
    , U.CRT_DT                    AS OCCURRED_AT
FROM im.tb_imph_ucdr_ds U
WHERE U.CRT_DT >= TIMESTAMP '2026-09-15 00:00:00'
  AND U.CRT_DT <  TIMESTAMP '2026-09-16 00:00:00'
  AND U.TELP_PTCR_PRSR_YN = '0'                                -- #{p.flagPtcrYes} (realdb: '0')
  AND U.TELP_PCALL_RECRD_YN = 'Y'
  AND U.DEL_DT IS NULL
  AND U.TELP_RECRD_FILE_ID IS NOT NULL
ORDER BY U.CRT_DT, U.VRFC_ESTL_ID
FETCH FIRST 2000 ROWS ONLY;


-- ── [진단] 특이수용자 테이블 접근 확인 — probeImsc ─────────────────────────
SELECT COUNT(*) FROM im.tb_imsc_ptpr_dt;
