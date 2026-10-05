package com.melvinfranco.grabadorapro.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class AudioProcessorTest {
    private val sr = 48000

    /** 1 s de ruido, 1 s de "voz" (tonos armónicos) + ruido, alternando. */
    private fun makeSignal(seconds: Int): Pair<ShortArray, BooleanArray> {
        val rnd = Random(1)
        val n = sr * seconds
        val s = ShortArray(n)
        val voiced = BooleanArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val v = (i / sr) % 2 == 1
            voiced[i] = v
            var x = (rnd.nextDouble() * 2 - 1) * 0.03
            if (v) x += 0.3 * (sin(2 * PI * 180 * t) + 0.5 * sin(2 * PI * 360 * t) + 0.3 * sin(2 * PI * 1250 * t))
            s[i] = (x * 32767).toInt().coerceIn(-32768, 32767).toShort()
        }
        return s to voiced
    }

    private fun write(file: File, s: ShortArray) {
        val bb = ByteBuffer.allocate(s.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        s.forEach { bb.putShort(it) }
        file.writeBytes(Wav.header(sr, 1, s.size * 2L) + bb.array())
    }

    private fun readPcm(file: File): ShortArray {
        val info = Wav.read(file)!!
        val bytes = file.readBytes()
        val bb = ByteBuffer.wrap(bytes, info.dataOffset.toInt(), info.dataSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray((info.dataSize / 2).toInt()) { bb.short }
    }

    private fun rms(s: ShortArray, mask: BooleanArray, want: Boolean): Double {
        var sum = 0.0; var c = 0
        for (i in s.indices) if (mask[i] == want && i > sr / 10) { sum += s[i].toDouble() * s[i]; c++ }
        return sqrt(sum / c)
    }

    @Test
    fun bypassIsIdentity() {
        val (sig, _) = makeSignal(3)
        val inF = File.createTempFile("gp_in", ".wav"); val outF = File.createTempFile("gp_out", ".wav")
        write(inF, sig)
        AudioProcessor(0f, 0f).process(inF, outF)
        val out = readPcm(outF)
        assertEquals(sig.size, out.size)
        for (i in sig.indices) assertTrue(kotlin.math.abs(sig[i] - out[i]) <= 1)
    }

    @Test
    fun noiseReductionReducesNoiseKeepsVoice() {
        val (sig, voiced) = makeSignal(10)
        val inF = File.createTempFile("gp_in", ".wav"); val outF = File.createTempFile("gp_out", ".wav")
        write(inF, sig)
        AudioProcessor(1f, 0f).process(inF, outF)
        val out = readPcm(outF)
        assertEquals(sig.size, out.size)
        val noiseIn = rms(sig, voiced, false); val noiseOut = rms(out, voiced, false)
        val voiceIn = rms(sig, voiced, true); val voiceOut = rms(out, voiced, true)
        val snrIn = 20 * kotlin.math.log10(voiceIn / noiseIn)
        val snrOut = 20 * kotlin.math.log10(voiceOut / noiseOut)
        println("ruido: %.1f -> %.1f  voz: %.1f -> %.1f  SNR %.1f dB -> %.1f dB".format(noiseIn, noiseOut, voiceIn, voiceOut, snrIn, snrOut))
        assertTrue(snrOut - snrIn > 20)
        assertTrue(voiceOut / voiceIn > 0.85)
    }

    @Test
    fun voiceEnhanceNormalizes() {
        val (sig, _) = makeSignal(4)
        val inF = File.createTempFile("gp_in", ".wav"); val outF = File.createTempFile("gp_out", ".wav")
        write(inF, sig)
        AudioProcessor(0.5f, 1f).process(inF, outF)
        val peak = readPcm(outF).maxOf { kotlin.math.abs(it.toInt()) }
        println("pico: $peak")
        assertTrue(peak in 27000..32767)
    }

    @Test
    fun speed() {
        val (sig, _) = makeSignal(120)
        val inF = File.createTempFile("gp_in", ".wav"); val outF = File.createTempFile("gp_out", ".wav")
        write(inF, sig)
        AudioProcessor(0.7f, 0.7f).process(inF, outF) // calentamiento JIT
        for ((nr, v) in listOf(0.7f to 0.7f, 0.7f to 0f, 0f to 0.7f)) {
            val t0 = System.nanoTime()
            AudioProcessor(nr, v).process(inF, outF)
            val ms = (System.nanoTime() - t0) / 1e6
            println("ruido=$nr voz=$v: 120 s de audio procesados en %.0f ms (%.0fx tiempo real)".format(ms, 120000 / ms))
        }
    }
}
