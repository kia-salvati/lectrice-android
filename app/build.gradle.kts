import java.util.Properties

// The composition root: MainActivity, the navigator and AppContainer (hand-written dependency injection).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Release signing from the gitignored keystore.properties (storeFile, storePassword, keyAlias, keyPassword);
 * `-Plectrice.keystoreProperties=<file>` names another file.
 */
val keystoreProperties = rootProject.file(providers.gradleProperty("lectrice.keystoreProperties").getOrElse("keystore.properties"))
val signing = Properties().apply {
    keystoreProperties.takeIf { it.isFile }?.inputStream()?.use(::load)
}
val hasReleaseKey = signing.getProperty("storeFile") != null

/** Phone tests only: `-Plectrice.debugSignedRelease=true` signs a release build with the debug key. Never upload one. */
val debugSignedRelease = providers.gradleProperty("lectrice.debugSignedRelease").map(String::toBoolean).getOrElse(false)

/**
 * A release build without the release key fails here. It must never fall back to the debug key by itself: such an APK
 * looks like a release, and whoever installs it can't update to the real one without losing their library.
 */
val checkReleaseSigning = tasks.register("checkReleaseSigning") {
    val allowed = hasReleaseKey || debugSignedRelease
    val debugSigned = !hasReleaseKey && debugSignedRelease
    val expected = keystoreProperties.path
    doLast {
        if (!allowed) {
            throw GradleException(
                "Release signing is not set up: $expected is missing or has no storeFile. " +
                    "For a phone test only, add -Plectrice.debugSignedRelease=true (signs with the debug key; never upload that APK).",
            )
        }
        if (debugSigned) logger.warn("WARNING: this release build is signed with the DEBUG key (-Plectrice.debugSignedRelease). Do not upload it.")
    }
}
tasks.configureEach { if (name == "preReleaseBuild") dependsOn(checkReleaseSigning) }

android {
    namespace = "app.lectrice"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "app.lectrice"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without the release key the build fails (checkReleaseSigning) unless the debug key was asked for by name.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug").takeIf { debugSignedRelease }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:playback"))
    implementation(project(":core:work"))
    implementation(project(":core:link"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:library"))
    implementation(project(":feature:reader"))
    implementation(project(":feature:settings"))
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.process)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
}
