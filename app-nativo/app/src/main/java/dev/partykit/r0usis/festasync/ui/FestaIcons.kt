package dev.partykit.r0usis.festasync.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

// Ícones de traço do design v2 (turno 6) — os mesmos SVGs do site (viewBox 24, traço 1,75,
// pontas redondas). Desenhados em preto; quem usa pinta com `tint` no Icon().
private class P(val d: String, val fill: Boolean)

private fun icon(name: String, vararg paths: P): ImageVector {
    val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    for (p in paths) b.addPath(
        pathData = addPathNodes(p.d),
        fill = if (p.fill) SolidColor(Color.Black) else null,
        stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
    )
    return b.build()
}

object FestaIcons {
    val chevron: ImageVector by lazy { icon("chevron", P("m6 9 6 6 6-6", false)) }
    val micoff: ImageVector by lazy { icon("micoff", P("M2 2l20 20M9 9v2a3 3 0 0 0 5.1 2.1M15 9.3V5a3 3 0 0 0-5.9-.8M19 10v1a7 7 0 0 1-1.2 3.9M5 10v1a7 7 0 0 0 11.3 5.5M12 18v4", false)) }
    val mic: ImageVector by lazy { icon("mic", P("M12.0 2.0h0.0a3.0 3.0 0 0 1 3.0 3.0v6.0a3.0 3.0 0 0 1 -3.0 3.0h-0.0a3.0 3.0 0 0 1 -3.0 -3.0v-6.0a3.0 3.0 0 0 1 3.0 -3.0z", false), P("M19 10v1a7 7 0 0 1-14 0v-1M12 18v4", false)) }
    val headphones: ImageVector by lazy { icon("headphones", P("M3 18v-6a9 9 0 0 1 18 0v6", false), P("M21 19a2 2 0 0 1-2 2h-1v-6h3zM3 19a2 2 0 0 0 2 2h1v-6H3z", false)) }
    val volume: ImageVector by lazy { icon("volume", P("M11 5 6 9H2v6h4l5 4z", false), P("M15.5 8.5a5 5 0 0 1 0 7M19 5a10 10 0 0 1 0 14", false)) }
    val prev: ImageVector by lazy { icon("prev", P("M19 20 9 12l10-8z", false), P("M5 19V5", false)) }
    val next: ImageVector by lazy { icon("next", P("m5 4 10 8-10 8z", false), P("M19 5v14", false)) }
    val pause: ImageVector by lazy { icon("pause", P("M7.0 4.0h2.0a1.0 1.0 0 0 1 1.0 1.0v14.0a1.0 1.0 0 0 1 -1.0 1.0h-2.0a1.0 1.0 0 0 1 -1.0 -1.0v-14.0a1.0 1.0 0 0 1 1.0 -1.0z", true), P("M15.0 4.0h2.0a1.0 1.0 0 0 1 1.0 1.0v14.0a1.0 1.0 0 0 1 -1.0 1.0h-2.0a1.0 1.0 0 0 1 -1.0 -1.0v-14.0a1.0 1.0 0 0 1 1.0 -1.0z", true)) }
    val play: ImageVector by lazy { icon("play", P("M7 4.5v15l12-7.5z", true)) }
    val search: ImageVector by lazy { icon("search", P("M4.0 11.0a7.0 7.0 0 1 0 14.0 0a7.0 7.0 0 1 0 -14.0 0z", false), P("m20 20-3.5-3.5", false)) }
    val plus: ImageVector by lazy { icon("plus", P("M12 5v14M5 12h14", false)) }
    val drag: ImageVector by lazy { icon("drag", P("M8.1 6.0a0.9 0.9 0 1 0 1.8 0a0.9 0.9 0 1 0 -1.8 0z", true), P("M14.1 6.0a0.9 0.9 0 1 0 1.8 0a0.9 0.9 0 1 0 -1.8 0z", true), P("M8.1 12.0a0.9 0.9 0 1 0 1.8 0a0.9 0.9 0 1 0 -1.8 0z", true), P("M14.1 12.0a0.9 0.9 0 1 0 1.8 0a0.9 0.9 0 1 0 -1.8 0z", true), P("M8.1 18.0a0.9 0.9 0 1 0 1.8 0a0.9 0.9 0 1 0 -1.8 0z", true), P("M14.1 18.0a0.9 0.9 0 1 0 1.8 0a0.9 0.9 0 1 0 -1.8 0z", true)) }
    val music: ImageVector by lazy { icon("music", P("M9 18V5l12-2v13", false), P("M3.0 18.0a3.0 3.0 0 1 0 6.0 0a3.0 3.0 0 1 0 -6.0 0z", false), P("M15.0 16.0a3.0 3.0 0 1 0 6.0 0a3.0 3.0 0 1 0 -6.0 0z", false)) }
    val gamepad: ImageVector by lazy { icon("gamepad", P("M8.0 6.0h8.0a6.0 6.0 0 0 1 6.0 6.0v0.0a6.0 6.0 0 0 1 -6.0 6.0h-8.0a6.0 6.0 0 0 1 -6.0 -6.0v-0.0a6.0 6.0 0 0 1 6.0 -6.0z", false), P("M6 12h4M8 10v4", false), P("M14.4 13.0a0.6 0.6 0 1 0 1.2 0a0.6 0.6 0 1 0 -1.2 0z", true), P("M17.4 11.0a0.6 0.6 0 1 0 1.2 0a0.6 0.6 0 1 0 -1.2 0z", true)) }
    val chat: ImageVector by lazy { icon("chat", P("M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z", false)) }
    val video: ImageVector by lazy { icon("video", P("M5.0 5.0h14.0a3.0 3.0 0 0 1 3.0 3.0v8.0a3.0 3.0 0 0 1 -3.0 3.0h-14.0a3.0 3.0 0 0 1 -3.0 -3.0v-8.0a3.0 3.0 0 0 1 3.0 -3.0z", false), P("m10 9 5 3-5 3z", false)) }
    val clipboard: ImageVector by lazy { icon("clipboard", P("M9.0 2.0h6.0a1.0 1.0 0 0 1 1.0 1.0v2.0a1.0 1.0 0 0 1 -1.0 1.0h-6.0a1.0 1.0 0 0 1 -1.0 -1.0v-2.0a1.0 1.0 0 0 1 1.0 -1.0z", false), P("M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2", false)) }
    val info: ImageVector by lazy { icon("info", P("M3.0 12.0a9.0 9.0 0 1 0 18.0 0a9.0 9.0 0 1 0 -18.0 0z", false), P("M12 16v-4M12 8h.01", false)) }
    val lock: ImageVector by lazy { icon("lock", P("M6.0 11.0h12.0a2.0 2.0 0 0 1 2.0 2.0v6.0a2.0 2.0 0 0 1 -2.0 2.0h-12.0a2.0 2.0 0 0 1 -2.0 -2.0v-6.0a2.0 2.0 0 0 1 2.0 -2.0z", false), P("M8 11V7a4 4 0 0 1 8 0v4", false)) }
    val x: ImageVector by lazy { icon("x", P("M18 6 6 18M6 6l12 12", false)) }
    val link: ImageVector by lazy { icon("link", P("M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7", false), P("M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7", false)) }
    val clip: ImageVector by lazy { icon("clip", P("m21.4 11.1-9.2 9.2a6 6 0 0 1-8.5-8.5l9.2-9.2a4 4 0 0 1 5.7 5.7l-9.2 9.2a2 2 0 0 1-2.8-2.8l8.5-8.5", false)) }
    val send: ImageVector by lazy { icon("send", P("m22 2-7 20-4-9-9-4z", false), P("M22 2 11 13", false)) }
    val pencil: ImageVector by lazy { icon("pencil", P("M17 3a2.8 2.8 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5z", false)) }
    val forca: ImageVector by lazy { icon("forca", P("M4 21h10M7 21V3h9v3", false), P("M13.5 9.0a2.5 2.5 0 1 0 5.0 0a2.5 2.5 0 1 0 -5.0 0z", false), P("M16 11.5V16", false)) }
    val target: ImageVector by lazy { icon("target", P("M3.0 12.0a9.0 9.0 0 1 0 18.0 0a9.0 9.0 0 1 0 -18.0 0z", false), P("M7.0 12.0a5.0 5.0 0 1 0 10.0 0a5.0 5.0 0 1 0 -10.0 0z", false), P("M11.0 12.0a1.0 1.0 0 1 0 2.0 0a1.0 1.0 0 1 0 -2.0 0z", true)) }
    val wheel: ImageVector by lazy { icon("wheel", P("M3.0 12.0a9.0 9.0 0 1 0 18.0 0a9.0 9.0 0 1 0 -18.0 0z", false), P("M12 3v18M3 12h18M5.6 5.6l12.8 12.8M18.4 5.6 5.6 18.4", false)) }
    val grid: ImageVector by lazy { icon("grid", P("M5.0 3.5h4.0a1.5 1.5 0 0 1 1.5 1.5v4.0a1.5 1.5 0 0 1 -1.5 1.5h-4.0a1.5 1.5 0 0 1 -1.5 -1.5v-4.0a1.5 1.5 0 0 1 1.5 -1.5z", false), P("M15.0 3.5h4.0a1.5 1.5 0 0 1 1.5 1.5v4.0a1.5 1.5 0 0 1 -1.5 1.5h-4.0a1.5 1.5 0 0 1 -1.5 -1.5v-4.0a1.5 1.5 0 0 1 1.5 -1.5z", false), P("M5.0 13.5h4.0a1.5 1.5 0 0 1 1.5 1.5v4.0a1.5 1.5 0 0 1 -1.5 1.5h-4.0a1.5 1.5 0 0 1 -1.5 -1.5v-4.0a1.5 1.5 0 0 1 1.5 -1.5z", false), P("M15.0 13.5h4.0a1.5 1.5 0 0 1 1.5 1.5v4.0a1.5 1.5 0 0 1 -1.5 1.5h-4.0a1.5 1.5 0 0 1 -1.5 -1.5v-4.0a1.5 1.5 0 0 1 1.5 -1.5z", false)) }
    val copy: ImageVector by lazy { icon("copy", P("M11.0 9.0h8.0a2.0 2.0 0 0 1 2.0 2.0v8.0a2.0 2.0 0 0 1 -2.0 2.0h-8.0a2.0 2.0 0 0 1 -2.0 -2.0v-8.0a2.0 2.0 0 0 1 2.0 -2.0z", false), P("M5 15V5a2 2 0 0 1 2-2h10", false)) }
    val logout: ImageVector by lazy { icon("logout", P("M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9", false)) }
}
