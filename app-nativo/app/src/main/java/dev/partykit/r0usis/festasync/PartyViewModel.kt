package dev.partykit.r0usis.festasync

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import dev.partykit.r0usis.festasync.net.Member
import dev.partykit.r0usis.festasync.net.PartyConnection
import dev.partykit.r0usis.festasync.net.PlaybackState
import dev.partykit.r0usis.festasync.net.ProtocolJson
import dev.partykit.r0usis.festasync.net.QueueItem
import dev.partykit.r0usis.festasync.net.SERVER_HOST
import dev.partykit.r0usis.festasync.net.parseVideoId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

// Tudo que a sala sabe e faz, num lugar só. A sincronização do player é a MESMA lógica do
// site (loadVideo / driftCorrect / handleVideoEnded em public/index.html), portada pra cá —
// se mudar uma, vale conferir a outra, senão app e site passam a tocar diferente.
class PartyViewModel(app: Application) : AndroidViewModel(app), PartyConnection.Listener {

    enum class Screen { Join, Room }

    private val prefs = app.getSharedPreferences("festaSync", Context.MODE_PRIVATE)

    // id da pessoa — sobrevive a reconexões e a fechar o app (igual festaSync.meuId do site)
    val myId: String = prefs.getString("meuId", null) ?: run {
        val id = (1..8).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("") +
            System.currentTimeMillis().toString(36)
        prefs.edit().putString("meuId", id).apply()
        id
    }
    val savedName: String get() = prefs.getString("meuNome", "") ?: ""

    var screen by mutableStateOf(Screen.Join); private set
    var joining by mutableStateOf(false); private set
    var joinError by mutableStateOf<String?>(null); private set
    var room by mutableStateOf(""); private set
    var state by mutableStateOf<PlaybackState?>(null); private set
    var members by mutableStateOf<List<Member>>(emptyList()); private set
    var maxPeople by mutableIntStateOf(0); private set
    var connectionLost by mutableStateOf(false); private set

    // tempo/duração mostrados na barra de progresso
    var elapsed by mutableDoubleStateOf(0.0); private set
    var duration by mutableDoubleStateOf(0.0); private set

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts
    private fun toast(msg: String) { _toasts.tryEmit(msg) }

    private val connection = PartyConnection(this)
    private val http = OkHttpClient.Builder().callTimeout(4, TimeUnit.SECONDS).build()

    init {
        // "relógio" da sincronização: corrige desvio e atualiza a barra de progresso
        viewModelScope.launch {
            while (isActive) {
                delay(500)
                driftCorrect()
                elapsed = if (loadedVideoId != null) myPlayerTime() else 0.0
            }
        }
    }

    // ---------------- entrar / sair ----------------

    fun join(name: String, code: String, password: String) = connect(name, code, password, create = false, maxPeople = 0)
    fun create(name: String, code: String, password: String, maxPeople: Int) = connect(name, code, password, create = true, maxPeople = maxPeople)

    private fun connect(name: String, code: String, password: String, create: Boolean, maxPeople: Int) {
        val cleanName = name.trim().take(24)
        when {
            cleanName.isEmpty() -> { joinError = "Coloca teu nome primeiro 😉"; return }
            code.isEmpty() -> { joinError = "Falta o código da sala."; return }
        }
        prefs.edit().putString("meuNome", cleanName).apply()
        joinError = null
        joining = true
        room = code
        connection.connect(
            PartyConnection.Params(
                room = code, name = cleanName, clientId = myId, password = password.take(64),
                create = create, maxPeople = maxPeople.coerceIn(2, 50),
            )
        )
    }

    fun leave() {
        connection.disconnect()
        player?.pause()
        loadedVideoId = null
        localPlaying = false
        state = null
        members = emptyList()
        connectionLost = false
        joining = false
        screen = Screen.Join
    }

    val roomLink: String get() = "https://$SERVER_HOST/?room=$room"

    override fun onConnected() { connectionLost = false }

    override fun onState(state: PlaybackState) {
        if (screen != Screen.Room) { screen = Screen.Room; joining = false }
        handleState(state)
    }

    override fun onMembers(members: List<Member>, maxPeople: Int) {
        this.members = members
        this.maxPeople = maxPeople
    }

    // sala não existe / senha errada / lotada / expulso: volta (ou fica) na tela de entrar
    // com o motivo que o servidor deu
    override fun onRejected(reason: String) {
        if (screen == Screen.Room) leave()
        joining = false
        joinError = reason
    }

    fun clearJoinError() { joinError = null }

    override fun onConnectionLost() {
        if (screen == Screen.Room) connectionLost = true
    }

    // ---------------- fila / chat ----------------

