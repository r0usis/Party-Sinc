package dev.partykit.r0usis.festasync.ui

import android.content.Intent
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import dev.partykit.r0usis.festasync.PartyViewModel
import dev.partykit.r0usis.festasync.net.avatarFor

enum class RoomTab(val icon: String, val label: String) { Music("🎵", "Música"), Games("🎮", "Jogos"), Chat("💬", "Chat") }

/** onde o player fica na aba Música — a aba reserva esse espaço e o player é desenhado por cima */
val PlayerSlotModifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp).fillMaxWidth().aspectRatio(16f / 9f)

@Composable
fun RoomScreen(vm: PartyViewModel, onBackground: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(RoomTab.Music) }
    var confirmLeave by remember { mutableStateOf(false) }

    // mensagens novas dos outros enquanto a pessoa está fora da aba Chat (o histórico que já
    // estava lá quando ela entrou não conta)
    val chatLog = vm.state?.chatLog.orEmpty()
    var chatSeen by remember { mutableIntStateOf(-1) }
    LaunchedEffect(tab, chatLog.size) {
        if (chatSeen < 0 || tab == RoomTab.Chat || chatSeen > chatLog.size) chatSeen = chatLog.size
    }
    val unread = if (chatSeen < 0 || tab == RoomTab.Chat) 0 else chatLog.drop(chatSeen).count { it.clientId != vm.myId }

    BackHandler {
        if (tab != RoomTab.Music) tab = RoomTab.Music else onBackground()
    }

    Column(Modifier.fillMaxSize().background(Festa.bgDeep).statusBarsPadding()) {
        TopBar(vm, onLeave = { confirmLeave = true })
        if (vm.connectionLost) {
            Text(
                "⚠️ conexão com o servidor caiu — reconectando...",
                style = Festa.label.copy(color = Festa.amber),
                modifier = Modifier.fillMaxWidth().background(Festa.amber.copy(alpha = 0.08f)).padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                RoomTab.Music -> MusicTab(vm)
                RoomTab.Games -> GamesTab()
                RoomTab.Chat -> ChatTab(vm)
            }
            // O player do YouTube NUNCA sai da tela de verdade: nas outras abas ele só é
            // empurrado pra fora da área visível (mesmo tamanho) — assim a música continua
            // tocando enquanto a pessoa está no chat/jogos, sem recarregar o vídeo.
            YouTubeHost(vm, PlayerSlotModifier.offset { if (tab == RoomTab.Music) IntOffset.Zero else IntOffset(0, 100_000) })
            if (tab == RoomTab.Music) PlayerOverlay(vm, PlayerSlotModifier)
        }
        BottomNav(tab, unread) { tab = it }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            containerColor = Festa.panel,
            title = { Text("Sair da festa?") },
            text = { Text("A música para aqui no seu celular. Dá pra voltar depois com o mesmo código e senha.", color = Festa.textDim) },
            confirmButton = { TextButton(onClick = { confirmLeave = false; vm.leave() }) { Text("Sair", color = Festa.hot) } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Ficar", color = Festa.textDim) } },
        )
    }
}

