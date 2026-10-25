import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// Preview MSIs use a monotonically increasing Windows-compatible version.
// Never generate a new upgrade UUID for subsequent installers.
val ciBuild = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
val previewBuild = (ciBuild ?: 1).also { require(it in 1..65535) { "Windows MSI build version must be 1..65535" } }
val previewVersion = "0.1.$previewBuild"

plugins {
    kotlin("jvm") version "2.1.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
    id("org.jetbrains.compose") version "1.8.2"
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("net.java.dev.jna:jna:5.14.0")
    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    systemProperty("skywing.test.runtimeClasspath", sourceSets.main.get().runtimeClasspath.asPath)
    if (System.getenv("SKYWING_TEST_MPV") == "true") {
        val nativeDir = requireNotNull(System.getenv("SKYWING_TEST_MPV_DIR")) {
            "Set SKYWING_TEST_MPV_DIR to the directory containing the pinned libmpv-2.dll"
        }
        require(file("$nativeDir/libmpv-2.dll").isFile) { "Pinned libmpv-2.dll is missing" }
        systemProperty("compose.application.resources.dir", file(nativeDir).absolutePath)
    }
}

compose.desktop {
    application {
        mainClass = "com.skywing.desktop.MainKt"
        nativeDistributions {
            appResourcesRootDir.set(layout.buildDirectory.dir("native-resources"))
            // Build these packages on Windows x64. ARM/32-bit variants are intentionally excluded.
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "SkyWing"
            packageVersion = previewVersion
            description = "SkyWing Windows x64 media player (development preview)"
            vendor = "SkyWing"
            windows {
                upgradeUuid = "f40c59ef-e520-4e10-b7a2-b86e7458f923"
                menu = true
                menuGroup = "SkyWing"
                shortcut = true
            }
        }
    }
}
