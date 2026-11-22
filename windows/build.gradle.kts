import java.io.BufferedInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
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
    implementation("me.friwi:jcefmaven:146.0.10")
    runtimeOnly("me.friwi:jcef-natives-windows-amd64:jcef-d3de827+cef-146.0.10+g8219561+chromium-146.0.7680.179")
    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
    // Compose semantics tests for accessible names, roles and states.
    testImplementation(compose.desktop.uiTestJUnit4)
}

tasks.test {
    systemProperty("skywing.test.runtimeClasspath", sourceSets.main.get().runtimeClasspath.asPath)
    // Tests never touch the user's SkyWing data. App code running inside the test JVM (for example the window content
    // in FullscreenWindowTest) gets file-backed preferences and its own LOCALAPPDATA and APPDATA, so its startup
    // library sync cannot scan the user's folders and shares or write their status. Tests that prove the real data is
    // untouched read the real folder from skywing.test.realLocalAppData.
    val userData = layout.buildDirectory.dir("test-user-data").get().asFile
    doFirst { userData.deleteRecursively(); userData.mkdirs() }
    systemProperty("java.util.prefs.PreferencesFactory", "com.skywing.testsupport.IsolatedPreferencesFactory")
    systemProperty("skywing.test.preferencesFile", userData.resolve("preferences.properties").absolutePath)
    systemProperty("skywing.test.realLocalAppData", System.getenv("LOCALAPPDATA").orEmpty())
    environment("LOCALAPPDATA", userData.resolve("LocalAppData").absolutePath)
    environment("APPDATA", userData.resolve("AppData").absolutePath)
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
            // Compose Desktop uses Java Access Bridge for Windows accessibility.
            modules("jdk.accessibility")
            appResourcesRootDir.set(layout.buildDirectory.dir("native-resources"))
            // Build these packages on Windows x64. ARM/32-bit variants are intentionally excluded.
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "SkyWing"
            packageVersion = previewVersion
            description = "SkyWing Windows x64 media player (development preview)"
            vendor = "SkyWing"
            // Offer SkyWing in Explorer's Open with menu without taking over defaults.
            val videoAssociations = mapOf(
                "mp4" to "video/mp4",
                "m4v" to "video/x-m4v",
                "mkv" to "video/x-matroska",
                "webm" to "video/webm",
                "mov" to "video/quicktime",
                "avi" to "video/x-msvideo",
                "wmv" to "video/x-ms-wmv",
                "ts" to "video/mp2t",
                "m2ts" to "video/mp2t",
                "hevc" to "video/hevc",
            )
            videoAssociations.forEach { (extension, mimeType) ->
                windows.fileAssociation(mimeType, extension, "SkyWing video")
            }
            windows {
                upgradeUuid = "f40c59ef-e520-4e10-b7a2-b86e7458f923"
                menu = true
                menuGroup = "SkyWing"
                shortcut = true
            }
        }
    }
}

/** Licence, notice and copyright files, matched by file name (for example LICENSE, NOTICE.txt, jogl.LICENSE.txt). */
fun isLegalFileName(path: String): Boolean {
    val leaf = path.substringAfterLast('/').lowercase()
    val words = listOf("license", "licence", "notice", "copying", "copyright", "authors")
    return words.any { leaf == it || leaf.startsWith("$it.") || leaf.startsWith("$it-") || leaf.startsWith("${it}_") } ||
        Regex("""[._-](license|licence|notice|copying)([._-]|$)""").containsMatchIn(leaf)
}

/**
 * Copies the legal files of a .tar.gz archive nested in a runtime jar (JCEF ships its Chromium bundle this way)
 * into [target]. Reads plain ustar and GNU tar headers; returns the number of files copied.
 */
