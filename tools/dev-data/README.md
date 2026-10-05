# 개발계 보라미 데이터 보정 — 수용자 사진 공통파일 누락 (2026-10-05)

## 무엇이 문제였나

개발계 보라미의 수용자 사진(`ir.tb_irim_bsif_ds`, 실제 · 마스킹 데이터)이 가리키는 **공통파일ID 8개**가
공통파일 표(`sm.tb_smsm_cmfi_bs`)에 없었다. 사진 1,661행 · 수용자 979명이 이 8개 키를 함께 쓰고 있어,
실제 이미지 배치가 매번 **979명 중 972명**을 `공통파일기본(TB_SMSM_CMFI_BS)에 CMMN_FILE_ID=… 가 없다` 로 수집(COLLECT) 실패했다.

`sm.tb_smsm_cmfi_bs` · `xvarm.asyscontentelement` 는 개발계에 실 테이블이 없어 **수집기가 만든 MOCK_DEV 표**다
(`src/main/resources/sql/xvarm_mock_tables_postgres.sql`). 정형 수집기는 이 두 표를 읽지 않는다.

## 파일

| 파일 | 하는 일 |
|---|---|
| `borami_image_cmfi_missing.sql` | 조회만 — 요약 · 수집 대상(수용자별 최신 사진) 누락 수 · 누락 키 목록(키별 사진 행 · 수용자 수) |
| `borami_image_cmfi_fix.sql` | 보정 — 누락 키마다 공통파일 1행(DOC_ID `UNSFIX`+md5 14자리 · 암호화 Y · 생성자 `unsfix`) + XVARM FILEKEY 1행. 끝에 확인 쿼리. **멱등** |
| `borami_image_cmfi_rollback.sql` | 되돌리기 — `crt_usr_id='unsfix'` 행과 그 FILEKEY 행만 지운다 |

FILEKEY 는 개발계 PV 에 **이미 있는** 샘플 이미지(수집기 키로 암호화된 대시보드 더미 사진)를 가리킨다 —
`/k8s/unstructured_collector/xvram/original_voice_files/image/DMYIMG26100400{01..05}_2.jpg.enc` · `DMYIMG2610050002_2.jpg.enc`(키 해시로 고정 배정).
내장 Mock 브로커가 이 파일을 **복사**해 받고(원본은 그대로), 수집기가 복호화 · 이미지 확인 · 저장 · Admin 매핑을 한다.

> ⚠ 샘플은 대시보드 더미(DMY) 원본이다. **[대시보드 테스트 데이터 초기화]** 나 대시보드용(REAL) 성능 시험이 DMY 원본을 지우면
> FILEKEY 가 가리키는 파일이 없어진다. 내장 Mock 브로커는 그때 암호화된 더미를 대신 만들어 처리는 계속되지만, '실제 파일을 바라본다' 는 보정 취지에서 벗어나고 브로커를 REST 로 바꾸면 실패한다.
> 오래 두려면 전용 폴더에 샘플을 두고 `fix.sql` 의 `samples` 경로만 바꿔 `rollback → fix` 를 다시 돌린다.
> (2026-10-05 에는 파드에 파일을 쓰는 원격 명령이 권한 판정에서 막혀 기존 샘플을 썼다.)

## 적용 (개발계 borami-db — 비밀번호는 파드 환경 변수, 출력 안 함)

```bash
kubectl -n data-pipeline exec -i borami-db-gijoxearrw-0 -- sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" psql -h 127.0.0.1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -P pager=off -f -' < tools/dev-data/borami_image_cmfi_missing.sql
kubectl -n data-pipeline exec -i borami-db-gijoxearrw-0 -- sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" psql -h 127.0.0.1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -P pager=off -v ON_ERROR_STOP=1 -1 -f -' < tools/dev-data/borami_image_cmfi_fix.sql
```

## 결과 (2026-10-05 16:1x 적용)

| 확인 | 적용 전 | 적용 후 |
|---|---|---|
| 누락 공통파일ID | 8개(사진 1,647행) | 0 — 공통파일 8행 · FILEKEY 8행 추가 |
| 수집 대상 979명 중 공통파일 누락 | 972 | **0**(972명이 보정 키 사용) |
| 실제 이미지 배치 | `20261005UNS002` 대상 979 · 성공 6 · **실패 973** | `20261005UNS008` 대상 979 · **성공 972 · 실패 0** · 건너뜀 7(이미 매핑) · Admin 매핑 신규 972 · T1 SUCCESS · T2 3단계 SUCCESS · T4 972 SUCCESS(SEND) |
