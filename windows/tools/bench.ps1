<#
.SYNOPSIS
  Runs SkyWing's hidden benchmark (plan 5.6) and, when you have the rights, records on-screen frame times with PresentMon.

.DESCRIPTION
  Starts SkyWing.exe --bench: the video plays full screen from -Start with motion smoothing at -Quality and -Stop, for
  -Minutes once smoothing holds. SkyWing writes the per-minute CSV and bench-<time>.txt (the test report) to -Out.
  It saves nothing to SkyWing's settings or library. Closing its window ends the run early.

  -PresentMon also runs Intel's PresentMon console app (MIT licence; not shipped with SkyWing) next to it. PresentMon
  reads Windows' present events, which needs an administrator PowerShell or membership of the Performance Log Users
  group. This script never asks for elevation: without the rights it says "not measured". PresentMon times presents
  and when each reached the screen. With display-resample mpv presents once per refresh, so one video frame can be
  shown for several presents: present cadence is not the video's frame cadence.

  The PresentMon summary covers the last -Minutes minutes of the capture, which is the measured part of the run.
  -PresentMonCsv summarises a capture made earlier and runs nothing else.

  Exit code: SkyWing's (0 smoothing held, 1 it stopped or the run ended early, 2 the run could not start), or 2 when
  this script could not start it. A SkyWing still running 10 minutes after -Minutes is stopped, with exit code 1.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File windows\tools\bench.ps1 -Video D:\clips\4k-24fps.mkv
  powershell -NoProfile -ExecutionPolicy Bypass -File windows\tools\bench.ps1 -Video D:\clips\4k-24fps.mkv -Quality Fast -Stop 2.5x -PresentMon
  powershell -NoProfile -ExecutionPolicy Bypass -File windows\tools\bench.ps1 -PresentMonCsv <folder>\presentmon.csv -Minutes 10
#>
param(
    [string]$Video,
    [double]$Minutes = 10,
    [ValidateSet('Fastest', 'Fast', 'Balanced', 'Better', 'Best')][string]$Quality = 'Balanced',
    [ValidateSet('2x', '2.5x', '3x', '4x')][string]$Stop = '2x',
    # A plan 5.7 experiment that replaces the -Quality preset's options (test only, never saved).
    [ValidateSet('PotPlayer-like')][string]$Experiment,
    # Plan 5.7 item 4: shrink a video larger than the screen to the screen's size before smoothing.
    [switch]$ScreenSize,
    [double]$Start = 0,
    # SkyWing.exe to run; default: this checkout's createDistributable image.
    [string]$App,
    # Folder for the CSV, the report and the PresentMon capture; default: windows\build\reports\bench\<time>.
    [string]$Out,
    [switch]$PresentMon,
    [string]$PresentMonPath,
    [string]$PresentMonCsv
)
$ErrorActionPreference = 'Stop'
$windows = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$invariant = [Globalization.CultureInfo]::InvariantCulture

function Format-Ms([double]$value) { $value.ToString('0.00', $invariant) + ' ms' }

function Get-Percentile([double[]]$sorted, [double]$p) {
    if ($sorted.Count -eq 0) { return [double]::NaN }
    $sorted[[Math]::Min($sorted.Count - 1, [int][Math]::Floor($p * ($sorted.Count - 1) + 0.5))]
}

