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
    val Close = vec("close", "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z")
    val Menu = vec("menu", "M3,6h18v2H3zM3,11h12v2H3zM3,16h18v2H3z")
    val Gear = vec("gear", "M10.03,4.66L10.47,2.32L13.53,2.32L13.97,4.66L15.80,5.42L17.76,4.07L19.93,6.24L18.58,8.20L19.34,10.03L21.68,10.47L21.68,13.53L19.34,13.97L18.58,15.80L19.93,17.76L17.76,19.93L15.80,18.58L13.97,19.34L13.53,21.68L10.47,21.68L10.03,19.34L8.20,18.58L6.24,19.93L4.07,17.76L5.42,15.80L4.66,13.97L2.32,13.53L2.32,10.47L4.66,10.03L5.42,8.20L4.07,6.24L6.24,4.07L8.20,5.42zM12,8.6a3.4,3.4 0,1,0 0,6.8a3.4,3.4 0,1,0 0,-6.8z")
    val Chevron = vec("chevron", "M10,6L8.59,7.41 13.17,12l-4.58,4.59L10,18l6,-6z")
    val Search = vec("search", "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z")
    val Group = vec("group", "M16,11c1.66,0 2.99,-1.34 2.99,-3S17.66,5 16,5c-1.66,0 -3,1.34 -3,3s1.34,3 3,3zM8,11c1.66,0 2.99,-1.34 2.99,-3S9.66,5 8,5C6.34,5 5,6.34 5,8s1.34,3 3,3zM8,13c-2.33,0 -7,1.17 -7,3.5V19h14v-2.5c0,-2.33 -4.67,-3.5 -7,-3.5zM16,13c-0.29,0 -0.62,0.02 -0.97,0.05 1.16,0.84 1.97,1.97 1.97,3.45V19h6v-2.5c0,-2.33 -4.67,-3.5 -7,-3.5z")
    val Tree = vec("tree", "M22,11V3h-7v3H9V3H2v8h7V8h2v10h4v3h7v-8h-7v3h-2V8h2v3z")
    val Phone = vec("phone", "M17,1.01L7,1c-1.1,0 -2,0.9 -2,2v18c0,1.1 0.9,2 2,2h10c1.1,0 2,-0.9 2,-2V3c0,-1.1 -0.9,-1.99 -2,-1.99zM17,19H7V5h10v14z")
    val Terminal = vec("terminal", "M5.5,7.5L10,12l-4.5,4.5 1.4,1.4L12.8,12 6.9,6.1z M12,16h6v2h-6z")
}
