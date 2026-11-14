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

// coui17 (Costben/coui17) vendored as source, pinned; see third_party/coui17/UPSTREAM.md.
listOf(
    "coui-shader",
    "coui-blur",
    "coui-squircle",
    "coui-core",
    "coui-ui",
    "coui-preference",
    "coui-icons",
).forEach { name ->
    include(":$name")
    project(":$name").projectDir = file("third_party/coui17/$name")
}
