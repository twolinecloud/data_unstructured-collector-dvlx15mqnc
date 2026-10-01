# 제논(Zenon) AI 수신 REST API — Mock 서버

비정형 수집기의 **SEND(적재/전송)** 단계가 STT 결과를 온프레미스 제논으로 넘기는 규약을 흉내 내는 FastAPI 서버입니다.
2026-10-01 3단계 복원(`[수집 COLLECT] → [정제/분석 ANALYZE] → [적재/전송 SEND(제논)]`)으로 클라우드 전송·비식별화(커넥터)가 빠지고,
결과는 PV(`/k8s/unstructured_collector/xenon`)에 남기지 않고 제논으로 보냅니다.

| 파일 | 내용 |
|---|---|
| `zenon_mock_server.py` | 목 서버 본체 |
| `requirements.txt` | `fastapi` · `uvicorn` · `python-multipart` |
| `mock_received_files/` | 받은 파일 저장 위치(실행 시 생성 · Git 제외) |

## 1. 설치 · 실행 (Python 3.10+)

```bash
cd tools/zenon-mock
python -m venv .venv && source .venv/bin/activate        # Windows: .venv\Scripts\activate
pip install -r requirements.txt
uvicorn zenon_mock_server:app --host 0.0.0.0 --port 8000
```

- `python zenon_mock_server.py` 로도 뜹니다(`ZENON_MOCK_PORT` · `ZENON_MOCK_HOST` 로 바꿈).
- 저장 폴더는 `ZENON_MOCK_DIR`(기본 `./mock_received_files`).
- Swagger: <http://localhost:8000/docs>

## 2. API

### `POST /api/v1/zenon/receive` — multipart/form-data

| 파트 | 형식 | 설명 |
|---|---|---|
| `file` | 파일 | 전송 파일(음성 = 전사 JSON, 이미지 = 사진) |
| `metadata` | JSON 문자열 | `exec_id`, `inmate_no`, `type`(`VOICE`·`IMAGE`), `file_name` (+ 수집기가 붙이는 `kind`, `idempotency_key`, `src_file_name`, `char_count`, `engine`) |

| 쿼리 | 설명 |
|---|---|
| `delay=<초>` | 응답 전에 기다립니다(0~600). 수집기 `zenon.read-timeout-ms`(기본 30초)보다 길게 주면 **타임아웃**을 재현합니다 |
| `status_code=500` · `status_code=400` | 그 상태 코드로 실패 응답(`{"code":"ERROR",...}`) — 수집기는 SEND 실패로 처리하고 전사를 보존합니다 |

성공 응답(200):

```json
{"code":"SUCCESS","exec_id":"20261001TST001","received_at":"2026-10-01T17:20:05","file_name":"MEET_0001.json","file_size_bytes":1834}
```

받은 파일은 `mock_received_files/{exec_id}/{file_name}` 에 저장되고, 옆에 `{file_name}.metadata.json`(수신 시각 · 메타데이터)이 남습니다.
콘솔에는 헤더(인증·쿠키 값은 `***`) · type · exec_id · 크기가 찍힙니다.

### 그 밖

- `GET /health` — 수집기 상단 헬스 배지(제논 전송)가 REST 모드에서 부릅니다.
- `GET /api/v1/zenon/received?exec_id=...` — 받아 둔 파일 목록(수집기 [제논 전송 확인] 과 대조).

## 3. cURL 예시

```bash
# 정상 수신
echo '{"text":"테스트 전사"}' > /tmp/sample.json
curl -s -X POST 'http://localhost:8000/api/v1/zenon/receive' \
  -F 'file=@/tmp/sample.json;type=application/json' \
  -F 'metadata={"exec_id":"20261001TST001","inmate_no":"2024000123","type":"VOICE","file_name":"MEET_0001.json"}'

# 장애 재현 — HTTP 500
curl -s -X POST 'http://localhost:8000/api/v1/zenon/receive?status_code=500' \
  -F 'file=@/tmp/sample.json' \
  -F 'metadata={"exec_id":"20261001TST001","inmate_no":"2024000123","type":"VOICE","file_name":"MEET_0001.json"}'

# 지연 재현 — 35초 뒤 응답(수집기 기본 read-timeout 30초 → 타임아웃)
curl -s -X POST 'http://localhost:8000/api/v1/zenon/receive?delay=35' \
  -F 'file=@/tmp/sample.json' \
  -F 'metadata={"exec_id":"20261001TST001","inmate_no":"2024000123","type":"VOICE","file_name":"MEET_0001.json"}'

# 헬스 · 받은 목록
curl -s http://localhost:8000/health
curl -s 'http://localhost:8000/api/v1/zenon/received?exec_id=20261001TST001'
```

## 4. 수집기와 붙이기

수집기 기본값은 `ZENON_MODE=MOCK`(수집기 안에서 수신증만 만들고 네트워크를 타지 않음)입니다. 이 목 서버로 실제 HTTP 전송을 보려면:

```bash
ZENON_MODE=REST ZENON_BASE_URL=http://localhost:8000 java -jar target/unstructured-collector-*.jar --server.port=18095
```

| 환경 변수 | 기본값 | 설명 |
|---|---|---|
| `ZENON_MODE` | `MOCK` | `MOCK` · `REST` |
| `ZENON_BASE_URL` | (빈 값) | REST 모드의 제논 주소 |
| `ZENON_RECEIVE_PATH` | `/api/v1/zenon/receive` | 수신 경로 |
| `ZENON_CONNECT_TIMEOUT_MS` · `ZENON_READ_TIMEOUT_MS` | `3000` · `30000` | 연결 · 응답 타임아웃 |
| `ZENON_KEEP_RECEIPTS` | `500` | 수집기 메모리에 남길 수신증 수(시뮬레이터 [제논 전송 확인] · 검증 패널) |

장애 재처리 확인: 시뮬레이터 3번 탭 **3. 전송 장애**(수집기 안에서 SEND 실패 주입) 또는 이 서버의 `status_code=500` 으로 실패시키면
전사가 `{ROOT}/stt_temp/{execId}/` 에 보존되고, `재처리(FROM_SEND)` 가 STT 없이 보존된 전사로 다시 보낸 뒤 보존물을 지웁니다.
