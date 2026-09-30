package dev.partykit.r0usis.festasync.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.partykit.r0usis.festasync.R

// Os mesmos tokens de cor do site (:root em public/index.html) — o app tem que parecer da
// mesma família.
object Festa {
    val bgDeep = Color(0xFF0B0710)
    val panel = Color(0xFF140C1E)
    val panel2 = Color(0xFF1B1026)
    val panel3 = Color(0xFF241531)
    val borderSoft = Color(0x12FFFFFF)
    val borderMid = Color(0x1AFFFFFF)
    val borderStrong = Color(0x2EFFFFFF)
    val hot = Color(0xFFFF3D81)
    val hot2 = Color(0xFFFF6FA5)
    val amber = Color(0xFFFFC93C)
    val danger = Color(0xFFFF6B6B)
    val textLight = Color(0xFFF6EEFF)
    val textMid = Color(0xFFE6DBF5)
    val textDim = Color(0xFFB9A8D6)
    val textFaint = Color(0xFF8E7BAE)
    val textGhost = Color(0xFF5F5175)
    val onHot = Color(0xFF1A0A12)
    val hotGradient = Brush.linearGradient(listOf(hot, hot2))

    val display = FontFamily(Font(R.font.bebas_neue))
    val mono = FontFamily(Font(R.font.space_mono, FontWeight.Normal), Font(R.font.space_mono_bold, FontWeight.Bold))

    /** rótulo pequeno em caixa alta, tipo "QUEM TÁ NA FESTA" */
    val label = TextStyle(fontFamily = mono, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.4.sp, color = textFaint)
}

@Composable
fun FestaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Festa.hot, onPrimary = Festa.onHot,
            secondary = Festa.amber, onSecondary = Festa.onHot,
            background = Festa.bgDeep, onBackground = Festa.textLight,
            surface = Festa.panel, onSurface = Festa.textLight,
            surfaceVariant = Festa.panel2, onSurfaceVariant = Festa.textDim,
            outline = Festa.borderMid, error = Festa.danger,
        ),
        content = content,
    )
}

@Composable
fun festaFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Festa.hot,
    unfocusedBorderColor = Festa.borderSoft,
    focusedContainerColor = Festa.bgDeep,
    unfocusedContainerColor = Festa.bgDeep,
    cursorColor = Festa.hot,
    focusedTextColor = Festa.textLight,
    unfocusedTextColor = Festa.textLight,
    focusedPlaceholderColor = Festa.textGhost,
    unfocusedPlaceholderColor = Festa.textGhost,
)

/** cartão com a borda fininha de 1px que o site usa em tudo */
@Composable
fun FestaCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(18.dp),
    color: Color = Festa.panel,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .clip(shape)
            .background(color)
            .border(BorderStroke(1.dp, Festa.borderSoft), shape)
            .padding(padding),
        content = content,
    )
}

/** botão principal rosa com o gradiente dos CTAs do site */
@Composable
fun HotButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, fill: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .then(if (fill) Modifier.fillMaxWidth() else Modifier)
            .clip(shape)
            .background(if (enabled) Festa.hotGradient else Brush.linearGradient(listOf(Festa.panel3, Festa.panel3)))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Text(text, color = if (enabled) Festa.onHot else Festa.textFaint, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
    }
}
