package com.skywing.desktop.automation

import com.skywing.desktop.NetworkPaths
import com.skywing.desktop.playback.MotionQuality
import com.skywing.desktop.playback.MotionStops
import com.skywing.desktop.playback.SmoothingCapabilities
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.exitProcess

/**
 * The handheld M0 runner: the eight runs of `docs/modernise/M0-RESULTS.md` section 4 on one handheld, one after the
 * other, with nobody at the device except to unplug the charger once. Each run is an [M0Run], the laptop harness, so
 * SkyWing runs on isolated data and the installed copy, the library and the settings stay as they are. The runs are
 * 1080p Fast, 1080p Balanced, 4K Fast and 4K Balanced plugged in, then the same four on battery, each with a minute's
 * warm-up and a 5-minute window, a minute apart.
 *
 * `gradlew -p windows m0Runner` builds a portable folder for it: a Java runtime, the jars, `Run M0.cmd`, `README.txt`
 * and empty `clips\1080p` and `clips\4K` folders. `Run M0.cmd` starts it. `Run M0.cmd trial` does a short check
 * instead: the plugged-in half with a 10-second warm-up and a 30-second window, which tests the runner, not the device.
 * The runner:
 * - refuses to start, saying what to fix, when the folder isn't on the device's own drive or is in OneDrive, SkyWing
 *   [M0_BUILD] isn't installed, a clips folder doesn't hold exactly one video, or plugged-in runs are left and the
 *   charger is out
 * - keeps the device and its screen awake while it runs, as a video player does
 * - before each run, waits until nobody has touched the device for Windows' foreground lock time-out (about 3
 *   minutes): until then, Windows keeps a new SkyWing window behind the program used last
 * - after the plugged-in runs, beeps until the charger is out (for up to 30 minutes), waits 30 seconds and checks the
 *   battery: at least 40 % charge and battery saver off (section 2)
 * - checks report B of each run: the video's size and SDR range fit its folder, and SkyWing played at 2× in the run's
 *   quality. A run that another program covered, or that left full screen, doesn't count.
 * - writes `results\<device>\M0-table.md` (section 4's rows) after each run, and beeps when it is done
 * - copies what it prints to `results\<device>\runner-log.txt`, so the results folder says why it stopped
 *
 * Started again, it does only the runs that have no row yet (`row.md` in the run's folder). The results folder is
 * named after the device, so one copy of the runner folder can visit both handhelds. SKYWING_M0_CLIP_1080P and
 * SKYWING_M0_CLIP_4K name the clips instead of the clips folders (for a trial on another machine). SKYWING_M0_VRR
 * (true, false or auto) overrides "My screen uses G-SYNC / FreeSync". Otherwise the setting is as the installed SkyWing
 * has it on the device (read, not changed), or left to SkyWing's own detection when it isn't set: section 2 says to
 * leave it as it is.
 */
fun main(args: Array<String>) {
    val trial = args.any { it.equals("trial", ignoreCase = true) }
    // exitProcess: AWT (the clipboard) and the Access Bridge client keep threads that would hold the JVM open.
    val code = try {
        HandheldSystem.keepConsoleRunning()
        HandheldSystem.keepAwake()
        HandheldRunner(trial, System.getenv(), Path.of("").toAbsolutePath()).runAll()
    } catch (refused: IllegalArgumentException) {
        HandheldSystem.beep(3)
        println()
        println("Stopped: ${refused.message}")
        2
    } catch (error: Throwable) {
        HandheldSystem.beep(3)
        println()
        println("Stopped by an error:")
        error.printStackTrace(System.out)
        3
    }
    exitProcess(code)
}

/** The SkyWing build the M0 runs measure: section 1 of M0-RESULTS.md. */
internal const val M0_BUILD = "0.1.101"

/** A clips folder of the runner, and the video class SkyWing must put its video in ([MotionStops.type]). */
internal enum class ClipSize(val folder: String, val motionClass: String, private val wanted: String) {
    HD_1080P("1080p", "1080", "one 1080p SDR video (the same file on both handhelds)"),
    UHD_4K("4K", "4k", "the laptop's 4K SDR clip");

