package dev.partykit.r0usis.festasync.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Peças do design v2 do celular (turno 6) usadas em várias telas.

@Composable
fun FIcon(icon: ImageVector, size: Dp = 22.dp, tint: Color = Festa.textLight, modifier: Modifier = Modifier) {
    Icon(icon, contentDescription = null, tint = tint, modifier = modifier.size(size))
}

/** botão redondo com ícone (fundo do painel, borda fininha) */
@Composable
fun IconCircle(icon: ImageVector, size: Dp, iconSize: Dp = 20.dp, hot: Boolean = false, tint: Color = Festa.textLight, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .then(if (hot) Modifier.background(Festa.hotGradient) else Modifier.background(Festa.panel2).border(1.dp, Festa.borderMid, CircleShape))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { FIcon(icon, iconSize, if (hot) Festa.onHot else tint) }
}

/** folha que sobe do rodapé: cantos 24, fundo #140C1E, véu .62, alça 40×4 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FestaSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Festa.panel,
        contentColor = Festa.textLight,
        scrimColor = Color.Black.copy(alpha = 0.62f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp).width(40.dp).height(4.dp).clip(CircleShape).background(Festa.borderStrong))
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 16.dp).navigationBarsPadding(), content = content)
    }
}

/** cabeçalho de folha: título + rótulo pequeno à direita */
@Composable
fun SheetTitle(title: String, cap: String) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Festa.textLight, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(cap, style = Festa.label)
    }
}

/** linha de ação de folha (ícone + texto), 52dp */
@Composable
fun SheetAction(icon: ImageVector?, text: String, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val color = when { !enabled -> Festa.textGhost; danger -> Festa.danger; else -> Festa.textLight }
    Row(
        Modifier.padding(bottom = 10.dp).fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp))
            .background(Festa.panel2).border(1.dp, Festa.borderSoft, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { FIcon(icon, 20.dp, color); Spacer(Modifier.width(12.dp)) }
        Text(text, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** botão "Pronto"/"Fechar" das folhas, 48dp */
@Composable
fun SheetDoneButton(text: String = "Pronto", onClick: () -> Unit) {
    Box(
        Modifier.padding(top = 6.dp).fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp))
            .border(1.dp, Festa.borderMid, RoundedCornerShape(14.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Festa.textLight, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
}

/** slider do design: trilho 6dp âmbar, bolinha branca 22dp. `tick` marca um ponto (ex.: 100% no de 0–200%) */
@Composable
fun FestaSlider(value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier, tick: Float? = null) {
    val v = value.coerceIn(0f, 1f)
    BoxWithConstraints(
        modifier.fillMaxWidth().height(30.dp)
            .pointerInput(Unit) { detectTapGestures { onChange((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ -> change.consume(); onChange((change.position.x / size.width).coerceIn(0f, 1f)) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Festa.panel3))
        Box(Modifier.fillMaxWidth(v).height(6.dp).clip(CircleShape).background(Festa.amber))
        if (tick != null) Box(Modifier.offset(x = maxWidth * tick - 1.dp).width(2.dp).height(12.dp).background(Festa.textFaint))
        Box(
            Modifier.offset(x = (maxWidth - 22.dp) * v).size(22.dp).shadow(3.dp, CircleShape)
                .clip(CircleShape).background(Color.White),
        )
    }
}

/** chave liga/desliga 50×30 */
@Composable
fun FestaSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val x by animateDpAsState(if (checked) 23.dp else 3.dp, label = "switch")
    Box(
        Modifier.width(50.dp).height(30.dp).clip(CircleShape)
            .background(if (checked) Festa.hot else Festa.panel3)
            .clickable { onChange(!checked) },
    ) {
        Box(Modifier.offset(x = x, y = 3.dp).size(24.dp).clip(CircleShape).background(if (checked) Festa.onHot else Festa.textFaint))
    }
}

/** "agora 02:38 / total 03:33": sem duração conhecida ainda mostra "--:--" (e não "00:00");
 *  com duração, o tempo corrido nunca passa dela */
fun timePair(elapsed: Double, duration: Double, hasVideo: Boolean): Pair<String, String> =
    if (duration > 0) formatTime(elapsed.coerceAtMost(duration)) to formatTime(duration)
    else formatTime(elapsed) to (if (hasVideo) "--:--" else "00:00")
