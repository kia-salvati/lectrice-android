package app.lectrice

import android.app.usage.StorageStatsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.os.storage.StorageManager
import android.provider.Settings
import app.lectrice.core.model.Sizes
import app.lectrice.feature.settings.SettingsPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.lectrice_ffi.coreVersion

/** Numbers and system pages for Settings (file 11 §3.1, file 20 §3.5). */
class AndroidSettingsPlatform(private val context: Context, private val container: AppContainer) : SettingsPlatform {
    override suspend fun modelSummary(): String = withContext(Dispatchers.IO) {
        val spec = container.engine.activeModel() ?: return@withContext "Not installed"
        "${spec.info.name} · ${Sizes.format(container.models.sizeBytes(spec))} · in use"
    }

    override suspend fun storageSummary(): String = withContext(Dispatchers.IO) {
        val stats = context.getSystemService(StorageStatsManager::class.java)
            .queryStatsForPackage(StorageManager.UUID_DEFAULT, context.packageName, Process.myUserHandle())
        val storage = context.getSystemService(StorageManager::class.java)
        val free = storage.getAllocatableBytes(storage.getUuidForPath(context.filesDir))
        "${Sizes.format(stats.appBytes + stats.dataBytes)} used · ${Sizes.format(free)} free"
    }

    override fun versionLine(): String = "Version ${BuildConfig.VERSION_NAME} · core ${runCatching { coreVersion() }.getOrDefault("?")}"

    override fun openBatterySettings() {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun openSourceCode() {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun copyToClipboard(text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Lectrice version", text))
    }

    private companion object {
        const val SOURCE_URL = "https://github.com/kia-salvati/lectrice-android"
    }
}
