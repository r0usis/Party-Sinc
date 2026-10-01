package dev.partykit.r0usis.festasync

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import dev.partykit.r0usis.festasync.net.MimicAudio
import dev.partykit.r0usis.festasync.net.MimicGame
import dev.partykit.r0usis.festasync.net.MimicSound
import dev.partykit.r0usis.festasync.net.ProtocolJson
import dev.partykit.r0usis.festasync.net.SERVER_HOST
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

// Mimic Party no app — o mesmo jogo e o mesmo protocolo do site (mimicInvite / mimicRespond /
// mimicBegin / mimicSubmit / mimicSkip / mimicLeave / mimicCancel + mimicPerformance). Quem
// manda é o servidor (ordem, vez de 60s, nota na tela 5s); a nota quem calcula é o aparelho de
// quem imitou (MimicAudio, mesma conta do site) e a gravação vai pra sala ouvir.
class MimicController(
    context: Context,
    private val scope: CoroutineScope,
    private val myId: () -> String,
    private val send: (type: String, fields: JsonObject) -> Unit,
    private val toast: (String) -> Unit,
    /** desliga o mic do chat de voz enquanto grava (devolve se estava ligado) */
    private val pauseVoice: () -> Boolean,
    private val resumeVoice: () -> Unit,
    /** música do YouTube muda/volta enquanto grava (senão ela vaza pra gravação) */
    private val muteMusic: (Boolean) -> Unit,
) {
    companion object {
        const val REC_MAX_MS = 6000L // tempo máximo de gravação da imitação (igual o site)
        const val TURN_MS = 60_000L
        private const val WAV_SR = 11025 // a gravação que vai pra sala: WAV 11kHz, até 6s
    }

    private val appContext = context.applicationContext
    private val http = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build()

    /** sons pra sortear (pasta do projeto + cadastrados pelo app/site); null = carregando */
    var library by mutableStateOf<List<MimicSound>?>(null); private set
    var recState by mutableStateOf("idle"); private set // idle | recording | processing
    var recStartedAt by mutableLongStateOf(0L); private set
    /** "rodada:clientId" -> gravação (data URL) — pra ouvir de novo a imitação */
    val performances = mutableStateMapOf<String, String>()

    private val originalCache = HashMap<String, FloatArray>()
    private var player: MediaPlayer? = null
    @Volatile private var stopRequested = false
    @Volatile private var discard = false
    private var game: MimicGame = MimicGame()

    private fun url(src: String) = if (src.startsWith("http")) src else "https://$SERVER_HOST$src"

    // ---------------- biblioteca ----------------

    fun loadLibrary() {
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                val list = ArrayList<MimicSound>()
                try {
                    http.newCall(Request.Builder().url(url("/mimic/sons.json")).build()).execute().use { r ->
                        if (r.isSuccessful) ProtocolJson.parseToJsonElement(r.body!!.string()).jsonObject["sons"]?.jsonArray?.forEach { el ->
                            val o = el.jsonObject
                            val arquivo = o["arquivo"]?.jsonPrimitive?.content ?: return@forEach
                            list += MimicSound(
                                id = "p:" + (o["id"]?.jsonPrimitive?.content ?: arquivo),
                                nome = o["nome"]?.jsonPrimitive?.content ?: arquivo,
                                src = "/mimic/sons/" + android.net.Uri.encode(arquivo),
                            )
                        }
                    }
                } catch (e: Exception) { }
                try {
                    http.newCall(Request.Builder().url(url("/parties/main/mimic-library")).build()).execute().use { r ->
                        if (r.isSuccessful) ProtocolJson.parseToJsonElement(r.body!!.string()).jsonArray.forEach { el ->
                            val o = el.jsonObject
                            val id = o["id"]?.jsonPrimitive?.content ?: return@forEach
                            list += MimicSound("a:$id", o["nome"]?.jsonPrimitive?.content ?: "som", "/parties/main/mimic-library?clip=" + android.net.Uri.encode(id))
                        }
                    }
                } catch (e: Exception) { }
                list
            }
            library = out
        }
    }

    // ---------------- convite / partida ----------------

    fun invite(ids: List<String>, rounds: Int) {
        if (library.isNullOrEmpty()) { toast("Não achei nenhum som na biblioteca 🎵 (cadastra pelo site)"); return }
        send("mimicInvite", buildJsonObject { putJsonArray("to") { ids.forEach { add(it) } }; put("rounds", rounds) })
    }
    fun respond(accept: Boolean) = send("mimicRespond", buildJsonObject { put("accept", accept) })
    fun begin() {
        val lib = library
        if (lib.isNullOrEmpty()) { toast("Nenhum som na biblioteca 🎵"); return }
        val pool = lib.shuffled()
        val picked = List(game.totalRounds) { pool[it % pool.size] } // se tiver menos som que rodada, repete
        send("mimicBegin", buildJsonObject {
            putJsonArray("sounds") { picked.forEach { s -> addJsonObject { put("id", s.id); put("nome", s.nome); put("src", s.src) } } }
        })
    }
    fun skip() = send("mimicSkip", JsonObject(emptyMap()))
    fun leave() = send("mimicLeave", JsonObject(emptyMap()))
    fun cancel() = send("mimicCancel", JsonObject(emptyMap()))

    /** a cada `state`: se a minha vez acabou (tempo, cancelaram...) com a gravação rolando, descarta */
    fun onState(g: MimicGame) {
        game = g
        if (recState == "recording" && !(g.phase == "playing" && g.currentPerformerId == myId())) { discard = true; stopRequested = true }
    }

    fun onPerformance(clientId: String, round: Int, audio: String) {
        performances["$round:$clientId"] = audio
        if (performances.size > 40) performances.remove(performances.keys.first())
        // toca a imitação pra quem está jogando
        if (myId() in game.acceptedIds) play(audio)
    }

    // ---------------- tocar ----------------

    fun play(src: String?) {
        if (src.isNullOrBlank()) return
        stopPlayback()
        scope.launch {
            val source = if (src.startsWith("data:")) withContext(Dispatchers.IO) {
                // data URL -> arquivo temporário (o MediaPlayer não toca data URL direto)
                val f = File(appContext.cacheDir, "mimic-play.wav")
                f.writeBytes(Base64.decode(src.substringAfter("base64,"), Base64.DEFAULT))
                f.absolutePath
            } else url(src)
            try {
                player = MediaPlayer().apply {
                    setDataSource(source)
                    setOnPreparedListener { it.start() }
                    setOnCompletionListener { it.release(); if (player === it) player = null }
                    prepareAsync()
                }
            } catch (e: Exception) { toast("Não consegui tocar esse som 😕") }
        }
    }

    private fun stopPlayback() { try { player?.release() } catch (e: Exception) { }; player = null }

    // ---------------- gravar a imitação ----------------

    @SuppressLint("MissingPermission")
    fun startRecording() {
        val g = game
        if (recState != "idle" || g.phase != "playing" || g.currentPerformerId != myId()) return
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Precisa da permissão do microfone pra gravar 🎙️"); return
        }
        stopPlayback()
        val voiceWasOn = pauseVoice()
        muteMusic(true)
        stopRequested = false; discard = false
        recState = "recording"; recStartedAt = System.currentTimeMillis()
        val sound = g.currentSound
        scope.launch {
            val samples = withContext(Dispatchers.IO) { record() }
            muteMusic(false)
            if (voiceWasOn) resumeVoice()
            if (discard || samples == null) { recState = "idle"; return@launch }
            recState = "processing"
            var score = 0; var audio: String? = null; var reason: String? = null
            try {
                withContext(Dispatchers.Default) {
                    val orig = originalFor(sound?.src) ?: throw IllegalStateException("sem original")
                    val r = MimicAudio.score(orig, samples)
                    score = r.score; reason = r.reason
                    val small = MimicAudio.resample(samples, MimicAudio.SR, WAV_SR).let { it.copyOf(minOf(it.size, (WAV_SR * REC_MAX_MS / 1000).toInt())) }
                    audio = MimicAudio.wavDataUrl(small, WAV_SR)
                }
            } catch (e: Exception) { reason = "não consegui processar a gravação" }
            reason?.let { toast("🎤 Nota 0 — $it") }
            recState = "idle"
            send("mimicSubmit", buildJsonObject { put("score", score); audio?.let { put("audio", it) } })
        }
    }

    fun stopRecording() { stopRequested = true }

    // grava em 16kHz mono (o formato da comparação) até tocar em Parar ou dar 6s
    @SuppressLint("MissingPermission")
    private fun record(): FloatArray? {
        val minBuf = AudioRecord.getMinBufferSize(MimicAudio.SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, MimicAudio.SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 4096) * 2)
        } catch (e: Exception) { return null }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return null }
        val maxSamples = (MimicAudio.SR * REC_MAX_MS / 1000).toInt()
        val all = ShortArray(maxSamples)
        var n = 0
        val buf = ShortArray(1024)
        try {
            rec.startRecording()
            while (!stopRequested && n < maxSamples) {
                val r = rec.read(buf, 0, minOf(buf.size, maxSamples - n))
                if (r <= 0) break
                System.arraycopy(buf, 0, all, n, r); n += r
            }
        } finally { try { rec.stop() } catch (e: Exception) { }; rec.release() }
        return FloatArray(n) { all[it] / 32768f }
    }

    private fun originalFor(src: String?): FloatArray? {
        if (src.isNullOrBlank()) return null
        originalCache[src]?.let { return it }
        val bytes = http.newCall(Request.Builder().url(url(src)).build()).execute().use { if (it.isSuccessful) it.body?.bytes() else null } ?: return null
        val samples = MimicAudio.decodeWavTo16k(bytes) ?: return null
        originalCache[src] = samples
        return samples
    }

    fun release() { stopRequested = true; discard = true; stopPlayback() }
}
