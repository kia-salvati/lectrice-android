package app.lectrice.core.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Battery saver, battery level/charging and the thermal status, as one observable value (file 08 §3.3 rules 5–7). */
data class Conditions(val batterySaver: Boolean, val batteryPercent: Int, val charging: Boolean, val thermalStatus: Int) {
    /** ≤ 15 % and not charging; once low, it stays low until > 20 % or charging. */
    fun batteryLow(wasLow: Boolean): Boolean = !charging && (batteryPercent <= LOW_PERCENT || (wasLow && batteryPercent <= OKAY_PERCENT))

    private companion object {
        const val LOW_PERCENT = 15
        const val OKAY_PERCENT = 20
    }
}

class DeviceConditions(private val context: Context) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val state = MutableStateFlow(read(null))
    val conditions: StateFlow<Conditions> = state.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            state.value = read(if (intent.action == Intent.ACTION_BATTERY_CHANGED) intent else null)
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        context.registerReceiver(receiver, filter)
        power.addThermalStatusListener(context.mainExecutor) { state.value = state.value.copy(thermalStatus = it) }
    }

    private fun read(battery: Intent?): Conditions {
        val sticky = battery ?: context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val plugged = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val percent = if (level >= 0 && scale > 0) level * FULL / scale else FULL
        return Conditions(power.isPowerSaveMode, percent, plugged, power.currentThermalStatus)
    }

    private companion object {
        const val FULL = 100
    }
}
