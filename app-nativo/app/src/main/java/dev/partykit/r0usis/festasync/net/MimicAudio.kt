package dev.partykit.r0usis.festasync.net

import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Áudio do Mimic Party: a NOTA (0-100) e os formatos (WAV). É a MESMA conta do site
// (mimicPrepare / mimicScore / mimicResample / mimicWavBytes em public/index.html), portada
// linha por linha — senão a mesma imitação daria nota diferente no app e no site.
// Compara o "contorno" do som ao longo do tempo (timbre por MFCC, volume subindo e
// descendo), alinhando as duas gravações no tempo (DTW) — começar um pouco antes/depois ou
// imitar mais rápido/devagar não é punido demais, e não depende do tom exato.
object MimicAudio {
    const val SR = 16000 // tudo é comparado em 16kHz mono
    private const val FRAME = 512
    private const val HOP = 256
    private const val NMEL = 26
    private const val NCEP = 10

    private val DCT: Array<DoubleArray> = Array(NCEP + 1) { c -> DoubleArray(NMEL) { m -> cos(PI * c * (m + 0.5) / NMEL) } }
    private val HANN = DoubleArray(FRAME) { i -> 0.5 - 0.5 * cos(2 * PI * i / (FRAME - 1)) }
    private val MEL_BANK: Array<DoubleArray> by lazy {
        fun hz2mel(h: Double) = 2595 * log10(1 + h / 700)
        fun mel2hz(m: Double) = 700 * (10.0.pow(m / 2595) - 1)
        val lo = hz2mel(80.0); val hi = hz2mel(5000.0) // até 5kHz: o som guardado na biblioteca é 11kHz
        val pts = IntArray(NMEL + 2) { i -> floor((FRAME + 1) * mel2hz(lo + (hi - lo) * i / (NMEL + 1)) / SR).toInt() }
        Array(NMEL) { idx ->
            val m = idx + 1
            val f = DoubleArray(FRAME / 2 + 1)
            for (k in pts[m - 1] until pts[m]) f[k] = (k - pts[m - 1]).toDouble() / max(1, pts[m] - pts[m - 1])
            for (k in pts[m] until pts[m + 1]) f[k] = (pts[m + 1] - k).toDouble() / max(1, pts[m + 1] - pts[m])
            f
        }
    }

