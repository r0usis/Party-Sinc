package dev.partykit.r0usis.festasync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import dev.partykit.r0usis.festasync.net.avatarFor
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.partykit.r0usis.festasync.PartyViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class GameInfo(val key: String, val icon: ImageVector, val name: String, val desc: String, val ready: Boolean)

private val GAMES = listOf(
    GameInfo("draw", FestaIcons.pencil, "Jogo de desenho", "Desenhe e a sala adivinha", ready = true),
    GameInfo("mimic", FestaIcons.mic, "Mimic Party", "Imite o som e ganhe a nota", ready = true),
    GameInfo("hangman", FestaIcons.forca, "Forca", "Adivinhe a palavra letra por letra", ready = false),
    GameInfo("contexto", FestaIcons.target, "Jogo do Contexto", "Ache a palavra pela proximidade", ready = false),
    GameInfo("stop", FestaIcons.wheel, "Roleta de categorias", "Stop com tema sorteado", ready = false),
    GameInfo("2048", FestaIcons.grid, "2048", "Junte os números até 2048", ready = false),
)

/** tem convite de jogo esperando resposta minha (pra acender a aba Jogos) */
fun hasGameInvite(vm: PartyViewModel): Boolean {
    val s = vm.state ?: return false
    return vm.myId in s.drawGame.invitedIds || vm.myId in s.mimicGame.invitedIds
}

private fun invitedTo(vm: PartyViewModel, key: String): Boolean = when (key) {
    "draw" -> vm.myId in (vm.state?.drawGame?.invitedIds ?: emptyList())
    "mimic" -> vm.myId in (vm.state?.mimicGame?.invitedIds ?: emptyList())
    else -> false
}

/** jogo rolando de verdade (não conta convite nem tela final) */
private fun runningNow(vm: PartyViewModel, key: String): Boolean = when (key) {
    "draw" -> (vm.state?.drawGame?.phase ?: "idle") !in listOf("idle", "inviting", "finished")
    "mimic" -> (vm.state?.mimicGame?.phase ?: "idle") !in listOf("idle", "inviting", "finished")
    else -> false
}

private data class LiveInfo(val ids: List<String>, val names: Map<String, String>, val round: String)

private fun liveInfo(vm: PartyViewModel, key: String): LiveInfo? {
    val s = vm.state ?: return null
    return when (key) {
        "draw" -> s.drawGame.let { LiveInfo(it.order.ifEmpty { it.acceptedIds }, it.names, "${it.round}/3") }
        "mimic" -> s.mimicGame.let { LiveInfo(it.order.ifEmpty { it.acceptedIds }, it.names, "${it.round}/${it.totalRounds}") }
        else -> null
    }
}

// 6e — lista de jogos com o que está rolando em destaque. Os que ainda não chegaram no app
// aparecem com "No site" (dá pra jogar eles pelo site).
@Composable
fun GamesTab(vm: PartyViewModel) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    androidx.activity.compose.BackHandler(enabled = open != null) { open = null }
    if (open == "draw") { DrawGameScreen(vm) { open = null }; return }
    if (open == "mimic") { MimicGameScreen(vm) { open = null }; return }

    val live = GAMES.firstOrNull { runningNow(vm, it.key) }
    LazyColumn(
        Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Festa.hot.copy(alpha = 0.07f), 0.25f to Festa.bgDeep)),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 20.dp),
    ) {
        item {
            Text("JOGOS", fontFamily = Festa.display, fontSize = 30.sp, color = Festa.textLight)
            Text("Jogue com quem tá na sala — a música continua.", color = Festa.textDim, fontSize = 13.5.sp, modifier = Modifier.padding(top = 2.dp, bottom = 16.dp))
        }
        if (live != null) item { FeaturedGame(vm, live) { open = live.key } }
        item { Text("TODOS OS JOGOS", style = Festa.label, modifier = Modifier.padding(bottom = 4.dp)) }
        items(GAMES.filter { it != live }) { g ->
            val invited = invitedTo(vm, g.key)
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(Festa.panel2)
                        .border(1.dp, if (invited) Festa.hot else Festa.borderSoft, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) { FIcon(g.icon, 22.dp, if (g.ready) Festa.textMid else Festa.textFaint) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(g.name, color = Festa.textLight, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(g.desc, color = Festa.textDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (invited) Text("TE CONVIDARAM!", style = Festa.label.copy(color = Festa.hot, fontSize = 9.sp), modifier = Modifier.padding(top = 3.dp))
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.height(34.dp).clip(CircleShape)
                        .then(if (invited) Modifier.background(Festa.hotGradient) else Modifier.border(1.dp, if (g.ready) Festa.hot.copy(alpha = 0.45f) else Festa.borderSoft, CircleShape))
                        .clickable(enabled = g.ready) { open = g.key }.padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when { invited -> "Ver convite"; g.ready -> "Jogar"; else -> "No site" },
                        color = when { invited -> Festa.onHot; g.ready -> Festa.textLight; else -> Festa.textFaint },
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Festa.borderSoft))
        }
    }
}

@Composable
private fun FeaturedGame(vm: PartyViewModel, g: GameInfo, onOpen: () -> Unit) {
    val info = liveInfo(vm, g.key) ?: return
    val names = info.ids.map { if (it == vm.myId) "você" else info.names[it] ?: vm.members.find { m -> m.clientId == it }?.name ?: "Alguém" }
    val who = if (names.size > 1) names.dropLast(1).joinToString(", ") + " e " + names.last() else names.firstOrNull().orEmpty()
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "live")
    val a by pulse.animateFloat(0.35f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(700), androidx.compose.animation.core.RepeatMode.Reverse), label = "a")
    Column(
        Modifier.padding(bottom = 20.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Festa.panel)
            .border(1.dp, Festa.hot.copy(alpha = 0.35f), RoundedCornerShape(20.dp)).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(Festa.hotGradient), contentAlignment = Alignment.Center) {
                FIcon(g.icon, 24.dp, Festa.onHot)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(g.name, color = Festa.textLight, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(Festa.hot.copy(alpha = a)))
                    Spacer(Modifier.width(6.dp))
                    Text("ROLANDO AGORA · RODADA ${info.round}", style = Festa.label.copy(color = Festa.hot, fontSize = 9.5.sp))
                }
            }
        }
        Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy((-7).dp)) {
                info.ids.take(4).forEach { id ->
                    Box(Modifier.size(26.dp).clip(CircleShape).background(Festa.panel3).border(2.dp, Festa.panel, CircleShape), contentAlignment = Alignment.Center) {
                        Text(avatarFor(info.names[id] ?: "?"), fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            Text("$who ${if (names.size == 1) "está" else "estão"} jogando", color = Festa.textDim, fontSize = 13.sp, maxLines = 2)
        }
        HotButton("Entrar na partida", Modifier.height(48.dp), fill = true, onClick = onOpen)
    }
}
