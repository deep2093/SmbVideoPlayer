<#
.SYNOPSIS
  SkyWing Windows verification, shared by Claude Code and Codex. Policy: windows/TESTING-POLICY.md.

.DESCRIPTION
  focused  Maps changed files to the test classes that cover them and runs only those (default).
  full     Builds the packaged test image and runs the whole suite with native libmpv and UI automation.
  release  full, then packageExe/packageMsi and the payload, legal-notice, upgrade-identity and signature checks.

  -Image runs focused or full mode against an existing build, for example a CI artifact, instead of this checkout's
  createDistributable image: an app image folder, or an MSI that is unpacked read-only and never installed.

  Never reports PASS when a required prerequisite is missing, a selected test class did not run, any selected test
  was skipped, a test left temporary data or processes behind, the user's real SkyWing data changed, or the UI tests
  started SkyWing from an image other than the one under test.

  Verdicts and exit codes:
    0 PASS        every selected test ran and passed, isolation held, and no broader mode is required.
    0 NO-TESTS    only documentation or instructions changed; nothing to run.
    0 PLAN        -ListOnly: nothing was built or run.
    1 FAIL        a build, test, isolation or release-gate check failed.
    2 BLOCKED     a required prerequisite is missing, or desktop focus was not allowed.
    3 INCOMPLETE  focused tests passed, but the policy requires full or release mode for these changes.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File windows\tools\verify.ps1 -Mode focused -ListOnly
  powershell -NoProfile -ExecutionPolicy Bypass -File windows\tools\verify.ps1 -Mode focused -AllowDesktopFocus
  powershell -NoProfile -ExecutionPolicy Bypass -File windows\tools\verify.ps1 -Mode full -AllowDesktopFocus
  powershell -NoProfile -ExecutionPolicy Bypass -File windows\tools\verify.ps1 -Mode full -Image <folder>\SkyWing-<version>.msi -AllowDesktopFocus