    fun addToQueue(raw: String, isLive: Boolean) {
        val videoId = parseVideoId(raw)
        if (videoId == null) { toast("Não reconheci esse link do YouTube 😕"); return }
        viewModelScope.launch {
            val meta = fetchMeta(videoId)
            connection.send("addQueue", buildJsonObject {
                put("videoId", videoId); put("title", meta.title); put("thumb", meta.thumb)
                put("artist", meta.artist); put("isLive", isLive)
            })
            toast(if (isLive) "Live adicionada à fila 🔴" else "Música adicionada à fila 🎶")
        }
    }

    fun removeFromQueue(qid: String) = connection.send("removeQueue", buildJsonObject { put("qid", qid) })
    fun moveItem(qid: String, dir: Int) = connection.send("moveQueue", buildJsonObject { put("qid", qid); put("dir", dir) })

    fun sendChat(text: String, imageDataUrl: String?) {
        val t = text.trim().take(300)
        if (t.isEmpty() && imageDataUrl == null) return
        connection.send("chatMessage", buildJsonObject {
            put("text", t)
            if (imageDataUrl != null) put("image", imageDataUrl)
        })
    }

    private data class Meta(val title: String, val thumb: String, val artist: String)

    // mesmo oEmbed que o site usa em fetchMeta()
    private suspend fun fetchMeta(videoId: String): Meta = withContext(Dispatchers.IO) {
        val fallback = Meta("Vídeo $videoId", "https://img.youtube.com/vi/$videoId/mqdefault.jpg", "")
        try {
            val url = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json"
            http.newCall(Request.Builder().url(url).build()).execute().use { res ->
                if (!res.isSuccessful) return@withContext fallback
                val obj = ProtocolJson.parseToJsonElement(res.body!!.string()).jsonObject
                Meta(
                    title = obj["title"]?.jsonPrimitive?.content ?: fallback.title,
                    thumb = obj["thumbnail_url"]?.jsonPrimitive?.content ?: fallback.thumb,
                    artist = obj["author_name"]?.jsonPrimitive?.content ?: "",
                )
            }
        } catch (e: Exception) { fallback }
    }

    // ---------------- player sincronizado ----------------

    private var player: YouTubePlayer? = null
    private var pendingLoad: ((YouTubePlayer) -> Unit)? = null
    private var loadedVideoId: String? = null
    private var localPlaying = false
    private var localPos = 0.0
    private var localTs = 0L
    private var playerSecond = 0.0
    private var playerSecondKnown = false // o player já disse onde está no vídeo ATUAL
    private var lastEndedVideoId: String? = null
    private var lastAutoSeekAt = 0L
    private var skipCooldownOnce = false
    // logo depois de EU tocar/pausar/pular, o `state` ainda é o velho até o eco do servidor
    // chegar — sem essa folga o driftCorrect desfazia a própria ação da pessoa
    private var suppressDriftUntil = 0L
    private val DRIFT_THRESHOLD = 1.2
    private val DRIFT_COOLDOWN = 4000L

    fun onPlayerReady(p: YouTubePlayer) {
        player = p
        pendingLoad?.let { it(p); pendingLoad = null }
    }

    fun onPlayerReleased() { player = null }

    fun onPlayerState(s: PlayerConstants.PlayerState) {
        if (s == PlayerConstants.PlayerState.ENDED) handleVideoEnded()
        if (s == PlayerConstants.PlayerState.PLAYING && skipCooldownOnce) {
            skipCooldownOnce = false; lastAutoSeekAt = 0; driftCorrect()
        }
    }

    fun onPlayerSecond(sec: Float) { playerSecond = sec.toDouble(); playerSecondKnown = true }
    fun onPlayerDuration(d: Float) { duration = d.toDouble() }

    fun onPlayerError(e: PlayerConstants.PlayerError) {
        toast(when (e) {
            PlayerConstants.PlayerError.INVALID_PARAMETER_IN_REQUEST -> "Link do YouTube inválido 🚫"
            PlayerConstants.PlayerError.HTML_5_PLAYER -> "O player travou tentando tocar esse vídeo — tenta pular e voltar pra ele 🔁"
            PlayerConstants.PlayerError.VIDEO_NOT_FOUND -> "Esse vídeo foi removido ou é privado 🚫"
            PlayerConstants.PlayerError.VIDEO_NOT_PLAYABLE_IN_EMBEDDED_PLAYER -> "O dono desse vídeo não permite tocar incorporado (fora do YouTube) 🚫"
            else -> "Não consegui tocar esse vídeo aqui 🚫"
        })
    }

    private fun withPlayer(fn: (YouTubePlayer) -> Unit) { player?.let { try { fn(it) } catch (e: Exception) { } } }