    /** The environment variable that names the clip instead of the folder. */
    private val override: String get() = "SKYWING_M0_CLIP_${folder.uppercase(Locale.ROOT)}"

    /** The one video in `clips\<folder>` of [runner], or the file [environment] names. */
    fun clip(runner: Path, environment: Map<String, String>): Path {
        environment[override]?.trim()?.takeIf { it.isNotEmpty() }?.let { return Path.of(it) }
        val folder = runner.resolve("clips").resolve(folder)
        val videos = if (folder.isDirectory()) {
            folder.listDirectoryEntries().filter { it.isRegularFile() && it.extension.lowercase(Locale.ROOT) in VIDEO_EXTENSIONS }
        } else {
            emptyList()
        }
        require(videos.isNotEmpty()) { "clips\\${this.folder} has no video. Copy $wanted into it." }
        require(videos.size == 1) { "clips\\${this.folder} has ${videos.size} videos. Leave only one in it." }
        return videos.single()
    }

    private companion object {
        /** The file types SkyWing lists as videos (LocalFolderLibrary). */
        val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "wmv", "ts", "m2ts", "hevc", "flv", "mpg", "mpeg")
    }
}

/** The warm-up, the measured window and the pause between runs. */
internal data class HandheldTiming(val warmupSeconds: Long, val windowSeconds: Long, val coolDownSeconds: Long) {
    companion object {
        /** Section 4: a minute to settle, a 5-minute window and a minute between runs. */
        val FULL = HandheldTiming(warmupSeconds = 60, windowSeconds = 300, coolDownSeconds = 60)

        /** A check of the runner itself; its numbers aren't measurements. */
        val TRIAL = HandheldTiming(warmupSeconds = 10, windowSeconds = 30, coolDownSeconds = 5)
    }
}

/** One row of the section 4 table. */
internal data class HandheldRun(val clip: ClipSize, val quality: MotionQuality, val onAc: Boolean, val trial: Boolean) {
    /** The run's results folder ends in this: "1080p-fast-ac", or "trial-1080p-fast-ac" in a trial. */
    val label: String
        get() = listOfNotNull(
            "trial".takeIf { trial },
            clip.folder.lowercase(Locale.ROOT),
            quality.name.lowercase(Locale.ROOT),
            if (onAc) "ac" else "battery",
        ).joinToString("-")

    val title: String get() = "${clip.folder} ${quality.label}, ${if (onAc) "plugged in" else "on battery"}"
    val power: String get() = if (onAc) "Plugged in" else "Battery"
    val timing: HandheldTiming get() = if (trial) HandheldTiming.TRIAL else HandheldTiming.FULL

    /** The run's settings, as [M0Settings.fromEnvironment] reads them. A null [vrr] leaves VRR to SkyWing. */
    fun environment(clipFile: Path, results: Path, vrr: Boolean?): Map<String, String> = mapOf(
        "SKYWING_M0_CLIP" to clipFile.toString(),
        "SKYWING_M0_OUT" to results.toString(),
        "SKYWING_M0_LABEL" to label,
        "SKYWING_M0_QUALITY" to quality.label,
        "SKYWING_M0_POWER" to if (onAc) "ac" else "battery",
        "SKYWING_M0_VRR" to (vrr?.toString() ?: "auto"),
        "SKYWING_M0_WARMUP" to timing.warmupSeconds.toString(),
        "SKYWING_M0_WINDOW" to timing.windowSeconds.toString(),
    )
}

internal object HandheldPlan {
    private val QUALITIES = listOf(MotionQuality.FAST, MotionQuality.BALANCED)

    /** Section 4's rows in order: plugged in, then on battery. A trial does only the plugged-in half. */
    fun runs(trial: Boolean): List<HandheldRun> = (if (trial) listOf(true) else listOf(true, false)).flatMap { onAc ->
        ClipSize.entries.flatMap { clip -> QUALITIES.map { quality -> HandheldRun(clip, quality, onAc, trial) } }
    }
}