fun extractLegalFilesFromTarGz(archive: InputStream, target: File): Int {
    val tar = BufferedInputStream(GZIPInputStream(archive), 65536)
    val header = ByteArray(512)
    val root = target.canonicalFile.toPath()
    var copied = 0
    var longName: String? = null
    while (tar.readNBytes(header, 0, header.size) == header.size && header.any { it != 0.toByte() }) {
        fun field(from: Int, to: Int) = String(header, from, to - from, Charsets.UTF_8).substringBefore('\u0000')
        val size = field(124, 136).trim().ifEmpty { "0" }.toLong(8)
        val padding = (512 - size % 512) % 512
        val type = header[156].toInt().toChar()
        // Only POSIX ustar headers use the prefix field; GNU headers keep other data there.
        val ustar = String(header, 257, 6, Charsets.US_ASCII) == "ustar\u0000"
        val name = longName ?: listOf(if (ustar) field(345, 500) else "", field(0, 100)).filter { it.isNotEmpty() }.joinToString("/")
        longName = null
        when {
            type == 'L' -> longName = String(tar.readNBytes(size.toInt()), Charsets.UTF_8).trimEnd('\u0000')
            (type == '0' || type == '\u0000') && isLegalFileName(name) -> {
                val file = target.resolve(name.removePrefix("./")).canonicalFile
                check(file.toPath().startsWith(root)) { "Invalid archive path: $name" }
                file.parentFile.mkdirs()
                file.outputStream().use { out ->
                    var left = size
                    val buffer = ByteArray(65536)
                    while (left > 0) {
                        val read = tar.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                        check(read > 0) { "Truncated archive entry: $name" }
                        out.write(buffer, 0, read)
                        left -= read
                    }
                }
                copied++
            }
            else -> tar.skipNBytes(size)
        }
        tar.skipNBytes(padding)
    }
    return copied
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
        // Licences declared in each artifact's Maven POM (or its parent POMs): evidence for jars that embed none.
        fun declaredLicences(group: String, name: String, version: String): String {
            var coordinates = Triple(group, name, version)
            repeat(5) {
                val (g, n, v) = coordinates
                val pom = runCatching {
                    configurations.detachedConfiguration(dependencies.create("$g:$n:$v@pom")).singleFile
                }.getOrNull() ?: return "POM not resolved"
                val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                }.newDocumentBuilder().parse(pom)
                val project = document.documentElement
                fun org.w3c.dom.Element.child(tag: String) = (0 until childNodes.length).map { childNodes.item(it) }
                    .filterIsInstance<org.w3c.dom.Element>().firstOrNull { it.tagName == tag }
                val licences = project.child("licenses")?.getElementsByTagName("license")?.let { list ->
                    (0 until list.length).map { list.item(it) as org.w3c.dom.Element }.map { licence ->
                        listOfNotNull(licence.child("name")?.textContent?.trim(), licence.child("url")?.textContent?.trim())
                            .joinToString(" ")
                    }
                }.orEmpty()
                if (licences.isNotEmpty()) return licences.joinToString("; ")
                val parent = project.child("parent") ?: return "none declared"
                coordinates = Triple(
                    parent.child("groupId")?.textContent?.trim().orEmpty(),
                    parent.child("artifactId")?.textContent?.trim().orEmpty(),
                    parent.child("version")?.textContent?.trim().orEmpty(),
                )
            }
            return "none declared"
        }
        val inventory = StringBuilder("Artifact\tFile\tSHA-256\tExtracted notice files\tDeclared licences (POM)\n")
        val artifacts = configurations.getByName("runtimeClasspath").resolvedConfiguration.resolvedArtifacts
        var withoutEvidence = 0
        for (artifact in artifacts.sortedBy { it.moduleVersion.id.toString() + ":" + it.file.name }) {
            val jar = artifact.file
            check(jar.extension == "jar") { "Review non-JAR runtime artifact: ${jar.name}" }
            val id = artifact.moduleVersion.id
            // Keep notice paths short enough for WiX when runtime jars have long version names.
            val artifactKey = "${id.group}:${id.name}:${id.version}:${jar.name}"
            val artifactHash = MessageDigest.getInstance("SHA-256")
                .digest(artifactKey.toByteArray(Charsets.UTF_8))
                .take(12).joinToString("") { "%02x".format(it.toInt() and 255) }
            val artifactDir = out.resolve("jvm/$artifactHash")
            var noticeCount = 0
            ZipFile(jar).use { zip ->
                for (entry in zip.entries().asSequence().filter { !it.isDirectory }.sortedBy { it.name }) {
                    if (entry.name.endsWith(".tar.gz")) {
                        // A native bundle inside the jar; its notices live inside the archive.
                        zip.getInputStream(entry).use { noticeCount += extractLegalFilesFromTarGz(it, artifactDir.resolve("nested")) }
                        continue
                    }
                    if (!isLegalFileName(entry.name)) continue
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
            val declared = declaredLicences(id.group, id.name, id.version)
            if (noticeCount == 0 && declared in setOf("none declared", "POM not resolved")) withoutEvidence++
            inventory.append("$id\t${jar.name}\t$digest\t$noticeCount\t$declared\n")
        }
        out.resolve("JVM-RUNTIME-INVENTORY.tsv").writeText(inventory.toString())
        if (withoutEvidence > 0) logger.warn("$withoutEvidence runtime artifacts have neither an embedded notice nor a declared licence.")
        check(out.resolve("upstream/mpv-Copyright.txt").isFile)
        check(out.resolve("licenses/GPL-3.0.txt").length() > 30000)
        logger.lifecycle("Bundled Windows legal files for ${artifacts.size} runtime artifacts. Native-source audit remains tracked in SOURCE-STATUS.md.")
    }
}

