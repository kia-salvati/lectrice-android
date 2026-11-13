package app.lectrice.core.data

import android.content.Context
import java.io.File

/**
 * Where installed models live: `filesDir/models/<model id>/` (file 20 §3.1). A folder counts as installed only once its
 * `.installed` marker is written (after a checked unpack), so a half-unpacked folder is never loaded.
 */
class ModelStore(private val context: Context) {
    val root: File get() = File(context.filesDir, MODELS_DIR)

    fun dir(spec: ModelSpec): File = File(root, spec.info.id)

    fun isInstalled(spec: ModelSpec): Boolean = File(dir(spec), MARKER).isFile

    fun installed(): List<ModelSpec> = ModelSpec.entries.filter(::isInstalled)

    fun markInstalled(spec: ModelSpec) {
        File(dir(spec), MARKER).writeText(spec.archiveSha256)
    }

    fun delete(spec: ModelSpec) {
        dir(spec).deleteRecursively()
    }

    fun sizeBytes(spec: ModelSpec): Long = dir(spec).walkTopDown().filter(File::isFile).sumOf(File::length)

    private companion object {
        const val MODELS_DIR = "models"
        const val MARKER = ".installed"
    }
}
