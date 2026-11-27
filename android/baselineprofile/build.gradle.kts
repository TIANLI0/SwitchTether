// :baselineprofile — Baseline Profile generator and Macrobenchmarks for :app (one module, the
// layout of Android Studio's Baseline Profile template). Needs a device or emulator; see
// android/DEV_NOTES.md "Performance". Nothing here is part of the shipped APK.
plugins {
    alias(libs.plugins.androidTest)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "io.github.tianli0.switchtether.baselineprofile"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        minSdk = 35
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Emulators are fine for profile generation; their benchmark numbers are indicative only.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    targetProjectPath = ":app"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
}

// Runs on connected devices (pick one with ANDROID_SERIAL); no Gradle-managed device.
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
