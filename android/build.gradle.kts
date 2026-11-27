// Root build: plugin versions come from gradle/libs.versions.toml. Only detekt is applied here.
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
    alias(libs.plugins.detekt)
    alias(libs.plugins.baselineprofile) apply false
}

// detekt over SwitchTether's own Kotlin sources (third_party/ is excluded): a light config on top of
// the defaults; findings already present live in config/detekt/baseline.xml, new ones fail.
// Run `./gradlew detekt`; regenerate the baseline with `./gradlew detektBaseline`.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(file("config/detekt/detekt.yml"))
    baseline = file("config/detekt/baseline.xml")
    source.setFrom(
        listOf("app", "core/remote", "core/security", "core/ui").flatMap { module ->
            listOf("src/main/kotlin", "src/test/kotlin", "src/androidTest/kotlin").map { file("$module/$it") }
        },
    )
    parallel = true
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
