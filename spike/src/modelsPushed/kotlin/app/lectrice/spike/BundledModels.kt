package app.lectrice.spike

import android.content.Context
import org.json.JSONObject

/** The normal build ships no models: they come from push-model.sh (or, later, the download flow). */
object BundledModels {
    @Suppress("UNUSED_PARAMETER")
    fun install(context: Context, log: (String) -> Unit): JSONObject? = null
}
