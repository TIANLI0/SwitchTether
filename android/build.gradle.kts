// Root build: plugin versions come from gradle/libs.versions.toml. Only detekt is applied here.
plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.detekt)
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
