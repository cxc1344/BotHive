package app.opengrok

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

private fun vec(name: String, d: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(d).toNodes(), fill = SolidColor(Color.Black))
        .build()

/** All icons are rendered vectors — no emoji anywhere. */
object Ic {
    val Add = vec("add", "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z")
    val Attach = vec("attach", "M16.5,6v11.5c0,2.21 -1.79,4 -4,4s-4,-1.79 -4,-4V5c0,-1.66 1.34,-3 3,-3s3,1.34 3,3v10.5c0,1.1 -0.9,2 -2,2s-2,-0.9 -2,-2V6H8.5v9.5C8.5,17.99 10.51,20 13,20s4.5,-2.01 4.5,-4.5V5c0,-2.76 -2.24,-5 -5,-5S7,2.24 7,5v12.5C7,20.43 9.57,23 12.5,23s5.5,-2.57 5.5,-5.5V6H16.5z")
    val Stop = vec("stop", "M6,6h12v12H6z")
    val Send = vec("send", "M2.01,21L23,12 2.01,3 2,10l15,2 -15,2z")
    val Back = vec("back", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z")
    val Down = vec("down", "M16.59,8.59L12,13.17 7.41,8.59 6,10l6,6 6,-6z")
    val Refresh = vec("refresh", "M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z")
    val Edit = vec("edit", "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z")
    val Tune = vec("tune", "M3,17v2h6v-2H3zM3,5v2h10V5H3zM13,21v-2h8v-2h-8v-2h-2v6h2zM7,9v2H3v2h4v2h2V9H7zM21,13v-2H11v2h10zM15,9h2V7h4V5h-4V3h-2v6z")
    val Terminal = vec("terminal", "M5.5,7.5L10,12l-4.5,4.5 1.4,1.4L12.8,12 6.9,6.1z M12,16h6v2h-6z")
}
