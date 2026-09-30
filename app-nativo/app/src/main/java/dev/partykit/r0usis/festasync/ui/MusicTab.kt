package dev.partykit.r0usis.festasync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.partykit.r0usis.festasync.PartyViewModel
import dev.partykit.r0usis.festasync.net.QueueItem
import kotlin.math.floor

fun formatTime(seconds: Double): String {
    val s = floor(seconds.coerceAtLeast(0.0)).toLong()
    return "%02d:%02d".format(s / 60, s % 60)
}

/** Por cima do player: bloqueia toque direto no vídeo (quem mexe é pelos botões, pra todo
 *  mundo continuar sincronizado — igual o site), mostra o aviso quando não tem música e o
 *  botão de tela cheia. Tocar duas vezes no vídeo também entra/sai da tela cheia; em tela
 *  cheia, um toque mostra os controles por alguns segundos. */
@Composable
fun PlayerOverlay(vm: PartyViewModel, modifier: Modifier, fullscreen: Boolean, onToggleFullscreen: () -> Unit) {
    val current = vm.state?.current
    var controlsVisible by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(controlsVisible, vm.state?.isPlaying) {
        if (controlsVisible) { kotlinx.coroutines.delay(3500); controlsVisible = false }
    }
    Box(
        modifier
            .clip(RoundedCornerShape(if (fullscreen) 0.dp else 18.dp))
            .then(if (current == null) Modifier.background(Festa.panel) else Modifier)
            .pointerInput(fullscreen, current != null) {
                detectTapGestures(
                    onTap = { if (fullscreen) controlsVisible = !controlsVisible },
                    onDoubleTap = { if (current != null) onToggleFullscreen() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (current == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🎧", fontSize = 26.sp)
                Spacer(Modifier.height(6.dp))
                Text("Nenhuma música na tela ainda.\nCole um link do YouTube aí embaixo!", color = Festa.textDim, textAlign = TextAlign.Center, fontSize = 15.sp, lineHeight = 22.sp)
            }
            return@Box
        }
        if (current.isLive) {
            Text(
                "🔴 AO VIVO", style = Festa.label.copy(color = Festa.hot),
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp).clip(CircleShape).background(Festa.bgDeep.copy(alpha = 0.7f)).padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        // entrar/sair da tela cheia
        Box(
            Modifier.align(Alignment.TopEnd).padding(if (fullscreen) 18.dp else 10.dp)
                .size(if (fullscreen) 44.dp else 36.dp).clip(RoundedCornerShape(10.dp))
                .background(if (fullscreen) Festa.hot else Festa.bgDeep.copy(alpha = 0.6f))
                .border(1.dp, Festa.borderMid, RoundedCornerShape(10.dp))
                .clickable(onClick = onToggleFullscreen),
            contentAlignment = Alignment.Center,
        ) { Text(if (fullscreen) "✕" else "⛶", color = if (fullscreen) Festa.onHot else Festa.textLight, fontSize = if (fullscreen) 18.sp else 17.sp, fontWeight = FontWeight.Bold) }

        if (fullscreen && controlsVisible) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Festa.bgDeep.copy(alpha = 0.9f))))
                    .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 18.dp),
            ) {
                Text(current.title, color = Festa.textLight, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                ProgressBar(vm)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    RoundButton("⏮", 44.dp) { vm.previous(); controlsVisible = true }
                    Spacer(Modifier.width(14.dp))
                    RoundButton("-10", 44.dp, mono = true) { vm.seekBy(-10.0); controlsVisible = true }
                    Spacer(Modifier.width(14.dp))
                    RoundButton(if (vm.state?.isPlaying == true) "⏸" else "▶", 58.dp, hot = true) { vm.playPause() }
                    Spacer(Modifier.width(14.dp))
                    RoundButton("+10", 44.dp, mono = true) { vm.seekBy(10.0); controlsVisible = true }
                    Spacer(Modifier.width(14.dp))
                    RoundButton("⏭", 44.dp) { vm.next(); controlsVisible = true }
                }
            }
        }
    }
}

