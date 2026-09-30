#!/usr/bin/env bash
# 비정형 수집기(unstructured-collector) 시뮬레이터 HTML 을 admin-fe(public/) 로 동기화하고 add · commit · push 한다. (PowerShell 판: sync-admin-fe.ps1)
#   ./scripts/sync-admin-fe.sh [admin-fe 경로] [--no-push]
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ADMIN_FE="${1:-/c/Projects/service_admin-fe-iqgor1oiru}"
NO_PUSH="${2:-}"
SRC="$HERE/../src/main/resources/static/unstructured_collector_simulator.html"
DST="$ADMIN_FE/public/unstructured_collector_simulator.html"

[ -f "$SRC" ] || { echo "원본이 없다: $SRC" >&2; exit 1; }
[ -d "$ADMIN_FE/.git" ] || { echo "admin-fe 레포가 아니다: $ADMIN_FE" >&2; exit 1; }

cp -f "$SRC" "$DST"
echo "[sync] 복사 — $SRC -> $DST"

cd "$ADMIN_FE"
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
# 2026-09-30 이름 변경 전 파일 — admin-fe 에 남아 있으면 지운다(옛 주소는 nginx 가 새 이름으로 301)
git rm -q --ignore-unmatch -- public/voice_collector_simulator.html
git add -- public/unstructured_collector_simulator.html custom-nginx.conf
if [ -z "$(git diff --cached --name-only)" ]; then
  echo "[sync] admin-fe 변경 없음 (브랜치 $BRANCH)"; exit 0
fi
git commit -q -m "sync(sim): unstructured_collector_simulator.html 동기화 ($(date '+%Y-%m-%d %H:%M'))

unstructured-collector 레포 src/main/resources/static 과 동일 파일. scripts/sync-admin-fe.sh 가 복사·커밋한다."
echo "[sync] commit — $(git log --oneline -1) (브랜치 $BRANCH)"
if [ "$NO_PUSH" = "--no-push" ]; then echo "[sync] --no-push — push 생략"; exit 0; fi
git push -q origin "$BRANCH"
echo "[sync] push — origin/$BRANCH"