    private fun loadVideo(videoId: String, startSeconds: Double, autoplay: Boolean, isLive: Boolean) {
        loadedVideoId = videoId
        localPos = if (isLive) 0.0 else startSeconds
        localTs = System.currentTimeMillis()
        localPlaying = autoplay
        playerSecondKnown = false
        duration = 0.0
        lastEndedVideoId = null
        skipCooldownOnce = true
        val start = if (isLive) 0f else startSeconds.toFloat()
        val doLoad: (YouTubePlayer) -> Unit = { p -> if (autoplay) p.loadVideo(videoId, start) else p.cueVideo(videoId, start) }
        val p = player
        if (p != null) doLoad(p) else pendingLoad = doLoad
    }

    private fun myPlayerTime(): Double {
        if (player != null && playerSecondKnown) return playerSecond
        return if (localPlaying) localPos + (System.currentTimeMillis() - localTs) / 1000.0 else localPos
    }

    private fun handleState(new: PlaybackState) {
        state = new
        val item = new.current
        if (item != null) {
            if (loadedVideoId != item.videoId) {
                loadVideo(item.videoId, new.estimatedPosition(), new.isPlaying, item.isLive)
            } else {
                if (new.isPlaying && !localPlaying) {
                    withPlayer { it.play() }
                    localPlaying = true; localTs = System.currentTimeMillis(); localPos = new.estimatedPosition()
                }
                if (!new.isPlaying && localPlaying) {
                    withPlayer { it.pause() }
                    localPlaying = false; localPos = new.position
                }
                driftCorrect()
            }
        } else if (loadedVideoId != null) {
            withPlayer { it.pause() }
            loadedVideoId = null
            localPlaying = false
            duration = 0.0
        }
    }

    private fun driftCorrect() {
        val s = state ?: return
        if (s.currentIndex < 0 || !s.isPlaying || !localPlaying) return
        if (s.screenSharerId != null) return // compartilhar tela pesa — corrigir só piora
        if (System.currentTimeMillis() < suppressDriftUntil) return
        val item = s.current ?: return
        if (item.videoId != loadedVideoId || item.isLive) return
        val target = s.estimatedPosition()
        if (abs(myPlayerTime() - target) > DRIFT_THRESHOLD && System.currentTimeMillis() - lastAutoSeekAt > DRIFT_COOLDOWN) {
            withPlayer { it.seekTo(target.toFloat()) }
            localPos = target; localTs = System.currentTimeMillis()
            playerSecond = target
            lastAutoSeekAt = System.currentTimeMillis()
        }
    }

    private fun handleVideoEnded() {
        val s = state ?: return
        if (s.currentIndex < 0) return
        val item = s.current
        if (item != null && lastEndedVideoId == item.videoId) return // dois aparelhos detectando o fim juntos
        if (item != null) lastEndedVideoId = item.videoId
        val next = s.currentIndex + 1
        if (next < s.queue.size) playIndex(next)
        else { localPlaying = false; connection.send("pause") }
    }

    fun playIndex(idx: Int) {
        val s = state ?: return
        val item: QueueItem = s.queue.getOrNull(idx) ?: return
        suppressDriftUntil = System.currentTimeMillis() + 1500
        loadVideo(item.videoId, 0.0, true, item.isLive)
        connection.send("playIndex", buildJsonObject { put("index", idx) })
    }

    fun playPause() {
        val s = state ?: return
        if (s.isPlaying) pause() else play()
    }

    private fun play() {
        val s = state ?: return
        if (s.currentIndex == -1) {
            if (s.queue.isNotEmpty()) playIndex(0) else toast("Adicione uma música na fila primeiro 🎵")
            return
        }
        suppressDriftUntil = System.currentTimeMillis() + 1500
        withPlayer { it.play() }
        localPlaying = true; localTs = System.currentTimeMillis()
        connection.send("play")
    }

    private fun pause() {
        val s = state ?: return
        if (s.currentIndex == -1) return
        suppressDriftUntil = System.currentTimeMillis() + 1500
        val mine = myPlayerTime()
        withPlayer { it.pause() }
        localPlaying = false; localPos = mine
        connection.send("pause")
    }

    fun seekBy(delta: Double) {
        val s = state ?: return
        if (s.currentIndex == -1) return
        suppressDriftUntil = System.currentTimeMillis() + 1500
        val newPos = max(0.0, myPlayerTime() + delta)
        withPlayer { it.seekTo(newPos.toFloat()) }
        localPos = newPos; localTs = System.currentTimeMillis()
        playerSecond = newPos
        elapsed = newPos
        connection.send("seek", buildJsonObject { put("delta", delta) })
    }

    fun seekTo(position: Double) = seekBy(position - myPlayerTime())

    fun previous() = playIndex((state?.currentIndex ?: 0) - 1)
    fun next() = playIndex((state?.currentIndex ?: -1) + 1)

    override fun onCleared() {
        connection.disconnect()
        super.onCleared()
    }
}
