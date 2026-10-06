# unstructured-collector — 비정형 데이터 수집 서비스

보라미의 **비정형 데이터**를 수집한다.

- **음성**(접견·통화) — 골라 가져와 복호화하고 **STT 텍스트**로 만든 뒤 **에이전트 커넥터 bypass API** 를 거쳐 온프레미스 제논으로 보낸다(비식별 없음)
  — `[수집 COLLECT] → [정제/분석 ANALYZE] → [적재/전송 SEND(에이전트 커넥터)]` 3단계(2026-10-01 복원 · 클라우드 전송·비식별화 제외)
- **수용자 사진** — 최신 사진의 XVARM 파일을 받아 **접견과 같은 방식으로 복호화**해 저장하고 Admin DB 에 경로를 매핑한다

정형 데이터는 `data-collector`, 파일 기반 비정형 데이터는 이 서비스가 맡는다.

> **이관 (2026-09-29)** — `data_voice-collector-x2daarjxe4`(voice-collector)에서 이 저장소로 옮기고 이름을 바꿨다.
> 투라코 서비스명 `unstructured-collector-dvlx15mqnc` · `spring.application.name=unstructured-collector`.
> 그대로 둔 것: 수용자 PID salt(`kcais-voice-collector` — 바꾸면 기존 T4·T5 이력과 PID 가 어긋난다) ·
> 설정 접두 `voice.*` · 데이터 루트 `/k8s/unstructured_collector`(브로커 출력 경로와 맞물림) · 시뮬레이터 파일명
> `unstructured_collector_simulator.html`(admin-fe 링크·nginx 경로).
>
> 설계 근거: `data_agent-connector-dp8qbi7xqh/study/회의록/9_11/음성수집_서비스_개발계획_초안_v2.md`
>
> **3단계 복원 (2026-10-01)** — 비식별(DEIDENT) 단계를 걷어냈다. SEND = 에이전트 커넥터 **bypass** API(`/api/v1/learn/transfer` — 비식별 없이 제논으로 중계, data-collector 와 같은 주소 · 양식).
> 10-05 에 '커넥터 없이 제논 직접' 으로 적었던 것을 10-06 PL 확인으로 되돌렸다 — 제논으로 가는 길은 늘 커넥터를 거친다. STT 결과는 PV
> (`/k8s/unstructured_collector/xenon`)에 남기지 않는다. 영향도는 `docs/3단계_복원_영향도_리포트.md`, 에이전트 커넥터 목 서버는 `tools/agent-connector-mock/`.

---

## 1. 이 서비스가 하는 일 / 하지 않는 일

```
[수집 COLLECT]                 [정제/분석 ANALYZE]          [적재/전송 SEND]
보라미 조회 → XVARM 브로커 →    복호화 → 포맷 → STT      →   에이전트 커넥터 bypass API 로 청크 전송(gzip · chunked)
파일 수신                                                    (file = 전사 JSON · metadata = exec_id·inmate_no·type·file_name)
                                       │
                                       ▼
            log-collector : T1 · T2(COLLECT 1 · ANALYZE 2 · SEND 3) · T4 적재 (API)
```

- 결과는 PV 에 남기지 않는다(옛 `xenon/` 폴더 없음). 에이전트 커넥터 전송이 실패하면 전사만 `{ROOT}/stt_temp/{execId}/` 에 보존했다가
  `resume=FROM_SEND` 재처리가 STT 없이 다시 보내고 지운다
- `agent-connector.mode` — `MOCK`(기본 · 수집기 내장 수신기) / `REST`(`agent-connector.base-url` + `/api/v1/learn/transfer` — 개발계 `http://agent-connector-dp8qbi7xqh:8080`)

| 하는 일 | 하지 않는 일 (다른 서비스 책임) |
|---|---|
| 대상 선별(특이수용자 필터) | 로그 테이블 INSERT → `log-collector` (API 로만 적재) |
| XVARM 추출 요청 | 재식별 매핑 → `data-collector` |
| 파일 수신·포맷 판별 | STT 엔진 자체 → NPU 서버 |
| 복호화 | 클라우드 전송 · 비식별화(커넥터) → **하지 않는다** (2026-10-01 제외) |
| STT 호출 · 원본 즉시 삭제 · **에이전트 커넥터 전송** · 처리 이력 적재(T2 COLLECT·ANALYZE·SEND) | 커넥터 → 제논 중계 · 그 뒤 적재·활용 → 에이전트 커넥터 · 제논 |

**R&R 은 2026-08-19 에 확정된 것이다.** 로그 테이블(T1~T11)의 단일 writer 는 로그 컬렉터다.
이 서비스도 자체 로그 테이블을 만들지 않는다.

---

## 2. 5개 스위치 — 준비된 것부터 실물로

외부 의존이 서로 다른 시점에 준비된다. 하나가 막혀도 나머지는 진행되도록 각각 독립 스위치다.
접견(브로커)과 전화(파일 연계)는 연동 주체가 달라 스위치도 따로 둔다.

| 스위치 | 값 | 기본 (`local`) | 실물 전환 조건 |
|---|---|---|---|
| `voice.source.mode` | `MOCK` / `DIRECT_JDBC` / `ESB_HTTP2DB` — 화면 표기 `MOCK (로컬 H2)` / `개발계 DB` / `메타빌드 (ESB)`. **드롭다운이 곧 어느 DB 에 붙는지**다: MOCK → 로컬 H2, 개발계 DB → `voice.source.direct-db`(PostgreSQL) | MOCK (MOCK) | 인터페이스ID(Q2)·I/F 테이블(Q15) |
| `voice.source.xvarm-mode` | `MOCK_DEV` / `REAL` — 화면 표기 `XVARM DB MOCK (개발계)` / `실 XVARM DB` (개발계 DB 모드 라디오) | **MOCK_DEV** | 실 XVARM·공통파일 테이블 확보 |
| `voice.broker.mode` | `MOCK` / `REST` — 접견 전용 | MOCK (**REST**) | XVARM 사양(Q3), 브로커 배포 |
| `voice.phone.mode` | `MOCK` / `ESB` — 전화 전용 | MOCK (MOCK) | ESB 전화 연계 프로바이더 구성 |
| `voice.decrypt.mode` | `SKIP` / `REAL` | SKIP (SKIP) | 복호화 주체 확정(Q13), 키 수령(Q8) |
| `voice.stt.mode` | `MOCK` / `NPU` | MOCK (MOCK) | NPU API 사양(Q9) |

> **REAL 모드는 실패해도 Mock 으로 빠지지 않는다**(Fail-fast). Mock 결과가 실제인 양 섞이면
> 시연·검증이 통째로 무의미해지기 때문이다.

### 런타임 전환 — 재시작이 필요 없다

네 구현이 모두 빈으로 떠 있고, 라우터가 **호출 시점에** 현재 모드를 보고 위임한다.
그래서 시연 중에도 "이건 아직 Mock, 이건 실물"을 바꿔 가며 보여줄 수 있다.

```bash
curl -X PUT "http://localhost:8085/api/v1/mock/modes/stt?value=NPU"
curl      "http://localhost:8085/api/v1/mock/modes"      # 현재값 + 기동 설정값 + 허용값
curl -X POST "http://localhost:8085/api/v1/mock/modes/reset"
```

