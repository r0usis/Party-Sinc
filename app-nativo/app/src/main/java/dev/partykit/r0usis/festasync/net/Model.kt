package dev.partykit.r0usis.festasync.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Espelho do `playback` que o servidor manda na mensagem {type:'state'} (ver
// defaultPlaybackState() em party/server.js). Só os campos que o app usa até agora — o resto
// (jogos, compartilhar tela...) é ignorado na leitura (ignoreUnknownKeys) e entra aqui
// conforme cada parte for sendo feita.

const val SERVER_HOST = "festa-sync.r0usis.partykit.dev"

val ProtocolJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
}

@Serializable
data class QueueItem(
    val qid: String = "",
    val videoId: String = "",
    val title: String = "",
    val thumb: String = "",
    val artist: String = "",
    val addedBy: String = "",
    val isLive: Boolean = false,
)

@Serializable
data class ChatMessage(
    val id: String = "",
    val clientId: String? = null,
    val name: String = "",
    val text: String = "",
    val image: String? = null, // data URL (jpeg em base64) — igual o site manda
    val ts: Long = 0,
)

@Serializable
data class PlaybackState(
    val queue: List<QueueItem> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val position: Double = 0.0,
    val updatedAt: Long = 0,
    val hostName: String? = null,
    val screenSharerId: String? = null,
    val chatLog: List<ChatMessage> = emptyList(),
) {
    val current: QueueItem? get() = queue.getOrNull(currentIndex)

    // mesma conta do computeEstimatedPosition() do site e do servidor
    fun estimatedPosition(now: Long = System.currentTimeMillis()): Double {
        if (currentIndex < 0) return 0.0
        if (!isPlaying) return position
        return position + (now - updatedAt) / 1000.0
    }
}

@Serializable
data class Member(val clientId: String = "", val name: String = "Convidado")

// mesmos emojis e a mesma conta do avatarFor() do site — a mesma pessoa aparece com o
// mesmo avatar no app e no site
private val PARTY_AVATARS = listOf("🥳", "🎉", "🕺", "💃", "🎊", "🤩", "😜", "🍾")
fun avatarFor(name: String): String {
    var hash = 0L
    for (ch in name) hash = (hash * 31 + ch.code) and 0xFFFFFFFFL
    return PARTY_AVATARS[(hash % PARTY_AVATARS.size).toInt()]
}

// igual sanitizeCode() do site: só letras e números, maiúsculas, até 12
fun sanitizeRoomCode(raw: String): String =
    raw.trim().uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(12)

// igual parseVideoId() do site
fun parseVideoId(raw: String): String? {
    val input = raw.trim()
    // código de vídeo solto (11 caracteres) — mas só se parecer código mesmo (tem número,
    // - ou _, ou maiúscula no meio): uma palavra comum de 11 letras é busca por nome, não link
    if (Regex("^[\\w-]{11}$").matches(input) && (input.any { it.isDigit() || it == '-' || it == '_' } || input.drop(1).any { it.isUpperCase() })) return input
    val uri = try { android.net.Uri.parse(input) } catch (e: Exception) { return null }
    val host = uri.host ?: return null
    if (host.contains("youtu.be")) return uri.path?.trimStart('/')?.take(11)?.takeIf { it.length == 11 }
    uri.getQueryParameter("v")?.let { return it.take(11) }
    Regex("/(shorts|embed|live)/([\\w-]{11})").find(uri.path ?: "")?.let { return it.groupValues[2] }
    return null
}
