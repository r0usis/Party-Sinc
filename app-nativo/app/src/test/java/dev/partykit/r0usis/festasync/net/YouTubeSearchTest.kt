package dev.partykit.r0usis.festasync.net

import okhttp3.OkHttpClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

// Busca DE VERDADE no YouTube (precisa de internet) — confere que a leitura da página de
// resultados continua funcionando. Rodar: ./gradlew testReleaseUnitTest
class YouTubeSearchTest {
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()

    @Test fun achaMusicaPeloNome() {
        for (q in listOf("evidências chitãozinho e xororó", "never gonna give you up", "anitta envolver")) {
            val f = YouTubeSearch.searchFirst(http, q)
            assertNotNull("não achou: $q", f)
            println("$q -> ${f!!.videoId} | ${f.title} | ${f.channel} | live=${f.isLive}")
            assertTrue(Regex("^[\\w-]{11}$").matches(f.videoId))
        }
    }
}
