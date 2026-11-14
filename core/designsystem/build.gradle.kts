// The theme (tokens for light, dark and black) and the stateless components every screen draws with.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android { buildFeatures { compose = true } }

dependencies {
    api(platform(libs.compose.bom))
    api(libs.compose.foundation)
    api(libs.compose.material3)
    api(libs.compose.ui)
    implementation(project(":core:model"))
    testImplementation(libs.junit)
}
