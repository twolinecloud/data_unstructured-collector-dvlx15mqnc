-- ═══════════════════════════════════════════════════════════════════════════
--  V2 — 공통코드 C05(처리 단계 구분 · STEP_TYPE_CD) 3단계 정비 (Admin DB · correction_ai · 스키마 kcais)
--  2026-10-02
--
--  비정형 파이프라인은 [수집 COLLECT] → [정제/분석 ANALYZE] → [적재/전송 SEND] 3단계다(2026-10-01 복원).
--    · DEIDENT(비식별화) · CLEANSE(정제)  → USE_YN = 'N'
--    · COLLECT · ANALYZE · SEND           → SORT_SEQ = 1 · 2 · 3 (사용 'Y')
--
--  ⚠ 앱이 실행하지 않는다 — 관리자가 적용한다(V1 과 같은 운영 방식). 몇 번 돌려도 결과가 같다(멱등).
--
--  ⚠ 행은 지우지 않고 사용 여부만 끈다.
--    옛 배치의 T2(DEIDENT 행)와 정형 체인(COLLECT → CLEANSE → DEIDENT → SEND)이 아직 이 코드값을 기록한다.
--    · 로그 컬렉터의 단계명 조인(LogMapper · c5.code_grp_id='C05')은 USE_YN 을 보지 않는다 → 이름이 그대로 나온다.
--    · admin-api 공통코드 API(commonCodeMapper · USE_YN='Y')의 C05 목록에서만 빠진다.
--      배치 이력 화면의 단계명은 admin-fe 상수(pages/pipeline/BatchHistory/constants.ts)라 영향이 없다.
--  STORE(저장)는 지시 범위 밖이라 그대로 둔다(정렬 5 · 사용).
--
--  되돌리기(원래 값 — admin-api V1):
--    UPDATE kcais.tb_comm_code SET use_yn = 'Y' WHERE code_grp_id = 'C05' AND code_val IN ('DEIDENT', 'CLEANSE');
--    COLLECT 1 · CLEANSE 2 · ANALYZE 3 · DEIDENT 4 · STORE 5 · SEND 6
-- ═══════════════════════════════════════════════════════════════════════════

UPDATE kcais.tb_comm_code
   SET use_yn = 'N', mod_user_id = 'unstructured-collector', mod_dtm = CURRENT_TIMESTAMP
 WHERE code_grp_id = 'C05' AND code_val IN ('DEIDENT', 'CLEANSE') AND use_yn <> 'N';

UPDATE kcais.tb_comm_code
   SET sort_seq = 1, use_yn = 'Y', mod_user_id = 'unstructured-collector', mod_dtm = CURRENT_TIMESTAMP
 WHERE code_grp_id = 'C05' AND code_val = 'COLLECT' AND (sort_seq <> 1 OR use_yn <> 'Y');

UPDATE kcais.tb_comm_code
   SET sort_seq = 2, use_yn = 'Y', mod_user_id = 'unstructured-collector', mod_dtm = CURRENT_TIMESTAMP
 WHERE code_grp_id = 'C05' AND code_val = 'ANALYZE' AND (sort_seq <> 2 OR use_yn <> 'Y');

UPDATE kcais.tb_comm_code
   SET sort_seq = 3, use_yn = 'Y', mod_user_id = 'unstructured-collector', mod_dtm = CURRENT_TIMESTAMP
 WHERE code_grp_id = 'C05' AND code_val = 'SEND' AND (sort_seq <> 3 OR use_yn <> 'Y');

-- 확인:
--   SELECT code_val, code_nm, sort_seq, use_yn FROM kcais.tb_comm_code
--    WHERE code_grp_id = 'C05' ORDER BY use_yn DESC, sort_seq;
--   → COLLECT 1 Y · ANALYZE 2 Y · SEND 3 Y · STORE 5 Y · CLEANSE 2 N · DEIDENT 4 N
