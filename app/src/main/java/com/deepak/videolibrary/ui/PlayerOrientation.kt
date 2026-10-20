package com.deepak.videolibrary.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration

/**
 * Turns the screen to match the video: landscape clips open in landscape, portrait clips stay portrait.
 * The rotate button takes over for the rest of that video. The app's previous setting is restored on exit.
 */
internal class PlayerOrientation(private val activity: Activity?) {
    private val original = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var manual = false
    private var appliedFor: Boolean? = null

    /** A new video starts: automatic rotation applies again. */
    fun newVideo() {
        manual = false
        appliedFor = null
    }

    /** Called when the real picture size is known. Only acts once per video unless the shape changes. */
    fun onVideoShape(width: Int, height: Int) {
        if (manual || width <= 0 || height <= 0) return
        val landscape = width >= height
        if (appliedFor == landscape) return
        appliedFor = landscape
        activity?.requestedOrientation =
            if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }

    /** Manual button: flips between landscape and portrait. */
    fun rotate() {
        val a = activity ?: return
        manual = true
        val landscapeNow = a.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        a.requestedOrientation =
            if (landscapeNow) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    fun restore() {
        activity?.requestedOrientation = original
    }
}
