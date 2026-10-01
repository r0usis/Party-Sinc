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
import dev.partykit.r0usis.festasync.net.VoiceChat
import kotlinx.serialization.json.JsonObject
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
class PartyViewModel(app: Application) : AndroidViewModel(app), PartyConnection.Listener, PartyControls {

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

    // Modo trabalho: esconde o vídeo e as imagens, deixa a conversa em destaque (igual o
    // 💼 do site). Preferência só deste aparelho.
    var workMode by mutableStateOf(prefs.getBoolean("modoTrabalho", false)); private set
    fun toggleWorkMode() {
        workMode = !workMode
        prefs.edit().putBoolean("modoTrabalho", workMode).apply()
        if (screen == Screen.Room) PlaybackService.update(nowPlaying()) // notificação sem capa
    }

    // tempo/duração mostrados na barra de progresso
    var elapsed by mutableDoubleStateOf(0.0); private set
    var duration by mutableDoubleStateOf(0.0); private set

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts
    private fun toast(msg: String) { _toasts.tryEmit(msg) }

    private val connection = PartyConnection(this)

    // chat de voz (mesmo protocolo do site) — ver net/VoiceChat.kt
    val voice = VoiceChat(app, object : VoiceChat.Sender {
        override fun send(type: String, fields: JsonObject) = connection.send(type, fields)
        override fun toast(msg: String) = this@PartyViewModel.toast(msg)
        override fun nameOf(clientId: String) = members.find { it.clientId == clientId }?.name ?: "essa pessoa"
    }).also {
        it.setMyId(myId)
        it.changeVoiceGain(prefs.getFloat("volumeVozes", 1f))
    }

    // ---------------- volumes (música x vozes) ----------------
    // Antes a música e a voz saíam no mesmo volume do Android, sem jeito de abaixar uma sem
    // a outra. Agora: volume da música (player do YouTube), volume das vozes (VoiceChat) e
    // "abaixar a música quando alguém fala" — tudo só neste aparelho.
    var musicVolume by mutableIntStateOf(prefs.getInt("volumeMusica", 100)); private set
    var duckMusic by mutableStateOf(prefs.getBoolean("abaixarMusica", true)); private set
    var musicDucked by mutableStateOf(false); private set
    private var lastTalkAt = 0L
    private var appliedPlayerVolume = -1

    fun setMusicVolumeLevel(v: Int) {
        musicVolume = v.coerceIn(0, 100)
        prefs.edit().putInt("volumeMusica", musicVolume).apply()
        applyPlayerVolume()
    }
    fun setVoiceVolumeLevel(gain: Float) {
        voice.changeVoiceGain(gain)
        prefs.edit().putFloat("volumeVozes", voice.voiceGain).apply()
    }
    fun setDuckMusicEnabled(on: Boolean) {
        duckMusic = on
        prefs.edit().putBoolean("abaixarMusica", on).apply()
        applyPlayerVolume()
    }
    private fun applyPlayerVolume() {
        val now = System.currentTimeMillis()
        if (voice.someoneTalking()) lastTalkAt = now
        // segura abaixada 1,5s depois da última fala, pra não ficar subindo e descendo a cada pausa
        musicDucked = duckMusic && now - lastTalkAt < 1500
        val target = if (musicDucked) (musicVolume * 0.3f).toInt() else musicVolume
        if (target != appliedPlayerVolume) {
            player?.let { try { it.setVolume(target); appliedPlayerVolume = target } catch (e: Exception) { } }
        }
    }
    private val http = OkHttpClient.Builder().callTimeout(4, TimeUnit.SECONDS).build()

