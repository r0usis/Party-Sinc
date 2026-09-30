package dev.partykit.r0usis.festasync.net

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.AudioTrackSink
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

// Chat de voz — o MESMO protocolo do site (toggleMic / startBroadcastingTo / handleVoiceSignal
// em public/index.html), pra voz do app chegar no site e vice-versa:
//
// • Quem LIGA o microfone abre uma conexão só-de-ida ("sendonly") com cada pessoa da sala.
//   Quem recebe só responde. Se as duas falam, são DUAS conexões (uma em cada sentido) —
//   por isso a chave é "clientId#out" (eu abri) / "clientId#in" (a outra pessoa abriu).
// • O servidor só repassa os recados ({type:'voiceSignal', to, signal}): offer, answer, ice
//   (com fromRole dizendo de qual das duas conexões é) e bye (desliguei o mic). O áudio vai
//   direto de celular pra celular (ou via TURN se a rede não deixar), nunca pelo servidor.
// • {type:'voiceStatus', speaking} avisa a sala pra mostrar o "🔴 falando" no nome.
//
// Tudo aqui roda na thread principal; os callbacks do WebRTC são repassados pra ela.
class VoiceChat(context: Context, private val sender: Sender) {

    interface Sender {
        fun send(type: String, fields: JsonObject)
        fun toast(msg: String)
        fun nameOf(clientId: String): String
    }

    companion object {
        private val STUN_ONLY = listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        private const val CONNECT_TIMEOUT_MS = 8000L // conexão presa em "checking" sem nunca avisar
        private const val MAX_ATTEMPTS = 3
        private const val DISCONNECTED_GRACE_MS = 5000L // "disconnected" costuma ser soluço passageiro

        @Volatile private var initialized = false
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    /** meu microfone está ligado */
    var micOn by mutableStateOf(false); private set
    /** volume da minha voz agora (0..1), pra animar o botão */
    var micLevel by mutableFloatStateOf(0f); private set
    /** quem está com o microfone ligado (inclui eu) — vem do voiceStatus */
    val speaking = mutableStateMapOf<String, Boolean>()
    /** volume da voz de cada pessoa que eu estou ouvindo agora (0..1) */
    val levels = mutableStateMapOf<String, Float>()
    /** volume que EU escolhi pra ouvir cada pessoa (0..1) — só pra mim, não muda pros outros */
    val memberVolumes = mutableStateMapOf<String, Float>()

    private var myId: String = ""

    // Servidores pra montar a conexão de voz. O TURN (a "ponte" pra quando os aparelhos não
    // se enxergam direto — 4G, rede de empresa) vem do nosso servidor com credencial
    // temporária da Cloudflare (ver setIceServersFromJson / PartyViewModel). O TURN gratuito
    // de antes (openrelay.metered.ca) saiu do ar: sem ponte, no 4G ninguém se ouvia.
    @Volatile private var iceServers: List<PeerConnection.IceServer> = STUN_ONLY

    /** resposta de /parties/main/turn-credentials: { iceServers: [{ urls, username?, credential? }] } */
    fun setIceServersFromJson(list: kotlinx.serialization.json.JsonArray) {
        val parsed = list.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val urls = when (val u = o["urls"]) {
                is kotlinx.serialization.json.JsonArray -> u.map { it.jsonPrimitive.content }
                is kotlinx.serialization.json.JsonPrimitive -> listOf(u.content)
                else -> emptyList()
            }.filter { it.isNotBlank() }
            if (urls.isEmpty()) return@mapNotNull null
            PeerConnection.IceServer.builder(urls).apply {
                o["username"]?.jsonPrimitive?.content?.let { setUsername(it) }
                o["credential"]?.jsonPrimitive?.content?.let { setPassword(it) }
            }.createIceServer()
        }
        if (parsed.isNotEmpty()) iceServers = parsed
    }
    private var members: List<String> = emptyList()

