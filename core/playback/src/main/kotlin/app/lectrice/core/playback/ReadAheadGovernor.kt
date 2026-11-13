package app.lectrice.core.playback

/** File 08 §3.3. */
enum class AheadMode { Full, Reduced, Off }

/** The hottest level of the current heat episode; it lasts until the phone has been ≤ MODERATE for 2 minutes. */
enum class Heat { None, Severe, Critical }

/** Everything the governor looks at. Times are in ms on one clock. */
data class GovernorInputs(
    val hasBook: Boolean,
    val playing: Boolean,
    /** When playback last paused; null while playing. */
    val pausedSinceMs: Long?,
    val sleepTimerFired: Boolean,
    val lowMemoryWhileStopped: Boolean,
    val heat: Heat,
    val batterySaver: Boolean,
    val batteryLowNotCharging: Boolean,
    val nowMs: Long,
)

/**
 * The only code that decides how far ahead speech is made: Full (the setting), Reduced (2 listening-minutes) or Off.
 * Pure; [ReadAheadController] feeds it and applies its answer.
 */
object ReadAheadGovernor {
    const val PAUSE_STOP_MS = 60_000L
    const val COOL_DOWN_MS = 120_000L
    const val REDUCED_MINUTES = 2u

    data class Decision(val mode: AheadMode, val reason: String)

    fun decide(input: GovernorInputs): Decision {
        val pausedLong = !input.playing && input.pausedSinceMs != null && input.nowMs - input.pausedSinceMs >= PAUSE_STOP_MS
        val batteryLimited = input.batterySaver || input.batteryLowNotCharging
        return when {
            !input.hasBook -> Decision(AheadMode.Off, "no_book")
            input.sleepTimerFired -> Decision(AheadMode.Off, "sleep_timer")
            input.lowMemoryWhileStopped -> Decision(AheadMode.Off, "low_memory")
            input.heat == Heat.Critical -> Decision(AheadMode.Off, "thermal_critical")
            pausedLong -> Decision(AheadMode.Off, "paused_60s")
            batteryLimited && !input.playing -> Decision(AheadMode.Off, "battery")
            input.heat == Heat.Severe -> Decision(AheadMode.Reduced, "thermal_severe")
            batteryLimited -> Decision(AheadMode.Reduced, "battery")
            else -> Decision(AheadMode.Full, "normal")
        }
    }

    /**
     * File 24 §3.10: the computer helper keeps filling its 30 minutes in Full and in Reduced (battery saver, heat: the
     * radio costs a fraction of synthesis, and sparing the phone is the point), and stops in Off.
     */
    fun helperRuns(mode: AheadMode): Boolean = mode != AheadMode.Off

    /**
     * The next heat episode level from the system's thermal status (`PowerManager.THERMAL_STATUS_*`: 0 none … 3 severe,
     * 4 critical …) and how long it has been cool.
     */
    fun nextHeat(current: Heat, thermalStatus: Int, coolSinceMs: Long?, nowMs: Long): Heat = when {
        thermalStatus >= THERMAL_CRITICAL -> Heat.Critical
        thermalStatus >= THERMAL_SEVERE -> if (current == Heat.Critical) Heat.Critical else Heat.Severe
        coolSinceMs != null && nowMs - coolSinceMs >= COOL_DOWN_MS -> Heat.None
        else -> current
    }

    private const val THERMAL_SEVERE = 3
    private const val THERMAL_CRITICAL = 4
}
