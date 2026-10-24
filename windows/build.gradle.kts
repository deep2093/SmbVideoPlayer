import org.jetbrains.compose.desktop.application.dsl.TargetFormat

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
}

compose.desktop {
    application {
        mainClass = "com.skywing.desktop.MainKt"
        nativeDistributions {
            // Build these packages on Windows x64. ARM/32-bit variants are intentionally excluded.
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "SkyWing"
            packageVersion = "0.1.0"
            description = "SkyWing Windows x64 media player (development preview)"
            vendor = "SkyWing"
        }
    }
}
