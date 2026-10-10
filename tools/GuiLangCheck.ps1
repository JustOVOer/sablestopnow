# GuiLangCheck.ps1 -- I7: every config option's label/desc lang key must exist in BOTH languages.
#
# WHY THIS EXISTS (docs/gui-统一.md, item B):
#   ConfigOption derives its lang keys from the option NAME at runtime:
#       label = "gui.sablestopnow.opt." + name          desc = label + ".desc"
#   NeoForge derives the TOML key from the same literal. So a typo in that literal does NOT
#   fail to compile and does NOT show up in the existing lang gate (which only understands
#   *static* string literals). The user-visible symptom is a raw key like
#   "gui.sablestopnow.opt.ui_font_scal" rendered in the settings screen.
#   This checker closes exactly that hole without touching 38 call sites.
#
# ASCII only on purpose. Exit 0 = OK, 1 = FAIL.

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

$enumPath = Join-Path $root 'src\main\java\com\ovo\sablestopnow\client\gui\ModConfigCategory.java'
$langDir = Join-Path $root 'src\main\resources\assets\sablestopnow\lang'

if (-not (Test-Path $enumPath)) { Write-Host "GUI_LANG_CHECK=FAIL (missing $enumPath)"; exit 1 }

$enumSrc = Get-Content $enumPath -Raw
$names = New-Object System.Collections.Generic.List[string]
foreach ($m in [regex]::Matches($enumSrc, 'ConfigOption\.\w+\("([a-z_0-9]+)"')) {
    if (-not $names.Contains($m.Groups[1].Value)) { $names.Add($m.Groups[1].Value) }
}

$checks = 0
$failures = New-Object System.Collections.Generic.List[string]
$langs = @{}
foreach ($f in @('zh_cn.json', 'en_us.json')) {
    $p = Join-Path $langDir $f
    if (-not (Test-Path $p)) { $failures.Add("lang file missing: $f"); continue }
    $langs[$f] = Get-Content $p -Raw
}

foreach ($name in $names) {
    foreach ($suffix in @('', '.desc')) {
        $key = 'gui.sablestopnow.opt.' + $name + $suffix
        foreach ($f in $langs.Keys) {
            $checks++
            if (-not $langs[$f].Contains('"' + $key + '"')) {
                $failures.Add("I7 $f : missing lang key '$key' (option '$name' would render as a raw key)")
            }
        }
    }
}

Write-Host ("  options parsed = {0}  lang files = {1}" -f $names.Count, $langs.Keys.Count)
if ($failures.Count -gt 0) {
    Write-Host ("GUI_LANG_CHECK=FAIL checks={0} failures={1}" -f $checks, $failures.Count)
    $failures | Select-Object -First 25 | ForEach-Object { Write-Host ("  - " + $_) }
    exit 1
}
Write-Host ("GUI_LANG_CHECK=OK checks={0} failures=0" -f $checks)
exit 0
