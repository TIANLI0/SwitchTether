// :core:remote — pure JVM. Protocol models (docs/PROTOCOL.md), pairing URI, pinned mTLS OkHttp
// client, events WebSocket, reducers and command helpers. No Android APIs so it is unit-testable
// on the JVM (including real TLS 1.3 handshakes against MockWebServer).
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(libs.okhttp)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(libs.okhttp.tls)
}

tasks.test {
    // Shared fixtures live at <repo>/protocol/fixtures (parsed by the Rust side too).
    systemProperty("switchtether.fixtures", rootDir.resolve("../protocol/fixtures").absolutePath)
}
