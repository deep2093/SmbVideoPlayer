package com.skywing.desktop.playback

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Plan 5.9: the processor's name, its core types and the instruction sets the bundled DLLs need. Only features count,
 * never the maker. open-svpflow is built with `-C target-cpu=x86-64-v3` (its `.cargo/config.toml`, which
 * `build_motion.py` keeps), so its code may use AVX2 anywhere; on a processor without AVX2 the first such instruction
 * would end SkyWing. Smoothing is therefore off there, before any motion DLL is loaded.
 */
internal object Processor {
    data class Core(val efficiencyClass: Int, val threads: Int)

    data class Info(val name: String?, val features: Map<String, Boolean?>, val cores: List<Core>) {
        /** Why smoothing can't run on this processor, or null when it can. */
        val motionBlocked: String? get() =
            if (features["AVX2"] == false) "Smoothing needs a processor with AVX2, and this one doesn’t have it" else null

        /** The report's "Processor:" line. */
        fun line(): String = (name ?: "name not reported") + "; cores: " + coreText(cores) +
            "; instruction sets: " + features.entries.joinToString { (set, present) -> "$set " + when (present) { true -> "yes"; false -> "no"; null -> "unknown" } } +
            "; motion engine (built for x86-64-v3) needs AVX2: " + when (features["AVX2"]) { true -> "yes"; false -> "no"; null -> "unknown" }
    }

    /** Read once: the processor doesn't change while SkyWing runs. */
    val current: Info by lazy { read() }

    // IsProcessorFeaturePresent (winnt.h). x86-64-v3 also takes FMA, BMI1, BMI2, LZCNT, MOVBE and F16C, which Windows has
    // no flag for; they are not checked.
    private val FEATURES = linkedMapOf("SSE4.2" to 38, "AVX" to 39, "AVX2" to 40, "AVX-512" to 41)
    private const val RELATION_PROCESSOR_CORE = 0
    private val HKEY_LOCAL_MACHINE = Pointer(0x80000002L.toInt().toLong())
    private const val RRF_RT_REG_SZ = 0x2

    private interface Kernel32 : Library {
        fun IsProcessorFeaturePresent(feature: Int): Boolean
        fun GetLogicalProcessorInformationEx(relationship: Int, buffer: Pointer?, length: IntByReference): Boolean
    }

    private interface Advapi32 : Library {
        fun RegGetValueW(key: Pointer, subKey: WString, value: WString, flags: Int, type: IntByReference?, data: Pointer?, size: IntByReference): Int
    }

    private fun read(): Info {
        val kernel = runCatching { Native.load("kernel32", Kernel32::class.java) }.getOrNull()
        val features = FEATURES.mapValues { (_, flag) -> kernel?.let { runCatching { it.IsProcessorFeaturePresent(flag) }.getOrNull() } }
        return Info(name(), features, kernel?.let(::coreBuffer)?.let(::cores).orEmpty())
    }

    private fun name(): String? = runCatching {
        val advapi = Native.load("advapi32", Advapi32::class.java)
        val key = WString("HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0")
        val value = WString("ProcessorNameString")
        val size = IntByReference(0)
        if (advapi.RegGetValueW(HKEY_LOCAL_MACHINE, key, value, RRF_RT_REG_SZ, null, null, size) != 0 || size.value <= 0) return null
        Memory(size.value.toLong()).use { data ->
            if (advapi.RegGetValueW(HKEY_LOCAL_MACHINE, key, value, RRF_RT_REG_SZ, null, data, size) != 0) return null
            data.getWideString(0).trim().ifEmpty { null }
        }
    }.getOrNull()

    private fun coreBuffer(kernel: Kernel32): ByteArray? = runCatching {
        val length = IntByReference(0)
        kernel.GetLogicalProcessorInformationEx(RELATION_PROCESSOR_CORE, null, length)
        if (length.value <= 0) return null
        Memory(length.value.toLong()).use { buffer ->
            if (!kernel.GetLogicalProcessorInformationEx(RELATION_PROCESSOR_CORE, buffer, length)) return null
            buffer.getByteArray(0, length.value)
        }
    }.getOrNull()

    /**
     * The cores in a SYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX buffer (x64 layout): the relationship at 0 and the entry's
     * size at 4, then PROCESSOR_RELATIONSHIP: EfficiencyClass at 9, GroupCount at 30 and 16-byte GROUP_AFFINITY entries
     * from 32, whose 8-byte masks hold the core's threads.
     */
    fun cores(buffer: ByteArray): List<Core> {
        val b = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        val cores = mutableListOf<Core>()
        var at = 0
        while (at + 8 <= buffer.size) {
            val size = b.getInt(at + 4)
            if (size < 8 || at + size > buffer.size) break
            if (b.getInt(at) == RELATION_PROCESSOR_CORE && size >= 32) {
                val groups = b.getShort(at + 30).toInt().coerceIn(0, (size - 32) / 16)
                val threads = (0 until groups).sumOf { java.lang.Long.bitCount(b.getLong(at + 32 + 16 * it)) }
                cores += Core(buffer[at + 9].toInt() and 0xff, threads)
            }
            at += size
        }
        return cores
    }

    /** Windows' own core classes: a higher efficiency class is a faster core (Intel P-cores, say); one class is no hybrid. */
    fun coreText(cores: List<Core>): String {
        if (cores.isEmpty()) return "not reported"
        val threads = cores.sumOf { it.threads }
        val classes = cores.groupingBy { it.efficiencyClass }.eachCount().toSortedMap(reverseOrder())
        if (classes.size == 1) return "${cores.size} of one class ($threads threads)"
        return "${cores.size} ($threads threads) by Windows' efficiency class, higher is faster: " +
            classes.entries.joinToString { (efficiency, count) -> "class $efficiency × $count" }
    }
}
