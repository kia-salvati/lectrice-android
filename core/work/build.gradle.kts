// Long background work in a foreground service: exports (with the MediaCodec AAC encoder), prepare jobs, model downloads.
plugins {
    alias(libs.plugins.android.library)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(libs.core.ktx)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
