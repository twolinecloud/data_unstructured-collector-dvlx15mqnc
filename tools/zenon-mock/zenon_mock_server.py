"""
제논(Zenon) AI 수신 REST API — 목(Mock) 서버.

비정형 수집기(unstructured-collector)의 SEND 단계(적재/전송)가 STT 결과를 온프레미스 제논으로 넘기는
규약을 흉내 낸다. 수집기를 ``ZENON_MODE=REST``, ``ZENON_BASE_URL=http://<이 서버>:8000`` 으로 띄우면
실제 HTTP multipart 전송 · 실패(4xx/5xx) · 지연(타임아웃)을 로컬에서 재현할 수 있다.

엔드포인트
  POST /api/v1/zenon/receive   multipart/form-data
       - file      : 전송 파일(음성 전사 JSON · 이미지 등)
       - metadata  : JSON 문자열 {"exec_id", "inmate_no", "type": "VOICE"|"IMAGE", "file_name", ...}
       - ?delay=<초>        응답 전에 기다린다(수집기 read-timeout 재현)
       - ?status_code=500|400  그 상태 코드로 실패 응답(장애 재현)
       → 200 {"code":"SUCCESS","exec_id","received_at","file_name","file_size_bytes"}
  GET  /health                 수집기 헬스 배지(HealthProbeService)가 부른다
  GET  /api/v1/zenon/received  받아 둔 파일 목록(확인용)

받은 파일은 ./mock_received_files/{exec_id}/{file_name} 에 저장하고, 옆에 {file_name}.metadata.json 을 남긴다.

실행 (Python 3.10+)
  pip install -r requirements.txt
  uvicorn zenon_mock_server:app --host 0.0.0.0 --port 8000
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import re
from datetime import datetime
from pathlib import Path
from typing import Any, Optional

from fastapi import FastAPI, File, Form, Query, Request, UploadFile
from fastapi.responses import JSONResponse

# ── 설정 ────────────────────────────────────────────────────────────────
RECEIVE_DIR = Path(os.environ.get("ZENON_MOCK_DIR", "./mock_received_files")).resolve()
ALLOWED_TYPES = {"VOICE", "IMAGE"}
MAX_DELAY_SEC = 600.0
# 로그에 값을 찍지 않을 헤더 — 토큰·쿠키가 콘솔에 남지 않게
MASKED_HEADERS = {"authorization", "cookie", "proxy-authorization", "x-api-key"}

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)-5s [zenon-mock] %(message)s")
log = logging.getLogger("zenon-mock")

app = FastAPI(
    title="Zenon AI 수신 Mock 서버",
    version="1.0.0",
    description="비정형 수집기 SEND(적재/전송) 단계용 제논 수신 API 목 — multipart(file + metadata JSON) 수신 · 저장 · 장애/지연 재현",
)


def _now() -> str:
    return datetime.now().replace(microsecond=0).isoformat()


def _safe(name: str, fallback: str) -> str:
    """경로 조작(../, 절대경로, 드라이브 문자)을 막는다 — 파일명 한 조각만 남긴다."""
    base = os.path.basename((name or "").replace("\\", "/")).strip()
    base = re.sub(r"[^0-9A-Za-z가-힣._-]", "_", base)
    base = base.lstrip(".")
    return base[:200] or fallback


def _headers_for_log(request: Request) -> dict[str, str]:
    return {k: ("***" if k.lower() in MASKED_HEADERS else v) for k, v in request.headers.items()}


def _error(status: int, code: str, message: str, exec_id: Optional[str] = None) -> JSONResponse:
    body: dict[str, Any] = {"code": code, "message": message, "received_at": _now()}
    if exec_id:
        body["exec_id"] = exec_id
    return JSONResponse(status_code=status, content=body)


@app.get("/health")
async def health() -> dict[str, Any]:
    return {"status": "UP", "service": "zenon-mock", "receive_dir": str(RECEIVE_DIR), "checked_at": _now()}


@app.post("/api/v1/zenon/receive")
async def receive(
    request: Request,
    file: UploadFile = File(..., description="전송 파일"),
    metadata: str = Form(..., description='JSON 문자열 — {"exec_id","inmate_no","type":"VOICE|IMAGE","file_name"}'),
    delay: float = Query(0.0, ge=0.0, le=MAX_DELAY_SEC, description="응답 지연(초) — 수집기 read-timeout 재현"),
    status_code: Optional[int] = Query(None, ge=400, le=599, description="실패 응답 상태 코드(예: 500 · 400)"),
) -> JSONResponse:
    content = await file.read()
    size = len(content)

    # ── 메타데이터 ──
    try:
        meta = json.loads(metadata)
        if not isinstance(meta, dict):
            raise ValueError("JSON 객체가 아닙니다")
    except (ValueError, json.JSONDecodeError) as e:
        log.warning("메타데이터 파싱 실패 — %s · file=%s (%d bytes)", e, file.filename, size)
        return _error(400, "INVALID_METADATA", f"metadata 가 올바른 JSON 객체가 아닙니다: {e}")

    exec_id = str(meta.get("exec_id") or "").strip()
    doc_type = str(meta.get("type") or "").strip().upper()
    file_name = _safe(str(meta.get("file_name") or file.filename or ""), "unnamed.bin")

    log.info("수신 ─ type=%s exec_id=%s inmate_no=%s file=%s size=%d bytes content-type=%s",
             doc_type or "(없음)", exec_id or "(없음)", meta.get("inmate_no"), file_name, size, file.content_type)
    log.info("  headers=%s", _headers_for_log(request))
    log.info("  metadata=%s", json.dumps(meta, ensure_ascii=False))
    if delay or status_code:
        log.info("  시뮬레이션 — delay=%ss status_code=%s", delay, status_code)

    # ── 지연 · 장애 재현 ──
    if delay > 0:
        await asyncio.sleep(delay)
    if status_code is not None:
        log.warning("  → 의도적 실패 응답 HTTP %d (exec_id=%s)", status_code, exec_id)
        return _error(status_code, "ERROR", f"Mock 장애 재현 — status_code={status_code}", exec_id)

    # ── 규약 검증 ──
    if not exec_id:
        return _error(400, "INVALID_METADATA", "metadata.exec_id 가 비어 있습니다")
    if doc_type not in ALLOWED_TYPES:
        return _error(400, "INVALID_METADATA", f"metadata.type 은 VOICE 또는 IMAGE 여야 합니다: {doc_type or '(없음)'}", exec_id)

    # ── 저장 ──
    target_dir = RECEIVE_DIR / _safe(exec_id, "no-exec-id")
    target_dir.mkdir(parents=True, exist_ok=True)
    target = target_dir / file_name
    target.write_bytes(content)
    received_at = _now()
    (target_dir / (file_name + ".metadata.json")).write_text(
        json.dumps({"received_at": received_at, "content_type": file.content_type, "metadata": meta},
                   ensure_ascii=False, indent=2),
        encoding="utf-8")
    log.info("  → 저장 %s (%d bytes)", target, size)

    return JSONResponse(status_code=200, content={
        "code": "SUCCESS",
        "exec_id": exec_id,
        "received_at": received_at,
        "file_name": file_name,
        "file_size_bytes": size,
    })


@app.get("/api/v1/zenon/received")
async def received(exec_id: Optional[str] = Query(None, description="이 실행 ID 의 파일만")) -> dict[str, Any]:
    """받아 둔 파일 목록 — 수집기 [제논 전송 확인] 결과와 대조할 때 쓴다."""
    rows: list[dict[str, Any]] = []
    if RECEIVE_DIR.is_dir():
        dirs = [RECEIVE_DIR / _safe(exec_id, "")] if exec_id else sorted(p for p in RECEIVE_DIR.iterdir() if p.is_dir())
        for d in dirs:
            if not d.is_dir():
                continue
            for f in sorted(d.iterdir()):
                if f.is_file() and not f.name.endswith(".metadata.json"):
                    rows.append({"exec_id": d.name, "file_name": f.name, "file_size_bytes": f.stat().st_size,
                                 "saved_at": datetime.fromtimestamp(f.stat().st_mtime).replace(microsecond=0).isoformat()})
    return {"receive_dir": str(RECEIVE_DIR), "total": len(rows), "files": rows}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("zenon_mock_server:app", host=os.environ.get("ZENON_MOCK_HOST", "0.0.0.0"),
                port=int(os.environ.get("ZENON_MOCK_PORT", "8000")))