/** The newest folder in [results] of a finished run with this [label] (`<date>-<time>-<label>` holding row.md), or null. */
internal fun finishedRun(results: Path, label: String): Path? {
    if (!results.isDirectory()) return null
    val name = Regex("\\d{8}-\\d{6}-${Regex.escape(label)}")
    return results.listDirectoryEntries().filter { name.matches(it.name) && it.resolve("row.md").exists() }.maxByOrNull { it.name }
}

/** The data of the REG_SZ value [name] in `reg.exe query` output, or null when the output doesn't have it. */
internal fun registryString(output: String, name: String): String? =
    Regex("^[ \\t]+${Regex.escape(name)}[ \\t]+REG_SZ(?:[ \\t]+(.*?))?[ \\t]*\\r?$", RegexOption.MULTILINE).find(output)?.groupValues?.get(1)

/** One REG_SZ value, read with reg.exe (which only reads), or null when it isn't there. */
internal fun registryValue(key: String, name: String): String? = runCatching {
    val process = ProcessBuilder("reg.exe", "query", key, "/v", name).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    if (process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) registryString(output, name) else null
}.getOrNull()?.takeIf { it.isNotBlank() }

/**
 * Why the runner can't work from [folder], or null. It writes its results there while it runs, so the folder must be on
 * the device's own drive: not on a share, a USB stick or a memory card ([removable]), and not in OneDrive.
 */
internal fun runnerFolderRefusal(folder: Path, environment: Map<String, String>, removable: (Path) -> Boolean): String? {
    val move = "Copy the whole folder to the device's own drive, for example to C:\\SkyWing-M0, and start Run M0.cmd there."
    return when {
        // First, and from the path alone, so a share is never queried.
        NetworkPaths.isNetwork(folder.toString()) -> "Run M0.cmd is on a network drive or share. $move"
        removable(folder) -> "Run M0.cmd is on a USB stick or memory card. $move"
        inOneDrive(folder, environment) -> "Run M0.cmd is in OneDrive, which would upload the results while they are written. $move"
        else -> null
    }
}

/** A folder name for the device: its product name, or the computer's name when the firmware gives a placeholder. */
internal fun machineFolder(product: String?, computerName: String?): String {
    fun clean(text: String?) = text?.replace(Regex("[^A-Za-z0-9]+"), "-")?.trim('-')?.take(60)?.trim('-')?.takeIf { it.isNotEmpty() }
    val placeholder = product?.trim()?.lowercase(Locale.ROOT) in FIRMWARE_PLACEHOLDERS
    return (if (placeholder) null else clean(product)) ?: clean(computerName) ?: "this-device"
}

private val FIRMWARE_PLACEHOLDERS = setOf("default string", "to be filled by o.e.m.", "system product name", "not applicable", "o.e.m.", "none")

/** What Windows reports about the power ([HandheldSystem.powerStatus]); null where it doesn't know. */
internal data class PowerStatus(val onAc: Boolean?, val percent: Int?, val batterySaver: Boolean)

/** Why a battery run can't start now (section 2: at least 40 % charge, battery saver off), or null when it can. */
internal fun batteryRefusal(status: PowerStatus): String? = when {
    status.onAc == null || status.percent == null -> "Windows doesn't report the battery state, so the battery runs can't be measured."
    status.onAc -> "The charger is plugged in. Unplug it before the battery runs: run Run M0.cmd again and unplug it at the beep."
    status.percent < 40 -> "The battery is at ${status.percent} %. Charge it to at least 80 %, then run Run M0.cmd again; the finished runs are kept."
    status.batterySaver -> "Battery saver is on. Turn it off (Settings > System > Power & battery), then run Run M0.cmd again."
    else -> null
}

/**
 * Why report B shows the run didn't measure what it should, or null. The video must be in its folder's class and SDR
 * (section 4), and SkyWing must have played it at 2× in the run's quality.
 */