    private val factory: PeerConnectionFactory by lazy {
        if (!initialized) {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions())
            initialized = true
        }
        val adm = JavaAudioDeviceModule.builder(appContext)
            // Eco/ruído pelo WebRTC em vez do "do aparelho": o do aparelho só funciona direito em
            // modo "ligação", que manda o som pro alto-falante de ouvido e abaixa a música.
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            // a voz dos outros sai como MÍDIA: alto-falante (ou fone), no mesmo volume da música
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setSamplesReadyCallback { samples -> onMicSamples(samples.data) }
            .createAudioDeviceModule()
        PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory()
    }

    private var micSource: AudioSource? = null
    private var micTrack: AudioTrack? = null

    private class Peer(
        val peerId: String,
        val isInitiator: Boolean,
        val attempt: Int,
    ) {
        lateinit var pc: PeerConnection
        val pendingCandidates = mutableListOf<IceCandidate>()
        var remoteTrack: AudioTrack? = null
        var sink: AudioTrackSink? = null
        var connectTimeout: Runnable? = null
        var closed = false
    }

    private val peers = HashMap<String, Peer>()
    private fun key(peerId: String, isInitiator: Boolean) = peerId + if (isInitiator) "#out" else "#in"

    // ---------------- sala ----------------

    fun setMyId(id: String) { myId = id }

    /** lista de gente mudou: quem chegou passa a me ouvir (se meu mic está ligado); quem saiu
     *  tem as conexões fechadas (igual handleMembersMessage do site) */
    fun onMembers(ids: List<String>) {
        val old = members.toSet()
        val now = ids.toSet()
        members = ids
        for (id in old) if (id !in now) { closeAll(id); speaking.remove(id); levels.remove(id) }
        val arrived = now.filter { it != myId && it !in old }
        if (micOn && arrived.isNotEmpty()) {
            arrived.forEach { startBroadcastingTo(it, 1) }
            // quem chega depois nunca recebeu o "estou falando" — manda de novo
            sender.send("voiceStatus", buildJsonObject { put("speaking", true) })
        }
    }

    fun onVoiceStatus(clientId: String, isSpeaking: Boolean) {
        if (isSpeaking) speaking[clientId] = true else { speaking.remove(clientId); levels.remove(clientId) }
    }

    /** volume geral das vozes (0..2 — acima de 1 é reforço), separado do volume da música,
     *  igual jogo que tem "volume da música" e "volume da conversa" */
    var voiceGain by mutableFloatStateOf(1f); private set

    fun changeVoiceGain(gain: Float) {
        voiceGain = gain.coerceIn(0f, 2f)
        peers.values.filter { !it.isInitiator }.forEach { p -> p.remoteTrack?.setVolume(effectiveVolume(p.peerId)) }
    }

    private fun effectiveVolume(peerId: String): Double = ((memberVolumes[peerId] ?: 1f) * voiceGain).toDouble()

    /** alguém (fora eu) falando alto o bastante agora — pra abaixar a música sozinha */
    fun someoneTalking(threshold: Float = 0.06f): Boolean = levels.values.any { it > threshold }

    fun setMemberVolume(clientId: String, volume: Float) {
        memberVolumes[clientId] = volume
        peers[key(clientId, false)]?.remoteTrack?.setVolume(effectiveVolume(clientId))
    }

    /** saiu da sala: desliga tudo */
    fun reset() {
        if (micOn) stopMic(notify = false)
        peers.values.toList().forEach { close(it) }
        peers.clear()
        speaking.clear()
        levels.clear()
        members = emptyList()
    }

    // ---------------- microfone ----------------

    /** só chamar com a permissão RECORD_AUDIO já dada */
    fun startMic() {
        if (micOn) return
        val source = factory.createAudioSource(MediaConstraints())
        micSource = source
        micTrack = factory.createAudioTrack("mic", source).also { it.setEnabled(true) }
        micOn = true
        speaking[myId] = true
        sender.send("voiceStatus", buildJsonObject { put("speaking", true) })
        members.filter { it != myId }.forEach { startBroadcastingTo(it, 1) }
    }

    fun stopMic(notify: Boolean = true) {
        if (!micOn) return
        micOn = false
        micLevel = 0f
        speaking.remove(myId)
        // só as conexões que EU abri — quem ainda está falando comigo continua sendo ouvido
        peers.values.filter { it.isInitiator }.forEach {
            sendSignal(it.peerId, buildJsonObject { put("kind", "bye") })
            close(it)
        }
        micTrack?.dispose(); micTrack = null
        micSource?.dispose(); micSource = null
        if (notify) sender.send("voiceStatus", buildJsonObject { put("speaking", false) })
    }

    private var lastLevelPost = 0L
    private var smoothed = 0f
    // chega na thread de gravação do WebRTC, ~100x por segundo
    private fun onMicSamples(data: ByteArray) {
        if (!micOn) return
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var sum = 0.0
        val n = buf.remaining()
        for (i in 0 until n) { val v = buf.get(i) / 32768.0; sum += v * v }
        val raw = if (n > 0) (sqrt(sum / n) * 4).toFloat().coerceAtMost(1f) else 0f
        smoothed = if (raw > smoothed) raw else smoothed * 0.85f + raw * 0.15f
        val now = System.currentTimeMillis()
        if (now - lastLevelPost > 100) {
            lastLevelPost = now
            val v = smoothed
            main.post { if (micOn) micLevel = v }
        }
    }

    // ---------------- conexões ----------------

    private fun sendSignal(to: String, signal: JsonObject) {
        sender.send("voiceSignal", buildJsonObject { put("to", to); put("signal", signal) })
    }

    private fun createPeer(peerId: String, isInitiator: Boolean, attempt: Int): Peer? {
        val peer = Peer(peerId, isInitiator, attempt)
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val pc = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) = main.post {
                if (peer.closed) return@post
                sendSignal(peerId, buildJsonObject {
                    put("kind", "ice")
                    put("candidate", buildJsonObject {
                        put("candidate", c.sdp); put("sdpMid", c.sdpMid); put("sdpMLineIndex", c.sdpMLineIndex)
                    })
                    put("fromRole", if (isInitiator) "out" else "in")
                })
            }.let { }

            override fun onTrack(transceiver: RtpTransceiver) = main.post {
                if (peer.closed) return@post
                val track = transceiver.receiver.track() as? AudioTrack ?: return@post
                peer.remoteTrack = track
                track.setVolume(effectiveVolume(peerId))
                // medidor da voz dessa pessoa (pra animar o nome dela na lista)
                var last = 0L
                var lastLog = 0L
                var sm = 0f
                val sink = AudioTrackSink { audio, bits, _, _, frames, _ ->
                    if (bits != 16 || frames <= 0) return@AudioTrackSink
                    val sb = audio.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    var s = 0.0
                    val n = sb.remaining()
                    for (i in 0 until n) { val v = sb.get(i) / 32768.0; s += v * v }
                    val raw = if (n > 0) (sqrt(s / n) * 4).toFloat().coerceAtMost(1f) else 0f
                    sm = if (raw > sm) raw else sm * 0.85f + raw * 0.15f
                    val now = System.currentTimeMillis()
                    if (now - last > 100) { last = now; val v = sm; main.post { if (!peer.closed) levels[peerId] = v } }
                    // registro técnico (adb logcat -s FestaVoz) pra diagnosticar voz que some
                    if (now - lastLog > 2000) { lastLog = now; Log.d("FestaVoz", "ouvindo $peerId nível=${"%.3f".format(sm)} frames=$frames") }
                }
                peer.sink = sink
                track.addSink(sink)
            }.let { }

            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) = main.post {
                Log.d("FestaVoz", "conexão ${key(peerId, isInitiator)} -> $state")
                if (peer.closed) return@post
                when (state) {
                    PeerConnection.PeerConnectionState.CONNECTED -> peer.connectTimeout?.let { main.removeCallbacks(it); peer.connectTimeout = null }
                    PeerConnection.PeerConnectionState.FAILED, PeerConnection.PeerConnectionState.CLOSED -> retryOrClose(peer)
                    PeerConnection.PeerConnectionState.DISCONNECTED -> main.postDelayed({
                        if (!peer.closed && peer.pc.connectionState() == PeerConnection.PeerConnectionState.DISCONNECTED) retryOrClose(peer)
                    }, DISCONNECTED_GRACE_MS)
                    else -> {}
                }
            }.let { }

            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onDataChannel(d: DataChannel) {}
            override fun onRenegotiationNeeded() {}
        }) ?: return null
        peer.pc = pc
        peers[key(peerId, isInitiator)] = peer
        if (isInitiator) {
            val t = Runnable { if (!peer.closed && pc.connectionState() != PeerConnection.PeerConnectionState.CONNECTED) retryOrClose(peer) }
            peer.connectTimeout = t
            main.postDelayed(t, CONNECT_TIMEOUT_MS)
        }
        return peer
    }

    private fun close(peer: Peer) {
        if (peer.closed) return
        Log.d("FestaVoz", "fechando ${key(peer.peerId, peer.isInitiator)}")
        peer.closed = true
        peer.connectTimeout?.let { main.removeCallbacks(it) }
        peer.sink?.let { s -> try { peer.remoteTrack?.removeSink(s) } catch (e: Exception) { } }
        if (peers[key(peer.peerId, peer.isInitiator)] === peer) peers.remove(key(peer.peerId, peer.isInitiator))
        if (!peer.isInitiator) levels.remove(peer.peerId)
        try { peer.pc.dispose() } catch (e: Exception) { }
    }

    private fun closeAll(peerId: String) {
        peers[key(peerId, true)]?.let { close(it) }
        peers[key(peerId, false)]?.let { close(it) }
    }

    // travou/caiu: se fui EU que abri (é a minha voz indo pra alguém), tenta de novo algumas
    // vezes; se foi a outra pessoa, ela mesma tenta de novo do lado dela
    private fun retryOrClose(peer: Peer) {
        if (peers[key(peer.peerId, peer.isInitiator)] !== peer) return
        close(peer)
        if (!peer.isInitiator || !micOn) return
        if (peer.attempt < MAX_ATTEMPTS) startBroadcastingTo(peer.peerId, peer.attempt + 1)
        else sender.toast("🎙️❌ Não consegui conectar o áudio com ${sender.nameOf(peer.peerId)}. Tenta desligar e ligar o microfone de novo.")
    }

    private fun startBroadcastingTo(peerId: String, attempt: Int) {
        val track = micTrack ?: return
        if (peers.containsKey(key(peerId, true))) return
        val peer = createPeer(peerId, true, attempt) ?: return
        val transceiver = peer.pc.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY),
        )
        transceiver.sender.setTrack(track, false)
        peer.pc.createOffer(sdp(onCreate = { offer ->
            peer.pc.setLocalDescription(sdp(onSet = {
                main.post { if (!peer.closed) sendSignal(peerId, signalWithSdp("offer", offer)) }
            }), offer)
        }), MediaConstraints())
    }

    /** {type:'voiceSignal', from, signal} vindo do servidor */
    fun onSignal(fromId: String, signal: JsonObject) {
        try {
            when (signal["kind"]?.jsonPrimitive?.content) {
                "offer" -> {
                    Log.d("FestaVoz", "oferta de $fromId")
                    // oferta = alguém começando a transmitir pra mim — vira a minha conexão de
                    // ENTRADA; se tinha uma antiga (reconexão), fecha antes
                    peers[key(fromId, false)]?.let { close(it) }
                    val peer = createPeer(fromId, false, 1) ?: return
                    val offer = parseSdp(signal) ?: return
                    peer.pc.setRemoteDescription(sdp(onSet = {
                        main.post {
                            if (peer.closed) return@post
                            peer.pendingCandidates.forEach { peer.pc.addIceCandidate(it) }
                            peer.pendingCandidates.clear()
                            peer.pc.createAnswer(sdp(onCreate = { answer ->
                                peer.pc.setLocalDescription(sdp(onSet = {
                                    main.post { if (!peer.closed) sendSignal(fromId, signalWithSdp("answer", answer)) }
                                }), answer)
                            }), MediaConstraints())
                        }
                    }), offer)
                }
                "answer" -> {
                    val peer = peers[key(fromId, true)] ?: return
                    val answer = parseSdp(signal) ?: return
                    peer.pc.setRemoteDescription(sdp(onSet = {
                        main.post {
                            if (peer.closed) return@post
                            peer.pendingCandidates.forEach { peer.pc.addIceCandidate(it) }
                            peer.pendingCandidates.clear()
                        }
                    }), answer)
                }
                // a pessoa desligou o mic: fecha a conexão onde eu ouvia ela
                "bye" -> peers[key(fromId, false)]?.let { close(it) }
                "ice" -> {
                    // fromRole é o papel de QUEM MANDOU; do meu lado é o oposto
                    val iAmInitiator = signal["fromRole"]?.jsonPrimitive?.content != "out"
                    val peer = peers[key(fromId, iAmInitiator)] ?: return
                    val c = signal["candidate"]?.jsonObject ?: return
                    val cand = IceCandidate(
                        c["sdpMid"]?.jsonPrimitive?.content ?: "0",
                        c["sdpMLineIndex"]?.jsonPrimitive?.int ?: 0,
                        c["candidate"]?.jsonPrimitive?.content ?: return,
                    )
                    if (peer.pc.remoteDescription != null) peer.pc.addIceCandidate(cand) else peer.pendingCandidates.add(cand)
                }
            }
        } catch (e: Exception) {
            // conexão de voz com defeito não pode derrubar o resto do app
        }
    }

    // o site manda o RTCSessionDescription inteiro: { sdp: { type, sdp } }
    private fun parseSdp(signal: JsonObject): SessionDescription? {
        val obj = signal["sdp"]?.jsonObject ?: return null
        val type = when (obj["type"]?.jsonPrimitive?.content) {
            "offer" -> SessionDescription.Type.OFFER
            "answer" -> SessionDescription.Type.ANSWER
            else -> return null
        }
        return SessionDescription(type, obj["sdp"]?.jsonPrimitive?.content ?: return null)
    }

    private fun signalWithSdp(kind: String, d: SessionDescription) = buildJsonObject {
        put("kind", kind)
        put("sdp", buildJsonObject { put("type", kind); put("sdp", d.description) })
    }

    private fun sdp(onCreate: (SessionDescription) -> Unit = {}, onSet: () -> Unit = {}) = object : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription) = onCreate(d)
        override fun onSetSuccess() = onSet()
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }
}
