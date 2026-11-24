package app.opengrok

import android.content.Intent
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
        Policy.init(this)
        UsageLedger.init(this)
        Vault.init(this)
        AgentMode.init(this)
        PhoneModel.init(this)
        GlassStyle.init(this)
        // Restore the background keep-alive service whenever the app is opened.
        if (KeepAlive.isEnabled(this)) KeepAlive.start(this)
        if (WebhookServer.isEnabled(this) && !WebhookServer.running.value) WebhookServer.start(this, store)
        handleOpenChat(intent)
        setContent { App(store) }
        requestHighRefreshRate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenChat(intent)
    }

    override fun onResume() {
        super.onResume()
        val store = (application as OgApp).store
        store.setAppVisible(true)
        store.markAppOpened()
        runCatching { GlassStyle.refreshSystemState(this) }
    }

    override fun onPause() {
        (application as OgApp).store.setAppVisible(false)
        super.onPause()
    }

    /** v0.7.0: a tapped notification carries the chat to open; the UI navigates and clears openRequest. */
    private fun handleOpenChat(intent: Intent?) {
        val id = intent?.getStringExtra(Notify.EXTRA_OPEN_CHAT)?.trim().orEmpty()
        if (id.isEmpty() || id.length > 200) return
        intent?.removeExtra(Notify.EXTRA_OPEN_CHAT)
        (application as OgApp).store.openRequest.value = id
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
