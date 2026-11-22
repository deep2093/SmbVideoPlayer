# M0 results: laptop and handheld measurements

These are the M0 acceptance runs from section 3.1, items 4 and 5, of `SkyWing-PC-modernise-plan.md`. Run them with the CI build of the pushed M0 commit. The laptop runs decide whether Automatic GPU stays as it is (plan 5.3). The handheld runs set the handheld default quality (plan 5.7).

Fill in the tables yourself, or paste the playback reports, the snippet output and the HWiNFO log back to Claude, who fills them in. Keep media names and paths out of this file: the repository is public.

## 1. Build under test

| | Value |
|---|---|
| Installer file name | `SkyWing-0.1.101-ea838e1-frame-sharing-with-ed072bf.msi` (and `.exe`), from [run 101](https://github.com/deep2093/SmbVideoPlayer/actions/runs/44986909958), the push build of ea838e1 |
| Expected installer name | `SkyWing-<version>-<commit>-frame-sharing-with-ed072bf.msi` (or `.exe`) |
| Playback report `Build:` line | |
| Expected `Build:` line | `Build: commit <commit>; runtime frame-sharing, sharing on; watchdog fix ed072bf included` (no "with local changes") |
| CI artifact check (Claude, after the push): `MPV-PREVIEW-MANIFEST.json` and the motion payload in the app image match the checkout | Pass (22 November, MSI unpacked with WiX `dark.exe`). ProductVersion 0.1.101; UpgradeCode {F40C59EF-E520-4E10-B7A2-B86E7458F923} unchanged. `skywing-build.properties` in the app jar: commit ea838e1, no local changes, runtime frame-sharing, watchdog fix included. libmpv: mpv revision e471496c and the frame-sharing patch match the checkout, and all 125 DLLs match `MPV-PREVIEW-MANIFEST.json`. Motion: the pins and the open-svpflow patch match `NATIVE-PROVENANCE.json`, no local plugin override, and all 63 files match `BUILD-MANIFEST.json`, none extra. |
| Automated tests against the CI build (Claude, after the push) | Pass (22 November, on the laptop). The MSI's files, unpacked with `dark.exe` into a test copy (not installed), ran the whole Gradle test suite with native libmpv and UI automation, as `verify.ps1 -Mode full` does. The UI automation tests launched the copy's `SkyWing.exe` (46 launches, all from the copy), and the native tests loaded its frame-sharing libmpv and motion payload. 122 tests in 26 classes: 0 failed, 0 skipped. The real preferences, library and installed SkyWing were unchanged, and nothing was left running. |
| Install and upgrade check (the owner installed; Claude checked) | Pass (23 November, on the laptop). The 0.1.101 MSI upgraded 0.1.98 in place. Windows Installer logged a successful install, and the only product with UpgradeCode {F40C59EF-E520-4E10-B7A2-B86E7458F923} is now 0.1.101, with one Installed apps entry. The 867 files in `C:\Program Files\SkyWing` match the MSI's files byte for byte, with none extra. The installed jar's `skywing-build.properties`: version 0.1.101, commit ea838e1, no local changes, runtime frame-sharing, watchdog fix included. The Start menu and desktop shortcuts open the installed `SkyWing.exe`. All 10 file types in `build.gradle.kts` are registered as "SkyWing video" with an Open command. The library, artwork and preferences were left as they were. Uninstall wasn't tested. |

## 2. How to measure

Use the same steps for every run.

**Settings**
- Motion options: motion on, 2×, and the quality the run asks for. SkyWing keeps these per video class (1080p or 4K, SDR or HDR), so set them while each clip plays. The report shows them as `Multiplier: 2×` and `Engine: … quality: <quality>; target: <fps> fps`.
- Settings → Playback: "Lower automatically when needed" off (the default). Leave "My screen uses G-SYNC / FreeSync" as it is, but the same for every run on one machine.
- Leave `SKYWING_VS_SHARE_FRAMES` and `SKYWING_D3D11_COMPAT` unset, so frame sharing is on and the normal swap chain is used. `SKYWING_GPU` is only for laptop run B.

**Playback**
- Local files only, not the network drive. Audio on. Fullscreen.
- Start the clip from the beginning. No seeks, no pauses and no other apps. Close any other SkyWing build too: the snippet below counts every process named SkyWing. Don't touch the mouse or keyboard during the measured window.
- Use one power mode per machine (the Windows power mode and the maker's performance, fan or TDP mode) and write it down.
- Battery runs: start with at least 40 % charge and battery saver off.

**Timing, process CPU, memory and battery**

Open Windows PowerShell and paste the snippet below. Then start the clip and go fullscreen straight away. The snippet waits until SkyWing runs and gives playback 60 seconds to settle. Then it beeps once: copy report A (Motion options → Copy playback report, and paste it into a text file), close Motion options and leave the PC alone. When the window ends it beeps twice: copy report B. It prints SkyWing's process CPU, its private memory at the start and end of the window and, on battery, the battery drain. The laptop window is 10 minutes; for the handhelds set `$window = 300` (5 minutes). The clip must outlast the settling time plus the window.

```powershell
$name = 'SkyWing'; $warmup = 60; $window = 600   # handheld runs: $window = 300
function Get-CpuSeconds { (Get-Process $name | ForEach-Object { $_.TotalProcessorTime.TotalSeconds } | Measure-Object -Sum).Sum }
function Get-PrivateMB { (Get-Process $name | Measure-Object PrivateMemorySize64 -Sum).Sum / 1MB }
function Get-BatteryCharge {
    $status = @(Get-CimInstance -Namespace root\wmi -ClassName BatteryStatus -ErrorAction SilentlyContinue)
    if ($status) { [pscustomobject]@{ mWh = ($status | Measure-Object RemainingCapacity -Sum).Sum; OnAC = [bool]($status | Where-Object PowerOnline) } }
}
while (-not (Get-Process $name -ErrorAction SilentlyContinue)) { Start-Sleep 1 }
"$name is running. Start the clip and go fullscreen; the measured window starts in $warmup s."
Start-Sleep $warmup
$cpu0 = Get-CpuSeconds; $memory0 = Get-PrivateMB; $battery0 = Get-BatteryCharge; $t0 = Get-Date
[console]::Beep(880, 300); "Window started at $($t0.ToString('T')). Copy report A now, then leave SkyWing alone."
Start-Sleep $window
$cpu1 = Get-CpuSeconds; $memory1 = Get-PrivateMB; $battery1 = Get-BatteryCharge; $seconds = ((Get-Date) - $t0).TotalSeconds
[console]::Beep(660, 300); [console]::Beep(660, 300)
"Window ended after {0:N0} s. Copy report B now." -f $seconds
"SkyWing process CPU: {0:N2} cores" -f (($cpu1 - $cpu0) / $seconds)
"SkyWing private memory: {0:N0} MB at the start of the window, {1:N0} MB at the end" -f $memory0, $memory1
if ($battery0 -and -not $battery1.OnAC) {
    $full = (Get-CimInstance -Namespace root\wmi -ClassName BatteryFullChargedCapacity | Measure-Object FullChargedCapacity -Sum).Sum
    $used = $battery0.mWh - $battery1.mWh
    "Battery: {0:N1} W; {1:N1} % per hour ({2} mWh used)" -f ($used / 1000 * 3600 / $seconds), ($used / $full * 100 * 3600 / $seconds), $used
} elseif ($battery1) { 'Battery: on AC power, not measured.' }
```

**HWiNFO** (the laptop, and the handhelds if it is installed): sensors only. Start CSV logging before the clip; the beeps mark the window. Keep the CSV out of the repository.

**Reading the numbers**
- Drops in the window: the increase in `frame-drop-count` plus `decoder-frame-drop-count` from report A to report B. Drops per minute: that number divided by the window's minutes. The report's "Drops in last 60 seconds" line covers only the last minute.
- Restarts: `Stall recovery attempts:` in report B. Expect 0.
- Graph generations: `observed graph generations:` in reports A and B. It should not change during the window.
- Process CPU is in logical cores: 1.00 means one core busy all the time. HWiNFO's CPU usage is for the whole system, so the two can't be compared.
- Private memory is Task Manager's "Commit size", summed over the SkyWing processes like the CPU. The change from start to end shows growth during the window. There is no earlier figure to compare with, so these runs set the baseline.
- On Intel, GPU power is part of CPU package power. Don't add the two.
- None of these numbers measures the frames on the screen. Presented FPS isn't measured yet (plan 5.6).

## 3. Laptop (RTX 5070 Ti, Hybrid)

Clip: the local 4K SDR H.264 clip at 30000/1001 fps (29.97) from the 20 November efficiency review. Balanced, 2× (target 59.94 fps). Plugged in. Window: 10 minutes.

SkyWing picks its GPU once per run, when it first creates the video surface, from the monitor its window is on (section 6). Start SkyWing with its window on the screen you are testing.

**Run A, Automatic (all-Intel).** The laptop in Hybrid (Optimus) mode, SkyWing on the built-in screen. The report must show Intel on both lines: `Preferred motion GPU: Intel…` and `Renderer: …; preference: Intel D3D11`.

**Run B, NVIDIA.** The same as run A, still in Hybrid mode, but with the test-only override `SKYWING_GPU=NVIDIA` (section 6). Close SkyWing, then start it from Windows PowerShell:

```powershell
$env:SKYWING_GPU = 'NVIDIA'; & "$env:ProgramFiles\SkyWing\SkyWing.exe"
```

The override applies only to SkyWing started from that window. The report must show NVIDIA on both lines, and the `Renderer:` line's preference must end in "forced by SKYWING_GPU". This is the plan's Hybrid + Performance case: NVIDIA draws the video and runs motion, and Windows copies the frames to the Intel-driven screen. If the laptop also has a dedicated-GPU (MUX) mode, a run in that mode without the override measures NVIDIA driving the screen directly; it's optional, so note it under the table if you do it.

| | Run A: Automatic (all-Intel) | Run B: NVIDIA |
|---|---|---|
| Date; power and fan mode | | |
| How NVIDIA was selected | — | |
| `Preferred motion GPU:` line | | |
| `Renderer:` line | | |
| `Display:` line | | |
| Filter processing throughput (report B) | | |
| Drops in the 10 minutes (video + decoder) | | |
| Drops per minute | | |
| Restarts (expect 0) | | |
| Graph generations, report A → B | | |
| Process CPU (cores) | | |
| Private memory, MB: start → end of the window | | |
| CPU package power, average W | | |
| NVIDIA GPU power, average W | | |
| CPU package temperature, average / maximum °C | | |
| GPU temperature, average / maximum °C | | |
| Fans, average / maximum RPM | | |
| Anything visible (judder, artefacts, audio sync) | | |

## 4. Handhelds

OneXPlayer 3 (Intel Arc B390) first, then MSI Claw 8. Both use Intel for everything, so there is no GPU choice.

Clips: a local 1080p SDR clip (the same file on both handhelds; write down its frame rate) and the laptop's 4K clip. Multiplier 2×. Window: 5 minutes (`$window = 300`). Do the plugged-in runs first, then the battery runs.

### OneXPlayer 3 (Intel Arc B390)

| | Value |
|---|---|
| `Preferred motion GPU:` line | |
| `Renderer:` line | |
| `Display:` line | |
| Power or TDP mode | |
| 1080p clip frame rate | |

| Clip | Quality | Power | Drops in 5 min | Drops per minute | Restarts | Process CPU (cores) | Private memory, MB (start → end) | Battery drain (W; % per hour) | Notes |
|---|---|---|---|---|---|---|---|---|---|
| 1080p | Fast | Plugged in | | | | | | — | |
| 1080p | Balanced | Plugged in | | | | | | — | |
| 4K | Fast | Plugged in | | | | | | — | |
| 4K | Balanced | Plugged in | | | | | | — | |
| 1080p | Fast | Battery | | | | | | | |
| 1080p | Balanced | Battery | | | | | | | |
| 4K | Fast | Battery | | | | | | | |
| 4K | Balanced | Battery | | | | | | | |

### MSI Claw 8

| | Value |
|---|---|
| `Preferred motion GPU:` line | |
| `Renderer:` line | |
| `Display:` line | |
| Power or TDP mode | |
| 1080p clip frame rate | |

| Clip | Quality | Power | Drops in 5 min | Drops per minute | Restarts | Process CPU (cores) | Private memory, MB (start → end) | Battery drain (W; % per hour) | Notes |
|---|---|---|---|---|---|---|---|---|---|
| 1080p | Fast | Plugged in | | | | | | — | |
| 1080p | Balanced | Plugged in | | | | | | — | |
| 4K | Fast | Plugged in | | | | | | — | |
| 4K | Balanced | Plugged in | | | | | | — | |
| 1080p | Fast | Battery | | | | | | | |
| 1080p | Balanced | Battery | | | | | | | |
| 4K | Fast | Battery | | | | | | | |
| 4K | Balanced | Battery | | | | | | | |

## 5. What the numbers decide

- **Automatic GPU (plan 5.3):** Automatic stays all-Intel unless run A drops frames, or run B holds 2× with clearly fewer drops at an acceptable power cost.
- **Handheld default (plan 5.7, item 3):** the quality that holds 2× at 1080p without drops on battery becomes the handheld default. The plan starts from 1080p 2× Fast.

## 6. How this build picks its GPU

This differs from the brief, whose "Performance forced to NVIDIA … (or `SKYWING_*` override)" didn't exist: Performance is part of plan 5.3 (M2). SkyWing uses the GPU that drives the monitor its window is on when it first creates the video surface (`WindowsDisplayAdapter`), both to draw the video (mpv's `d3d11-adapter`) and for motion (`SKYWING_OPENCL_VENDOR`, which SkyWing sets itself). If Windows names neither an Intel nor an NVIDIA adapter, it asks for NVIDIA. Windows' per-app graphics setting doesn't change the choice, because SkyWing passes the adapter to mpv by name.

For run B, M0 adds the test-only override `SKYWING_GPU` (`NVIDIA` or `Intel`, set before SkyWing starts). It changes the GPU for drawing and motion only; display queries such as VRR still use the monitor's GPU. If mpv refuses the named GPU (NVIDIA on a handheld, for example), SkyWing uses Windows' choice and the `Renderer:` line says so. The plan 5.3 setting replaces the override.
