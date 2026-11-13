// Media3 playback: the MediaSessionService, the sentence DataSource, the read-ahead governor and the sleep timer.
plugins {
    alias(libs.plugins.android.library)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    api(libs.media3.exoplayer)
    implementation(libs.media3.datasource)
    api(libs.media3.session)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
