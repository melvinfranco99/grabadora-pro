package com.melvinfranco.grabadorapro.audio

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Post-procesado de grabaciones:
 *
 * - **Reducción de ruido** (0..1): supresión espectral STFT con estimación automática del
 *   perfil de ruido (tramos más silenciosos del audio), ganancia de Wiener con estimador
 *   "decision-directed" de Ephraim–Malah y suavizado temporal/frecuencial para evitar el
 *   "ruido musical".
 * - **Mejora de voz** (0..1): filtro paso-alto anti-rumble, limpieza de graves "embarrados",
 *   realce de presencia e inteligibilidad (2–4 kHz), brillo, compresor de dinámica,
 *   limitador suave y normalización de volumen.
 */
class AudioProcessor(
    private val noiseAmount: Float,
    private val voiceAmount: Float,
) {
    class Cancelled : Exception()

    /**
     * Procesa [input] y escribe el resultado en [output] (WAV 16 bits mono).
     * [startFrame]/[maxFrames] permiten procesar sólo un fragmento (previsualización).
     */
    fun process(
        input: File,
        output: File,
        startFrame: Long = 0,
        maxFrames: Long = -1,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ) {
        val info = Wav.read(input) ?: throw IOException("WAV no válido")
        if (info.bitsPerSample != 16) throw IOException("Sólo se admite PCM de 16 bits")
        val sr = info.sampleRate
        val ch = info.channels
        val start = startFrame.coerceIn(0, info.frameCount)
        val total = if (maxFrames < 0) info.frameCount - start else min(maxFrames, info.frameCount - start)

        val n = if (sr >= 32000) 2048 else 1024
        val hop = n / 4
        val useNr = noiseAmount > 0.001f
        val noisePsd = if (useNr) estimateNoise(input, info, n) else null
        val nr = noisePsd?.let { SpectralDenoiser(n, it, noiseAmount) }
        val voice = if (voiceAmount > 0.001f) VoiceChain(sr, voiceAmount) else null

        val latency = if (nr != null) n - hop else 0
        val frameBytes = ch * 2
        val inBytes = ByteArray(hop * frameBytes)
        val outBytes = ByteArray(hop * 2)
        val inBlock = FloatArray(hop)
        val outBlock = FloatArray(hop)
        var peak = 0f

        output.parentFile?.mkdirs()
        FileOutputStream(output).use { fos ->
            val out = BufferedOutputStream(fos, 1 shl 20)
            out.write(Wav.header(sr, 1, 0))
            FileInputStream(input).use { fis ->
                fis.channel.position(info.dataOffset + start * frameBytes)
                val inp = BufferedInputStream(fis, 1 shl 20)
                var readFrames = 0L
                var written = 0L
                var skip = latency.toLong()
                var lastProgress = -1
                while (written < total) {
                    if (isCancelled()) throw Cancelled()
                    // Leer un bloque (relleno con ceros al final para vaciar la latencia).
                    val want = min(hop.toLong(), total - readFrames).coerceAtLeast(0).toInt()
                    var got = 0
                    if (want > 0) {
                        val bytes = want * frameBytes
                        while (got < bytes) {
                            val r = inp.read(inBytes, got, bytes - got)
                            if (r < 0) break
                            got += r
                        }
                    }
                    val gotFrames = got / frameBytes
                    readFrames += gotFrames
                    for (i in 0 until hop) {
                        inBlock[i] = if (i < gotFrames) {
                            var s = 0f
                            for (c in 0 until ch) {
                                val o = i * frameBytes + c * 2
                                s += ((inBytes[o].toInt() and 0xFF) or (inBytes[o + 1].toInt() shl 8)).toShort() / 32768f
                            }
                            s / ch
                        } else 0f
                    }
                    if (gotFrames == 0 && want > 0) readFrames = total // fichero más corto de lo esperado

                    if (nr != null) nr.processHop(inBlock, outBlock) else inBlock.copyInto(outBlock)

                    var o = 0
                    for (i in 0 until hop) {
                        if (skip > 0) { skip--; continue }
                        if (written >= total) break
                        var y = outBlock[i]
                        if (voice != null) y = voice.process(y)
                        y = softLimit(y)
                        val a = abs(y)
                        if (a > peak) peak = a
                        val s = (y * 32767f).toInt().coerceIn(-32768, 32767)
                        outBytes[o++] = s.toByte()
                        outBytes[o++] = (s shr 8).toByte()
                        written++
                    }
                    out.write(outBytes, 0, o)
                    val p = if (total == 0L) 100 else (written * 100 / total).toInt()
                    if (p != lastProgress) { lastProgress = p; onProgress(p / 100f * 0.97f) }
                }
                out.flush()
            }
        }
        Wav.finalizeHeader(output, sr, 1)

        // Normalización a -1 dBFS cuando se pide mejora de voz (máx. +20 dB).
        if (voice != null && peak > 1e-5f) {
            val gain = min(0.891f / peak, 10f)
            if (abs(gain - 1f) > 0.01f) applyGain(output, gain, isCancelled)
        }
        onProgress(1f)
    }

    private fun softLimit(x: Float): Float {
        val a = abs(x)
        if (a <= 0.9f) return x
        val y = 0.9f + 0.1f * tanh((a - 0.9f) / 0.1f)
        return if (x < 0) -y else y
    }

    private fun applyGain(file: File, gain: Float, isCancelled: () -> Boolean) {
        RandomAccessFile(file, "rw").use { raf ->
            val buf = ByteArray(1 shl 20)
            var pos = Wav.HEADER_SIZE.toLong()
            val len = raf.length()
            while (pos < len) {
                if (isCancelled()) throw Cancelled()
                raf.seek(pos)
                val r = raf.read(buf, 0, min(buf.size.toLong(), len - pos).toInt() and 1.inv())
                if (r <= 0) break
                val bb = ByteBuffer.wrap(buf, 0, r).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until r / 2) {
                    val s = (bb.getShort(i * 2) * gain).toInt().coerceIn(-32768, 32767)
                    bb.putShort(i * 2, s.toShort())
                }
                raf.seek(pos)
                raf.write(buf, 0, r)
                pos += r
            }
        }
    }

    /**
     * Estima el espectro de potencia del ruido de fondo promediando el 15 % de las tramas
     * con menos energía (muestreadas a lo largo de todo el fichero).
     */
    private fun estimateNoise(file: File, info: WavInfo, n: Int): FloatArray {
        val bins = n / 2 + 1
        val fft = RealFft(n)
        val win = hann(n)
        val totalFrames = info.frameCount
        val maxSamples = 1500
        val count = max(1L, min(maxSamples.toLong(), (totalFrames - n) / (n / 4))).toInt()
        val spectra = Array(count) { FloatArray(bins) }
        val energy = FloatArray(count)
        val frameBytes = info.channels * 2
        val bytes = ByteArray(n * frameBytes)
        val frame = FloatArray(n)
        val re = FloatArray(bins)
        val im = FloatArray(bins)
        RandomAccessFile(file, "r").use { raf ->
            for (f in 0 until count) {
                val pos = if (count == 1) 0L else (totalFrames - n).coerceAtLeast(0) * f / (count - 1)
                raf.seek(info.dataOffset + pos * frameBytes)
                val r = raf.read(bytes)
                val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until n) {
                    var s = 0f
                    if ((i + 1) * frameBytes <= r) {
                        for (c in 0 until info.channels) s += bb.getShort((i * info.channels + c) * 2) / 32768f
                        s /= info.channels
                    }
                    frame[i] = s * win[i]
                }
                fft.forward(frame, re, im)
                var e = 0f
                val spec = spectra[f]
                for (k in 0 until bins) {
                    val p = re[k] * re[k] + im[k] * im[k]
                    spec[k] = p
                    e += p
                }
                energy[f] = e
            }
        }
        val order = (0 until count).sortedBy { energy[it] }
        val take = max(1, count * 15 / 100)
        val noise = FloatArray(bins)
        for (i in 0 until take) {
            val s = spectra[order[i]]
            for (k in 0 until bins) noise[k] += s[k]
        }
        for (k in 0 until bins) noise[k] = max(noise[k] / take, 1e-10f)
        return noise
    }

    companion object {
        fun hann(n: Int) = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / n)).toFloat() }
    }
}

