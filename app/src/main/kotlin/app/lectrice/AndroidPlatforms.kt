package app.lectrice

import android.app.ActivityManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.os.storage.StorageManager
import androidx.core.content.FileProvider
import app.lectrice.core.model.AudioExport
import app.lectrice.core.model.VoiceSamplePlayer
import app.lectrice.feature.reader.ExportPlatform
import app.lectrice.feature.settings.StoragePlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Opening, sharing and copying export files (file 10 §3.9, file 20 §3.5). */
class AndroidExportFiles(private val context: Context) {
    fun uri(export: AudioExport): Uri? = export.sharedUri?.let(Uri::parse)
        ?: export.filePath?.let { FileProvider.getUriForFile(context, "${context.packageName}.exports", File(it)) }

    fun share(export: AudioExport) {
        val uri = uri(export) ?: return
        val send = Intent(Intent.ACTION_SEND).setType(MIME).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun open(export: AudioExport) {
        val uri = uri(export) ?: return
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, MIME).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    suspend fun copyTo(export: AudioExport, target: String) = withContext(Dispatchers.IO) {
        val source = uri(export) ?: return@withContext
        val resolver = context.contentResolver
        resolver.openInputStream(source)?.use { input -> resolver.openOutputStream(Uri.parse(target))?.use { input.copyTo(it) } }
    }

    fun openPhoneStorage(requestedBytes: Long?) {
        val intent = Intent(StorageManager.ACTION_MANAGE_STORAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        requestedBytes?.let { intent.putExtra(StorageManager.EXTRA_REQUESTED_BYTES, it) }
        context.startActivity(intent)
    }

    private companion object {
        const val MIME = "audio/mp4"
    }
}

class AndroidStoragePlatform(private val context: Context, private val files: AndroidExportFiles) : StoragePlatform {
    private fun stats() = context.getSystemService(StorageStatsManager::class.java).queryStatsForPackage(StorageManager.UUID_DEFAULT, context.packageName, Process.myUserHandle())

    override suspend fun totalBytes(): Long = withContext(Dispatchers.IO) { stats().let { it.appBytes + it.dataBytes } }

    override suspend fun freeBytes(): Long = withContext(Dispatchers.IO) {
        val storage = context.getSystemService(StorageManager::class.java)
        storage.getAllocatableBytes(storage.getUuidForPath(context.filesDir))
    }

    override suspend fun appBytes(): Long = withContext(Dispatchers.IO) { stats().appBytes }

    override fun openPhoneStorage(requestedBytes: Long?) = files.openPhoneStorage(requestedBytes)

    override fun share(export: AudioExport) = files.share(export)

    override fun open(export: AudioExport) = files.open(export)

    /** Exports saved to Audiobooks stay (they are the user's files in shared storage). */
    override fun deleteAllData() {
        context.getSystemService(ActivityManager::class.java).clearApplicationUserData()
    }
}

class AndroidExportPlatform(
    private val files: AndroidExportFiles,
    private val samples: VoiceSamplePlayer,
    private val startWork: () -> Unit,
    private val openStorage: (Long?) -> Unit,
) : ExportPlatform {
    override fun share(export: AudioExport) = files.share(export)

    override suspend fun copyTo(export: AudioExport, uri: String) {
        files.copyTo(export, uri)
    }

    override fun openStorage(requestedBytes: Long?) = openStorage.invoke(requestedBytes)

    override fun playSample(voiceId: String) = samples.play(voiceId)

    override val sampleState get() = samples.state

    override fun runExports() = startWork()
}
