package dev.partykit.r0usis.festasync.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.partykit.r0usis.festasync.MimicController
import dev.partykit.r0usis.festasync.PartyViewModel
import dev.partykit.r0usis.festasync.net.MimicGame
import dev.partykit.r0usis.festasync.net.avatarFor
import kotlinx.coroutines.delay

// Mimic Party — mesmas telas do site (renderMimicPanel em public/index.html): convite com
// número de rodadas → palco com um bonequinho por pessoa → cada um ouve o som da rodada e
// grava a imitação na sua vez → nota de 0 a 100 na tela → ranking.

@Composable
fun MimicGameScreen(vm: PartyViewModel, onClose: () -> Unit) {
    val g = vm.state?.mimicGame ?: MimicGame()
    val me = vm.myId
    LaunchedEffect(Unit) { vm.mimic.loadLibrary() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("←", color = Festa.textLight, fontSize = 20.sp, modifier = Modifier.clip(CircleShape).clickable(onClick = onClose).padding(8.dp))
            Spacer(Modifier.width(4.dp))
            Text("🎤 Mimic Party", fontFamily = Festa.display, fontSize = 24.sp, color = Festa.textLight, modifier = Modifier.weight(1f))
            if (g.phase != "idle" && (me in g.acceptedIds || me in g.invitedIds)) {
                Text(
                    "Sair", color = Festa.textFaint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { vm.mimic.leave() }.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp)) {
            when (g.phase) {
                "inviting" -> MimicInviting(vm, g)
                "playing" -> MimicPlaying(vm, g)
                "turnResult" -> MimicTurnResult(vm, g)
                "finished" -> MimicFinished(vm, g, onClose)
                else -> MimicInvite(vm)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MHint(text: String, center: Boolean = false) {
    Text(text, color = Festa.textDim, fontSize = 14.sp, lineHeight = 20.sp, textAlign = if (center) TextAlign.Center else TextAlign.Start,
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
}

@Composable
private fun MPerson(name: String, selected: Boolean = false, trailing: @Composable () -> Unit = {}, onClick: (() -> Unit)? = null) {
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
private fun MSecondary(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Festa.panel2)
            .border(1.dp, Festa.borderMid, RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick).padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (enabled) Festa.textLight else Festa.textGhost, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
}

// ---------------- telas ----------------

@Composable
private fun MimicInvite(vm: PartyViewModel) {
    val others = vm.members.filter { it.clientId != vm.myId }
    if (others.isEmpty()) { MHint("Chama mais gente pra sala antes de convidar pro Mimic Party 🎤"); return }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var rounds by remember { mutableIntStateOf(3) }
    MHint("Cada rodada toca um som (cavalo, buzina, qualquer coisa!) e todo mundo tem que imitar, um de cada vez. O app dá uma nota de 0 a 100 pra cada imitação — quem somar mais ganha.")
    others.forEach { m ->
        val on = m.clientId in picked
        MPerson(m.name, selected = on, trailing = { Text(if (on) "✅" else "⬜", fontSize = 18.sp) }) { picked = if (on) picked - m.clientId else picked + m.clientId }
    }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("QUANTAS RODADAS?", style = Festa.label, modifier = Modifier.weight(1f))
        Text("−", color = Festa.textLight, fontSize = 22.sp, modifier = Modifier.clip(CircleShape).clickable { if (rounds > 1) rounds-- }.padding(horizontal = 14.dp, vertical = 4.dp))
        Text("$rounds", fontFamily = Festa.mono, color = Festa.amber, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("+", color = Festa.textLight, fontSize = 22.sp, modifier = Modifier.clip(CircleShape).clickable { if (rounds < 10) rounds++ }.padding(horizontal = 14.dp, vertical = 4.dp))
    }
    val lib = vm.mimic.library
    Text(
        when { lib == null -> "Carregando sons..."; lib.isEmpty() -> "⚠️ Nenhum som cadastrado ainda — cadastra pelo site 🎵."; else -> "${lib.size} ${if (lib.size == 1) "som disponível" else "sons disponíveis"} pra sortear." },
        color = Festa.textFaint, fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp),
    )
    HotButton("Convidar pra jogar 🎤", Modifier.fillMaxWidth(), enabled = picked.isNotEmpty() && !lib.isNullOrEmpty(), fill = true) { vm.mimic.invite(picked.toList(), rounds) }
}

@Composable
private fun MimicInviting(vm: PartyViewModel, g: MimicGame) {
    val me = vm.myId
    val host = g.names[g.hostId] ?: "Alguém"
    val rodadas = "${g.totalRounds} ${if (g.totalRounds == 1) "rodada" else "rodadas"}"
    if (me in g.invitedIds) {
        MHint("🎤 $host te chamou pro Mimic Party ($rodadas)!")
        HotButton("Aceitar ✅", Modifier.fillMaxWidth(), fill = true) { vm.mimic.respond(true) }
        Spacer(Modifier.height(8.dp))
        MSecondary("Recusar") { vm.mimic.respond(false) }
        return
    }
    val rows = @Composable {
        (g.acceptedIds + g.invitedIds).forEach { id ->
            val ok = id in g.acceptedIds
            MPerson(g.names[id] ?: vm.members.find { it.clientId == id }?.name ?: "Alguém", selected = ok,
                trailing = { Text(if (ok) "✅ topou" else "⏳ esperando", color = if (ok) Festa.hot else Festa.textDim, fontSize = 12.sp, fontFamily = Festa.mono) })
        }
    }
    when {
        me == g.hostId -> {
            MHint("Convite enviado — $rodadas. Assim que tiver gente suficiente, toca em Iniciar.")
            rows()
            Spacer(Modifier.height(12.dp))
            HotButton("Iniciar jogo 🎤", Modifier.fillMaxWidth(), enabled = g.acceptedIds.size >= 2 && !vm.mimic.library.isNullOrEmpty(), fill = true) { vm.mimic.begin() }
            Spacer(Modifier.height(8.dp))
            MSecondary("Cancelar convite") { vm.mimic.cancel() }
        }
        me in g.acceptedIds -> { MHint("Você topou! Esperando $host iniciar..."); rows() }
        else -> MHint("🎤 $host está organizando um Mimic Party.")
    }
}

@Composable
private fun RoundPill(g: MimicGame) {
    Text(
        "RODADA ${g.round}/${g.totalRounds}", style = Festa.label.copy(color = Festa.hot),
        modifier = Modifier.padding(bottom = 10.dp).clip(CircleShape).background(Festa.hot.copy(alpha = 0.10f))
            .border(1.dp, Festa.hot.copy(alpha = 0.35f), CircleShape).padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun SoundCard(vm: PartyViewModel, g: MimicGame) {
    Row(
        Modifier.padding(bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Festa.panel2)
            .border(1.dp, Festa.borderSoft, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val recording = vm.mimic.recState == "recording"
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(Festa.panel).border(1.dp, Festa.borderMid, CircleShape)
                .clickable(enabled = !recording) { vm.mimic.play(g.currentSound?.src) },
            contentAlignment = Alignment.Center,
        ) { Text("▶", color = if (recording) Festa.textGhost else Festa.textLight, fontSize = 14.sp) }
        Spacer(Modifier.width(10.dp))
        Column {
            Text("SOM DA RODADA", style = Festa.label)
            Text(g.currentSound?.nome ?: "som misterioso", color = Festa.textLight, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun MimicPlaying(vm: PartyViewModel, g: MimicGame) {
    val myTurn = g.currentPerformerId == vm.myId
    val context = LocalContext.current
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.mimic.startRecording() else Toast.makeText(context, "Sem permissão de microfone 🎙️", Toast.LENGTH_LONG).show()
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(g.turnStartedAt) { while (true) { now = System.currentTimeMillis(); delay(250) } }

    RoundPill(g)
    MimicStage(g)
    SoundCard(vm, g)
    when {
        myTurn -> when (vm.mimic.recState) {
            "recording" -> {
                val left = ((MimicController.REC_MAX_MS - (now - vm.mimic.recStartedAt)) / 1000 + 1).coerceIn(0, 6)
                Box(
                    Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(12.dp)).background(Festa.danger).clickable { vm.mimic.stopRecording() },
                    contentAlignment = Alignment.Center,
                ) { Text("⏹ Parar (${left}s)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            }
            "processing" -> MSecondary("⏳ Calculando sua nota...", enabled = false) {}
            else -> {
                MHint("É a sua vez! 🎤 Ouve o som (▶) e depois grava a sua imitação.", center = true)
                HotButton("🎙️ Gravar minha imitação", Modifier.fillMaxWidth().height(56.dp), fill = true) {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.mimic.startRecording()
                    else mic.launch(Manifest.permission.RECORD_AUDIO)
                }
                Spacer(Modifier.height(8.dp))
                MSecondary("Passar a vez") { vm.mimic.skip() }
            }
        }
        vm.myId in g.acceptedIds -> MHint("🎤 ${g.currentPerformerName ?: "Alguém"} está se preparando pra imitar...", center = true)
        else -> MHint("Tem um Mimic Party rolando entre ${g.acceptedIds.joinToString(", ") { g.names[it] ?: "?" }}.")
    }
    val left = ((g.turnStartedAt + MimicController.TURN_MS - now) / 1000).coerceAtLeast(0)
    Text("⏱ ${left}s pra essa vez acabar", fontFamily = Festa.mono, fontSize = 11.sp, color = Festa.textFaint, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
}

private fun scoreCaption(score: Int) = when {
    score >= 90 -> "Perfeito! Idêntico! 🤩"
    score >= 70 -> "Muito parecido! 👏"
    score >= 45 -> "Tá chegando lá 😄"
    score > 0 -> "Hmm... foi uma tentativa 😅"
    else -> "Passou a vez (ou o tempo acabou) 🙊"
}

@Composable
private fun MimicTurnResult(vm: PartyViewModel, g: MimicGame) {
    val lp = g.lastPerformance
    val key = "${g.round}:${lp?.clientId}"
    RoundPill(g)
    MimicStage(g)
    Text("${lp?.name ?: "Alguém"} tirou", color = Festa.textDim, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Text(
        "${lp?.score ?: 0}", fontFamily = Festa.display, fontSize = 80.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        style = androidx.compose.ui.text.TextStyle(brush = Brush.verticalGradient(listOf(Festa.hot, Festa.amber))),
    )
    Text(scoreCaption(lp?.score ?: 0), color = Festa.textDim, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) { MSecondary("▶ Original") { vm.mimic.play(g.currentSound?.src) } }
        Box(Modifier.weight(1f)) { MSecondary("▶ Imitação", enabled = vm.mimic.performances.containsKey(key)) { vm.mimic.play(vm.mimic.performances[key]) } }
    }
}

@Composable
private fun MimicFinished(vm: PartyViewModel, g: MimicGame, onClose: () -> Unit) {
    MHint("🏆 Fim do Mimic Party! Ranking final:")
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
    HotButton("Fechar", Modifier.fillMaxWidth(), fill = true) { vm.mimic.cancel(); onClose() }
}

// ---------------- palco com os bonequinhos ----------------

// mesma cor por nome que o site (mimicHue)
private fun hueFor(name: String): Float {
    var h = 0L
    for (ch in name) h = (h * 31 + ch.code) and 0xFFFFFFFFL
    return (h % 360).toFloat()
}

@Composable
private fun MimicStage(g: MimicGame) {
    Box(
        Modifier.padding(bottom = 14.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF1A0F24), Color(0xFF0E0814))))
            .border(1.dp, Festa.borderSoft, RoundedCornerShape(14.dp)),
    ) {
        // cortinas
        Box(Modifier.align(Alignment.CenterStart).width(14.dp).height(150.dp).background(Brush.horizontalGradient(listOf(Color(0xFF7A1636), Color(0xFF5A0F28)))))
        Box(Modifier.align(Alignment.CenterEnd).width(14.dp).height(150.dp).background(Brush.horizontalGradient(listOf(Color(0xFF5A0F28), Color(0xFF7A1636)))))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.Bottom,
        ) {
            g.order.forEach { id ->
                val name = g.names[id] ?: "Alguém"
                val performing = (id == g.currentPerformerId && g.phase == "playing") || (g.phase == "turnResult" && g.lastPerformance?.clientId == id)
                val sc = g.roundScores[id]
                Column(
                    Modifier.width(64.dp).offset(y = if (performing) (-8).dp else 0.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Puppet(name, performing, Modifier.size(width = if (performing) 58.dp else 48.dp, height = if (performing) 90.dp else 76.dp))
                    Text(name, color = Festa.textMid, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(sc?.toString() ?: "", fontFamily = Festa.mono, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Festa.amber)
                }
            }
        }
    }
}

@Composable
private fun Puppet(name: String, performing: Boolean, modifier: Modifier) {
    val hue = hueFor(name)
    Canvas(modifier) {
        val sx = size.width / 60f; val sy = size.height / 92f
        fun o(x: Float, y: Float) = Offset(x * sx, y * sy)
        if (performing) drawCircle(Color(0x33FFE6A0), radius = 34 * sx, center = o(30f, 50f))
        val arm = Color.hsl(hue, 0.6f, 0.42f)
        drawLine(arm, o(19f, 50f), o(11f, 64f), strokeWidth = 5 * sx, cap = StrokeCap.Round)
        drawLine(arm, o(41f, 50f), if (performing) o(45f, 40f) else o(49f, 64f), strokeWidth = 5 * sx, cap = StrokeCap.Round)
        drawRoundRect(Color.hsl(hue, 0.7f, 0.55f), topLeft = o(17f, 42f), size = Size(26 * sx, 36 * sy), cornerRadius = CornerRadius(12 * sx))
        val leg = Color.hsl(hue, 0.45f, 0.30f)
        drawRoundRect(leg, topLeft = o(21f, 76f), size = Size(7 * sx, 12 * sy), cornerRadius = CornerRadius(3 * sx))
        drawRoundRect(leg, topLeft = o(32f, 76f), size = Size(7 * sx, 12 * sy), cornerRadius = CornerRadius(3 * sx))
        drawCircle(Color(0xFFF3D6BF), radius = 13 * sx, center = o(30f, 25f))
        drawArc(Color.hsl((hue + 40) % 360, 0.45f, 0.28f), 180f, 180f, true, topLeft = o(17f, 11f), size = Size(26 * sx, 18 * sy))
        drawCircle(Color(0xFF2A1530), radius = 1.8f * sx, center = o(25.5f, 24.5f))
        drawCircle(Color(0xFF2A1530), radius = 1.8f * sx, center = o(34.5f, 24.5f))
        if (performing) drawOval(Color(0xFF3A1020), topLeft = o(26.8f, 27.4f), size = Size(6.4f * sx, 7.2f * sy))
        else drawLine(Color(0xFF3A1020), o(26.5f, 30.5f), o(33.5f, 30.5f), strokeWidth = 1.6f * sx, cap = StrokeCap.Round)
        // pedestal com o microfone na frente
        drawLine(Color(0xFF8A8494), o(30f, 90f), o(30f, 60f), strokeWidth = 2 * sx)
        drawLine(Color(0xFF8A8494), o(24f, 90f), o(36f, 90f), strokeWidth = 2.4f * sx, cap = StrokeCap.Round)
        drawRoundRect(Color(0xFF2B2733), topLeft = o(26.5f, 52f), size = Size(7 * sx, 10 * sy), cornerRadius = CornerRadius(3.5f * sx))
    }
}
