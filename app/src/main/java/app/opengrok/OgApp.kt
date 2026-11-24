package app.opengrok

import android.app.Application

class OgApp : Application() {
    lateinit var store: Store
    override fun onCreate() {
        super.onCreate()
        // v0.7.0: policy, usage ledger and vault are read by the Store, the notification listener
        // and the webhook server, which can all run without MainActivity: init them first.
        runCatching { Policy.init(this) }
        runCatching { UsageLedger.init(this) }
        runCatching { Vault.init(this) }
        runCatching { ModelPrefs.init(this) }
        store = Store(this)
        // Local webhook trigger for routines (localhost only unless the user enabled LAN access).
        runCatching { if (WebhookServer.isEnabled(this)) WebhookServer.start(this, store) }
    }
}
