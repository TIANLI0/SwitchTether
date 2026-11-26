// :core:security — Android Keystore identity (TLS client cert) + step-up key + BiometricPrompt.
plugins {
    alias(libs.plugins.androidLibrary)
}

android {
    namespace = "io.github.tianli0.switchtether.security"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    buildToolsVersion = "37.0.0"
    defaultConfig {
        minSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":core:remote"))
    implementation(libs.androidx.core.ktx)
    api(libs.androidx.biometric)
    api(libs.androidx.fragment)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.okhttp.mockwebserver3)
    androidTestImplementation(libs.okhttp.tls)
}
