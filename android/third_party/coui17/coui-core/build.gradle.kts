// SwitchTether build file for the vendored coui17 module "coui-core" (Android-only KMP library).
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
        namespace = "io.github.suqi8.coui.kmp.core"
        compileSdk {
            version = release(37) { minorApiLevel = 0 }
        }
        buildToolsVersion = "37.0.0"
        minSdk = 23
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.jetbrains.compose.foundation)
        }
        androidMain.dependencies {

        }
    }
}
