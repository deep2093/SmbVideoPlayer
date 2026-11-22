# M0 results: laptop and handheld measurements

These are the M0 acceptance runs from section 3.1, items 4 and 5, of `SkyWing-PC-modernise-plan.md`. Run them with the CI build of the pushed M0 commit. The laptop runs decide whether Automatic GPU stays as it is (plan 5.3). The handheld runs set the handheld default quality (plan 5.7).

Fill in the tables yourself, or paste the playback reports, the snippet output and the HWiNFO log back to Claude, who fills them in. Keep media names and paths out of this file: the repository is public.

## 1. Build under test

| | Value |
|---|---|
| Installer file name | |
| Expected installer name | `SkyWing-<version>-<commit>-frame-sharing-with-ed072bf.msi` (or `.exe`) |
| Playback report `Build:` line | |
| Expected `Build:` line | `Build: commit <commit>; runtime frame-sharing, sharing on; watchdog fix ed072bf included` (no "with local changes") |
| CI artifact check (Claude, after the push): `MPV-PREVIEW-MANIFEST.json` and the motion payload in the app image match the checkout | |

## 2. How to measure

Use the same steps for every run.

**Settings**
- Motion options: motion on, 2×, and the quality the run asks for. SkyWing keeps these per video class (1080p or 4K, SDR or HDR), so set them while each clip plays. The report shows them as `Multiplier: 2×` and `Engine: … quality: <quality>; target: <fps> fps`.
- Settings → Playback: "Lower automatically when needed" off (the default). Leave "My screen uses G-SYNC / FreeSync" as it is, but the same for every run on one machine.
- Leave `SKYWING_VS_SHARE_FRAMES` and `SKYWING_D3D11_COMPAT` unset, so frame sharing is on and the normal swap chain is used.

