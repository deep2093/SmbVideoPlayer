package com.deepak.videolibrary.player

import android.os.Bundle
import android.os.Build
import java.io.File
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in native engine feasibility test. Does not change the playback backend. */
@RunWith(AndroidJUnit4::class)
class NativeSvpProbeTest {
    @Test fun nativeEngineGenerates60FpsInsideApp() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("nativeSvp") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val frames = args.getString("probeFrames")?.toInt() ?: 12
        val fps = args.getString("probeFps")?.toInt() ?: 24
        val threads = args.getString("probeThreads")?.toInt() ?: 4
        val guard = args.getString("probeGuardFrames")?.toInt() ?: 0
        require(frames in 2..7200 && fps in 1..60 && threads in 1..32 && guard in 0..16)
        val half = args.getString("probeHalf") == "true"
        val profileName = args.getString("probeProfile") ?: "baseline"
        val profile = when (profileName) {
            "baseline" -> 0
            "pel1" -> 1
            "captured" -> 2
            else -> error("Unknown probeProfile: $profileName")
        }
        assumeTrue(Build.SUPPORTED_ABIS.first() == "arm64-v8a")
        val info = instrumentation.context.applicationInfo
        val directory = if (File(info.nativeLibraryDir, "libsvpflow1_vs.so").exists())
            info.nativeLibraryDir else info.sourceDir + "!/lib/arm64-v8a"
        for (gpu in args.getString("probeGpu")?.toBooleanStrictOrNull()?.let { listOf(it) } ?: listOf(false, true)) {
            val result = NativeSvpProbe.run(directory, 1920, 1080, gpu, frames, fps, half, threads, profile, guard)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nNative SVP gpu=$gpu: $result\n") })
            val expected = (frames - 1) * 60 / fps + 1
            assertTrue(result, result.contains("fps=60/1") && result.contains("frames=$expected "))
            val distinct = Regex("distinct=(\\d+)").find(result)!!.groupValues[1].toInt()
            if (fps < 60) assertTrue("No intermediate frame differed from both source neighbours: $result", distinct > 0)
        }
    }
}

internal object NativeSvpProbe {
    init { System.loadLibrary("skywing_svp_probe") }
    external fun run(directory: String, width: Int, height: Int, gpu: Boolean, frames: Int, fps: Int, half: Boolean, threads: Int, profile: Int, guard: Int): String
}
