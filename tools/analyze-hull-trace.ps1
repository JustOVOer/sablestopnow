<#
.SYNOPSIS
    分析 sablestopnow 的凸包逐步轨迹，抓「陷入地下」与「速度突变」。

.DESCRIPTION
    读取 HullTrace 产出的 CSV（每【物理步】一行，20Hz），回答一个此前一直没被回答的问题：

        我们的凸包到底有没有【和地面产生有效接触】？

    三种机制会在轨迹上留下完全不同的指纹：

      ① y 一路穿过地面并继续下降      -> 根本没有接触对
                                        （碰撞分组 / 形状层面 ✗）
      ② y 在地面附近停住但低于地面     -> 有接触但穿透
                                        （接触解算层面 ✗）
      ③ 入地那一刻 |Δvy| 骤增          -> 被别的碰撞体弹出（结构自己的体素碰撞体）
                                        （共存 / 优先级层面 ✗）

    这正是「调物理参数 9 次都没用」之后，唯一能一次分清三种机制的测量。

.PARAMETER Path
    CSV 路径。默认 <仓库根>/run/sablestopnow-hull-trace.csv

.PARAMETER GroundY
    地面平面的 y。默认 0。

.PARAMETER MinSink
    低于地面多少才算「入地」，避免浮点噪声。默认 0.05。

.PARAMETER JumpVy
    |Δvy| 超过多少算一次「突变」。默认 2.0（方块/秒每物理步）。

.EXAMPLE
    .\tools\analyze-hull-trace.ps1
    .\tools\analyze-hull-trace.ps1 -Path run\sablestopnow-hull-trace.csv -GroundY 0
#>
[CmdletBinding()]
param(
    [string] $Path,
    [double] $GroundY = 0.0,
    [double] $MinSink = 0.05,
    [double] $JumpVy   = 2.0
)

$ErrorActionPreference = 'Stop'

if (-not $Path) {
    $root = Split-Path -Parent $PSScriptRoot
    $Path = Join-Path $root 'run\sablestopnow-hull-trace.csv'
}

if (-not (Test-Path $Path)) {
    Write-Host "找不到轨迹文件：$Path" -ForegroundColor Red
    Write-Host "先按四步操作一次（移空中 -> 转45度 -> 重载模型 -> 解除锁定），" -ForegroundColor Yellow
    Write-Host "HullTrace 会自动在 <gamedir> 下生成 sablestopnow-hull-trace.csv" -ForegroundColor Yellow
    exit 1
}

$rows = Import-Csv -Path $Path
if (-not $rows -or $rows.Count -eq 0) {
    Write-Host "轨迹文件是空的：$Path" -ForegroundColor Red
    exit 1
}

Write-Host ("=== 凸包轨迹分析 ===") -ForegroundColor Cyan
Write-Host ("文件   : " + $Path)
Write-Host ("样本   : " + $rows.Count + " 个物理步")
Write-Host ("物理步 : " + $rows[0].tick + " .. " + $rows[-1].tick)
Write-Host ("")

# ---------------- 收集 ----------------
$ys      = New-Object System.Collections.Generic.List[double]
$vys     = New-Object System.Collections.Generic.List[double]
$ticks   = New-Object System.Collections.Generic.List[long]
$masses  = New-Object System.Collections.Generic.List[double]
$colls   = New-Object System.Collections.Generic.List[double]

foreach ($r in $rows) {
    $ys.Add([double]$r.y);      $vys.Add([double]$r.vy)
    $ticks.Add([long]$r.tick);  $masses.Add([double]$r.mass)
    $colls.Add([double]$r.colliders)
}

# ---------------- ① 陷入地下 ----------------
$minY = ($ys | Measure-Object -Minimum).Minimum
$minIdx = $ys.IndexOf($minY)
$underIdx = @()
for ($i = 0; $i -lt $ys.Count; $i++) { if ($ys[$i] -lt ($GroundY - $MinSink)) { $underIdx += $i } }

