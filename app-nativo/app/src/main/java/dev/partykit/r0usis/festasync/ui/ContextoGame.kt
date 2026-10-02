package dev.partykit.r0usis.festasync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.partykit.r0usis.festasync.PartyViewModel
import dev.partykit.r0usis.festasync.net.ContextoGame
import dev.partykit.r0usis.festasync.net.ContextoGuess
import dev.partykit.r0usis.festasync.net.avatarFor
import kotlinx.coroutines.delay
import kotlin.math.ln

// Jogo do Contexto — mesmas telas do site (renderContexto*Screen em public/index.html):
// convite → palpites (cada um volta com a posição; #1 é a palavra) → "a palavra era..." →
// ranking. Quem calcula a posição é o servidor; aqui é só a tela.

private const val CONTEXTO_ROUNDS = 5 // bate com CONTEXTO_MAX_ROUNDS do servidor
private const val CONTEXTO_MAX_RANK = 40000 // tamanho do vocabulário do servidor

/** o servidor manda rank 0 = a palavra; na tela é "#1" */
private fun shown(rank: Int?) = rank?.let { "#${it + 1}" } ?: "não conheço"
private fun heat(rank: Int?): Float {
    val d = (rank ?: return 0f) + 1
    return (1f - (ln(d.toDouble()) / ln(CONTEXTO_MAX_RANK + 1.0)).toFloat()).coerceAtLeast(0.06f)
}
private fun toneColor(rank: Int?): Color = when {
    rank == null -> Festa.textGhost
    rank < 100 -> Festa.hot
    rank < 1000 -> Festa.amber
    else -> Festa.textDim
}
private fun bestBy(g: ContextoGame): Map<String, ContextoGuess> {
    val best = mutableMapOf<String, ContextoGuess>()
    for (x in g.guesses) {
        val r = x.rank ?: continue
        val cur = best[x.byId]
        if (cur == null || r < (cur.rank ?: Int.MAX_VALUE)) best[x.byId] = x
    }
    return best
}