@Composable
private fun TopBar(vm: PartyViewModel, onLeave: () -> Unit) {
    val context = LocalContext.current
    val s = vm.state
    Column(
        Modifier.fillMaxWidth().drawBehind {
            drawLine(Festa.borderSoft, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        }.padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Logo(26)
            Spacer(Modifier.width(10.dp))
            // código da sala — tocar abre o "compartilhar" do Android (WhatsApp etc.)
            Row(
                Modifier.clip(CircleShape).background(Festa.panel).border(1.dp, Festa.amber.copy(alpha = 0.28f), CircleShape)
                    .clickable {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, "Bora pra festa no Festa Sync! 🎉 Sala ${vm.room}: ${vm.roomLink}")
                        context.startActivity(Intent.createChooser(send, "Chamar gente pra festa"))
                    }
                    .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(vm.room, fontFamily = Festa.mono, fontSize = 12.sp, color = Festa.amber, letterSpacing = 1.6.sp, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                Text("↗", color = Festa.textDim, fontSize = 13.sp)
            }
            Spacer(Modifier.weight(1f))
            Text(
                "Sair", color = Festa.textFaint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onLeave).padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val playing = s?.isPlaying == true
            val host = s?.hostName
            val text = when {
                host == null -> "NINGUÉM TOCOU AINDA"
                playing -> "NO AR · TOCANDO POR ${host.uppercase()}"
                else -> "TOCANDO POR ${host.uppercase()}"
            }
            Row(
                Modifier.weight(1f, fill = false).clip(CircleShape)
                    .background(if (playing) Festa.hot.copy(alpha = 0.08f) else Festa.panel.copy(alpha = 0.4f))
                    .border(1.dp, if (playing) Festa.hot.copy(alpha = 0.35f) else Festa.borderSoft, CircleShape)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (playing) Festa.hot else Festa.textGhost))
                Spacer(Modifier.width(7.dp))
                Text(text, style = Festa.label.copy(fontSize = 9.sp, color = if (playing) Festa.hot else Festa.textFaint), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            // avatares de quem está na sala (até 4, depois "+N") — com anel rosa em quem está falando
            val shown = vm.members.take(4)
            Row(horizontalArrangement = Arrangement.spacedBy((-4).dp)) {
                shown.forEach { m -> SpeakingAvatar(vm, m, 26.dp) }
                val extra = vm.members.size - shown.size
                if (extra > 0) {
                    Box(Modifier.size(26.dp).clip(CircleShape).background(Festa.panel3).border(2.dp, Festa.bgDeep, CircleShape), contentAlignment = Alignment.Center) {
                        Text("+$extra", fontFamily = Festa.mono, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Festa.textFaint)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            // microfone aqui em cima (e não só no chat): dá pra falar de qualquer aba
            MicButton(vm, 38.dp)
        }
    }
}

@Composable
private fun BottomNav(tab: RoomTab, unreadChat: Int, onSelect: (RoomTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Festa.bgDeep).drawBehind {
            drawLine(Festa.borderSoft, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
        }.navigationBarsPadding().height(62.dp),
    ) {
        RoomTab.entries.forEach { t ->
            val on = t == tab
            Box(Modifier.weight(1f).fillMaxSize().clickable { onSelect(t) }, contentAlignment = Alignment.Center) {
                if (on) Box(Modifier.align(Alignment.TopCenter).width(48.dp).height(2.dp).background(Festa.hot))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(t.icon, fontSize = if (on) 22.sp else 20.sp, modifier = Modifier.then(if (on) Modifier else Modifier.padding(top = 1.dp)))
                    Text(t.label.uppercase(), style = Festa.label.copy(color = if (on) Festa.hot else Festa.textFaint))
                }
                if (t == RoomTab.Chat && unreadChat > 0) {
                    Box(
                        Modifier.align(Alignment.Center).offset(x = 16.dp, y = (-14).dp).size(18.dp).clip(CircleShape).background(Festa.hot),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (unreadChat > 9) "9+" else "$unreadChat", color = Festa.onHot, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// O player do YouTube (a única parte "web" do app — o YouTube só deixa tocar vídeo pelo
// player dele). Criado uma vez só e ligado ao PartyViewModel, que decide o que tocar.
@Composable
private fun YouTubeHost(vm: PartyViewModel, modifier: Modifier) {
    val context = LocalContext.current
    val view = remember {
        YouTubePlayerView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            enableAutomaticInitialization = false
            val options = IFramePlayerOptions.Builder(context)
                .controls(0).rel(0).ivLoadPolicy(3).ccLoadPolicy(0).fullscreen(0)
                .build()
            initialize(object : AbstractYouTubePlayerListener() {
                override fun onReady(youTubePlayer: YouTubePlayer) = vm.onPlayerReady(youTubePlayer)
                override fun onStateChange(youTubePlayer: YouTubePlayer, state: PlayerConstants.PlayerState) = vm.onPlayerState(state)
                override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) = vm.onPlayerSecond(second)
                override fun onVideoDuration(youTubePlayer: YouTubePlayer, duration: Float) = vm.onPlayerDuration(duration)
                override fun onError(youTubePlayer: YouTubePlayer, error: PlayerConstants.PlayerError) = vm.onPlayerError(error)
            }, options)
        }
    }
    DisposableEffect(view) {
        onDispose { vm.onPlayerReleased(); view.release() }
    }
    AndroidView(factory = { view }, modifier = modifier.clip(RoundedCornerShape(18.dp)))
}
