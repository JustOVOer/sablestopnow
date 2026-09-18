# Offline verifier for the mate (SolidWorks-style constraint) geometry.
#
# Compiles tools/MateFramesCheck.java against the real project + Sable + Minecraft jars and
# runs it. No game launch needed; exits non-zero if any assertion fails.
#
# ASCII only on purpose: Windows PowerShell 5.1 reads a BOM-less .ps1 as ANSI, and non-ASCII
# comments can corrupt parsing.
#
# Usage:  powershell -ExecutionPolicy Bypass -File tools\run-mate-check.ps1
# Override the JDK with $env:JAVA_HOME or -Jdk <path>.

param(
    [string]$Jdk = $env:JAVA_HOME,
    [string]$GradleCache = (Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1')
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$out = Join-Path ([System.IO.Path]::GetTempPath()) 'matecheck-out'

if (-not $Jdk -or -not (Test-Path (Join-Path $Jdk 'bin\javac.exe'))) {
    throw "JDK 21 not found. Set `$env:JAVA_HOME or pass -Jdk <path>."
}
if (-not (Test-Path $GradleCache)) {
    throw "Gradle module cache not found: $GradleCache"
}

Write-Host "=== compiling project ==="
$env:JAVA_HOME = $Jdk
& (Join-Path $root 'gradlew.bat') -p $root compileJava --console=plain -q
if ($LASTEXITCODE -ne 0) { throw "project compile failed" }

$parts = New-Object System.Collections.Generic.List[string]
$parts.Add((Join-Path $root 'build\classes\java\main'))
# The merged jar carries net.minecraft.* itself; clientLegacyClasspath.txt only lists libraries.
$merged = Join-Path $root 'build\moddev\artifacts\neoforge-21.1.248-merged.jar'
if (Test-Path $merged) { $parts.Add($merged) }

$cpFile = Join-Path $root 'build\moddev\clientLegacyClasspath.txt'
if (Test-Path $cpFile) {
    Get-Content $cpFile | Where-Object { $_.Trim() -ne '' } | ForEach-Object { $parts.Add($_.Trim()) }
}

# sable / companion / joml are compileOnly, so they are not in clientLegacyClasspath.txt
foreach ($group in @('dev.ryanhcode.sable', 'dev.ryanhcode.sable-companion', 'org.joml')) {
    $dir = Join-Path $GradleCache $group
    if (-not (Test-Path $dir)) { continue }
    Get-ChildItem $dir -Recurse -Filter '*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'sources|javadoc' } |
        ForEach-Object { $parts.Add($_.FullName) }
}

$unique = $parts | Select-Object -Unique
$cp = $unique -join ';'
Write-Host "classpath entries: $($unique.Count)"

New-Item -ItemType Directory -Force -Path $out | Out-Null
Write-Host "=== compiling harness ==="
& (Join-Path $Jdk 'bin\javac.exe') -encoding UTF-8 -nowarn -cp $cp -d $out (Join-Path $root 'tools\MateFramesCheck.java') 2>&1
if ($LASTEXITCODE -ne 0) { throw "harness compile failed" }

Write-Host "=== running harness ==="
& (Join-Path $Jdk 'bin\java.exe') "-Dfile.encoding=UTF-8" -cp "$out;$cp" com.ovo.sablestopnow.tools.MateFramesCheck 2>&1
$code = $LASTEXITCODE
Write-Host "HARNESS_EXIT=$code"
exit $code
