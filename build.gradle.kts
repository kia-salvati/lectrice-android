plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Shared settings for every Android library module (namespace = app.lectrice.<path>). Kept here instead of a buildSrc
// plugin, which would need the kotlin-dsl plugin from the plugin portal.
subprojects {
    plugins.withId("com.android.library") {
        val catalog = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")
        extensions.configure<com.android.build.api.dsl.LibraryExtension> {
            namespace = "app.lectrice." + path.removePrefix(":").replace(':', '.')
            compileSdk = catalog.findVersion("compileSdk").get().requiredVersion.toInt()
            buildToolsVersion = catalog.findVersion("buildTools").get().requiredVersion
            defaultConfig { minSdk = catalog.findVersion("minSdk").get().requiredVersion.toInt() }
            testOptions { unitTests.isReturnDefaultValues = true }
        }
    }
}
