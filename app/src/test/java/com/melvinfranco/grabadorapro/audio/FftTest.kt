package com.melvinfranco.grabadorapro.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class FftTest {
    @Test
    fun realFftMatchesComplexFft() {
        val n = 2048
        val rnd = Random(3)
        val x = FloatArray(n) { rnd.nextFloat() * 2 - 1 }
        val re = x.copyOf(); val im = FloatArray(n)
        Fft(n).transform(re, im, false)
        val rf = RealFft(n)
        val r2 = FloatArray(n / 2 + 1); val i2 = FloatArray(n / 2 + 1)
        rf.forward(x, r2, i2)
        for (k in 0..n / 2) {
            assertEquals(re[k], r2[k], 1e-2f)
            assertEquals(im[k], i2[k], 1e-2f)
        }
        val back = FloatArray(n)
        rf.inverse(r2, i2, back)
        for (i in 0 until n) assertEquals(x[i], back[i] / n, 1e-4f)
    }
}
