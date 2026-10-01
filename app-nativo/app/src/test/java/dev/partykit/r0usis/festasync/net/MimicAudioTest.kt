package dev.partykit.r0usis.festasync.net

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

// A nota do app tem que bater com a do site (mimicScore em public/index.html). Os números
// esperados vieram rodando a conta do site nos mesmos sons da pasta do projeto.
class MimicAudioTest {
    private val dir = File("../../public/mimic/sons")
    private fun wav(n: String) = MimicAudio.decodeWavTo16k(File(dir, "$n.wav").readBytes())!!

    // "imitação" fingida: o próprio som com ruído determinístico e 15% mais lento (igual o script do site)
    private fun fake(x: FloatArray): FloatArray {
        val out = FloatArray(Math.floor(x.size * 1.15).toInt())
        var seed = 1L
        for (i in out.indices) {
            seed = (seed * 1103515245 + 12345) % 2147483648
            out[i] = (x[Math.floor(i / 1.15).toInt()] * 0.6 + ((seed / 2147483648.0) - 0.5) * 0.02).toFloat()
        }
        return out
    }

    @Test fun notaIgualAoSite() {
        val cases = listOf(
            Triple("sirene", "sirene", 100), Triple("sirene", "buzina", 27), Triple("vaca", "gato", 1),
            Triple("apito", "vaca", 11), Triple("gato", "gato*", 85), Triple("buzina", "buzina*", 61),
        )
        for ((p, q, expected) in cases) {
            val b = if (q.endsWith("*")) fake(wav(q.dropLast(1))) else wav(q)
            val got = MimicAudio.score(wav(p), b).score
            println("$p x $q: app=$got site=$expected")
            assertEquals("$p x $q", expected.toDouble(), got.toDouble(), 1.0)
        }
    }
}