tasks.matching { it.name in setOf("prepareAppResources", "createDistributable", "packageExe", "packageMsi") }.configureEach {
    dependsOn(generateWindowsLegal)
}

// Compose 1.8.2 does not count the prepared app resources as a createDistributable input: after only libmpv,
// motion or legal files change, it reports UP-TO-DATE and keeps the old files in the app image.
tasks.matching { it.name == "createDistributable" }.configureEach {
    inputs.files(tasks.named("prepareAppResources")).withPropertyName("preparedAppResources")
}

// Same source, same jar bytes: no file timestamps, fixed entry order.
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val generateBuildInfo by tasks.registering {
    val destination = layout.buildDirectory.dir("generated/build-info")
    outputs.dir(destination)
    inputs.property("previewVersion", previewVersion)
    doLast {
        destination.get().asFile.apply { mkdirs() }.resolve("skywing-build.txt").writeText(previewVersion)
    }
}
sourceSets.main { resources.srcDir(generateBuildInfo) }

// SkyWing's installer will not replace a newer installed SkyWing, and a local build has no CI run number, so it packages
// 0.1.1. Packaging therefore stops when this PC already has a newer SkyWing (0.1.1 was once built next to 0.1.98).

/** Windows Installer compares the first three version fields as numbers, so 0.1.10 is newer than 0.1.9. */
fun compareMsiVersions(a: String, b: String): Int {
    fun fields(version: String) = version.split('.').take(3).map { it.toInt() }
    val (left, right) = fields(a) to fields(b)
    for (i in 0 until 3) {
        val difference = left.getOrElse(i) { 0 }.compareTo(right.getOrElse(i) { 0 })
        if (difference != 0) return difference
    }
    return 0
}

/** Explains why a package numbered [packaged] would be older than the newest [installed] SkyWing, or returns null. */
fun olderThanInstalled(packaged: String, installed: List<String>): String? {
    val newest = installed.maxWithOrNull(::compareMsiVersions) ?: return null
    return if (compareMsiVersions(packaged, newest) < 0) "SkyWing $newest is installed on this PC and this package would be $packaged." else null
}

