package app.lectrice.core.work

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import app.lectrice.core.data.Log
import app.lectrice.core.data.RustCore
import app.lectrice.core.model.PrepareController
import app.lectrice.core.model.PrepareRequest
import app.lectrice.core.model.PrepareState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Prepare jobs (file 14): the requests are kept in a small preferences file (they outlive the process, the core's job
 * doesn't), run by [BackgroundWorkService] only while the phone charges and the battery isn't low, and lifted after
 * 14 days or when the user turns them off.
 */
class PrepareCenter(private val context: Context, private val core: RustCore, private val scope: CoroutineScope) : PrepareController {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val requests = MutableStateFlow(load())
    private val mutableStates = MutableStateFlow<Map<String, PrepareState>>(requests.value.mapValues { PrepareState.WaitingForCharger })
    override val states: StateFlow<Map<String, PrepareState>> = mutableStates.asStateFlow()

    /** Set by [prepare]; the work service starts when there's something to run. */
    var onWork: () -> Unit = {}

    override fun prepare(request: PrepareRequest) {
        requests.update { it + (request.documentId to request) }
        save()
        mutableStates.update { it + (request.documentId to PrepareState.WaitingForCharger) }
        onWork()
    }

    override fun cancel(documentId: String) {
        requests.update { it - documentId }
        save()
        mutableStates.update { it - documentId }
        scope.launch { runCatching { core.call("cancel_prepare") { it.cancelPrepare(documentId) } } }
    }

    fun hasWork(): Boolean = requests.value.keys.any { mutableStates.value[it] !is PrepareState.Ready } && canRun()

    fun hasRequests(): Boolean = requests.value.isNotEmpty()

    /** Charging and not low (file 14 §3). */
    fun canRun(): Boolean {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * PERCENT / battery.getIntExtra(BatteryManager.EXTRA_SCALE, PERCENT).coerceAtLeast(1)
        return plugged && level > LOW_PERCENT
    }

    /** One step of the service loop: start, watch or stop each request. */
    suspend fun tick(nowMs: Long) {
        val runnable = canRun()
        for ((documentId, request) in requests.value) {
            if (nowMs - request.createdAtEpochMs > PIN_DAYS_MS) {
                cancel(documentId)
                continue
            }
            val status = runCatching { core.call("prepare_status") { it.prepareStatus(documentId) } }.getOrNull()
            val state = when {
                status != null && status.total > 0u && status.ready >= status.total -> PrepareState.Ready(request.upToLabel)
                !runnable -> {
                    if (status?.running == true) runCatching { core.call("stop_prepare") { it.stopPrepare(documentId) } }
                    PrepareState.WaitingForCharger
                }
                status?.running == true -> PrepareState.Running((status.ready.toLong() * PERCENT / status.total.toLong().coerceAtLeast(1)).toInt())
                else -> {
                    val started = runCatching {
                        core.call("start_prepare") { it.startPrepare(documentId, request.voiceId, request.readSkipped, request.fromSentence.toUInt(), request.toSentence.toUInt()) }
                    }.onFailure { Log.w(SCOPE, "prepare couldn't start", it) }.getOrNull()
                    if (started != null) PrepareState.Running(0) else PrepareState.WaitingForCharger
                }
            }
            mutableStates.update { it + (documentId to state) }
        }
    }

    private fun load(): Map<String, PrepareRequest> = prefs.all.mapNotNull { (key, value) ->
        runCatching {
            val json = JSONObject(value as String)
            key to PrepareRequest(
                documentId = key,
                voiceId = json.getString("voice"),
                readSkipped = json.getBoolean("readSkipped"),
                fromSentence = json.getInt("from"),
                toSentence = json.getInt("to"),
                upToLabel = json.getString("upTo"),
                createdAtEpochMs = json.getLong("created"),
            )
        }.getOrNull()
    }.toMap()

    private fun save() {
        val editor = prefs.edit().clear()
        requests.value.forEach { (id, request) ->
            val json = JSONObject()
                .put("voice", request.voiceId)
                .put("readSkipped", request.readSkipped)
                .put("from", request.fromSentence)
                .put("to", request.toSentence)
                .put("upTo", request.upToLabel)
                .put("created", request.createdAtEpochMs)
            editor.putString(id, json.toString())
        }
        editor.apply()
    }

    private companion object {
        const val FILE = "prepare"
        const val SCOPE = "Prepare"
        const val PERCENT = 100
        const val LOW_PERCENT = 15
        const val PIN_DAYS_MS = 14L * 24 * 60 * 60 * 1000
    }
}
