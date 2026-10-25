package app.lectrice.spike

import android.content.Context
import android.content.res.AssetManager
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

private const val MANIFEST_ASSET = "models/manifest.json"
private const val FILES_ASSET_DIR = "models/files"
private const val VERSION_MARKER = ".bundled-version"
private const val BUFFER_BYTES = 1 shl 20
private const val PROGRESS_EVERY_BYTES = 100L shl 20
private const val BYTES_PER_MB = 1 shl 20

private class BundledFile(val path: String, val size: Long, val sha256: String)

/**
 * -Plectrice.bundleModels=true: the model bundles ship in the APK (see `PackBundledModels` in build.gradle.kts).
 * Copies each one into files/models/<bundle>/ unless that folder already holds this version, checking every file's
 * SHA-256 on the way. A bundle pushed with push-model.sh has no version marker, so it is replaced.
 */
object BundledModels {
    fun install(context: Context, log: (String) -> Unit): JSONObject? {
        val bundles = JSONObject(context.assets.open(MANIFEST_ASSET).bufferedReader().use { it.readText() }).getJSONObject("bundles")
        val result = JSONObject()
        for (name in bundles.keys()) {
            val bundle = bundles.getJSONObject(name)
            val files = bundle.getJSONArray("files").let { array ->
                (0 until array.length()).map { array.getJSONObject(it).run { BundledFile(getString("path"), getLong("size"), getString("sha256")) } }
            }
            result.put(name, install(context, name, bundle.getString("version"), files, log))
        }
        return result
    }

    private fun install(context: Context, name: String, version: String, files: List<BundledFile>, log: (String) -> Unit): JSONObject {
        val target = File(context.filesDir, "models/$name")
        val marker = File(target, VERSION_MARKER)
        if (marker.isFile && marker.readText() == version && files.all { File(target, it.path).length() == it.size }) {
            log("model $name: bundled version already installed")
            return JSONObject().put("copied", false)
        }
        val totalBytes = files.sumOf(BundledFile::size)
        log("model $name: copying ${files.size} files, ${totalBytes / BYTES_PER_MB} MB")
        val startedAt = SystemClock.elapsedRealtime()
        val partial = File(context.filesDir, "models/$name.partial").apply { deleteRecursively() }
        var copiedBytes = 0L
        var nextProgress = PROGRESS_EVERY_BYTES
        for (file in files) {
            copiedBytes += copyChecked(context.assets, file, File(partial, file.path))
            if (copiedBytes >= nextProgress) {
                log("model $name: ${copiedBytes / BYTES_PER_MB} of ${totalBytes / BYTES_PER_MB} MB")
                nextProgress = copiedBytes + PROGRESS_EVERY_BYTES
            }
        }
        File(partial, VERSION_MARKER).writeText(version)
        target.deleteRecursively()
        if (!partial.renameTo(target)) throw IOException("could not move $name into place")
        val copyMs = SystemClock.elapsedRealtime() - startedAt
        log("model $name: installed in $copyMs ms")
        return JSONObject().put("copied", true).put("files", files.size).put("bytes", totalBytes).put("copyMs", copyMs)
    }

    /** Copies one file out of the APK and fails unless its size and SHA-256 match the manifest. */
    private fun copyChecked(assets: AssetManager, file: BundledFile, destination: File): Long {
        destination.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        assets.open("$FILES_ASSET_DIR/${file.sha256}.bin", AssetManager.ACCESS_STREAMING).use { input ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                    bytes += read
                }
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (bytes != file.size || sha != file.sha256) throw IOException("bundled model file ${file.path} is damaged")
        return bytes
    }
}