#>
param(
    [ValidateSet('focused', 'full', 'release')][string]$Mode = 'focused',
    # Changed files, repository-relative. Default: uncommitted and untracked changes under windows/ and agent files.
    [string[]]$Paths,
    # Also include files changed since this ref (for example origin/main) in the default change set.
    [string]$Base,
    # Tests that open windows or go full screen take desktop focus. The user must be told first.
    [switch]$AllowDesktopFocus,
    # Folder holding libmpv-2.dll for native and UI tests. Default: the -Image's app\resources, then
    # SKYWING_TEST_MPV_DIR, then the payload that packaging ships.
    [string]$MpvDir,
    # An existing build to test instead of this checkout's createDistributable image: an app image folder (the one
    # holding SkyWing.exe) or an MSI, unpacked read-only into windows\build\verify-image (also with -ListOnly) and never
    # installed. The UI automation tests launch it; the other tests still run this checkout's code. Not with release.
    [string]$Image,
    # Print the plan and prerequisite status without building or running anything.
    [switch]$ListOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2
if ($Image -and $Mode -eq 'release') { throw '-Image tests an existing build, but release mode builds and checks this checkout''s installers. Use -Mode full or focused.' }
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$windows = Join-Path $repo 'windows'
$results = Join-Path $windows 'build\test-results\test'
$reportDir = Join-Path $windows 'build\reports\verification'
$started = Get-Date
$pkg = 'com.skywing.desktop'
$upgradeUuid = 'f40c59ef-e520-4e10-b7a2-b86e7458f923'

# ---------------------------------------------------------------------------------------------------------------
# Change-to-test map. Keep in step with the table in windows/TESTING-POLICY.md.
#   Escalate: a passing focused run is not enough; the policy requires full mode afterwards.
#   Release:  packaging, native payload or legal inputs; the policy requires release mode before shipping.
# ---------------------------------------------------------------------------------------------------------------
$rules = @(
    @{ Area = 'Playback / libmpv'; Escalate = $true
       Pattern = 'windows/src/main/kotlin/com/skywing/desktop/(playback/(EmbeddedMpv|GpuOverride|LocalPlayback|ThumbnailExtractor)\.kt|PlayerPage\.kt|PlaybackProblemDialog\.kt|LocalVideoDropHandler\.kt)$'
       Tests = @("$pkg.playback.EmbeddedPlaybackNativeTest", "$pkg.automation.PlaybackAutomationTest") }
    @{ Area = 'Motion interpolation / smoothing'; Escalate = $false
       Pattern = 'windows/src/main/kotlin/com/skywing/desktop/(playback/(Motion\w*|Smoothing|RecentDrops|DisplayVrr|WindowsDisplayAdapter)\.kt|SmoothingDialog\.kt)$|windows/tools/(build_motion\.py|open-svpflow-windows\.patch)$'
       Tests = @("$pkg.playback.MotionEngineTest", "$pkg.playback.SmoothingTest", "$pkg.playback.MotionNativeTest", "$pkg.automation.MotionAutomationTest") }
    @{ Area = 'Full screen / window chrome'; Escalate = $true
       Pattern = 'windows/src/main/kotlin/com/skywing/desktop/(BorderlessFullscreen|FullscreenChrome|PlayerChrome|WindowChrome)\.kt$'
       Tests = @("$pkg.FullscreenWindowTest", "$pkg.PlayerChromeTest", "$pkg.automation.FullscreenAutomationTest") }
    @{ Area = 'App shell (Main.kt)'; Escalate = $true
       Pattern = 'windows/src/main/kotlin/com/skywing/desktop/Main\.kt$'
       Tests = @("$pkg.FullscreenWindowTest", "$pkg.PlayerChromeTest", "$pkg.HubBackNavigationTest", "$pkg.playback.EmbeddedPlaybackNativeTest",
                 "$pkg.automation.PlaybackAutomationTest", "$pkg.automation.FullscreenAutomationTest") }
    @{ Area = 'Accessibility / shared UI'; Escalate = $false
       Pattern = 'windows/src/main/kotlin/com/skywing/desktop/(SkyUi|SkyTheme|AdaptiveTheme|SkyIcons|GridSelection)\.kt$'
       Tests = @("$pkg.AccessibilitySemanticsTest", "$pkg.SkyUiLogicTest", "$pkg.AccessBridgeTreeTest", "$pkg.automation.SkyWingAutomationTest") }
    @{ Area = 'Library'; Escalate = $false
       Pattern = 'windows/src/main/kotlin/com/skywing/desktop/(LibraryModel|LibrarySync|LocalFolderLibrary|WindowsLibraryData|VideosScreen|VideoViews|FoldersScreen|FolderOrganizer|FolderPickers|Thumbnails|Covers|VideoRename|ChangeCoverDialog)\.kt$'
       Tests = @("$pkg.FolderOrganizerTest", "$pkg.WindowsMediaIndexScaleTest", "$pkg.HubBackNavigationTest", "$pkg.automation.LibraryAutomationTest", "$pkg.automation.SkyWingAutomationTest") }
    @{ Area = 'SMB / network shares'; Escalate = $false
       Pattern = 'windows/src/main/kotlin/com/skywing/desktop/(ShareHosts|WindowsShareBrowser|WindowsShareProfileStore|FilesScreen)\.kt$'
       Tests = @("$pkg.WindowsShareBrowserTest", "$pkg.PortSettingsTest", "$pkg.automation.NetworkAndStabilityAutomationTest") }
    @{ Area = 'Settings'; Escalate = $false
       Pattern = 'windows/src/main/kotlin/com/skywing/desktop/(SettingsScreen|ArtworkSettingsPage)\.kt$'
       Tests = @("$pkg.HubBackNavigationTest", "$pkg.automation.SkyWingAutomationTest", "$pkg.automation.LibraryAutomationTest") }
    @{ Area = 'Artwork / matching'; Escalate = $false
       Pattern = 'windows/src/main/kotlin/com/skywing/desktop/(artwork/.*|matching/.*|EmbeddedGoogleImages)\.kt$'
       Tests = @("$pkg.ArtworkTest", "$pkg.matching.PerformerMatcherTest") }
    @{ Area = 'UI automation framework'; Escalate = $true
       Pattern = 'windows/src/test/(kotlin/com/skywing/desktop/automation/(SkyWingTestApp|AccessBridgeClient|ControlQuery|TestMedia)\.kt|java/.*)$'
       Tests = @() }
    @{ Area = 'Installer / packaging / native payload / legal'; Escalate = $true; Release = $true
       Pattern = '^(windows/build\.gradle\.kts|windows/legal/.*|windows/LICENSE|windows/tools/build_mpv_preview\.sh|windows/tools/mpv-vapoursynth-output-preview\.patch|\.github/workflows/skywing-windows-x64\.yml|windows/tools/(compare_builds|test_compare_builds)\.py|gradle/.*|gradlew(\.bat)?)$'
       Tests = @() }
)
# Python build tools have their own unittest suite (windows/tools/test_*.py), run with the system Python.
$toolTestsPattern = '^windows/tools/[^/]+\.py$'
# Tests that open windows, go full screen or launch SkyWing. Everything else runs off screen.
$focusTests = '^com\.skywing\.desktop\.(automation\..*|FullscreenWindowTest|AccessBridgeTreeTest|playback\.EmbeddedPlaybackNativeTest|playback\.MotionNativeTest)$'
$nativeTests = '^com\.skywing\.desktop\.(automation\..*|FullscreenWindowTest|playback\.EmbeddedPlaybackNativeTest|playback\.MotionNativeTest)$'
$ffmpegTests = '^com\.skywing\.desktop\.(automation\.MotionAutomationTest|playback\.EmbeddedPlaybackNativeTest)$'
$notMeasured = 'HDR output and tone mapping, VRR activation, displayed frame pacing (the engine reports its configured rate), ' +
    'multi-monitor, real Alt+Tab, a real NAS or a dropped SMB connection, fresh install and uninstall on a clean PC.'

# ---------------------------------------------------------------------------------------------------------------
function Get-ChangedPaths {
    Push-Location $repo
    # git writes warnings (for example about line endings) to stderr; they must not stop the script.
    $previous = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try {
        $list = @(git -c core.safecrlf=false diff --name-only HEAD 2>$null) + @(git ls-files --others --exclude-standard 2>$null)
        if ($Base) { $list += @(git diff --name-only "$Base...HEAD" 2>$null) }
    } finally { $ErrorActionPreference = $previous; Pop-Location }
    $list = @($list | Where-Object { $_ -is [string] })
    # Untracked folders are listed file by file; keep only the Windows app and agent instruction files.
    $list | ForEach-Object { $_ -replace '\\', '/' } |
        Where-Object { $_ -match '^(windows/|\.github/workflows/skywing-windows|AGENTS\.md|CLAUDE\.md|\.agents/|\.claude/skills/|gradle/|gradlew)' } |
        Sort-Object -Unique
}

function Select-Tests([string[]]$changed) {
    $plan = [ordered]@{ Areas = @(); Tests = New-Object System.Collections.Generic.SortedSet[string]; Escalate = @(); Release = $false; Unmapped = @(); DocsOnly = @() }
    foreach ($path in $changed) {
        $hit = $false
        foreach ($rule in $rules) {
            if ($path -match $rule.Pattern) {
                $hit = $true
                if ($plan.Areas -notcontains $rule.Area) { $plan.Areas += $rule.Area }
                $rule.Tests | ForEach-Object { [void]$plan.Tests.Add($_) }
                if ($rule.Escalate -and $plan.Escalate -notcontains $rule.Area) { $plan.Escalate += $rule.Area }
                if ($rule.ContainsKey('Release') -and $rule.Release) { $plan.Release = $true }
            }
        }
        if ($path -match '^windows/src/test/(kotlin|java)/(.+)\.(kt|java)$') {
            $class = ($Matches[2] -replace '/', '.')
            if ($class -match 'Test$') { [void]$plan.Tests.Add($class); $hit = $true }
            else {
                # A test helper: run every test class that uses it.
                $helper = [IO.Path]::GetFileNameWithoutExtension($path)
                Get-ChildItem (Join-Path $windows 'src\test\kotlin') -Recurse -Filter '*Test.kt' |
                    Where-Object { Select-String -LiteralPath $_.FullName -Pattern "\b$helper\b" -Quiet } | ForEach-Object {
                        [void]$plan.Tests.Add(($_.FullName.Substring((Join-Path $windows 'src\test\kotlin\').Length) -replace '\\', '.' -replace '\.kt$', ''))
                        $hit = $true }
            }
        }
        if (-not $hit) {
            if ($path -match '\.(md|txt|png|jpg|svg)$' -or $path -match '^(\.agents|\.claude/skills)/' -or $path -match '^(AGENTS|CLAUDE)\.md$') { $plan.DocsOnly += $path }
            elseif ($path -match '^windows/tools/verify\.ps1$') { $plan.DocsOnly += $path }
            else { $plan.Unmapped += $path }
        }
    }
    # Unmapped production code, or a build-wide change with no test of its own: run every off-screen unit test and
    # require a broader mode afterwards.
    $unmappedCode = [bool]($plan.Unmapped | Where-Object { $_ -match '^windows/src/main/' })
    if ($unmappedCode) { $plan.Escalate += 'Unmapped production code' }
    if ($plan.Unmapped -and -not $unmappedCode -and $plan.Escalate -notcontains 'Unmapped files') { $plan.Escalate += 'Unmapped files' }
    if ($unmappedCode -or ($plan.Escalate -and $plan.Tests.Count -eq 0)) {
        Get-ChildItem (Join-Path $windows 'src\test\kotlin') -Recurse -Filter '*Test.kt' | ForEach-Object {
            $class = ($_.FullName.Substring((Join-Path $windows 'src\test\kotlin\').Length) -replace '\\', '.' -replace '\.kt$', '')
            if ($class -notmatch $focusTests) { [void]$plan.Tests.Add($class) }
        }
    }
    if ($plan.Areas.Count -ge 3 -and $plan.Escalate -notcontains 'Three or more areas') { $plan.Escalate += 'Three or more areas' }
    # Any run that executes tests also proves the test JVM cannot reach the user's preferences or data folders.
    if ($plan.Tests.Count -gt 0) { [void]$plan.Tests.Add("$pkg.TestIsolationTest") }
    $plan
}

function Test-Prerequisites([string[]]$tests, [string]$mode, [string]$mpv) {
    $needNative = ($mode -ne 'focused') -or [bool]($tests | Where-Object { $_ -match $nativeTests })
    $needFfmpeg = ($mode -ne 'focused') -or [bool]($tests | Where-Object { $_ -match $ffmpegTests })
    $needLoopback = ($mode -ne 'focused') -or ($tests -contains "$pkg.automation.NetworkAndStabilityAutomationTest")
    $checks = @()
    $checks += [pscustomobject]@{ Name = 'Windows x64'; Required = $true; Ok = ($env:PROCESSOR_ARCHITECTURE -eq 'AMD64'); Detail = $env:PROCESSOR_ARCHITECTURE }
    $javaExe = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
    # java -version writes to stderr; Windows PowerShell turns that into an error record, so read it through cmd.
    $javaVersion = try { (cmd.exe /c "`"$javaExe`" -version 2>&1" | Select-Object -First 1) } catch { $null }
    if (-not $javaVersion) { $javaVersion = 'not found' }
    $checks += [pscustomobject]@{ Name = 'JDK 17 (JAVA_HOME or PATH)'; Required = $true; Ok = ($javaVersion -match 'version "17\.'); Detail = $javaVersion }
    $checks += [pscustomobject]@{ Name = 'Interactive desktop session'; Required = $needNative; Ok = [Environment]::UserInteractive; Detail = '' }
    $dll = if ($mpv) { Join-Path $mpv 'libmpv-2.dll' } else { '' }
    $checks += [pscustomobject]@{ Name = 'libmpv-2.dll (SKYWING_TEST_MPV_DIR)'; Required = $needNative; Ok = ($dll -and (Test-Path -LiteralPath $dll)); Detail = $mpv }
    $vulkan = (Test-Path "$env:WINDIR\System32\vulkan-1.dll") -or ($mpv -and (Test-Path -LiteralPath (Join-Path $mpv 'vulkan-1.dll')))
    $checks += [pscustomobject]@{ Name = 'Vulkan loader (libmpv imports vulkan-1.dll)'; Required = $needNative; Ok = $vulkan; Detail = 'System32 or the libmpv folder' }
    $ffmpeg = Get-Command ffmpeg -ErrorAction SilentlyContinue
    $checks += [pscustomobject]@{ Name = 'ffmpeg on PATH (multi-track MKV and 24 fps fixtures)'; Required = $needFfmpeg; Ok = [bool]$ffmpeg; Detail = $(if ($ffmpeg) { $ffmpeg.Source } else { '' }) }
    $loopback = Test-Path '\\localhost\C$\Windows' -ErrorAction SilentlyContinue
    $checks += [pscustomobject]@{ Name = 'Loopback admin share \\localhost\C$ (read-only playback)'; Required = $needLoopback; Ok = $loopback; Detail = '' }
    if ($Image) {
        $checks += [pscustomobject]@{ Name = 'App image to test (-Image) with SkyWing.exe'; Required = $true
            Ok = [bool]($imageDir -and (Test-Path -LiteralPath (Join-Path $imageDir 'SkyWing.exe'))); Detail = $(if ($imageProblem) { $imageProblem } else { $imageDir }) }
    }
    $a = Get-ChildItem (Join-Path $repo '.agents\skills') -Recurse -File -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName.Substring($repo.Length + 8) + ':' + (Get-FileHash $_.FullName).Hash }
    $c = Get-ChildItem (Join-Path $repo '.claude\skills') -Recurse -File -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName.Substring($repo.Length + 8) + ':' + (Get-FileHash $_.FullName).Hash }
    $inSync = -not (Compare-Object @($a) @($c))
    $checks += [pscustomobject]@{ Name = '.claude/skills identical to .agents/skills'; Required = $true; Ok = $inSync; Detail = $(if ($inSync) { '' } else { 'copy .agents/skills over .claude/skills' }) }
    # Codex reads AGENTS.md; Claude Code reads CLAUDE.md, which must import it. Both must lead to this policy and runner.
    $read = { param($f) $p = Join-Path $repo $f; if (Test-Path -LiteralPath $p) { Get-Content -LiteralPath $p -Raw } else { '' } }
    $agents = & $read 'AGENTS.md'; $claude = & $read 'CLAUDE.md'
    $shared = ($claude -match '(?m)^@AGENTS\.md\s*$') -and $agents.Contains('windows/TESTING-POLICY.md') -and $agents.Contains('verify.ps1')
    $checks += [pscustomobject]@{ Name = 'Shared instructions: CLAUDE.md imports AGENTS.md, which points to TESTING-POLICY.md and verify.ps1'; Required = $true; Ok = $shared; Detail = $(if ($shared) { '' } else { 'restore the @AGENTS.md import or the AGENTS.md verification contract' }) }
    $checks
}

# The user's real data: Java preferences in HKCU and the library cache. Only hashes are kept, never the contents.
function Get-UserDataFingerprint {
    $tmp = Join-Path $env:TEMP ("verify-prefs-" + [guid]::NewGuid() + '.reg')
    try {
        & reg.exe export 'HKCU\Software\JavaSoft\Prefs\com\skywing' $tmp /y 2>$null | Out-Null
        $prefs = if (Test-Path $tmp) { (Get-FileHash $tmp).Hash } else { 'absent' }
    } finally { Remove-Item $tmp -Force -ErrorAction SilentlyContinue }
    $lib = Join-Path $env:LOCALAPPDATA 'SkyWing'
    $files = if (Test-Path $lib) {
        $lines = Get-ChildItem $lib -Recurse -File -Force | ForEach-Object { "$($_.FullName)|$($_.Length)|$($_.LastWriteTimeUtc.Ticks)" }
        $bytes = [Text.Encoding]::UTF8.GetBytes(($lines -join "`n"))
        [BitConverter]::ToString((New-Object Security.Cryptography.SHA256Managed).ComputeHash($bytes)) -replace '-', ''
    } else { 'absent' }
    [pscustomobject]@{ Prefs = $prefs; Library = $files }
}

function Get-Leftovers {
    $dirs = @(Get-ChildItem $env:TEMP -Directory -Filter 'skywing-*' -ErrorAction SilentlyContinue | ForEach-Object Name)
    $procs = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object { $_.ExecutablePath -and $_.ExecutablePath.StartsWith("$testImage\", [StringComparison]::OrdinalIgnoreCase) } | ForEach-Object ProcessId)
    [pscustomobject]@{ Dirs = $dirs; Processes = $procs }
}

# Records every SkyWing.exe that starts while the tests run, to prove which image the UI automation tests launched.
# SkyWing processes that were already running, such as the user's own, are not counted.
function Start-LaunchWatch {
    Start-Job -ArgumentList (Get-Date) -ScriptBlock {
        param([datetime]$since)
        $seen = @{}
        while ($true) {
            foreach ($p in @(Get-CimInstance Win32_Process -Filter "Name = 'SkyWing.exe'" -ErrorAction SilentlyContinue)) {
                $key = "$($p.ProcessId)|$($p.CreationDate)"
                if ($p.ExecutablePath -and $p.CreationDate -ge $since -and -not $seen.ContainsKey($key)) { $seen[$key] = $true; $p.ExecutablePath }
            }
            Start-Sleep -Milliseconds 500
        }
    }
}

function Stop-LaunchWatch($job) {
    Stop-Job $job
    $paths = @(Receive-Job $job -ErrorAction SilentlyContinue | ForEach-Object { "$_" })
    Remove-Job $job -Force
    $paths
}

function Invoke-Gradle([string[]]$gradleArgs) {
    $line = '.\gradlew.bat -p windows ' + ($gradleArgs -join ' ')
    Write-Host ">> $line"
    $script:commands += $line
    Push-Location $repo
    # Gradle and the tests write warnings to stderr; they must not stop the script.
    $previous = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { cmd.exe /c ".\gradlew.bat -p windows $($gradleArgs -join ' ') 2>&1" | Where-Object { $_ -notmatch '^(w: |open-svpflow: GPU renderer active)' } | Write-Host; $code = $LASTEXITCODE }
    finally { $ErrorActionPreference = $previous; Pop-Location }
    $code
}

function Read-Results([string[]]$expected) {
    $suites = @()
    Get-ChildItem $results -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue | Where-Object { $_.LastWriteTime -ge $started } | ForEach-Object {
        [xml]$x = Get-Content -LiteralPath $_.FullName -Raw
        $s = $x.testsuite
        $skipped = @($s.testcase | Where-Object { $_.SelectSingleNode('skipped') } | ForEach-Object { $_.name })
        $failed = @($s.testcase | Where-Object { $_.SelectSingleNode('failure') -or $_.SelectSingleNode('error') } | ForEach-Object {
            $node = $_.SelectSingleNode('failure'); if (-not $node) { $node = $_.SelectSingleNode('error') }
            $msg = ($node.GetAttribute('message') -split "`n")[0]; "$($_.name): " + $msg.Substring(0, [Math]::Min(200, $msg.Length)) })
        $suites += [pscustomobject]@{ Class = $s.name; Tests = [int]$s.tests; Failures = [int]$s.failures + [int]$s.errors; Skipped = [int]$s.skipped
                                      SkippedNames = $skipped; FailedNames = $failed; Seconds = [double]$s.time }
    }
    $missing = @($expected | Where-Object { $e = $_; -not ($suites | Where-Object { $_.Class -eq $e }) })
    [pscustomobject]@{ Suites = $suites; Missing = $missing }
}

# packageMsi packs its own copy of the app image, so prove it holds what the tests ran against: extract the MSI with
# WiX dark.exe (read-only) and compare every file by SHA-256. The Compose plugin regenerates a manifest-only
# skiko-awt-runtime jar for each package, with new timestamps and therefore a new content-hashed name, and SkyWing.cfg
# lists that name. A jar whose name differs only in that hash counts as equivalent when its entries are identical, and
# SkyWing.cfg when it is identical with the jar hashes masked. Anything else is a difference.
function Compare-MsiToImage([string]$msiPath, [string]$imageDir) {
    $dark = Join-Path $windows 'build\wix311\dark.exe'
    if (-not (Test-Path -LiteralPath $dark)) { return $null }
    $out = Join-Path $windows 'build\verification\msi-content'
    Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue
    try {
        $wxs = Join-Path $out 'product.wxs'
        cmd.exe /c "`"$dark`" -nologo -x `"$out`" -o `"$wxs`" `"$msiPath`" >nul 2>&1"
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $wxs)) { return [pscustomobject]@{ Identical = 0; Equivalent = 0; Differences = @("dark.exe exited $LASTEXITCODE") } }
        [xml]$x = Get-Content -LiteralPath $wxs -Raw
        $file = { param($name, $path) [pscustomobject]@{ Name = $name; Path = $path; Key = "$name|$((Get-FileHash -LiteralPath $path).Hash)" } }
        $source = { param($s) if ([IO.Path]::IsPathRooted($s)) { $s } else { Join-Path $out ($s -replace '^SourceDir\\', '') } }
        $packed = @($x.GetElementsByTagName('File') | ForEach-Object { & $file $_.GetAttribute('Name') (& $source $_.GetAttribute('Source')) })
        $tested = @(Get-ChildItem -LiteralPath $imageDir -Recurse -File | Where-Object { $_.Name -ne '.jpackage.xml' } | ForEach-Object { & $file $_.Name $_.FullName })
        $delta = @(Compare-Object $tested $packed -Property Key -PassThru)
        $onlyTested = @($delta | Where-Object { $_.SideIndicator -eq '<=' })
        $onlyPacked = [Collections.ArrayList]@($delta | Where-Object { $_.SideIndicator -eq '=>' })
        $hashed = '-[0-9a-f]{28,32}(?=\.jar\b)'
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $entries = { param($jar) $zip = [IO.Compression.ZipFile]::OpenRead($jar)
            try { @($zip.Entries | ForEach-Object { $s = $_.Open(); try { "$($_.FullName)|" + [BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash($s)) } finally { $s.Dispose() } }) -join "`n" }
            finally { $zip.Dispose() } }
        $equivalent = 0; $differences = @()
        foreach ($t in $onlyTested) {
            $p = $onlyPacked | Where-Object { ($_.Name -replace $hashed) -eq ($t.Name -replace $hashed) } | Select-Object -First 1
            $same = $false
            if ($p -and $t.Name -match $hashed) { $same = (& $entries $t.Path) -eq (& $entries $p.Path) }
            elseif ($p -and $t.Name -eq 'SkyWing.cfg') { $same = ((Get-Content -LiteralPath $t.Path -Raw) -replace $hashed) -eq ((Get-Content -LiteralPath $p.Path -Raw) -replace $hashed) }
            if ($same) { $equivalent++; $onlyPacked.Remove($p) } else { $differences += "$($t.Name) (tested image)" }
        }
        $differences += @($onlyPacked | ForEach-Object { "$($_.Name) (MSI only)" })
        [pscustomobject]@{ Identical = $tested.Count - $onlyTested.Count; Equivalent = $equivalent; Differences = $differences }
    } finally { Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue }
}

# -Image with an MSI: unpack it with WiX dark.exe (read-only; nothing is installed) and lay its files out as Windows
# Installer would, so the UI automation tests can launch the build it holds. Returns the folder holding SkyWing.exe.
function Expand-MsiImage([string]$msiPath, [string]$dest) {
    Remove-Item -LiteralPath $dest -Recurse -Force -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath $dest) { throw "could not clear $dest; close any SkyWing started from it" }
    $raw = Join-Path $dest 'unpacked'
    [void][IO.Directory]::CreateDirectory($raw)
    $wxs = Join-Path $raw 'product.wxs'
    $log = @(cmd.exe /c "`"$(Join-Path $windows 'build\wix311\dark.exe')`" -nologo -x `"$raw`" -o `"$wxs`" `"$msiPath`" 2>&1")
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $wxs)) { throw "dark.exe exited $LASTEXITCODE $(@($log | Where-Object { "$_".Trim() }) | Select-Object -Last 1)" }
    [xml]$x = Get-Content -LiteralPath $wxs -Raw
    # The install path of each Directory, from its Name and its parent Directory or DirectoryRef.
    $dirs = @{}
    foreach ($d in $x.GetElementsByTagName('Directory')) {
        $parent = $d.ParentNode
        $dirs[$d.GetAttribute('Id')] = @{ Name = $d.GetAttribute('Name')
            Parent = $(if ($parent.LocalName -eq 'Directory' -or $parent.LocalName -eq 'DirectoryRef') { $parent.GetAttribute('Id') } else { $null }) }
    }
    $pathOf = { param($id)
        $parts = @()
        while ($id -and $dirs.ContainsKey($id)) { $name = $dirs[$id].Name; if ($name -and $name -ne 'SourceDir') { $parts = @($name) + $parts }; $id = $dirs[$id].Parent }
        $parts -join '\' }
    $files = @(foreach ($c in $x.GetElementsByTagName('Component')) {
        $dir = if ($c.GetAttribute('Directory')) { $c.GetAttribute('Directory') } else { $c.ParentNode.GetAttribute('Id') }
        foreach ($f in $c.GetElementsByTagName('File')) {
            $s = $f.GetAttribute('Source')
            [pscustomobject]@{ Target = "$(& $pathOf $dir)\$($f.GetAttribute('Name'))"
                               Source = $(if ([IO.Path]::IsPathRooted($s)) { $s } else { Join-Path $raw ($s -replace '^SourceDir\\', '') }) }
        }
    })
    $exe = @($files | Where-Object { $_.Target -match '\\SkyWing\.exe$' })
    if ($exe.Count -ne 1) { throw "it holds $($exe.Count) SkyWing.exe files" }
    $root = $exe[0].Target -replace 'SkyWing\.exe$', ''
    $image = Join-Path $dest 'SkyWing'
    foreach ($f in @($files | Where-Object { $_.Target.StartsWith($root, [StringComparison]::OrdinalIgnoreCase) })) {
        # The names come from the MSI, so make sure none of them leads out of the image folder.
        $target = [IO.Path]::GetFullPath((Join-Path $image $f.Target.Substring($root.Length)))
        if (-not $target.StartsWith("$image\", [StringComparison]::OrdinalIgnoreCase)) { throw "it names a file outside the app folder: $($f.Target)" }
        [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target))
        [IO.File]::Copy($f.Source, $target, $true)
    }
    Remove-Item -LiteralPath $raw -Recurse -Force
    $image
}

# The build an app image holds: skywing-build.properties (version, commit, localChanges, runtime, watchdogFix) from
# one of its jars, or $null.
function Get-ImageBuild([string]$imageDir) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    foreach ($jar in @(Get-ChildItem -LiteralPath (Join-Path $imageDir 'app') -Filter '*.jar' -File -ErrorAction SilentlyContinue)) {
        $zip = [IO.Compression.ZipFile]::OpenRead($jar.FullName)
        try {
            $entry = $zip.GetEntry('skywing-build.properties')
            if (-not $entry) { continue }
            $reader = New-Object IO.StreamReader($entry.Open())
            try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
            $props = @{}
            foreach ($line in $text -split "`r?`n") { if ($line -match '^([^#=]+)=(.*)$') { $props[$Matches[1].Trim()] = $Matches[2].Trim() } }
            return $props
        } finally { $zip.Dispose() }
    }
    $null
}

# The off-screen and native tests run this checkout's code, not the image's. Says whether that is the same app code:
# the image's commit against this working tree, untracked files included.
function Get-SourceMatch([string]$commit) {
    # The commit comes from the image, so it reaches git only when it is a plain commit id.
    if ($commit -notmatch '^[0-9a-fA-F]{7,40}$') { return "no valid commit is recorded, so whether this checkout's app code matches it is unknown" }
    Push-Location $repo
    $previous = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try {
        $files = @(git diff --name-only $commit -- windows/src/main 2>$null); $code = $LASTEXITCODE
        $files += @(git ls-files --others --exclude-standard -- windows/src/main 2>$null)
    } finally { $ErrorActionPreference = $previous; Pop-Location }
    if ($code -ne 0) { "commit $commit is not in this checkout, so the in-process tests ran this checkout's app code, not the image's" }
    elseif ($files.Count -eq 0) { "this checkout's app code (windows/src/main) is the same, so the in-process tests ran it too" }
    else { "this checkout's app code (windows/src/main) differs in $($files.Count) files, so the in-process tests ran this checkout's code, not the image's" }
}

function Test-ReleasePayload {
    $out = @()
    $res = Join-Path $windows 'build\compose\binaries\main\app\SkyWing\app\resources'
    $legal = Join-Path $res 'legal'
    # Third argument: $false = must pass, $true = public-release blocker, 'warn' = risk to report (does not change the verdict).
    $add = { param($ok, $what, $level) $warn = $level -is [string]
             $script:payload += [pscustomobject]@{ Ok = [bool]$ok; Check = $what; Blocker = (-not $warn -and [bool]$level); Warn = $warn } }
    $script:payload = @()
    $required = 'README.txt', 'SkyWing-Windows-GPL-3.0.txt', 'licenses/GPL-3.0.txt', 'upstream/mpv-Copyright.txt', 'upstream/FFmpeg-LICENSE.md',
        'upstream/skiko-NOTICE.txt', 'upstream/Skia-LICENSE.txt', 'JVM-RUNTIME-INVENTORY.tsv', 'NATIVE-PROVENANCE.json', 'SOURCE-STATUS.md',
        'SKYWING-SOURCE.txt', 'java-runtime/BUILD-RELEASE.txt', 'upstream/Python-LICENSE.txt', 'upstream/VapourSynth-COPYING.LESSER',
        'upstream/open-svpflow-LICENSE.txt', 'upstream/VapourSynth-font-OFL.txt'
    $missing = @($required | Where-Object { $p = Join-Path $legal $_; -not (Test-Path -LiteralPath $p) -or (Get-Item -LiteralPath $p).Length -eq 0 })
    & $add (-not $missing) "Legal notices present ($($required.Count - $missing.Count)/$($required.Count))" $false
    $inventory = @(Import-Csv (Join-Path $legal 'JVM-RUNTIME-INVENTORY.tsv') -Delimiter "`t")
    & $add ($inventory.Count -ge 10) "JVM runtime inventory ($($inventory.Count) rows)" $false
    & $add ([bool](Get-ChildItem (Join-Path $legal 'jvm') -Recurse -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -match 'LICENSE|NOTICE|COPYING' })) 'Embedded JVM notices preserved' $false
    $native = Get-Content (Join-Path $legal 'NATIVE-PROVENANCE.json') -Raw | ConvertFrom-Json
    $dll = Join-Path $res 'libmpv-2.dll'
    $manifestPath = Join-Path $res 'MPV-PREVIEW-MANIFEST.json'
    if (Test-Path -LiteralPath $manifestPath) {
        $preview = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
        $bad = @($preview.files.PSObject.Properties | Where-Object { $f = Join-Path $res $_.Name; -not (Test-Path -LiteralPath $f) -or (Get-FileHash -LiteralPath $f).Hash.ToLowerInvariant() -ne $_.Value })
        & $add ($preview.mpvRevision -eq $native.mpvRevision -and -not $bad) "Experimental mpv payload matches MPV-PREVIEW-MANIFEST.json ($($bad.Count) mismatches)" $false
    } else {
        & $add ((Get-FileHash -LiteralPath $dll).Hash.ToLowerInvariant() -eq $native.dllSha256) 'libmpv-2.dll SHA-256 matches NATIVE-PROVENANCE.json' $false
        & $add (Test-Path -LiteralPath (Join-Path $res 'vulkan-1.dll')) 'Vulkan loader bundled (libmpv imports vulkan-1.dll; not shipped by design, see legal/SOURCE-STATUS.md, so a PC without a GPU driver that installs it cannot load libmpv)' 'warn'
    }
    $motion = Join-Path $res 'motion\BUILD-MANIFEST.json'
    if (Test-Path -LiteralPath $motion) {
        $data = Get-Content -LiteralPath $motion -Raw | ConvertFrom-Json
        $bad = @($data.files.PSObject.Properties | Where-Object { $f = Join-Path (Split-Path $motion) $_.Name; -not (Test-Path -LiteralPath $f) -or (Get-FileHash -LiteralPath $f).Hash.ToLowerInvariant() -ne $_.Value })
        & $add (-not $bad) "Motion payload matches BUILD-MANIFEST.json ($($bad.Count) mismatches)" $false
        & $add (-not $data.localPluginOverride) 'Motion plugins built by the pinned pipeline (no local plugin override)' $true
    } else { & $add $false 'Motion BUILD-MANIFEST.json present' $false }
    $gradle = Get-Content (Join-Path $windows 'build.gradle.kts') -Raw
    & $add ($gradle -match [regex]::Escape("upgradeUuid = `"$upgradeUuid`"")) "build.gradle.kts keeps MSI upgradeUuid $upgradeUuid" $false
    # The packages for the version this build produces (build.gradle.kts: 0.1.$GITHUB_RUN_NUMBER, default 0.1.1), built by this run.
    $version = if ($env:GITHUB_RUN_NUMBER) { "0.1.$($env:GITHUB_RUN_NUMBER)" } else { '0.1.1' }
    $msi = Get-Item -LiteralPath (Join-Path $windows "build\compose\binaries\main\msi\SkyWing-$version.msi") -ErrorAction SilentlyContinue
    $exe = Get-Item -LiteralPath (Join-Path $windows "build\compose\binaries\main\exe\SkyWing-$version.exe") -ErrorAction SilentlyContinue
    $image = Join-Path $windows 'build\compose\binaries\main\app\SkyWing'
    foreach ($p in @(@{ F = $msi; N = "SkyWing-$version.msi" }, @{ F = $exe; N = "SkyWing-$version.exe" })) {
        & $add ($p.F -and $p.F.LastWriteTime -ge $started) "$($p.N) built by this run" $false
    }
    if ($msi) {
        $installer = New-Object -ComObject WindowsInstaller.Installer
        $db = $installer.GetType().InvokeMember('OpenDatabase', 'InvokeMethod', $null, $installer, @($msi.FullName, 0))
        $rows = { param($sql, $cols)   # MSI SQL: quote table and column names with backticks; Extension, Key and Value are reserved words
            $view = $db.GetType().InvokeMember('OpenView', 'InvokeMethod', $null, $db, @($sql))
            [void]$view.GetType().InvokeMember('Execute', 'InvokeMethod', $null, $view, $null)
            while ($row = $view.GetType().InvokeMember('Fetch', 'InvokeMethod', $null, $view, $null)) {
                , @(1..$cols | ForEach-Object { $row.GetType().InvokeMember('StringData', 'GetProperty', $null, $row, $_) })
            }
            [void]$view.GetType().InvokeMember('Close', 'InvokeMethod', $null, $view, $null) }
        $prop = @{}; & $rows 'SELECT `Property`, `Value` FROM `Property`' 2 | ForEach-Object { $prop[$_[0]] = $_[1] }
        $code = $prop['UpgradeCode']
        & $add ($code -eq "{$($upgradeUuid.ToUpperInvariant())}") "MSI UpgradeCode $code ($($msi.Name), $($msi.Length) bytes, SHA-256 $((Get-FileHash $msi.FullName).Hash))" $false
        & $add ($prop['ProductVersion'] -eq $version -and $prop['ProductName'] -eq 'SkyWing') "MSI ProductName $($prop['ProductName']), ProductVersion $($prop['ProductVersion']) (expected $version)" $false
        & $add ($version -ne '0.1.1') "MSI version above the default local 0.1.1 (set GITHUB_RUN_NUMBER above the installed build to test an upgrade)" $true
        $summary = $db.GetType().InvokeMember('SummaryInformation', 'GetProperty', $null, $db, 0)
        $template = $summary.GetType().InvokeMember('Property', 'GetProperty', $null, $summary, 7)
        & $add ($template -like 'x64;*' -and $prop['ALLUSERS'] -eq '1') "MSI platform $template, per-machine (ALLUSERS=$($prop['ALLUSERS']))" $false
        # File associations: build.gradle.kts is the source of the expected list. jpackage writes them as HKCR registry rows.
        $expected = @([regex]::Matches($gradle, '"(\w+)" to "(video/[^"]+)"') | ForEach-Object { ".$($_.Groups[1].Value)=$($_.Groups[2].Value)" } | Sort-Object)
        $registry = @(& $rows 'SELECT `Key`, `Name`, `Value` FROM `Registry` WHERE `Root` = 0' 3)
        $progIds = @{}; $registry | Where-Object { $_[0] -match '^progid\w+$' -and -not $_[1] } | ForEach-Object { $progIds[$_[0]] = $_[2] }
        $commands = @($registry | Where-Object { $_[0] -match '\\shell\\open\\command$' -and $_[2] -match '^"\[#\w+\]" "%1"$' } | ForEach-Object { $_[0] -replace '\\shell\\open\\command$' })
        $found = @($registry | Where-Object { $_[0] -match '^\.\w+$' -and $_[1] -eq 'Content Type' } | ForEach-Object {
            $ext = $_[0]; $mime = $_[2]
            $prog = ($registry | Where-Object { $_[0] -eq $ext -and -not $_[1] } | Select-Object -First 1)[2]
            if ($progIds[$prog] -eq 'SkyWing video' -and $commands -contains $prog) { "$ext=$mime" } } | Sort-Object)
        & $add ($expected.Count -gt 0 -and -not (Compare-Object $expected $found)) "File associations $($found.Count)/$($expected.Count) with an Open command ($(($found | ForEach-Object { ($_ -split '=')[0] }) -join ' '))" $false
        $shortcuts = @(& $rows 'SELECT `Shortcut`.`Directory_`, `Shortcut`.`Name`, `Component`.`Condition` FROM `Shortcut`, `Component` WHERE `Shortcut`.`Component_` = `Component`.`Component`' 3)
        $desktop = $shortcuts | Where-Object { $_[0] -eq 'DesktopFolder' -and $_[2] -eq 'JP_INSTALL_DESKTOP_SHORTCUT' }
        $startMenu = $shortcuts | Where-Object { $_[0] -ne 'DesktopFolder' -and $_[2] -eq 'JP_INSTALL_STARTMENU_SHORTCUT' }
        & $add ($desktop -and $startMenu -and $shortcuts.Count -eq 2) "Shortcuts: Start menu and optional desktop ($($shortcuts.Count), conditioned on JP_INSTALL_STARTMENU_SHORTCUT and JP_INSTALL_DESKTOP_SHORTCUT)" $false
        [void][Runtime.InteropServices.Marshal]::ReleaseComObject($db)
        $diff = Compare-MsiToImage $msi.FullName $image
        $detail = if (-not $diff) { 'WiX dark.exe not found' } else {
            "$($diff.Identical) identical, $($diff.Equivalent) regenerated with the same content, differences: " + $(if ($diff.Differences) { $diff.Differences -join ', ' } else { 'none' }) }
        & $add ($diff -and -not $diff.Differences) "MSI files match the tested app image ($detail)" $false
    }
    if ($exe) {
        $info = $exe.VersionInfo
        & $add ($info.ProductVersion -eq $version) "EXE $($exe.Name), ProductVersion $($info.ProductVersion), $($exe.Length) bytes, SHA-256 $((Get-FileHash $exe.FullName).Hash)" $false
    }
    # Bundled runtime and architecture.
    $release = Join-Path $image 'runtime\release'
    $javaVersion = if (Test-Path -LiteralPath $release) { (Select-String -LiteralPath $release -Pattern '^JAVA_VERSION="([^"]+)"').Matches.Groups[1].Value }
    & $add ($javaVersion -like '17.*' -and (Test-Path -LiteralPath (Join-Path $image 'runtime\bin\server\jvm.dll'))) "Bundled Java runtime $javaVersion" $false
    foreach ($bin in 'SkyWing.exe', 'app\resources\libmpv-2.dll', 'runtime\bin\server\jvm.dll') {
        $machine = 0
        $path = Join-Path $image $bin
        if (Test-Path -LiteralPath $path) {
            $stream = [IO.File]::OpenRead($path)
            try { $bytes = New-Object byte[] 4096; [void]$stream.Read($bytes, 0, 4096) } finally { $stream.Dispose() }
            $pe = [BitConverter]::ToInt32($bytes, 0x3c); $machine = [BitConverter]::ToUInt16($bytes, $pe + 4)
        }
        & $add ($machine -eq 0x8664) ("$bin is x64 (PE machine 0x{0:X4})" -f $machine) $false
    }
    foreach ($f in @($msi, $exe) | Where-Object { $_ }) {
        & $add ((Get-AuthenticodeSignature $f.FullName).Status -eq 'Valid') "Authenticode signature on $($f.Name)" $true
    }
    & $add ((Get-Content (Join-Path $windows 'legal\SOURCE-STATUS.md') -Raw) -notmatch 'Status: INCOMPLETE') 'legal/SOURCE-STATUS.md audit complete' $true
}

# ---------------------------------------------------------------------------------------------------------------
$script:commands = @()
$script:payload = @()   # release checks; stays empty when tests fail or with -ListOnly
# The app image the UI automation tests launch: -Image (an MSI is unpacked first), or this checkout's own.
$imageDir = $null; $imageProblem = $null; $item = $null
if ($Image) {
    $item = Get-Item -LiteralPath $Image -ErrorAction SilentlyContinue
    if (-not $item) { $imageProblem = "not found: $Image" }
    elseif ($item.PSIsContainer) {
        $imageDir = $item.FullName.TrimEnd('\')
        $installed = "$env:ProgramFiles\SkyWing"
        if ($imageDir -eq $installed -or $imageDir.StartsWith("$installed\", [StringComparison]::OrdinalIgnoreCase)) {
            $imageProblem = 'this is the installed SkyWing, which the tests never launch: pass its MSI, or a copy of the folder'; $imageDir = $null
        }
    }
    elseif ($item.Extension -ne '.msi') { $imageProblem = 'use an app image folder (the one holding SkyWing.exe) or an .msi' }
    elseif (-not (Test-Path -LiteralPath (Join-Path $windows 'build\wix311\dark.exe'))) { $imageProblem = 'WiX dark.exe, needed to unpack the MSI, is missing (packageMsi downloads it to windows\build\wix311)' }
    else {
        Write-Host "Unpacking $($item.Name) into windows\build\verify-image (read-only; nothing is installed)"
        try { $imageDir = Expand-MsiImage $item.FullName (Join-Path $windows 'build\verify-image') } catch { $imageProblem = "could not unpack the MSI: $($_.Exception.Message)" }
    }
}
$testImage = if ($Image) { $imageDir } else { Join-Path $windows 'build\compose\binaries\main\app\SkyWing' }
if (-not $MpvDir) {
    $MpvDir = if ($imageDir) { Join-Path $imageDir 'app\resources' } elseif ($env:SKYWING_TEST_MPV_DIR) { $env:SKYWING_TEST_MPV_DIR } else { Join-Path $windows 'build\native-resources\windows' }
}
# powershell -File passes "a,b" as one string, so split on commas as well.
$changed = @(if ($Paths) { $Paths -split ',' | ForEach-Object { $_.Trim() -replace '\\', '/' } | Where-Object { $_ } } else { Get-ChangedPaths })
$plan = Select-Tests $changed
$tests = @($plan.Tests)
if ($Mode -ne 'focused') { $tests = @() }   # the whole suite
$needsFocus = ($Mode -ne 'focused') -or [bool]($tests | Where-Object { $_ -match $focusTests })
$prereqs = Test-Prerequisites $tests $Mode $MpvDir
$runToolTests = ($Mode -ne 'focused') -or [bool]($changed | Where-Object { $_ -match $toolTestsPattern })
$python = Get-Command python -ErrorAction SilentlyContinue
$prereqs = @($prereqs) + [pscustomobject]@{ Name = 'Python 3.11+ for the build tool tests'; Ok = [bool]$python; Required = $runToolTests
    Detail = $(if ($python) { $python.Source } else { 'python not on PATH' }) }
$toolRun = $null

Write-Host "SkyWing verification: mode=$Mode  changed=$($changed.Count) files  areas=$(@($plan.Areas) -join ', ')"
if ($Image) { Write-Host "App image under test: $(if ($imageDir) { $imageDir } else { "none ($imageProblem)" })" }
if ($Mode -eq 'focused') {
    Write-Host "Selected test classes ($($tests.Count)):"; $tests | ForEach-Object { Write-Host "  $_" }
}
if ($plan.Unmapped) { Write-Host "Unmapped changes (all off-screen unit tests added): $(@($plan.Unmapped) -join ', ')" }
Write-Host 'Prerequisites:'
$prereqs | ForEach-Object { Write-Host ("  [{0}] {1}{2}{3}" -f $(if ($_.Ok) { 'ok' } elseif ($_.Required) { 'MISSING' } else { 'n/a' }), $_.Name, $(if (-not $_.Required) { ' (not needed)' } else { '' }), $(if ($_.Detail) { " - $($_.Detail)" } else { '' })) }

$verdict = 'PASS'; $reasons = @()
$missingPrereqs = @($prereqs | Where-Object { $_.Required -and -not $_.Ok })
if ($missingPrereqs) { $verdict = 'BLOCKED'; $reasons += 'Missing prerequisites: ' + (($missingPrereqs | ForEach-Object Name) -join '; ') }
if ($needsFocus -and -not $AllowDesktopFocus) {
    $verdict = 'BLOCKED'
    $reasons += 'These tests take desktop focus (windows, full screen, SkyWing launches). Tell the user, then rerun with -AllowDesktopFocus.'
}
if ($verdict -ne 'BLOCKED' -and $Mode -eq 'focused' -and $tests.Count -eq 0) { $verdict = 'NO-TESTS'; $reasons += 'Only documentation or instructions changed; no test covers them.' }
if ($ListOnly -and $verdict -eq 'PASS') { $verdict = 'PLAN' }

$run = $null; $before = $null; $leftBefore = $null; $after = $null; $leftAfter = $null; $launched = @(); $fromImage = @()
$realAppRunning = [bool](Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.Path -and $_.Path -like "$env:ProgramFiles\SkyWing\*" })
if (-not $ListOnly -and $verdict -ne 'BLOCKED' -and ($Mode -ne 'focused' -or $tests.Count -gt 0)) {
    if ($needsFocus) { Write-Host "`n*** Desktop focus: SkyWing windows and full screen will appear until this finishes. ***`n" }
    $env:SKYWING_TEST_MPV = 'true'; $env:SKYWING_TEST_MPV_DIR = $MpvDir; $env:SKYWING_TEST_AUTOMATION = 'true'
    # SkyWingTestApp launches SKYWING_TEST_IMAGE when it is set. Without -Image, drop one inherited from the shell.
    if ($Image) { $env:SKYWING_TEST_IMAGE = $imageDir } else { Remove-Item Env:\SKYWING_TEST_IMAGE -ErrorAction SilentlyContinue }
    $before = Get-UserDataFingerprint; $leftBefore = Get-Leftovers
    $needImage = (-not $Image) -and (($Mode -ne 'focused') -or [bool]($tests | Where-Object { $_ -match '^com\.skywing\.desktop\.automation\.' }))
    $code = 0
    if ($needImage) { $code = Invoke-Gradle @('createDistributable', '--no-daemon') }
    if ($code -ne 0) { $verdict = 'FAIL'; $reasons += 'createDistributable failed' }
    else {
        $gradleArgs = @('test', '--no-daemon', '--rerun')
        foreach ($t in $tests) { $gradleArgs += @('--tests', $t) }
        $watch = Start-LaunchWatch
        try { $testCode = Invoke-Gradle $gradleArgs } finally { $launched = @(Stop-LaunchWatch $watch) }
        $run = Read-Results $tests
        if ($testCode -ne 0 -and -not ($run.Suites | Where-Object { $_.Failures -gt 0 })) { $verdict = 'FAIL'; $reasons += "Gradle test exited $testCode without a test failure (build or configuration error)" }
        if ($run.Suites | Where-Object { $_.Failures -gt 0 }) { $verdict = 'FAIL'; $reasons += 'Test failures' }
        if ($run.Missing) { $verdict = 'FAIL'; $reasons += 'Selected classes produced no results: ' + ($run.Missing -join ', ') }
        if ($run.Suites | Where-Object { $_.Skipped -gt 0 }) { $verdict = 'FAIL'; $reasons += 'Skipped tests (a required prerequisite or assumption failed)' }
        if (-not $run.Suites) { $verdict = 'FAIL'; $reasons += 'No test results were written' }
        if ($runToolTests) {
            $line = "python -I -m unittest discover -s windows/tools -p test_*.py"
            $script:commands += $line
            Push-Location $repo
            $previous = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
            try { $toolOutput = @(& $python.Source -I -m unittest discover -s windows/tools -p 'test_*.py' 2>&1 | ForEach-Object { "$_" }); $toolCode = $LASTEXITCODE }
            finally { $ErrorActionPreference = $previous; Pop-Location }
            $toolOutput | Write-Host
            $toolRun = ($toolOutput | Where-Object { $_ -match '^(Ran \d+ tests?|OK|FAILED)' }) -join '; '
            if ($toolCode -ne 0) { $verdict = 'FAIL'; $reasons += "Build tool tests failed ($toolRun)" }
            elseif ($toolOutput -match 'skipped') { $verdict = 'FAIL'; $reasons += 'Build tool tests skipped some tests' }
        }
        if ($Mode -eq 'release' -and $verdict -eq 'PASS') {
            # --rerun: build fresh packages from the tested image every time, never reuse up-to-date ones from an earlier run.
            # allowLowerVersion: this run checks its packages and never installs them, so a local 0.1.1 below the installed
            # SkyWing may be built here. The version gate below still reports it.
            $code = Invoke-Gradle @('packageExe', '--rerun', 'packageMsi', '--rerun', '-Pskywing.allowLowerVersion=true', '--no-daemon')
            if ($code -ne 0) { $verdict = 'FAIL'; $reasons += 'packageExe/packageMsi failed' }
            else {
                Test-ReleasePayload
                $gateFailures = @($script:payload | Where-Object { -not $_.Ok -and -not $_.Blocker -and -not $_.Warn })
                $gateBlockers = @($script:payload | Where-Object { -not $_.Ok -and $_.Blocker })
                if ($gateFailures) { $verdict = 'FAIL'; $reasons += "Release checks failed: $($gateFailures.Count)" }
                elseif ($gateBlockers) { $verdict = 'INCOMPLETE'; $reasons += "Tests and packaging passed; $($gateBlockers.Count) public-release gates are open" }
            }
        }
    }
    Start-Sleep -Seconds 2
    $after = Get-UserDataFingerprint; $leftAfter = Get-Leftovers
    $newDirs = @($leftAfter.Dirs | Where-Object { $leftBefore.Dirs -notcontains $_ })
    $newProcs = @($leftAfter.Processes | Where-Object { $leftBefore.Processes -notcontains $_ })
    if ($newDirs -or $newProcs) { $verdict = 'FAIL'; $reasons += "Left behind: $($newDirs.Count) temp folders, $($newProcs.Count) processes" }
    # Which build the UI tests ran: every SkyWing.exe they started must come from the image under test.
    $fromImage = @($launched | Where-Object { $_.StartsWith("$testImage\", [StringComparison]::OrdinalIgnoreCase) })
    $installedRuns = @($launched | Where-Object { $_.StartsWith("$env:ProgramFiles\SkyWing\", [StringComparison]::OrdinalIgnoreCase) })
    $otherImages = @($launched | Where-Object { $fromImage -notcontains $_ -and $installedRuns -notcontains $_ } | Sort-Object -Unique)
    if ($otherImages) { $verdict = 'FAIL'; $reasons += "SkyWing started from an image other than the one under test: $($otherImages -join '; ')" }
    if ($installedRuns) { $reasons += 'WARNING: the installed SkyWing was started during the run' }
    if ($before.Prefs -ne $after.Prefs -or $before.Library -ne $after.Library) {
        if ($realAppRunning) { $reasons += 'WARNING: real SkyWing data changed while the installed SkyWing was running; close it and rerun to confirm isolation' }
        else { $verdict = 'FAIL'; $reasons += 'The real SkyWing preferences or %LOCALAPPDATA%\SkyWing changed during the tests' }
    }
    if ($verdict -eq 'PASS' -and $Mode -eq 'focused' -and ($plan.Escalate -or $plan.Release)) {
        $verdict = 'INCOMPLETE'
        $reasons += 'Focused tests passed. Policy requires ' + $(if ($plan.Release) { 'release' } else { 'full' }) + ' mode for: ' + (@($plan.Escalate) -join ', ')
    }
    if ($verdict -eq 'PASS' -and $Mode -eq 'full' -and $plan.Release) {
        $verdict = 'INCOMPLETE'; $reasons += 'Full suite passed. Installer, payload or legal inputs changed: policy requires release mode before shipping.'
    }
}

# ---------------------------------------------------------------------------------------------------------------
$md = New-Object System.Text.StringBuilder
function Add([string]$s) { [void]$md.AppendLine($s) }
Add "# SkyWing verification report"
Add ""
Add "- Mode: **$Mode**$(if ($ListOnly) { ' (plan only, nothing run)' })"
Add "- Started: $($started.ToString('yyyy-MM-dd HH:mm')); duration $([int]((Get-Date) - $started).TotalMinutes) min"
Add "- Verdict: **$verdict**"
$reasons | ForEach-Object { Add "  - $_" }
Add "- Changed files considered: $($changed.Count); areas: $(if ($plan.Areas) { @($plan.Areas) -join ', ' } else { 'none mapped' })"
if ($plan.Escalate) { Add "- **Full mode required by policy** after focused passes: $(@($plan.Escalate) -join ', ')" }
if ($plan.Release) { Add "- **Release mode required by policy** before shipping (installer, payload or legal inputs changed)" }
if ($plan.Unmapped) { Add "- Unmapped changes: $(@($plan.Unmapped) -join ', ')" }
if ($Image) {
    $unpacked = if ($imageDir -and -not $item.PSIsContainer) { ", unpacked from $($item.Name) (not installed)" } else { '' }
    Add "- App image under test (-Image): $(if ($imageDir) { $imageDir } else { "none ($imageProblem)" })$unpacked"
    $build = if ($imageDir) { Get-ImageBuild $imageDir } else { $null }
    if ($build) {
        $dirty = $build['localChanges'] -eq 'true'
        $match = if ($dirty) { "it was built with uncommitted changes, so whether this checkout's app code matches it is unknown" } else { Get-SourceMatch $build['commit'] }
        Add "- Its build: version $($build['version']), commit $($build['commit'])$(if ($dirty) { ' with local changes' }), runtime $($build['runtime']); $match"
    }
    elseif ($imageDir) { Add '- Its build: unknown (no skywing-build.properties in its jars)' }
}
Add ""
Add "## Prerequisites"
$prereqs | ForEach-Object { Add ("- {0} {1}{2}" -f $(if ($_.Ok) { 'OK' } elseif ($_.Required) { '**MISSING**' } else { 'not needed:' }), $_.Name, $(if ($_.Detail) { " ($($_.Detail))" })) }
if ($script:commands) { Add ""; Add "## Commands"; $script:commands | ForEach-Object { Add "- ``$_``" } }
if ($run) {
    $t = ($run.Suites | Measure-Object Tests -Sum).Sum; $f = ($run.Suites | Measure-Object Failures -Sum).Sum; $k = ($run.Suites | Measure-Object Skipped -Sum).Sum
    Add ""; Add "## Results: $t tests, $f failed, $k skipped, $(@($run.Suites).Count) classes"
    Add ""; Add "| Class | Tests | Failed | Skipped | Seconds |"; Add "|---|---|---|---|---|"
    $run.Suites | Sort-Object Class | ForEach-Object { Add "| $($_.Class -replace '^com\.skywing\.desktop\.', '') | $($_.Tests) | $($_.Failures) | $($_.Skipped) | $([int]$_.Seconds) |" }
    $run.Suites | ForEach-Object { $_.FailedNames | ForEach-Object { Add "- FAILED $_" }; $_.SkippedNames | ForEach-Object { Add "- SKIPPED $_" } }
    if ($run.Missing) { Add "- DID NOT RUN: $($run.Missing -join ', ')" }
}
if ($toolRun) { Add ""; Add "## Build tool tests (windows/tools/test_*.py): $toolRun" }
if ($after) {
    Add ""; Add "## Isolation"
    Add "- Real preferences (HKCU\Software\JavaSoft\Prefs\com\skywing): $(if ($before.Prefs -eq $after.Prefs) { 'unchanged' } else { 'CHANGED' })"
    Add "- Real library cache (%LOCALAPPDATA%\SkyWing): $(if ($before.Library -eq $after.Library) { 'unchanged' } else { 'CHANGED' })"
    Add "- New temp folders: $(@($leftAfter.Dirs | Where-Object { $leftBefore.Dirs -notcontains $_ }).Count); lingering test processes: $(@($leftAfter.Processes | Where-Object { $leftBefore.Processes -notcontains $_ }).Count)"
    Add "- Installed SkyWing running during the run: $realAppRunning"
    Add "- SkyWing.exe starts during the tests: $(@($launched).Count), from the image under test: $(@($fromImage).Count)"
    $mem = Join-Path $windows 'build\reports\tests\test'
    foreach ($m in 'playback-memory.txt', 'app-memory.txt') { $p = Join-Path $mem $m; if ((Test-Path $p) -and (Get-Item $p).LastWriteTime -ge $started) { Add "- $(((Get-Content $p) -join ' ').Trim())" } }
}
if ($Mode -eq 'release' -and $script:payload) {
    Add ""; Add "## Release gates"
    $script:payload | ForEach-Object { Add ("- {0} {1}" -f $(if ($_.Ok) { 'PASS' } elseif ($_.Blocker) { '**BLOCKER**' } elseif ($_.Warn) { 'WARNING' } else { '**FAIL**' }), $_.Check) }
    $blockers = @($script:payload | Where-Object { -not $_.Ok -and $_.Blocker })
    Add ""; Add "Public release: **$(if ($blockers -or $verdict -ne 'PASS') { 'NOT READY' } else { 'gates passed; still needs clean-PC install, upgrade and uninstall checks' })**. This script never certifies a release."
}
Add ""; Add "## Not covered by automation"
Add "- $notMeasured"
New-Item -ItemType Directory -Force $reportDir | Out-Null
$report = Join-Path $reportDir ("verify-$Mode-" + $started.ToString('yyyyMMdd-HHmmss') + '.md')
[IO.File]::WriteAllText($report, $md.ToString(), (New-Object Text.UTF8Encoding $false))
Write-Host "`n$($md.ToString())"
Write-Host "Report: $report"
Write-Host "VERDICT: $verdict"
exit $(switch ($verdict) { 'PASS' { 0 } 'NO-TESTS' { 0 } 'PLAN' { 0 } 'BLOCKED' { 2 } 'INCOMPLETE' { 3 } default { 1 } })
