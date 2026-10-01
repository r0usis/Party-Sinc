package dev.partykit.r0usis.festasync.net

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

// Busca por nome de música: abre a página de resultados do YouTube (a mesma que o navegador
// abre), lê a lista que vem embutida nela (ytInitialData) e devolve o PRIMEIRO vídeo —
// pulando anúncio, Shorts e playlist. Não precisa de chave de API (a API oficial só deixa
// ~100 buscas por dia). Se um dia o YouTube mudar o formato da página, isso para de achar e
// o app avisa "não achei" — colar o link continua funcionando.
object YouTubeSearch {
    data class Found(val videoId: String, val title: String, val channel: String, val isLive: Boolean)

    private val INITIAL_DATA = Regex("""var ytInitialData = (\{.*?\});</script>""", RegexOption.DOT_MATCHES_ALL)

    fun searchFirst(http: OkHttpClient, query: String): Found? {
        val url = "https://www.youtube.com/results".toHttpUrl().newBuilder()
            .addQueryParameter("search_query", query)
            .addQueryParameter("hl", "pt-BR")
            .addQueryParameter("gl", "BR")
            .build()
        val req = Request.Builder().url(url)
            // como um navegador de computador — no celular o YouTube manda outra página
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36")
            .header("Accept-Language", "pt-BR,pt;q=0.9")
            // pula a tela de "aceitar cookies" que o YouTube mostra em alguns lugares
            .header("Cookie", "CONSENT=YES+1; SOCS=CAI")
            .build()
        val html = http.newCall(req).execute().use { res -> if (!res.isSuccessful) return null; res.body?.string() } ?: return null
        val json = INITIAL_DATA.find(html)?.groupValues?.get(1) ?: return null
        val root = try { ProtocolJson.parseToJsonElement(json) } catch (e: Exception) { return null }
        val video = firstVideoRenderer(root) ?: return null
        val id = video["videoId"]?.jsonPrimitive?.content ?: return null
        return Found(
            videoId = id,
            title = textOf(video["title"]) ?: "Vídeo $id",
            channel = textOf(video["ownerText"]) ?: "",
            isLive = isLiveNow(video),
        )
    }

    // primeiro "videoRenderer" na ordem da página (anúncio é "adSlotRenderer"/"promotedVideoRenderer",
    // Shorts é "reelShelfRenderer" — nenhum deles tem um videoRenderer dentro)
    private fun firstVideoRenderer(el: JsonElement): JsonObject? {
        when (el) {
            is JsonObject -> {
                (el["videoRenderer"] as? JsonObject)?.let { if (it["videoId"] != null) return it }
                for ((k, v) in el) {
                    if (k == "adSlotRenderer" || k == "promotedVideoRenderer" || k == "reelShelfRenderer") continue
                    firstVideoRenderer(v)?.let { return it }
                }
            }
            is JsonArray -> for (v in el) firstVideoRenderer(v)?.let { return it }
            else -> {}
        }
        return null
    }

    // { runs: [{ text }] } ou { simpleText }
    private fun textOf(el: JsonElement?): String? {
        val o = el as? JsonObject ?: return null
        o["simpleText"]?.jsonPrimitive?.content?.let { return it }
        return o["runs"]?.jsonArray?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content ?: "" }?.takeIf { it.isNotBlank() }
    }

    private fun isLiveNow(video: JsonObject): Boolean {
        val badges = video["badges"]?.toString() ?: ""
        val overlays = video["thumbnailOverlays"]?.toString() ?: ""
        return "BADGE_STYLE_TYPE_LIVE_NOW" in badges || "\"LIVE\"" in overlays
    }
}
