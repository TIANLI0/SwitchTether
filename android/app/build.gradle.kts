import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "io.github.tianli0.switchtether"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "io.github.tianli0.switchtether"
        minSdk = 35
        // Android 17. Local network access needs ACCESS_LOCAL_NETWORK (requested at runtime).
        targetSdk = 37
        versionCode = 38
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 64-bit ARM (ARMv8) devices only; smaller APK.
        ndk { abiFilters += "arm64-v8a" }
        // Self-update source: public GitHub releases of TIANLI0/SwitchTether. The API base can be
        // overridden for local end-to-end tests (`-PupdateApiBase=http://10.0.2.2:8765`, debug only).
        buildConfigField("String", "UPDATE_REPO", "\"TIANLI0/SwitchTether\"")
        buildConfigField("String", "UPDATE_API_BASE", "\"https://api.github.com\"")
    }

    // Release signing: the keystore and its passwords live OUTSIDE the repository. Point to a
    // properties file (storeFile, storePassword, keyAlias, keyPassword) with
    // `switchtether.signing.properties=<path>` in local.properties or the
    // SWITCHTETHER_SIGNING_PROPERTIES environment variable; in CI (release.yml) the
    // ST_KEYSTORE_FILE / ST_KEYSTORE_PASSWORD / ST_KEY_ALIAS / ST_KEY_PASSWORD variables from
    // GitHub secrets. Without any of these, release is unsigned.
    val localProps = Properties().apply {
        rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
    }
    val signingProps = (localProps.getProperty("switchtether.signing.properties")
        ?: System.getenv("SWITCHTETHER_SIGNING_PROPERTIES"))
        ?.let { file(it) }
        ?.takeIf { it.isFile }
        ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
        ?: System.getenv("ST_KEYSTORE_FILE")?.takeIf { it.isNotBlank() && file(it).isFile }?.let { ks ->
            Properties().apply {
                setProperty("storeFile", ks)
                setProperty("storePassword", System.getenv("ST_KEYSTORE_PASSWORD"))
                setProperty("keyAlias", System.getenv("ST_KEY_ALIAS"))
                setProperty("keyPassword", System.getenv("ST_KEY_PASSWORD"))
            }
        }
    signingConfigs {
        if (signingProps != null) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            (project.findProperty("updateApiBase") as String?)?.let { buildConfigField("String", "UPDATE_API_BASE", "\"$it\"") }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfigs.findByName("release")?.let { signingConfig = it }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    // Android Lint: findings already present are recorded in lint-baseline.xml; anything new fails
    // the build (abortOnError). Regenerate with `./gradlew :app:updateLintBaseline` only when deliberately accepting.
    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = true
        checkDependencies = false
        lintConfig = rootProject.file("lint.xml")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        localeFilters += listOf("en", "zh-rCN")
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Test classes share JVMs. Robolectric's Conscrypt provider used to leak into the JVM-wide
            // provider list and break JCE provider verification for later plain-JVM TLS / cipher
            // tests (javax.crypto.JarVerifier); src/test/resources/robolectric.properties sets
            // conscryptMode=OFF so it is never installed. Half the cores, capped: each Robolectric
            // fork is memory-heavy and more forks stopped paying off locally.
            all { it.maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 6) }
        }
    }
    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }
}

kotlin {
    jvmToolchain(21)
}

// :core:remote models are immutable but built without the Compose compiler; see the file's header.
composeCompiler {
    stabilityConfigurationFiles.add(layout.projectDirectory.file("compose-stability.conf"))
}

// Baseline Profile (android/DEV_NOTES.md "Performance"): generated on a device by
// `./gradlew :app:generateBaselineProfile` from :baselineprofile and committed under
// src/release/generated/baselineProfiles/. Never generated during a normal build, so release
// builds (CI) need no device.
baselineProfile {
    automaticGenerationDuringBuild = false
    saveInSrc = true
    dexLayoutOptimization = true
}

dependencies {
    implementation(libs.lottie.compose)
    implementation(project(":core:remote"))
    implementation(project(":core:security"))
    implementation(project(":core:ui"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigationevent)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    // File viewer: zoomable / tiled images, rendered Markdown, syntax highlighting (all Apache-2.0).
    implementation(libs.telephoto.zoomable)
    implementation(libs.telephoto.sub.sampling.image)
    // Flick-to-dismiss for the image lightbox (same author / license as zoomable, Apache-2.0).
    implementation(libs.telephoto.flick)
    implementation(libs.markdown.renderer)
    implementation(libs.markdown.renderer.code)
    implementation(libs.highlights)
    implementation(libs.androidx.compose.ui.tooling.preview)
    // Installs the Baseline Profile on devices without Play's cloud profiles (sideloaded APKs).
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(libs.okhttp.tls)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
