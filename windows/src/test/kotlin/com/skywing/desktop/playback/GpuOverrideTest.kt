package com.skywing.desktop.playback

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GpuOverrideTest {
    @Test
    fun unsetKeepsTheMonitorsGpu() = assertNull(GpuOverride.vendor(null))

    @Test
    fun namesNvidiaOrIntelInAnyCase() {
        assertEquals("NVIDIA", GpuOverride.vendor("NVIDIA"))
        assertEquals("NVIDIA", GpuOverride.vendor(" nvidia "))
        assertEquals("Intel", GpuOverride.vendor("Intel"))
        assertEquals("Intel", GpuOverride.vendor("INTEL"))
    }

    @Test
    fun anyOtherValueKeepsTheMonitorsGpu() {
        listOf("", "AMD", "1", "NVIDIA GeForce RTX 5070 Ti").forEach { assertNull(GpuOverride.vendor(it), it) }
    }
}