시뮬레이터 화면의 드롭다운이 이 API 를 호출한다.

> 런타임 변경은 **이 프로세스에만** 남는다. 재기동하면 설정값으로 돌아간다 —
> 운영에서 실수로 바꾼 모드가 영구히 남지 않게 하려는 것이다.

---

## 3. 로컬 실행

```bash
mvn spring-boot:run
```

**프로파일을 안 주면 `local` 로 뜬다**(`spring.profiles.default: local`) — IntelliJ 에서 Active profiles 를
비워 둬도 같다. K8s 이미지는 Dockerfile 이 프로파일을 박아 넣으므로 영향이 없다.

`local` 의 기본은 **포트 8085 · 보라미 조회 `DIRECT_JDBC`(H2 Mock 보라미) + 브로커 `REST`(8082)** 다.
그래서 접견 트랙을 돌리려면 **브로커가 8082 에 `local` 프로파일로 떠 있어야 한다** — 없으면
접견 배치가 바로 실패한다(Fail-fast, 의도된 동작). 전화·복호화·STT 는 MOCK 이라 외부 의존이 없다.

```bash
# 브로커 (다른 레포) — local 프로파일이 출력 폴더를 이 레포의 work/voice_raw/meet(= voice.dirs.receive-meet) 로 맞춘다
cd ../data_borami-xvarm-broker-1joiuorqhl
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

브로커 없이 돌리려면 시뮬레이터의 [XVARM 브로커] 드롭다운을 `MOCK` 으로 내리면 된다
(또는 `VOICE_BROKER_MODE=MOCK`).

### 시뮬레이션 데이터 — DB 메타 14건 + 물리 더미 파일 (Complete Clean & Seed)

**접견 7 · 전화 7 = 14건**을 `SimulationDataService` 가 만든다. DB 메타(접견 re·im·sm·xvarm 4단 1:1:1:1, 전화 im 통화내역 + im 특이수용자 1:1)와
DB 파일명과 1:1 인 **경량 더미 음성 파일**(수십 byte)을 한 번에 쓴다. 로컬(H2)은 기동 시 자동 생성하고(`voice.sim.seed-on-startup=true`),
개발계(PostgreSQL)는 시뮬레이터 버튼으로만 만든다.

| 배치 | 시간창 | 접견 | 전화 | 시각 |
|---|---|---:|---:|---|
| 일배치 `POST /batches/daily` | [어제 00:00, 오늘 00:00) | 5 | 5 | 어제 09:10 ~ 09:50 (10분 간격) |
| 10분 주기 `POST /batches/periodic` (접견만·전화만 포함) | [지금-20분, 지금) | 2 | 2 | 지금-6분 / 지금-3분 |

| 버튼 / API | 하는 일 |
|---|---|
| **시뮬레이션 데이터 생성** (상단) `POST /api/v1/mock/sim-data` (= `/reset`) | 표준 폴더 확인/생성 → 멱등 표식·수신 파일 삭제 → SIM 행 삭제 → (개발계) 누락 테이블 생성 → 14건 INSERT → 더미 파일 15개 write(전화 기존 STT 텍스트 1개 포함) |
| **시뮬레이션 데이터 초기화** (상단) `DELETE /api/v1/mock/test-data` | 테스트 이력(TST 로그·STT 출력 폴더) + SIM 접두 행 일괄 DELETE(수용자·녹취·통화·공통파일·XVARM) + 더미 파일(`mock_*`) + 멱등 표식 — 시뮬레이터가 만든 것 전부 |
| 시뮬레이션 데이터만 초기화 `DELETE /api/v1/mock/sim-data` | 위에서 테스트 이력을 뺀 것(API 전용) |
| 현황 `GET /api/v1/mock/sim-data` | DB 종류 · 조립된 테이블명 · SIM 행 수 · 더미 파일 목록 |

- 우리가 넣은 행은 전부 **`SIM` 접두**(교정번호 `SIM…`, 녹취 `SIM-MEET-nnn`, 통화 `SIM-PHONE-nnn`, 공통파일 `SIMCMFInnnn`, 문서 `SIMDOCnnnn`)라
  초기화가 운영·다른 사람 행을 건드리지 않는다. INSERT 는 테이블 정의서의 NOT NULL 컬럼을 전부 채운다(개발계 실제 테이블 기준)
- 더미 파일 위치: `{ROOT_DIR}/xvram/original_voice_files` (아래 "표준 디렉터리" 참조). `TARE_FLPTH_NM`/`TELP_RECRD_FLPTH_NM` 이 이 폴더, XVARM `FILEKEY` 가 파일 절대경로다
- 시각은 **생성 시점 기준**이라 기동 후 20분이 지나면 주기배치 창이 비니, 시연 직전에 [시뮬레이션 데이터 생성] 을 한 번 누른다.
  전체 배치는 10초 안에 끝난다(접견은 로컬 브로커 추출 지연 `BROKER_DELAY_MS`=1500ms 가 대부분 — 더 빠르게는 브로커에 `BROKER_DELAY_MS=0`)

### XVARM 연동 — 개발계 DB 에 없는 두 테이블

2026-09-12 확인 기준 개발계 borami-db 에는 **공통파일기본(`sm.tb_smsm_cmfi_bs`)과 XVARM(`asyscontentelement`)이 없어** 접견 4단 조인이 서지 않는다.
`개발계 DB` 모드의 라디오 `voice.source.xvarm-mode` 로 고른다.

| 값 | 화면 | 동작 |
|---|---|---|
| `MOCK_DEV` (기본) | XVARM DB MOCK (개발계) | 개발계 DB 에 `sm.tb_smsm_cmfi_bs` · `xvarm.asyscontentelement` 를 **자동 생성**(`CREATE … IF NOT EXISTS`, `src/main/resources/sql/xvarm_mock_tables_postgres.sql`)하고 시뮬레이션 데이터를 시딩해 4단 조인이 돈다. 스키마는 `voice.source.xvarm-mock.schema-smsm/schema-xvarm` |
| `REAL` | 실 XVARM DB | `voice.source.schema.smsm/xvarm` 의 실 테이블을 직접 조인한다 — 없으면 조회가 사유와 함께 실패한다 |

DBeaver 에서 손으로 만들려면 [`docs/borami_missing_tables.sql`](docs/borami_missing_tables.sql)(DDL + 시딩 + 확인 쿼리 + 정리).
컬럼 정의 근거는 `ref/c9uviulsfMXZ-비정형-140926-020258.pdf`(테이블 정의서, TB_SMSM_CMFI_BS 17컬럼)이고,
`ref/(발췌)교정청_표준인터페이스_설계서.pdf` 는 ESB 연계 규약이라 테이블 정의는 없다. XVARM 테이블은 솔루션 소유라 조인 키(ELEMENTID·FILEKEY)만 둔다.
> `ref/` 는 출처 자료를 두는 **로컬 폴더로 형상관리 대상이 아니다**(`.gitignore` 의 `/ref`). 공유하는 산출물은 `docs/` 에 있다.

> **브로커 출력 경로가 어긋나면** 브로커는 "추출 완료" 를 돌려주지만 우리 수신 폴더는 비어 있다.
> 브로커 응답의 `filePath`(절대경로)에 파일이 **실제로 있는데** 수신 폴더 밖이면 5분을 기다리지 않고
> 그 건을 즉시 실패시키며 사유에 두 경로를 적는다(`BrokerOutputCheck`). 운영은 보라미 서버 경로라
> 우리 쪽에 존재하지 않아 이 판정에 걸리지 않는다. 배치 전 **[브로커 연결 확인]** 으로 대조하는 것이 먼저다.

### 로컬 포트 배치

사내 서비스를 동시에 띄워도 겹치지 않게 정해져 있다.

| 포트 | 서비스 | 비고 |
|---:|---|---|
| 8080 | `agent-connector` | SEND 전송 대상(bypass API) — REST 로 붙일 때 `AGENT_CONNECTOR_BASE_URL=http://localhost:8080` |
| 8082 | `borami-xvarm-broker` | XVARM 브로커 — 접견 트랙이 호출 |
| **8085** | **`voice-collector`** | 이 서비스 |
| 8090 | `log-collector` | context-path **`/logc`** |

