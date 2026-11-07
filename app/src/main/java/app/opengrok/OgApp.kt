package app.opengrok

import android.app.Application

class OgApp : Application() {
    lateinit var store: Store
    override fun onCreate() {
        super.onCreate()
        store = Store(this)
    }
}
