# 에이전트 커넥터 bypass 수신 API — Mock 서버 (data-collector 전송 양식)

비정형 수집기의 **SEND(적재/전송)** 단계가 STT 결과를 **에이전트 커넥터 bypass API**(`POST /api/v1/learn/transfer` —
비식별 없이 받은 그대로 제논으로 중계)로 보내는 규약을 흉내 내는 FastAPI 서버입니다.
제논으로 가는 길은 늘 에이전트 커넥터를 거칩니다(2026-10-06 PL 확인) — data-collector 도 개발계에서 같은 주소 · 같은 양식으로 보냅니다.
실제 커넥터(`LearnTransferController`)는 헤더 누락 400 · 멱등(200 duplicate)만 보고 본문을 풀지 않습니다 — 아래의 본문 대조 400 · 413 ·
장부 · 유실 판정은 송신단을 더 엄하게 확인하려고 이 목에만 있습니다.

> 수집기 안에도 같은 수신기가 있습니다 — `AGENT_CONNECTOR_MODE=MOCK`(개발계 기본)이면 네트워크 없이 내장 수신기를 부르고,
> dev · local 에서는 `POST /api/v1/mock/agent-connector/transfer` 로도 열려 있습니다(시뮬레이터 7번 탭 '에이전트 커넥터 전송 시뮬레이션').
> 이 서버는 수집기 밖에서 **실제 소켓**으로 받아 보고 싶을 때 씁니다.

| 파일 | 내용 |
|---|---|
| `agent_connector_mock_server.py` | 목 서버 본체 |
| `requirements.txt` | `fastapi` · `uvicorn` |
| `mock_received_files/` | 받은 청크(풀어서) 저장 위치 — `{runId}/seq-{n}.json`(실행 시 생성 · Git 제외) |

## 1. 설치 · 실행 (Python 3.10+)

```bash
cd tools/agent-connector-mock
python -m venv .venv && source .venv/bin/activate        # Windows: .venv\Scripts\activate
pip install -r requirements.txt
uvicorn agent_connector_mock_server:app --host 0.0.0.0 --port 8000
```

| 환경 변수 | 기본값 | 설명 |
|---|---|---|
| `AGENT_CONNECTOR_MOCK_DIR` | `./mock_received_files` | 받은 청크 저장 폴더 |
| `AGENT_CONNECTOR_MOCK_MAX_MB` | `512` | 해제 상한 — 넘으면 413(압축 폭탄 방어) |
| `AGENT_CONNECTOR_MOCK_GAP_TIMEOUT_SEC` | `60` | 유실 판정 스윕의 기본 대기 |

Swagger: <http://localhost:8000/docs>

## 2. 전송 양식 — 요청 하나 = 청크 하나

```
POST /api/v1/learn/transfer
Content-Type: application/json;charset=UTF-8
Content-Encoding: gzip
Transfer-Encoding: chunked
X-Run-Id: 20261005UNS001        전송 런 ID = 수집 실행 ID(재처리 이어달리기면 원배치 ID)
X-Data-Type: UNSTRUCTURED
X-Target-Cnt: 6                 이 런의 전체 레코드 수
X-Chunk-Cnt: 2                  이 청크의 레코드 수
X-Seq: 1                        순번(1부터 오름차순)
X-Is-Last: false                마지막 청크만 true

{"header":{"runId":"20261005UNS001","dataTypeCd":"UNSTRUCTURED","collectDtm":"2026-10-05T10:00:00+09:00","setTypeCd":"VOICE"},
 "payload":[{"managementNo":"(교정번호)","rawDataset":{"recFileId":"…","kind":"MEET","inmatePid":"…","transcript":{…}}}, …]}
```

| 응답 | 뜻 |
|---|---|
| 200 `{"code":"SUCCESS","runId","seq","duplicate":false,"receivedChunks","receivedRecords","state","missingSeqs"}` | 받음 |
| 200 `{"duplicate":true}` | 이미 받은 (X-Run-Id + X-Seq) — 재전송 무해(멱등) |
| 400 | 헤더 누락(X-Run-Id · X-Seq) · `header.runId` ≠ X-Run-Id · payload 건수 ≠ X-Chunk-Cnt · managementNo 없음 |
| 413 | 해제 상한 초과 — 끝까지 풀지 않고 끊는다 |
| 503 | 장애 흉내(`POST /fault`) |

