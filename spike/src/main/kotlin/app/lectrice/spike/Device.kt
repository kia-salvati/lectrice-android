package app.lectrice.spike

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val BYTES_PER_MB = 1024 * 1024
private const val HEADROOM_FORECAST_SECONDS = 10
private const val TENTHS_PER_DEGREE = 10.0

/** What the result file says about the phone: no user data. */
fun deviceInfo(context: Context): JSONObject {
    val memory = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
    return JSONObject()
        .put("manufacturer", Build.MANUFACTURER)
        .put("model", Build.MODEL)
        .put("board", Build.BOARD)
        .put("hardware", Build.HARDWARE)
        .put("socManufacturer", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else "")
        .put("socModel", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else "")
        .put("ramMb", memory.totalMem / BYTES_PER_MB)
        .put("android", Build.VERSION.RELEASE)
        .put("api", Build.VERSION.SDK_INT)
        .put("pageSize", Os.sysconf(OsConstants._SC_PAGESIZE))
        .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
        .put("cpus", Runtime.getRuntime().availableProcessors())
}

/** `VmRSS` and `VmHWM` (peak) of this process, in KB. */
fun processMemoryKb(): JSONObject {
    val result = JSONObject()
    File("/proc/self/status").readLines()
        .filter { it.startsWith("VmRSS:") || it.startsWith("VmHWM:") }
        .forEach { line ->
            val (key, value) = line.split(":", limit = 2)
            result.put(key, value.trim().removeSuffix("kB").trim().toLong())
        }
    return result
}

/** Heat and battery, for the soak run. */
class PowerProbe(private val context: Context) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val battery = context.getSystemService(BatteryManager::class.java)

    fun isCharging(): Boolean = battery.isCharging

    /** µAh left in the battery; the main number for energy use. */
    fun chargeCounterUah(): Long = battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)

    fun sample(): JSONObject {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val temperature = status?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        return JSONObject()
            .put("thermalStatus", power.currentThermalStatus)
            .put("thermalHeadroom", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) power.getThermalHeadroom(HEADROOM_FORECAST_SECONDS).toDouble() else JSONObject.NULL)
            .put("batteryTempC", if (temperature == Int.MIN_VALUE) JSONObject.NULL else temperature / TENTHS_PER_DEGREE)
            .put("batteryPct", if (level >= 0 && scale > 0) level * 100.0 / scale else JSONObject.NULL)
            .put("charging", battery.isCharging)
            .put("chargeCounterUah", chargeCounterUah())
            // Samsung may report mA instead of µA (unverified).
            .put("currentNow", battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))
    }
}
