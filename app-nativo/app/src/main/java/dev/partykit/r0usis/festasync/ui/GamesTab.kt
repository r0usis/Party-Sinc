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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class GameInfo(val emoji: String, val name: String)

private val GAMES = listOf(
    GameInfo("🎨", "Jogo de desenho"),
    GameInfo("🪢", "Forca"),
    GameInfo("🧩", "Jogo do Contexto"),
    GameInfo("🎤", "Mimic Party"),
    GameInfo("🎰", "Roleta de categorias"),
    GameInfo("🔢", "2048"),
)

// Parte 1 do app nativo: os jogos ainda não existem aqui — cada um entra numa parte própria.
@Composable
fun GamesTab() {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(2) }) {
            Text(
                "Os jogos estão chegando no app, um por vez. Por enquanto, dá pra jogar pelo site.",
                color = Festa.textDim, fontSize = 14.sp, lineHeight = 20.sp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        items(GAMES) { g ->
            FestaCard(Modifier.fillMaxWidth().heightIn(min = 110.dp).alpha(0.55f)) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(g.emoji, fontSize = 34.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(g.name, color = Festa.textDim, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, textAlign = TextAlign.Center)
                    Text("EM BREVE", style = Festa.label.copy(fontSize = 9.sp), modifier = Modifier.alpha(0.8f))
                }
            }
        }
    }
}
