# GuiLayoutCheck.ps1 -- P3c offline half: layout-constant invariants (no font needed).
#
# WHY SEPARATE FROM GuiSpecCheck.ps1: editing that file with a text insertion broke its
# string boundaries once. A self-contained sibling is safer and easier to reason about.
#
# The font-DEPENDENT half ("does THIS string fit in THAT width") cannot run offline at all;
# it runs at client startup where a real Font exists -- see client/gui/lib/TextFit.java.
#
# What is checked here (all parsed from the sources, nothing hard-coded):
#   L1  ROW_H  >= ceil(baseLine * maxFontScale) + 2      (else a row is shorter than its line)
#   L2  CONTROL_W >= 60                                  (slider/textfield column must stay usable)
#   L3  min left-panel width >= 80                       (category titles must fit)
#   L4  all four constants were found                    (else the checker is stale, not the code)
#
# Usage:  powershell -NoProfile -ExecutionPolicy Bypass -File tools\GuiLayoutCheck.ps1 [-SelfTest]
# ASCII only on purpose (no BOM/encoding traps). Exit 0 = OK, 1 = FAIL.

param([switch]$SelfTest)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

function Get-Int([string] $src, [string] $pattern) {
    $m = [regex]::Match($src, $pattern)
    if ($m.Success) { return [int]$m.Groups[1].Value }
    return -1
}

# --- the assertion itself, so -SelfTest can feed it synthetic numbers ------------------
function Test-Layout($rowH, $controlW, $panelMin, $baseLine, $fontScaleMax) {
    $failures = New-Object System.Collections.Generic.List[string]
    $checks = 0

    if ($rowH -gt 0 -and $baseLine -gt 0 -and $fontScaleMax -gt 0) {
        $checks++
        $needed = [Math]::Ceiling($baseLine * $fontScaleMax) + 2
        if ($rowH -lt $needed) {
            $failures.Add("L1 ROW_H=$rowH < ceil(baseLine=$baseLine x fontScaleMax=$fontScaleMax)+2 = $needed -> a line of text does not fit its own row")
        }
    } else {
        $checks++
        $failures.Add('L4 could not parse ROW_H / baseLine / ui_font_scale (checker is stale?)')
    }

    if ($controlW -gt 0) {
        $checks++
        if ($controlW -lt 60) { $failures.Add("L2 CONTROL_W=$controlW is too narrow") }
    } else {
        $checks++
        $failures.Add('L4 could not parse CONTROL_W')
    }

    if ($panelMin -gt 0) {
        $checks++
        if ($panelMin -lt 80) { $failures.Add("L3 left panel min width $panelMin is too narrow") }
    } else {
        $checks++
        $failures.Add('L4 could not parse the left-panel minimum width')
    }

    return [pscustomobject]@{ Checks = $checks; Failures = $failures }
}

if ($SelfTest) {
    # synthetic cases: the failing one MUST be reported, otherwise this checker is worthless.
    $ok = Test-Layout 16 120 92 9 1.0
    $bad = Test-Layout 4 40 60 9 1.0
    $neg = -1
    $stale = Test-Layout $neg $neg $neg $neg $neg
    $problems = New-Object System.Collections.Generic.List[string]
    $checks = 3
    if (-not $ok.Failures.Count -eq 0) { $problems.Add('self-test: a healthy constant set was reported as failing') }
    if ($bad.Failures.Count -lt 3) { $problems.Add("self-test: the broken constant set produced only $($bad.Failures.Count) failures (expected >= 3)") }
    if ($stale.Failures.Count -lt 1) { $problems.Add('self-test: unparseable constants were NOT reported') }
    if ($problems.Count -gt 0) {
        Write-Host ("GUI_LAYOUT_CHECK=FAIL checks={0} failures={1}" -f $checks, $problems.Count)
        $problems | ForEach-Object { Write-Host ("  - " + $_) }
        exit 1
    }
    Write-Host ("GUI_LAYOUT_SELFTEST=OK checks={0}" -f $checks)
    exit 0
}

$screenPath = Join-Path $root 'src\main\java\com\ovo\sablestopnow\client\gui\ModSettingsCategoryScreen.java'
$catScreenPath = Join-Path $root 'src\main\java\com\ovo\sablestopnow\client\gui\ModSettingsScreen.java'
$themePath = Join-Path $root 'src\main\java\com\ovo\sablestopnow\client\gui\lib\UiTheme.java'
$enumPath = Join-Path $root 'src\main\java\com\ovo\sablestopnow\client\gui\ModConfigCategory.java'
foreach ($p in @($screenPath, $themePath, $enumPath)) {
    if (-not (Test-Path $p)) { Write-Host "GUI_LAYOUT_CHECK=FAIL (missing $p)"; exit 1 }
}

$scr = Get-Content $screenPath -Raw
$thm = Get-Content $themePath -Raw
$enm = Get-Content $enumPath -Raw

$rowH = Get-Int $scr 'ROW_H\s*=\s*(\d+)'
$controlW = Get-Int $scr 'CONTROL_W\s*=\s*(\d+)'
$panelMin = Get-Int (Get-Content $catScreenPath -Raw) 'Math\.max\((\d+),\s*Math\.min\('
$baseLine = Get-Int $thm 'baseLine\s*=\s*(\d+)'
$fontScaleMax = -1.0
$fs = [regex]::Match($enm, 'ui_font_scale",\s*config\.uiFontScale,\s*[0-9.]+,\s*([0-9.]+)')
if ($fs.Success) { $fontScaleMax = [double]$fs.Groups[1].Value }

Write-Host ("  parsed: ROW_H={0} CONTROL_W={1} panelMin={2} baseLine={3} fontScaleMax={4}" -f $rowH, $controlW, $panelMin, $baseLine, $fontScaleMax)

$r = Test-Layout $rowH $controlW $panelMin $baseLine $fontScaleMax
if ($r.Failures.Count -gt 0) {
    Write-Host ("GUI_LAYOUT_CHECK=FAIL checks={0} failures={1}" -f $r.Checks, $r.Failures.Count)
    $r.Failures | ForEach-Object { Write-Host ("  - " + $_) }
    exit 1
}
Write-Host ("GUI_LAYOUT_CHECK=OK checks={0} failures=0" -f $r.Checks)
exit 0
