# M0 results: laptop and handheld measurements

These are the M0 acceptance runs from section 3.1, items 4 and 5, of `SkyWing-PC-modernise-plan.md`. Run them with the CI build of the pushed M0 commit. The laptop runs decide whether Automatic GPU stays as it is (plan 5.3). The handheld runs set the handheld default quality (plan 5.7).

Fill in the tables yourself, or give Claude the playback reports, the snippet output or the handheld runner's results folder, and the HWiNFO log. Claude fills them in. Keep media names and paths out of this file: the repository is public.

## 1. Build under test

| | Value |
|---|---|
| Installer file name | `SkyWing-0.1.101-ea838e1-frame-sharing-with-ed072bf.msi` (and `.exe`), from [run 101](https://github.com/deep2093/SmbVideoPlayer/actions/runs/44986909958), the push build of ea838e1 |
| Expected installer name | `SkyWing-<version>-<commit>-frame-sharing-with-ed072bf.msi` (or `.exe`) |
| Playback report `Build:` line | `Build: commit ea838e1; runtime frame-sharing, sharing on; watchdog fix ed072bf included`, from the reports of laptop runs A and B (23 November). It matches the expected line. |
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

**Automated route (the laptop, with this checkout and JDK 17)**

`.\gradlew.bat -p windows m0Run --no-daemon` does one run of everything above against an installed SkyWing without changing it: SkyWing runs on isolated preferences and data, so the installed copy and the owner's library and settings stay as they are. The run takes the desktop, so ask the owner first. Set these in the environment:
- `SKYWING_TEST_IMAGE`: the folder that holds `SkyWing.exe`, for example `C:\Program Files\SkyWing`.
- `SKYWING_M0_CLIP`: a local clip that outlasts the warm-up plus the window. A clip on a network share is refused.
- `SKYWING_M0_OUT`: a results folder outside this checkout and outside OneDrive.
- `SKYWING_M0_LABEL`: a name for the run (letters, digits, `-` and `_`).
- `SKYWING_M0_VRR`: the G-SYNC / FreeSync setting, `true` or `false`, or `auto` to leave it unset so SkyWing detects it.
- `SKYWING_M0_POWER`: `ac` or `battery`. The run doesn't start if the laptop is on the other.
- Optional: `SKYWING_M0_QUALITY` (Fastest, Fast, Balanced, Better or Best; Balanced by default), `SKYWING_M0_WARMUP` and `SKYWING_M0_WINDOW` in seconds (60 and 600 by default), and `SKYWING_GPU` for run B. The run refuses to start if `SKYWING_VS_SHARE_FRAMES` or `SKYWING_D3D11_COMPAT` is set.

It seeds the settings above (motion on, 2×, the run's quality for every video class, "Lower automatically" off), opens the clip, goes full screen, copies report A after the warm-up and report B at the end of the window, and closes SkyWing. Each run gets its own folder with both reports, SkyWing's output and `summary.txt`: process CPU, private memory, the battery, the Windows power mode at the start and end, the report numbers, and anything that disturbed the window (another program in front, full screen lost, or keyboard or mouse input). Don't use the PC after starting a run, not even to type in another window: Windows then keeps SkyWing behind that window, and the run stops within 10 seconds. Windows lets a new window come to the front again once nobody has used the PC for its foreground lock time-out, 200 s by default. On the laptop, Windows reported 2147483647 ms (about 25 days) while the registry said 200 s. Something had changed the value after sign-in without changing the registry, so there the lock doesn't end. Start HWiNFO logging yourself; `summary.txt` gives the window's start and end times. The handhelds have no JDK or checkout, so they use the portable runner in section 4, which runs this harness on a Java runtime of its own.

**HWiNFO** (the laptop, and the handhelds if it is installed): sensors only. Start CSV logging before the clip; the beeps mark the window. Keep the CSV out of the repository, and out of OneDrive or any other synced folder, which would upload it while it is written.

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
| Date and window; power and fan mode | 23 November, 02:09:15–02:19:15; Windows power mode Balanced at the start and end; Lenovo mode Balance | 23 November, 02:50:01–03:00:02; Windows power mode Balanced at the start and end; Lenovo mode Balance |
| How NVIDIA was selected | — | `SKYWING_GPU=NVIDIA`, set by the harness for SkyWing only; the laptop in Hybrid mode |
| `Preferred motion GPU:` line | `Intel(R) Graphics; driver: 32.0.101.9175` | `NVIDIA GeForce RTX 5070 Ti Laptop GPU; driver: 621.24` |
| `Renderer:` line | `Intel(R) Graphics; preference: Intel D3D11; output: gpu-next` | `NVIDIA GeForce RTX 5070 Ti Laptop GPU; preference: NVIDIA D3D11, forced by SKYWING_GPU; output: gpu-next` |
| `Display:` line | `240.00020606660073 Hz; VRR activation: not measured` | `240.00020606660073 Hz; VRR activation: not measured` |
| Filter processing throughput (report B) | 59.91 fps. The filter output callbacks rose from 3,664 to 39,597 between the reports, about 60 a second. | 60.19 fps. The filter output callbacks rose from 3,742 to 39,738 between the reports, about 60 a second. |
| Drops in the 10 minutes (video + decoder) | 0 (`frame-drop-count` 0 → 0; `decoder-frame-drop-count` 0 → 0) | 0 (`frame-drop-count` 0 → 0; `decoder-frame-drop-count` 0 → 0) |
| Drops per minute | 0.0 | 0.0 |
| Restarts (expect 0) | 0 | 0 |
| Graph generations, report A → B | 1 → 1 | 1 → 1 |
| Process CPU (cores) | 2.06 | 1.79 |
| Private memory, MB: start → end of the window | 5,659 → 5,946 | 6,158 → 6,173 |
| CPU package power, average W | 53.8, including 7.2 for the Intel GPU | 44.0, including 0.0 for the Intel GPU |
| NVIDIA GPU power, average W | 3.5 | 20.1 |
| CPU package temperature, average / maximum °C | 68 / 80 | 71 / 79 |
| GPU temperature, average / maximum °C | Intel 45 / 46; NVIDIA 39 / 40 | Intel 43 / 45; NVIDIA 41 / 42 |
| Fans, average / maximum RPM | CPU 2,450 / 2,800; GPU 2,439 / 2,700; PCH 3,251 / 3,600 | CPU 2,780 / 2,800; GPU 2,686 / 2,700; PCH 3,581 / 3,600 |
| Anything visible (judder, artefacts, audio sync) | Nothing; the owner saw and heard nothing odd | Nothing; the owner saw and heard nothing odd |

Notes:
- Both runs used the automated route in section 2: the local clip from the start, full screen, plugged in. In neither window did another program come to the front, full screen drop or any keyboard or mouse input arrive.
- Run A replaces a first run A from earlier that night, which played from the network share (both of its reports said `File: network share`). Its numbers, for reference: 58.17 fps, 1 drop, 2.00 cores, 6,246 → 6,304 MB, 52.9 W CPU package (6.2 for the Intel GPU) and 3.5 W NVIDIA. The owner saw nothing wrong in it.
- A first attempt at run B (from 02:22) was discarded. Another program stayed the active window for 586 of the 600 seconds, so the taskbar and that program covered the video. Windows keeps a new window behind the active one when someone has just used another program. The harness now stops a run within 10 seconds when this happens.
- Both runs' reports say `G-SYNC / FreeSync: true`, `lower automatically: false` and `VRR capability: unknown; Display capability unavailable; choice: manual`.
- HWiNFO logged every 2 seconds, with gaps of up to 12 seconds. Run A's first sample in the window came 56 seconds after it started (228 samples); run B's came 11 seconds after (249 samples).
- CPU package plus NVIDIA power: 57.4 W in run A and 64.1 W in run B.
- Private memory rose by 287 MB during run A's window and by 15 MB during run B's (58 MB in the first run A). One run of each can't tell steady growth from memory that levels off; a longer run on the Intel path would show which.

## 4. Handhelds

OneXPlayer 3 (Intel Arc B390) first, then MSI Claw 8. Both use Intel for everything, so there is no GPU choice.

Clips: a local 1080p SDR clip and the laptop's 4K clip. The 1080p clip needs a standard frame rate (23.976, 24, 25, 29.97 or 30 fps; SkyWing can't smooth other rates, such as a variable frame rate) and at least 8 minutes of video: each run plays it from the start through the warm-up and the window, and SkyWing stops at the end of a clip. Use the same file on both handhelds and write down its frame rate. Multiplier 2×. Window: 5 minutes. Do the plugged-in runs first, then the battery runs. The handheld runner below does all eight runs and writes the rows. The snippet in section 2, with `$window = 300`, is the manual route.

**The handheld runner.** `.\gradlew.bat -p windows m0Runner --no-daemon` builds `windows\build\m0-runner\SkyWing-M0-runner`. The folder holds `Run M0.cmd`, `README.txt` with the owner's steps, a Java 17 runtime made with jlink, and the harness of section 2 (about 84 MB in all). It is a test tool for the owner's devices, not a SkyWing release. It installs nothing, changes no Windows setting and runs the installed SkyWing on preferences and data of its own, so the handheld's library and settings stay as they are.

On each handheld, the owner installs 0.1.101 if needed and copies the folder to the handheld's own drive. Then the owner copies the two clips into `clips\1080p` and `clips\4K`, charges the battery to 100 % with the charger left in, closes SkyWing and turns battery saver off. The owner double-clicks `Run M0.cmd`, leaves the handheld alone, unplugs the charger at the beep and brings back the `results` folder. The runner:
- refuses to start, saying what to fix, when its folder is on a network share, a USB stick, a memory card or OneDrive. It also refuses when 0.1.101 isn't installed, a clips folder doesn't hold exactly one video, a clip is on removable or network storage, or plugged-in runs are left and the charger is out;
- does the eight runs in table order, each with a 60-second warm-up and a 5-minute window, a minute apart;
- leaves "My screen uses G-SYNC / FreeSync" as SkyWing has it on the handheld: it reads the setting and doesn't change it;
- before each run, waits until nobody has touched the handheld for Windows' foreground lock time-out plus 5 seconds (205 seconds by default). When the time-out is over 10 minutes, as on the laptop, it doesn't wait, and SkyWing comes to the front only if nobody touches the handheld after the start;
- after the plugged-in runs, beeps until the charger is out, waits 30 seconds and checks the battery: at least 40 % and battery saver off;
- stops when report B doesn't show the run's video class, SDR, 2× and quality. A run that another program covered, or that left full screen, doesn't count, and two such runs in a row stop the runner;
- keeps a run in which SkyWing turned smoothing off by itself, because it couldn't keep up at 2×, as a row: "Didn't hold 2×: SkyWing turned smoothing off during the warm-up" (or "during the measured window"), with the Windows power mode. That holds only when nobody touched the handheld and nothing covered SkyWing; otherwise the run doesn't count. Runners built before 23 November stopped such a run with "No push button labelled 'Copy playback report'";
- writes `results\<handheld>\M0-table.md` with the rows for the tables below, `runner-log.txt` with everything it printed, and a folder per run with both reports and `summary.txt`. Started again, it does only the runs that have no row.

HWiNFO isn't part of the runner. Start its logging yourself if it is installed.

**Laptop trial of the runner** (23 November, 05:30–05:34). `Run M0.cmd trial` in a copy of the folder ran the four plugged-in runs against the installed 0.1.101, with a 10-second warm-up and 30-second windows. It used a generated 1080p clip at 24000/1001 fps and the laptop's 4K clip. All four runs counted: no other program came to the front, full screen held and no input arrived. The table and `runner-log.txt` were written. 4K Balanced dropped 2 frames in its 30 seconds and the other runs none, but trial numbers aren't measurements. The battery half wasn't tried. `verify.ps1 -Mode full` passed straight after (147 tests).

### OneXPlayer 3 (Intel Arc B390)

23 November, 15:46–16:45, with the handheld runner and the installed 0.1.101 (`Build:` line as in section 1). The same two clips as the Claw.

| | Value |
|---|---|
| `Preferred motion GPU:` line | `Intel(R) Arc(TM) B390 GPU; driver: 32.0.101.9175` |
| `Renderer:` line | `Intel(R) Arc(TM) B390 GPU; preference: Intel D3D11; output: gpu-next` |
| `Display:` line | `144.00040424002876 Hz; VRR activation: not measured` |
| Power or TDP mode | Windows power mode Best performance at the start and end of every run. PL1 20 W, PL2 21 W and CPU boost off, set by the owner before the runs and still set after them. |
| 1080p clip frame rate | 23.976 fps (`source 23.97599983215332 fps`) |

| Clip | Quality | Power | Drops in 5 min | Drops per minute | Restarts | Process CPU (cores) | Private memory, MB (start → end) | Battery drain (W; % per hour) | Notes |
|---|---|---|---|---|---|---|---|---|---|
| 1080p | Fast | Plugged in | 8 | 1.6 | 0 | 0.99 | 1,703 → 1,748 | — | Filter throughput 47.709 fps, target 47.95 fps. The drops came early: see the notes |
| 1080p | Balanced | Plugged in | 0 | 0.0 | 0 | 1.43 | 1,872 → 1,894 | — | Filter throughput 49.746 fps, target 47.95 fps |
| 4K | Fast | Plugged in | 0 | 0.0 | 0 | 2.31 | 3,878 → 3,918 | — | Filter throughput 58.36 fps, target 59.94 fps |
| 4K | Balanced | Plugged in | 0 | 0.0 | 0 | 4.66 | 4,732 → 4,773 | — | Filter throughput 60.288 fps, target 59.94 fps |
| 1080p | Fast | Battery | 0 | 0.0 | 0 | 1.01 | 1,711 → 1,739 | 11.0; 12.2 | Filter throughput 47.006 fps, target 47.95 fps |
| 1080p | Balanced | Battery | 0 | 0.0 | 0 | 1.45 | 1,922 → 1,965 | 12.1; 13.4 | Filter throughput 49.139 fps, target 47.95 fps |
| 4K | Fast | Battery | 0 | 0.0 | 0 | 2.35 | 3,649 → 3,912 | 20.2; 22.3 | Filter throughput 58.017 fps, target 59.94 fps |
| 4K | Balanced | Battery | 0 | 0.0 | 0 | 4.67 | 4,731 → 4,775 | 23.1; 25.5 | Filter throughput 59.992 fps, target 59.94 fps |

Notes:
- All eight runs counted. In no window did another program come to the front, full screen drop or any keyboard or mouse input arrive. Every report B showed the run's class, SDR, 2× and quality, a local file and 1 → 1 graph generations. The filter output callbacks ran at 47.8–48.0 a second at 1080p and 59.9–60.0 at 4K.
- The 8 drops in 1080p Fast plugged in came from a slow start: report A, at the end of the warm-up, showed 34 drops in its last minute, and report B showed none in its last minute. That run was the first after the runner started. The same clip at Balanced, which does more work, and at Fast on battery dropped nothing, so the cause was probably the start rather than too little speed. Rerunning it would confirm that.
- Every report says `G-SYNC / FreeSync: false`, `lower automatically: false` and `VRR capability: unknown; Display capability unavailable; choice: automatic`. On the Claw the choice was manual and on. At 2×, the 1080p target of 47.95 fps fits the 144 Hz refresh (3 refreshes a frame); the 4K target of 59.94 fps doesn't.
- Process CPU is about twice the Claw's, but the battery drain is lower: 11.0 W against 14.2 W at 1080p Fast, and 23.1 W against 40.0 W at 4K Balanced. Process CPU counts the time cores are busy, not the work done. Under a 20 W limit with CPU boost off, the cores run at lower clocks, so the same work keeps them busy longer while using less energy. Compare watts between the handhelds, not cores. The two also differ in processor, screen, power limit and boost, so this doesn't separate the causes; the power-limit runs of plan 5.7 item 5 will.
- At these drains a full battery lasts about 8.2 hours for 1080p Fast and about 3.9 hours for 4K Balanced. Balanced at 1080p took 1.1 W more than Fast.

### MSI Claw 8

The Claw 8 AI+ (A2VM), 23 November, 15:10–16:07, with the handheld runner and the installed 0.1.101 (`Build:` line as in section 1).

| | Value |
|---|---|
| `Preferred motion GPU:` line | `Intel(R) Arc(TM) 140V GPU (16GB); driver: 32.0.101.9175` |
| `Renderer:` line | `Intel(R) Arc(TM) 140V GPU (16GB); preference: Intel D3D11; output: gpu-next` |
| `Display:` line | `120.0021299902629 Hz; VRR activation: not measured` |
| Power or TDP mode | Windows power mode Best performance at the start and end of every run. MSI's mode was 30 W when the owner checked after the runs. Nobody set it for the runs (the runner only reads the Windows power mode), and whether it stayed 30 W on battery wasn't logged. |
| 1080p clip frame rate | 23.976 fps (`source 23.97599983215332 fps`) |

| Clip | Quality | Power | Drops in 5 min | Drops per minute | Restarts | Process CPU (cores) | Private memory, MB (start → end) | Battery drain (W; % per hour) | Notes |
|---|---|---|---|---|---|---|---|---|---|
| 1080p | Fast | Plugged in | 0 | 0.0 | 0 | 0.48 | 1,544 → 1,561 | — | Filter throughput 47.006 fps, target 47.95 fps |
| 1080p | Balanced | Plugged in | 0 | 0.0 | 0 | 0.65 | 1,724 → 1,742 | — | Filter throughput 49.665 fps, target 47.95 fps |
| 4K | Fast | Plugged in | 0 | 0.0 | 0 | 1.20 | 3,162 → 3,183 | — | Filter throughput 60.028 fps, target 59.94 fps |
| 4K | Balanced | Plugged in | 0 | 0.0 | 0 | 2.14 | 4,035 → 4,049 | — | Filter throughput 60.425 fps, target 59.94 fps |
| 1080p | Fast | Battery | 0 | 0.0 | 0 | 0.47 | 1,527 → 1,544 | 14.2; 20.3 | Filter throughput 48.398 fps, target 47.95 fps |
| 1080p | Balanced | Battery | 0 | 0.0 | 0 | 0.64 | 1,740 → 1,756 | 18.5; 26.4 | Filter throughput 46.838 fps, target 47.95 fps |
| 4K | Fast | Battery | 0 | 0.0 | 0 | 1.16 | 3,155 → 3,176 | 28.2; 40.3 | Filter throughput 59.289 fps, target 59.94 fps |
| 4K | Balanced | Battery | 0 | 0.0 | 0 | 2.06 | 4,083 → 4,099 | 40.0; 57.2 | Filter throughput 61.701 fps, target 59.94 fps |

Notes:
- All eight runs counted. In no window did another program come to the front, full screen drop or any keyboard or mouse input arrive. Every report B showed the run's class, SDR, 2× and quality, a local file, 0 engine errors and 1 → 1 graph generations.
- Every report says `G-SYNC / FreeSync: true`, `lower automatically: false` and `VRR capability: unknown; Display capability unavailable; choice: manual`. At 2×, the 1080p target of 47.95 fps doesn't divide the 120 Hz refresh.
- The filter throughput is the figure in report B only, not an average over the window. Two 1080p runs read just below their target (47.006 and 46.838 fps) and still dropped nothing.
- At these drains a full battery lasts about 4.9 hours for 1080p Fast and about 1.7 hours for 4K Balanced. Balanced at 1080p took 4.3 W more than Fast.
- Earlier attempts that day don't count. The first 1080p clip reported 23.06 fps, which 0.1.101 can't smooth: SkyWing showed "Waiting for source frame rate" and the runner timed out. That clip was replaced. In the next attempt, Mouse Without Borders on the Claw came to the front three times when the PC's mouse crossed onto it (for 55, 14 and 295 seconds). Windows then kept SkyWing behind it, so the runner couldn't press Motion options and those runs stopped. No input reached the Claw in the runs above.

#### Claw 8 on battery at a low power limit (plan 5.7, item 5)

23 November, 16:39–16:58: the four battery runs again, with the same build, clips and runner. The plugged-in rows above were kept.

| | Value |
|---|---|
| Power or TDP mode | The owner set MSI's limits to PL1 8 W and PL2 8–9 W, and chose Windows Best power efficiency. The runner read Windows power mode Best performance for battery (the `ActiveOverlayDcPowerScheme` value) at the start of every run and at the end of both 1080p runs. Which program changed it, or whether the choice went to the plugged-in setting, isn't known. |

| Clip | Quality | Power | Drops in 5 min | Drops per minute | Restarts | Process CPU (cores) | Private memory, MB (start → end) | Battery drain (W; % per hour) | Notes |
|---|---|---|---|---|---|---|---|---|---|
| 1080p | Fast | Battery | 0 | 0.0 | 0 | 0.56 | 1,540 → 1,557 | 15.9; 22.7 | Filter throughput 48.866 fps, target 47.95 fps |
| 1080p | Balanced | Battery | 0 | 0.0 | 0 | 0.93 | 1,752 → 1,768 | 15.6; 22.3 | Filter throughput 48.794 fps, target 47.95 fps |
| 4K | Fast | Battery | — | — | — | — | — | — | Didn't hold 2×: SkyWing stopped smoothing during the 60-second warm-up |
| 4K | Balanced | Battery | — | — | — | — | — | — | Didn't hold 2×: SkyWing stopped smoothing during the 60-second warm-up |

Notes:
- In both 1080p windows nothing came to the front, full screen held and no input arrived. Report B showed the run's quality, 0 restarts and 1 → 1 graph generations.
- Against the battery runs at 30 W: 1080p Fast drew 1.7 W more (15.9 against 14.2 W), and 1080p Balanced drew 2.9 W less (15.6 against 18.5 W) while using more CPU time (0.93 against 0.64 cores). The screen was at its lowest brightness in both sets (the owner set it), so brightness doesn't explain the difference. The 8 W limit appears to have taken effect: 4K couldn't hold 2×, and Balanced drew less while using more CPU time. Why Fast drew more isn't known. Each figure comes from one 5-minute run, so run-to-run spread isn't measured.
- In both 4K runs, after the warm-up the player bar read "Smoothing: Off" and Motion options showed the switch off. With "Lower automatically" off and 2× already the lowest stop, SkyWing turns smoothing off by itself only when it can't keep up: "Cannot keep up at 2×; smoothing stopped" (`EmbeddedMpv.kt`). So 4K 2× held neither Fast nor Balanced at this setting. The runner reported a missing "Copy playback report" button instead, and stopped after the second such run.

#### Claw 8 on battery at 15 W in Best power efficiency (plan 5.7, item 5)

23 November, 17:25–17:54: the four battery runs again, with the same build, clips and runner. The owner moved the 8 W battery folders out first, so all four ran. The plugged-in rows above were kept.

| | Value |
|---|---|
| Power or TDP mode | MSI's limits PL1 15 W and PL2 16 W, set by the owner. Windows power mode Best power efficiency, as the owner chose it; the runner read it for battery at the start and end of every run. Screen at its lowest brightness, as in the other battery sets. |

| Clip | Quality | Power | Drops in 5 min | Drops per minute | Restarts | Process CPU (cores) | Private memory, MB (start → end) | Battery drain (W; % per hour) | Notes |
|---|---|---|---|---|---|---|---|---|---|
| 1080p | Fast | Battery | 0 | 0.0 | 0 | 1.30 | 1,559 → 1,576 | 8.4; 12.0 | Filter throughput 49.096 fps, target 47.95 fps |
| 1080p | Balanced | Battery | 0 | 0.0 | 0 | 1.67 | 1,766 → 1,777 | 9.7; 13.8 | Filter throughput 47.175 fps, target 47.95 fps |
| 4K | Fast | Battery | 0 | 0.0 | 0 | 2.45 | 2,966 → 2,986 | 20.0; 28.6 | Filter throughput 61.29 fps, target 59.94 fps |
| 4K | Balanced | Battery | 0 | 0.0 | 0 | 4.69 | 4,129 → 4,167 | 21.1; 30.1 | Filter throughput 56.056 fps, target 59.94 fps |

Notes:
- All four runs counted. In no window did another program come to the front, full screen drop or any input arrive. Every report B showed the run's quality, 0 restarts and 1 → 1 graph generations.
- Against the battery runs at 30 W in Best performance, the drain fell by 41 % at 1080p Fast (8.4 against 14.2 W), 48 % at 1080p Balanced (9.7 against 18.5 W), 29 % at 4K Fast (20.0 against 28.2 W) and 47 % at 4K Balanced (21.1 against 40.0 W). A full battery would last about 8.3 hours at 1080p Fast instead of 4.9, and about 3.3 hours at 4K Balanced instead of 1.7.
- Process CPU rose (1.30 against 0.47 cores at 1080p Fast) while the drain fell: at lower clocks the cores are busy for longer. Process CPU is not a power figure.
- Both 4K runs dropped frames during the warm-up only: `frame-drop-count` was already 1 (Fast) and 2 (Balanced) at report A and didn't move in the window. 4K Balanced's report B throughput read 56.056 fps, below its target, yet the filter delivered 60.1 frames a second over the window and nothing dropped.
- Two settings changed together against the 30 W runs: MSI's limit and the Windows power mode. These runs can't say how much of the saving each gave. The 8 W runs, which the runner read as Best performance, drew more at 1080p (15.9 and 15.6 W), which suggests the Windows mode matters more than the limit there.

## 5. What the numbers decide

- **Automatic GPU (plan 5.3):** Automatic stays all-Intel unless run A drops frames, or run B holds 2× with clearly fewer drops at an acceptable power cost.
  Result of the laptop runs on 23 November: Automatic stays all-Intel. Run A held 2× without a drop, and run B dropped no fewer while drawing about 6.7 W more (CPU package plus NVIDIA). The owner saw and heard nothing odd in either run.
- **Handheld default (plan 5.7, item 3):** the quality that holds 2× at 1080p without drops on battery becomes the handheld default. The plan starts from 1080p 2× Fast.
  Result of the handheld runs on 23 November: Fast and Balanced both held 1080p 2× on battery without a drop on both handhelds. Fast drew 4.3 W less than Balanced on the Claw and 1.1 W less on the OneXPlayer 3. Both also held 4K 2× on battery without a drop.
  Owner decision the same day: Fast is the default quality on every PC, not only the handhelds. A quality the user picks for a video class is saved on that PC and kept. Classes nobody has set use Fast.

## 6. How this build picks its GPU

This differs from the brief, whose "Performance forced to NVIDIA … (or `SKYWING_*` override)" didn't exist: Performance is part of plan 5.3 (M2). SkyWing uses the GPU that drives the monitor its window is on when it first creates the video surface (`WindowsDisplayAdapter`), both to draw the video (mpv's `d3d11-adapter`) and for motion (`SKYWING_OPENCL_VENDOR`, which SkyWing sets itself). If Windows names neither an Intel nor an NVIDIA adapter, it asks for NVIDIA. Windows' per-app graphics setting doesn't change the choice, because SkyWing passes the adapter to mpv by name.

For run B, M0 adds the test-only override `SKYWING_GPU` (`NVIDIA` or `Intel`, set before SkyWing starts). It changes the GPU for drawing and motion only; display queries such as VRR still use the monitor's GPU. If mpv refuses the named GPU (NVIDIA on a handheld, for example), SkyWing uses Windows' choice and the `Renderer:` line says so. The plan 5.3 setting replaces the override.
