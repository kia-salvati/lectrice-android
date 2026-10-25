import java.security.MessageDigest
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

/**
 * Packs the model bundles into assets for -Plectrice.bundleModels=true. Each distinct file is stored once as
 * `models/files/<sha256>.bin` (both bundles share espeak-ng-data, voices and lexicons); `models/manifest.json` lists
 * every bundle's files with their size and SHA-256, and a version the app compares with its installed copy.
 */
abstract class PackBundledModels : DefaultTask() {
    @get:Internal abstract val modelsDir: DirectoryProperty
    @get:Input abstract val bundles: MapProperty<String, List<String>>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val sources: ConfigurableFileCollection
    @get:OutputDirectory abstract val assetsDir: DirectoryProperty

    @TaskAction
    fun pack() {
        val filesDir = assetsDir.get().dir("models/files").asFile.apply { deleteRecursively(); mkdirs() }
        val manifest = bundles.get().entries.joinToString(",\n", "{\"bundles\": {\n", "\n}}\n") { (bundle, roots) ->
            val bundleDir = modelsDir.get().dir(bundle).asFile
            val files = roots.flatMap { root -> bundleDir.resolve(root).walkTopDown().filter(File::isFile).toList() }.sortedBy(File::getPath)
            val entries = files.map { file ->
                val sha = sha256(file)
                val blob = filesDir.resolve("$sha.bin")
                if (!blob.exists()) file.copyTo(blob)
                "{\"path\": \"${file.relativeTo(bundleDir).invariantSeparatorsPath}\", \"size\": ${file.length()}, \"sha256\": \"$sha\"}"
            }
            val version = hex(MessageDigest.getInstance("SHA-256").digest(entries.joinToString("\n").toByteArray()))
            "\"$bundle\": {\"version\": \"$version\", \"files\": [\n  ${entries.joinToString(",\n  ")}\n]}"
        }
        assetsDir.get().file("models/manifest.json").asFile.writeText(manifest)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 20)
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}

val sherpaSets = mapOf("sherpa1" to "../lectrice", "sherpa2" to "../lectrice-sherpa2")
val repoDir = rootProject.layout.projectDirectory

val bundleModels = providers.gradleProperty("lectrice.bundleModels").map(String::toBoolean).getOrElse(false)
// What the English Kokoro engine reads (the same list as push-model.sh). The sherpa2 build ignores espeak-ng-data.
val sharedModelFiles = listOf("voices.bin", "tokens.txt", "lexicon-us-en.txt", "lexicon-gb-en.txt", "espeak-ng-data")
val bundledModels = mapOf(
    "kokoro-multi-lang-v1_0" to sharedModelFiles + "model.onnx",
    "kokoro-int8-multi-lang-v1_0" to sharedModelFiles + "model.int8.onnx",
)
val modelsRoot = repoDir.dir("../lectrice/models")
val packModels = tasks.register<PackBundledModels>("packBundledModels") {
    modelsDir.set(modelsRoot)
    bundles.set(bundledModels)
    sources.from(bundledModels.flatMap { (bundle, files) -> files.map { modelsRoot.dir(bundle).file(it) } })
    assetsDir.set(layout.buildDirectory.dir("generated/bundledModels/assets"))
}

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

    // Stored, not deflated: the first launch copies the bundled models straight out of the APK.
    if (bundleModels) androidResources { noCompress += "bin" }
}

androidComponents {
    onVariants { variant ->
        val rust = rustTasks.getValue(requireNotNull(variant.flavorName))
        variant.sources.jniLibs?.addGeneratedSourceDirectory(rust, BuildRustFfi::jniLibsDir)
        variant.sources.kotlin?.addGeneratedSourceDirectory(rust, BuildRustFfi::kotlinDir)
        if (bundleModels) {
            variant.sources.assets?.addGeneratedSourceDirectory(packModels, PackBundledModels::assetsDir)
            variant.sources.kotlin?.addStaticSourceDirectory("src/modelsBundled/kotlin")
        } else {
            variant.sources.kotlin?.addStaticSourceDirectory("src/modelsPushed/kotlin")
        }
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
