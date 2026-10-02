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
import androidx.compose.material3.Icon
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.ui.graphics.Color
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

/** tela cheia: deita o celular e esconde as barras do sistema (volta tudo ao sair) */
@Composable
private fun FullscreenWindowEffect(fullscreen: Boolean) {
    val activity = LocalContext.current as? android.app.Activity ?: return
    DisposableEffect(fullscreen) {
        val controller = androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (fullscreen) {
            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (fullscreen) {
                activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

/** nos "apps recentes" do Android o app aparece como "Mensagens" no modo trabalho */
@Composable
private fun WorkModeTaskLabel(on: Boolean) {
    val activity = LocalContext.current as? android.app.Activity ?: return
    LaunchedEffect(on) {
        @Suppress("DEPRECATION")
        activity.setTaskDescription(android.app.ActivityManager.TaskDescription(if (on) "Mensagens" else activity.getString(dev.partykit.r0usis.festasync.R.string.app_name)))
    }
}

enum class RoomTab(val label: String) { Music("Música"), Games("Jogos"), Chat("Chat") }

private fun RoomTab.icon() = when (this) { RoomTab.Music -> FestaIcons.music; RoomTab.Games -> FestaIcons.gamepad; RoomTab.Chat -> FestaIcons.chat }

/** onde o player fica na aba Música — a aba reserva esse espaço e o player é desenhado por
 *  cima. No design v2 o vídeo vai de ponta a ponta, sem margem nem canto arredondado. */
val PlayerSlotModifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)

@Composable
fun RoomScreen(vm: PartyViewModel, onBackground: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(RoomTab.Music) }
    var confirmLeave by remember { mutableStateOf(false) }
    // tela cheia do vídeo: celular deitado, sem barras do sistema, só o player
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    FullscreenWindowEffect(fullscreen)
    // modo trabalho ("Só áudio"): nada de tela cheia de vídeo. Continua na aba Música — a
    // tela de só áudio (6c) é lá.
    if (vm.workMode && fullscreen) fullscreen = false
    var showVolume by remember { mutableStateOf(false) }
    if (showVolume) VolumeSheet(vm) { showVolume = false }
    WorkModeTaskLabel(vm.workMode)
    // saiu da música (acabou a fila etc.) com a tela cheia ligada: volta ao normal
    if (fullscreen && vm.state?.current == null) fullscreen = false

    // mensagens novas dos outros enquanto a pessoa está fora da aba Chat (o histórico que já
    // estava lá quando ela entrou não conta)
    val chatLog = vm.state?.chatLog.orEmpty()
    var chatSeen by remember { mutableIntStateOf(-1) }
    LaunchedEffect(tab, chatLog.size) {
        if (chatSeen < 0 || tab == RoomTab.Chat || chatSeen > chatLog.size) chatSeen = chatLog.size
    }
    val unread = if (chatSeen < 0 || tab == RoomTab.Chat) 0 else chatLog.drop(chatSeen).count { it.clientId != vm.myId }

    // Android 13+: sem essa permissão a notificação "Na festa" (com os controles da música e o
    // "Sair da festa") não aparece — a festa continua no fundo, mas sem controle nenhum
    val notifPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    BackHandler {
        when {
            fullscreen -> fullscreen = false // "voltar" em tela cheia só sai da tela cheia
            tab != RoomTab.Music -> tab = RoomTab.Music
            else -> onBackground()
        }
    }

    Column(Modifier.fillMaxSize().background(if (fullscreen) Color.Black else Festa.bgDeep).then(if (fullscreen) Modifier else Modifier.statusBarsPadding())) {
        if (!fullscreen) TopBar(vm, onLeave = { confirmLeave = true }, onPeople = { tab = RoomTab.Chat })
        if (vm.connectionLost && !fullscreen) {
            Text(
                "⚠️ conexão com o servidor caiu — reconectando...",
                style = Festa.label.copy(color = Festa.amber),
                modifier = Modifier.fillMaxWidth().background(Festa.amber.copy(alpha = 0.08f)).padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (!fullscreen) when (tab) {
                RoomTab.Music -> MusicTab(vm, onVolume = { showVolume = true })
                RoomTab.Games -> GamesTab(vm)
                RoomTab.Chat -> ChatTab(vm, onVolume = { showVolume = true })
            }
            // O player do YouTube NUNCA sai da tela de verdade: nas outras abas ele só é
            // empurrado pra fora da área visível (mesmo tamanho) — assim a música continua
            // tocando enquanto a pessoa está no chat/jogos, sem recarregar o vídeo. Em tela
            // cheia é o MESMO player (mesma View), só que ocupando tudo — não recarrega nada.
            val slot = if (fullscreen) Modifier.fillMaxSize() else PlayerSlotModifier
            // modo trabalho: o player também sai da tela (a música continua)
            val showVideo = fullscreen || (tab == RoomTab.Music && !vm.workMode)
            YouTubeHost(vm, slot.offset { if (showVideo) IntOffset.Zero else IntOffset(0, 100_000) }, rounded = false)
            if (showVideo) PlayerOverlay(vm, slot, fullscreen) { fullscreen = !fullscreen }
        }
        if (!fullscreen) BottomNav(tab, unread, gameInvite = hasGameInvite(vm)) { tab = it }
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

/** 6 — topo de uma linha só (~56dp): logo, pílula da sala (abre copiar/compartilhar/sair),
 *  avatares (levam pro Chat, onde está a lista de gente) e o mic. */
@Composable
private fun TopBar(vm: PartyViewModel, onLeave: () -> Unit, onPeople: () -> Unit) {
    val context = LocalContext.current
    var roomSheet by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(56.dp).drawBehind {
            drawLine(Festa.borderSoft, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        }.padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Logo(22)
        Spacer(Modifier.width(10.dp))
        val playing = vm.state?.isPlaying == true
        val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse")
        val dotAlpha by pulse.animateFloat(
            0.35f, 1f,
            androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(700), androidx.compose.animation.core.RepeatMode.Reverse),
            label = "dot",
        )
        Row(
            Modifier.weight(1f, fill = false).height(32.dp).clip(CircleShape).background(Festa.panel)
                .border(1.dp, Festa.amber.copy(alpha = 0.28f), CircleShape)
                .clickable { roomSheet = true }
                .padding(start = 10.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (playing) Festa.hot.copy(alpha = dotAlpha) else Festa.textGhost))
            Spacer(Modifier.width(7.dp))
            Text(vm.room, fontFamily = Festa.mono, fontSize = 12.sp, color = Festa.amber, letterSpacing = 1.4.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(4.dp))
            FIcon(FestaIcons.chevron, 16.dp, Festa.textDim)
        }
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        // avatares de quem está na sala (até 3, depois "+N")
        val shown = vm.members.take(3)
        Row(
            Modifier.clip(CircleShape).clickable(onClick = onPeople).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy((-6).dp),
        ) {
            shown.forEach { m -> SpeakingAvatar(vm, m, 26.dp) }
            val extra = vm.members.size - shown.size
            if (extra > 0) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(Festa.panel3).border(2.dp, Festa.bgDeep, CircleShape), contentAlignment = Alignment.Center) {
                    Text("+$extra", fontFamily = Festa.mono, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Festa.textFaint)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        MicButton(vm, 38.dp)
    }

    if (roomSheet) FestaSheet({ roomSheet = false }) {
        SheetTitle("Sala ${vm.room}", "CHAMA A GALERA")
        SheetAction(FestaIcons.copy, "Copiar link da sala") {
            roomSheet = false
            val cm = context.getSystemService(android.content.ClipboardManager::class.java)
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("Festa Sync", vm.roomLink))
            android.widget.Toast.makeText(context, "Link da sala copiado!", android.widget.Toast.LENGTH_SHORT).show()
        }
        SheetAction(FestaIcons.link, "Compartilhar (WhatsApp etc.)") {
            roomSheet = false
            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "Bora pra festa no Festa Sync! 🎉 Sala ${vm.room}: ${vm.roomLink}")
            context.startActivity(Intent.createChooser(send, "Chamar gente pra festa"))
        }
        SheetAction(FestaIcons.logout, "Sair da sala", danger = true) { roomSheet = false; onLeave() }
        SheetDoneButton("Fechar") { roomSheet = false }
    }
}

/** abas com ícone 22dp + rótulo 11/600; ativa em rosa com "pílula" 60×32 atrás do ícone */
@Composable
private fun BottomNav(tab: RoomTab, unreadChat: Int, gameInvite: Boolean, onSelect: (RoomTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Festa.bgDeep).drawBehind {
            drawLine(Festa.borderSoft, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
        }.navigationBarsPadding().height(66.dp),
    ) {
        RoomTab.entries.forEach { t ->
            val on = t == tab
            val color = if (on) Festa.hot else Festa.textFaint
            Box(Modifier.weight(1f).fillMaxSize().clickable { onSelect(t) }, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.width(60.dp).height(32.dp).clip(CircleShape).background(if (on) Festa.hot.copy(alpha = 0.14f) else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(t.icon(), null, tint = color, modifier = Modifier.size(22.dp))
                        val badge = when {
                            t == RoomTab.Games && gameInvite -> "1"
                            t == RoomTab.Chat && unreadChat > 0 -> if (unreadChat > 9) "9+" else "$unreadChat"
                            else -> null
                        }
                        if (badge != null) {
                            Box(
                                Modifier.align(Alignment.TopEnd).offset(x = (-6).dp, y = (-2).dp)
                                    .border(2.dp, Festa.bgDeep, CircleShape).padding(2.dp)
                                    .size(16.dp).clip(CircleShape).background(Festa.hot),
                                contentAlignment = Alignment.Center,
                            ) { Text(badge, color = Festa.onHot, fontFamily = Festa.mono, fontSize = 9.5.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(t.label, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// O player do YouTube (a única parte "web" do app — o YouTube só deixa tocar vídeo pelo
// player dele). Criado uma vez só e ligado ao PartyViewModel, que decide o que tocar.
@Composable
private fun YouTubeHost(vm: PartyViewModel, modifier: Modifier, rounded: Boolean) {
    val context = LocalContext.current
    val view = remember {
        YouTubePlayerView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            enableAutomaticInitialization = false
            // não deixa o player "perceber" que o app saiu da tela — senão ele pausa sozinho
            // (o resto do segundo plano é o PlaybackService)
            enableBackgroundPlayback(true)
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
    AndroidView(factory = { view }, modifier = modifier.clip(RoundedCornerShape(if (rounded) 18.dp else 0.dp)))
}
