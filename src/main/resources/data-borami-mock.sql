-- ════════════════════════════════════════════════════════════════════════════
--  보라미 Mock (H2) — 조회 SQL 필터 검증용 "걸러져야 할 행" 만 둔다
-- ════════════════════════════════════════════════════════════════════════════
--  배치 대상이 되는 유효 데이터(접견 5 · 전화 5 = 10건 + 더미 파일)는 여기 없다 —
--  SimulationDataService 가 기동 시(voice.sim.seed-on-startup) 와 시뮬레이터 [시뮬레이션 데이터 생성] 에서
--  지금 시각 기준으로 만든다(개발계 PostgreSQL 에도 같은 코드로 넣는다).
--
--  아래 행은 어떤 시간창에서도 대상이 되지 않아야 한다(전부 5분 전 시각인데도 필터에 걸린다):
--     접견 MEET-X01 : 녹취 DEL_YN='Y'                 → 제외
--          MEET-X02 : 특이수용자 해제(RMV_YMD)         → 제외
--          MEET-X03 : SPECL_MNG_SE_CD='4' (대상 코드 아님) → 제외
--     전화 TUID-PHONE-X01 : 녹음여부 'N'               → 제외
--          TUID-PHONE-X02 : DEL_DT 존재                → 제외
--
--  ⚠ 개인정보 컬럼(RCVER_NM·ACQT_RLTNS_NM·INTRL_TELNO)에는 실제 같은 값을 넣지 않는다.
--  ⚠ H2 문법(DATEADD). 실DB 에는 절대 돌리지 않는다.
-- ════════════════════════════════════════════════════════════════════════════

-- ── 특이수용자 (필터 검증용) ──────────────────────────────────────────────
INSERT INTO TB_IMSC_PTPR_DT
  (CORR_NO, PTCR_PRSR_DTL_SN, SPECL_MNG_SE_CD, PTCR_PRSR_SE_CD, PTCR_PRSR_APNT_YMD,
   PTCR_PRSR_RMV_YMD, CRT_DT, CRT_USR_ID, MDFCN_DT, MDFCN_USR_ID)
VALUES
  ('MOCKX000000000001', 1, '3', 'A02', '20260201', NULL,       DATEADD(DAY, -30, CURRENT_TIMESTAMP), 'mockadm', DATEADD(DAY, -30, CURRENT_TIMESTAMP), 'mockadm'),
  -- 해제된 특이수용자 — 조회에서 빠져야 한다
  ('MOCKX000000000002', 1, '0', 'A01', '20260101', '20260801', DATEADD(DAY, -30, CURRENT_TIMESTAMP), 'mockadm', DATEADD(DAY, -30, CURRENT_TIMESTAMP), 'mockadm'),
  -- 대상 코드가 아닌 특별관리구분(4) — 조회에서 빠져야 한다
  ('MOCKX000000000003', 1, '4', 'A03', '20260301', NULL,       DATEADD(DAY, -30, CURRENT_TIMESTAMP), 'mockadm', DATEADD(DAY, -30, CURRENT_TIMESTAMP), 'mockadm');

-- ── 공통파일기본 · XVARM (접견 제외 행이 참조) ──────────────────────────────
INSERT INTO TB_SMSM_CMFI_BS
  (CMMN_FILE_ID, DOC_ID, FILE_NM, CORR_WRK_SE_CD, FILE_TY_CD, REG_DT, RPRS_YN,
   CMMN_FILE_ENC_YN, DEL_YN, CRT_DT, CRT_USR_ID, MDFCN_DT, MDFCN_USR_ID)
VALUES
  ('CMFIX00000000001', 'DOCX00000000001', 'mock_meet_x01.m4a', '01', 'A', CURRENT_TIMESTAMP, 'Y', 'Y', 'N', CURRENT_TIMESTAMP, 'mockadm', CURRENT_TIMESTAMP, 'mockadm'),
  ('CMFIX00000000002', 'DOCX00000000002', 'mock_meet_x02.m4a', '01', 'A', CURRENT_TIMESTAMP, 'Y', 'Y', 'N', CURRENT_TIMESTAMP, 'mockadm', CURRENT_TIMESTAMP, 'mockadm'),
  ('CMFIX00000000003', 'DOCX00000000003', 'mock_meet_x03.m4a', '01', 'A', CURRENT_TIMESTAMP, 'Y', 'Y', 'N', CURRENT_TIMESTAMP, 'mockadm', CURRENT_TIMESTAMP, 'mockadm');

INSERT INTO ASYSCONTENTELEMENT (ELEMENTID, FILEKEY) VALUES
  ('DOCX00000000001', 'XVARM/MOCK/FILEKEY-X001'),
  ('DOCX00000000002', 'XVARM/MOCK/FILEKEY-X002'),
  ('DOCX00000000003', 'XVARM/MOCK/FILEKEY-X003');

