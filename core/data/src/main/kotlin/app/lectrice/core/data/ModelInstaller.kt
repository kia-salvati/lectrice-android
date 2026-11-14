package app.lectrice.core.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.storage.StorageManager
import app.lectrice.core.model.ErrorName
import app.lectrice.core.model.LectriceError
import app.lectrice.core.model.ModelInstallState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import uniffi.lectrice_ffi.unpackModelArchive
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Live numbers for S3 (file 01 §3.3). */
data class DownloadProgress(
    val spec: ModelSpec,
    val doneBytes: Long,
    val totalBytes: Long,
    /** Bytes per second over the last 5 s; null in the first 3 s. */
    val bytesPerSecond: Long?,
    val state: ModelInstallState,
    /** The user chose "Wait for Wi-Fi". */
    val wifiOnly: Boolean,
)

/** What a download needs from the phone: space and the network. */
interface InstallerDevice {
    fun freeBytes(): Long

    fun isMetered(): Boolean

    /** A validated network, and an unmetered one when [wifiOnly]. */
    fun hasNetwork(wifiOnly: Boolean): Boolean
}

class AndroidInstallerDevice(private val context: Context) : InstallerDevice {
    override fun freeBytes(): Long {
        val storage = context.getSystemService(StorageManager::class.java)
        return storage.getAllocatableBytes(storage.getUuidForPath(context.filesDir))
    }

    override fun isMetered(): Boolean = context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered

    override fun hasNetwork(wifiOnly: Boolean): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false
        return !wifiOnly || capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}

/**
 * Downloads a model archive with resumable `Range` requests, checks its SHA-256 against the catalog, and unpacks it
 * through the core (one `.tar.bz2` per model, released on GitHub). Runs inside the foreground work service; one
 * download at a time. [connect] and [unpack] are the network and the core's unpacker (replaced in tests).
 */
