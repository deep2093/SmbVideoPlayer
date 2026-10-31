package com.deepak.videolibrary.player

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.deepak.videolibrary.ServiceLocator
import com.deepak.videolibrary.smb.HostFallback
import com.deepak.videolibrary.smb.SmbConnection
import com.deepak.videolibrary.smb.SmbProfile
import com.deepak.videolibrary.thumbs.SmbMediaDataSource
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit local diagnostic. Saves only the requested short segment in private app cache. */
class NativeReferenceDecodeTest {
    @Test fun decodeRequestedSegmentToPlanarYuv() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val name = args.getString("nativeReferenceName")
        assumeTrue(!name.isNullOrBlank())
        val startUs = (args.getString("referenceMs")?.toLong() ?: 265_000L) * 1000
        val wanted = (args.getString("referenceFrames")?.toInt() ?: 120).coerceIn(12, 120)
        val app = instrumentation.targetContext
        val services = ServiceLocator.get(app)
        val settings = services.settings.load()
        val profile = SmbProfile(settings.host, settings.share, settings.rootPath,
            settings.username, settings.password, settings.domain, settings.requireSigning,
            settings.requireEncryption, alternateHosts = HostFallback.parse(settings.alternateHosts))
        val item = services.db.openHelper.readableDatabase.query(
            "SELECT relativePath, size FROM videos WHERE name = ? LIMIT 2", arrayOf(name),
        ).use {
            check(it.count == 1) { "Reference must match exactly one library item" }
            it.moveToFirst()
            it.getString(0) to it.getLong(1)
        }
        val raw = File(app.cacheDir, "native-reference.yuv")
        val metadata = File(app.cacheDir, "native-reference.txt")
        raw.delete()
        metadata.delete()
        val times = mutableListOf<Long>()
        var width = 0
        var height = 0
        var succeeded = false
        try {
            SmbConnection(profile).use { connection ->
                connection.open()
                SmbMediaDataSource(connection, item.first, item.second).use { source ->
                    val extractor = MediaExtractor()
                    try {
                        extractor.setDataSource(source)
                        val track = (0 until extractor.trackCount).first {
                            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/")
                        }
                        val format = extractor.getTrackFormat(track)
                        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                        extractor.selectTrack(track)
                        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                        try {
                            codec.configure(format, null, null, 0)
                            codec.start()
                            val info = MediaCodec.BufferInfo()
                            var inputEnded = false
                            val deadline = SystemClock.elapsedRealtime() + 90_000
                            raw.outputStream().buffered(1024 * 1024).use { output ->
                                while (times.size < wanted && SystemClock.elapsedRealtime() < deadline) {
                                    if (!inputEnded) {
                                        val input = codec.dequeueInputBuffer(10_000)
                                        if (input >= 0) {
                                            val buffer = codec.getInputBuffer(input)!!
                                            val size = extractor.readSampleData(buffer, 0)
                                            if (size < 0) {
                                                codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                                inputEnded = true
                                            } else {
                                                codec.queueInputBuffer(input, 0, size, extractor.sampleTime, 0)
                                                extractor.advance()
                                            }
                                        }
                                    }
                                    val index = codec.dequeueOutputBuffer(info, 10_000)
                                    if (index >= 0) {
                                        try {
                                            if (info.size > 0 && info.presentationTimeUs >= startUs) {
                                                val image = checkNotNull(codec.getOutputImage(index)) { "Decoder did not expose YUV" }
                                                image.use {
                                                    val crop = it.cropRect
                                                    check(crop.width() % 2 == 0 && crop.height() % 2 == 0)
                                                    if (times.isEmpty()) { width = crop.width(); height = crop.height() }
                                                    check(width == crop.width() && height == crop.height())
                                                    it.planes.forEachIndexed { p, plane ->
                                                        val shift = if (p == 0) 0 else 1
                                                        val row = ByteArray(width shr shift)
                                                        val bytes = plane.buffer.duplicate()
                                                        val origin = bytes.position()
                                                        repeat(height shr shift) { y ->
                                                            val offset = origin + ((crop.top shr shift) + y) * plane.rowStride +
                                                                (crop.left shr shift) * plane.pixelStride
                                                            for (x in row.indices) row[x] = bytes.get(offset + x * plane.pixelStride)
                                                            output.write(row)
                                                        }
                                                    }
                                                }
                                                times.add(info.presentationTimeUs)
                                            }
                                        } finally { codec.releaseOutputBuffer(index, false) }
                                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                                    }
                                }
                            }
                        } finally { codec.release() }
                        assertEquals("Requested segment was not completely decoded", wanted, times.size)
                        val intervals = times.zipWithNext { a, b -> b - a }
                        check(intervals.all { it > 0 })
                        val report = "width=$width height=$height frames=${times.size} firstUs=${times.first()} lastUs=${times.last()} " +
                            "minIntervalUs=${intervals.min()} maxIntervalUs=${intervals.max()} " +
                            "colorStandard=${format.getInteger(MediaFormat.KEY_COLOR_STANDARD, -1)} " +
                            "colorRange=${format.getInteger(MediaFormat.KEY_COLOR_RANGE, -1)}"
                        metadata.writeText(report + "\n" + times.joinToString(","))
                        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n$report\n") })
                        succeeded = true
                    } finally { extractor.release() }
                }
            }
        } finally {
            if (!succeeded) { raw.delete(); metadata.delete() }
        }
    }
}
