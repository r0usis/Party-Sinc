package dev.partykit.r0usis.festasync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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

private data class GameInfo(val key: String, val emoji: String, val name: String, val ready: Boolean)

private val GAMES = listOf(
    GameInfo("draw", "🎨", "Jogo de desenho", ready = true),
    GameInfo("hangman", "🪢", "Forca", ready = false),
    GameInfo("contexto", "🧩", "Jogo do Contexto", ready = false),
    GameInfo("mimic", "🎤", "Mimic Party", ready = true),
    GameInfo("stop", "🎰", "Roleta de categorias", ready = false),
    GameInfo("2048", "🔢", "2048", ready = false),
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

private fun runningNow(vm: PartyViewModel, key: String): Boolean = when (key) {
    "draw" -> (vm.state?.drawGame?.phase ?: "idle") != "idle"
    "mimic" -> (vm.state?.mimicGame?.phase ?: "idle") != "idle"
    else -> false
}

// Os jogos vão chegando no app um por vez; os que ainda não chegaram aparecem "em breve"
// (dá pra jogar eles pelo site).
@Composable
fun GamesTab(vm: PartyViewModel) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    androidx.activity.compose.BackHandler(enabled = open != null) { open = null }
    if (open == "draw") { DrawGameScreen(vm) { open = null }; return }
    if (open == "mimic") { MimicGameScreen(vm) { open = null }; return }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(2) }) {
            Text(
                "Os outros jogos estão chegando no app, um por vez. Por enquanto, dá pra jogar eles pelo site.",
                color = Festa.textDim, fontSize = 14.sp, lineHeight = 20.sp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        items(GAMES) { g ->
            val invited = invitedTo(vm, g.key)
            val running = runningNow(vm, g.key)
            FestaCard(
                Modifier.fillMaxWidth().heightIn(min = 110.dp)
                    .then(if (g.ready) Modifier.clickable { open = g.key } else Modifier.alpha(0.55f))
                    .then(if (invited) Modifier.border(2.dp, Festa.hot, RoundedCornerShape(18.dp)) else Modifier),
            ) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(g.emoji, fontSize = 34.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(g.name, color = if (g.ready) Festa.textLight else Festa.textDim, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, textAlign = TextAlign.Center)
                    when {
                        invited -> Text("TE CONVIDARAM!", style = Festa.label.copy(fontSize = 9.sp, color = Festa.hot))
                        running -> Text("ROLANDO AGORA", style = Festa.label.copy(fontSize = 9.sp, color = Festa.amber))
                        !g.ready -> Text("EM BREVE", style = Festa.label.copy(fontSize = 9.sp), modifier = Modifier.alpha(0.8f))
                    }
                }
            }
        }
    }
}
