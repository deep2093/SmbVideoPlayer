package com.skywing.desktop

import com.skywing.desktop.automation.javaToolOptions

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * SkyWingTestApp passes its JVM options to the app through JAVA_TOOL_OPTIONS. The boot class path option points into
 * this checkout's build folder, so a checkout in a folder with a space in its name must still reach the app's JVM
 * unchanged. Runs a short-lived JVM (`-version`); no window opens.
 */
class JavaToolOptionsTest {
    @Test
    fun optionsWithSpacesReachTheJvmUnchanged() {
        val dir = Files.createTempDirectory("skywing tool options ")
        try {
            val classes = dir.resolve("test classes").createDirectory()
            val property = "C:\\Program Files\\SkyWing"
            val options = javaToolOptions(listOf("-Xbootclasspath/a:$classes", "-Dskywing.probe=$property"))

            val java = ProcessHandle.current().info().command().orElseThrow()
            val process = ProcessBuilder(java, "-Xlog:class+path=info", "-XshowSettings:properties", "-version")
                .redirectErrorStream(true)
                .apply { environment()["JAVA_TOOL_OPTIONS"] = options }
                .start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor(60, TimeUnit.SECONDS)) { "java -version did not finish" }

            assertEquals(0, process.exitValue(), output)
            assertContains(output, "bootstrap loader class path=", message = output)
            assertContains(output.lineSequence().first { "bootstrap loader class path=" in it }, ";$classes", message = output)
            assertContains(output, "skywing.probe = $property", message = output)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