> 8080·8090 은 각 프로젝트의 `application-local.yml` 에 이미 정해져 있고 8082 는 브로커가 쓴다.
> 그래서 이 서비스는 8085 를 쓴다.

### 화면 두 개

| 주소 | 용도 |
|---|---|
| **`http://localhost:8085/unstructured_collector_simulator.html`** | **시뮬레이터** — 시연용 조작 화면 |
| `http://localhost:8085/swagger-ui.html` | Swagger — API 개별 호출·스펙 확인 |
| `https://<admin-fe>/unstructured_collector_simulator.html` | **배포 환경** — admin-fe(nginx)가 같은 HTML 을 정적으로 서빙하고 `/voice/` 를 수집기로 프록시 |

**배포 환경은 admin-fe 를 통해서만 화면이 뜬다.** 같은 HTML 이 `service_admin-fe-iqgor1oiru/public/unstructured_collector_simulator.html` 에도 있고,
화면 JS 가 API base 를 `''` → `/voice` 순으로 자동 감지한다(수집기가 직접 서빙하면 `''`, admin-fe 면 `/voice`; `?api=http://host:port` 로 강제 가능).
admin-fe 의 `custom-nginx.conf` 에 `location = /unstructured_collector_simulator.html`(정적)과 `location /voice/`(→ `voice-collector-x2daarjxe4:8080`) 가 있다.

백엔드 빌드·push 때 admin-fe 도 같이 올린다 — **`scripts/sync-admin-fe.ps1`**(`.sh`)이 HTML 을 복사하고 admin-fe 의
`public/unstructured_collector_simulator.html` · `custom-nginx.conf` 를 add · commit · push 한다:

```powershell
.\scripts\sync-admin-fe.ps1            # 복사 → 변경 있으면 commit · push (admin-fe 현재 브랜치)
.\scripts\sync-admin-fe.ps1 -NoPush    # commit 까지만
```

### 로그 컬렉터 연동 (로컬)

K8s 의 DB 만 포트포워딩하고 컬렉터 앱은 로컬에서 직접 띄우는 구성을 기본값으로 잡아 두었다.

```yaml
log-collector:
  enabled: true
  base-url: http://localhost:8090/logc     # context-path 까지 포함해야 한다
```

- 컬렉터가 떠 있으면 **T1·T2·T4** 가 실제로 적재되고 EXEC_ID 도 컬렉터가 채번한다
- 떠 있지 않아도 이 서비스는 정상 동작한다 — 호출 실패를 경고만 남기고 넘어가며,
  EXEC_ID 는 로컬 임시 ID(`…VOC-LOCAL`)로 대체된다

시뮬레이터 화면 구성 (상세 정보는 전부 **아코디언**이라 접힌 채 얇게 보이고, 클릭하면 스르륵 펼쳐진다):

- **헤더** — [Swagger] · **[시뮬레이션 데이터 생성]**(`POST /api/v1/mock/sim-data`) · **[시뮬레이션 데이터 초기화]**(`DELETE /api/v1/mock/test-data` — 테스트 이력 + 시뮬레이션 데이터 전부) · [상태 새로고침].
  그 아래 "환경" 줄에 자동 감지 결과(LOCAL/K8S · OS · ROOT_DIR · 브로커 · 로그 컬렉터 주소와 출처)가 뜬다
- **상단 서브헤더 — 디렉터리** — ROOT_DIR 과 표준 6종(XVARM 접견 원본 · ESB 수신 접견/전화 · XVARM 복호화(작업) · 최종 저장 접견/전화) 경로를 한 줄로 보여주고,
  프리셋 버튼 `기본(자동 감지)` / `Windows C:/k8s/unstructured_collector` / `PV /k8s/unstructured_collector` 을 고르는 즉시 반영한다(현재 값과 같은 프리셋이 진하게).
  [편집 ▾] 을 누르면 6종 입력칸과 ROOT_DIR 로 표준 배치 채우기가 펼쳐진다 — 로드될 때 OS 감지값으로 채워져 있다.
  ESB 수신(접견)을 바꾸면 로컬 브로커의 `BROKER_OUTPUT_DIR` 도 맞춰야 한다 — [브로커 연결 확인] 으로 대조
- **① 처리 구간 모드** — 접견·전화 2트랙의 스위치를 **드롭다운으로 즉시 전환**(서버 재시작 불필요).
  데이터 조회는 `MOCK (로컬 H2)` / `개발계 DB` / `메타빌드 (ESB)` 로 표기하고, `개발계 DB` 를 고르면 그 아래
  **XVARM 연동 라디오**(`XVARM DB MOCK (개발계)` 기본 / `실 XVARM DB`)가 뜬다. 지금 붙어 있는 DB 와 조립된 테이블명도 한 줄로 보인다.
  MOCK/SKIP 은 주황, 실물은 초록. 로그 컬렉터 연결 여부도 함께 본다.
  브로커가 `REST` 면 단계 아래에 **주소 라디오**(`개발계 K8s` / `로컬 PC`)가 뜨고 고르는 즉시 반영된다 —
  기동 설정값과 같은 항목에 `(Default)` 가 붙는다(`voice.broker.presets` 에서 내려준다)
- **② 제어**
  - 배치 4종(왼쪽부터): **`전체 실행(10분 주기)`**(`POST /api/v1/voice/batches/periodic`) · **`전체 실행(일배치)`**(`/daily`) — 기본색 ·
    `접견만`(`/periodic?kinds=MEET`) · `전화만`(`?kinds=PHONE`) — 옅은 색. 실행 중에는 버튼이 잠기고 누른 버튼에 스피너가 돈다
  - 보조: `대상 미리보기` · `수신 파일` · `STT 출력 확인` · `브로커 연결 확인` (시뮬레이션 데이터 생성/초기화는 헤더로 옮겼다)
  - **고급 (접힘)**: 대용량 Mock(일배치용 건수 + 프리셋 1,000 / 5,000 / 10,000건) · 장애 주입(활성 토글 + 실패 % · 지연 % · 지연 ms).
    규모를 올리거나 장애 주입이 켜져 있으면 접힌 상태에서도 노란 요약이 뜬다
