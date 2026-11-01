import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
}

// GeckoView 159 and AppServices 159 (places storage) ship different
// glean-native versions providing the same Gradle capability, which
// Gradle refuses to pick between. The engine's version wins; the
// storage layer's Glean is never initialised (Sett has no telemetry).
configurations.configureEach {
    resolutionStrategy {
        // One JNA, the vendored one — a stray higher version (with both
        // jar and aar artifacts) otherwise enters via transitive metadata.
        force("net.java.dev.jna:jna:5.12.1")
        capabilitiesResolution.withCapability("org.mozilla.telemetry:glean-native") {
            // Candidates are the providing modules: geckoview-omni
            // (bundles glean-native 70.2.1) vs the standalone
            // glean-native 70.0.0 from AppServices. The engine wins.
            candidates.firstOrNull { candidate -> candidate.toString().contains("geckoview") }
                ?.let { select(it) }
        }
    }
}

val keystorePropertiesFile = rootProject.file("key.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "app.privatex.browser"
    // v0.1.1: compile against the standard Android 16 platform (API 36).
    // v0.1.0 was compiled against the API 37.0 minor platform, which made
    // aapt stamp compileSdkVersionCodename='17' into the APK — a
    // nonstandard package stamp that is the prime suspect for Android 16
    // installers rejecting the package as invalid. AC 159 AARs declare
    // minCompileSdk 37 in their metadata; that check is disabled below
    // (we call no API-37 APIs, and the artifact is verified after build).
    compileSdk = 36

    defaultConfig {
        applicationId = "app.privatex.browser"
        minSdk = 26
        targetSdk = 36
        versionCode = 14
        versionName = "0.8.0"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
            // v0.1.1: sign with all three schemes for maximum installer
            // compatibility (v0.1.0 carried v2 only).
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            // v0.1: no R8 minification yet — GeckoView/AC are reflection-
            // heavy; shrinking is enabled only after on-device testing.
            isMinifyEnabled = false
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// AC 159 AAR metadata declares minCompileSdk 37 while we compile against
// 36 (see the compileSdk note above). Disable AGP's AAR-metadata gate; the
// packaged APK is verified directly after the build instead.
tasks.matching { it.name.contains("AarMetadata") }.configureEach {
    enabled = false
}

dependencies {
    val ac = "159.0"
    implementation("org.mozilla.components:browser-engine-gecko:$ac")
    implementation("org.mozilla.components:browser-state:$ac")
    implementation("org.mozilla.components:browser-session-storage:$ac")
    implementation("org.mozilla.components:browser-errorpages:$ac")
    implementation("org.mozilla.components:feature-session:$ac")
    implementation("org.mozilla.components:feature-tabs:$ac")
    implementation("org.mozilla.components:feature-findinpage:$ac")
    implementation("org.mozilla.components:feature-prompts:$ac")
    implementation("org.mozilla.components:browser-storage-sync:$ac")
    // Referenced directly (storage supertypes, snackbar delegate, app
    // links, places root GUIDs) — POM-scope transitivity hides them
    // from the compile classpath, so they are declared explicitly.
    implementation("org.mozilla.components:concept-toolbar:$ac")
    implementation("org.mozilla.components:concept-sync:$ac")
    implementation("org.mozilla.components:ui-widgets:$ac")
    implementation("org.mozilla.components:feature-app-links:$ac")
    implementation("org.mozilla.appservices:places:159.0.1")
    implementation("org.mozilla.components:feature-readerview:$ac")
    implementation("org.mozilla.components:feature-contextmenu:$ac")
    implementation("org.mozilla.components:feature-recentlyclosed:$ac")
    implementation("org.mozilla.components:support-webextensions:$ac")
    // Phase 4 vault: lazysodium (bundled libsodium) + Keystore biometrics.
    // The Android AAR is self-contained; the Java artifact is tests-only
    // (same classes — it must never enter the APK). JNA needs its AAR
    // variant for libjnidispatch.so.
    implementation("com.goterl:lazysodium-android:5.1.0") {
        // JNA comes exactly once, as the AAR below (the jar lacks
        // libjnidispatch.so; having both = duplicate-class failure).
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    implementation("net.java.dev.jna:jna:5.12.1@aar")
    implementation("androidx.biometric:biometric:1.1.0")
    testImplementation("com.goterl:lazysodium-java:5.1.0")
    testImplementation("net.java.dev.jna:jna:5.12.1")
    testImplementation("org.json:json:20240303")
    implementation("org.mozilla.components:feature-downloads:$ac")
    implementation("org.mozilla.components:lib-fetch-httpurlconnection:$ac")
    implementation("org.mozilla.components:support-ktx:$ac")
    implementation("org.mozilla.components:support-utils:$ac")
    // AC publishes these as implementation-scope deps of the feature
    // modules; consumers must declare them explicitly (Fenix does the same).
    implementation("org.mozilla.components:concept-engine:$ac")
    implementation("org.mozilla.components:concept-fetch:$ac")
    implementation("org.mozilla.components:support-base:$ac")
    implementation("org.mozilla.components:lib-state:$ac")

    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("com.google.android.material:material:1.12.0")

    // JVM unit tests (JUnit vendored into local-maven; no Android deps).
    testImplementation("junit:junit:4.13.2")
}