class ModelInstaller(
    private val models: ModelStore,
    private val device: InstallerDevice,
    private val connect: (String) -> HttpURLConnection = { url -> URL(url).openConnection() as HttpURLConnection },
    private val unpack: (archive: File, spec: ModelSpec, target: File) -> Unit = { archive, spec, target ->
        RustCore.function("unpack_model_archive") { unpackModelArchive(archive.path, spec.archiveRoot, spec.keep, target.path) }
    },
) {
    private val progressState = MutableStateFlow<DownloadProgress?>(null)
    val progress: StateFlow<DownloadProgress?> = progressState.asStateFlow()

    private val partsDir: File get() = File(models.root, ".download")

    fun partFile(spec: ModelSpec): File = File(partsDir, "${spec.info.id}.tar.bz2.part")

    fun downloadedBytes(spec: ModelSpec): Long = partFile(spec).length()

    /** Free space needed before starting or resuming: what's left to download + the unpacked size + 200 MB to work with. */
    fun spaceNeeded(spec: ModelSpec): Long = spec.info.downloadBytes - downloadedBytes(spec) + spec.info.installedBytes + WORK_MARGIN_BYTES

    fun freeBytes(): Long = device.freeBytes()

    fun isMetered(): Boolean = device.isMetered()

    private fun hasNetwork(wifiOnly: Boolean): Boolean = device.hasNetwork(wifiOnly)

    fun publish(progress: DownloadProgress?) {
        progressState.value = progress
    }

    /**
     * Downloads (resuming), checks and installs. Suspends while there's no (allowed) network. Throws [LectriceError]:
     * `StorageFull`, `ModelChecksumMismatch` (after one retry), `ModelDownloadFailed`. Cancelling keeps the part file
     * (pause); [discard] deletes it.
     */
    suspend fun install(spec: ModelSpec, wifiOnly: Boolean) {
        partsDir.mkdirs()
        var mismatches = 0
        var transientFailures = 0
        var backoff = FIRST_BACKOFF_MS
        while (true) {
            currentCoroutineContext().ensureActive()
            if (freeBytes() < spaceNeeded(spec)) throw LectriceError(ErrorName.StorageFull, bytes = spaceNeeded(spec))
            if (!hasNetwork(wifiOnly)) {
                report(spec, wifiOnly, waitingForNetwork = true)
                delay(NETWORK_POLL_MS)
                continue
            }
            try {
                download(spec, wifiOnly)
            } catch (error: CancellationException) {
                throw error
            } catch (error: HttpStatusException) {
                if (error.status in SERVER_ERRORS && ++transientFailures <= MAX_TRANSIENT_FAILURES) {
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                    continue
                }
                throw LectriceError(ErrorName.ModelDownloadFailed, error)
            } catch (error: TooLargeException) {
                partFile(spec).delete()
                Log.w(SCOPE, "download larger than the archive, dropped id=${spec.info.id}")
                throw LectriceError(ErrorName.ModelChecksumMismatch, error)
            } catch (error: IOException) {
                if (CoreLibraryRepository.isOutOfSpace(error)) throw LectriceError(ErrorName.StorageFull, error, spaceNeeded(spec))
                Log.w(SCOPE, "download interrupted, retrying", error)
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                continue
            }
            if (sha256(partFile(spec)) != spec.archiveSha256) {
                partFile(spec).delete()
                if (++mismatches >= MAX_MISMATCHES) throw LectriceError(ErrorName.ModelChecksumMismatch)
                Log.w(SCOPE, "archive hash mismatch, retrying id=${spec.info.id}")
                continue
            }
            installArchive(spec, partFile(spec))
            partFile(spec).delete()
            return
        }
    }

    /**
     * "Import from a file…": a model archive the user picked, identified by its SHA-256. Returns the model it is.
     * A file larger than the largest known archive can't be one, so the copy stops there.
     */
    fun importArchive(input: () -> InputStream): ModelSpec {
        partsDir.mkdirs()
        val temp = File(partsDir, "import.tar.bz2")
        try {
            input().use { source -> temp.outputStream().use { CappedCopy.copy(source, it, ModelSpec.entries.maxOf { spec -> spec.info.downloadBytes }) } }
            val hash = sha256(temp)
            val spec = ModelSpec.entries.firstOrNull { it.archiveSha256 == hash } ?: throw LectriceError(ErrorName.ModelChecksumMismatch)
            if (freeBytes() < spec.info.installedBytes + WORK_MARGIN_BYTES) throw LectriceError(ErrorName.StorageFull, bytes = spec.info.installedBytes + WORK_MARGIN_BYTES)
            installArchive(spec, temp)
            return spec
        } catch (error: TooLargeException) {
            throw LectriceError(ErrorName.ModelChecksumMismatch, error)
        } catch (error: IOException) {
            throw LectriceError(if (CoreLibraryRepository.isOutOfSpace(error)) ErrorName.StorageFull else ErrorName.FileUnreadable, error)
        } finally {
            temp.delete()
        }
    }

    fun discard(spec: ModelSpec) {
        partFile(spec).delete()
    }

    private suspend fun download(spec: ModelSpec, wifiOnly: Boolean) {
        val part = partFile(spec)
        var done = part.length()
        if (done >= spec.info.downloadBytes) return
        val connection = connect(spec.url).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            if (done > 0) setRequestProperty("Range", "bytes=$done-")
        }
        try {
            val status = connection.responseCode
            val append = status == HttpURLConnection.HTTP_PARTIAL
            if (status != HttpURLConnection.HTTP_OK && !append) throw HttpStatusException(status)
            if (!append) done = 0
            Log.i(SCOPE, "download id=${spec.info.id} from=$done status=$status")
            val speed = SpeedMeter()
            connection.inputStream.use { input ->
                java.io.FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var lastReport = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        // The archive's size is known: a server that sends more is wrong or hostile. Stop before the phone fills up.
                        if (done + read > spec.info.downloadBytes) throw TooLargeException(spec.info.downloadBytes)
                        output.write(buffer, 0, read)
                        done += read
                        speed.add(read.toLong())
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= REPORT_INTERVAL_MS) {
                            lastReport = now
                            progressState.value = DownloadProgress(spec, done, spec.info.downloadBytes, speed.bytesPerSecond(), downloadingState(done, spec), wifiOnly)
                        }
                        if (wifiOnly && !hasNetwork(wifiOnly = true)) throw IOException("left Wi-Fi")
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun installArchive(spec: ModelSpec, archive: File) {
        progressState.update { it?.copy(state = ModelInstallState.Installing) }
        val target = models.dir(spec)
        val staging = File(models.root, "${spec.info.id}.unpacking")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            unpack(archive, spec, staging)
        } catch (error: LectriceError) {
            staging.deleteRecursively()
            throw error
        }
        target.deleteRecursively()
        if (!staging.renameTo(target)) throw LectriceError(ErrorName.StorageFailed)
        models.markInstalled(spec)
        Log.i(SCOPE, "model installed id=${spec.info.id}")
    }

    private fun report(spec: ModelSpec, wifiOnly: Boolean, waitingForNetwork: Boolean) {
        val done = downloadedBytes(spec)
        progressState.value = DownloadProgress(
            spec, done, spec.info.downloadBytes, null,
            ModelInstallState.Downloading(done, spec.info.downloadBytes, paused = false, waitingForNetwork = waitingForNetwork, waitingForWifi = waitingForNetwork && wifiOnly),
            wifiOnly,
        )
    }

    private fun downloadingState(done: Long, spec: ModelSpec) =
        ModelInstallState.Downloading(done, spec.info.downloadBytes, paused = false, waitingForNetwork = false, waitingForWifi = false)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Bytes per second over the last 5 s, after 3 s of data (file 01 §3.4). */
    private class SpeedMeter {
        private val samples = ArrayDeque<Pair<Long, Long>>()
        private val started = System.currentTimeMillis()

        fun add(bytes: Long) {
            val now = System.currentTimeMillis()
            samples.addLast(now to bytes)
            while (samples.isNotEmpty() && now - samples.first().first > WINDOW_MS) samples.removeFirst()
        }

        fun bytesPerSecond(): Long? {
            val now = System.currentTimeMillis()
            if (now - started < MIN_DATA_MS || samples.isEmpty()) return null
            val span = (now - samples.first().first).coerceAtLeast(1)
            return samples.sumOf { it.second } * MS_PER_SECOND / span
        }

        private companion object {
            const val WINDOW_MS = 5_000L
            const val MIN_DATA_MS = 3_000L
            const val MS_PER_SECOND = 1_000L
        }
    }

    private class HttpStatusException(val status: Int) : IOException("HTTP $status")

    private companion object {
        const val SCOPE = "ModelDownload"
        const val WORK_MARGIN_BYTES = 200L * 1_000_000
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val BUFFER_BYTES = 1 shl 16
        const val REPORT_INTERVAL_MS = 250L
        const val NETWORK_POLL_MS = 3_000L
        const val FIRST_BACKOFF_MS = 30_000L
        const val MAX_BACKOFF_MS = 5 * 60_000L
        const val MAX_TRANSIENT_FAILURES = 3
        const val MAX_MISMATCHES = 2
        val SERVER_ERRORS = 500..599
    }
}