- **③ 최근 배치 결과** — 대상 / 성공 / 실패 / 건너뜀 / 상태 숫자 +
  **요약 바** `[HTTP 200 OK] [POST] /api/v1/voice/batches/periodic?kinds=MEET&test=true · 성공 1 / 실패 0 · SUCCESS · 1965ms [▼]`.
  ▼ 를 누르면 **REST 명세**(Request · Query · Request Body · Response 상태·소요) + EXEC_ID·T2 단계 배지·STT 출력 폴더 +
  **Response JSON**(구문 강조 · 복사 · 펼치기)이 펼쳐진다.
  그 아래 **처리 파일 · STT 텍스트** 표도 아코디언(건수·성공/실패 요약만 보이고 펼치면 표)
- **④ 재처리(Retry) 시나리오** — ① 브로커 장애 주입(`http://localhost:9999`) → ② 1차 배치(실패 이력) →
  ③ 브로커 복구(`http://localhost:8082`) → ④ 재처리 배치. 버튼 4개로 하나씩, 또는 **🚀 원클릭 자동 실행**
  (간격 설정 · 실행 전 Mock 초기화 옵션). 단계별 타임라인 카드는 상태 배지 + 한 줄 요약(② `FAIL · 성공 0 / 실패 1 · EXEC_ID`,
  ④ `SUCCESS · 성공 1 / 실패 0 · EXEC_ID`)만 보이고, 카드를 클릭하면 REST 명세와 Response JSON 이 펼쳐진다.
  아래 **② vs ④ JSON 대조** 바를 펼치면 실패 JSON 과 성공 JSON 이 나란히 놓인다
- **⑤ 실행 로그** — 호출 API·응답 시간·결과 요약·T2 단계·실패 사유

**시연 순서**: `Mock 데이터 초기화` → `처리 대상 미리보기` → `배치 실행` → **한 번 더 배치 실행**
(두 번째는 `건너뜀`으로 잡혀 멱등 동작이 드러난다) 

```bash
# CLI 로 하려면
curl -X POST "http://localhost:8085/api/v1/mock/reset"          # 초기화
curl      "http://localhost:8085/api/v1/mock/targets"           # 대상 미리보기
curl -X POST "http://localhost:8085/api/v1/voice/batches/daily" # 배치 실행
curl      "http://localhost:8085/api/v1/voice/status"           # 현재 구성
```

> `/api/v1/mock/**` 는 **시연 전용**이다. 운영 배포 시 인그레스·게이트웨이에서 차단한다.

### 조회 DB 라우팅 — 드롭다운이 곧 DataSource

보라미 DataSource 는 **두 개**이고 조회 모드가 어느 쪽을 쓸지 정한다(`BoramiDbRouter`, MyBatis·JdbcTemplate·트랜잭션 공통).

| 드롭다운 | 붙는 DB | 설정 |
|---|---|---|
| `MOCK (로컬 H2)` | 인메모리 H2 Mock 보라미 — 기동 시 스키마·필터 검증 행 + 시뮬레이션 14건 자동 | `voice.source.local-h2` |
| `개발계 DB` | 개발계 borami-db(PostgreSQL). 로컬은 포트포워딩(15433) 전제 | `voice.source.direct-db` (`BORAMI_DB_URL` / `BORAMI_DB_USER` / `BORAMI_DB_PASSWORD`) |
| `메타빌드 (ESB)` | DB 를 쓰지 않는다 — ESB HTTP2DB | `voice.source.esb-base-url` |

- 예전에는 DataSource 가 하나라 `개발계 DB` 를 골라도 H2 를 봤다(생성 완료인데 DBeaver 에는 아무것도 없던 이유).
  지금은 드롭다운을 바꾸는 즉시 조회·시뮬레이션 데이터 생성·초기화가 그 DB 로 간다. ① 카드의 **조회 DB** 줄과
  [DB 연결 확인](`GET /api/v1/mock/db/probe`)이 어느 DB 에 붙어 있는지, 붙는지(사유 포함)를 보여 준다
- 개발계 DB 는 기동 시 붙어 보지 않는다(포트포워딩 없이도 뜬다). 붙지 못하면 `DB_ERROR` 와 함께 포트포워딩 안내가 응답에 실린다
- H2 Mock 스키마 적재(`schema-borami-mock.sql`, DROP TABLE 포함)는 **H2 DataSource 에만** 한다 — `spring.sql.init` 은 쓰지 않는다(라우터에 걸면 실DB 로 갈 수 있다)
- 스키마 접두(im/re/sm/xvarm)는 개발계·운영 DB 에만 붙고 H2 는 늘 평평하다

### 4단 조인 SQL 검증 (H2 Mock)

H2 에 보라미 Mock 스키마가 올라간다. `MOCK (로컬 H2)` 모드가 이 DB 를 JDBC 로 조회한다(MyBatis SQL 이 실제로 돈다).

`data-borami-mock.sql` 에는 **걸러져야 할 행**만 있다(삭제된 녹취, 해제된 특이수용자, 대상 아닌 관리코드,
녹음 안 된 통화 — 대상이 되지 않아 배치 시간에는 영향이 없다). 유효 대상 14건은 시뮬레이션 데이터 생성이 만든다.

**DIRECT_JDBC 원본 쿼리**(접견 4단 조인 · 전화 조인 · 전화 지름길)를 스키마·테이블명과 값을 풀어
DBeaver 에서 바로 실행할 수 있게 [`docs/borami_direct_jdbc_queries.sql`](docs/borami_direct_jdbc_queries.sql) 에 두었다.

### 개발계 borami-db 조회

포트포워딩을 열고 드롭다운을 `개발계 DB` 로 바꾸면 된다(재기동 불필요). 계정은 환경변수로.

```bash
kubectl port-forward -n data-pipeline svc/borami-db-gijoxearrw 15433:5432
BORAMI_DB_USER=borami BORAMI_DB_PASSWORD=... mvn spring-boot:run
```