internal fun reportMismatch(run: HandheldRun, report: PlaybackReport): String? {
    val where = "The video in clips\\${run.clip.folder}"
    val (width, height) = report.size ?: return "Report B doesn't give the video's size, so the video in clips\\${run.clip.folder} can't be checked."
    val hdr = report.transfer in HDR_TRANSFERS
    val size = MotionStops.type(SmoothingCapabilities(width = width, height = height, hdrVideo = hdr)).substringBefore('-')
    return when {
        hdr -> "$where is HDR (transfer ${report.transfer}). Use an SDR video."
        size != run.clip.motionClass -> "$where is $width × $height, which SkyWing treats as ${CLASS_NAMES[size] ?: size}. Put ${run.clip.folder} video there."
        report.quality != run.quality.label -> "SkyWing played at ${report.quality ?: "an unknown quality"}, not ${run.quality.label}."
        report.multiplier != "2×" ->
            "SkyWing didn't play at 2× (Multiplier: ${report.multiplier ?: "unknown"}). The screen may be too slow for twice this clip's frame rate: use a 24, 25 or 30 fps clip."
        else -> null
    }
}

private val HDR_TRANSFERS = setOf("pq", "hlg")
private val CLASS_NAMES = mapOf("1080" to "1080p", "1440" to "1440p", "4k" to "4K")

/** The run's row of the section 4 table. */
internal fun tableRow(run: HandheldRun, outcome: M0Outcome): String {
    val battery = when {
        run.onAc -> "—"
        else -> outcome.battery?.let { "%.1f; %.1f".format(Locale.ROOT, it.watts, it.percentPerHour) } ?: "unavailable"
    }
    val (startMode, endMode) = outcome.powerModes
    val (generationsA, generationsB) = outcome.generations
    val notes = buildList {
        add(if (startMode == endMode) "Windows power mode $startMode" else "Windows power mode $startMode at the start, $endMode at the end")
        if (generationsA != generationsB) add("graph generations ${generationsA ?: "?"} → ${generationsB ?: "?"}")
        if (outcome.disturbances.inputSeconds > 0) add("keyboard or mouse input in ${outcome.disturbances.inputSeconds} s")
        add("filter throughput ${outcome.reportB.throughput ?: "unavailable"} fps, target ${outcome.reportB.target ?: "unavailable"} fps")
        if (run.trial) add("trial, ${run.timing.windowSeconds} s window")
    }
    return listOf(
        run.clip.folder,
        run.quality.label,
        run.power,
        outcome.drops?.toString() ?: "unavailable",
        outcome.dropsPerMinute?.let { "%.1f".format(Locale.ROOT, it) } ?: "unavailable",
        outcome.restarts?.toString() ?: "unavailable",
        "%.2f".format(Locale.ROOT, outcome.cores),
        "%,.0f → %,.0f".format(Locale.ROOT, outcome.memoryStartMb, outcome.memoryEndMb),
        battery,
        notes.joinToString("; "),
    ).joinToString(" | ", prefix = "| ", postfix = " |")
}

/** A finished run as its folder keeps it: the table row and report B. */
internal data class FinishedRun(val row: String, val reportB: PlaybackReport) {
    companion object {
        fun load(folder: Path) = FinishedRun(
            folder.resolve("row.md").readText().trim(),
            PlaybackReport(folder.resolve("report-B.txt").takeIf { it.exists() }?.readText().orEmpty()),
        )
    }
}

/** The results table for [device]: section 4's device lines, from the reports of [finished] runs, and a row per run. */
internal fun handheldTable(device: String, plan: List<HandheldRun>, finished: Map<HandheldRun, FinishedRun>): String = buildString {
    val report = finished.values.firstOrNull()?.reportB
    val hd = finished.entries.firstOrNull { it.key.clip == ClipSize.HD_1080P }?.value?.reportB
    fun code(text: String?) = text?.let { "`$it`" }.orEmpty()
    val window = plan.first().timing.windowSeconds
    appendLine("# SkyWing M0 handheld runs: $device")
    appendLine()
    appendLine("Written by Run M0.cmd. The rows below go into section 4 of `windows/docs/modernise/M0-RESULTS.md`.")
    appendLine("SkyWing ${code(report?.line("SkyWing "))}; ${code(report?.line("Build: "))}")
    appendLine()
    appendLine("| | Value |")
    appendLine("|---|---|")
    appendLine("| `Preferred motion GPU:` line | ${code(report?.line("Preferred motion GPU: "))} |")
    appendLine("| `Renderer:` line | ${code(report?.line("Renderer: "))} |")
    appendLine("| `Display:` line | ${code(report?.line("Display: "))} |")
    appendLine("| Power or TDP mode | Windows: in each row's notes. The maker's mode (its app or overlay): write it here. |")
    appendLine("| 1080p clip frame rate | ${hd?.sourceFps?.let { "$it fps" }.orEmpty()} |")
    appendLine()
    val drops = if (window % 60 == 0L) "Drops in ${window / 60} min" else "Drops in $window s"
    appendLine("| Clip | Quality | Power | $drops | Drops per minute | Restarts | Process CPU (cores) | Private memory, MB (start → end) | Battery drain (W; % per hour) | Notes |")
    appendLine("|---|---|---|---|---|---|---|---|---|---|")
    for (run in plan) appendLine(finished[run]?.row ?: "| ${run.clip.folder} | ${run.quality.label} | ${run.power} | | | | | | | not run yet |")
}