| 쿼리 | 설명 |
|---|---|
| `delay=<초>` | 응답 전에 기다립니다(0~600). 수집기 `agent-connector.read-timeout-ms`(기본 60초)보다 길게 주면 **타임아웃** |
| `status_code=503` | 그 상태 코드로 실패 — 수집기는 그 청크에서 멈추고(뒤 청크 미전송) 그 건들을 SEND 실패로 남깁니다 |

### 장부 · 장애

- `GET /api/v1/learn/transfer/ledger` · `GET /api/v1/learn/transfer/ledger/{runId}` — 받은 순번 · 빈 순번 · 상태
  (`RECEIVING` · `GAP_SUSPECT` · `COMPLETE` · `COUNT_MISMATCH` · `INGEST-GAP`)
- `POST /api/v1/learn/transfer/sweep?idle_sec=0` — 유실 판정(빈 순번이 있거나 마지막 청크가 오지 않은 채 조용한 런 → `INGEST-GAP`)
- `POST /fault?mode=DOWN` — 전부 503 · `POST /fault?mode=FAIL_FROM_SEQ&from_seq=3` — 3번 청크부터 503 · `POST /fault?mode=UP` — 정상화
- `GET /actuator/health`(또는 `/health`) — 수집기 상단 헬스 배지(에이전트 커넥터 전송)가 REST 모드에서 부릅니다(실제 커넥터와 같은 경로)

## 3. 수집기와 붙이기

```bash
AGENT_CONNECTOR_MODE=REST AGENT_CONNECTOR_BASE_URL=http://localhost:8000 java -jar target/unstructured-collector-dvlx15mqnc.jar --server.port=18095
```

| 환경 변수 | 기본값 | 설명 |
|---|---|---|
| `AGENT_CONNECTOR_MODE` | `MOCK` | `MOCK`(내장 수신기) · `REST` |
| `AGENT_CONNECTOR_BASE_URL` | (빈 값 · dev 프로필은 `http://agent-connector-dp8qbi7xqh:8080`) | REST 모드의 에이전트 커넥터 주소(경로 접두가 있으면 포함) |
| `AGENT_CONNECTOR_TRANSFER_PATH` | `/api/v1/learn/transfer` | 수신 경로(data-collector `transfer-path` 와 같은 기본값) |
| `AGENT_CONNECTOR_GZIP` | `true` | 본문 gzip |
| `AGENT_CONNECTOR_CHUNK_RECORDS` · `AGENT_CONNECTOR_MAX_PAYLOAD_BYTES` | `50` · `52428800` | 청크 레코드 수 · 청크 직렬화 상한(50MB) |
| `AGENT_CONNECTOR_SET_TYPE_CD` · `AGENT_CONNECTOR_DATA_TYPE_CD` | `VOICE` · `UNSTRUCTURED` | `header.setTypeCd` · `X-Data-Type` |
| `AGENT_CONNECTOR_CONNECT_TIMEOUT_MS` · `AGENT_CONNECTOR_READ_TIMEOUT_MS` | `3000` · `60000` | 연결 · 응답 타임아웃 |

**전송 재처리** — 이 서버에 `POST /fault?mode=FAIL_FROM_SEQ&from_seq=3` 를 걸고 배치를 돌리면 1·2번 청크만 받아들여지고(PARTIAL),
실패한 건의 전사가 `{ROOT}/stt_temp/{execId}/` 에 남습니다. `POST /fault?mode=UP` 뒤 긴급 재처리(단계 SEND)를 하면 수집기가
**원배치 런(X-Run-Id = 원 실행 ID)을 3번 청크부터 이어** 보내고 마지막 청크로 마감합니다 — `ledger/{runId}` 가 `COMPLETE`.
어디까지 받아들여졌는지는 수집기가 `{ROOT}/transfer/transfer_runs.json` 에 남겨 재기동 뒤에도 이어 갑니다.

**키 표식 장애(SF)** — 더미 키 표식(`…-SF-…`)은 이제 수집기가 **보내기 전에** 그 건만 한 번 거부합니다(청크는 멈추지 않음).
이 서버는 표식을 보지 않습니다.