기동부터 개발계 DB 를 보고 실DB 플래그 값(`ptcr-yes=0`)까지 맞추려면 `realdb` 프로파일을 겹친다:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local,realdb
```

> 개발계 DB 에 [시뮬레이션 데이터 생성] 을 누르면 XVARM 모드가 `MOCK_DEV` 일 때 누락 테이블(`sm.tb_smsm_cmfi_bs` · `xvarm.asyscontentelement`)을
> 먼저 만들고 14건을 넣는다. [시뮬레이션 데이터 초기화] 가 `SIM` 접두 행(과 테스트 이력)을 지운다. 대용량(14건 초과) 시딩은 로컬 H2 에서만 허용한다.

로그 컬렉터용 DB는 별도다(네임스페이스가 다르니 `-n` 을 빠뜨리지 말 것).

```bash
kubectl port-forward -n service-core svc/admin-db-fy9tjq4tsk 15432:5432
```

| 대상 | 포트 | database | user | 용도 |
|---|---:|---|---|---|
| `admin-db` (service-core) | 15432 | `correction_ai` | `kcais` | 로그 컬렉터(`kcais` 스키마) |
| `borami-db` (data-pipeline) | 15433 | `borami` | `borami` | 보라미 조회 |

---

## 4. 안정성 검증 기능

### 대용량 부하 (OOM 방어)

실데이터 규모는 접견 약 1,300건(6.5GB) · 전화 약 1,300건이다. 14건짜리 시뮬레이션 데이터로는
스트리밍·청크 처리가 메모리를 지키는지 알 수 없어, MOCK 소스의 **일배치용** 건수를 런타임에 올릴 수 있게 했다
(주기배치용 2·2 는 그대로라 10분 주기 배치가 수백 건을 돌 일이 없다). 올린 값은 [시뮬레이션 데이터 생성] 이 기본으로 되돌린다.

```bash
curl -X PUT "http://localhost:8085/api/v1/mock/dataset?meet=500&phone=500"
```

- 건수를 올려도 **힙이 비례해 늘지 않아야** 한다 — 파일은 스트리밍으로 다루고
  건별로 처리한 뒤 바로 지운다
- **200건을 넘으면** Mock WAV 를 1초짜리로 줄인다. 우리가 보려는 것은 건수에 대한
  메모리 거동이지 파일 크기가 아니고, 10,000건 × 2초는 640MB 라 디스크만 잡아먹는다
- **처리 시간 주의**: 건당 파일 안정성 검사(`stable-check-ms`, local 100ms)가 그대로
  곱해진다. 1,000건 ≈ 2분, 10,000건 ≈ 20분

### 장애 주입 (Chaos)

```bash
curl -X PUT "http://localhost:8085/api/v1/mock/chaos?enabled=true&failPercent=10&delayPercent=10&delayMs=2000"
```

STT 구간에 의도적으로 실패와 지연을 섞는다. **확인하려는 것은
파일 1건이 터져도 배치가 죽지 않고 `failCnt` 만 올리며 끝까지 도는가**이다.
1,300건 배치에서 한 건 때문에 전체가 멈추면 나머지를 전부 다시 처리해야 한다.

- 기본은 꺼져 있고, 켜져 있으면 `status` 와 시뮬레이터 화면에 항상 노출된다
  (켜진 줄 모르고 시연하면 실패 건수를 버그로 오해한다)
- 실패·지연은 **독립 판정** — 지연되면서 실패할 수도 있다
- 주입된 예외는 `InjectedFaultException` 이라 진짜 버그와 로그에서 구분된다

### PII 원본 즉시 삭제

정책은 "복호화된 원본 음성은 STT 완료 즉시 삭제"다(계획서 5.3-(4)).

- 삭제는 `processOne` 의 **`finally`** 에서 한다 — STT 가 실패하는 경로에서도 지워야 한다.
  성공 경로에서만 지우면 실패한 건의 음성이 디스크에 남는다
- `ResilienceE2ETest` 가 수신·작업 디렉터리를 직접 세어 **전부 실패한 배치에서도 잔여 0** 임을 검증한다
  (예전의 `PiiResidueAuditor` · `GET /api/v1/mock/pii-residue` · 결과의 `residue` 는 제거했다)

### 환경 자동 감지 — ROOT_DIR · 브로커 · 로그 컬렉터 (`DeployEnvPreset`)

기동 시 **OS 와 active profile** 로 환경을 정하고, 설정이 비어 있는 값을 채운다. 환경변수(ConfigMap)로 주면 그것이 이긴다.

| | Windows / 로컬(local 프로파일) | Linux / 배포(K8s) | 덮어쓰기 |
|---|---|---|---|
| ROOT_DIR | `C:/k8s/unstructured_collector` | `/k8s/unstructured_collector` | `VOICE_BASE_DIR` |
| XVARM 브로커 (①카드 라디오 · ④ 복구 주소) | `http://localhost:8082` | `http://borami-xvarm-broker-1joiuorqhl:8080` | `VOICE_BROKER_BASE_URL` |
| 로그 컬렉터 | `http://localhost:8090/logc` | `http://log-collector-a2z96kgyrm:8080/logc` | `LOG_COLLECTOR_BASE_URL` |

- ROOT_DIR 은 OS 로, URL 은 환경 종류(LOCAL/K8S)로 정한다. `GET /api/v1/voice/config`(와 `/status` 의 `env`)가 이 값을 내려주고,
  시뮬레이터가 로드될 때 상단 디렉터리 input · [XVARM 브로커] 라디오 · 로그 컬렉터 카드 · DB 정보를 그 값으로 맞춘다(헤더 "환경" 줄에 출처 표기)
- 로그 컬렉터 K8s 주소는 차트(`data_HelmChart/pipeline/log-collector-a2z96kgyrm`)의 Service 명 `log-collector-a2z96kgyrm` · 포트 8080 이다

### 표준 디렉터리 4종 — ROOT_DIR 아래

```
{ROOT_DIR}/xvram/original_voice_files   XVARM 접견 원본 (시뮬레이션 더미 파일)      voice.dirs.xvarm-original  VOICE_XVARM_ORIGINAL_DIR
{ROOT_DIR}/esb/meet                     ESB 원본 수신 (접견) — 브로커 BROKER_OUTPUT_DIR 과 같아야   receive-meet   VOICE_MEET_DIR
{ROOT_DIR}/esb/phone                    ESB 원본 수신 (전화)                         receive-phone  VOICE_PHONE_DIR
{ROOT_DIR}/xvram/decoding               XVARM 접견 복호화 (작업 · 멱등 표식)         work           VOICE_WORK_DIR
{ROOT_DIR}/stt_temp/{execId}            에이전트 커넥터 전송 실패 건의 전사 보존(FROM_SEND 재처리용 · 보낸 뒤 삭제)
```

> 최종 결과 폴더 `xenon/meet|phone` 와 설정 `output-meet`·`output-phone`(`VOICE_OUTPUT_*_DIR`)은 2026-10-01 없앴다 — STT 결과는 에이전트 커넥터(bypass → 제논)로 보낸다.

- 없는 폴더는 **앱 기동 · 시뮬레이션 데이터 생성/초기화 · 경로 변경** 때 만든다(CREATE_IF_NOT_EXISTS)
- STT 텍스트는 에이전트 커넥터(bypass → 제논)로 보낸다. 배치 응답의 `transfer`(모드·주소·보낸 건수), `outcomes[].sttPath` 가 파일별 전송 위치(`agent-connector:mock/…`).
  보낸 내용(메타데이터 · 전사 미리보기)은 `GET /api/v1/mock/agent-connector/receipts?execId=` 로(수집기 메모리에 최근 `agent-connector.keep-receipts` 개)
- 로컬 브로커(local 프로파일)는 기본으로 다른 폴더에 떨구므로 브로커에 `BROKER_OUTPUT_DIR=C:/k8s/unstructured_collector/esb/meet`(K8s 는 `/k8s/unstructured_collector/esb/meet`)를 준다 —
  [브로커 연결 확인] 이 두 경로를 대조한다
- 런타임 변경: `GET/PUT /api/v1/mock/dirs` · `PUT /api/v1/mock/dirs/preset?key=configured|win|pv|base&baseDir=` · `POST /api/v1/mock/dirs/reset`
- [시뮬레이션 데이터 초기화](`DELETE /api/v1/mock/test-data`)가 시험 배치의 전송 수신증 · 재처리 보존물과 **이미지 SIM**(6번 탭 [SIM 데이터 정리] 와 같은 API)까지 지운다

### T2 단계 로그 — COLLECT · ANALYZE · SEND

로그 컬렉터의 비정형 체인은 `COLLECT 1 → ANALYZE 2 → SEND 3` 이다(2026-10-01 DEIDENT 제외). 이 서비스는 세 단계를 모두 남긴다
(`POST /api/v1/logs/batches/{execId}/steps` → `PATCH /api/v1/logs/steps/{stepLogId}`).

