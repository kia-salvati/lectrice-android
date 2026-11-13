// The domain layer: pure Kotlin types, interfaces and helpers. No Android imports (an Android library only to share the
// build setup); its tests run on the JVM.
plugins {
    alias(libs.plugins.android.library)
}

dependencies {
    api(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