/**
 * From now until the runner exits, copies what it prints to the end of [log] as well, after a line that marks this
 * start. The results folder then says why a run or the runner stopped, without anyone copying the console window.
 * Returns the new [System.out]. Closing it closes the log file and leaves the console open; the runner doesn't, as
 * the file closes when it exits.
 */
internal fun logOutput(log: Path): PrintStream {
    val console = System.out
    val file = FileOutputStream(log.toFile(), true)
    val started = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT))
    file.write("${System.lineSeparator()}=== Started $started${System.lineSeparator()}".toByteArray(Charsets.UTF_8))
    val both = object : OutputStream() {
        override fun write(b: Int) {
            console.write(b)
            file.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            console.write(b, off, len)
            file.write(b, off, len)
        }

        override fun flush() {
            console.flush()
            file.flush()
        }

        override fun close() {
            console.flush()
            file.close()
        }
    }
    return PrintStream(both, true, Charsets.UTF_8).also(System::setOut)
}

/** One start of the runner on one device. */
private class HandheldRunner(private val trial: Boolean, private val environment: Map<String, String>, private val runner: Path) {
    private val plan = HandheldPlan.runs(trial)
    private val timing = plan.first().timing
    private val image = Path.of(requireNotNull(environment["SKYWING_TEST_IMAGE"]?.takeIf { it.isNotBlank() }) { "Start the runner with Run M0.cmd." })
    private val product = registryValue(BIOS, "SystemProductName")
    private val device = listOfNotNull(registryValue(BIOS, "SystemManufacturer"), product).joinToString(" ").ifBlank { "this device" }
    private val results = runner.resolve("results").resolve(machineFolder(product, environment["COMPUTERNAME"]))
    private val table = results.resolve(if (trial) "M0-trial-table.md" else "M0-table.md")
    private var lastRunEnded: Long? = null