| 단계 | 여는 시점 | 마감 시점 | in / out / err |
|---|---|---|---|
| `COLLECT` | 배치 시작 | 전 건 처리 후 | 대상(건너뜀 제외) / 파일 확보·복호화 성공 / 확보 실패 |
| `ANALYZE` | 첫 STT 직전 | **STT 처리가 끝난 직후** | 확보 성공 / STT 성공 / STT 실패 |
| `SEND` | 첫 에이전트 커넥터 전송 직전 | 전송이 끝난 직후 | STT 성공 / 커넥터 수신 SUCCESS / 전송 실패(전사 보존) |

- `data-type-cd` 는 **`UNSTRUCTURED`**(C01 4종) — 예전 `VOICE` 는 C01 에 없어 대시보드 필터·체인 순번을 타지 못했다
- 파일별 실패 단계는 `outcomes[].failedStep` 에 남는다. T1 마감에는 대표 오류 `[코드] 상세` 와 `ERR_TYPE_CD`(CONNECTION/TIMEOUT/DATA)가 실린다

### 재처리(Retry) 시나리오

실패한 건은 멱등 표식이 남지 않으므로 **다음 배치가 자동으로 다시 처리**한다. 시뮬레이터 ⑤ 가 이것을 4단계로 보여준다.

```bash
curl -X PUT  "http://localhost:8085/api/v1/mock/endpoints/broker?value=http://localhost:9999"   # ① 장애
curl -X POST "http://localhost:8085/api/v1/voice/batches/periodic?kinds=MEET&test=true"          # ② FAIL — T1 FAIL · T2 COLLECT FAIL · T4 FAIL
curl -X PUT  "http://localhost:8085/api/v1/mock/endpoints/broker?value=http://localhost:8082"   # ③ 복구
curl -X POST "http://localhost:8085/api/v1/voice/batches/periodic?kinds=MEET&test=true"          # ④ SUCCESS — 새 EXEC_ID · COLLECT·ANALYZE SUCCESS
```

> 브로커 요청 키는 `VOC-{execId}-{대상키}` 다. 대상만으로 키를 만들면 브로커의 멱등 캐시가 "이미 DONE" 을 돌려주고
> 파일은 이미 지워진 뒤라 재처리 배치가 수신 대기 타임아웃으로 실패한다(실제로 그랬다).

---

## 5. 실DB에서 확인된 차이 (2026-09-12)

`borami-db`에 직접 붙어 확인한 결과, **문서만 보고 짠 SQL 로는 동작하지 않는** 지점들이 있었다.
전부 설정으로 흡수했으니, 값만 바꾸면 실연동에 대응된다.

### ① 테이블이 스키마로 나뉘어 있다

```
im.tb_imsc_ptpr_dt    특이수용자상세      636행
re.tb_rerd_tfin_ds    녹취파일내역        106행
im.tb_imph_ucdr_ds    사용자통화내역       25행
```

회의 축어록 `[00:17:26]` 의 *"TVG의 RE면 RE점, IM이면 IM점"* 이 이 구조다. 테이블명 접두어가 곧 스키마다.
`voice.source.schema.{imsc,rerd,smsm,xvarm}` 로 주입하며, 비우면 수식 없이(H2 Mock) 나간다.

### ② 플래그 값 도메인이 문서와 다르다

```
telp_ptcr_prsr_yn  =  '0'  × 25건        ← 'Y' 로 비교하면 영원히 0건
telp_pcall_recrd_yn=  'N'  × 25건
telp_recrd_file_id =  NULL × 25건
```

스펙 문서에는 `CHAR NOT NULL` 이라고만 적혀 있고 값 정의가 없다. **에러가 아니라 "대상 없음"으로
보여서 알아채기 가장 어려운 종류의 결함**이라, `voice.source.flag.*` 로 빼 두었다.

### ③ 접견 경로의 3·4단 테이블이 없다

```
TB_SMSM_CMFI_BS      MISSING   (sm 스키마엔 공통코드 8개뿐)
ASYSCONTENTELEMENT   MISSING   (XVARM 관련 테이블 전무)
```

접견은 실DB 로 `DOC_ID`·`FILEKEY` 를 얻을 수 없다. 1·2단(특이수용자→녹취) 조건에 맞는 건은 11건 있다.
**접견은 두 테이블이 확보될 때까지 MOCK 을 유지한다.**

조회에 실패하면 조립된 테이블명과 원인을 그대로 돌려준다.

```
CONFIGURATION_ERROR
  현재 대상: 특이수용자=im.TB_IMSC_PTPR_DT, … 공통파일=sm.TB_SMSM_CMFI_BS, …
  원인     : ERROR: relation "sm.tb_smsm_cmfi_bs" does not exist
```

### ④ 계획서 Q1 — 보라미 기존 STT는 없었다

```
im.tb_imph_ucdr_ds : total=25, telp_stt_flpth_nm 채워진 건 = 0
```

이 DB 기준으로는 **보라미가 STT 결과를 갖고 있지 않다.** 운영 DB는 다를 수 있으나 강한 신호다.

---

## 6. 절대 바꾸면 안 되는 것

### T4 = 파일 1건 = 1행

정합성 규칙이 `TB_BATCH_EXEC_LOG.SUCCESS_CNT == Σ(T3·T4·T5)` 다. 이걸 어기면 배치 전체의
대사가 깨진다. `SKIPPED`(멱등으로 건너뛴 건)는 이번 배치가 처리한 것이 아니므로 T4 에 넣지 않는다.

### `CMMN_FILE_ENC_YN = 'N'` 인 파일

암호화되지 않은 파일이다. 복호화하면 **멀쩡한 원본이 깨진다.** `DecryptService` 가 먼저 거른다.

---

## 5. Turaco 레포가 생기면 — 병합 절차

이 프로젝트는 **Turaco 레포가 나오기 전에 먼저 만든 것**이다. Turaco 가 만들어 주는 레포에는
템플릿 소스가 들어 있으므로, 그쪽을 base 로 두고 우리 소스만 얹는다.

```bash
git clone https://github.com/twolinecloud/data_voice-collector-<suffix>.git
cd data_voice-collector-<suffix>

# 우리 소스만 덮어쓴다
cp -r <이 프로젝트>/src/main/java/egovframework      src/main/java/
cp -r <이 프로젝트>/src/main/resources/*             src/main/resources/
cp -r <이 프로젝트>/src/test/java/egovframework       src/test/java/
```

| 파일 | 처리 |
|---|---|
| `Jenkinsfile` · `src/main/docker/Dockerfile` | **Turaco 것 유지** — 변수는 Jenkins Job 이 주입한다 |
| `.gitattributes` · `settings.xml` · `.editorconfig` | **Turaco 것 유지** |
| `pom.xml` | **Turaco 것을 base** 로, 우리가 쓴 의존성만 옮겨 적기 |
| `src/**` | **우리 것으로 덮어쓰기** |

`pom.xml` 은 `agent-connector` 것을 복사해 식별자만 바꿔 둔 상태다
(`groupId`·`artifactId`·`name`·`description`·`final-name`). Turaco 가 만든 것과 비교해
의존성 차이만 병합하면 된다.

