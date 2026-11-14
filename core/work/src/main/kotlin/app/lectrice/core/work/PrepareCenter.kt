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
import app.lectrice.core.model.suspendRunCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import uniffi.lectrice_ffi.PrepareStatusRecord

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

    /** Set by the app: the paired computer makes audio now (file 24 §3.13), so a range runs there without a charger. */
    var helperConnected: () -> Boolean = { false }

    /** When a range the computer stopped short of its end is sent again (only touched by [tick]). */
    private val computerRetryAt = mutableMapOf<String, Long>()

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
        scope.launch { suspendRunCatching { core.call("cancel_prepare") { it.cancelPrepare(documentId) } } }
    }

    fun hasWork(): Boolean = requests.value.keys.any { mutableStates.value[it] !is PrepareState.Ready } && canRun()

    fun hasRequests(): Boolean = requests.value.isNotEmpty()

    /** A range is being made on the computer: the helper must stay connected for it. */
    fun runsOnHelper(): Boolean = mutableStates.value.values.any { it is PrepareState.Running && it.onComputer }

    /** Charging and not low (file 14 §3), or the computer makes it. */
    fun canRun(): Boolean = helperConnected() || charging()

    private fun charging(): Boolean {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * PERCENT / battery.getIntExtra(BatteryManager.EXTRA_SCALE, PERCENT).coerceAtLeast(1)
        return plugged && level > LOW_PERCENT
    }

    /** One step of the service loop: start, watch or stop each request. */
    suspend fun tick(nowMs: Long) {
        val onComputer = helperConnected()
        val runnable = onComputer || charging()
        val looks = requests.value.values.mapNotNull { request ->
            if (nowMs - request.createdAtEpochMs > PIN_DAYS_MS) {
                cancel(request.documentId)
                return@mapNotNull null
            }
            val status = suspendRunCatching { core.call("prepare_status") { it.prepareStatus(request.documentId) } }.getOrNull()
            val onHelper = status?.running == true && suspendRunCatching { core.call("prepare_on_helper") { it.prepareOnHelper(request.documentId) } }.getOrDefault(false)
            Look(request, status, onHelper)
        }
        // The computer makes one range at a time: a second start replaces the first, so two books would take turns
        // restarting each other and neither would finish.
        var computerTaken = looks.any { it.onHelper }
        for ((request, status, onHelper) in looks) {
            val documentId = request.documentId
            // The job runs where it should: on the computer while it is there, else on the phone (charging only).
            val misplaced = status?.running == true && onHelper != onComputer
            val before = mutableStates.value[documentId]
            val state = when {
                status != null && status.total > 0u && status.ready >= status.total -> PrepareState.Ready(request.upToLabel)
                // Made on the computer, which has moved on to another book's range since (its progress is gone).
                before is PrepareState.Ready && status?.running != true -> before
                !runnable || misplaced -> {
                    if (status?.running == true) suspendRunCatching { core.call("stop_prepare") { it.stopPrepare(documentId) } }
                    PrepareState.WaitingForCharger
                }
                status?.running == true -> PrepareState.Running(percent(status.ready, status.total), onComputer)
                // The computer refused some sentences (busy, security S15): ask it again a little later, not every tick.
                status != null && onComputer && endedShort(documentId, status, nowMs) -> PrepareState.Running(percent(status.ready, status.total), onComputer = true)
                onComputer && computerTaken -> PrepareState.Running(0, onComputer = true)
                else -> {
                    val started = suspendRunCatching {
                        val (from, to) = request.fromSentence.toUInt() to request.toSentence.toUInt()
                        if (onComputer) {
                            core.call("start_prepare_on_helper") { it.startPrepareOnHelper(documentId, request.voiceId, request.readSkipped, from, to) }
                        } else {
                            core.call("start_prepare") { it.startPrepare(documentId, request.voiceId, request.readSkipped, from, to) }
                        }
                    }.onFailure { Log.w(SCOPE, "prepare couldn't start", it) }.getOrNull()
                    when {
                        started == null -> PrepareState.WaitingForCharger
                        started.total > 0u && started.ready >= started.total -> PrepareState.Ready(request.upToLabel)
                        else -> PrepareState.Running(percent(started.ready, started.total), onComputer).also { if (onComputer) computerTaken = true }
                    }
                }
            }
            mutableStates.update { it + (documentId to state) }
        }
    }

    /** The document's range on the computer stopped before its end; true while the retry pause after that runs. */
    private fun endedShort(documentId: String, status: PrepareStatusRecord, nowMs: Long): Boolean {
        if (status.running || status.total == 0u || status.ready >= status.total) return false
        val retryAt = computerRetryAt.getOrPut(documentId) { nowMs + COMPUTER_RETRY_MS }
        if (nowMs < retryAt) return true
        computerRetryAt.remove(documentId)
        return false
    }

    private fun percent(ready: UInt, total: UInt): Int = (ready.toLong() * PERCENT / total.toLong().coerceAtLeast(1)).toInt()

    /** One request as the core sees it now; [onHelper]: its range is being made on the computer. */
    private data class Look(val request: PrepareRequest, val status: PrepareStatusRecord?, val onHelper: Boolean)

    private fun load(): Map<String, PrepareRequest> = prefs.all.mapNotNull { (key, value) ->
        suspendRunCatching {
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
        const val COMPUTER_RETRY_MS = 30_000L
    }
}