    /** Does every run without a row and returns the exit code: 0 when all count, 1 when some must be done again. */
    fun runAll(): Int {
        runnerFolderRefusal(runner, environment, HandheldSystem::removable)?.let { throw IllegalArgumentException(it) }
        results.createDirectories()
        // The results folder is what comes back from the device, so it gets what the console window shows as well.
        logOutput(results.resolve("runner-log.txt"))
        checkInstalled()
        val clips = ClipSize.entries.associateWith { it.clip(runner, environment) }
        for ((size, clip) in clips) require(!HandheldSystem.removable(clip)) { "The ${size.folder} clip is on a USB stick or memory card. Copy it to the device's own drive." }
        val (vrr, vrrSource) = vrrChoice()
        // The accessibility trees SkyWingTestApp saves when a run fails go with the results.
        System.setProperty(SkyWingTestApp.REPORTS_PROPERTY, results.resolve("diagnostics").toString())
        val todo = plan.filter { finishedRun(results, it.label) == null }
        println("SkyWing M0 ${if (trial) "trial" else "handheld runs"} (M0-RESULTS.md section 4) on $device")
        println("Results: $results")
        println("G-SYNC / FreeSync: $vrrSource")
        writeTable()
        if (todo.isEmpty()) {
            println("All ${plan.size} runs are already done. The table is in $table.")
            return 0
        }
        if (todo.any { it.onAc }) require(HandheldSystem.powerStatus().onAc == true) { "Plug in the charger: the first runs are plugged in. Then run Run M0.cmd again." }
        val minutes = (todo.size * (timing.warmupSeconds + timing.windowSeconds + timing.coolDownSeconds + 50) + 210) / 60
        println("${todo.size} of ${plan.size} runs to do, about $minutes minutes.${if (todo.any { !it.onAc }) " The device beeps when it is time to unplug the charger." else ""}")
        println("Don't touch the device until it beeps.")

        val notCounted = mutableListOf<HandheldRun>()
        var notCountedInARow = 0
        for ((index, run) in plan.withIndex()) {
            if (finishedRun(results, run.label) != null) continue
            if (run.onAc) {
                require(HandheldSystem.powerStatus().onAc == true) { "The charger is out, but ${run.title} runs plugged in. Plug it in and run Run M0.cmd again; the finished runs are kept." }
            } else {
                readyBattery()
            }
            coolDown()
            waitForQuiet()
            println()
            println("Run ${index + 1} of ${plan.size}: ${run.title}. Don't touch the device.")
            val settings = M0Settings.fromEnvironment(environment + run.environment(clips.getValue(run.clip), results, vrr), runner)
            val m0 = M0Run(settings).also { it.preflight() }
            val outcome = runCatching { m0.run() }
            lastRunEnded = System.currentTimeMillis()
            val problem = outcome.fold(onSuccess = { keep(run, it) }, onFailure = { "it stopped: ${it.message?.lineSequence()?.firstOrNull()}" })
            writeTable()
            if (problem == null) {
                notCountedInARow = 0
                println("Run ${index + 1} done.")
                continue
            }
            println("Run ${index + 1} doesn't count: $problem")
            notCounted += run
            notCountedInARow++
            require(notCountedInARow < 2) {
                "Two runs in a row didn't count. failure.txt in their folders in $results says why. Fix that and run Run M0.cmd again; the finished runs are kept."
            }
        }

        HandheldSystem.beep(3)
        println()
        if (notCounted.isNotEmpty()) {
            println("These runs don't count: ${notCounted.joinToString { it.title }}. Run Run M0.cmd again to do just those.")
            return 1
        }
        println(if (plan.any { !it.onAc }) "All runs are done. Plug the charger back in." else "All runs are done.")
        println("The table is in $table. Bring back the whole results folder.")
        return 0
    }

    private fun checkInstalled() {
        require(image.resolve("SkyWing.exe").isRegularFile()) { "SkyWing isn't installed in $image. Install SkyWing $M0_BUILD and run again." }
        val script = "(Get-Item -LiteralPath '${image.resolve("SkyWing.exe").toString().replace("'", "''")}').VersionInfo.ProductVersion"
        val version = runCatching { runPowerShell(script, "Reading SkyWing's version").trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
        if (version == null) {
            println("SkyWing's version can't be read here; the reports show it.")
        } else {
            require(version == M0_BUILD) { "SkyWing $version is installed, but the M0 runs measure $M0_BUILD (M0-RESULTS.md section 1). Install $M0_BUILD and run again." }
        }
    }

    /** "My screen uses G-SYNC / FreeSync" for every run, and where the choice came from. */
    private fun vrrChoice(): Pair<Boolean?, String> = when (val text = environment["SKYWING_M0_VRR"]?.trim()?.takeIf { it.isNotEmpty() }) {
        null -> when (val owner = registryValue(PLAYBACK, "motion.vrr")?.toBooleanStrictOrNull()) {
            null -> null to "not set in SkyWing on this device, so SkyWing detects it"
            else -> owner to "$owner, as SkyWing has it on this device"
        }
        "auto" -> null to "left to SkyWing (SKYWING_M0_VRR=auto)"
        else -> requireNotNull(text.toBooleanStrictOrNull()) { "SKYWING_M0_VRR must be true, false or auto." } to "$text (SKYWING_M0_VRR)"
    }

