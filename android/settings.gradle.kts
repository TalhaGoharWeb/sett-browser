pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://maven.mozilla.org/maven2") }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Vendored org.mozilla.* artifacts (see vendor_mozilla.py): Gradle
        // cannot reach maven.mozilla.org through this sandbox's proxy,
        // curl can, so the Mozilla closure lives on disk.
        maven { url = uri("local-maven") }
        google()
        mavenCentral()
        maven { url = uri("https://maven.mozilla.org/maven2") }
    }
}
rootProject.name = "PrivateX"
include(":app")