-- ── 녹취파일내역 (접견) — 전부 제외 대상 ───────────────────────────────────
INSERT INTO TB_RERD_TFIN_DS
  (TARE_FILE_NO, CORR_INSTT_CD, ADNC_SE_CD, RCPT_YMD, RCPT_SN, CORR_NO, ADNC_YMD,
   TBLT_RECRD_FILE_ID, TBLT_VTR_FILE_ID, TARE_FILE_NM, TARE_BGNG_HMS, TARE_END_HMS,
   TARE_FILE_MG_VL, TARE_FLPTH_NM, DEL_YN, RECRD_FILE_DEL_YN, RECRD_BKUP_FILE_DEL_YN,
   CRT_DT, CRT_USR_ID, MDFCN_DT, MDFCN_USR_ID)
VALUES
  -- X01 녹취 삭제됨 → 제외
  ('MEET-X01-0000000000000000', 'CI00001', '01', '20260912', 1, 'MOCKX000000000001', '20260912',
   'TRCDX00000000001', 'CMFIX00000000001', 'mock_meet_x01.m4a', '110000', '111000',
   '3145728', '/data001/doc01/recv', 'Y', 'N', 'N',
   DATEADD(MINUTE, -5, CURRENT_TIMESTAMP), 'mockadm', DATEADD(MINUTE, -5, CURRENT_TIMESTAMP), 'mockadm'),
  -- X02 해제된 특이수용자 → 제외
  ('MEET-X02-0000000000000000', 'CI00001', '01', '20260912', 2, 'MOCKX000000000002', '20260912',
   'TRCDX00000000002', 'CMFIX00000000002', 'mock_meet_x02.m4a', '120000', '121000',
   '3145728', '/data001/doc01/recv', 'N', 'N', 'N',
   DATEADD(MINUTE, -5, CURRENT_TIMESTAMP), 'mockadm', DATEADD(MINUTE, -5, CURRENT_TIMESTAMP), 'mockadm'),
  -- X03 대상 코드 아님(4) → 제외
  ('MEET-X03-0000000000000000', 'CI00001', '01', '20260912', 3, 'MOCKX000000000003', '20260912',
   'TRCDX00000000003', 'CMFIX00000000003', 'mock_meet_x03.m4a', '130000', '131000',
   '3145728', '/data001/doc01/recv', 'N', 'N', 'N',
   DATEADD(MINUTE, -5, CURRENT_TIMESTAMP), 'mockadm', DATEADD(MINUTE, -5, CURRENT_TIMESTAMP), 'mockadm');

-- ── 사용자통화내역 (전화) — 전부 제외 대상 ─────────────────────────────────
INSERT INTO TB_IMPH_UCDR_DS
  (VRFC_ESTL_ID, PCALL_KND_CD, TELP_USR_SCPT_SE_CD, CORR_NO, TELP_LST_SE_CD,
   RCVER_NM, ACQT_RLTNS_NM, INTRL_TELNO, TELP_PCALL_BGNG_DT, TELP_PCALL_END_DT,
   TELP_PCALL_TIME, TELP_PCALL_RECRD_YN, TELP_PTCR_PRSR_YN, TELP_PTCR_PRSR_TCNT,
   CORR_INSTT_CD, TELP_USE_PLACE_NM, TELP_RECRD_FLPTH_NM, TELP_RECRD_FILE_NM,
   TELP_RECRD_FILE_ID, TELP_STT_FLPTH_NM, DEL_DT,
   CRT_DT, CRT_USR_ID, MDFCN_DT, MDFCN_USR_ID)
VALUES
  -- X01 녹음 안 됨 → 제외
  ('TUID-PHONE-X01', 'P001', '01', 'MOCKX000000000001', 'L001',
   '(테스트)수신자X1', '(테스트)관계', '000-0000-0091', '20260912102000', '20260912102100',
   60, 'N', 'Y', 1, 'CI00001', '(테스트)전화실', NULL, NULL,
   NULL, NULL, NULL,
   DATEADD(MINUTE, -5, CURRENT_TIMESTAMP), 'mockadm', DATEADD(MINUTE, -5, CURRENT_TIMESTAMP), 'mockadm'),
  -- X02 삭제됨 → 제외
  ('TUID-PHONE-X02', 'P001', '01', 'MOCKX000000000001', 'L001',
   '(테스트)수신자X2', '(테스트)관계', '000-0000-0092', '20260912103000', '20260912103200',
   120, 'Y', 'Y', 1, 'CI00001', '(테스트)전화실', '/data001/phone/recv', 'mock_phone_x02.wav',
   'PHONEKEY-X0000000000000002', NULL, CURRENT_TIMESTAMP,
   DATEADD(MINUTE, -5, CURRENT_TIMESTAMP), 'mockadm', DATEADD(MINUTE, -5, CURRENT_TIMESTAMP), 'mockadm');
