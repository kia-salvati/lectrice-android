package app.lectrice.feature.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * Calls [onChange] when the screen goes on or off and when the user unlocks ([unlocked] = true), so the Listen view's
 * lock marks follow the keyguard instead of a value read once at resume (B3). Returns the unregister call.
 */
internal fun watchKeyguard(context: Context, onChange: (unlocked: Boolean) -> Unit): () -> Unit {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onChange(intent.action == Intent.ACTION_USER_PRESENT)
    }
    val filter = IntentFilter().apply {
        addAction(Intent.ACTION_SCREEN_ON)
        addAction(Intent.ACTION_SCREEN_OFF)
        addAction(Intent.ACTION_USER_PRESENT)
    }
    ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    return { context.unregisterReceiver(receiver) }
}