    private fun fftPower(re: DoubleArray): DoubleArray {
        val n = re.size
        val im = DoubleArray(n)
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val t = re[i]; re[i] = re[j]; re[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = kotlin.math.sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ar = re[i + k]; val ai = im[i + k]; val br = re[i + k + len / 2]; val bi = im[i + k + len / 2]
                    val tr = br * cr - bi * ci; val ti = br * ci + bi * cr
                    re[i + k] = ar + tr; im[i + k] = ai + ti; re[i + k + len / 2] = ar - tr; im[i + k + len / 2] = ai - ti
                    val ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
        return DoubleArray(n / 2 + 1) { k -> re[k] * re[k] + im[k] * im[k] }
    }

    private class Frame(val v: DoubleArray, val loud: Double)

    private fun prepare(x: FloatArray): List<Frame>? {
        val bank = MEL_BANK
        class Raw(val mel: DoubleArray, val rms: Double)
        var fr = ArrayList<Raw>()
        var s = 0
        while (s + FRAME <= x.size) {
            val buf = DoubleArray(FRAME); var e = 0.0
            for (i in 0 until FRAME) { val v = x[s + i].toDouble(); e += v * v; buf[i] = v * HANN[i] }
            val p = fftPower(buf)
            val mel = DoubleArray(NMEL)
            for (m in 0 until NMEL) {
                var acc = 1e-10; val f = bank[m]
                for (k in f.indices) if (f[k] != 0.0) acc += f[k] * p[k]
                mel[m] = ln(acc)
            }
            fr.add(Raw(mel, sqrt(e / FRAME)))
            s += HOP
        }
        if (fr.size < 3) return null
        // corta o silêncio/chiado do começo e do fim
        val sorted = fr.map { it.rms }.sorted()
        val peak = sorted.last()
        val floorV = sorted[floor(sorted.size * 0.1).toInt()]
        // só chiado: baixinho demais, ou "plano" E baixo (som contínuo e alto não é chiado)
        if (peak < 0.004 || (peak < floorV * 2.2 && peak < 0.03)) return null
        val thr = max(peak * 0.08, floorV + 0.25 * (peak - floorV))
        var a = 0; var b = fr.size - 1
        while (a < b && fr[a].rms < thr) a++
        while (b > a && fr[b].rms < thr) b--
        fr = ArrayList(fr.subList(a, b + 1))
        if (fr.size < 3) return null
        // piso de ~50dB abaixo do ponto mais forte
        var top = Double.NEGATIVE_INFINITY
        for (f in fr) for (m in 0 until NMEL) top = max(top, f.mel[m])
        for (f in fr) for (m in 0 until NMEL) f.mel[m] = max(f.mel[m], top - 11.5)
        // média de cada banda só dos trechos com som (compensa microfone/volume diferentes)
        val voiced = fr.filter { it.rms / peak > 0.15 }
        val mean = DoubleArray(NMEL)
        for (f in voiced) for (m in 0 until NMEL) mean[m] += f.mel[m] / voiced.size
        return fr.map { f ->
            val v = DoubleArray(NCEP); var n = 0.0
            for (c in 1..NCEP) {
                var acc = 0.0
                for (m in 0 until NMEL) acc += (f.mel[m] - mean[m]) * DCT[c][m]
                v[c - 1] = acc; n += acc * acc
            }
            n = sqrt(n).let { if (it == 0.0) 1.0 else it }
            for (c in 0 until NCEP) v[c] /= n
            Frame(v, f.rms / peak)
        }
    }

    private fun frameDist(a: Frame, b: Frame): Double {
        var dot = 0.0
        for (c in 0 until NCEP) dot += a.v[c] * b.v[c]
        val w = min(1.0, sqrt(max(a.loud, b.loud)) * 1.2) // numa pausa, o "timbre" do chiado não importa
        return 0.75 * ((1 - dot) / 2) * w + 0.25 * abs(a.loud - b.loud)
    }

    data class Score(val score: Int, val reason: String? = null)

    /** nota de 0 a 100 da imitação (16kHz mono) comparada com o original (16kHz mono) */
    fun score(orig: FloatArray, imit: FloatArray): Score {
        val A = prepare(orig) ?: return Score(0, "o som original parece vazio")
        val B = prepare(imit) ?: return Score(0, "não deu pra ouvir nada na gravação")
        val n = A.size; val m = B.size
        val band = max(abs(n - m), ceil(max(n, m) * 0.35).toInt())
        var prev = DoubleArray(m + 1) { Double.POSITIVE_INFINITY }
        var cur = DoubleArray(m + 1)
        prev[0] = 0.0
        for (i in 1..n) {
            cur.fill(Double.POSITIVE_INFINITY)
            val jc = Math.round(i.toDouble() * m / n).toInt()
            for (j in max(1, jc - band)..min(m, jc + band)) {
                cur[j] = frameDist(A[i - 1], B[j - 1]) + min(prev[j], min(cur[j - 1], prev[j - 1]))
            }
            val t = prev; prev = cur; cur = t
        }
        val d = prev[m] / (n + m)
        val ratio = min(n, m).toDouble() / max(n, m)
        var s = (100 * (1 - (d - 0.04) / 0.22)).coerceIn(0.0, 100.0)
        if (s.isNaN()) s = 0.0
        s *= 0.75 + 0.25 * ratio // duração muito diferente perde um pouquinho
        return Score(Math.round(s).toInt())
    }

    /** reamostra por média de janela (igual mimicResample do site) */
    fun resample(x: FloatArray, fromSR: Int, toSR: Int): FloatArray {
        if (fromSR == toSR) return x
        val r = fromSR.toDouble() / toSR
        val out = FloatArray(floor(x.size / r).toInt())
        for (i in out.indices) {
            val a = i * r; val b = min(x.size.toDouble(), (i + 1) * r)
            var s = 0.0; var c = 0
            var k = floor(a).toInt()
            while (k < b) { s += x[k]; c++; k++ }
            out[i] = if (c > 0) (s / c).toFloat() else x[min(x.size - 1, floor(a).toInt())]
        }
        return out
    }

    /** WAV (PCM 8/16 bits) -> mono float em 16kHz. Os sons da pasta e da biblioteca são todos WAV. */
    fun decodeWavTo16k(bytes: ByteArray): FloatArray? {
        if (bytes.size < 44 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var channels = 1; var rate = SR; var bits = 16; var format = 1
        var dataStart = -1; var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4); val len = bb.getInt(pos + 4)
            if (id == "fmt ") {
                format = bb.getShort(pos + 8).toInt(); channels = bb.getShort(pos + 10).toInt()
                rate = bb.getInt(pos + 12); bits = bb.getShort(pos + 22).toInt()
            } else if (id == "data") {
                dataStart = pos + 8; dataLen = min(len, bytes.size - dataStart); break
            }
            pos += 8 + len + (len and 1)
        }
        if (dataStart < 0 || format != 1 || channels < 1 || (bits != 16 && bits != 8)) return null
        val bytesPerSample = bits / 8
        val frames = dataLen / (bytesPerSample * channels)
        val mono = FloatArray(frames)
        for (f in 0 until frames) {
            var acc = 0f
            for (ch in 0 until channels) {
                val o = dataStart + (f * channels + ch) * bytesPerSample
                acc += if (bits == 16) bb.getShort(o) / 32768f else ((bytes[o].toInt() and 0xFF) - 128) / 128f
            }
            mono[f] = acc / channels
        }
        return resample(mono, rate, SR)
    }

    /** WAV mono 16 bits, com o volume normalizado (gravação baixinha fica audível) — igual mimicWavBytes */
    fun wavBytes(samples: FloatArray, sr: Int): ByteArray {
        val bb = ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + samples.size * 2); bb.put("WAVE".toByteArray()); bb.put("fmt ".toByteArray())
        bb.putInt(16); bb.putShort(1); bb.putShort(1); bb.putInt(sr); bb.putInt(sr * 2); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(samples.size * 2)
        var peak = 1e-6f
        for (v in samples) peak = max(peak, abs(v))
        val g = min(4f, 0.9f / peak)
        for (v in samples) bb.putShort(((v * g).coerceIn(-1f, 1f) * 32767).roundToInt().toShort())
        return bb.array()
    }

    fun wavDataUrl(samples: FloatArray, sr: Int): String =
        "data:audio/wav;base64," + Base64.encodeToString(wavBytes(samples, sr), Base64.NO_WRAP)
}
