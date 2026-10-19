import java.security.KeyStore

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.deepak.videolibrary"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.deepak.videolibrary"
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        targetSdk = 35
        // Every CI run and its update-test build gets a higher installable version.
        versionCode = providers.gradleProperty("previewVersionCode").orNull?.toInt()
            ?: (1000 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0) * 2)
        versionName = "0.10.0"
        resValue("string", "app_name", "Video Library")
    }

    signingConfigs {
        create("preview") {
            // Dedicated non-production test identity. Keep this key stable across CI runs.
            storeFile = rootProject.file(".ci/preview-signing.p12")
            storeType = "PKCS12"
            storePassword = "android"
            keyAlias = KeyStore.getInstance("PKCS12").run {
                rootProject.file(".ci/preview-signing.p12").inputStream().use { load(it, "android".toCharArray()) }
                aliases().toList().single { isKeyEntry(it) }
            }
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
            resValue("string", "app_name", "Video Library Preview")
            signingConfig = signingConfigs.getByName("preview")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/versions/**",
                "META-INF/{AL2.0,LGPL2.1}",
            )
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("dev.chrisbanes.haze:haze:1.1.0")
    implementation("org.videolan.android:libvlc-all:3.6.5")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    // LibVLC also exposes fragment helpers; align its older transitive Fragment with Activity Result.
    implementation("androidx.fragment:fragment:1.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Local database (library index, scan state, errors)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("androidx.room:room-paging:2.6.1")
    implementation("androidx.paging:paging-runtime-ktx:3.3.6")
    implementation("androidx.paging:paging-compose:3.3.6")
    ksp("androidx.room:room-compiler:2.6.1")

    // Background scanning
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-datasource:1.5.1")

    // SMB client
    implementation("com.hierynomus:smbj:0.13.0")
    implementation("org.slf4j:slf4j-android:1.7.36")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