    /** Asks for the charger to come out (once, before the first battery run), then checks the battery. */
    private fun readyBattery() {
        if (HandheldSystem.powerStatus().onAc == true) {
            println()
            println("The plugged-in runs are done. Unplug the charger now.")
            println("The battery runs start about 30 seconds after it is out. Then leave the device alone again.")
            val deadline = System.currentTimeMillis() + UNPLUG_WAIT_MS
            var nextBeep = 0L
            while (HandheldSystem.powerStatus().onAc == true) {
                require(System.currentTimeMillis() < deadline) {
                    "The charger wasn't unplugged within 30 minutes. Run Run M0.cmd again to do the battery runs; the finished runs are kept."
                }
                if (System.currentTimeMillis() >= nextBeep) {
                    HandheldSystem.beep(2)
                    nextBeep = System.currentTimeMillis() + 60_000
                }
                Thread.sleep(5_000)
            }
            println("The charger is out. The battery runs start in 30 seconds.")
            Thread.sleep(30_000)
        }
        batteryRefusal(HandheldSystem.powerStatus())?.let { throw IllegalArgumentException(it) }
    }

    private fun coolDown() {
        val ended = lastRunEnded ?: return
        val wait = ended + timing.coolDownSeconds * 1000 - System.currentTimeMillis()
        if (wait > 0) {
            println("Next run in ${(wait + 999) / 1000} s.")
            Thread.sleep(wait)
        }
    }

    /** Waits until nobody has touched the device for Windows' foreground lock time-out, so SkyWing can come to the front. */
    private fun waitForQuiet() {
        val needed = HandheldSystem.foregroundLockTimeoutMs() + 5_000
        if (HandheldSystem.idleMs() >= needed) return
        println("Waiting until nobody has touched the device for ${needed / 1000} s: until then, Windows keeps SkyWing behind the program used last.")
        while (true) {
            val idle = HandheldSystem.idleMs()
            if (idle >= needed) return
            Thread.sleep((needed - idle).coerceIn(500, 5_000))
        }
    }

    /** Checks a measured run and keeps its row. Returns why it doesn't count, or null. A wrong clip or setting stops the runner. */
    private fun keep(run: HandheldRun, outcome: M0Outcome): String? {
        reportMismatch(run, outcome.reportB)?.let { mismatch ->
            outcome.folder.resolve("failure.txt").writeText("$mismatch\n")
            throw IllegalArgumentException(mismatch)
        }
        if (outcome.disturbances.spoiled) {
            val why = "another program was in front or SkyWing left full screen (${outcome.disturbances.description})"
            outcome.folder.resolve("failure.txt").writeText("This run doesn't count: $why.\n")
            return why
        }
        outcome.folder.resolve("row.md").writeText(tableRow(run, outcome) + "\n")
        return null
    }

    private fun writeTable() {
        val finished = plan.mapNotNull { run -> finishedRun(results, run.label)?.let { run to FinishedRun.load(it) } }.toMap()
        table.writeText(handheldTable(device, plan, finished))
    }

    private companion object {
        const val UNPLUG_WAIT_MS = 30 * 60 * 1000L
        const val BIOS = "HKLM\\HARDWARE\\DESCRIPTION\\System\\BIOS"

        /** Where Java keeps the installed SkyWing's playback settings (its WindowsPreferences node, all lower case). */
        val PLAYBACK = "HKCU\\Software\\JavaSoft\\Prefs\\" + MotionStatus.PLAYBACK_NODE.replace('/', '\\')
    }
}

/**
 * The few Windows calls the runner needs that [Win32] doesn't have. Only [keepConsoleRunning], [keepAwake] and [beep]
 * change anything, and only while the runner runs.
 */
internal object HandheldSystem {
    @Suppress("FunctionName")
    private interface Kernel32 : StdCallLibrary {
        fun SetThreadExecutionState(flags: Int): Int
        fun Beep(frequency: Int, milliseconds: Int): Boolean
        fun GetSystemPowerStatus(status: SystemPowerStatus): Boolean
        fun GetTickCount(): Int
        fun GetDriveTypeW(root: WString): Int
        fun GetStdHandle(which: Int): Pointer?
        fun GetConsoleMode(console: Pointer, mode: IntByReference): Boolean
        fun SetConsoleMode(console: Pointer, mode: Int): Boolean
    }