/** no lugar do vídeo, no modo trabalho */
@Composable
private fun WorkModeBanner(vm: PartyViewModel) {
    Row(
        Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp).fillMaxWidth()
            .clip(RoundedCornerShape(14.dp)).background(Color(0x0F78A0FF))
            .border(1.dp, Color(0x3378A0FF), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("💼 Modo trabalho: vídeo escondido — a música continua tocando.", color = Festa.textDim, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        Text(
            "Mostrar", color = Festa.textLight, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(CircleShape).border(1.dp, Festa.borderMid, CircleShape).clickable { vm.toggleWorkMode() }.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
fun MusicTab(vm: PartyViewModel) {
    val s = vm.state
    val queue = s?.queue.orEmpty()
    Column(Modifier.fillMaxSize()) {
    // espaço reservado pro player, que é desenhado por cima (ver RoomScreen). Fica fixo no
    // topo; controles, fila etc. rolam embaixo dele.
    if (vm.workMode) WorkModeBanner(vm) else Box(PlayerSlotModifier)
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(top = 12.dp, bottom = 20.dp)) {
        item { Controls(vm) }
        item { AddBar(vm) }
        item {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
                Text("FILA DO AUX", fontFamily = Festa.display, fontSize = 28.sp, color = Festa.textLight)
                Spacer(Modifier.width(10.dp))
                Text("${queue.size} MÚSICA${if (queue.size == 1) "" else "S"}", style = Festa.label, modifier = Modifier.padding(bottom = 6.dp))
            }
        }
        if (queue.isEmpty()) {
            item {
                Text(
                    "Fila vazia. Cola um link do YouTube ali em cima e bora! 🎶",
                    color = Festa.textFaint, fontSize = 14.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                )
            }
        }
        itemsIndexed(queue, key = { _, it -> it.qid }) { index, item ->
            QueueRow(vm, item, index, isCurrent = index == s?.currentIndex, isLast = index == queue.lastIndex)
        }
        item {
            Text(
                "Qualquer pessoa pode tocar, pausar, pular e adicionar músicas — a última ação manda 🎛️",
                color = Festa.textGhost, fontSize = 12.sp, textAlign = TextAlign.Center, lineHeight = 18.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            )
        }
    }
    }
}

@Composable
private fun Controls(vm: PartyViewModel) {
    val s = vm.state
    val current = s?.current
    Column(
        Modifier.padding(horizontal = 14.dp).fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Festa.panel)
            .border(1.dp, Festa.borderSoft, RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        if (current != null) {
            Text(current.title, color = Festa.textLight, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(current.artist.takeIf { it.isNotBlank() }, "adicionada por ${current.addedBy}").joinToString(" · "),
                color = Festa.textFaint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
        }
        ProgressBar(vm)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            RoundButton("⏮", 46.dp) { vm.previous() }
            RoundButton("-10", 46.dp, mono = true) { vm.seekBy(-10.0) }
            RoundButton(if (s?.isPlaying == true) "⏸" else "▶", 64.dp, hot = true) { vm.playPause() }
            RoundButton("+10", 46.dp, mono = true) { vm.seekBy(10.0) }
            RoundButton("⏭", 46.dp) { vm.next() }
        }
    }
}

@Composable
private fun ProgressBar(vm: PartyViewModel) {
    val duration = vm.duration
    val live = vm.state?.current?.isLive == true
    var dragRatio by remember { mutableStateOf<Float?>(null) }
    var width by remember { mutableFloatStateOf(1f) }
    val ratio = dragRatio ?: if (duration > 0) (vm.elapsed / duration).toFloat().coerceIn(0f, 1f) else 0f
    val canSeek = duration > 0 && !live

    Box(
        Modifier.fillMaxWidth().height(24.dp)
            .pointerInput(canSeek, duration) {
                if (!canSeek) return@pointerInput
                width = size.width.toFloat()
                detectTapGestures { pos -> vm.seekTo((pos.x / size.width).coerceIn(0f, 1f) * duration) }
            }
            .pointerInput(canSeek, duration) {
                if (!canSeek) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { dragRatio = (it.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { dragRatio?.let { vm.seekTo(it * duration) }; dragRatio = null },
                    onDragCancel = { dragRatio = null },
                ) { change, _ -> dragRatio = (change.position.x / size.width).coerceIn(0f, 1f) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Festa.panel3))
        Box(Modifier.fillMaxWidth(ratio).height(8.dp).clip(CircleShape).background(Festa.hotGradient))
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            Box(Modifier.offset(x = maxWidth * ratio - 7.dp).size(14.dp).clip(CircleShape).background(Color.White))
        }
    }
    Row(Modifier.fillMaxWidth()) {
        val shownElapsed = dragRatio?.let { it * duration } ?: vm.elapsed
        Text(if (live) "AO VIVO" else formatTime(shownElapsed), fontFamily = Festa.mono, fontSize = 12.sp, color = Festa.amber)
        Spacer(Modifier.weight(1f))
        Text(if (live) "" else formatTime(duration), fontFamily = Festa.mono, fontSize = 12.sp, color = Festa.textFaint)
    }
}

@Composable
fun RoundButton(label: String, size: Dp, hot: Boolean = false, mono: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (hot) Festa.hotGradient else androidx.compose.ui.graphics.Brush.linearGradient(listOf(Festa.panel2, Festa.panel2)))
            .border(1.dp, if (hot) Color.Transparent else Festa.borderMid, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (hot) Festa.onHot else Festa.textLight,
            fontSize = if (hot) 24.sp else if (mono) 13.sp else 16.sp,
            fontFamily = if (mono) Festa.mono else null, fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun AddBar(vm: PartyViewModel) {
    var link by remember { mutableStateOf("") }
    var live by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    fun submit() {
        if (link.isBlank()) return
        vm.addToQueue(link, live)
        link = ""; live = false
        focus.clearFocus()
    }
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = link, onValueChange = { link = it },
            placeholder = { Text("Cole o link do YouTube aqui...", maxLines = 1) },
            singleLine = true, shape = RoundedCornerShape(12.dp), colors = festaFieldColors(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        // "o próximo link é uma transmissão ao vivo" (igual o botão 🔴 do site)
        Box(
            Modifier.size(48.dp).clip(CircleShape)
                .background(if (live) Festa.hot.copy(alpha = 0.18f) else Festa.panel)
                .border(1.dp, if (live) Festa.hot else Festa.borderMid, CircleShape)
                .clickable { live = !live },
            contentAlignment = Alignment.Center,
        ) { Text(if (live) "🔴" else "⚫", fontSize = 16.sp) }
        Spacer(Modifier.width(8.dp))
        HotButton("Adicionar", Modifier.height(52.dp)) { submit() }
    }
}

@Composable
private fun QueueRow(vm: PartyViewModel, item: QueueItem, index: Int, isCurrent: Boolean, isLast: Boolean) {
    Row(
        Modifier.padding(horizontal = 14.dp, vertical = 4.dp).fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isCurrent) Festa.hot.copy(alpha = 0.08f) else Festa.panel)
            .border(1.dp, if (isCurrent) Festa.hot.copy(alpha = 0.35f) else Festa.borderSoft, RoundedCornerShape(14.dp))
            .clickable { vm.playIndex(index) }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!vm.workMode) Box {
            AsyncImage(
                model = item.thumb.ifBlank { "https://img.youtube.com/vi/${item.videoId}/mqdefault.jpg" },
                contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.width(88.dp).height(50.dp).clip(RoundedCornerShape(8.dp)).background(Festa.panel3),
            )
            if (isCurrent) {
                Box(Modifier.width(88.dp).height(50.dp).clip(RoundedCornerShape(8.dp)).background(Festa.bgDeep.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                    Text(if (vm.state?.isPlaying == true) "▶" else "⏸", color = Festa.hot, fontSize = 18.sp)
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                (if (item.isLive) "🔴 " else "") + item.title,
                color = if (isCurrent) Festa.textLight else Festa.textMid, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 17.sp,
            )
            Text("por ${item.addedBy}", color = Festa.textFaint, fontSize = 11.sp, maxLines = 1)
        }
        Column {
            SmallAction("▲", enabled = index > 0) { vm.moveItem(item.qid, -1) }
            SmallAction("▼", enabled = !isLast) { vm.moveItem(item.qid, 1) }
        }
        SmallAction("✕", fillHeight = true) { vm.removeFromQueue(item.qid) }
    }
}

@Composable
private fun SmallAction(label: String, enabled: Boolean = true, fillHeight: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.width(34.dp).then(if (fillHeight) Modifier.height(50.dp) else Modifier.height(25.dp))
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) Festa.textDim else Festa.textGhost.copy(alpha = 0.5f), fontSize = if (fillHeight) 15.sp else 11.sp)
    }
}