Write-Host "① 陷入地下" -ForegroundColor Yellow
Write-Host ("   最低 y           = " + [Math]::Round($minY, 4) + "   (物理步 " + $ticks[$minIdx] + ")")
Write-Host ("   起始 y           = " + [Math]::Round($ys[0], 4))
Write-Host ("   结束 y           = " + [Math]::Round($ys[-1], 4))
if ($underIdx.Count -gt 0) {
    $pct = [Math]::Round(100.0 * $underIdx.Count / $ys.Count, 2)
    Write-Host ("   低于地面(-$MinSink) = " + $underIdx.Count + " 步  (" + $pct + "%)") -ForegroundColor Red
    Write-Host ("   首次入地 物理步  = " + $ticks[$underIdx[0]])
    Write-Host ("   最深穿透         = " + [Math]::Round(($GroundY - $minY), 4) + " 方块")
} else {
    Write-Host ("   ✓ 从未低于地面" ) -ForegroundColor Green
}
Write-Host ("")

# ---------------- ② 突变 ----------------
Write-Host "② 速度突变" -ForegroundColor Yellow
$maxJump = 0.0; $maxJumpIdx = -1; $jumps = @()
for ($i = 1; $i -lt $vys.Count; $i++) {
    $d = [Math]::Abs($vys[$i] - $vys[$i - 1])
    if ($d -gt $maxJump) { $maxJump = $d; $maxJumpIdx = $i }
    if ($d -gt $JumpVy) { $jumps += [pscustomobject]@{ idx = $i; tick = $ticks[$i]; d = $d; y = $ys[$i] } }
}
Write-Host ("   最大 |dvy|/步     = " + [Math]::Round($maxJump, 4) + "   (物理步 " + $(if ($maxJumpIdx -ge 0) { $ticks[$maxJumpIdx] } else { 'n/a' }) + ")")
Write-Host ("   突变次数(> $JumpVy)  = " + $jumps.Count)
if ($jumps.Count -gt 0) {
    Write-Host "   前 8 次突变（tick / |dvy| / 当时 y）:"
    $jumps | Select-Object -First 8 | ForEach-Object {
        Write-Host ("     tick " + $_.tick + "   |dvy|=" + [Math]::Round($_.d, 3) + "   y=" + [Math]::Round($_.y, 3))
    }
}
Write-Host ("")

# ---------------- ③ 其它 ----------------
Write-Host "③ 其它" -ForegroundColor Yellow
Write-Host ("   质量 首/末       = " + [Math]::Round($masses[0], 4) + " / " + [Math]::Round($masses[-1], 4))
Write-Host ("   碰撞体数 首/末   = " + [Math]::Round($colls[0], 0) + " / " + [Math]::Round($colls[-1], 0))
Write-Host ("")

# ---------------- 判读 ----------------
Write-Host "=== 判读（三种机制的指纹）===" -ForegroundColor Cyan
$neverStops = ($ys[-1] -lt $minY + 1e-9) -and ($minY -lt ($GroundY - 1.0))
if ($underIdx.Count -gt 0 -and $jumps.Count -gt 0 -and ($jumps | Where-Object { $_.y -lt ($GroundY + 0.5) }).Count -gt 0) {
    Write-Host "   ⇒ 入地那一刻有速度骤变 -> 【被别的碰撞体弹出】" -ForegroundColor Magenta
    Write-Host "      （结构自己的体素碰撞体 vs 我们的凸包：共存 / 优先级层面）" -ForegroundColor Magenta
} elseif ($underIdx.Count -gt 0) {
    Write-Host "   ⇒ 有入地但没有明显骤变 -> 【有接触但穿透】" -ForegroundColor Magenta
    Write-Host "      （接触解算：穿透容差 / 恢复力层面）" -ForegroundColor Magenta
} else {
    Write-Host "   ⇒ 没有低于地面的样本 -> 若你实际看到陷入，说明【凸包压根没参与接触】" -ForegroundColor Magenta
    Write-Host "      （碰撞分组 / 形状层面；回到 groups.rs 与 collider.rs 的对照）" -ForegroundColor Magenta
}
