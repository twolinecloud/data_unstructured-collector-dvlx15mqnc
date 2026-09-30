# ══════════════════════════════════════════════════════════════════════════════
#  비정형 수집기 시뮬레이터 HTML 을 admin-fe(public/) 로 동기화하고 add · commit · push 한다.
# ══════════════════════════════════════════════════════════════════════════════
#  배포 환경에서는 admin-fe(nginx)만이 화면을 낼 수 있다. deid_demo.html 과 같은 방식으로
#  unstructured_collector_simulator.html 을 admin-fe 의 public/ 에 두고, 백엔드 빌드·배포 push 때 같이 올린다.
#
#  사용:
#    .\scripts\sync-admin-fe.ps1                 # 복사 → 변경 있으면 commit · push (admin-fe 의 현재 브랜치)
#    .\scripts\sync-admin-fe.ps1 -NoPush         # commit 까지만
#    .\scripts\sync-admin-fe.ps1 -AdminFeDir D:\src\service_admin-fe-iqgor1oiru
#
#  admin-fe 에서 함께 올리는 것: public/unstructured_collector_simulator.html · custom-nginx.conf
#  (nginx 에 /unstructured_collector_simulator.html 정적 서빙과 /voice/ → unstructured-collector 프록시가 있어야 화면이 뜨고 버튼이 산다)
# ══════════════════════════════════════════════════════════════════════════════
param(
    [string]$AdminFeDir = "C:\Projects\service_admin-fe-iqgor1oiru",
    [switch]$NoPush
)
$ErrorActionPreference = "Stop"

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$src  = Join-Path (Split-Path -Parent $here) "src\main\resources\static\unstructured_collector_simulator.html"
$dst  = Join-Path $AdminFeDir "public\unstructured_collector_simulator.html"

if (-not (Test-Path $src)) { throw "원본이 없다: $src" }
if (-not (Test-Path (Join-Path $AdminFeDir ".git"))) { throw "admin-fe 레포가 아니다: $AdminFeDir" }

Copy-Item -Path $src -Destination $dst -Force
Write-Host "[sync] 복사 — $src -> $dst"

Push-Location $AdminFeDir
try {
    $branch = (git rev-parse --abbrev-ref HEAD).Trim()
    # 2026-09-30 이름 변경 전 파일 — admin-fe 에 남아 있으면 지운다(옛 주소는 nginx 가 새 이름으로 301)
    git rm -q --ignore-unmatch -- public/voice_collector_simulator.html
    git add -- public/unstructured_collector_simulator.html custom-nginx.conf
    $staged = git diff --cached --name-only
    if (-not $staged) {
        Write-Host "[sync] admin-fe 변경 없음 (브랜치 $branch)"
        return
    }
    $stamp = Get-Date -Format "yyyy-MM-dd HH:mm"
    $msg = "sync(sim): unstructured_collector_simulator.html 동기화 ($stamp)`n`nunstructured-collector 레포 src/main/resources/static 과 동일 파일. scripts/sync-admin-fe.ps1 이 복사·커밋한다."
    git commit -q -m $msg
    Write-Host "[sync] commit — $((git log --oneline -1).Trim()) (브랜치 $branch)"
    if ($NoPush) { Write-Host "[sync] -NoPush — push 생략"; return }
    git push -q origin $branch
    Write-Host "[sync] push — origin/$branch"
}
finally {
    Pop-Location
}
