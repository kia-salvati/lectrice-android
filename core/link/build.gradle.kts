// The computer helper on the phone (file 24 C50): NSD discovery, the Wi-Fi network and socket binding, the
// Keystore-wrapped identity key, the paired computers and the link's state for the screens and the governor.
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
