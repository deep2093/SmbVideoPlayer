package com.deepak.videolibrary.player

import android.content.Context
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/** Bridges decoder EOS to a look-ahead effect; Media3 1.6.1 does not forward per-stream EOS to effects. */
internal class NativeSvpController {
    private var listener: ((Long) -> Unit)? = null
    private var endUs = Long.MAX_VALUE
    @Synchronized fun bind(callback: (Long) -> Unit) { listener = callback; if (endUs != Long.MAX_VALUE) callback(endUs) }
    @Synchronized fun unbind(callback: (Long) -> Unit) { if (listener === callback) listener = null }
    @Synchronized fun reset() { endUs = Long.MAX_VALUE }
    @Synchronized fun end(timeUs: Long) { endUs = timeUs; listener?.invoke(timeUs) }
}

@androidx.annotation.OptIn(UnstableApi::class)
internal class NativeSvpRenderersFactory(context: Context, private val controller: NativeSvpController) : DefaultRenderersFactory(context) {
    override fun buildVideoRenderers(context: Context, extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector, enableDecoderFallback: Boolean,
        eventHandler: Handler, eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long, out: ArrayList<Renderer>) {
        out.add(object : MediaCodecVideoRenderer(Builder(context)
            .setCodecAdapterFactory(codecAdapterFactory)
            .setMediaCodecSelector(mediaCodecSelector)
            .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
            .setEnableDecoderFallback(enableDecoderFallback)
            .setEventHandler(eventHandler).setEventListener(eventListener)
            .setMaxDroppedFramesToNotify(50)) {
            private var lastCompositionUs = C.TIME_UNSET
            override fun onProcessedOutputBuffer(presentationTimeUs: Long) {
                lastCompositionUs = presentationTimeUs + bufferTimestampAdjustmentUs
                super.onProcessedOutputBuffer(presentationTimeUs)
            }
            override fun renderToEndOfStream() {
                if (lastCompositionUs != C.TIME_UNSET) controller.end(lastCompositionUs)
                super.renderToEndOfStream()
            }
            override fun onProcessedStreamChange() {
                if (lastCompositionUs != C.TIME_UNSET) controller.end(lastCompositionUs)
                super.onProcessedStreamChange()
                controller.reset()
                lastCompositionUs = C.TIME_UNSET
            }
            override fun onPositionReset(positionUs: Long, joining: Boolean) {
                controller.reset(); lastCompositionUs = C.TIME_UNSET
                super.onPositionReset(positionUs, joining)
            }
        })
    }
}
