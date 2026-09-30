package dev.partykit.r0usis.festasync.net

import android.os.Handler
import android.os.Looper
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

// Conexão WebSocket com a sala — o mesmo endereço e os mesmos parâmetros que o site usa em
// connectWS(): wss://<servidor>/parties/main/<SALA>?name=&id=&mode=&password=[&maxPeople=]
// Todos os callbacks do Listener chegam já na thread principal.
class PartyConnection(private val listener: Listener) {
    interface Listener {
        fun onConnected()
        fun onState(state: PlaybackState)
        fun onMembers(members: List<Member>, maxPeople: Int)
        /** o servidor recusou de vez (sala não existe, senha errada, lotada, expulso) */
        fun onRejected(reason: String)
        /** caiu — já está tentando reconectar sozinho */
        fun onConnectionLost()
    }

    data class Params(
        val room: String,
        val name: String,
        val clientId: String,
        val password: String,
        val create: Boolean,
        val maxPeople: Int = 10,
    )

    companion object {
        // ver ROOM_REJECT_CODES no site / CLOSE_* no servidor: nesses casos tentar de novo
        // sozinho não resolve
        private val REJECT_CODES = setOf(4001, 4002, 4003, 4004, 4006)
        private const val RECONNECT_MS = 1500L
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket fica aberto indefinidamente
        .pingInterval(25, TimeUnit.SECONDS)
        .build()
    private val main = Handler(Looper.getMainLooper())
    private var socket: WebSocket? = null
    @Volatile private var params: Params? = null
    private var stopped = false
    private var generation = 0 // descarta eventos de sockets antigos (reconexões)
    private var handledCloseGen = -1 // onClosing + onFailure podem chegar os dois pro mesmo socket

    fun connect(p: Params) {
        params = p
        stopped = false
        open()
    }

    private fun open() {
        val p = params ?: return
        val gen = ++generation
        val url = HttpUrl.Builder()
            .scheme("https").host(SERVER_HOST)
            .addPathSegments("parties/main").addPathSegment(p.room)
            .addQueryParameter("name", p.name)
            .addQueryParameter("id", p.clientId)
            .addQueryParameter("mode", if (p.create) "create" else "join")
            .addQueryParameter("password", p.password)
            .apply { if (p.create) addQueryParameter("maxPeople", p.maxPeople.toString()) }
            .build()
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.post { if (gen == generation) listener.onConnected() }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // o parse (o `state` pode ter vários MB com fotos do chat) fica fora da thread
                // principal; só o resultado vai pra ela
                val msg = try { ProtocolJson.parseToJsonElement(text).jsonObject } catch (e: Exception) { return }
                when (msg["type"]?.jsonPrimitive?.content) {
                    "ping" -> webSocket.send("""{"type":"pong"}""")
                    "state" -> {
                        val st = try { ProtocolJson.decodeFromJsonElement<PlaybackState>(msg["state"]!!) } catch (e: Exception) { return }
                        // Quem CRIOU a sala precisa reconectar como "join" — reconectar com
                        // mode=create dá "Essa sala já existe" e derrubava a pessoa da
                        // própria sala quando a internet piscava.
                        params = params?.copy(create = false)
                        main.post { if (gen == generation) listener.onState(st) }
                    }
                    "members" -> {
                        val members = try {
                            msg["members"]!!.jsonArray.map { ProtocolJson.decodeFromJsonElement<Member>(it) }
                        } catch (e: Exception) { return }
                        val max = try { msg["maxPeople"]!!.jsonPrimitive.int } catch (e: Exception) { 0 }
                        main.post { if (gen == generation) listener.onMembers(members, max) }
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                handleClose(gen, code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handleClose(gen, -1, "")
            }
        })
    }

    private fun handleClose(gen: Int, code: Int, reason: String) {
        main.post {
            if (gen != generation || stopped || gen == handledCloseGen) return@post
            handledCloseGen = gen
            socket = null
            if (code in REJECT_CODES) {
                stopped = true
                listener.onRejected(reason.ifBlank { "Não foi possível entrar nessa sala." })
            } else {
                listener.onConnectionLost()
                main.postDelayed({ if (!stopped && gen == generation) open() }, RECONNECT_MS)
            }
        }
    }

    fun send(type: String, fields: JsonObject = JsonObject(emptyMap())) {
        val obj = buildJsonObject {
            put("type", type)
            fields.forEach { (k, v) -> put(k, v) }
        }
        socket?.send(obj.toString())
    }

    fun disconnect() {
        stopped = true
        generation++
        socket?.close(1000, "saiu")
        socket = null
    }
}
