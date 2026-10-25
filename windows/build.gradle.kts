import java.util.zip.ZipFile
import java.security.MessageDigest

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

/**
 * Preserve notices with the exact runtime artifacts. This collects evidence;
 * legal/SOURCE-STATUS.md tracks the remaining native-source release gate.
 */
val generateWindowsLegal by tasks.registering {
    group = "distribution"
    description = "Bundle Windows licences, runtime inventory and upstream notices"
    inputs.dir(layout.projectDirectory.dir("legal"))
    inputs.file(layout.projectDirectory.file("LICENSE"))
    inputs.files(configurations.named("runtimeClasspath"))
    inputs.dir(file(System.getProperty("java.home")).resolve("legal"))
    inputs.property("sourceRevision", providers.environmentVariable("GITHUB_SHA").orElse("local-unversioned"))
    val destination = layout.buildDirectory.dir("native-resources/common/legal")
    outputs.dir(destination)
    doLast {
        val out = destination.get().asFile
        val expected = layout.buildDirectory.dir("native-resources/common/legal").get().asFile.canonicalFile
        check(out.canonicalFile == expected && expected.toPath().startsWith(layout.buildDirectory.get().asFile.canonicalFile.toPath()))
        project.delete(out)
        out.mkdirs()
        project.copy { from(layout.projectDirectory.dir("legal")); into(out) }
        file("LICENSE").copyTo(out.resolve("SkyWing-Windows-GPL-3.0.txt"), overwrite = true)
        val jdkHome = file(System.getProperty("java.home"))
        check(jdkHome.resolve("legal").isDirectory) { "JDK legal directory is missing" }
        project.copy { from(jdkHome.resolve("legal")); into(out.resolve("java-runtime")) }
        jdkHome.resolve("release").copyTo(out.resolve("java-runtime/BUILD-RELEASE.txt"), overwrite = true)
        val revision = providers.environmentVariable("GITHUB_SHA").orElse("local-unversioned").get()
        out.resolve("SKYWING-SOURCE.txt").writeText(
            "SkyWing Windows is GPL-3.0-or-later. Third-party components retain their own licences.\n" +
            "Build revision: $revision\n" +
            "Source: https://github.com/deep2093/SmbVideoPlayer/tree/$revision/windows\n" +
            "The CI source ZIP contains SkyWing's Windows sources and build scripts.\n" +
            "See SOURCE-STATUS.md for dependency-source gaps; this is not a complete corresponding-source bundle.\n"
        )
        val inventory = StringBuilder("Artifact\tFile\tSHA-256\tExtracted notice files\n")
        val artifacts = configurations.getByName("runtimeClasspath").resolvedConfiguration.resolvedArtifacts
        for (artifact in artifacts.sortedBy { it.moduleVersion.id.toString() + ":" + it.file.name }) {
            val jar = artifact.file
            check(jar.extension == "jar") { "Review non-JAR runtime artifact: ${jar.name}" }
            val id = artifact.moduleVersion.id
            val artifactDir = out.resolve("jvm/${id.group}/${id.name}/${id.version}/${jar.name}")
            var noticeCount = 0
            ZipFile(jar).use { zip ->
                for (entry in zip.entries().asSequence().filter { !it.isDirectory }.sortedBy { it.name }) {
                    val leaf = entry.name.substringAfterLast('/').lowercase()
                    val legalFile = listOf("license", "licence", "notice", "copying", "copyright", "authors").any {
                        leaf == it || leaf.startsWith("$it.") || leaf.startsWith("$it-") || leaf.startsWith("${it}_")
                    }
                    if (!legalFile) continue
                    val target = artifactDir.resolve(entry.name).canonicalFile
                    check(target.toPath().startsWith(artifactDir.canonicalFile.toPath())) { "Invalid archive path" }
                    target.parentFile.mkdirs()
                    zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
                    noticeCount++
                }
            }
            val hash = MessageDigest.getInstance("SHA-256")
            jar.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
            }
            val digest = hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            inventory.append("$id\t${jar.name}\t$digest\t$noticeCount\n")
        }
        out.resolve("JVM-RUNTIME-INVENTORY.tsv").writeText(inventory.toString())
        check(out.resolve("upstream/mpv-Copyright.txt").isFile)
        check(out.resolve("licenses/GPL-3.0.txt").length() > 30000)
        logger.lifecycle("Bundled Windows legal files for ${artifacts.size} runtime artifacts. Native-source audit remains tracked in SOURCE-STATUS.md.")
    }
}

tasks.matching { it.name in setOf("prepareAppResources", "createDistributable", "packageExe", "packageMsi") }.configureEach {
    dependsOn(generateWindowsLegal)
}