    @Suppress("FunctionName")
    private interface User32 : StdCallLibrary {
        fun SystemParametersInfoW(action: Int, param: Int, value: IntByReference, winIni: Int): Boolean
    }

    @Structure.FieldOrder("acLineStatus", "batteryFlag", "batteryLifePercent", "systemStatusFlag", "batteryLifeTime", "batteryFullLifeTime")
    class SystemPowerStatus : Structure() {
        @JvmField var acLineStatus: Byte = 0
        @JvmField var batteryFlag: Byte = 0
        @JvmField var batteryLifePercent: Byte = 0
        @JvmField var systemStatusFlag: Byte = 0
        @JvmField var batteryLifeTime: Int = 0
        @JvmField var batteryFullLifeTime: Int = 0
    }

    private val kernel32: Kernel32 by lazy { Native.load("kernel32", Kernel32::class.java) }
    private val user32: User32 by lazy { Native.load("user32", User32::class.java) }
    private const val ES_CONTINUOUS = Int.MIN_VALUE // 0x80000000
    private const val ES_SYSTEM_REQUIRED = 0x1
    private const val ES_DISPLAY_REQUIRED = 0x2
    private const val SPI_GETFOREGROUNDLOCKTIMEOUT = 0x2000
    private const val DRIVE_REMOVABLE = 2
    private const val NO_SYSTEM_BATTERY = 128
    private const val STD_INPUT_HANDLE = -10
    private const val ENABLE_QUICK_EDIT_MODE = 0x40
    private const val ENABLE_EXTENDED_FLAGS = 0x80

    /**
     * Turns QuickEdit off in the runner's own console window, until it closes. With it on, a tap in the window starts
     * a selection, and Windows then holds the runner's next line of output, and the runner, until a key is pressed.
     * Does nothing when the runner has no console (started from a script).
     */
    fun keepConsoleRunning() {
        val console = kernel32.GetStdHandle(STD_INPUT_HANDLE) ?: return
        val mode = IntByReference()
        if (kernel32.GetConsoleMode(console, mode)) kernel32.SetConsoleMode(console, (mode.value and ENABLE_QUICK_EDIT_MODE.inv()) or ENABLE_EXTENDED_FLAGS)
    }

    /** Keeps the device and its screen on until the runner exits, as a video player does. Changes no setting. */
    fun keepAwake() {
        check(kernel32.SetThreadExecutionState(ES_CONTINUOUS or ES_SYSTEM_REQUIRED or ES_DISPLAY_REQUIRED) != 0) { "Windows refused to keep the device awake." }
    }

    fun beep(times: Int) = repeat(times) {
        runCatching { kernel32.Beep(880, 300) }
        Thread.sleep(200)
    }

    fun powerStatus(): PowerStatus {
        val status = SystemPowerStatus()
        if (!kernel32.GetSystemPowerStatus(status)) return PowerStatus(onAc = null, percent = null, batterySaver = false)
        val battery = (status.batteryFlag.toInt() and 0xFF) != NO_SYSTEM_BATTERY
        return PowerStatus(
            onAc = when (status.acLineStatus.toInt() and 0xFF) {
                0 -> false
                1 -> true
                else -> null
            },
            percent = (status.batteryLifePercent.toInt() and 0xFF).takeIf { battery && it <= 100 },
            batterySaver = status.systemStatusFlag.toInt() == 1,
        )
    }

    /** Milliseconds since the last keyboard, mouse or touch input. */
    fun idleMs(): Long = (kernel32.GetTickCount() - Win32.lastInputTick()).toLong() and 0xFFFF_FFFFL

    /** How long after input Windows keeps other programs from taking the foreground; 200 s unless changed. */
    fun foregroundLockTimeoutMs(): Long {
        val value = IntByReference()
        return if (user32.SystemParametersInfoW(SPI_GETFOREGROUNDLOCKTIMEOUT, 0, value, 0)) value.value.toLong() else 200_000L
    }

    /** True when [path] is on a USB stick or a memory card. */
    fun removable(path: Path): Boolean {
        val root = path.toAbsolutePath().root ?: return false
        return kernel32.GetDriveTypeW(WString(root.toString())) == DRIVE_REMOVABLE
    }
}