val testPackageVersionGuard by tasks.registering {
    group = "verification"
    description = "Checks the rule that stops packaging a SkyWing older than the installed one"
    doLast {
        // Packaged version, installed versions, and whether packaging must stop.
        val cases = listOf(
            Triple("0.1.1", listOf("0.1.98"), true),
            Triple("0.1.99", listOf("0.1.98"), false),
            Triple("0.1.98", listOf("0.1.98"), false),
            Triple("0.1.1", emptyList(), false),
            Triple("0.1.10", listOf("0.1.9"), false),
            Triple("0.1.9", listOf("0.1.10"), true),
            Triple("0.2.1", listOf("0.1.98"), false),
            Triple("0.1.99", listOf("0.1.98", "0.1.120"), true),
        )
        val wrong = cases.filter { (packaged, installed, stop) -> (olderThanInstalled(packaged, installed) != null) != stop }
        check(wrong.isEmpty()) {
            "Package version rule is wrong for: " +
                wrong.joinToString { (packaged, installed, stop) -> "$packaged with $installed installed should ${if (stop) "stop" else "proceed"}" }
        }
        logger.lifecycle("Package version rule: all ${cases.size} cases pass.")
    }
}
tasks.test { dependsOn(testPackageVersionGuard) }

/** Versions of the SkyWing installs on this PC with [upgradeCode], read from Windows Installer without changing anything. */
fun installedSkyWingVersions(upgradeCode: String): List<String> {
    val powershell = File(System.getenv("SystemRoot").orEmpty(), "System32/WindowsPowerShell/v1.0/powershell.exe")
    check(powershell.isFile) { "Windows PowerShell not found" }
    val script = "\$installer = New-Object -ComObject WindowsInstaller.Installer; " +
        "foreach (\$product in \$installer.RelatedProducts('{${upgradeCode.uppercase()}}')) { \$installer.ProductInfo(\$product, 'VersionString') }"
    val process = ProcessBuilder(powershell.path, "-NoProfile", "-NonInteractive", "-Command", script).redirectErrorStream(true).start()
    if (!process.waitFor(60, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        error("Windows Installer query timed out")
    }
    val output = process.inputStream.bufferedReader().readText().trim()
    check(process.exitValue() == 0) { output.ifEmpty { "Windows Installer query failed" } }
    return output.lines().map(String::trim).filter(String::isNotEmpty).onEach {
        check(Regex("""\d+(\.\d+){1,3}""").matches(it)) { "unexpected installed version '$it'" }
    }
}

val checkPackageVersion by tasks.registering {
    group = "distribution"
    description = "Stops packaging a SkyWing older than the one installed on this PC"
    val upgradeCode = requireNotNull(compose.desktop.application.nativeDistributions.windows.upgradeUuid)
    val allowLower = providers.gradleProperty("skywing.allowLowerVersion").map { it.toBoolean() }.orElse(false)
    onlyIf("packaging on Windows") { System.getProperty("os.name").startsWith("Windows") }
    doLast {
        val installed = runCatching { installedSkyWingVersions(upgradeCode) }.getOrElse {
            logger.warn("Could not read the installed SkyWing version (${it.message}), so this package ($previewVersion) was not checked against it.")
            return@doLast
        }
        val problem = olderThanInstalled(previewVersion, installed) ?: return@doLast
        if (allowLower.get()) {
            logger.warn("$problem Packaging it anyway (-Pskywing.allowLowerVersion=true): it cannot be installed over the newer SkyWing.")
        } else {
            throw GradleException(
                "$problem Windows Installer will not install it over the newer SkyWing. Set GITHUB_RUN_NUMBER so that " +
                    "0.1.<number> is above the installed version, as CI numbers its builds, or pass " +
                    "-Pskywing.allowLowerVersion=true for a package you will not install on this PC."
            )
        }
    }
}
tasks.matching { it.name in setOf("packageExe", "packageMsi", "packageReleaseExe", "packageReleaseMsi") }.configureEach {
    dependsOn(checkPackageVersion)
}
