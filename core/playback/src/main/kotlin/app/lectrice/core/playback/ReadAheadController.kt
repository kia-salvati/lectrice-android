package app.lectrice.core.playback

import android.os.SystemClock
import app.lectrice.core.data.Log
import app.lectrice.core.data.RustCore
import app.lectrice.core.model.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Applies [ReadAheadGovernor]'s mode to the core (file 08 §3.3): Full = the setting's minutes, Reduced = 2, Off =
 * `stop_read_ahead` once and `plan_ahead = false` until the mode leaves Off. Every input change re-decides at once; a
 * pause re-decides again after 60 s, a cool-down after 2 minutes.
 */
class ReadAheadController(
    private val core: RustCore,
    private val settings: SettingsStore,
    private val params: SpeechParams,
    private val scope: CoroutineScope,
) {
    private var documentId: String? = null
    private var playing = false
    private var pausedSinceMs: Long? = null
    private var sleepTimerFired = false
    private var lowMemory = false
    private var heat = Heat.None
    private var coolSinceMs: Long? = null
    private var batteryWasLow = false
    private var conditions: Conditions? = null
    private var mode: AheadMode? = null
    private var appliedMinutes: UInt? = null
    private var recheck: Job? = null

    @Synchronized
    fun onBook(documentId: String?) {
        if (documentId != this.documentId) this.documentId?.let { old -> stopFor(old) }
        this.documentId = documentId
        sleepTimerFired = false
        update()
    }

    @Synchronized
    fun onPlaying(playing: Boolean) {
        if (playing) {
            sleepTimerFired = false
            lowMemory = false
        }
        if (this.playing && !playing) pausedSinceMs = SystemClock.elapsedRealtime()
        if (playing) pausedSinceMs = null
        this.playing = playing
        update()
    }

    @Synchronized
    fun onSleepTimerFired() {
        sleepTimerFired = true
        update()
    }

    @Synchronized
    fun onLowMemory() {
        if (playing) return
        lowMemory = true
        update()
    }

    @Synchronized
    fun onConditions(next: Conditions) {
        val now = SystemClock.elapsedRealtime()
        coolSinceMs = if (next.thermalStatus >= THERMAL_SEVERE) null else coolSinceMs ?: now
        heat = ReadAheadGovernor.nextHeat(heat, next.thermalStatus, coolSinceMs, now)
        batteryWasLow = next.batteryLow(batteryWasLow)
        conditions = next
        update()
    }

    /** The setting changed: Full mode applies the new minutes from the next sentence. */
    @Synchronized
    fun onSettingChanged() {
        appliedMinutes = null
        update()
    }

    private fun update() {
        val now = SystemClock.elapsedRealtime()
        val decision = ReadAheadGovernor.decide(
            GovernorInputs(
                hasBook = documentId != null,
                playing = playing,
                pausedSinceMs = pausedSinceMs,
                sleepTimerFired = sleepTimerFired,
                lowMemoryWhileStopped = lowMemory,
                heat = heat,
                batterySaver = conditions?.batterySaver == true,
                batteryLowNotCharging = batteryWasLow,
                nowMs = now,
            ),
        )
        if (decision.mode != mode) {
            Log.i(SCOPE, "read_ahead_mode from=$mode to=${decision.mode} reason=${decision.reason}")
            mode = decision.mode
            if (decision.mode == AheadMode.Off) documentId?.let(::stopFor)
        }
        params.planAhead = decision.mode != AheadMode.Off
        val minutes = when (decision.mode) {
            AheadMode.Full -> settings.settings.value.readAhead.minutes.toUInt()
            AheadMode.Reduced -> ReadAheadGovernor.REDUCED_MINUTES
            AheadMode.Off -> null
        }
        if (minutes != null && minutes != appliedMinutes) {
            appliedMinutes = minutes
            scope.launch {
                // Without a loaded engine the core can't take it yet: forget it, so the next update tries again.
                runCatching { core.call("set_read_ahead_minutes") { it.setReadAheadMinutes(minutes) } }
                    .onFailure { synchronized(this@ReadAheadController) { if (appliedMinutes == minutes) appliedMinutes = null } }
            }
        }
        scheduleRecheck(now)
    }

    /** The pause rule and the cool-down are timed: look again when they could flip. */
    private fun scheduleRecheck(now: Long) {
        recheck?.cancel()
        val waits = listOfNotNull(
            pausedSinceMs?.let { ReadAheadGovernor.PAUSE_STOP_MS - (now - it) }?.takeIf { it > 0 },
            coolSinceMs?.takeIf { heat != Heat.None }?.let { ReadAheadGovernor.COOL_DOWN_MS - (now - it) }?.takeIf { it > 0 },
        )
        val wait = waits.minOrNull() ?: return
        recheck = scope.launch {
            delay(wait + 1)
            synchronized(this@ReadAheadController) {
                conditions?.let { c ->
                    heat = ReadAheadGovernor.nextHeat(heat, c.thermalStatus, coolSinceMs, SystemClock.elapsedRealtime())
                }
                update()
            }
        }
    }

    private fun stopFor(documentId: String) {
        scope.launch { runCatching { core.call("stop_read_ahead") { it.stopReadAhead(documentId) } } }
    }

    private companion object {
        const val SCOPE = "ReadAhead"
        const val THERMAL_SEVERE = 3
    }
}
