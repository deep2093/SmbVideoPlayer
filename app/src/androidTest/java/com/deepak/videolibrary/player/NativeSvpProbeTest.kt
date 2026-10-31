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
        assumeTrue(Build.SUPPORTED_ABIS.first() == "arm64-v8a")
        val info = instrumentation.context.applicationInfo
        val directory = if (File(info.nativeLibraryDir, "libsvpflow1_vs.so").exists())
            info.nativeLibraryDir else info.sourceDir + "!/lib/arm64-v8a"
        for (gpu in listOf(false, true)) {
            val result = NativeSvpProbe.run(directory, 1920, 1080, gpu)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nNative SVP gpu=$gpu: $result\n") })
            assertTrue(result, result.contains("fps=60/1") && result.contains("frames=28"))
        }
    }
}

internal object NativeSvpProbe {
    init { System.loadLibrary("skywing_svp_probe") }
    external fun run(directory: String, width: Int, height: Int, gpu: Boolean): String
}
