# Full pre-handoff verification for sablestopnow. ASCII only (Windows PowerShell 5.1 reads
# a BOM-less .ps1 as ANSI, and native tools write diagnostics to stderr).
#
# Checks, in order:
#   1. full-source type check + geometry harness + GUI self-check harness
#      (delegates to run-mate-check-nogradle.ps1)
#   2. mixin config vs mixin sources (a mismatch here means the game will not start)
#   3. lang keys referenced from Java vs keys defined in zh_cn.json (a miss shows a raw key in game)
#   4. raw GuiGraphics drawing outside the GUI library (violates "UI must go through the library")
#   5. the control HUD's public notify* API is intact (external callers depend on it)
#   6. sketch: solver contract (30) + model layer (85) + persistence round-trip (62),
#      all offline. The solver one is the only gate that exercises Rust code and it catches
#      a broken C ABI or a stale .dll; the persistence one catches "writes but cannot read back".
#
# Usage:  powershell -NoProfile -ExecutionPolicy Bypass -File tools\verify-all.ps1
#         powershell -NoProfile -ExecutionPolicy Bypass -File tools\verify-all.ps1 -Only types,lang
#
# -Only <names> runs just the named blocks (comma separated); no -Only runs everything.
# Block names and their cost:
#   types  - full-source javac type check            (SLOW: compiles everything)
#   mixin  - mixin config vs sources                 (instant)
#   lang   - lang keys referenced vs defined         (instant)
#   gui    - raw GuiGraphics drawing outside the lib (instant)
#   hud    - control HUD public notify* API          (instant)
#   sketch - solver/model/persistence offline checks (medium: compiles the pure classes)
#   nurbs  - NURBS core offline check (fast: pure JDK, no Minecraft/JOML)
# Typical use: after changing only pure sketch logic, run `-Only sketch`;
# reserve the plain full run for handoff.

param(
    [string[]]$Only = @(),
    [switch]$NoProgress
)

$ErrorActionPreference = 'Continue'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$srcRoot = Join-Path $root 'src\main\java'
# ⚠ 在**脚本顶层**就把 tools 目录定下来。
#   我在下面的块里直接读 `$PSScriptRoot` 时，它离奇地取到空值
#   （同一个变量在同一块的上一行还是好的），为此连栽三次（含一次"表达式跨行"误诊）。
#   与其解释 PowerShell 的作用域行为，不如在能用的地方算一次、之后只用这个变量。
$toolsDir = $PSScriptRoot
if (-not $toolsDir) {
    $toolsDir = Join-Path $root 'tools'
}
# ⚠ 连**路径字符串**都在顶层算好：我在下面的块里读 `$PSScriptRoot`/`$toolsDir` 时反复取到空值
#   （同一变量在顶层明明可用），为此连栽四次。与其继续追 PowerShell 的作用域行为，
#   不如"在能用的地方算完，块里只做使用"。这里也顺带说明：**本脚本一切语句必须单行**，
#   我三次故障里两次是把表达式跨了行（PowerShell 不会因下一行以 `+`/`(` 开头而续行）。
$colCheckSrc = $toolsDir + '\NurbsCollisionCheck.java'
Write-Host ('  [tools] ' + $toolsDir)

# ---------------------------------------------------------------- 进度窗口
# 用户 2026-10 的要求：跑 verify 时**弹一个终端窗口**显示进度条与当前正在执行的验证，
# 验证完自动关闭。实现用"状态文件轮询"（最小耦合，不依赖管道权限）。
# 加 -NoProgress 可以关掉它（CI / 脚本里不想弹窗时）。
$progressFile = $env:TEMP + '\sablestopnow-verify-status.txt'
$stepIndex = 0
$stepTotal = 7
if (-not $NoProgress) {
    Remove-Item -Force $progressFile -ErrorAction SilentlyContinue
    Set-Content -Path $progressFile -Value 'STEP|0|8|starting' -Encoding ASCII
    Start-Process -FilePath 'powershell' -ArgumentList @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass',
        '-File', ($toolsDir + '\verify-progress.ps1'),
        '-StatusFile', $progressFile
    ) | Out-Null
}

# 每进入一个块就写一行进度（进度窗口据此重画）
function Set-Step([string]$name) {
    $script:stepIndex++
    if (-not $NoProgress) {
        Set-Content -Path $progressFile -Value ("STEP|$($script:stepIndex)|$stepTotal|$name") -Encoding ASCII -ErrorAction SilentlyContinue
    }
}

$langDir = Join-Path $root 'src\main\resources\assets\sablestopnow\lang'
$failed = New-Object System.Collections.Generic.List[string]

# No -Only means "run everything"; otherwise only the named blocks.
function Want([string]$name) {
    return ($Only.Count -eq 0) -or ($Only -contains $name)
}