# PresentMon 1.x metrics (--v1_metrics): one row per present. Dropped is 1 for a present that never reached the screen;
# msBetweenDisplayChange is the time since the previous displayed frame.
function Get-PresentMonSummary([string]$csv, [double]$minutes) {
    $lines = [IO.File]::ReadAllLines($csv)
    if ($lines.Count -lt 2) { return @('On-screen frame times: PresentMon recorded no presents from SkyWing.exe') }
    $header = $lines[0].Split(',')
    $col = @{}; for ($i = 0; $i -lt $header.Count; $i++) { $col[$header[$i].Trim()] = $i }
    foreach ($name in 'SwapChainAddress', 'TimeInSeconds', 'Dropped', 'PresentMode', 'SyncInterval', 'msBetweenDisplayChange') {
        if (-not $col.ContainsKey($name)) { return @("On-screen frame times: the PresentMon CSV has no $name column (was it captured with --v1_metrics?)") }
    }
    # mpv's swap chain is the one with the most presents; the benchmark window itself draws with GDI.
    $chains = @{}
    for ($i = 1; $i -lt $lines.Count; $i++) {
        if (-not $lines[$i]) { continue }
        $f = $lines[$i].Split(',')
        $key = $f[$col.SwapChainAddress]
        if (-not $chains.ContainsKey($key)) { $chains[$key] = New-Object 'System.Collections.Generic.List[string[]]' }
        $chains[$key].Add($f)
    }
    $chain = $chains.Values | Sort-Object Count -Descending | Select-Object -First 1
    $first = [double]::Parse($chain[0][$col.TimeInSeconds], $invariant)
    $last = [double]::Parse($chain[$chain.Count - 1][$col.TimeInSeconds], $invariant)
    $from = $last - $minutes * 60
    $presents = 0; $dropped = 0
    $intervals = New-Object 'System.Collections.Generic.List[double]'
    $modes = @{}; $syncs = @{}
    foreach ($f in $chain) {
        if ([double]::Parse($f[$col.TimeInSeconds], $invariant) -lt $from) { continue }
        $presents++
        $modes[$f[$col.PresentMode]] = 1 + [int]$modes[$f[$col.PresentMode]]
        $syncs[$f[$col.SyncInterval]] = $true
        if ($f[$col.Dropped] -eq '1') { $dropped++; continue }
        $between = [double]::Parse($f[$col.msBetweenDisplayChange], $invariant)
        if ($between -gt 0) { $intervals.Add($between) }
    }
    $span = [Math]::Min($minutes * 60, $last - $first)
    $out = @("On-screen frame times (PresentMon, last $($span.ToString('0', $invariant)) s, mpv's swap chain): " +
        "$presents presents, $($presents - $dropped) displayed, $dropped not displayed " +
        "($((100.0 * $dropped / [Math]::Max(1, $presents)).ToString('0.00', $invariant))%)")
    if ($intervals.Count) {
        $intervals.Sort()
        $sorted = $intervals.ToArray()
        $mean = ($sorted | Measure-Object -Average).Average
        $median = Get-Percentile $sorted 0.5
        $long = @($sorted | Where-Object { $_ -gt 1.5 * $median }).Count
        $out += "Displayed-frame interval: mean $(Format-Ms $mean) ($((1000 / $mean).ToString('0.0', $invariant)) per second), " +
            "median $(Format-Ms $median), 99th percentile $(Format-Ms (Get-Percentile $sorted 0.99)), max $(Format-Ms $sorted[-1]); " +
            "over 1.5x the median: $long ($((100.0 * $long / $sorted.Count).ToString('0.00', $invariant))%)"
    }
    $modeText = $modes.GetEnumerator() | Sort-Object Value -Descending |
        ForEach-Object { "$($_.Key) $((100.0 * $_.Value / [Math]::Max(1, $presents)).ToString('0.0', $invariant))%" }
    $out += "Present mode: $($modeText -join ', '); sync interval: $((@($syncs.Keys) | Sort-Object) -join ', ')"
    $out += 'Presents are not video frames: with display-resample mpv presents once per refresh.'
    $out
}

if ($PresentMonCsv) { Get-PresentMonSummary (Resolve-Path $PresentMonCsv).Path $Minutes; exit 0 }

