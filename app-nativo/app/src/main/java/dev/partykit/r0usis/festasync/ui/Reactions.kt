package dev.partykit.r0usis.festasync.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.partykit.r0usis.festasync.PartyViewModel
import dev.partykit.r0usis.festasync.net.FloatingReaction
import dev.partykit.r0usis.festasync.net.REACTIONS

// Reações (igual o Meet): o emoji sai de baixo da tela e sobe balançando, com o nome de quem
// mandou. Mesmo visual do site (.reaction-float em public/index.html).

/** camada por cima de tudo (inclusive do vídeo em tela cheia) — não pega toque */
@Composable
fun ReactionsOverlay(vm: PartyViewModel) {
    if (vm.reactions.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        vm.reactions.toList().forEach { r ->
            key(r.id) { ReactionBubble(r, w, h, discreet = vm.workMode) { vm.removeReaction(r.id) } }
        }
    }
}

@Composable
private fun ReactionBubble(r: FloatingReaction, w: Float, h: Float, discreet: Boolean, onDone: () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(r.id) {
        progress.animateTo(1f, tween(r.durationMs, easing = CubicBezierEasing(0.2f, 0.6f, 0.3f, 1f)))
        onDone()
    }
    val sway by rememberInfiniteTransition(label = "sway").animateFloat(
        -1f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "s",
    )
    val density = LocalDensity.current
    val start = with(density) { 70.dp.toPx() }
    val swayPx = with(density) { r.sway.dp.toPx() }
    Column(
        Modifier.graphicsLayer {
            val p = progress.value
            alpha = when { p < 0.1f -> p / 0.1f; p > 0.75f -> (1f - p) / 0.25f; else -> 1f } * (if (discreet) 0.55f else 1f)
            val s = if (p < 0.1f) 0.5f + 5f * p else 1f
            scaleX = s; scaleY = s
            translationX = r.x * w + swayPx * sway
            translationY = h - start * 0.3f - p * h * 0.82f
            rotationZ = 7f * sway
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(r.emoji, fontSize = if (discreet) 22.sp else 44.sp)
        // modo trabalho: discreto — pequeno, meio transparente e sem nome
        if (!discreet) Text(
            r.name, color = androidx.compose.ui.graphics.Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp).clip(CircleShape).background(Festa.bgDeep.copy(alpha = 0.72f)).padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** botão do topo (ao lado do mic): abre a fileira de emojis embaixo dele */
@Composable
fun ReactionButton(vm: PartyViewModel, size: androidx.compose.ui.unit.Dp = 38.dp) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.size(size).clip(CircleShape).background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.04f))
                .border(1.dp, Festa.borderMid, CircleShape).clickable { open = !open },
            contentAlignment = Alignment.Center,
        ) { FIcon(FestaIcons.smile, 20.dp, Festa.textDim) }
        if (open) {
            val density = LocalDensity.current
            // alinhado pela direita, com a borda direita perto da borda da tela (o mic fica do lado)
            Popup(
                alignment = Alignment.TopEnd,
                offset = with(density) { IntOffset((size + 8.dp).roundToPx(), (size + 8.dp).roundToPx()) },
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier.clip(RoundedCornerShape(18.dp)).background(Festa.panel)
                        .border(1.dp, Festa.borderMid, RoundedCornerShape(18.dp)).padding(8.dp),
                ) {
                    Row {
                        REACTIONS.forEach { e ->
                            // fica aberto: dá pra mandar várias seguidas
                            Box(
                                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).clickable { vm.sendReaction(e) },
                                contentAlignment = Alignment.Center,
                            ) { Text(e, fontSize = 26.sp) }
                        }
                    }
                    Row(
                        Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp))
                            .clickable { vm.setHideReactionsEnabled(!vm.hideReactions) }.padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(18.dp).clip(RoundedCornerShape(5.dp))
                                .background(if (vm.hideReactions) Festa.hot else androidx.compose.ui.graphics.Color.Transparent)
                                .border(1.5.dp, if (vm.hideReactions) Festa.hot else Festa.textFaint, RoundedCornerShape(5.dp)),
                            contentAlignment = Alignment.Center,
                        ) { if (vm.hideReactions) Text("✓", color = Festa.onHot, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.width(8.dp))
                        Text("Não mostrar as reações dos outros", color = Festa.textDim, fontSize = 12.5.sp)
                    }
                }
            }
        }
    }
}