    init {
        // botões da notificação / da tela de bloqueio chegam aqui
        PlaybackService.controls = this
        // "relógio" da sincronização: corrige desvio e atualiza a barra de progresso
        viewModelScope.launch {
            while (isActive) {
                delay(250)
                applyPlayerVolume()
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
        PlaybackService.stop(getApplication())
        voice.reset()
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
        val entering = screen != Screen.Room
        if (entering) { screen = Screen.Room; joining = false }
        handleState(state)
        // entrou na sala: começa o serviço que mantém a festa tocando com o app no fundo
        if (entering) PlaybackService.start(getApplication(), nowPlaying()) else PlaybackService.update(nowPlaying())
    }

    override fun onMembers(members: List<Member>, maxPeople: Int) {
        this.members = members
        this.maxPeople = maxPeople
        voice.onMembers(members.map { it.clientId })
    }

    override fun onVoiceSignal(from: String, signal: JsonObject) = voice.onSignal(from, signal)
    override fun onVoiceStatus(clientId: String, speaking: Boolean) = voice.onVoiceStatus(clientId, speaking)

    /** o botão de mic: a permissão de microfone já tem que ter sido dada (ver RoomScreen) */
    fun toggleMic() {
        if (voice.micOn) voice.stopMic() else voice.startMic()
        PlaybackService.setMicActive(voice.micOn)
    }

    // ---------------- notificação / tela de bloqueio ----------------

    private fun nowPlaying(): NowPlaying {
        val s = state
        val cur = s?.current
        return NowPlaying(
            room = room,
            title = cur?.title,
            artist = cur?.artist,
            thumb = cur?.let { it.thumb.ifBlank { "https://img.youtube.com/vi/${it.videoId}/mqdefault.jpg" } },
            isPlaying = s?.isPlaying == true,
            positionMs = ((s?.estimatedPosition() ?: 0.0) * 1000).toLong(),
            durationMs = if (cur?.isLive == true) 0 else (duration * 1000).toLong(),
            hasPrevious = (s?.currentIndex ?: 0) > 0,
            hasNext = s != null && s.currentIndex + 1 < s.queue.size,
            discreet = workMode,
        )
    }

    override fun seekTo(positionMs: Long) = seekTo(positionMs / 1000.0)
    override fun quitParty() = leave()

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

    /** buscando no YouTube agora (o botão "Adicionar" mostra que está procurando) */
    var searching by mutableStateOf(false); private set
    private val searchHttp = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build()

    // Link do YouTube -> adiciona direto. Qualquer outra coisa (ex.: "evidências chitãozinho")
    // -> pesquisa no YouTube e adiciona o PRIMEIRO resultado.
    fun addToQueue(raw: String, isLive: Boolean) {
        val videoId = parseVideoId(raw)
        if (videoId == null) {
            val query = raw.trim()
            if (query.length < 2) return
            if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(query)) { toast("Não reconheci esse link do YouTube 😕"); return }
            searchAndAdd(query, isLive)
            return
        }
        viewModelScope.launch {
            val meta = fetchMeta(videoId)
            connection.send("addQueue", buildJsonObject {
                put("videoId", videoId); put("title", meta.title); put("thumb", meta.thumb)
                put("artist", meta.artist); put("isLive", isLive)
            })
            toast(if (isLive) "Live adicionada à fila 🔴" else "Música adicionada à fila 🎶")
        }
    }

    private fun searchAndAdd(query: String, forceLive: Boolean) {
        if (searching) return
        searching = true
        viewModelScope.launch {
            val found = try { withContext(Dispatchers.IO) { dev.partykit.r0usis.festasync.net.YouTubeSearch.searchFirst(searchHttp, query) } } catch (e: Exception) { null }
            searching = false
            if (found == null) { toast("Não achei nada no YouTube pra \"$query\" 😕 — tenta outro nome ou cola o link"); return@launch }
            val live = forceLive || found.isLive
            connection.send("addQueue", buildJsonObject {
                put("videoId", found.videoId); put("title", found.title)
                put("thumb", "https://i.ytimg.com/vi/${found.videoId}/mqdefault.jpg")
                put("artist", found.channel); put("isLive", live)
            })
            toast("🔎 ${found.title} — ${if (live) "live adicionada 🔴" else "na fila 🎶"}")
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
        appliedPlayerVolume = -1
        applyPlayerVolume()
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
    fun onPlayerDuration(d: Float) {
        duration = d.toDouble()
        if (screen == Screen.Room) PlaybackService.update(nowPlaying()) // barra de progresso da notificação
    }

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

    override fun playPause() {
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

    override fun previous() = playIndex((state?.currentIndex ?: 0) - 1)
    override fun next() = playIndex((state?.currentIndex ?: -1) + 1)

    override fun onCleared() {
        if (PlaybackService.controls === this) PlaybackService.controls = null
        PlaybackService.stop(getApplication())
        voice.reset()
        connection.disconnect()
        super.onCleared()
    }
}
