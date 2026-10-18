# docx 한 벌로 hwp · hwpx · pdf 를 만든다.
# 같은 내용·같은 정답으로 포맷만 바꿔야 "포맷 탓" 과 "내용 탓" 이 섞이지 않는다.
param(
  [Parameter(Mandatory = $true)][string]$Dir,
  [string]$Base = "deid_probe"
)

$src = Join-Path $Dir "$Base.docx"
if (-not (Test-Path $src)) { throw "원본이 없다: $src" }

$h = New-Object -ComObject HWPFrame.HwpObject
try {
    # 보안 모듈이 등록돼 있지 않으면 파일 열기/저장에서 승인 대화상자가 뜬다.
    #   비대화 환경에서는 그대로 멈추므로 먼저 시도해 둔다(없으면 그냥 넘어간다).
    try { $h.RegisterModule("FilePathCheckDLL", "FilePathCheckerModule") | Out-Null } catch { }
    try { $h.XHwpWindows.Item(0).Visible = $false } catch { }

    $null = $h.Open($src, "", "")

    foreach ($t in @(@("hwp", "HWP"), @("hwpx", "HWPX"), @("pdf", "PDF"))) {
        $ext = $t[0]; $fmt = $t[1]
        $dst = Join-Path $Dir "$Base.$ext"
        if (Test-Path $dst) { Remove-Item $dst -Force }
        try {
            $null = $h.SaveAs($dst, $fmt, "")
            if (Test-Path $dst) {
                "{0} OK {1} bytes" -f $ext, (Get-Item $dst).Length
            } else {
                "$ext 실패 — 파일이 생기지 않음"
            }
        } catch {
            "$ext 실패 — " + $_.Exception.Message
        }
    }
} finally {
    try { $h.Clear(1) | Out-Null } catch { }
    try { $h.Quit() } catch { }
    try { [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($h) } catch { }
}
