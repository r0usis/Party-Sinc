package dev.partykit.r0usis.festasync.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.partykit.r0usis.festasync.PartyViewModel
import dev.partykit.r0usis.festasync.net.DrawGame
import dev.partykit.r0usis.festasync.net.DrawPoint
import dev.partykit.r0usis.festasync.net.avatarFor
import kotlinx.coroutines.delay

// Jogo de desenho — mesmas telas e o mesmo protocolo do site (renderGamePanel em
// public/index.html): convite → aceitar → cada um desenha uma vez por rodada (3 rodadas),
// quem desenha escolhe 1 de 3 palavras, os outros adivinham em voz alta e quem desenha
// marca quem acertou. Quadro com as mesmas coordenadas do site (320 x 220).

private const val BOARD_W = 320f
private const val BOARD_H = 220f
private val DRAW_COLORS = listOf("#1a0a12", "#FF3D81", "#3B82F6", "#22C55E", "#FFC93C", "#A855F7", "#FFFFFF")

private fun parseColor(hex: String): Color = try { Color(android.graphics.Color.parseColor(hex)) } catch (e: Exception) { Color.Black }

@Composable
fun DrawGameScreen(vm: PartyViewModel, onClose: () -> Unit) {
    val g = vm.state?.drawGame ?: DrawGame()
    val me = vm.myId
    val participant = me in g.acceptedIds || me in g.invitedIds
    Column(Modifier.fillMaxSize()) {
        // cabeçalho
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("←", color = Festa.textLight, fontSize = 20.sp, modifier = Modifier.clip(CircleShape).clickable(onClick = onClose).padding(8.dp))
            Spacer(Modifier.width(4.dp))
            Text("🎨 Jogo de desenho", fontFamily = Festa.display, fontSize = 24.sp, color = Festa.textLight, modifier = Modifier.weight(1f))
            if (g.phase != "idle" && participant) {
                Text(
                    "Sair", color = Festa.textFaint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { vm.drawLeave() }.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            when (g.phase) {
                "inviting" -> InvitingScreen(vm, g)
                "choosing" -> ChoosingScreen(vm, g)
                "drawing" -> DrawingScreen(vm, g)
                "finished" -> FinishedScreen(vm, g, onClose)
                else -> InviteScreen(vm)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Festa.textDim, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(bottom = 12.dp))
}

@Composable
private fun PersonRow(name: String, trailing: @Composable () -> Unit = {}, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.padding(vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (selected) Festa.hot.copy(alpha = 0.10f) else Festa.panel)
            .border(1.dp, if (selected) Festa.hot.copy(alpha = 0.45f) else Festa.borderSoft, RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(Festa.panel3), contentAlignment = Alignment.Center) { Text(avatarFor(name), fontSize = 16.sp) }
        Spacer(Modifier.width(10.dp))
        Text(name, color = Festa.textLight, fontSize = 14.sp, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun InviteScreen(vm: PartyViewModel) {
    val others = vm.members.filter { it.clientId != vm.myId }
    if (others.isEmpty()) { Hint("Chama mais gente pra sala antes de convidar pra jogar 🎨"); return }
    var picked by remember { mutableStateOf(setOf<String>()) }
    Hint("Escolhe quem você quer chamar — cada um desenha por vez, os outros tentam adivinhar em voz alta!")
    others.forEach { m ->
        val on = m.clientId in picked
        PersonRow(m.name, selected = on, trailing = { Text(if (on) "✅" else "⬜", fontSize = 18.sp) }) {
            picked = if (on) picked - m.clientId else picked + m.clientId
        }
    }
    Spacer(Modifier.height(12.dp))
    HotButton("Convidar pra jogar 🎨", Modifier.fillMaxWidth(), enabled = picked.isNotEmpty(), fill = true) { vm.drawInvite(picked.toList()) }
}

@Composable
private fun InvitingScreen(vm: PartyViewModel, g: DrawGame) {
    val me = vm.myId
    val hostName = g.names[g.hostId] ?: "Alguém"
    if (me in g.invitedIds) {
        Hint("🎨 $hostName te chamou pra jogar o jogo de desenho!")
        HotButton("Aceitar ✅", Modifier.fillMaxWidth(), fill = true) { vm.drawRespond(true) }
        Spacer(Modifier.height(8.dp))
        SecondaryButton("Recusar") { vm.drawRespond(false) }
        return
    }
    val rows = @Composable {
        (g.acceptedIds + g.invitedIds).forEach { id ->
            val name = g.names[id] ?: vm.members.find { it.clientId == id }?.name ?: "Alguém"
            val ok = id in g.acceptedIds
            PersonRow(name, selected = ok, trailing = { Text(if (ok) "✅ topou" else "⏳ esperando", color = if (ok) Festa.hot else Festa.textDim, fontSize = 12.sp, fontFamily = Festa.mono) })
        }
    }
    when {
        me == g.hostId -> {
            Hint("Convite enviado — assim que tiver gente suficiente, toca em Iniciar.")
            rows()
            Spacer(Modifier.height(12.dp))
            HotButton("Iniciar jogo 🎨", Modifier.fillMaxWidth(), enabled = g.acceptedIds.size >= 2, fill = true) { vm.drawBegin() }
            Spacer(Modifier.height(8.dp))
            SecondaryButton("Cancelar convite") { vm.drawCancel() }
        }
        me in g.acceptedIds -> { Hint("Você topou! Esperando $hostName iniciar o jogo..."); rows() }
        else -> Hint("🎨 $hostName está organizando uma partida do jogo de desenho.")
    }
}

@Composable
private fun ChoosingScreen(vm: PartyViewModel, g: DrawGame) {
    g.lastGuess?.let { LastGuessBanner(it.guesserName, it.points) }
    when {
        g.currentDrawerId == vm.myId -> {
            val words = vm.drawWordChoices
            if (words == null) { Hint("Escolhendo suas palavras..."); return }
            Hint("Rodada ${g.round} de 3 — sua vez! Escolhe uma palavra pra desenhar:")
            words.forEach { w ->
                Row(
                    Modifier.padding(vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Festa.panel2)
                        .border(1.dp, Festa.borderSoft, RoundedCornerShape(14.dp)).clickable { vm.drawChooseWord(w) }
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("✏️", fontSize = 16.sp)
                    Spacer(Modifier.width(10.dp))
                    Text(w.replaceFirstChar { it.uppercase() }, color = Festa.textLight, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        vm.myId in g.acceptedIds -> Hint("Rodada ${g.round} de 3 — 🎨 ${g.currentDrawerName} está escolhendo uma palavra...")
        else -> Hint("🎨 Tem uma partida rolando entre ${g.order.joinToString(", ") { g.names[it] ?: "?" }}.")
    }
}

@Composable
private fun LastGuessBanner(name: String, points: Int) {
    Text(
        "🎉 $name acertou! +$points pontos", color = Festa.amber, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Festa.amber.copy(alpha = 0.08f))
            .border(1.dp, Festa.amber.copy(alpha = 0.3f), RoundedCornerShape(12.dp)).padding(12.dp),
    )
}

@Composable
private fun DrawingScreen(vm: PartyViewModel, g: DrawGame) {
    val amDrawer = g.currentDrawerId == vm.myId
    if (vm.myId !in g.acceptedIds) { Hint("🎨 ${g.currentDrawerName} está desenhando pra galera adivinhar (rodada ${g.round} de 3)."); return }
    // cronômetro de 60s (o servidor é quem encerra a vez; isso aqui é só o mostrador)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(g.turnStartedAt) { while (true) { now = System.currentTimeMillis(); delay(500) } }
    val left = ((60_000 - (now - (g.turnStartedAt ?: now))) / 1000).coerceIn(0, 60)

    Text(
        "Rodada ${g.round} de 3 — " + if (amDrawer) "sua vez de desenhar!" else "${g.currentDrawerName} está desenhando",
        color = Festa.textDim, fontSize = 14.sp,
    )
    Text("${left}s", fontFamily = Festa.mono, fontSize = 26.sp, color = Festa.amber, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
    if (!amDrawer) {
        Text(List(g.wordLength) { "_" }.joinToString(" "), fontFamily = Festa.mono, fontSize = 20.sp, letterSpacing = 3.sp, color = Festa.textLight, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
    }
    DrawBoard(vm, interactive = amDrawer)
    if (amDrawer) {
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Festa.panel).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DRAW_COLORS.forEach { c ->
                val on = vm.drawColor == c
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(parseColor(c))
                        .border(if (on) 3.dp else 1.dp, if (on) Festa.hot else Festa.borderMid, CircleShape)
                        .clickable { vm.drawColor = c },
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "Limpar", color = Festa.textLight, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).background(Festa.panel2).clickable { vm.drawClear() }.padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        var picking by remember(g.turnStartedAt) { mutableStateOf(false) }
        HotButton("✅ Alguém acertou!", Modifier.fillMaxWidth(), fill = true) { picking = !picking }
        if (picking) {
            Spacer(Modifier.height(10.dp))
            Hint("Quem acertou?")
            g.acceptedIds.filter { it != vm.myId }.forEach { id ->
                PersonRow(g.names[id] ?: "Alguém", trailing = { Text("→", color = Festa.hot, fontWeight = FontWeight.Bold) }) { vm.drawGuessed(id) }
            }
        }
    } else {
        Spacer(Modifier.height(10.dp))
        Hint("Fala o palpite em voz alta (chat de voz 🎤) — quem está desenhando marca quem acertou.")
    }
}

/** o quadro branco: mostra os traços (de quem estiver desenhando) e, se for a minha vez, desenha com o dedo */
@Composable
private fun DrawBoard(vm: PartyViewModel, interactive: Boolean) {
    Box(
        Modifier.fillMaxWidth().aspectRatio(BOARD_W / BOARD_H).clip(RoundedCornerShape(12.dp)).background(Color.White),
    ) {
        Canvas(
            Modifier.fillMaxSize().then(
                if (!interactive) Modifier else Modifier
                    .pointerInput(Unit) {
                        detectTapGestures { o ->
                            val p = DrawPoint(o.x * BOARD_W / size.width, o.y * BOARD_H / size.height)
                            vm.drawLocal(p, newStroke = true)
                            vm.drawLocal(DrawPoint(p.x + 0.5f, p.y + 0.5f), newStroke = false) // um pontinho
                        }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { o -> vm.drawLocal(DrawPoint(o.x * BOARD_W / size.width, o.y * BOARD_H / size.height), newStroke = true) },
                        ) { change, _ ->
                            change.consume()
                            val o = change.position
                            vm.drawLocal(
                                DrawPoint((o.x * BOARD_W / size.width).coerceIn(0f, BOARD_W), (o.y * BOARD_H / size.height).coerceIn(0f, BOARD_H)),
                                newStroke = false,
                            )
                        }
                    }
            ),
        ) {
            val sx = size.width / BOARD_W
            val sy = size.height / BOARD_H
            for (stroke in vm.drawStrokes) {
                val pts = stroke.points
                val color = parseColor(stroke.color)
                val w = stroke.width * sx
                if (pts.size == 1) drawCircle(color, radius = w / 2, center = Offset(pts[0].x * sx, pts[0].y * sy))
                for (i in 1 until pts.size) {
                    drawLine(color, Offset(pts[i - 1].x * sx, pts[i - 1].y * sy), Offset(pts[i].x * sx, pts[i].y * sy), strokeWidth = w, cap = StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
private fun FinishedScreen(vm: PartyViewModel, g: DrawGame, onClose: () -> Unit) {
    Hint("🏆 Fim de jogo! Ranking final:")
    g.scores.entries.sortedByDescending { it.value }.forEachIndexed { i, (id, pts) ->
        Row(
            Modifier.padding(vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(if (i == 0) Festa.amber.copy(alpha = 0.12f) else Festa.panel)
                .border(1.dp, if (i == 0) Festa.amber.copy(alpha = 0.4f) else Festa.borderSoft, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${i + 1}º", fontFamily = Festa.mono, color = Festa.amber, fontSize = 13.sp, modifier = Modifier.width(30.dp))
            Text(if (i == 0) "🏆" else avatarFor(g.names[id] ?: "?"), fontSize = 18.sp)
            Spacer(Modifier.width(10.dp))
            Text(g.names[id] ?: "Alguém", color = Festa.textLight, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text("$pts pts", fontFamily = Festa.mono, color = Festa.hot, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
    Spacer(Modifier.height(12.dp))
    // só quem organizou zera o jogo pra todo mundo (igual o site); os outros só fecham a tela
    HotButton("Fechar", Modifier.fillMaxWidth(), fill = true) { if (vm.myId == g.hostId) vm.drawCancel(); onClose() }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Festa.panel2)
            .border(1.dp, Festa.borderMid, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Festa.textLight, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
}
