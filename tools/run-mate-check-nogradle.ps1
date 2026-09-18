# Offline verifier for the mate geometry, WITHOUT invoking Gradle.
#
# Why a second script: tools/run-mate-check.ps1 runs "gradlew compileJava", which rewrites
# build/classes/java/main. That is fine normally, but it must NOT be done while a dev client
# is running (the client lazily loads classes such as the mate GUI screen). This script only
# READS the already-compiled classes and builds the harness into a scratch directory.
#
# Requires the project to have been built at least once (gradlew build).
# ASCII only: PowerShell 5.1 reads a BOM-less .ps1 as ANSI.
param(
    [string]$Jdk = $env:JAVA_HOME,
    [string]$GradleCache = (Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1')
)

# Native tools (javac/java) write diagnostics to stderr. Under PowerShell 5.1, 'Stop' plus a
# "2>&1" redirection converts that stderr into a terminating NativeCommandError and kills the
# script mid-pipeline, so this must stay 'Continue'; the critical steps are checked explicitly.
$ErrorActionPreference = 'Continue'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$out = Join-Path ([System.IO.Path]::GetTempPath()) 'matecheck-out-nogradle'

if (-not $Jdk -or -not (Test-Path (Join-Path $Jdk 'bin\javac.exe'))) {
    throw "JDK 21 not found. Set `$env:JAVA_HOME or pass -Jdk <path>."
}
if (-not (Test-Path (Join-Path $root 'build\classes\java\main'))) {
    throw "project classes missing - run gradlew build first."
}

$parts = New-Object System.Collections.Generic.List[string]
$parts.Add((Join-Path $root 'build\classes\java\main'))
$merged = Join-Path $root 'build\moddev\artifacts\neoforge-21.1.248-merged.jar'
if (Test-Path $merged) { $parts.Add($merged) }

$cpFile = Join-Path $root 'build\moddev\clientLegacyClasspath.txt'
if (Test-Path $cpFile) {
    Get-Content $cpFile | Where-Object { $_.Trim() -ne '' } | ForEach-Object { $parts.Add($_.Trim()) }
}
Get-ChildItem (Join-Path $root 'lib') -Filter '*.jar' -ErrorAction SilentlyContinue |
    ForEach-Object { $parts.Add($_.FullName) }
foreach ($g in @('dev.ryanhcode.sable', 'dev.ryanhcode.sable-companion', 'dev.simulated_team.simulated',
                 'org.joml', 'org.jetbrains', 'io.github.llamalad7')) {
    $d = Join-Path $GradleCache $g
    if (-not (Test-Path $d)) { continue }
    Get-ChildItem $d -Recurse -Filter '*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'sources|javadoc' } |
        ForEach-Object { $parts.Add($_.FullName) }
}
$cp = (($parts | Select-Object -Unique) -join ';')

# Classpath for the full type check: same jars, but WITHOUT the previously compiled mod classes,
# so every mod class must come from source. Otherwise a stale .class could satisfy a reference
# and hide exactly the kind of error we are trying to catch.
$classesDir = Join-Path $root 'build\classes\java\main'
$cpNoClasses = (($parts | Select-Object -Unique | Where-Object { $_ -ne $classesDir }) -join ';')

$javac = Join-Path $Jdk 'bin\javac.exe'
Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $out | Out-Null

# ---------------------------------------------------------------------------------------------
# Pass 1: full-source type check. Compiles EVERY mod source into a scratch dir. Never touches
# build/classes, so it is safe to run while the dev client is up. This is the pass that catches
# exhaustive-switch / missing-symbol errors across client, server, network, mixin and mate code.
# If the jar classpath is incomplete we degrade to the old partial mode instead of failing hard,
# because the geometry harness below does not need the client classes at all.
# ---------------------------------------------------------------------------------------------
$srcRoot = Join-Path $root 'src\main\java'
$full = Join-Path ([System.IO.Path]::GetTempPath()) 'matecheck-full'
Remove-Item -Recurse -Force $full -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $full | Out-Null

$sources = @(Get-ChildItem $srcRoot -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
$argFile = Join-Path ([System.IO.Path]::GetTempPath()) 'matecheck-sources.txt'
Set-Content -Path $argFile -Value ($sources | ForEach-Object { '"' + ($_ -replace '\\', '\\') + '"' }) -Encoding ASCII

# Gradle-generated sources (mod metadata / access transformers) live outside src; add them when present.
Get-ChildItem (Join-Path $root 'build\generated') -Recurse -Filter '*.java' -ErrorAction SilentlyContinue |
    ForEach-Object { Add-Content -Path $argFile -Value ('"' + ($_.FullName -replace '\\', '\\') + '"') }

Write-Host "=== pass 1: full type check ($($sources.Count) sources) ==="
$fullLog = Join-Path ([System.IO.Path]::GetTempPath()) 'matecheck-full.log'
& $javac -encoding UTF-8 -nowarn -cp $cpNoClasses -d $full "@$argFile" 2>&1 | Tee-Object -FilePath $fullLog
$fullExit = $LASTEXITCODE
if ($fullExit -eq 0) {
    Write-Host "FULL_TYPECHECK=OK"
} else {
    $errCount = (Select-String -Path $fullLog -Pattern 'error:|错误:' -ErrorAction SilentlyContinue).Count
    Write-Host "FULL_TYPECHECK=FAILED errors=$errCount (geometry checks continue)"
}

Write-Host "=== pass 2: compiling harness (no gradle) ==="
# Fallback source overrides, used only when pass 1 could not run (incomplete jar classpath): they
# are compiled into the scratch dir, which is placed FIRST on the classpath, so they shadow the
# stale copies in build/classes without ever writing to it.
if ($fullExit -ne 0) {
    $overrides = @(
        (Join-Path $root 'src\main\java\com\ovo\sablestopnow\mate\RefKind.java'),
        (Join-Path $root 'src\main\java\com\ovo\sablestopnow\mate\MateType.java'),
        (Join-Path $root 'src\main\java\com\ovo\sablestopnow\mate\Mate.java'),
        (Join-Path $root 'src\main\java\com\ovo\sablestopnow\mate\MateFrames.java')
    ) | Where-Object { Test-Path $_ }

    if ($overrides.Count -gt 0) {
        & $javac -encoding UTF-8 -nowarn -cp $cp -d $out $overrides 2>&1
        if ($LASTEXITCODE -ne 0) { throw "override compile failed" }
        Write-Host "overrides compiled: $($overrides.Count)"
    }
}

& $javac -encoding UTF-8 -nowarn -cp "$full;$out;$cp" -d $out `
    (Join-Path $root 'tools\MateFramesCheck.java') 2>&1
if ($LASTEXITCODE -ne 0) { throw "harness compile failed" }

Write-Host "=== running harness ==="
& (Join-Path $Jdk 'bin\java.exe') "-Dfile.encoding=UTF-8" -cp "$full;$out;$cp" com.ovo.sablestopnow.tools.MateFramesCheck 2>&1
$code = $LASTEXITCODE
Write-Host "HARNESS_EXIT=$code"
# The geometry harness is the gate; the type check is reported but must also be surfaced.
if ($fullExit -ne 0) {
    Write-Host "NOTE: full type check failed - see $fullLog"
    exit 2
}
exit $code
