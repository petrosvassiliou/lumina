import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.androidx.room)
}

val enableAbiSplits = providers.gradleProperty("enableAbiSplits")
    .map { it.equals("true", ignoreCase = true) }
    .getOrElse(false)

val extractApkNativeLibs = providers.gradleProperty("extractApkNativeLibs")
    .map { it.equals("true", ignoreCase = true) }
    .getOrElse(false)

android {
    namespace = "app.lumina.player"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.lumina.player"
        minSdk = 26
        targetSdk = 35
        versionCode = 10002
        versionName = "1.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }

        if (!enableAbiSplits) {
            ndk {
                // Full native ABI coverage for Android Phones/Foldables (ARM) and Googlebook OS (ARM64 & x86_64)
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            }
        }

        // Multi-language support: English, German, Italian, French, Spanish, Greek
        resourceConfigurations += listOf("en", "de", "it", "fr", "es", "el")
    }

    splits {
        abi {
            isEnable = enableAbiSplits
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }

    signingConfigs {
        val keystorePath = providers.gradleProperty("LUMINA_KEYSTORE_PATH").orNull
            ?: System.getenv("LUMINA_KEYSTORE_PATH")
        if (!keystorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = providers.gradleProperty("LUMINA_KEYSTORE_PASSWORD").orNull
                    ?: System.getenv("LUMINA_KEYSTORE_PASSWORD")
                keyAlias = providers.gradleProperty("LUMINA_KEY_ALIAS").orNull
                    ?: System.getenv("LUMINA_KEY_ALIAS")
                keyPassword = providers.gradleProperty("LUMINA_KEY_PASSWORD").orNull
                    ?: System.getenv("LUMINA_KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Uncompressed page-aligned native libs for Play Store AAB; extracted native libs for standalone APKs
            useLegacyPackaging = extractApkNativeLibs
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }
}

room {
    schemaDirectory("$projectDir/build/room-schemas")
}

dependencies {
    // Core AndroidX & Per-App Locale Support
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.core.splashscreen)

    // Lifecycle & Coroutines
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // Jetpack Compose & Material 3
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    debugImplementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.window.size)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // Material 3 Adaptive & WindowManager (Foldable Tabletop + Googlebook OS Freeform Windows)
    implementation(libs.androidx.compose.adaptive)
    implementation(libs.androidx.compose.adaptive.layout)
    implementation(libs.androidx.compose.adaptive.navigation)
    implementation(libs.androidx.window)

    // Hilt Dependency Injection
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Room Database (Media Library & Playback Resume States)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DataStore (User Preferences & Equalizer State)
    implementation(libs.androidx.datastore.preferences)

    // Core Playback Engine: VideoLAN libVLC 4.0+
    implementation(libs.videolan.libvlc.all)

    // Media Artwork & Video Frame Thumbnails
    implementation(libs.coil.compose)
    implementation(libs.coil.video)

    // Google Play Official In-App Updates (100% Play Protect Compliant)
    implementation(libs.play.app.update)
    implementation(libs.play.app.update.ktx)

    // Unit Tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
