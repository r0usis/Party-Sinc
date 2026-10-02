package dev.partykit.r0usis.festasync.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.partykit.r0usis.festasync.PartyViewModel
import dev.partykit.r0usis.festasync.net.QueueItem
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.roundToInt

fun formatTime(seconds: Double): String {
    val s = floor(seconds.coerceAtLeast(0.0)).toLong()
    return "%02d:%02d".format(s / 60, s % 60)
}

/** Por cima do player: bloqueia toque direto no vídeo (quem mexe é pelos botões, pra todo
 *  mundo continuar sincronizado — igual o site), mostra o aviso quando não tem música, a
 *  pílula "Só áudio", o botão de tela cheia e a barrinha de progresso colada na base. Tocar
 *  duas vezes no vídeo também entra/sai da tela cheia; em tela cheia, um toque mostra os
 *  controles por alguns segundos. */
@Composable
fun PlayerOverlay(vm: PartyViewModel, modifier: Modifier, fullscreen: Boolean, onToggleFullscreen: () -> Unit) {
    val current = vm.state?.current
    var controlsVisible by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(controlsVisible, vm.state?.isPlaying) {
        if (controlsVisible) { kotlinx.coroutines.delay(3500); controlsVisible = false }
    }
    Box(
        modifier
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
                FIcon(FestaIcons.headphones, 28.dp, Festa.textFaint)
                Spacer(Modifier.height(8.dp))
                Text("Nenhuma música na tela ainda.\nCole um link ou o nome aí embaixo!", color = Festa.textDim, textAlign = TextAlign.Center, fontSize = 15.sp, lineHeight = 22.sp)
            }
            return@Box
        }
        if (current.isLive) {
            Text(
                "AO VIVO", style = Festa.label.copy(color = Festa.hot),
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp).clip(CircleShape).background(Festa.bgDeep.copy(alpha = 0.7f)).padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        if (!fullscreen) {
            // "Só áudio" = modo trabalho (6c)
            Row(
                Modifier.align(Alignment.TopEnd).padding(10.dp).height(32.dp).clip(CircleShape)
                    .background(Festa.bgDeep.copy(alpha = 0.7f)).border(1.dp, Festa.borderMid, CircleShape)
                    .clickable { vm.toggleWorkMode() }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FIcon(FestaIcons.headphones, 16.dp)
                Spacer(Modifier.width(6.dp))
                Text("Só áudio", color = Festa.textLight, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        // entrar/sair da tela cheia
        Box(
            Modifier.align(if (fullscreen) Alignment.TopEnd else Alignment.BottomEnd).padding(if (fullscreen) 18.dp else 10.dp)
                .size(if (fullscreen) 44.dp else 32.dp).clip(RoundedCornerShape(10.dp))
                .background(if (fullscreen) Festa.hot else Festa.bgDeep.copy(alpha = 0.6f))
                .border(1.dp, Festa.borderMid, RoundedCornerShape(10.dp))
                .clickable(onClick = onToggleFullscreen),
            contentAlignment = Alignment.Center,
        ) {
            if (fullscreen) FIcon(FestaIcons.x, 20.dp, Festa.onHot)
            else Text("⛶", color = Festa.textLight, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        // barrinha de 3dp colada na base do vídeo
        if (!fullscreen && !current.isLive) {
            val ratio = if (vm.duration > 0) (vm.elapsed / vm.duration).toFloat().coerceIn(0f, 1f) else 0f
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.12f))) {
                Box(Modifier.fillMaxWidth(ratio).fillMaxHeight().background(Festa.hotGradient))
            }
        }

        if (fullscreen && controlsVisible) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Festa.bgDeep.copy(alpha = 0.9f))))
                    .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 18.dp),
            ) {
                Text(current.title, color = Festa.textLight, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                ProgressBar(vm)
                Spacer(Modifier.height(8.dp))
                Transport(vm, 44.dp, 58.dp, Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally)) { controlsVisible = true }
            }
        }
    }
}