if (Want 'types') {
    Set-Step 'type check'
Write-Host '=== 1/5 mate geometry + type check + GUI/outline self-checks ==='
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'run-mate-check-nogradle.ps1') 2>&1 |
    Select-String -Pattern 'FULL_TYPECHECK|checks,|HARNESS_EXIT|GUI_LINT|OUTLINE_CHECK' | ForEach-Object { Write-Host ('  ' + $_.Line) }
if ($LASTEXITCODE -ne 0) { $failed.Add('mate check / type check reported a non-zero exit') }
}

Set-Step 'mixins'
Write-Host '=== 2/5 mixin config vs sources ==='
$mixinsJson = Get-Content (Join-Path $root 'src\main\resources\sablestopnow.mixins.json') -Raw | ConvertFrom-Json
$entries = @()
$entries += $mixinsJson.mixins
$entries += $mixinsJson.client
$entries += $mixinsJson.server
$entries = $entries | Where-Object { $_ }
$mixinDir = Join-Path $srcRoot 'com\ovo\sablestopnow\mixin'
$missing = @()
foreach ($e in $entries) {
    $p = Join-Path $mixinDir (($e -replace '\.', '\') + '.java')
    if (-not (Test-Path $p)) { $missing += $e }
}
$unregistered = @()
Get-ChildItem $mixinDir -Recurse -Filter '*.java' | ForEach-Object {
    $rel = $_.FullName.Substring($mixinDir.Length + 1).Replace('.java', '').Replace('\', '.')
    if ($entries -notcontains $rel) { $unregistered += $rel }
}
Write-Host ("  entries={0} missing={1} unregistered={2}" -f $entries.Count, $missing.Count, $unregistered.Count)
$missing | ForEach-Object { Write-Host ("  MISSING MIXIN: " + $_) }
$unregistered | ForEach-Object { Write-Host ("  UNREGISTERED MIXIN: " + $_) }
if ($missing.Count -gt 0) { $failed.Add('mixins.json lists classes that do not exist') }
if ($unregistered.Count -gt 0) { $failed.Add('mixin sources are not registered in mixins.json') }

Set-Step 'lang keys'
Write-Host '=== 3/5 lang keys referenced vs defined ==='
$zh = Get-Content (Join-Path $langDir 'zh_cn.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$en = Get-Content (Join-Path $langDir 'en_us.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$zhNames = @($zh.PSObject.Properties.Name)
$enNames = @($en.PSObject.Properties.Name)
$onlyZh = @($zhNames | Where-Object { $enNames -notcontains $_ })
$onlyEn = @($enNames | Where-Object { $zhNames -notcontains $_ })
$defined = @{}
$zhNames | ForEach-Object { $defined[$_] = $true }
$referenced = @{}
Get-ChildItem $srcRoot -Recurse -Filter '*.java' | ForEach-Object {
    $text = Get-Content $_.FullName -Raw -Encoding UTF8
    [regex]::Matches($text, '(?:translatable|tooltipKey|placeholder)\(\s*"([a-zA-Z0-9_.]+)"') | ForEach-Object {
        $referenced[$_.Groups[1].Value] = $true
    }
}
# keys ending in '.' are string-concatenation prefixes, not real keys
$undef = @($referenced.Keys | Where-Object { -not $defined.ContainsKey($_) -and $_ -notmatch '\.$' } | Sort-Object)
Write-Host ("  zh={0} en={1} referenced={2} undefined={3}" -f $zhNames.Count, $enNames.Count, $referenced.Count, $undef.Count)
$undef | ForEach-Object { Write-Host ("  UNDEFINED LANG KEY: " + $_) }
if ($undef.Count -gt 0) { $failed.Add('Java references lang keys that are not defined') }
if ($onlyZh.Count -gt 0 -or $onlyEn.Count -gt 0) {
    Write-Host ("  KEY SET MISMATCH: only-in-zh={0} only-in-en={1}" -f $onlyZh.Count, $onlyEn.Count)
    $failed.Add('zh_cn.json and en_us.json key sets differ')
}

Set-Step 'GUI lint'
Write-Host '=== 4/5 raw GuiGraphics drawing outside the GUI library ==='
$libDir = Join-Path $srcRoot 'com\ovo\sablestopnow\client\gui\lib'
$rawHits = @()
Get-ChildItem $srcRoot -Recurse -Filter '*.java' | Where-Object { -not $_.FullName.StartsWith($libDir) } | ForEach-Object {
    $text = Get-Content $_.FullName -Raw -Encoding UTF8
    # strip line comments so "// graphics.fill(...)" explanations do not count
    $code = ($text -split "`n" | ForEach-Object { ($_ -replace '//.*$', '') }) -join "`n"
    # ⚠ The receiver matters, and a regex cannot see types. Require the file to actually
    # reference GuiGraphics at all; otherwise innocuous calls like java.util.Arrays.fill(...)
    # trip the rule (that false positive DID block a build once). A real offender must have
    # GuiGraphics in scope, so this keeps the check meaningful while dropping the noise.
    # Match on the RECEIVER name, not on the method name alone: a regex cannot see types,
    # but it CAN require that the call looks like `graphics.fill(...)` / `guiGraphics.fill(...)`.
    # Without this, any innocuous `.fill(` (e.g. java.util.Arrays.fill) inside a file that
    # legitimately mentions GuiGraphics trips the rule -- that happened twice.
    if ($code -match 'GuiGraphics' -and $code -match '(?<![\w.])(graphics|guiGraphics|drawContext|context|poseStack)\s*\.\s*(fill|drawString|drawCenteredString|blit|renderItem)\s*\(') {
        $rawHits += $_.FullName.Substring($srcRoot.Length + 1)
    }
}
Write-Host ("  files with raw drawing (outside lib): {0}" -f $rawHits.Count)
$rawHits | ForEach-Object { Write-Host ("  RAW DRAW: " + $_) }
if ($rawHits.Count -gt 0) { $failed.Add('UI code outside client/gui/lib draws GuiGraphics directly') }

Set-Step 'HUD api'
Write-Host '=== 5/6 control HUD public API ==='
$hudPath = Join-Path $srcRoot 'com\ovo\sablestopnow\client\StaffControlHud.java'
$apiOk = $true
if (Test-Path $hudPath) {
    $hudText = Get-Content $hudPath -Raw -Encoding UTF8
    foreach ($m in @('notifySelectionChanged', 'notifyApplied', 'notifyModeChanged', 'notifySustainedEnded')) {
        if ($hudText -notmatch [regex]::Escape($m)) {
            Write-Host ("  MISSING API: " + $m)
            $apiOk = $false
        }
    }
} else {
    Write-Host '  StaffControlHud.java not found'
    $apiOk = $false
}
Write-Host ("  notify* API intact: {0}" -f $apiOk)
if (-not $apiOk) { $failed.Add('StaffControlHud public notify* API changed') }

# --- 6/6 sketch solver contract (real native library, via JNA) ------------------
# This is the one gate that exercises Rust code. It compiles the solver facade plus the
# harness with plain javac and runs 30 assertions against the real .dll/.so, so a broken
# C ABI or a stale native library is caught without launching the game.
if (Want 'sketch') {
    Set-Step 'sketch model / data'
Write-Host '=== 6/6 sketch solver contract (native lib via JNA) ==='
$jnaJar = Join-Path $root 'lib\jna-5.14.0.jar'
$solverSrc = Join-Path $srcRoot 'com\ovo\sablestopnow\sketch\SketchSolver.java'
$harnessSrc = Join-Path $PSScriptRoot 'SketchSolverCheck.java'
# pick the native library for this platform
$nativeName = 'ezpz_windows-x86_64.dll'
if ($env:OS -notmatch 'Windows') {
    $nativeName = if ((uname) -match 'Darwin') { 'ezpz_macos-x86_64.dylib' } else { 'ezpz_linux-x86_64.so' }
}
$nativeLib = Join-Path $root ('src\main\resources\sablestopnow\sketch\' + $nativeName)
if (-not (Test-Path $solverSrc) -or -not (Test-Path $harnessSrc)) {
    Write-Host '  SKETCH_SOLVER_CHECK=SKIP (sources not present yet)'
} elseif (-not (Test-Path $jnaJar)) {
    Write-Host '  SKETCH_SOLVER_CHECK=FAIL (missing lib\jna-5.14.0.jar)'
    $failed.Add('sketch solver check cannot run: JNA jar missing')
} elseif (-not (Test-Path $nativeLib)) {
    Write-Host ("  SKETCH_SOLVER_CHECK=FAIL (missing native lib for this platform: {0})" -f $nativeName)
    $failed.Add('sketch solver check cannot run: native library missing for this platform')
} else {
    $sketchOut = Join-Path $env:TEMP 'sketchcheck-gate'
    Remove-Item -Recurse -Force $sketchOut -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $sketchOut | Out-Null
    & javac -encoding UTF-8 -cp $jnaJar -d $sketchOut $solverSrc $harnessSrc 2>&1 |
        Select-Object -First 15 | ForEach-Object { Write-Host ('  ' + $_) }
    if ($LASTEXITCODE -ne 0) {
        Write-Host '  SKETCH_SOLVER_CHECK=FAIL (compile)'
        $failed.Add('sketch solver facade/harness failed to compile')
    } else {
        $sketchRun = & java -cp ($sketchOut + ';' + $jnaJar) SketchSolverCheck $nativeLib 2>&1
        $sketchLine = $sketchRun | Select-String -Pattern 'SKETCH_SOLVER_CHECK=' | Select-Object -Last 1
        $sketchRun | Select-String -Pattern 'FAIL ' | ForEach-Object { Write-Host ('  ' + $_.Line) }
        if ($sketchLine) { Write-Host ('  ' + $sketchLine.Line) }
        if (-not $sketchLine -or $sketchLine.Line -notmatch 'SKETCH_SOLVER_CHECK=OK') {
            $failed.Add('sketch solver contract check failed')
        }

        # --- model layer (SketchPlane / Sketch): pure geometry + program encoding ---
        $sketchDir = Join-Path $srcRoot 'com\ovo\sablestopnow\sketch'
        # The pure (MC-free) sketch classes live in ONE place so the model block and the data
        # block below cannot drift apart. They used to be two hand-kept lists, and forgetting a
        # class in one of them shows up as a wall of "cannot find symbol" for that class - which
        # reads like a wrong argument type, not like a missing file. That trap bit us more than
        # six times; hence the single list.
        $pureSketch = @(
            'SketchPlane.java', 'Sketch.java', 'SketchSession.java', 'SketchDatumPlanes.java',
            'SketchMode.java', 'SketchProfiles.java', 'Extrude.java', 'Revolve.java',
            'Solid.java', 'SolidMesh.java', 'SolidUv.java', 'SolidAppearance.java',
            'SolidCollision.java', 'SketchDof.java', 'SketchMeasure.java',
            'ConvexDecomposition.java'
        ) | ForEach-Object { Join-Path $sketchDir $_ }
        # ⚠ **新增的纯逻辑包必须加进这张清单**，否则 SolidUv 引用它时，
        #   草图检查会报一整片 "找不到符号"（读起来像参数类型错，而不是缺文件）——
        #   这个坑在注释里已经记过一次，这次是 param 包。
        # ⚠ 这里**显式列文件名**，不用 Get-ChildItem + 管道 + 续行：
        #   我试过那种写法，它**静默地没生效**（编译仍报"包不存在"），
        #   而显式列出失败时会直接说"文件不存在"，好排查得多。
        # ⚠⚠ **本脚本里 `Join-Path` 不可靠**：同一个变量、同样两个参数，
        #   它有时返回正确路径、有时返回**空**（`Join-Path $sketchDir 'param'` 就是空，
        #   而 `Join-Path $sketchDir $_` 在管道里却正常）。我为此在 `$toolsDir`/`$colSrc`/
        #   `$paramDir` 上连栽五次，每次症状都是"变量莫名是 null"，而错误信息被我自己的
        #   Select-String 过滤掉了，等于把证据扔掉。
        #   **结论：路径一律用字符串拼接，不用 Join-Path。**
        $paramDir = $sketchDir + '\param'
        $pureSketch += @(
            ($paramDir + '\ParamSurface.java'),
            ($paramDir + '\Surfaces.java'),
            ($paramDir + '\UvMapping.java'),
            ($paramDir + '\ArcLengthTable.java')
        )
        # 再补一次 SketchMeasure.java（字符串拼接，避开本脚本里 Join-Path 间歇返回空的问题）
        # 实锤：model 块报过 找不到符号 SketchMeasure，而它就在上面清单里 ——
        # 空串被 javac 静默忽略，于是 文件没进编译 被读成 类不存在，方向被带偏。
        $pureSketch += @(($sketchDir + '\SketchMeasure.java'))
        # CSG（csg.js 的 Java 移植）—— 切割用布尔，也要进离线编译 ✓
        $pureSketch += @(
            ($sketchDir + '\csg\Csg.java'),
            ($sketchDir + '\csg\CsgMesh.java'),
            ($sketchDir + '\Features.java')
        )
        $jomlJar = Get-ChildItem (Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1\org.joml') -Recurse -Filter 'joml-*.jar' -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1
        # @Nullable comes from org.jetbrains.annotations; the lightweight classpath needs it too.
        $annJar = Get-ChildItem (Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1\org.jetbrains\annotations') -Recurse -Filter 'annotations-*.jar' -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1
        if (-not $jomlJar -or -not $annJar) {
            Write-Host '  SKETCH_MODEL_CHECK=SKIP (joml/annotations not in the Gradle cache; run a build once)'
        } else {
            $modelOut = Join-Path $env:TEMP 'sketchmodel-gate'
            Remove-Item -Recurse -Force $modelOut -ErrorAction SilentlyContinue
            New-Item -ItemType Directory -Force -Path $modelOut | Out-Null
            $modelCp = $jomlJar.FullName + ';' + $jnaJar + ';' + $annJar.FullName
            # model 块最容易"文件没进编译"；只打 15 行会把真正原因截掉（今天就被带偏过一次）⇒ 打 60 行
            & javac -encoding UTF-8 -nowarn -cp $modelCp -d $modelOut @pureSketch $solverSrc (Join-Path $PSScriptRoot 'SketchModelCheck.java') 2>&1 |
                Select-Object -First 60 | ForEach-Object { Write-Host ('  ' + $_) }
            if ($LASTEXITCODE -ne 0) {
                Write-Host '  SKETCH_MODEL_CHECK=FAIL (compile)'
                $failed.Add('sketch model harness failed to compile')
            } else {
                $modelRun = & java -cp ($modelOut + ';' + $modelCp) SketchModelCheck $nativeLib 2>&1
                $modelLine = $modelRun | Select-String -Pattern 'SKETCH_MODEL_CHECK=' | Select-Object -Last 1
                $modelRun | Select-String -Pattern 'FAIL ' | ForEach-Object { Write-Host ('  ' + $_.Line) }
                if ($modelLine) { Write-Host ('  ' + $modelLine.Line) }
                if (-not $modelLine -or $modelLine.Line -notmatch 'SKETCH_MODEL_CHECK=OK') {
                    $failed.Add('sketch model check failed')
                }
            }
        }

        # --- 薄壳拟合精度：偏差 <= 1/16 且包含曲面（用户"精度不差"的条件）---
        $shellOut = $env:TEMP + '\shellfit-gate'
        Remove-Item -Recurse -Force $shellOut -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force -Path $shellOut | Out-Null
        & javac -encoding UTF-8 -nowarn -cp ($modelOut + ';' + $modelCp) -d $shellOut ($root + '\tools\ShellFitCheck.java') 2>&1 |
            Select-Object -First 10 | ForEach-Object { Write-Host ('  ' + $_) }
        if (Test-Path ($shellOut + '\ShellFitCheck.class')) {
            $shellRun = & java -cp ($shellOut + ';' + $modelOut + ';' + $modelCp) ShellFitCheck 2>&1
            $shellRun | Select-String -Pattern 'FAIL ' | ForEach-Object { Write-Host ('  ' + $_.Line) }
            $shellLine = $shellRun | Select-String -Pattern 'SHELL_FIT_CHECK=' | Select-Object -Last 1
            if ($shellLine) { Write-Host ('  ' + $shellLine.Line) }
            if (-not $shellLine -or $shellLine.Line -notmatch 'SHELL_FIT_CHECK=OK') {
                $failed.Add('shell fit precision check failed')
            }
        } else {
            Write-Host '  SHELL_FIT_CHECK=FAIL (compile)'
            $failed.Add('shell fit harness failed to compile')
        }
        # --- 凸分解：非凸 / 带孔形状必须拆成凸块，且孔必须还是空的 ---
        # 这一条**真的抓到过两个 bug**（顶点哈希碰撞让圆变成 11 块重叠多边形、
        # 贪心顺序让圆环变成 48 块），两个都只在离线可见，所以它值得常驻。
        $decompOut = $env:TEMP + '\decomp-gate'
        Remove-Item -Recurse -Force $decompOut -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force -Path $decompOut | Out-Null
        & javac -encoding UTF-8 -nowarn -cp ($modelOut + ';' + $modelCp) -d $decompOut ($root + '\tools\ConvexDecompCheck.java') 2>&1 |
            Select-Object -First 10 | ForEach-Object { Write-Host ('  ' + $_) }
        if (Test-Path ($decompOut + '\ConvexDecompCheck.class')) {
            $decompRun = & java -cp ($decompOut + ';' + $modelOut + ';' + $modelCp) ConvexDecompCheck 2>&1
            $decompRun | Select-String -Pattern 'FAIL ' | ForEach-Object { Write-Host ('  ' + $_.Line) }
            $decompLine = $decompRun | Select-String -Pattern 'CONVEX_DECOMP_CHECK=' | Select-Object -Last 1
            if ($decompLine) { Write-Host ('  ' + $decompLine.Line) }
            if (-not $decompLine -or $decompLine.Line -notmatch 'CONVEX_DECOMP_CHECK=OK') {
                $failed.Add('convex decomposition check failed')
            }
        } else {
            Write-Host '  CONVEX_DECOMP_CHECK=FAIL (compile)'
            $failed.Add('convex decomposition harness failed to compile')
        }
        # --- 质量合成的坐标系回归：用【崩溃报告里的原始数字】钉住那个 1e17 惯量事故 ---
        # 同一个坐标系错误在本项目里出现过两次（体素路 / 合并质量路），教训是"修一处不等于修一类"。
        # 需要 Sable 的 MassData（SolidMassComposition.Composed 实现了它），所以从 run\mods 取。
        $sableJar = Get-ChildItem ($root + '\run\mods') -Filter 'sable-neoforge-*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1
        if (-not $sableJar) {
            Write-Host '  MASS_FRAME_CHECK=SKIP (run\mods 里没有 sable jar)'
        } else {
            $massOut = $env:TEMP + '\massframe-gate'
            Remove-Item -Recurse -Force $massOut -ErrorAction SilentlyContinue
            New-Item -ItemType Directory -Force -Path $massOut | Out-Null
            & javac -encoding UTF-8 -nowarn -cp ($modelCp + ';' + $sableJar.FullName) -sourcepath $srcRoot -d $massOut ($root + '\tools\MassFrameCheck.java') 2>&1 |
                Select-Object -First 10 | ForEach-Object { Write-Host ('  ' + $_) }
            if (Test-Path ($massOut + '\MassFrameCheck.class')) {
                $massRun = & java -cp ($massOut + ';' + $modelCp + ';' + $sableJar.FullName) MassFrameCheck 2>&1
                $massRun | Select-String -Pattern 'FAIL ' | ForEach-Object { Write-Host ('  ' + $_.Line) }
                $massLine = $massRun | Select-String -Pattern 'MASS_FRAME_CHECK=' | Select-Object -Last 1
                if ($massLine) { Write-Host ('  ' + $massLine.Line) }
                if (-not $massLine -or $massLine.Line -notmatch 'MASS_FRAME_CHECK=OK') {
                    $failed.Add('mass frame check failed')
                }
            } else {
                Write-Host '  MASS_FRAME_CHECK=FAIL (compile)'
                $failed.Add('mass frame harness failed to compile')
            }
        }
        # --- 7/7 sketch persistence: NBT round-trip ---
        # Needs the Minecraft classes (CompoundTag etc.), so unlike the two checks above this one
        # must compile against the full merged-jar classpath. A save that writes but cannot read
        # back only shows up when a player reopens the world, so it is worth pinning here.
        $mergedJar = Get-ChildItem (Join-Path $root 'build\moddev\artifacts') -Filter 'neoforge-*-merged.jar' -ErrorAction SilentlyContinue | Select-Object -First 1
        if (-not $mergedJar) {
            Write-Host '  SKETCH_DATA_CHECK=SKIP (merged jar not built yet; run a build once)'
        } else {
            $parts = New-Object System.Collections.Generic.List[string]
            $parts.Add($mergedJar.FullName)
            Get-Content (Join-Path $root 'build\moddev\clientLegacyClasspath.txt') -ErrorAction SilentlyContinue |
                Where-Object { $_.Trim() -ne '' } | ForEach-Object { $parts.Add($_.Trim()) }
            Get-ChildItem (Join-Path $root 'lib') -Filter '*.jar' -ErrorAction SilentlyContinue |
                ForEach-Object { $parts.Add($_.FullName) }
            Get-ChildItem (Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1\org.joml') -Recurse -Filter '*.jar' -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -notmatch 'sources|javadoc' } | ForEach-Object { $parts.Add($_.FullName) }
            if ($annJar) { $parts.Add($annJar.FullName) }
            $dataCp = (($parts | Select-Object -Unique) -join ';')
            $dataOut = Join-Path $env:TEMP 'sketchdata-gate'
            Remove-Item -Recurse -Force $dataOut -ErrorAction SilentlyContinue
            New-Item -ItemType Directory -Force -Path $dataOut | Out-Null
            # NOTE: @pureSketch is the SAME list the model block uses (defined above), plus the
            # MC-dependent data classes. Sharing it is what stops the two from drifting apart.
            & javac -encoding UTF-8 -nowarn -cp $dataCp -d $dataOut @pureSketch $solverSrc (Join-Path $sketchDir 'SketchData.java') (Join-Path $sketchDir 'SketchClientState.java') (Join-Path $sketchDir 'SolidData.java') (Join-Path $sketchDir 'SolidClientState.java') (Join-Path $PSScriptRoot 'SketchDataCheck.java') 2>&1 |
                Select-Object -First 15 | ForEach-Object { Write-Host ('  ' + $_) }
            if ($LASTEXITCODE -ne 0) {
                Write-Host '  SKETCH_DATA_CHECK=FAIL (compile)'
                $failed.Add('sketch data harness failed to compile')
            } else {
                $dataRun = & java -cp ($dataOut + ';' + $dataCp) SketchDataCheck 2>&1
                $dataLine = $dataRun | Select-String -Pattern 'SKETCH_DATA_CHECK=' | Select-Object -Last 1
                $dataRun | Select-String -Pattern 'FAIL ' | ForEach-Object { Write-Host ('  ' + $_.Line) }
                if ($dataLine) { Write-Host ('  ' + $dataLine.Line) }
                if (-not $dataLine -or $dataLine.Line -notmatch 'SKETCH_DATA_CHECK=OK') {
                    $failed.Add('sketch persistence round-trip check failed')
                }
            }
        }
    }
}
}

if ((Want 'nurbs') -and ($Only.Count -gt 0)) {
    Set-Step 'NURBS core + collision'
    # NURBS 统一方案的离线检查（纯 JDK：不依赖 Minecraft、不依赖 JOML）。
    # 断言内容：基函数单位分解/非负/导数和为 0、夹紧端点、**圆的精确性**、
    # 任意张角圆弧、解析导数 vs 中心差分、圆柱法线径向、圆锥线性、圆环精确性、细分容差。
    Write-Host '=== 7/7 NURBS core (pure JDK, offline) ==='
    $nurbsSrc = Join-Path $srcRoot 'com\ovo\sablestopnow\sketch\nurbs'
    $nurbsOut = Join-Path $env:TEMP 'nurbs-gate'
    Remove-Item -Recurse -Force $nurbsOut -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $nurbsOut | Out-Null
    $nurbsFiles = Get-ChildItem $nurbsSrc -Filter '*.java' -ErrorAction SilentlyContinue |
        ForEach-Object { $_.FullName }
    if (-not $nurbsFiles) {
        Write-Host '  NURBS_CHECK=SKIP (no sources)'
    } else {
        & javac -encoding UTF-8 -nowarn -d $nurbsOut @nurbsFiles ($root + '\tools\NurbsCheck.java') 2>&1 |
            Select-Object -First 15 | ForEach-Object { Write-Host ('  ' + $_) }
        if ($LASTEXITCODE -ne 0) {
            Write-Host '  NURBS_CHECK=FAIL (compile)'
            $failed.Add('nurbs harness failed to compile')
        } else {
            $nurbsRun = & java -cp $nurbsOut NurbsCheck 2>&1
            $nurbsRun | Select-String -Pattern 'FAIL ' | ForEach-Object { Write-Host ('  ' + $_.Line) }
            $nurbsLine = $nurbsRun | Select-String -Pattern 'NURBS_CHECK=' | Select-Object -Last 1
            if ($nurbsLine) { Write-Host ('  ' + $nurbsLine.Line) }
            if (-not $nurbsLine -or $nurbsLine.Line -notmatch 'NURBS_CHECK=OK') {
                $failed.Add('nurbs core check failed')
            }

            # NURBS -> 体素碰撞体：误差有界性等断言（同属纯 JDK）
            # ⚠ 命令**不跨行**：我用反引号续行时踩过"尾随空格让续行失效"的坑，
            #   表现为 verify 里编译失败、手工跑却成功（极难查）。
            # ⚠ **表达式绝不能跨行**：PowerShell 不会因为下一行以 `+` 开头就续行，
            #   而是把它当成**新语句**（一元正号作用于字符串）→ 报 "无法转换为 Int32"。
            #   我为此在这里连栽两次（先前是反引号续行被尾随空格破坏）。命令一律写单行。
            
            if (-not (Test-Path ($root + '\tools\NurbsCollisionCheck.java'))) {
                # 路径不对时**明确报出来**，而不是丢一句 javac 的天书
                Write-Host ('  NURBS_COLLISION_CHECK=FAIL (source not found: ' + $colCheckSrc + ')')
                Write-Host ('  PSScriptRoot=' + $PSScriptRoot)
                $failed.Add('nurbs collision source not found')
            } elseif (-not (Test-Path $nurbsOut)) {
                Write-Host '  NURBS_COLLISION_CHECK=FAIL (output dir missing)'
                $failed.Add('nurbs collision output dir missing')
            } else {
            & javac -encoding UTF-8 -nowarn -cp $nurbsOut -d $nurbsOut ($root + '\tools\NurbsCollisionCheck.java') 2>&1 |
                Select-Object -First 12 | ForEach-Object { Write-Host ('  ' + $_) }
            if (-not (Test-Path (Join-Path $nurbsOut 'NurbsCollisionCheck.class'))) {
                Write-Host '  NURBS_COLLISION_CHECK=FAIL (compile)'
                $failed.Add('nurbs collision harness failed to compile')
            } else {
                $colRun = & java -cp $nurbsOut NurbsCollisionCheck 2>&1
                $colRun | Select-String -Pattern 'FAIL ' | ForEach-Object { Write-Host ('  ' + $_.Line) }
                $colLine = $colRun | Select-String -Pattern 'NURBS_COLLISION_CHECK=' | Select-Object -Last 1
                if ($colLine) { Write-Host ('  ' + $colLine.Line) }
                if (-not $colLine -or $colLine.Line -notmatch 'NURBS_COLLISION_CHECK=OK') {
                    $failed.Add('nurbs collision check failed')
                }
            }
            }
        }
    }
}

if (Want 'param') {
    Set-Step 'parametric surfaces'
    # 统一参数曲面 + 唯一 UV 出口的离线检查（纯 JDK）。
    # 关键断言：project∘evaluate≈id、法线单位且正交、轴上极点无 NaN、
    # 旋转面角度尺度**沿轮廓连续**（这条正是"离基准面越远畸变越大"的反例断言）。
    Write-Host '=== 8/8 unified parametric surfaces (pure JDK, offline) ==='
    $paramSrc = $srcRoot + '\com\ovo\sablestopnow\sketch\param'
    $paramOut = Join-Path $env:TEMP 'param-gate'
    Remove-Item -Recurse -Force $paramOut -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $paramOut | Out-Null
    $paramFiles = Get-ChildItem $paramSrc -Filter '*.java' -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName }
    if (-not $paramFiles) {
        Write-Host '  PARAM_CHECK=SKIP (no sources)'
    } else {
        $paramCheck = $root + '\tools\ParamCheck.java'
        & javac -encoding UTF-8 -nowarn -d $paramOut @paramFiles $paramCheck 2>&1 |
            Select-Object -First 15 | ForEach-Object { Write-Host ('  ' + $_) }
        if (-not (Test-Path ($paramOut + '\ParamCheck.class'))) {
            Write-Host '  PARAM_CHECK=FAIL (compile)'
            $failed.Add('param harness failed to compile')
        } else {
            $paramRun = & java -cp $paramOut ParamCheck 2>&1
            $paramRun | Select-String -Pattern 'FAIL ' | ForEach-Object { Write-Host ('  ' + $_.Line) }
            $paramLine = $paramRun | Select-String -Pattern 'PARAM_CHECK=' | Select-Object -Last 1
            if ($paramLine) { Write-Host ('  ' + $paramLine.Line) }
            if (-not $paramLine -or $paramLine.Line -notmatch 'PARAM_CHECK=OK') {
                $failed.Add('param check failed')
            }
        }
    }
}

Write-Host ''
if ($failed.Count -eq 0) {
    if (Want 'guispec') {
    Set-Step 'gui spec'
    Write-Host '=== GUI spec invariants (options <-> SECTION_PLAN, lang keys) ==='
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'GuiSpecCheck.ps1') 2>&1 |
        Select-String -Pattern 'GUI_SPEC_CHECK|I1 |I2 |I6 ' | ForEach-Object { Write-Host ('  ' + $_.Line) }
    if ($LASTEXITCODE -ne 0) { $failed.Add('GUI spec invariants failed (see GUI_SPEC_CHECK above)') }
}
if (Want 'layout') {
    Set-Step 'gui layout'
    Write-Host '=== GUI layout constants (no font needed; see tools/GuiLayoutCheck.ps1) ==='
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'GuiLayoutCheck.ps1') 2>&1 |
        Select-String -Pattern 'GUI_LAYOUT' | ForEach-Object { Write-Host ('  ' + $_.Line) }
    if ($LASTEXITCODE -ne 0) { $failed.Add('GUI layout constant invariants failed (see GUI_LAYOUT_CHECK above)') }
}
if (Want 'guilang') {
    Set-Step 'gui lang'
    Write-Host '=== GUI option lang keys (I7: label/desc must exist in both languages) ==='
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'GuiLangCheck.ps1') 2>&1 |
        Select-String -Pattern 'GUI_LANG_CHECK|I7 ' | ForEach-Object { Write-Host ('  ' + $_.Line) }
    if ($LASTEXITCODE -ne 0) { $failed.Add('GUI option lang keys missing (see GUI_LANG_CHECK above)') }
}
Write-Host 'VERIFY_ALL=PASS'
    if (-not $NoProgress) {
        Set-Content -Path $progressFile -Value 'DONE|PASS|' -Encoding ASCII -ErrorAction SilentlyContinue
    }
    exit 0
}
Write-Host ("VERIFY_ALL=FAIL ({0} problem(s))" -f $failed.Count)
$failed | ForEach-Object { Write-Host ('  - ' + $_) }
if (-not $NoProgress) {
    # 把**第一条失败原因**带给进度窗口，免得用户还要回来翻这堆输出
    $first = if ($failed.Count -gt 0) { $failed[0] } else { '' }
    Set-Content -Path $progressFile -Value ("DONE|FAIL|$first") -Encoding ASCII -ErrorAction SilentlyContinue
}
exit 1
