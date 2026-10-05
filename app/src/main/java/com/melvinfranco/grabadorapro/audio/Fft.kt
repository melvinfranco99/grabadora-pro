package com.melvinfranco.grabadorapro.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** FFT compleja radix-2, in-place, con tablas precalculadas. */
class Fft(val n: Int) {
    private val levels = Integer.numberOfTrailingZeros(n)
    private val cosT = FloatArray(n / 2)
    private val sinT = FloatArray(n / 2)
    private val rev = IntArray(n)

    init {
        require(n >= 2 && n and (n - 1) == 0) { "n debe ser potencia de 2" }
        for (i in 0 until n / 2) {
            cosT[i] = cos(2 * PI * i / n).toFloat()
            sinT[i] = sin(2 * PI * i / n).toFloat()
        }
        for (i in 0 until n) rev[i] = Integer.reverse(i) ushr (32 - levels)
    }

    /** Transformada directa (inverse=false) o inversa sin normalizar (inverse=true). */
    fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        val sign = if (inverse) 1f else -1f
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var i = 0
            while (i < n) {
                var k = 0
                for (j in i until i + half) {
                    val wr = cosT[k]
                    val wi = sign * sinT[k]
                    val l = j + half
                    val tr = re[l] * wr - im[l] * wi
                    val ti = re[l] * wi + im[l] * wr
                    re[l] = re[j] - tr
                    im[l] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                i += size
            }
            size *= 2
        }
    }
}

/**
 * FFT de señal real de tamaño [n] calculada con una FFT compleja de n/2 puntos
 * (el doble de rápida). El espectro tiene n/2+1 bins.
 */
class RealFft(val n: Int) {
    private val m = n / 2
    private val half = Fft(m)
    private val zr = FloatArray(m)
    private val zi = FloatArray(m)
    private val cosT = FloatArray(m + 1) { cos(2 * PI * it / n).toFloat() }
    private val sinT = FloatArray(m + 1) { sin(2 * PI * it / n).toFloat() }

    fun forward(x: FloatArray, outRe: FloatArray, outIm: FloatArray) {
        for (i in 0 until m) { zr[i] = x[2 * i]; zi[i] = x[2 * i + 1] }
        half.transform(zr, zi, false)
        for (k in 0..m) {
            val a = zr[k % m]; val b = zi[k % m]
            val j = (m - k) % m
            val c = zr[j]; val d = zi[j]
            val feR = (a + c) * 0.5f; val feI = (b - d) * 0.5f
            val foR = (b + d) * 0.5f; val foI = -(a - c) * 0.5f
            val cs = cosT[k]; val sn = sinT[k]
            outRe[k] = feR + cs * foR + sn * foI
            outIm[k] = feI + cs * foI - sn * foR
        }
    }

    /** Inversa sin normalizar: inverse(forward(x)) = n·x. */
    fun inverse(re: FloatArray, im: FloatArray, out: FloatArray) {
        for (k in 0 until m) {
            val a = re[k]; val b = im[k]
            val c = re[m - k]; val d = -im[m - k]
            val feR = (a + c) * 0.5f; val feI = (b + d) * 0.5f
            val dR = (a - c) * 0.5f; val dI = (b - d) * 0.5f
            val cs = cosT[k]; val sn = sinT[k]
            val foR = dR * cs - dI * sn
            val foI = dR * sn + dI * cs
            zr[k] = feR - foI
            zi[k] = feI + foR
        }
        half.transform(zr, zi, true)
        for (i in 0 until m) { out[2 * i] = 2 * zr[i]; out[2 * i + 1] = 2 * zi[i] }
    }
}
