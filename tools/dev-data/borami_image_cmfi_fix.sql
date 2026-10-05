-- ════════════════════════════════════════════════════════════════════════════
--  개발계 보라미 — 수용자 사진의 공통파일 누락 보정 (INSERT)            2026-10-05
-- ════════════════════════════════════════════════════════════════════════════
--  왜: 개발계 ir.tb_irim_bsif_ds 의 실제(마스킹) 사진 행이 가리키는 공통파일ID 가 sm.tb_smsm_cmfi_bs 에 없어
--      실제 이미지 배치가 '공통파일기본(TB_SMSM_CMFI_BS)에 CMMN_FILE_ID=… 가 없다' 로 COLLECT 실패한다(10-05 UNS002: 979 중 973).
--      두 표(sm.tb_smsm_cmfi_bs · xvarm.asyscontentelement)는 개발계에 실 테이블이 없어 수집기가 MOCK_DEV 로 만든 표다.
--  무엇: 누락 공통파일ID 마다
--      ① sm.tb_smsm_cmfi_bs 1행 — DOC_ID 'UNSFIX' + md5 14자리 · 암호화 'Y'(실 보라미처럼) · 생성자 'unsfix'(보정 표식)
--      ② xvarm.asyscontentelement 1행 — FILEKEY = 개발계 PV 에 **이미 있는** 샘플 이미지(수집기 키로 암호화된 더미 사진) 6개 중
--         하나(공통파일ID 해시로 고정 배정). 내장 Mock 브로커가 FILEKEY 원본을 복사해 받고, 수집기가 복호화 · 이미지 확인 · 저장한다.
--         {ROOT}/xvram/original_voice_files/image/DMYIMG26100400{01..05}_2.jpg.enc · DMYIMG2610050002_2.jpg.enc
--  ⚠ 샘플은 대시보드 더미(DMY) 원본이다 — [대시보드 테스트 데이터 초기화] · 대시보드용 성능 시험이 DMY 원본을 지우면 FILEKEY 가 깨진다.
--     그때는 전용 샘플 폴더에 파일을 두고 아래 samples 값만 바꿔 다시 돌린다(되돌리기 → 적용) — tools/dev-data/README.md
--  멱등: 이미 있는 키는 건너뛴다(몇 번 돌려도 같다). 되돌리기: borami_image_cmfi_rollback.sql
--  적용: kubectl -n data-pipeline exec -i borami-db-gijoxearrw-0 -- sh -c \
--          'PGPASSWORD="$POSTGRES_PASSWORD" psql -h 127.0.0.1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -1 -f -' < 이 파일
-- ════════════════════════════════════════════════════════════════════════════

INSERT INTO sm.tb_smsm_cmfi_bs
       (cmmn_file_id, doc_id, file_nm, corr_wrk_se_cd, file_ty_cd, reg_dt, rprs_yn, cmmn_file_enc_yn, del_yn,
        crt_dt, crt_usr_id, mdfcn_dt, mdfcn_usr_id)
SELECT m.id,
       'UNSFIX' || substr(md5(m.id), 1, 14),
       'devfix_' || m.id || '.jpg',
       '01', 'A', CURRENT_TIMESTAMP, 'Y', 'Y', 'N',
       CURRENT_TIMESTAMP, 'unsfix', CURRENT_TIMESTAMP, 'unsfix'
  FROM (SELECT DISTINCT b.image_cmmn_file_id AS id
          FROM ir.tb_irim_bsif_ds b
          LEFT JOIN sm.tb_smsm_cmfi_bs c ON c.cmmn_file_id = b.image_cmmn_file_id
         WHERE b.image_se_cd = '1'
           AND b.corr_no NOT LIKE 'SIMIMG%'
           AND b.image_cmmn_file_id IS NOT NULL
           AND c.cmmn_file_id IS NULL) m;

WITH samples(i, path) AS (VALUES
        (0, '/k8s/unstructured_collector/xvram/original_voice_files/image/DMYIMG2610040001_2.jpg.enc'),
        (1, '/k8s/unstructured_collector/xvram/original_voice_files/image/DMYIMG2610040002_2.jpg.enc'),
        (2, '/k8s/unstructured_collector/xvram/original_voice_files/image/DMYIMG2610040003_2.jpg.enc'),
        (3, '/k8s/unstructured_collector/xvram/original_voice_files/image/DMYIMG2610040004_2.jpg.enc'),
        (4, '/k8s/unstructured_collector/xvram/original_voice_files/image/DMYIMG2610040005_2.jpg.enc'),
        (5, '/k8s/unstructured_collector/xvram/original_voice_files/image/DMYIMG2610050002_2.jpg.enc'))
INSERT INTO xvarm.asyscontentelement (elementid, filekey)
SELECT c.doc_id, s.path
  FROM sm.tb_smsm_cmfi_bs c
  JOIN samples s ON s.i = mod(abs(hashtext(c.cmmn_file_id)), 6)
  LEFT JOIN xvarm.asyscontentelement x ON x.elementid = c.doc_id
 WHERE c.crt_usr_id = 'unsfix'
   AND x.elementid IS NULL;

-- 확인 — 수집 대상(수용자별 최신 사진) 중 공통파일 · FILEKEY 누락 0 이어야 한다
SELECT COUNT(*)                                       AS latest_rows,
       COUNT(*) FILTER (WHERE c.cmmn_file_id IS NULL) AS latest_missing_cmfi,
       COUNT(*) FILTER (WHERE c.cmmn_file_id IS NOT NULL AND x.elementid IS NULL) AS latest_missing_filekey,
       COUNT(*) FILTER (WHERE c.crt_usr_id = 'unsfix') AS latest_via_fix
  FROM (SELECT b.*, ROW_NUMBER() OVER (PARTITION BY b.corr_no ORDER BY b.image_sn DESC) rn
          FROM ir.tb_irim_bsif_ds b
         WHERE b.image_se_cd = '1' AND b.corr_no NOT LIKE 'SIMIMG%') t
  LEFT JOIN sm.tb_smsm_cmfi_bs c ON c.cmmn_file_id = t.image_cmmn_file_id
  LEFT JOIN xvarm.asyscontentelement x ON x.elementid = c.doc_id
 WHERE t.rn = 1;

SELECT c.cmmn_file_id, c.doc_id, c.cmmn_file_enc_yn, x.filekey
  FROM sm.tb_smsm_cmfi_bs c
  JOIN xvarm.asyscontentelement x ON x.elementid = c.doc_id
 WHERE c.crt_usr_id = 'unsfix'
 ORDER BY c.cmmn_file_id;
