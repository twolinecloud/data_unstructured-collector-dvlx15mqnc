"""
에이전트 커넥터 bypass 수신 API(POST /api/v1/learn/transfer) — 목(Mock) 서버. data-collector 와 같은 전송 양식.

비정형 수집기(unstructured-collector)의 SEND 단계(적재/전송)가 STT 결과를 에이전트 커넥터 bypass API(비식별 없이 제논으로 중계)로
보내는 규약을 흉내 낸다(에이전트 커넥터 LearnTransferController 와 같은 수신단 — 실제 커넥터는 헤더 누락 400 · 멱등만 보고 본문을 풀지 않는다.
아래의 본문 대조 400 · 413 · 장부 · 유실 판정은 송신단을 더 엄하게 확인하려고 이 목에만 있다). 수집기를 ``AGENT_CONNECTOR_MODE=REST``, ``AGENT_CONNECTOR_BASE_URL=http://<이 서버>:8000``
으로 띄우면 실제 소켓으로 gzip + chunked 전송 · 순번/중복/유실 판정 · 장애(503) · 해제 상한(413)을 로컬에서 재현할 수 있다.
(수집기 안에도 같은 수신기가 있다 — AGENT_CONNECTOR_MODE=MOCK 기본, 또는 /api/v1/mock/agent-connector/transfer)

엔드포인트
  POST /api/v1/learn/transfer          청크 1건 — 요청 하나 = 청크 하나
       헤더  X-Run-Id(필수) · X-Seq(필수, 1부터) · X-Is-Last · X-Target-Cnt(런 전체 레코드) · X-Chunk-Cnt(이 청크 레코드) · X-Data-Type
             Content-Encoding: gzip (선택) · Transfer-Encoding: chunked
       본문  {"header":{"runId","dataTypeCd","collectDtm","setTypeCd"}, "payload":[{"managementNo","rawDataset"}, ...]}
       ?delay=<초>                응답 전에 기다린다(수집기 read-timeout 재현)
       ?status_code=503|500|400   그 상태 코드로 실패(장애 재현)
       → 200 {"code":"SUCCESS","runId","seq","duplicate","receivedChunks","receivedRecords","state","missingSeqs"}
         400 헤더 누락 · header.runId ≠ X-Run-Id · payload 건수 ≠ X-Chunk-Cnt · managementNo 없음
         413 해제 상한 초과(AGENT_CONNECTOR_MOCK_MAX_MB, 기본 512) · 503 장애 흉내(POST /fault)
       (X-Run-Id + X-Seq) 멱등 — 이미 받은 청크는 200 + duplicate=true
  GET  /api/v1/learn/transfer/ledger            최근 런 장부
  GET  /api/v1/learn/transfer/ledger/{run_id}   런 하나 — 받은 순번 · 빈 순번 · 상태(RECEIVING · GAP_SUSPECT · COMPLETE · INGEST-GAP)
  POST /api/v1/learn/transfer/sweep?idle_sec=0  유실 판정(빈 순번이 있거나 마지막이 안 온 채 조용한 런 → INGEST-GAP)
  POST /fault?mode=UP|DOWN|FAIL_FROM_SEQ&from_seq=3   장애 흉내(전송 재처리 시나리오)
  GET  /actuator/health · /health              수집기 헬스 배지(HealthProbeService)가 부른다(커넥터와 같은 /actuator/health)

받은 청크는 ./mock_received_files/{runId}/seq-{n}.json 에 (풀어서) 저장한다 — 확인용.

실행 (Python 3.10+)
  pip install -r requirements.txt
  uvicorn agent_connector_mock_server:app --host 0.0.0.0 --port 8000
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import re
import zlib
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any, Optional

from fastapi import FastAPI, Query, Request
from fastapi.responses import JSONResponse

# ── 설정 ────────────────────────────────────────────────────────────────
RECEIVE_DIR = Path(os.environ.get("AGENT_CONNECTOR_MOCK_DIR", "./mock_received_files")).resolve()
MAX_DECOMPRESSED = int(os.environ.get("AGENT_CONNECTOR_MOCK_MAX_MB", "512")) * 1024 * 1024
GAP_TIMEOUT_SEC = int(os.environ.get("AGENT_CONNECTOR_MOCK_GAP_TIMEOUT_SEC", "60"))
MAX_DELAY_SEC = 600.0

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)-5s [agent-connector-mock] %(message)s")
log = logging.getLogger("agent-connector-mock")

app = FastAPI(
    title="에이전트 커넥터 bypass 수신 Mock 서버",
    version="2.0.0",
    description="비정형 수집기 SEND — data-collector 와 같은 전송 양식(gzip · chunked · 유실검증 헤더 6종 · 2xx) 수신 · 장부 · 장애/지연 재현",
)

# 런별 장부 — 메모리(재기동하면 비워진다. 중복 수신은 멱등이라 무해)
_RUNS: dict[str, dict[str, Any]] = {}
_FAULT: dict[str, Any] = {"mode": "UP", "from_seq": 0}


def _now() -> datetime:
    return datetime.now().replace(microsecond=0)


def _safe(name: str) -> str:
    """경로 조작을 막는다 — 한 조각만 남긴다."""
    return re.sub(r"[^0-9A-Za-z가-힣._-]", "_", os.path.basename((name or "").replace("\\", "/"))).lstrip(".")[:120] or "run"


def _error(status: int, code: str, message: str) -> JSONResponse:
    return JSONResponse(status_code=status, content={"code": code, "message": message, "received_at": _now().isoformat()})


def _run(run_id: str) -> dict[str, Any]:
    return _RUNS.setdefault(run_id, {"runId": run_id, "seqs": {}, "records": 0, "bytes": 0, "rawBytes": 0, "targetCnt": -1,
                                     "lastSeq": -1, "duplicates": 0, "state": "RECEIVING", "rejected": set(),
                                     "firstSeenAt": _now(), "lastSeenAt": _now()})


def _missing(r: dict[str, Any]) -> list[int]:
    up_to = r["lastSeq"] if r["lastSeq"] > 0 else (max(r["seqs"]) if r["seqs"] else 0)
    return [i for i in range(1, up_to + 1) if i not in r["seqs"]]


def _refresh(r: dict[str, Any]) -> None:
    full = r["lastSeq"] > 0 and not _missing(r)
    if full and (r["targetCnt"] < 0 or r["records"] == r["targetCnt"]):
        r["state"] = "COMPLETE"
    elif full:
        r["state"] = "COUNT_MISMATCH"
    elif _missing(r):
        r["state"] = "GAP_SUSPECT"
    else:
        r["state"] = "RECEIVING"


def _view(r: dict[str, Any]) -> dict[str, Any]:
    return {"runId": r["runId"], "state": r["state"], "complete": r["state"] == "COMPLETE",
            "receivedSeqs": sorted(r["seqs"]), "missingSeqs": _missing(r), "rejectedSeqs": sorted(r["rejected"]),
            "lastSeq": r["lastSeq"], "records": r["records"], "targetCnt": r["targetCnt"], "bytes": r["bytes"],
            "rawBytes": r["rawBytes"], "duplicates": r["duplicates"], "firstSeenAt": r["firstSeenAt"].isoformat(),
            "lastSeenAt": r["lastSeenAt"].isoformat()}


def _int(v: Optional[str], default: int) -> int:
    try:
        return int(v) if v not in (None, "") else default
    except ValueError:
        return default


@app.get("/health")
@app.get("/actuator/health")
async def health() -> dict[str, Any]:
    return {"status": "UP", "service": "agent-connector-mock", "protocol": "data-collector transfer (gzip · chunked · X-Run-Id/X-Seq)",
            "receive_dir": str(RECEIVE_DIR), "fault": _FAULT, "checked_at": _now().isoformat()}


@app.post("/api/v1/learn/transfer")
async def transfer(
    request: Request,
    delay: float = Query(0.0, ge=0.0, le=MAX_DELAY_SEC, description="응답 지연(초) — 수집기 read-timeout 재현"),
    status_code: Optional[int] = Query(None, ge=400, le=599, description="실패 응답 상태 코드(예: 503 · 500 · 400)"),
) -> JSONResponse:
    h = request.headers
    run_id = (h.get("x-run-id") or "").strip()
    seq_text = (h.get("x-seq") or "").strip()
    if not run_id or not seq_text:
        return _error(400, "MISSING_HEADER", "필수 헤더 누락 — X-Run-Id · X-Seq")
    seq = _int(seq_text, -1)
    if seq < 1:
        return _error(400, "BAD_HEADER", f"X-Seq 는 1부터: {seq_text}")
    target = _int(h.get("x-target-cnt"), -1)
    chunk_cnt = _int(h.get("x-chunk-cnt"), -1)
    last = (h.get("x-is-last") or "").strip().lower() == "true"

    r = _run(run_id)
    if _FAULT["mode"] == "DOWN" or (_FAULT["mode"] == "FAIL_FROM_SEQ" and seq >= _FAULT["from_seq"]):
        r["rejected"].add(seq)
        r["lastSeenAt"] = _now()
        log.warning("장애 흉내 503 — runId=%s seq=%d (%s)", run_id, seq, _FAULT)
        return _error(503, "UNAVAILABLE", f"(MOCK) 에이전트 커넥터 수신 장애 — {_FAULT['mode']} · seq {seq}")
    if status_code:
        return _error(status_code, "FORCED", f"요청한 실패 응답 {status_code}")
    if seq in r["seqs"]:
        async for _ in request.stream():
            pass
        r["duplicates"] += 1
        r["lastSeenAt"] = _now()
        return JSONResponse({"code": "SUCCESS", "runId": run_id, "seq": seq, "duplicate": True,
                             "receivedChunks": len(r["seqs"]), "receivedRecords": r["records"], "state": r["state"]})

    # 본문 — 흘려 받으며 푼다. 상한을 넘으면 413(끝까지 풀지 않는다)
    gz = "gzip" in (h.get("content-encoding") or "").lower()
    inflater = zlib.decompressobj(16 + zlib.MAX_WBITS) if gz else None
    wire, plain = 0, bytearray()
    async for part in request.stream():
        wire += len(part)
        data = inflater.decompress(part, MAX_DECOMPRESSED + 1 - len(plain)) if inflater else part
        plain.extend(data)
        if len(plain) > MAX_DECOMPRESSED:
            log.warning("해제 상한 초과 413 — runId=%s seq=%d", run_id, seq)
            return _error(413, "PAYLOAD_TOO_LARGE", f"해제 상한 {MAX_DECOMPRESSED // 1048576}MB 초과 — 청크를 더 잘게(권장 50MB)")
    try:
        body = json.loads(plain.decode("utf-8"))
        payload = body["payload"]
        if not isinstance(payload, list):
            raise ValueError("payload 가 배열이 아니다")
    except (ValueError, KeyError, UnicodeDecodeError) as e:
        return _error(400, "BAD_BODY", f"본문을 읽지 못함 — {e}")
    header = body.get("header") or {}
    if header.get("runId") and header["runId"] != run_id:
        return _error(400, "RUN_ID_MISMATCH", f"header.runId({header['runId']}) ≠ X-Run-Id({run_id})")
    if chunk_cnt >= 0 and chunk_cnt != len(payload):
        return _error(400, "CHUNK_CNT_MISMATCH", f"X-Chunk-Cnt {chunk_cnt} ≠ payload {len(payload)}건")
    if any(not isinstance(p, dict) or not p.get("managementNo") for p in payload):
        return _error(400, "BAD_RECORD", "managementNo 없는 레코드가 있다")

    if delay:
        await asyncio.sleep(delay)
    r["seqs"][seq] = len(payload)
    r["records"] += len(payload)
    r["bytes"] += wire
    r["rawBytes"] += len(plain)
    r["rejected"].discard(seq)
    if target >= 0:
        r["targetCnt"] = target
    if last:
        r["lastSeq"] = seq
    r["lastSeenAt"] = _now()
    _refresh(r)
    out = RECEIVE_DIR / _safe(run_id)
    out.mkdir(parents=True, exist_ok=True)
    (out / f"seq-{seq}.json").write_bytes(bytes(plain))
    log.info("청크 수신 runId=%s seq=%d last=%s 레코드 %d · 압축 %dB → 해제 %dB · %s", run_id, seq, last, len(payload), wire,
             len(plain), r["state"])
    return JSONResponse({"code": "SUCCESS", "runId": run_id, "seq": seq, "duplicate": False, "receivedChunks": len(r["seqs"]),
                         "receivedRecords": r["records"], "last": last, "records": len(payload), "bytes": wire,
                         "rawBytes": len(plain), "state": r["state"], "missingSeqs": _missing(r)})


@app.get("/api/v1/learn/transfer/ledger")
async def ledgers(limit: int = Query(20, ge=1, le=500)) -> list[dict[str, Any]]:
    runs = sorted(_RUNS.values(), key=lambda x: x["lastSeenAt"], reverse=True)[:limit]
    return [_view(r) for r in runs]


@app.get("/api/v1/learn/transfer/ledger/{run_id}")
async def ledger(run_id: str) -> JSONResponse:
    r = _RUNS.get(run_id)
    return JSONResponse(_view(r)) if r else _error(404, "NOT_FOUND", f"장부 없음 — {run_id}")


@app.post("/api/v1/learn/transfer/sweep")
async def sweep(idle_sec: int = Query(GAP_TIMEOUT_SEC, ge=0)) -> dict[str, Any]:
    cut = _now() - timedelta(seconds=idle_sec)
    swept = []
    for r in _RUNS.values():
        incomplete = _missing(r) or (r["lastSeq"] < 0 and r["seqs"])
        if r["state"] not in ("COMPLETE", "INGEST-GAP") and incomplete and r["lastSeenAt"] <= cut:
            r["state"] = "INGEST-GAP"
            swept.append(r["runId"])
    return {"idleSec": idle_sec, "sweptCount": len(swept), "swept": swept}


@app.post("/fault")
async def fault(mode: str = Query("UP", pattern="^(UP|DOWN|FAIL_FROM_SEQ)$"), from_seq: int = Query(3, ge=1)) -> dict[str, Any]:
    _FAULT.update({"mode": mode, "from_seq": from_seq if mode == "FAIL_FROM_SEQ" else 0})
    log.warning("장애 설정 — %s", _FAULT)
    return _FAULT
