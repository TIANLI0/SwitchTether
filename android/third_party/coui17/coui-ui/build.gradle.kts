// SwitchTether build file for the vendored coui17 module "coui-ui" (Android-only KMP library).
// Upstream build file: Costben/coui17 @ see ../UPSTREAM.md. Only src/commonMain + src/androidMain are vendored.
plugins {
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
}

kotlin {
    jvmToolchain(21)

    android {
        namespace = "io.github.suqi8.coui.kmp.ui"
        compileSdk {
            version = release(37) { minorApiLevel = 2 }
        }
        buildToolsVersion = "37.0.0"
        minSdk = 23
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":coui-blur"))
            api(project(":coui-core"))
            api(project(":coui-squircle"))
            api(libs.jetbrains.compose.foundation)
            implementation(libs.androidx.navigationevent)
            implementation(libs.jetbrains.compose.window.size)
            implementation(libs.materialkolor.utilities)
        }
        androidMain.dependencies {
            // org.lsposed.hiddenapibypass intentionally NOT included: see ../PATCHES.md (0001).
        }
    }
}