@Composable
fun MusicTab(vm: PartyViewModel, onVolume: () -> Unit) {
    val s = vm.state
    val queue = s?.queue.orEmpty()
    val currentIndex = s?.currentIndex ?: -1
    // a que está tocando já aparece em cima — a lista é só o que vem depois (e antes)
    val listed = queue.withIndex().filter { it.index != currentIndex }
    var sheetFor by remember { mutableStateOf<QueueItem?>(null) }
    sheetFor?.let { item -> QueueSheet(vm, item) { sheetFor = null } }

    Column(Modifier.fillMaxSize()) {
        // espaço reservado pro player, que é desenhado por cima (ver RoomScreen). Fica fixo no
        // topo; controles, fila etc. rolam embaixo dele.
        if (!vm.workMode) Box(PlayerSlotModifier)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 20.dp)) {
            if (vm.workMode) {
                item { AudioSegmented(vm) }
                item { CompactCard(vm, onVolume) }
            } else {
                item { NowPlayingInfo(vm, onVolume) }
            }
            item { AddBar(vm) }
            item {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
                    Text("FILA DO AUX", fontFamily = Festa.display, fontSize = 28.sp, color = Festa.textLight)
                    Spacer(Modifier.width(10.dp))
                    Text("${queue.size} MÚSICA${if (queue.size == 1) "" else "S"}", style = Festa.label, modifier = Modifier.padding(bottom = 6.dp))
                }
            }
            if (listed.isEmpty()) {
                item {
                    Text(
                        if (queue.isEmpty()) "Fila vazia. Cola um link ou o nome de uma música ali em cima e bora! 🎶" else "Nada depois dessa. Adiciona mais uma! 🎶",
                        color = Festa.textFaint, fontSize = 14.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                    )
                }
            }
            items(listed, key = { it.value.qid }) { (index, item) ->
                val isNext = index == currentIndex + 1
                SwipeToRemove(vm, item, onTap = { vm.playIndex(index) }, onLongPress = { sheetFor = item }) {
                    if (vm.workMode) DenseQueueRow(item, number = index - currentIndex.coerceAtLeast(-1), isNext = isNext)
                    else QueueRow(vm, item, isNext = isNext)
                }
            }
            if (listed.isNotEmpty()) item {
                Text(
                    "Segure uma música pra ver as opções · arraste pro lado pra tirar",
                    color = Festa.textGhost, fontSize = 12.sp, textAlign = TextAlign.Center, lineHeight = 18.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
        }
    }
}

/** título 16/600, "Artista · adicionada por X", botão de volume 40, barra e controles */
@Composable
private fun NowPlayingInfo(vm: PartyViewModel, onVolume: () -> Unit) {
    val current = vm.state?.current
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(current?.title ?: "Nada tocando", color = Festa.textLight, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (current != null) Text(subtitleOf(current), color = Festa.textDim, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(12.dp))
            IconCircle(FestaIcons.volume, 40.dp, 20.dp, onClick = onVolume)
        }
        Spacer(Modifier.height(10.dp))
        ProgressBar(vm)
        Spacer(Modifier.height(10.dp))
        Transport(vm, 44.dp, 60.dp, Arrangement.SpaceBetween)
    }
}

private fun subtitleOf(item: QueueItem) =
    listOfNotNull(item.artist.takeIf { it.isNotBlank() }, "adicionada por ${item.addedBy}").joinToString(" · ")

