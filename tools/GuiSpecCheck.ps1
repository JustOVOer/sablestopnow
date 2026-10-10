# GuiSpecCheck.ps1 -- config-GUI invariants (P0/P1, see docs/gui-统一.md).
#
# P1 moved group OWNERSHIP onto the option itself (ConfigOption.section(...)), so the old
# "name list in SECTION_PLAN" checks are obsolete. What still needs guarding:
#   I1'  every option in options() declares a section      (no silent "Other" group)
#   I3'  every section used by an option is in the ORDER table (else display order drifts)
#   I4'  every section in the ORDER table is actually used  (no ghost group)
#   I6   every used section key + category key exists in BOTH zh_cn.json and en_us.json
# ASCII only on purpose (no BOM/encoding traps). Exit 0 = OK, 1 = FAIL.

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$enumPath = Join-Path $root 'src\main\java\com\ovo\sablestopnow\client\gui\ModConfigCategory.java'
$planPath = Join-Path $root 'src\main\java\com\ovo\sablestopnow\client\gui\ModSettingsCategoryScreen.java'
$langDir = Join-Path $root 'src\main\resources\assets\sablestopnow\lang'
foreach ($p in @($enumPath, $planPath)) { if (-not (Test-Path $p)) { Write-Host "GUI_SPEC_CHECK=FAIL (missing $p)"; exit 1 } }
$enumSrc = Get-Content $enumPath -Raw
$planSrc = Get-Content $planPath -Raw


$failures = New-Object System.Collections.Generic.List[string]
$checks = 0

$catIds = [ordered]@{}
foreach ($m in [regex]::Matches($enumSrc, '([A-Z_]+)\("([a-z_]+)"')) { if (-not $catIds.Contains($m.Groups[1].Value)) { $catIds[$m.Groups[1].Value] = $m.Groups[2].Value } }

function Get-Slice([string] $src, [string] $startMarker, [string] $nextMarker, [string] $fallbackMarker) {
    $start = $src.IndexOf($startMarker)
    if ($start -lt 0) { return $null }
    $next = $src.IndexOf($nextMarker, $start + $startMarker.Length)
    $end = if ($next -ge 0) { $next } else { $src.IndexOf($fallbackMarker, $start) }
    if ($end -lt 0) { $end = $src.Length }
    return $src.Substring($start, $end - $start)
}

function Get-Options([string] $slice) {
    $out = New-Object System.Collections.Generic.List[object]
    $pos = 0
    while ($true) {
        $idx = $slice.IndexOf('ConfigOption.', $pos)
        if ($idx -lt 0) { break }
        $open = $slice.IndexOf('(', $idx)
        if ($open -lt 0) { break }
        $depth = 0; $i = $open; $close = -1
        while ($i -lt $slice.Length) {
            $c = $slice[$i]
            if ($c -eq '(') { $depth++ } elseif ($c -eq ')') { $depth--; if ($depth -eq 0) { $close = $i; break } }
            $i++
        }
        if ($close -lt 0) { break }
        $call = $slice.Substring($idx, $close - $idx + 1)
        $nm = [regex]::Match($call, 'ConfigOption\.\w+\("([a-z_]+)"')
        $after = $slice.Substring($close + 1, [Math]::Min(400, $slice.Length - $close - 1))
        $stop = $after.IndexOf('ConfigOption.')
        if ($stop -ge 0) { $after = $after.Substring(0, $stop) }
        $sm = [regex]::Match($after, '\.section\("([^"]+)"\)')
        if ($nm.Success) {
            $out.Add([pscustomobject]@{ Name = $nm.Groups[1].Value; Section = $(if ($sm.Success) { $sm.Groups[1].Value } else { $null }) })
        }
        $pos = $close + 1
    }
    return $out
}

$langs = @{}
foreach ($f in @('zh_cn.json', 'en_us.json')) {
    $lp = Join-Path $langDir $f
    if (-not (Test-Path $lp)) { $failures.Add("I6 lang file missing: $f"); continue }
    $langs[$f] = Get-Content $lp -Raw
}

$cats = @($catIds.Keys)
if ($cats.Count -eq 0) { $failures.Add('no categories parsed from ModConfigCategory.java') }
foreach ($cat in $cats) {
    $enumSlice = Get-Slice $enumSrc ('case ' + $cat) 'case ' '};'
    $planSlice = Get-Slice $planSrc ('ModConfigCategory.' + $cat + ', List.of(') 'ModConfigCategory.' '));'
    if ($null -eq $enumSlice) { $failures.Add("$cat : not found in ModConfigCategory.java"); continue }
    $opts = @(Get-Options $enumSlice)
    if ($opts.Count -eq 0) { $failures.Add("$cat : no ConfigOption found") }
    foreach ($o in $opts) {
        $checks++
        if ([string]::IsNullOrEmpty($o.Section)) { $failures.Add("I1' $cat : option '$($o.Name)' does NOT declare .section(...) -> it silently lands in SECTION_OTHER") }
    }
    $orderKeys = New-Object System.Collections.Generic.List[string]
    if ($null -ne $planSlice) { foreach ($m in [regex]::Matches($planSlice, 'new Section\("([^"]+)"')) { $orderKeys.Add($m.Groups[1].Value) } }
    $used = New-Object System.Collections.Generic.List[string]
    foreach ($o in $opts) { if ($o.Section -and -not $used.Contains($o.Section)) { $used.Add($o.Section) } }
    foreach ($k in $used) {
        $checks++
        if (-not $orderKeys.Contains($k)) { $failures.Add("I3' $cat : section '$k' is used but missing from the ORDER table -> display order would silently change") }
    }
    foreach ($k in $orderKeys) {
        $checks++
        if (-not $used.Contains($k)) { $failures.Add("I4' $cat : ORDER table lists '$k' but no option uses it (ghost group)") }
    }
    $summary = ($used | ForEach-Object { $k = $_; "$($k -replace 'gui\.sablestopnow\.section\.','')=$(@($opts | Where-Object { $_.Section -eq $k }).Count)" }) -join ' '
    Write-Host ("  {0,-10} options={1,-3} groups={2,-3}  {3}" -f $cat, $opts.Count, $used.Count, $summary)
    foreach ($k in $used) {
        foreach ($f in $langs.Keys) {
            $checks++
            if (-not $langs[$f].Contains('"' + $k + '"')) { $failures.Add("I6 $f : missing lang key '$k'") }
        }
    }
    foreach ($key in @(('gui.sablestopnow.category.' + $catIds[$cat]), ('gui.sablestopnow.category.' + $catIds[$cat] + '.desc'))) {
        foreach ($f in $langs.Keys) {
            $checks++
            if (-not $langs[$f].Contains('"' + $key + '"')) { $failures.Add("I6 $f : missing lang key '$key'") }
        }
    }
}
Write-Host ''


if ($failures.Count -gt 0) {
    Write-Host ("GUI_SPEC_CHECK=FAIL checks={0} failures={1}" -f $checks, $failures.Count)
    $failures | Select-Object -First 25 | ForEach-Object { Write-Host ("  - " + $_) }
    exit 1
}
Write-Host ("GUI_SPEC_CHECK=OK checks={0} failures=0" -f $checks)
exit 0