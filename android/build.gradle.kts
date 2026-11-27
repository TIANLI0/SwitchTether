// Root build: plugin versions come from gradle/libs.versions.toml; nothing is applied here.
plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.androidTest) apply false
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.baselineprofile) apply false
}

// Compose compiler stability reports / metrics, off by default (android/DEV_NOTES.md "Performance"):
// `./gradlew :app:assembleRelease -PcomposeCompilerReports=true` writes them to
// <module>/build/compose_compiler/ for every module that applies the Compose compiler plugin.
if (providers.gradleProperty("composeCompilerReports").orNull == "true") {
    subprojects {
        plugins.withId("org.jetbrains.kotlin.plugin.compose") {
            extensions.configure<org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension> {
                reportsDestination.set(layout.buildDirectory.dir("compose_compiler"))
                metricsDestination.set(layout.buildDirectory.dir("compose_compiler"))
            }
        }
    }
}
