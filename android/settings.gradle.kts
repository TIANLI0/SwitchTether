// SwitchTether Android (native client). See docs/ANDROID_UI.md and android/DEV_NOTES.md.
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("""com\.android.*""")
                includeGroupByRegex("""com\.google.*""")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Google's repository only serves Android/Google groups; everything else comes from Maven Central.
        google {
            content {
                includeGroupByRegex("""com\.android.*""")
                includeGroupByRegex("""com\.google.*""")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "SwitchTether"

include(":app")
include(":core:remote")
include(":core:security")
include(":core:ui")
// Baseline Profile generator + Macrobenchmarks for :app (android/DEV_NOTES.md "Performance").
include(":baselineprofile")

// COUI17 (github.com/TIANLI0/COUI17) as a git submodule, built from source at the pinned commit;
// see third_party/coui17/UPSTREAM.md. Run `git submodule update --init` after cloning.
listOf(
    "coui-shader",
    "coui-blur",
    "coui-squircle",
    "coui-core",
    "coui-ui",
    "coui-preference",
    "coui-icons",
    // Back-stack host with finger-following predictive back (upstream coui-nav).
    "coui-nav",
    // Page-level patterns (page scaffold, panels, glass menu, composer, button tiers).
    "coui-patterns",
).forEach { name ->
    include(":$name")
    project(":$name").projectDir = file("third_party/coui17/$name")
}
