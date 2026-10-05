-- ════════════════════════════════════════════════════════════════════════════
--  개발계 보라미 — 사진 공통파일 보정 되돌리기 (borami_image_cmfi_fix.sql 이 넣은 행만)        2026-10-05
-- ════════════════════════════════════════════════════════════════════════════
--  보정 표식: sm.tb_smsm_cmfi_bs.crt_usr_id = 'unsfix' · doc_id 'UNSFIX%'
--  ⚠ 이미 돈 배치가 Admin DB 에 남긴 사진 매핑 · PV 저장 사진 · 로그 컬렉터 이력은 지우지 않는다(필요하면 따로).
--  샘플 파일(PV 의 DMY 더미 원본)은 보정이 만든 것이 아니므로 건드리지 않는다.
-- ════════════════════════════════════════════════════════════════════════════

DELETE FROM xvarm.asyscontentelement x
 USING sm.tb_smsm_cmfi_bs c
 WHERE x.elementid = c.doc_id
   AND c.crt_usr_id = 'unsfix';

DELETE FROM sm.tb_smsm_cmfi_bs
 WHERE crt_usr_id = 'unsfix';
