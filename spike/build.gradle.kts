import javax.inject.Inject
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/** One sherpa-onnx set per flavor: its Rust build swaps Cargo.lock, so two must never run at once. */
abstract class RustBuildLock : BuildService<BuildServiceParameters.None>

val rustBuildLock = gradle.sharedServices.registerIfAbsent("rustBuildLock", RustBuildLock::class) { maxParallelUsages.set(1) }

/** Runs rust/build-android.sh: liblectrice_ffi.so plus its native libraries, and the Kotlin bindings. */
abstract class BuildRustFfi : DefaultTask() {
    @get:Input abstract val sherpaSet: Property<String>
    @get:Internal abstract val repoRoot: DirectoryProperty
    @get:OutputDirectory abstract val jniLibsDir: DirectoryProperty
    @get:OutputDirectory abstract val kotlinDir: DirectoryProperty
    @get:Inject abstract val execOperations: ExecOperations

    @TaskAction
    fun build() {
        val root = repoRoot.get().asFile
        execOperations.exec {
            workingDir = root
            commandLine(root.resolve("rust/build-android.sh").path, sherpaSet.get(), jniLibsDir.get().asFile.path, kotlinDir.get().asFile.path, "bench")
        }
    }
}

val sherpaSets = mapOf("sherpa1" to "../lectrice", "sherpa2" to "../lectrice-sherpa2")
val repoDir = rootProject.layout.projectDirectory

fun coreRevision(checkout: String): String =
    providers.exec {
        commandLine("git", "-C", repoDir.dir(checkout).asFile.path, "describe", "--always", "--dirty")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().ifEmpty { "unknown" }

val rustTasks = sherpaSets.keys.associateWith { set ->
    tasks.register<BuildRustFfi>("buildRust${set.replaceFirstChar(Char::uppercase)}") {
        // cargo decides what is out of date.
        outputs.upToDateWhen { false }
        usesService(rustBuildLock)
        sherpaSet.set(set)
        repoRoot.set(repoDir)
        jniLibsDir.set(layout.buildDirectory.dir("rust/$set/jniLibs"))
        kotlinDir.set(layout.buildDirectory.dir("generated/uniffi/$set"))
    }
}

android {
    namespace = "app.lectrice.spike"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "app.lectrice.spike"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0-spike"
        ndk { abiFilters += "arm64-v8a" }
    }

    flavorDimensions += "sherpa"
    productFlavors {
        sherpaSets.forEach { (set, checkout) ->
            create(set) {
                dimension = "sherpa"
                applicationIdSuffix = ".$set"
                versionNameSuffix = "-$set"
                buildConfigField("String", "SHERPA_SET", "\"$set\"")
                buildConfigField("String", "CORE_REVISION", "\"${coreRevision(checkout)}\"")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // A test app: the debug key is enough.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Fallback for M-a if pdfium can't be loaded by bare name from inside the APK: -Plectrice.legacyPackaging=true.
    packaging { jniLibs.useLegacyPackaging = providers.gradleProperty("lectrice.legacyPackaging").map(String::toBoolean).getOrElse(false) }
}

androidComponents {
    onVariants { variant ->
        val rust = rustTasks.getValue(requireNotNull(variant.flavorName))
        variant.sources.jniLibs?.addGeneratedSourceDirectory(rust, BuildRustFfi::jniLibsDir)
        variant.sources.kotlin?.addGeneratedSourceDirectory(rust, BuildRustFfi::kotlinDir)
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.datasource)
    implementation(libs.coroutines.android)
    implementation(libs.jna) { artifact { type = "aar" } }
}
