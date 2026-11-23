// :core:ui — theme wrapper over COUITheme + native renderers (markdown, unified diff, code,
// charts). No WebView anywhere.
plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "io.github.tianli0.switchtether.ui"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    buildToolsVersion = "37.0.0"
    defaultConfig {
        minSdk = 29
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":coui-ui"))
    api(project(":coui-preference"))
    api(project(":coui-icons"))
    // App icon set: Phosphor Icons (Regular + Fill pairs), MIT. R8 strips unused icons in release.
    api(libs.phosphor.icon)
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)

    testImplementation(libs.junit4)
}
