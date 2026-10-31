package com.deepak.videolibrary.player

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Known-motion synthesis only: this does not validate motion estimation or SVP parity. */
class VulkanMotionProbeTest {
    @Test fun compareMovingYuvAgainstCpuAndRecreateDevice() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("vulkanMotion") == "true")
        val duration = args.getString("vulkanDurationMs")?.toInt()?.coerceIn(2000, 60000) ?: 30000
        for ((width, height) in listOf(1920 to 1080, 1280 to 720)) {
            val result = VulkanMotionProbe.run(width, height, duration)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "\nVulkan known-motion: $result\n")
            })
            assertTrue(result, result.contains("mismatches=0"))
            val p95 = Regex("p95_ms=([0-9.]+)").find(result)!!.groupValues[1].toDouble()
            assertTrue("Missed 60 fps processing budget: $result", p95 < 1000.0 / 60)
            val frames = Regex("frames=([0-9]+)").find(result)!!.groupValues[1].toInt()
            assertTrue("Sustained processing fell below 54 fps: $result", frames >= duration * 54 / 1000)
        }
    }
}

internal object VulkanMotionProbe {
    init { System.loadLibrary("skywing_vulkan_probe") }
    external fun run(width: Int, height: Int, durationMs: Int): String
}
