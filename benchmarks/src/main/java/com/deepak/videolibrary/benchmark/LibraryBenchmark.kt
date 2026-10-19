package com.deepak.videolibrary.benchmark

import androidx.benchmark.macro.*
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.uiautomator.By
import org.junit.Rule
import org.junit.Test

private const val APP = "com.deepak.videolibrary.preview"

class StartupBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()
    @Test fun coldStartup() = benchmark.measureRepeated(
        packageName = APP,
        metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.COLD,
        iterations = 5,
        setupBlock = { device.executeShellCommand("pm grant $APP android.permission.POST_NOTIFICATIONS"); pressHome() },
    ) { startActivityAndWait() }
}

class GenerateBaselineProfile {
    @get:Rule val baseline = BaselineProfileRule()
    @Test fun libraryJourneys() = baseline.collect(packageName = APP, includeInStartupProfile = true) {
        device.executeShellCommand("pm grant $APP android.permission.POST_NOTIFICATIONS")
        pressHome(); startActivityAndWait()
        for (label in listOf("Virtual folders", "Custom folders", "Original folders", "Videos", "Scan", "Settings")) {
            (device.findObject(By.desc(label)) ?: device.findObject(By.text(label)))?.click()
            device.waitForIdle()
        }
    }
}