### `data_HelmChart` 에도 차트가 필요하다

Jenkins 파이프라인이 `${GROUP_NAME}_HelmChart` 레포의 `$STAGE/$SERVICE_NAME/values.yaml` 을
찾아 이미지 태그를 갱신한다. Turaco 가 자동으로 넣어 주지 않으면
`pipeline/voice-collector-<suffix>/` 를 직접 만들어야 한다.

**PVC 가 필요하다** — 이 서비스는 음성 파일을 디스크에 받는다.

| 마운트 | 용량 | 용도 |
|---|---:|---|
| `/k8s/voice_raw` | 15Gi | ESB 수신 (접견 6.5GB + 전화 0.4GB × 여유) |
| `/k8s/voice_work` | 5Gi | 복호화 작업·멱등 표식 |

---

## 6. 미확정 사항

계획서 11장에 14건이 정리되어 있다. 코드에 영향이 큰 것만 추리면:

| # | 내용 | 영향 |
|---|---|---|
| Q1 | `TELP_STT_FLPTH_NM` 에 이미 STT 결과가 있는가 | 사실이면 전화 건은 복호화·STT 불필요 |
| Q13 | 전화 복호화를 메타빌드가 하는가 우리가 하는가 | `PhoneAriaDecryptor` 구현 필요/불필요 |
| Q4 | 로그 컬렉터 T4 요청 DTO 필드명 | `LogCollectorClient.FileProcReq` 가 추정값 |
| Q7 | ESB 수신 파일명 규칙 (`ORIGINAL` / `ESB_DAT`) | `.DAT` 면 업무 키가 없어 매핑 수단이 따로 필요 |
| Q8 | `rvs_key.txt` · `rvs-media-decryptor.html` | 접견 복호화 구현 불가 |
| Q15 | 조회 대상이 원장 테이블인가 I/F 뷰인가 | `BoramiVoiceMapper.xml` 의 테이블명 |

미구현 지점은 **조용히 빈 값을 돌려주지 않고 명시적으로 실패**시킨다.
"대상 0건"과 "아직 안 만들었음"이 같아 보이면 배치가 성공한 것처럼 보이기 때문이다.

---

## 7. 패키지 구조

```
src/main/resources/static/unstructured_collector_simulator.html   ← 시연·성능 시험 화면(1~6번 탭)

egovframework.unstructured.collector
├─ common/     음성·이미지가 같이 쓰는 기반
│  ├─ config/    VoiceProperties · VoiceModeState(모드) · VoiceDirState(디렉터리) · 보라미 DataSource 라우터
│  │             · BoramiTableNames · DbKindDetector · SourcePoolPeak(Hikari 최고치·반납) · RestTemplate · MyBatis
│  ├─ broker/    XVARM 브로커 — Mock / Rest (음성 extract · 이미지 extractFile)
│  ├─ sync/      파일 수신 — FileArrivalWatcher · EsbFileNamingPolicy · PhoneFileProvider
│  ├─ decrypt/   복호화 — DecryptService · MediaDecryptor(RVS) · Noop / PhoneAria / MeetRvs
│  ├─ logging/   LogCollectorClient (T1 · T2 · T4)
│  ├─ transfer/  AgentConnectorClient · AgentConnectorProperties · AgentConnectorMockReceiver · TransferRuns — 에이전트 커넥터 bypass 전송(MOCK · REST 청크)
│  ├─ health/    연계 상태 점검
│  ├─ model/     VoiceTarget · VoiceFile · SttResult · FileProcOutcome …
│  └─ util/      InmatePidGenerator · AudioFormatDetector · SilentWav · SampleImage
├─ voice/      접견·전화 음성 수집 · STT 파이프라인
│  ├─ controller/ VoiceBatchController(운영·시연) · VoiceMockController · PerfController(Mock 전용)
│  ├─ batch/      VoiceCollectService(본체 — 확보/STT 워커 생산자-소비자) · Scheduler · IdempotencyGuard
│  ├─ source/     보라미 조회 — Mock / Jdbc / EsbHttp2Db · SimulationDataService
│  ├─ mapper/     BoramiVoiceMapper (MyBatis)
│  ├─ stt/        STT — Mock / Npu · SttTempStore(전송 실패 시 전사 보존)
│  ├─ transfer/   VoiceTransferDocument(전사 → 에이전트 커넥터 전송 문서)
│  └─ perf/       성능 시험(4·5번 탭)
└─ image/      수용자 이미지 수집
   ├─ controller/ ImageController(/api/v1/image) · ImageMockController(/api/v1/mock/image — 6번 탭)
   ├─ batch/      ImageCollectService(본체) · ImageMetrics
   ├─ source/     ImageSourceService — 보라미 조회 3단계
   ├─ store/      ImageFileStore(복호화·저장) · InmatePhotoRepository(Admin DB 매핑)
   ├─ config/     ImageProperties(image.*) · AdminDb(Admin DB 풀 — 스프링 빈 DataSource 아님)
   ├─ sim/        ImageSimulationService — SIMIMG 시뮬레이션 데이터
   ├─ perf/       ImagePerfService — 6번 탭 검증
   └─ model/      ImageTarget · ImageOutcome · ImageStage · ImageFormat
```

---

## 8. 수용자 이미지 수집

PL 요구사항(2026-09-29) — 단순 메타 조회가 아니라 **실제 파일 수신 → 접견과 같은 복호화 → 저장 → Admin DB 매핑** 전체.

| 단계 | 무엇을 | 어디서 |
|---|---|---|
| ① 최신 이미지 조회 | `IMAGE_SE_CD='1'`(사진) 중 수용자별 `IMAGE_SN` 최대 1건의 `IMAGE_CMMN_FILE_ID` | 보라미 `TB_IRIM_BSIF_DS`(개발계 `ir`) |
| ② 문서ID | `CMMN_FILE_ID = IMAGE_CMMN_FILE_ID` 의 `DOC_ID` (+ 파일명 · 암호화 여부) | `TB_SMSM_CMFI_BS` |
| ③ FILEKEY | `ELEMENTID = DOC_ID` 의 `FILEKEY` | `ASYSCONTENTELEMENT` |
| ④ 수신 · 복호화 | 브로커 `POST /api/v1/xvarm/extract` → 수신 폴더 → `MediaDecryptor`(접견과 같은 RVS 키) | borami-xvarm-broker |
| ⑤ 저장 · 매핑 | `{image.output-dir}/{교정번호}/{교정번호}_{순번}.{확장자}` → `TB_SRC_INMATE_PHOTO` UPSERT | Admin DB `kcais` |

- **API** — `POST /api/v1/image/batches`(동기 실행) · `GET /api/v1/image/status` · `GET /api/v1/image/photos/{corrNo}` ·
  `POST /api/v1/image/photo-ref/sync`(선택 — `TB_SRC_INMATE_BS.PHOTO_REF` 일괄 반영)
- **재실행해도 안전하다(멱등)** — 변경 없는 건은 다시 받지 않는다(매핑된 순번·FILEKEY 가 같고 파일이 있으면 건너뜀, `force` 로 다시).
  다시 받는 건은 저장 파일을 원자적으로 덮어쓰고 매핑을 **UPSERT** 한다 — 몇 번 돌려도 키 중복 오류 없이 있으면 갱신 · 없으면 넣기