/** Supresor espectral en streaming (STFT, ventana Hann, solape 75 %). */
private class SpectralDenoiser(private val n: Int, noisePsd: FloatArray, amount: Float) {
    private val hop = n / 4
    private val bins = n / 2 + 1
    private val fft = RealFft(n)
    private val win = AudioProcessor.hann(n)
    private val inBuf = FloatArray(n)
    private val outAcc = FloatArray(n)
    private val frame = FloatArray(n)
    private val re = FloatArray(bins)
    private val im = FloatArray(bins)
    private val noise = noisePsd.copyOf()
    private val noiseEnergy0 = noisePsd.sum()
    private val gPrev = FloatArray(bins) { 1f }
    private val gammaPrev = FloatArray(bins) { 1f }
    private val gSmooth = FloatArray(bins) { 1f }
    private val gain = FloatArray(bins)
    private val power = FloatArray(bins)

    // Parámetros dependientes de la intensidad.
    private val beta = 1f + 2f * amount // sobre-sustracción
    private val floor = 10f.pow(-(4f + 36f * amount) / 20f) // -4 dB .. -40 dB
    private val synthScale = 1f / (1.5f * n) // Hann² con solape 75 % suma 1.5; 1/n de la IFFT

    fun processHop(input: FloatArray, output: FloatArray) {
        System.arraycopy(inBuf, hop, inBuf, 0, n - hop)
        System.arraycopy(input, 0, inBuf, n - hop, hop)
        for (i in 0 until n) frame[i] = inBuf[i] * win[i]
        fft.forward(frame, re, im)

        var e = 0f
        for (k in 0 until bins) { val p = re[k] * re[k] + im[k] * im[k]; power[k] = p; e += p }
        // Seguimiento lento del ruido en tramas claramente sin voz.
        if (e < 1.5f * noise.sum() && e < 4f * noiseEnergy0) {
            for (k in 0 until bins) noise[k] = 0.97f * noise[k] + 0.03f * power[k]
        }
        for (k in 0 until bins) {
            val gamma = power[k] / (beta * noise[k] + 1e-12f)
            // xi mínimo: evita números desnormalizados (muy lentos) y reduce el ruido musical.
            val xi = max(0.98f * gPrev[k] * gPrev[k] * gammaPrev[k] + 0.02f * max(gamma - 1f, 0f), 1e-4f)
            val g = xi / (1f + xi)
            gPrev[k] = g
            gammaPrev[k] = min(gamma, 1000f)
            gain[k] = g
        }
        // Suavizado en frecuencia (3 bins) y en tiempo (ataque rápido, relajación lenta).
        var prev = gain[0]
        for (k in 0 until bins) {
            val cur = gain[k]
            val next = if (k + 1 < bins) gain[k + 1] else cur
            val g = 0.25f * prev + 0.5f * cur + 0.25f * next
            prev = cur
            val s = if (g > gSmooth[k]) g else 0.6f * gSmooth[k] + 0.4f * g
            gSmooth[k] = s
        }
        for (k in 0 until bins) {
            val g = floor + (1f - floor) * gSmooth[k]
            re[k] *= g; im[k] *= g
        }
        fft.inverse(re, im, frame)
        for (i in 0 until n) outAcc[i] += frame[i] * win[i] * synthScale
        System.arraycopy(outAcc, 0, output, 0, hop)
        System.arraycopy(outAcc, hop, outAcc, 0, n - hop)
        java.util.Arrays.fill(outAcc, n - hop, n, 0f)
    }
}

