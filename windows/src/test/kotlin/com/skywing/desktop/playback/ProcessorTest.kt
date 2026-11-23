package com.skywing.desktop.playback

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProcessorTest {
    /** One SYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX entry for a core, as Windows lays it out on x64. */
    private fun core(efficiencyClass: Int, vararg masks: Long): ByteArray {
        val size = 32 + 16 * masks.size
        val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0, 0) // RelationProcessorCore
        b.putInt(4, size)
        b.put(8, if (masks.sumOf { java.lang.Long.bitCount(it) } > 1) 1 else 0) // LTP_PC_SMT
        b.put(9, efficiencyClass.toByte())
        b.putShort(30, masks.size.toShort())
        masks.forEachIndexed { i, mask -> b.putLong(32 + 16 * i, mask) }
        return b.array()
    }

    private fun entries(vararg parts: ByteArray) = parts.fold(ByteArray(0)) { all, part -> all + part }

    @Test
    fun eachCoreIsReadWithItsEfficiencyClassAndThreads() {
        val cores = Processor.cores(entries(core(1, 0b11), core(1, 0b1100), core(0, 0b10000), core(0, 0b100000)))
        assertEquals(listOf(Processor.Core(1, 2), Processor.Core(1, 2), Processor.Core(0, 1), Processor.Core(0, 1)), cores)
        // Another relationship in the buffer (a cache, say) is skipped by its size.
        val cache = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 2).putInt(4, 40).array()
        assertEquals(listOf(Processor.Core(1, 2)), Processor.cores(entries(cache, core(1, 0b11))))
        assertEquals(emptyList(), Processor.cores(ByteArray(0)))
    }

    @Test
    fun coreTypesAreNamedByWindowsEfficiencyClassNotByMaker() {
        val hybrid = List(6) { Processor.Core(1, 2) } + List(8) { Processor.Core(0, 1) }
        assertEquals("14 (20 threads) by Windows' efficiency class, higher is faster: class 1 × 6, class 0 × 8", Processor.coreText(hybrid))
        assertEquals("8 of one class (16 threads)", Processor.coreText(List(8) { Processor.Core(0, 2) }))
        assertEquals("not reported", Processor.coreText(emptyList()))
    }

    @Test
    fun theMotionEngineNeedsAvx2BecauseOpenSvpflowIsBuiltForX8664V3() {
        val modern = Processor.Info("A processor", mapOf("SSE4.2" to true, "AVX" to true, "AVX2" to true, "AVX-512" to false), emptyList())
        assertNull(modern.motionBlocked)
        val old = modern.copy(features = modern.features + ("AVX2" to false))
        assertEquals("Smoothing needs a processor with AVX2, and this one doesn’t have it", old.motionBlocked)
        assertTrue(old.line().endsWith("; motion engine (built for x86-64-v3) needs AVX2: no"), old.line())
        assertTrue(modern.line().startsWith("A processor; cores: not reported; instruction sets: SSE4.2 yes, AVX yes, AVX2 yes, AVX-512 no"), modern.line())
        assertFalse(Processor.Info(null, emptyMap(), emptyList()).line().contains("null"))
    }

    @Test
    fun thisPcsProcessorIsRead() {
        val info = Processor.current
        println("Processor: " + info.line())
        assertTrue(info.name != null, "the name from the registry")
        assertTrue(info.cores.isNotEmpty() && info.cores.all { it.threads >= 1 }, info.cores.toString())
        assertTrue(info.features.values.all { it != null }, info.features.toString())
    }

    @Test
    fun aProcessorWithoutAvx2ReportsWhySmoothingIsOff() {
        val caps = SmoothingCapabilities(sourceFps = 24.0, gpuOutput = true, hardwareDecode = true, interlaced = false,
            processorBlocked = "Smoothing needs a processor with AVX2, and this one doesn’t have it")
        assertEquals("Smoothing needs a processor with AVX2, and this one doesn’t have it", caps.unavailable(SmoothingMode.MOTION))
        assertNull(caps.unavailable(SmoothingMode.OFF))
        assertNull(caps.copy(processorBlocked = null).unavailable(SmoothingMode.MOTION))
    }
}