if (-not $Video) { Write-Host 'Give -Video <file>, or -PresentMonCsv <file> to summarise a capture.'; exit 2 }
if (-not (Test-Path -LiteralPath $Video -PathType Leaf)) { Write-Host "The video was not found: $Video"; exit 2 }
if (-not $App) { $App = Join-Path $windows 'build\compose\binaries\main\app\SkyWing\SkyWing.exe' }
if (-not (Test-Path -LiteralPath $App -PathType Leaf)) {
    Write-Host "SkyWing.exe was not found at $App. Build it with .\gradlew.bat -p windows createDistributable, or pass -App."; exit 2
}
if (-not $Out) { $Out = Join-Path $windows ('build\reports\bench\' + (Get-Date).ToString('yyyyMMdd-HHmmss')) }
New-Item -ItemType Directory -Force $Out | Out-Null
$Out = (Resolve-Path $Out).Path

$capture = $null; $monitor = $null; $notMeasured = $null
if ($PresentMon) {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $admin = (New-Object Security.Principal.WindowsPrincipal $identity).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    $logUsers = [bool]($identity.Groups | Where-Object { $_.Value -eq 'S-1-5-32-559' })
    if (-not $PresentMonPath) {
        $PresentMonPath = @(Get-ChildItem "$env:ProgramFiles\Intel\PresentMon\PresentMonConsoleApplication\PresentMon-*-x64.exe" -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName) + @((Get-Command 'PresentMon.exe', 'PresentMon-*-x64.exe' -ErrorAction SilentlyContinue).Source) |
            Where-Object { $_ } | Select-Object -First 1
    }
    if (-not ($admin -or $logUsers)) {
        $notMeasured = 'not measured (no rights: run from an administrator PowerShell, or join the Performance Log Users group and sign in again)'
    }
    elseif (-not $PresentMonPath -or -not (Test-Path -LiteralPath $PresentMonPath)) {
        $notMeasured = 'not measured (PresentMon console app not found; pass -PresentMonPath)'
    }
}

$benchArgs = @('--bench', "`"$Video`"", '--minutes', $Minutes.ToString($invariant), '--quality', $Quality, '--stop', $Stop,
    '--start', $Start.ToString($invariant), '--out', "`"$Out`"")
if ($Experiment) { $benchArgs += @('--experiment', $Experiment) }
if ($ScreenSize) { $benchArgs += @('--screen-size', 'on') }
Write-Host "SkyWing benchmark: $Minutes min, $Quality, $Stop, from $Start s. It plays full screen; keep hands off until it closes."
$skywing = Start-Process -FilePath $App -ArgumentList $benchArgs -PassThru
if ($PresentMon -and -not $notMeasured) {
    $capture = Join-Path $Out 'presentmon.csv'
    # Started after SkyWing so --terminate_on_proc_exit has a process to wait for.
    $monitor = Start-Process -FilePath $PresentMonPath -WindowStyle Hidden -PassThru -ArgumentList @(
        '--process_name', 'SkyWing.exe', '--output_file', "`"$capture`"", '--v1_metrics', '--no_console_stats',
        '--session_name', 'SkyWingBench', '--stop_existing_session', '--terminate_on_proc_exit')
}
# SkyWing's own waits (window, loading, a minute to start smoothing, closing) take a few minutes at most; past that it
# is stuck, so stop it rather than keep a chain of runs waiting.
$deadlineSeconds = [int]($Minutes * 60) + 600
$hung = -not $skywing.WaitForExit($deadlineSeconds * 1000)
if ($hung) {
    Stop-Process -Id $skywing.Id -Force -ErrorAction SilentlyContinue
    $skywing.WaitForExit(10000) | Out-Null
    $code = 1
}
else { $code = $skywing.ExitCode }
if ($monitor) {
    if (-not $monitor.WaitForExit(30000)) {
        Stop-Process -Id $monitor.Id -ErrorAction SilentlyContinue
        & $PresentMonPath --session_name SkyWingBench --terminate_existing_session | Out-Null
    }
}

$summary = @()
if ($hung) { $summary += "SkyWing did not finish within $deadlineSeconds s, so it was stopped" }
elseif (-not (Get-ChildItem $Out -Filter 'bench-*.txt')) { $summary += "SkyWing wrote no report: it did not start (exit code $code)" }
if (-not $PresentMon) { $summary += 'On-screen frame times: not measured (run with -PresentMon)' }
elseif ($notMeasured) { $summary += "On-screen frame times: $notMeasured" }
elseif (-not (Test-Path $capture)) { $summary += "On-screen frame times: not measured (PresentMon wrote no capture; exit code $($monitor.ExitCode))" }
else { $summary += Get-PresentMonSummary $capture $Minutes }
[IO.File]::WriteAllLines((Join-Path $Out 'presentmon-summary.txt'), [string[]]$summary)
Get-ChildItem $Out -Filter 'bench-*.txt' | Sort-Object LastWriteTime | Select-Object -Last 1 | ForEach-Object { Get-Content -Encoding UTF8 $_.FullName }
$summary | ForEach-Object { Write-Host $_ }
Write-Host "Results: $Out"
Write-Host "Exit code: $code"
exit $code
