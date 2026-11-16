package app.opengrok

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        val store = (application as OgApp).store
        AgentMode.init(this)
        PhoneModel.init(this)
        GlassStyle.init(this)
        // Restore the background keep-alive service whenever the app is opened.
        if (KeepAlive.isEnabled(this)) KeepAlive.start(this)
        setContent { App(store) }
        requestHighRefreshRate()
    }

    /** Ask the system for the panel's highest refresh rate at the current resolution (120/144 Hz where available). */
    private fun requestHighRefreshRate() {
        try {
            @Suppress("DEPRECATION")
            val d = windowManager.defaultDisplay ?: return
            val cur = d.mode
            val best = d.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .maxByOrNull { it.refreshRate } ?: return
            val lp = window.attributes
            lp.preferredDisplayModeId = best.modeId
            window.attributes = lp
        } catch (_: Throwable) {
        }
    }
}