- **UPSERT** — PostgreSQL 은 `INSERT … ON CONFLICT (corr_no) DO UPDATE … WHERE 기존 순번 <= 새 순번 RETURNING (xmax = 0)` 한 문장
  (원자적 · 신규/갱신 구분 · 옛 사진이면 행이 안 돌아와 STALE). H2(로컬·테스트)는 행 잠금 + INSERT/UPDATE.
  갱신은 경로·크기·처리일시·`mod_dtm` 을 바꾸고 `reg_dtm` 은 남긴다. `PHOTO_REF` 동시 갱신은 기본 끔(`image.update-photo-ref`)
- **순서 꼬임 방지** — 수용자당 한 행인 별도 테이블 · 옛 사진(순번이 작은 것)은 덮지 않음(STALE)
- **건마다 격리** — 한 건의 수신·복호화·저장·매핑 오류(Error 포함)는 그 건만 실패로 남기고 로그를 쓴 뒤 다음 건을 계속한다
- **커넥션을 잡고 기다리지 않는다** — 조회는 문장마다 빌렸다 반납, 파일 수신·복호화·저장은 트랜잭션 밖,
  매핑만 Admin DB 에서 짧은 트랜잭션 하나(돌아오면 커밋·반납 끝). 워커가 풀보다 많아도 잠깐 줄을 설 뿐 고갈되지 않는다.
  Admin DB 풀은 스프링 빈이 아니다(보라미 DataSource 자동 구성과 섞이지 않게)
- **SIM 행 제외** — 실제 수집(`corrNoPrefix` 없음)은 6번 탭이 남겨 둔 `SIMIMG…` 행을 대상에서 뺀다
- **복호화 확인** — 결과가 이미지(매직 넘버)가 아니면 저장하지 않고 실패. 공통파일기본의 `CMMN_FILE_ENC_YN='N'` 이면 복호화하지 않는다
- **처리 이력** — 아직 로그 컬렉터(T1·T2·T4)에 남기지 않는다. 이미지용 작업 코드가 로그 컬렉터에 먼저 정해져야 한다

> 배포·전환 순서와 확인 방법: [`docs/배포_전환_가이드_0929.md`](docs/배포_전환_가이드_0929.md)
> (개발계 DDL 적용 완료 · 차트 `data_HelmChart` f60994b — Admin DB Secret · RVS 키 · 데이터 PV,
> 2026-09-30 164f8e2 — 데이터 PVC 를 옛 voice-collector 에서 독립: `pvc-unstructured-collector-data`(이 차트 소유 · 브로커가 함께 붙임),
> RVS 키 Secret `unstructured-collector-rvs-key`)

### Admin DB DDL — 배포 전에 관리자가 적용

`src/main/resources/db/admin/V1__tb_src_inmate_photo.sql` (PostgreSQL · `correction_ai.kcais`).
앱이 만들지 않는다(로그 컬렉터 V-스크립트와 같은 운영 방식). 적용 전에는 'DB 매핑' 단계가 실패로 남는다.
로컬 H2 는 `admin-schema-h2.sql` 로 자동 생성된다.

### 설정 (`image.*`)

| 키 | 기본 | 뜻 |
|---|---|---|
| `image.output-dir` (`IMAGE_OUTPUT_DIR`) | `{ROOT_DIR}/image` | 사진 저장소 — 배포에서는 **PV** 에 두어야 파드가 다시 떠도 남는다 |
| `image.workers` | 4 | 동시 처리 워커 |
| `image.max-per-run` | 2000 | 한 번에 처리할 최대 수용자 |
| `image.update-photo-ref` | false | 매핑과 함께 `PHOTO_REF` 갱신 |
| `image.admin-db.url` (`ADMIN_DB_URL`) | 로컬 H2 · dev: `admin-db-fy9tjq4tsk.service-core…/correction_ai` | Admin DB |
| `ADMIN_DB_USERNAME` · `ADMIN_DB_PASSWORD` | — | 차트 Secret 으로 준다 |
| `voice.source.schema.irim` (`VOICE_SCHEMA_IRIM`) | dev·local `ir` | `TB_IRIM_BSIF_DS` 스키마 |

### 시뮬레이터 6번 탭 — 수용자 이미지 수집 검증 (부하 · 재실행 멱등성)

별도 탭 없이 이 탭에서 부하 검증과 재실행(멱등성) 검증을 한다. SIM 사진(`SIMIMG…`, 수용자마다 사진 2장 + 사진 아닌
이미지 1장 — 순번 2가 골라져야 맞다)을 암호화해 만들고 실제 파이프라인으로 처리한다. 받기는 수집기 내장 Mock 브로커다
(SIM 원본이 수집기 저장소에만 있다).

| 입력 · 버튼 | 뜻 |
|---|---|
| 이미지 수집 건수 (1~5,000) · 워커 (1~64) · 가상 처리 지연 | 부하 조건 |
| 의도적 실패 발생률 (0~100%) · 실패 지점 | 대상 × 비율(반올림)만큼 **정확히** 골라 실패 — 수신 · 복호화 · 저장 · DB 매핑(UPSERT 뒤 커밋 전 → 롤백) 또는 고르게 |
| [초기화 후 신규 실행] | 남은 SIM 을 지우고 건수만큼 새로 만들어 처리 |
| [기존 데이터로 재실행 (멱등성)] + 범위 | 남은 SIM 을 다시 처리. **전체 덮어쓰기**: 매핑 없는 건 신규 INSERT · 있는 건 UPSERT / **실패·누락분만**: 운영 배치처럼 변경 없는 건 건너뜀. 실패 주입은 기본 끔(체크하면 적용) |
| [SIM 데이터 정리] | SIM 보라미 행 · 매핑 · 더미 · 저장 사진 삭제. **SIM 은 끝나도 남긴다**(재실행용) |

- **시나리오**: 실패율 10% 로 신규 실행 → 90% 성공 · 10% 실패 → 재실행(전체 덮어쓰기) → 실패했던 10% 는 신규 INSERT,
  성공했던 90% 는 PK 중복 없이 UPSERT → 최종 100% 매핑
- **검증** — 실행 전 매핑 스냅샷과 주입 목록으로 계산한 **기대값**(신규 · UPSERT · 건너뜀 · 실패 · 최종 매핑)과 대조,
  주입 건 매핑 미반영(롤백) · 이번 실행이 쓴 행(`last_batch_exec_id`) = 신규 + UPSERT · 최신 순번 · 원문 해시 · PK 중복 오류 0 ·
  커넥션 풀 반납. 수집기가 다시 떠도 맞다(스냅샷 · 원문은 DB 와 교정번호로 다시 계산)
- **리포트** — TPS · 단계별 시간 · 신규 INSERT / UPSERT / 건너뜀 / 실패(의도적 · 실제 오류) · 최종 매핑 % ·
  풀별 최고 사용 · 대기 스레드 최고 · 최대 대기 시간 · 종료 후 반납 완료 여부. [📸 전체 리포트 저장] 으로 ①·② 를 PNG 로 남긴다
- API — `POST /api/v1/mock/image/perf/runs` (`mode` NEW·RERUN · `rerunScope` ALL·MISSING · `failRatePct` · `failStage`) ·
  `GET`·`DELETE /api/v1/mock/image/sim`
