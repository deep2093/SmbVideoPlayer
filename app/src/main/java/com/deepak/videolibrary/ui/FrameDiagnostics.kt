package com.deepak.videolibrary.ui

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import java.io.Closeable

/** Collect only while the user has enabled diagnostics; never logs filenames or paths. */
class FrameDiagnostics(private val activity: Activity) : Closeable {
    private val prefs = activity.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
    private var thread: HandlerThread? = null
    private val samples = ArrayList<Double>(240)
    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        if (AppDiagnostics.enabled(activity)) {
            synchronized(samples) {
                val ms = metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000.0
                if (ms > 0) samples.add(ms)
                if (samples.size >= 240) {
                    val hz = activity.window.decorView.display
                        ?.refreshRate
                        ?.takeIf { it > 0 } ?: 60f
                    AppDiagnostics.frameSample(samples, 1000.0 / hz)
                    samples.clear()
                }
            }
        }
    }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "enabled") activity.runOnUiThread { update() }
    }
    init {
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        update()
    }
    private fun update() {
        if (prefs.getBoolean("enabled", false) && thread == null) {
            val worker = HandlerThread("library-frame-metrics").apply { start() }
            thread = worker
            activity.window.addOnFrameMetricsAvailableListener(listener, Handler(worker.looper))
        } else if (!prefs.getBoolean("enabled", false)) {
            stop()
        }
    }
    private fun stop() {
        if (thread != null) activity.window.removeOnFrameMetricsAvailableListener(listener)
        thread?.quitSafely()
        thread = null
    }
    override fun close() {
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        stop()
    }
}
