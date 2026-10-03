# verify-all.ps1 的**进度窗口**：每项验证各自一条进度条，完成后自动关闭。
#
# 用户 2026-10 的要求：
#   - 窗口**小一点**
#   - **每一个测试项有单独的进度条**
#   - **不要刷屏**（只在状态变化时重画）
#
# 实现用"状态文件轮询"：父进程只写、本窗口只读 —— 最小耦合，不依赖管道权限，
# 父进程意外退出时本窗口也能自己超时关闭。

param(
    [Parameter(Mandatory = $true)][string]$StatusFile
)

$ErrorActionPreference = 'SilentlyContinue'

# 小窗口：够放下 7 行条目 + 标题即可（太大会挡住画面）
try {
    $raw = $host.UI.RawUI
    $raw.WindowTitle = 'sablestopnow verify'
    $size = $raw.WindowSize
    $size.Width = 58
    $size.Height = 13
    $raw.WindowSize = $size
    $buf = $raw.BufferSize
    $buf.Width = 58
    $buf.Height = 13
    $raw.BufferSize = $buf
} catch { }

# 状态文件：STEP|<index>|<total>|<name>   或   DONE|<PASS|FAIL>|<summary>
$done = $false
$lastKey = ''
$tick = 0
$steps = @{}
$current = 0
$total = 7
$barWidth = 14
$result = ''

while (-not $done) {
    $tick++
    if ($tick -gt 12000) { break }
    if (-not (Test-Path $StatusFile)) { Start-Sleep -Milliseconds 250; continue }
    $text = (Get-Content $StatusFile -Raw).Trim()
    if (-not $text) { Start-Sleep -Milliseconds 250; continue }
    $parts = $text -split '\|'
    if ($parts.Count -lt 2) { Start-Sleep -Milliseconds 250; continue }

    if ($parts[0] -eq 'STEP' -and $parts.Count -ge 4) {
        $current = [int]$parts[1]
        $total = [int]$parts[2]
        $steps[$current] = $parts[3]
    } elseif ($parts[0] -eq 'DONE') {
        $done = $true
        $result = $parts[1]
        if ($parts.Count -ge 3) { $result = $result + '|' + $parts[2] }
        $current = $total + 1
    } else {
        Start-Sleep -Milliseconds 250
        continue
    }

    # 只在内容真的变了时重画 —— 这就是"不刷屏"
    $key = "$current/" + ($steps.Keys | Sort-Object | ForEach-Object { $steps[$_] })
    if ($key -eq $lastKey -and -not $done) { Start-Sleep -Milliseconds 200; continue }
    $lastKey = $key

    Clear-Host
    Write-Host ''
    Write-Host '  sablestopnow  verify' -ForegroundColor Cyan

    for ($i = 1; $i -le $total; $i++) {
        $name = $steps[$i]
        if (-not $name) { $name = '(pending)' }
        if ($i -lt $current) {
            $bar = '#' * $barWidth
            $color = 'Green'
            $mark = 'OK '
        } elseif ($i -eq $current) {
            $half = [int]($barWidth / 2)
            $bar = ('#' * $half) + ('.' * ($barWidth - $half))
            $color = 'Yellow'
            $mark = '>> '
        } else {
            $bar = '.' * $barWidth
            $color = 'DarkGray'
            $mark = '   '
        }
        Write-Host ("  {0}[{1}] {2}" -f $mark, $bar, $name) -ForegroundColor $color
    }

    if ($done) {
        Write-Host ''
        if ($result -like 'PASS*') {
            Write-Host '  ALL CHECKS PASSED' -ForegroundColor Green
        } else {
            Write-Host '  FAILED' -ForegroundColor Red
            $why = ($result -split '\|')[1]
            if ($why) { Write-Host ('  ' + $why) -ForegroundColor Red }
        }
        Start-Sleep -Seconds 3
        break
    }
    Start-Sleep -Milliseconds 200
}
