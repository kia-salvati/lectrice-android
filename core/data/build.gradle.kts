import javax.inject.Inject

// The data layer: RustCore (the uniffi bindings to lectrice-ffi), repositories, mappers, settings, model files.
plugins {
    alias(libs.plugins.android.library)
}

/** Runs rust/build-android.sh: liblectrice_ffi.so with its native libraries, and the Kotlin bindings. */
abstract class BuildRustFfi : DefaultTask() {
    @get:Internal abstract val repoRoot: DirectoryProperty
    @get:OutputDirectory abstract val jniLibsDir: DirectoryProperty
    @get:OutputDirectory abstract val kotlinDir: DirectoryProperty
    @get:Inject abstract val execOperations: ExecOperations

    @TaskAction
    fun build() {
        val root = repoRoot.get().asFile
        execOperations.exec {
            workingDir = root
            commandLine(root.resolve("rust/build-android.sh").path, "sherpa1", jniLibsDir.get().asFile.path, kotlinDir.get().asFile.path)
        }
    }
}

val buildRust = tasks.register<BuildRustFfi>("buildRustFfi") {
    // cargo decides what is out of date.
    outputs.upToDateWhen { false }
    repoRoot.set(rootProject.layout.projectDirectory)
    jniLibsDir.set(layout.buildDirectory.dir("rust/jniLibs"))
    kotlinDir.set(layout.buildDirectory.dir("generated/uniffi"))
}

android {
    ndkVersion = libs.versions.ndk.get()
    defaultConfig {
        ndk { abiFilters += "arm64-v8a" }
        consumerProguardFiles("consumer-rules.pro")
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(buildRust, BuildRustFfi::jniLibsDir)
        variant.sources.kotlin?.addGeneratedSourceDirectory(buildRust, BuildRustFfi::kotlinDir)
    }
}

dependencies {
    api(project(":core:model"))
    implementation(libs.jna) { artifact { type = "aar" } }
    implementation(libs.coroutines.android)
    implementation(libs.core.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