/** 6c — "Vídeo | Só áudio" (a ativa com borda âmbar) */
@Composable
private fun AudioSegmented(vm: PartyViewModel) {
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp).fillMaxWidth().height(48.dp)
            .clip(RoundedCornerShape(14.dp)).background(Festa.bgDeep).border(1.dp, Festa.borderSoft, RoundedCornerShape(14.dp)).padding(4.dp),
    ) {
        listOf(false to "Vídeo", true to "Só áudio").forEach { (audio, label) ->
            val on = vm.workMode == audio
            Row(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp))
                    .background(if (on) Festa.amber.copy(alpha = 0.1f) else Color.Transparent)
                    .border(1.dp, if (on) Festa.amber.copy(alpha = 0.7f) else Color.Transparent, RoundedCornerShape(10.dp))
                    .clickable { if (!on) vm.toggleWorkMode() },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                FIcon(if (audio) FestaIcons.headphones else FestaIcons.video, 18.dp, if (on) Festa.amber else Festa.textFaint)
                Spacer(Modifier.width(8.dp))
                Text(label, color = if (on) Festa.amber else Festa.textFaint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** 6c — card compacto: capa 56, título, volume, barra e controles 40/52 */
@Composable
private fun CompactCard(vm: PartyViewModel, onVolume: () -> Unit) {
    val current = vm.state?.current
    Column(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)).background(Festa.panel).border(1.dp, Festa.borderSoft, RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (current != null) {
                AsyncImage(
                    model = thumbOf(current), contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(Festa.panel3),
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(current?.title ?: "Nada tocando", color = Festa.textLight, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (current != null) Text(subtitleOf(current), color = Festa.textDim, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            IconCircle(FestaIcons.volume, 40.dp, 20.dp, onClick = onVolume)
        }
        Spacer(Modifier.height(12.dp))
        ProgressBar(vm)
        Spacer(Modifier.height(10.dp))
        Transport(vm, 40.dp, 52.dp, Arrangement.SpaceBetween)
    }
}

/** ⏮ −10 ⏯ +10 ⏭ */
@Composable
private fun Transport(vm: PartyViewModel, small: Dp, big: Dp, arrangement: Arrangement.Horizontal, onAny: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = arrangement, verticalAlignment = Alignment.CenterVertically) {
        IconCircle(FestaIcons.prev, small, 20.dp) { vm.previous(); onAny() }
        TextCircle("-10", small) { vm.seekBy(-10.0); onAny() }
        IconCircle(if (vm.state?.isPlaying == true) FestaIcons.pause else FestaIcons.play, big, (big.value * 0.42f).dp, hot = true) { vm.playPause(); onAny() }
        TextCircle("+10", small) { vm.seekBy(10.0); onAny() }
        IconCircle(FestaIcons.next, small, 20.dp) { vm.next(); onAny() }
    }
}

@Composable
private fun TextCircle(label: String, size: Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Festa.panel2).border(1.dp, Festa.borderMid, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Festa.textLight, fontSize = 12.sp, fontFamily = Festa.mono, fontWeight = FontWeight.Bold) }
}

@Composable
private fun ProgressBar(vm: PartyViewModel) {
    val duration = vm.duration
    val live = vm.state?.current?.isLive == true
    var dragRatio by remember { mutableStateOf<Float?>(null) }
    val ratio = dragRatio ?: if (duration > 0) (vm.elapsed / duration).toFloat().coerceIn(0f, 1f) else 0f
    val canSeek = duration > 0 && !live

    Box(
        Modifier.fillMaxWidth().height(22.dp)
            .pointerInput(canSeek, duration) {
                if (!canSeek) return@pointerInput
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
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Festa.panel3))
        Box(Modifier.fillMaxWidth(ratio).height(6.dp).clip(CircleShape).background(Festa.hotGradient))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Box(Modifier.offset(x = (maxWidth - 14.dp) * ratio).size(14.dp).clip(CircleShape).background(Color.White))
        }
    }
    Row(Modifier.fillMaxWidth()) {
        // sem duração ainda: "--:--" (antes aparecia "02:38 / 00:00"); com duração, o tempo
        // corrido nunca passa dela
        val (shownElapsed, shownDuration) = timePair(dragRatio?.let { it * duration } ?: vm.elapsed, duration, vm.state?.current != null)
        Text(if (live) "AO VIVO" else shownElapsed, fontFamily = Festa.mono, fontSize = 11.sp, color = Festa.amber)
        Spacer(Modifier.weight(1f))
        Text(if (live) "" else shownDuration, fontFamily = Festa.mono, fontSize = 11.sp, color = Festa.textFaint)
    }
}

/** campo 46 com lupa + chip "AO VIVO" + botão + (46) */
@Composable
private fun AddBar(vm: PartyViewModel) {
    var link by remember { mutableStateOf("") }
    var live by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    fun submit() {
        if (link.isBlank() || vm.searching) return
        vm.addToQueue(link, live)
        link = ""; live = false
        focus.clearFocus()
    }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(14.dp)).background(Festa.panel)
                .border(1.dp, Festa.borderMid, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FIcon(FestaIcons.search, 18.dp, Festa.textFaint)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (link.isEmpty()) Text("Link ou nome da música", color = Festa.textGhost, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicTextField(
                    value = link, onValueChange = { link = it }, singleLine = true,
                    textStyle = TextStyle(color = Festa.textLight, fontSize = 14.sp),
                    cursorBrush = SolidColor(Festa.hot),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        // "o próximo é uma transmissão ao vivo" (o 🔴 do site)
        Row(
            Modifier.height(46.dp).clip(RoundedCornerShape(14.dp))
                .background(if (live) Festa.hot.copy(alpha = 0.14f) else Festa.panel)
                .border(1.dp, if (live) Festa.hot else Festa.borderMid, RoundedCornerShape(14.dp))
                .clickable { live = !live }.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(8.dp).clip(CircleShape)
                    .then(if (live) Modifier.background(Festa.hot) else Modifier.border(1.5.dp, Festa.textFaint, CircleShape)),
            )
            Spacer(Modifier.width(6.dp))
            Text("AO VIVO", fontFamily = Festa.mono, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.sp, color = if (live) Festa.hot else Festa.textFaint)
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(Festa.hotGradient).clickable { submit() },
            contentAlignment = Alignment.Center,
        ) {
            if (vm.searching) CircularProgressIndicator(color = Festa.onHot, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            else FIcon(FestaIcons.plus, 22.dp, Festa.onHot)
        }
    }
}

private fun thumbOf(item: QueueItem) = item.thumb.ifBlank { "https://img.youtube.com/vi/${item.videoId}/mqdefault.jpg" }

/** arrastar pro lado esquerdo tira da fila; segurar abre as opções; tocar toca */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwipeToRemove(vm: PartyViewModel, item: QueueItem, onTap: () -> Unit, onLongPress: () -> Unit, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val dx = remember(item.qid) { Animatable(0f) }
    val haptic = LocalHapticFeedback.current
    Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        if (dx.value < 0f) {
            Row(
                Modifier.matchParentSize().clip(RoundedCornerShape(12.dp)).background(Festa.danger.copy(alpha = 0.16f)).padding(end = 18.dp),
                horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically,
            ) {
                FIcon(FestaIcons.x, 18.dp, Festa.danger)
                Spacer(Modifier.width(6.dp))
                Text("Remover", color = Festa.danger, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Box(
            Modifier.offset { IntOffset(dx.value.roundToInt(), 0) }.fillMaxWidth().background(Festa.bgDeep)
                .pointerInput(item.qid) {
                    val limit = 80.dp.toPx()
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (dx.value < -limit) { dx.animateTo(-size.width.toFloat()); vm.removeFromQueue(item.qid) }
                                else dx.animateTo(0f)
                            }
                        },
                        onDragCancel = { scope.launch { dx.animateTo(0f) } },
                    ) { change, amount ->
                        change.consume()
                        scope.launch { dx.snapTo((dx.value + amount).coerceAtMost(0f)) }
                    }
                }
                .combinedClickable(onClick = onTap, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLongPress() }),
        ) { content() }
    }
}

