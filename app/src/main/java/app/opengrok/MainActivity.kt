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
        // Restore the background keep-alive service whenever the app is opened.
        if (KeepAlive.isEnabled(this)) KeepAlive.start(this)
        setContent { App(store) }
    }
}
