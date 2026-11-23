package com.skywing.desktop.playback

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference

/**
 * Where the motion engine runs (plan 5.8 rule 4): an OpenCL [device], and the [filter] that points the patched
 * plugins at it. No device means the processor.
 */
internal data class MotionDevice(val device: OpenClDevice?, val filter: Map<String, String>, val matchedBy: String) {
    /** No OpenCL device at all: open-svpflow smooths on the processor, so SkyWing allows only up to 1080p at 2×. */
    val processor: Boolean get() = device == null
}

/** One OpenCL GPU device. [luid] is the Windows adapter LUID when the driver reports it (`cl_khr_device_uuid`). */
internal data class OpenClDevice(val vendor: String, val name: String, val driver: String, val luid: Long?) {
    companion object {
        private const val VENDOR = "SKYWING_OPENCL_VENDOR"
        private const val LUID = "SKYWING_OPENCL_LUID"
        /** A vendor filter no device's vendor contains. */
        private const val NO_DEVICE = "skywing-no-opencl-device"
        private interface OpenCl : Library {
            fun clGetPlatformIDs(count: Int, platforms: Pointer?, returned: IntByReference): Int
            fun clGetDeviceIDs(platform: Pointer, type: Long, count: Int, devices: Pointer?, returned: IntByReference): Int
            fun clGetDeviceInfo(device: Pointer, key: Int, size: Long, value: Pointer, returned: Pointer?): Int
        }

        private const val DEVICE_TYPE_GPU = 4L
        private const val DEVICE_NAME = 0x102B
        private const val DEVICE_VENDOR = 0x102C
        private const val DRIVER_VERSION = 0x102D
        // cl_khr_device_uuid (Khronos cl_ext.h)
        private const val DEVICE_LUID_VALID = 0x106C
        private const val DEVICE_LUID = 0x106D

        /**
         * The device the motion engine should use for [adapter]: by LUID when another GPU shares its maker and the
         * driver reports LUIDs, otherwise the first device from its maker. Null when that GPU has no OpenCL device;
         * never a device on another GPU of the same maker. [choose] then falls back to another GPU, and says so.
         */
        fun pick(devices: List<OpenClDevice>, adapter: GpuAdapters.Adapter?, byLuid: Boolean): OpenClDevice? {
            if (adapter == null) return devices.firstOrNull()
            val maker = adapter.gpuVendor.openCl ?: return devices.firstOrNull()
            val sameMaker = devices.filter { it.vendor.contains(maker, ignoreCase = true) }
            if (byLuid && sameMaker.any { it.luid != null }) return sameMaker.firstOrNull { it.luid == adapter.luid }
            return sameMaker.firstOrNull()
        }

        /**
         * The process environment the patched open-svpflow reads each time it opens a device
         * (`windows/tools/open-svpflow-windows.patch`). Empty means no filter.
         */
        fun filter(adapter: GpuAdapters.Adapter?, byLuid: Boolean): Map<String, String> = mapOf(
            VENDOR to (adapter?.gpuVendor?.openCl ?: ""),
            LUID to if (byLuid && adapter != null) "%016x".format(adapter.luid) else "",
        )

        /**
         * The motion engine's device for [adapter] (plan 5.8 rule 4): OpenCL on that GPU ([pick]), else OpenCL on any
         * other GPU, named exactly by its vendor and LUID, else the processor. The engine gets its frames from system
         * memory (hwdec auto-copy), so another GPU only adds an upload; the report names it.
         */
        fun choose(devices: List<OpenClDevice>, adapter: GpuAdapters.Adapter?, byLuid: Boolean): MotionDevice {
            pick(devices, adapter, byLuid)?.let { device ->
                val how = when {
                    adapter == null -> "any device"
                    byLuid && device.luid == adapter.luid -> "LUID"
                    byLuid -> "maker (this driver reports no LUID)"
                    else -> "maker"
                }
                return MotionDevice(device, filter(adapter, byLuid), how)
            }
            devices.firstOrNull()?.let { other ->
                val pinned = mapOf(VENDOR to other.vendor, LUID to (other.luid?.let { "%016x".format(it) } ?: ""))
                return MotionDevice(other, pinned, "another GPU (${adapter?.name ?: "the chosen GPU"} has no OpenCL device)")
            }
            return MotionDevice(null, mapOf(VENDOR to NO_DEVICE, LUID to ""), "none (no OpenCL device: the processor)")
        }

        /** The OpenCL GPU devices of every installed driver; empty without OpenCL. */
        fun list(): List<OpenClDevice> = runCatching {
            val cl = Native.load("OpenCL", OpenCl::class.java)
            val count = IntByReference()
            if (cl.clGetPlatformIDs(0, null, count) != 0 || count.value !in 1..32) return@runCatching emptyList()
            val platforms = Memory(count.value.toLong() * Native.POINTER_SIZE)
            if (cl.clGetPlatformIDs(count.value, platforms, count) != 0) return@runCatching emptyList()
            (0 until count.value).flatMap { p ->
                val platform = platforms.getPointer(p.toLong() * Native.POINTER_SIZE)
                val found = IntByReference()
                if (cl.clGetDeviceIDs(platform, DEVICE_TYPE_GPU, 0, null, found) != 0 || found.value !in 1..16) return@flatMap emptyList()
                val ids = Memory(found.value.toLong() * Native.POINTER_SIZE)
                if (cl.clGetDeviceIDs(platform, DEVICE_TYPE_GPU, found.value, ids, found) != 0) return@flatMap emptyList()
                (0 until found.value).map { d -> describe(cl, ids.getPointer(d.toLong() * Native.POINTER_SIZE)) }
            }
        }.getOrDefault(emptyList())

        private fun describe(cl: OpenCl, device: Pointer): OpenClDevice = Memory(512).use { bytes ->
            fun text(key: Int): String {
                bytes.clear()
                return if (cl.clGetDeviceInfo(device, key, bytes.size(), bytes, null) == 0) bytes.getString(0).trim() else ""
            }
            // A driver without cl_khr_device_uuid rejects these keys; a valid flag of 0 means "no LUID" too.
            bytes.clear()
            val valid = cl.clGetDeviceInfo(device, DEVICE_LUID_VALID, 4, bytes, null) == 0 && bytes.getInt(0) != 0
            bytes.clear()
            val luid = if (valid && cl.clGetDeviceInfo(device, DEVICE_LUID, 8, bytes, null) == 0) bytes.getLong(0).takeIf { it != 0L } else null
            OpenClDevice(text(DEVICE_VENDOR), text(DEVICE_NAME), text(DRIVER_VERSION), luid)
        }
    }
}
