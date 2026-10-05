-- ════════════════════════════════════════════════════════════════════════════
--  개발계 보라미 — 수용자 사진(ir.tb_irim_bsif_ds)이 가리키는 공통파일(sm.tb_smsm_cmfi_bs)이 없는 키 추출 (조회만)
-- ════════════════════════════════════════════════════════════════════════════
--  사진 수집 경로: 사진(IMAGE_SE_CD='1', 수용자별 최신 IMAGE_SN) → 공통파일 CMMN_FILE_ID → DOC_ID
--                 → xvarm.asyscontentelement(ELEMENTID = DOC_ID) → FILEKEY → 브로커 수신 → 복호화 → 저장 → Admin 매핑
--  개발계에는 공통파일 · XVARM 실 테이블이 없어 수집기가 MOCK_DEV 로 만든 sm.tb_smsm_cmfi_bs · xvarm.asyscontentelement 를 쓴다
--  (src/main/resources/sql/xvarm_mock_tables_postgres.sql). 그 표에 실제(마스킹) 사진 행의 공통파일이 없어 COLLECT 실패가 난다.
-- ════════════════════════════════════════════════════════════════════════════

-- ① 요약 — 사진 행 · 수용자 · 누락
SELECT COUNT(*)                                                    AS photo_rows,
       COUNT(DISTINCT b.corr_no)                                   AS inmates,
       COUNT(*) FILTER (WHERE c.cmmn_file_id IS NULL)              AS rows_missing_cmfi,
       COUNT(DISTINCT b.image_cmmn_file_id) FILTER (WHERE c.cmmn_file_id IS NULL) AS distinct_missing_ids,
       COUNT(*) FILTER (WHERE b.image_cmmn_file_id IS NULL)        AS rows_null_file_id
  FROM ir.tb_irim_bsif_ds b
  LEFT JOIN sm.tb_smsm_cmfi_bs c ON c.cmmn_file_id = b.image_cmmn_file_id
 WHERE b.image_se_cd = '1'
   AND b.corr_no NOT LIKE 'SIMIMG%';

-- ② 수집 대상(수용자별 최신 사진)만 — 실제 배치가 집는 범위
SELECT COUNT(*)                                       AS latest_rows,
       COUNT(*) FILTER (WHERE c.cmmn_file_id IS NULL) AS latest_missing_cmfi,
       COUNT(*) FILTER (WHERE c.cmmn_file_id IS NOT NULL AND x.elementid IS NULL) AS latest_missing_filekey
  FROM (SELECT b.*, ROW_NUMBER() OVER (PARTITION BY b.corr_no ORDER BY b.image_sn DESC) rn
          FROM ir.tb_irim_bsif_ds b
         WHERE b.image_se_cd = '1' AND b.corr_no NOT LIKE 'SIMIMG%') t
  LEFT JOIN sm.tb_smsm_cmfi_bs c ON c.cmmn_file_id = t.image_cmmn_file_id
  LEFT JOIN xvarm.asyscontentelement x ON x.elementid = c.doc_id
 WHERE t.rn = 1;

-- ③ 누락 키 목록 — 공통파일ID · 그 키를 가리키는 사진 행 수 · 수용자 수(많은 순)
SELECT b.image_cmmn_file_id AS cmmn_file_id,
       COUNT(*)                  AS photo_rows,
       COUNT(DISTINCT b.corr_no) AS inmates
  FROM ir.tb_irim_bsif_ds b
  LEFT JOIN sm.tb_smsm_cmfi_bs c ON c.cmmn_file_id = b.image_cmmn_file_id
 WHERE b.image_se_cd = '1'
   AND b.corr_no NOT LIKE 'SIMIMG%'
   AND b.image_cmmn_file_id IS NOT NULL
   AND c.cmmn_file_id IS NULL
 GROUP BY b.image_cmmn_file_id
 ORDER BY photo_rows DESC, cmmn_file_id;