@Composable
fun ContextoGameScreen(vm: PartyViewModel, onClose: () -> Unit) {
    val g = vm.state?.contextoGame ?: ContextoGame()
    val me = vm.myId
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("←", color = Festa.textLight, fontSize = 20.sp, modifier = Modifier.clip(CircleShape).clickable(onClick = onClose).padding(8.dp))
            Spacer(Modifier.width(4.dp))
            FIcon(FestaIcons.target, 22.dp, Festa.hot)
            Spacer(Modifier.width(8.dp))
            Text("CONTEXTO", fontFamily = Festa.display, fontSize = 24.sp, color = Festa.textLight)
            if (g.phase == "playing" || g.phase == "roundEnd") {
                Spacer(Modifier.width(10.dp))
                Text(
                    "RODADA ${g.round}/$CONTEXTO_ROUNDS", style = Festa.label.copy(fontSize = 9.sp),
                    modifier = Modifier.clip(CircleShape).border(1.dp, Festa.borderMid, CircleShape).padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            if (g.phase != "idle" && (me in g.acceptedIds || me in g.invitedIds)) {
                Text(
                    "Sair", color = Festa.textFaint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { vm.contextoLeave() }.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp)) {
            when (g.phase) {
                "inviting" -> ContextoInviting(vm, g)
                "playing" -> ContextoPlaying(vm, g)
                "roundEnd" -> ContextoRoundEnd(vm, g)
                "finished" -> ContextoFinished(vm, g)
                else -> ContextoInvite(vm)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CHint(text: String, center: Boolean = false) {
    Text(text, color = Festa.textDim, fontSize = 14.sp, lineHeight = 20.sp, textAlign = if (center) TextAlign.Center else TextAlign.Start,
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
}

@Composable
private fun CPerson(name: String, selected: Boolean = false, trailing: @Composable () -> Unit = {}, onClick: (() -> Unit)? = null) {
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
private fun CSecondary(text: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp)).background(Festa.panel2)
            .border(1.dp, Festa.borderMid, RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Festa.textLight, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
}

private fun nameOf(vm: PartyViewModel, g: ContextoGame, id: String?) =
    g.names[id] ?: vm.members.find { it.clientId == id }?.name ?: "Alguém"

// ---------------- telas ----------------

@Composable
private fun ContextoInvite(vm: PartyViewModel) {
    val others = vm.members.filter { it.clientId != vm.myId }
    if (others.isEmpty()) { CHint("Chama mais gente pra sala antes de convidar pra jogar Contexto 🧩"); return }
    var picked by remember { mutableStateOf(setOf<String>()) }
    CHint("Escolhe quem você quer chamar — o app pensa numa palavra secreta e todo mundo tenta chegar nela mandando palpites, vendo o quão \"perto\" cada um chegou!")
    others.forEach { m ->
        val on = m.clientId in picked
        CPerson(m.name, selected = on, trailing = { Text(if (on) "✅" else "⬜", fontSize = 18.sp) }) { picked = if (on) picked - m.clientId else picked + m.clientId }
    }
    Spacer(Modifier.height(12.dp))
    HotButton("Convidar pra jogar", Modifier.height(50.dp), enabled = picked.isNotEmpty(), fill = true) { vm.contextoInvite(picked.toList()) }
}

@Composable
private fun ContextoInviting(vm: PartyViewModel, g: ContextoGame) {
    val me = vm.myId
    if (me in g.invitedIds) {
        CHint("🧩 ${nameOf(vm, g, g.hostId)} te chamou pra jogar Contexto!")
        HotButton("Aceitar", Modifier.height(50.dp), fill = true) { vm.contextoRespond(true) }
        Spacer(Modifier.height(8.dp))
        CSecondary("Recusar") { vm.contextoRespond(false) }
        return
    }
    val amHost = g.hostId == me
    if (!amHost && me !in g.acceptedIds) { CHint("🧩 ${nameOf(vm, g, g.hostId)} está organizando uma partida de Contexto."); return }
    CHint(if (amHost) "Convite enviado — assim que tiver gente suficiente, toca em Iniciar." else "Você topou! Esperando ${nameOf(vm, g, g.hostId)} iniciar o jogo...")
    (g.acceptedIds + g.invitedIds).forEach { id ->
        val ok = id in g.acceptedIds
        CPerson(nameOf(vm, g, id), selected = ok, trailing = {
            Text(if (ok) "✅ topou" else "⏳ esperando", style = Festa.label.copy(color = if (ok) Festa.hot else Festa.textFaint, fontSize = 9.sp))
        })
    }
    if (amHost) {
        Spacer(Modifier.height(12.dp))
        HotButton("Iniciar jogo", Modifier.height(50.dp), enabled = g.acceptedIds.size >= 2, fill = true) { vm.contextoBegin() }
        Spacer(Modifier.height(8.dp))
        CSecondary("Cancelar convite") { vm.contextoCancel() }
    }
}

@Composable
private fun ContextoPlaying(vm: PartyViewModel, g: ContextoGame) {
    val me = vm.myId
    if (me !in g.acceptedIds) {
        CHint("Tem uma partida de Contexto rolando entre ${g.acceptedIds.joinToString(", ") { nameOf(vm, g, it) }}.")
        return
    }
    val mine = g.guesses.filter { it.byId == me }
    val best = bestBy(g)
    val myBest = best[me]
    val rival = best.filterKeys { it != me }.values.minByOrNull { it.rank ?: Int.MAX_VALUE }
    var allMode by rememberSaveable { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    fun send() { if (text.isNotBlank()) { vm.contextoGuess(text); text = "" } }

    Text(
        "Ache a palavra secreta. O número diz a distância — #1 é a palavra.",
        color = Festa.textDim, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(bottom = 12.dp),
    )
    // seu mais perto (com o adversário mais perto embaixo)
    if (myBest != null) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Festa.panel)
                .border(1.dp, Festa.hot.copy(alpha = 0.35f), RoundedCornerShape(16.dp)).padding(14.dp),
        ) {
            Row {
                Text("SEU MAIS PERTO", style = Festa.label, modifier = Modifier.weight(1f))
                Text("${mine.size} ${if (mine.size == 1) "TENTATIVA" else "TENTATIVAS"}", style = Festa.label)
            }
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(myBest.word, color = Festa.textLight, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(shown(myBest.rank), fontFamily = Festa.mono, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = Festa.hot)
            }
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Festa.panel3)) {
                Box(Modifier.fillMaxWidth(heat(myBest.rank)).fillMaxHeight().clip(CircleShape).background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Festa.amber, Festa.hot))))
            }
            Spacer(Modifier.height(8.dp))
            val ahead = rival == null || (myBest.rank ?: 0) <= (rival.rank ?: Int.MAX_VALUE)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (rival != null) "${avatarFor(rival.byName)} ${rival.byName} ${shown(rival.rank)}" else "ninguém mais chegou perto ainda",
                    color = Festa.textDim, fontSize = 12.5.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(if (ahead) "VOCÊ TÁ NA FRENTE" else "${rival?.byName?.uppercase()} TÁ NA FRENTE", style = Festa.label.copy(fontSize = 9.sp))
            }
        }
    } else {
        Text(
            if (mine.isNotEmpty()) "Não conheço nenhuma das palavras que você tentou — tenta outra!" else "Manda o seu primeiro palpite 👇",
            color = Festa.textDim, fontSize = 13.5.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().dashedBorder(Festa.borderStrong, 14.dp).padding(16.dp),
        )
    }
    Spacer(Modifier.height(12.dp))
    // campo de palpite
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF07040A))
                .border(1.dp, Festa.hot.copy(alpha = 0.6f), RoundedCornerShape(14.dp)).padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) Text("seu palpite...", color = Festa.textGhost, fontSize = 15.sp)
            BasicTextField(
                value = text, onValueChange = { text = it.take(40) }, singleLine = true,
                textStyle = TextStyle(color = Festa.textLight, fontSize = 15.sp), cursorBrush = SolidColor(Festa.hot),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(8.dp))
        HotButton("Tentar", Modifier.height(48.dp)) { send() }
    }
    // última tentativa
    mine.maxByOrNull { it.ts }?.let { last ->
        Row(
            Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Festa.amber.copy(alpha = 0.06f))
                .border(1.dp, Festa.amber.copy(alpha = 0.3f), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("ÚLTIMA", style = Festa.label.copy(color = Festa.amber, fontSize = 9.sp))
            Spacer(Modifier.width(10.dp))
            Text(last.word, color = Festa.textLight, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(shown(last.rank), fontFamily = Festa.mono, fontSize = 13.sp, color = toneColor(last.rank))
        }
    }
    // lista: minhas | de todos
    Row(Modifier.padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.clip(CircleShape).border(1.dp, Festa.borderSoft, CircleShape).padding(3.dp)) {
            listOf(false to "MINHAS", true to "DE TODOS").forEach { (all, label) ->
                val on = allMode == all
                Text(
                    label, style = Festa.label.copy(fontSize = 9.sp, color = if (on) Festa.textLight else Festa.textFaint),
                    modifier = Modifier.clip(CircleShape).background(if (on) Festa.panel3 else Color.Transparent).clickable { allMode = all }.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Text("ORDEM: MAIS PERTO", style = Festa.label.copy(fontSize = 9.sp))
    }
    val list = if (allMode) g.guesses else mine
    if (list.isEmpty()) CHint(if (allMode) "Ninguém tentou nada ainda — manda o primeiro palpite!" else "Você ainda não tentou nada nessa rodada.", center = true)
    list.forEach { x -> GuessRow(x, showBy = allMode) }
}

@Composable
private fun GuessRow(x: ContextoGuess, showBy: Boolean) {
    val unknown = x.rank == null
    Box(
        Modifier.padding(vertical = 3.dp).fillMaxWidth().height(40.dp).clip(RoundedCornerShape(10.dp))
            .background(Festa.panel).border(1.dp, Festa.borderSoft, RoundedCornerShape(10.dp)),
    ) {
        if (!unknown) Box(Modifier.fillMaxWidth(heat(x.rank)).fillMaxHeight().background(toneColor(x.rank).copy(alpha = 0.14f)))
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                x.word, color = if (unknown) Festa.textFaint else Festa.textLight, fontSize = 14.sp, modifier = Modifier.weight(1f),
                textDecoration = if (unknown) TextDecoration.LineThrough else null, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (showBy) { Text(x.byName, color = Festa.textFaint, fontSize = 12.sp, maxLines = 1); Spacer(Modifier.width(10.dp)) }
            Text(if (unknown) "NÃO CONHEÇO" else shown(x.rank), fontFamily = Festa.mono, fontSize = if (unknown) 9.sp else 13.sp, fontWeight = FontWeight.Bold, color = toneColor(x.rank))
        }
    }
}

@Composable
private fun ContextoRoundEnd(vm: PartyViewModel, g: ContextoGame) {
    val r = g.lastRoundResult ?: run { CHint("Preparando a próxima rodada...", center = true); return }
    val count = g.guesses.groupingBy { it.byId }.eachCount()
    val best = bestBy(g)
    val people = g.acceptedIds.filter { best[it] != null || (count[it] ?: 0) > 0 }.sortedBy { best[it]?.rank ?: Int.MAX_VALUE }
    val winnerCount = count[r.winnerId] ?: r.guessCount
    // o servidor troca de rodada 4s depois do acerto
    val endsAt = (g.guesses.firstOrNull { it.rank == 0 }?.ts ?: System.currentTimeMillis()) + 4000
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(g.round) { while (now < endsAt) { delay(250); now = System.currentTimeMillis() } }
    val lastRound = g.round >= CONTEXTO_ROUNDS

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Festa.panel)
            .border(1.dp, Festa.hot.copy(alpha = 0.35f), RoundedCornerShape(18.dp)).padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("A PALAVRA ERA", style = Festa.label)
        Text(r.word.uppercase(), fontFamily = Festa.display, fontSize = 44.sp, color = Festa.hot)
        Text(
            "${avatarFor(r.winnerName)} ${if (r.winnerId == vm.myId) "você achou" else "${r.winnerName} achou"} em $winnerCount ${if (winnerCount == 1) "tentativa" else "tentativas"}",
            color = Festa.textDim, fontSize = 14.sp,
        )
    }
    Spacer(Modifier.height(16.dp))
    Text("QUEM CHEGOU MAIS PERTO", style = Festa.label, modifier = Modifier.padding(bottom = 6.dp))
    people.forEachIndexed { i, id ->
        val won = id == r.winnerId
        val b = best[id]
        val sub = if (won) "Acertou · ${count[id] ?: 0} ${if ((count[id] ?: 0) == 1) "tentativa" else "tentativas"}" else b?.let { "Melhor: ${it.word} ${shown(it.rank)}" } ?: "Nenhuma palavra conhecida"
        Row(Modifier.padding(vertical = 5.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${i + 1}º", fontFamily = Festa.mono, fontSize = 12.sp, color = if (won) Festa.amber else Festa.textFaint, modifier = Modifier.width(30.dp))
            Text(avatarFor(nameOf(vm, g, id)), fontSize = 18.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(if (id == vm.myId) "você" else nameOf(vm, g, id), color = Festa.textLight, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(sub, color = Festa.textFaint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(if (won) "+${r.points}" else "+0", fontFamily = Festa.mono, fontSize = 13.sp, color = if (won) Festa.hot else Festa.textFaint)
        }
    }
    // palavras vizinhas (#2 a #5) que alguém tentou
    val chips = g.guesses.filter { (it.rank ?: 0) in 1..4 }.sortedBy { it.rank }.distinctBy { it.rank }
    if (chips.isNotEmpty()) {
        Text("PALAVRAS VIZINHAS", style = Festa.label, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            chips.forEach { x ->
                Text(
                    "${x.word} ${shown(x.rank)}", color = Festa.textMid, fontSize = 13.sp,
                    modifier = Modifier.clip(CircleShape).background(Festa.panel2).padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    val left = (endsAt - now).coerceAtLeast(0)
    Box(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).background(Festa.panel2)) {
        Box(Modifier.fillMaxWidth((1f - left / 4000f).coerceIn(0f, 1f)).fillMaxHeight().background(Festa.hot.copy(alpha = 0.18f)))
        Text(
            if (lastRound) "Fim do jogo →" else "Próxima rodada · ${g.round + 1}/$CONTEXTO_ROUNDS →",
            color = Festa.textLight, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center),
        )
    }
    Text(
        if (lastRound) "O resultado final vem já já" else "Começa sozinha em ${(left + 999) / 1000}s",
        style = Festa.label.copy(fontSize = 9.sp), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
}

@Composable
private fun ContextoFinished(vm: PartyViewModel, g: ContextoGame) {
    CHint("🏆 Fim de jogo! Ranking final:")
    g.scores.entries.sortedByDescending { it.value }.forEachIndexed { i, (id, pts) ->
        CPerson(nameOf(vm, g, id), selected = i == 0, trailing = {
            Text("$pts pts", fontFamily = Festa.mono, fontSize = 13.sp, color = if (i == 0) Festa.hot else Festa.textDim)
        })
    }
    Spacer(Modifier.height(12.dp))
    HotButton("Fechar", Modifier.height(50.dp), fill = true) { vm.contextoCancel() }
}