**Playback**
- Local files only, not the network drive. Audio on. Fullscreen.
- Start the clip from the beginning. No seeks, no pauses and no other apps. Don't touch the mouse or keyboard during the measured window.
- Use one power mode per machine (the Windows power mode and the maker's performance, fan or TDP mode) and write it down.
- Battery runs: start with at least 40 % charge and battery saver off.

**Timing, process CPU and battery**

Open Windows PowerShell and paste the snippet below. Then start the clip and go fullscreen straight away. The snippet waits until SkyWing runs and gives playback 60 seconds to settle. Then it beeps once: copy report A (Motion options → Copy playback report, and paste it into a text file), close Motion options and leave the PC alone. When the window ends it beeps twice: copy report B. It prints SkyWing's process CPU and, on battery, the battery drain. The laptop window is 10 minutes; for the handhelds set `$window = 300` (5 minutes). The clip must outlast the settling time plus the window.

```powershell
$name = 'SkyWing'; $warmup = 60; $window = 600   # handheld runs: $window = 300
function Get-CpuSeconds { (Get-Process $name | ForEach-Object { $_.TotalProcessorTime.TotalSeconds } | Measure-Object -Sum).Sum }
function Get-BatteryCharge {
    $status = @(Get-CimInstance -Namespace root\wmi -ClassName BatteryStatus -ErrorAction SilentlyContinue)
    if ($status) { [pscustomobject]@{ mWh = ($status | Measure-Object RemainingCapacity -Sum).Sum; OnAC = [bool]($status | Where-Object PowerOnline) } }
}
while (-not (Get-Process $name -ErrorAction SilentlyContinue)) { Start-Sleep 1 }
"$name is running. Start the clip and go fullscreen; the measured window starts in $warmup s."
Start-Sleep $warmup
$cpu0 = Get-CpuSeconds; $battery0 = Get-BatteryCharge; $t0 = Get-Date
[console]::Beep(880, 300); "Window started at $($t0.ToString('T')). Copy report A now, then leave SkyWing alone."
Start-Sleep $window
$cpu1 = Get-CpuSeconds; $battery1 = Get-BatteryCharge; $seconds = ((Get-Date) - $t0).TotalSeconds
[console]::Beep(660, 300); [console]::Beep(660, 300)
"Window ended after {0:N0} s. Copy report B now." -f $seconds
"SkyWing process CPU: {0:N2} cores" -f (($cpu1 - $cpu0) / $seconds)
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
- On Intel, GPU power is part of CPU package power. Don't add the two.
- None of these numbers measures the frames on the screen. Presented FPS isn't measured yet (plan 5.6).

## 3. Laptop (RTX 5070 Ti, Hybrid)

Clip: the local 4K SDR H.264 clip at 30000/1001 fps (29.97) from the 20 November efficiency review. Balanced, 2× (target 59.94 fps). Plugged in. Window: 10 minutes.

SkyWing picks its GPU once per run, when it first creates the video surface, from the monitor its window is on (section 6). Start SkyWing with its window on the screen you are testing.

**Run A, Automatic (all-Intel).** The laptop in Hybrid (Optimus) mode, SkyWing on the built-in screen. The report must show Intel on both lines: `Preferred motion GPU: Intel…` and `Renderer: …; preference: Intel D3D11`.

**Run B, NVIDIA.** This build has no Performance choice and no override (section 6), so make NVIDIA drive the screen. Switch the laptop to its dedicated-GPU (MUX) mode in the maker's control app, or in NVIDIA Control Panel → Manage Display Mode where it exists, and restart Windows if it asks. Everything else stays the same. The report must show NVIDIA on both lines. Switch back to Hybrid afterwards. This measures NVIDIA driving the screen directly, not the plan's Hybrid + Performance case, where NVIDIA's frames are copied to the Intel-driven screen. Without a MUX switch, use an external monitor on a port wired to the NVIDIA GPU (on most gaming laptops, HDMI); that also changes the screen, so write down which one.

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

| Clip | Quality | Power | Drops in 5 min | Drops per minute | Restarts | Process CPU (cores) | Battery drain (W; % per hour) | Notes |
|---|---|---|---|---|---|---|---|---|
| 1080p | Fast | Plugged in | | | | | — | |
| 1080p | Balanced | Plugged in | | | | | — | |
| 4K | Fast | Plugged in | | | | | — | |
| 4K | Balanced | Plugged in | | | | | — | |
| 1080p | Fast | Battery | | | | | | |
| 1080p | Balanced | Battery | | | | | | |
| 4K | Fast | Battery | | | | | | |
| 4K | Balanced | Battery | | | | | | |

### MSI Claw 8

| | Value |
|---|---|
| `Preferred motion GPU:` line | |
| `Renderer:` line | |
| `Display:` line | |
| Power or TDP mode | |
| 1080p clip frame rate | |

| Clip | Quality | Power | Drops in 5 min | Drops per minute | Restarts | Process CPU (cores) | Battery drain (W; % per hour) | Notes |
|---|---|---|---|---|---|---|---|---|
| 1080p | Fast | Plugged in | | | | | — | |
| 1080p | Balanced | Plugged in | | | | | — | |
| 4K | Fast | Plugged in | | | | | — | |
| 4K | Balanced | Plugged in | | | | | — | |
| 1080p | Fast | Battery | | | | | | |
| 1080p | Balanced | Battery | | | | | | |
| 4K | Fast | Battery | | | | | | |
| 4K | Balanced | Battery | | | | | | |

## 5. What the numbers decide

- **Automatic GPU (plan 5.3):** Automatic stays all-Intel unless run A drops frames, or run B holds 2× with clearly fewer drops at an acceptable power cost.
- **Handheld default (plan 5.7, item 3):** the quality that holds 2× at 1080p without drops on battery becomes the handheld default. The plan starts from 1080p 2× Fast.

## 6. How this build picks its GPU

This differs from the brief. Its "Performance forced to NVIDIA … (or `SKYWING_*` override)" doesn't exist yet: Performance is part of plan 5.3 (M2). Today SkyWing uses the GPU that drives the monitor its window is on when it first creates the video surface (`WindowsDisplayAdapter`), both to draw the video (mpv's `d3d11-adapter`) and for motion (`SKYWING_OPENCL_VENDOR`, which SkyWing sets itself). If Windows names neither an Intel nor an NVIDIA adapter, it asks for NVIDIA. No environment variable changes the choice. Windows' per-app graphics setting doesn't change it either, because SkyWing passes the adapter to mpv by name.
