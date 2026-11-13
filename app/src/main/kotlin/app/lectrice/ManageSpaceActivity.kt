package app.lectrice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lectrice.core.designsystem.LectriceTheme

/** Android's Settings → Apps → Lectrice → Storage → "Clear storage" opens our Storage screen (file 20 §3.3). */
class ManageSpaceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as LectriceApplication).container
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle()
            LectriceTheme(settings.theme) {
                StorageRoute(Screen.Storage(), navigator = null, container = container, closeInsteadOfBack = true, onClose = ::finish)
            }
        }
    }
}