/** 6b — capa 64×40, título 13.5, "por X", "A SEGUIR ·" em âmbar na próxima, alça à direita */
@Composable
private fun QueueRow(vm: PartyViewModel, item: QueueItem, isNext: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = thumbOf(item), contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.width(64.dp).height(40.dp).clip(RoundedCornerShape(8.dp)).background(Festa.panel3),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                (if (item.isLive) "● " else "") + item.title,
                color = Festa.textLight, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row {
                if (isNext) Text("A SEGUIR · ", style = Festa.label.copy(color = Festa.amber, fontSize = 9.5.sp))
                Text("POR ${item.addedBy.uppercase()}", style = Festa.label.copy(fontSize = 9.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // alça: arrastar pra cima/baixo muda a posição (cada ~56dp = uma casa)
        Box(
            Modifier.size(40.dp).pointerInput(item.qid) {
                var acc = 0f
                val step = 56.dp.toPx()
                detectVerticalDragGestures(onDragStart = { acc = 0f }) { change, amount ->
                    change.consume()
                    acc += amount
                    if (acc > step) { vm.moveItem(item.qid, 1); acc -= step }
                    else if (acc < -step) { vm.moveItem(item.qid, -1); acc += step }
                }
            },
            contentAlignment = Alignment.Center,
        ) { FIcon(FestaIcons.drag, 20.dp, Festa.textFaint) }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Festa.borderSoft))
}

/** 6c — fila densa: linhas de 46 com número em mono e título */
@Composable
private fun DenseQueueRow(item: QueueItem, number: Int, isNext: Boolean) {
    Row(Modifier.fillMaxWidth().height(46.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("%02d".format(number), fontFamily = Festa.mono, fontSize = 11.sp, color = if (isNext) Festa.amber else Festa.textFaint, modifier = Modifier.width(30.dp))
        Text(item.title, color = Festa.textLight, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Festa.borderSoft))
}

/** segurar uma música: tocar agora / subir / descer / remover */
@Composable
private fun QueueSheet(vm: PartyViewModel, item: QueueItem, onDismiss: () -> Unit) {
    val queue = vm.state?.queue.orEmpty()
    val idx = queue.indexOfFirst { it.qid == item.qid }
    FestaSheet(onDismiss) {
        Text(item.title, color = Festa.textLight, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text("por ${item.addedBy}", color = Festa.textDim, fontSize = 12.5.sp, modifier = Modifier.padding(bottom = 14.dp))
        SheetAction(FestaIcons.play, "Tocar agora") { onDismiss(); if (idx >= 0) vm.playIndex(idx) }
        SheetAction(null, "Subir na fila", enabled = idx > 0) { onDismiss(); vm.moveItem(item.qid, -1) }
        SheetAction(null, "Descer na fila", enabled = idx in 0 until queue.lastIndex) { onDismiss(); vm.moveItem(item.qid, 1) }
        SheetAction(FestaIcons.x, "Remover da fila", danger = true) { onDismiss(); vm.removeFromQueue(item.qid) }
        SheetDoneButton("Cancelar", onDismiss)
    }
}