/** Cadena de mejora de voz en el dominio temporal. */
private class VoiceChain(sr: Int, v: Float) {
    private val filters = buildList {
        add(Biquad.highPass(sr, 60f + 50f * v, 0.707f))
        add(Biquad.peaking(sr, 250f, 1.0f, -4f * v))
        add(Biquad.peaking(sr, 3000f, 0.9f, 6f * v))
        if (sr > 20000) add(Biquad.highShelf(sr, 8000f, 3f * v))
    }.toTypedArray()

    private val threshold = -24f
    private val ratio = 1f + 3f * v
    private val makeup = 0.5f * -threshold * (1f - 1f / ratio)
    private val attack = exp(-1f / (0.005f * sr))
    private val release = exp(-1f / (0.150f * sr))
    private var env = 0f
    private var antiDenormal = 1e-12f

    fun process(x: Float): Float {
        // Señal inaudible alternante (pasa el paso-alto) para que los filtros no caigan en
        // números desnormalizados durante los silencios, que son muy lentos en la CPU.
        antiDenormal = -antiDenormal
        var y = x + antiDenormal
        for (f in filters) y = f.process(y)
        val lvl = abs(y)
        env = if (lvl > env) attack * env + (1 - attack) * lvl else release * env + (1 - release) * lvl
        val envDb = 20f * log10(env + 1e-9f)
        val over = envDb - threshold
        val gainDb = (if (over > 0) -over * (1f - 1f / ratio) else 0f) + makeup
        return y * 10f.pow(gainDb / 20f)
    }
}

/** Filtro biquad (fórmulas del "Audio EQ Cookbook" de R. Bristow-Johnson). */
private class Biquad(b0: Float, b1: Float, b2: Float, a0: Float, a1: Float, a2: Float) {
    private val b0 = b0 / a0
    private val b1 = b1 / a0
    private val b2 = b2 / a0
    private val a1 = a1 / a0
    private val a2 = a2 / a0
    private var z1 = 0f
    private var z2 = 0f

    fun process(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    companion object {
        fun highPass(sr: Int, f: Float, q: Float): Biquad {
            val w = 2 * PI.toFloat() * f / sr
            val c = cos(w); val alpha = sin(w) / (2 * q)
            return Biquad((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }

        fun peaking(sr: Int, f: Float, q: Float, gainDb: Float): Biquad {
            val a = 10f.pow(gainDb / 40f)
            val w = 2 * PI.toFloat() * f / sr
            val c = cos(w); val alpha = sin(w) / (2 * q)
            return Biquad(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
        }

        fun highShelf(sr: Int, f: Float, gainDb: Float): Biquad {
            val a = 10f.pow(gainDb / 40f)
            val w = 2 * PI.toFloat() * f / sr
            val c = cos(w); val alpha = sin(w) / 2 * sqrt(2f)
            val sa = 2 * sqrt(a) * alpha
            return Biquad(
                a * ((a + 1) + (a - 1) * c + sa),
                -2 * a * ((a - 1) + (a + 1) * c),
                a * ((a + 1) + (a - 1) * c - sa),
                (a + 1) - (a - 1) * c + sa,
                2 * ((a - 1) - (a + 1) * c),
                (a + 1) - (a - 1) * c - sa,
            )
        }
    }
}
