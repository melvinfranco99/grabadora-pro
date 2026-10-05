package com.melvinfranco.grabadorapro.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Información básica de un WAV PCM de 16 bits. */
data class WavInfo(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val dataOffset: Long,
    val dataSize: Long,
) {
    val frameCount: Long get() = dataSize / (channels * bitsPerSample / 8)
    val durationMs: Long get() = if (sampleRate == 0) 0 else frameCount * 1000 / sampleRate
}

object Wav {
    const val HEADER_SIZE = 44

    fun header(sampleRate: Int, channels: Int, dataSize: Long): ByteArray {
        val byteRate = sampleRate * channels * 2
        val bb = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray())
        bb.putInt((dataSize + 36).coerceAtMost(0xFFFFFFFFL).toInt())
        bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray())
        bb.putInt(16)
        bb.putShort(1) // PCM
        bb.putShort(channels.toShort())
        bb.putInt(sampleRate)
        bb.putInt(byteRate)
        bb.putShort((channels * 2).toShort())
        bb.putShort(16)
        bb.put("data".toByteArray())
        bb.putInt(dataSize.coerceAtMost(0xFFFFFFFFL).toInt())
        return bb.array()
    }

    /** Reescribe los tamaños de la cabecera según la longitud real del fichero. */
    fun finalizeHeader(file: File, sampleRate: Int, channels: Int) {
        RandomAccessFile(file, "rw").use { raf ->
            val dataSize = (raf.length() - HEADER_SIZE).coerceAtLeast(0)
            raf.seek(0)
            raf.write(header(sampleRate, channels, dataSize))
        }
    }

    fun read(file: File): WavInfo? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < HEADER_SIZE) return null
            val head = ByteArray(12)
            raf.readFully(head)
            if (String(head, 0, 4) != "RIFF" || String(head, 8, 4) != "WAVE") return null
            var sampleRate = 0
            var channels = 0
            var bits = 0
            var pos = 12L
            val chunk = ByteArray(8)
            while (pos + 8 <= raf.length()) {
                raf.seek(pos)
                raf.readFully(chunk)
                val id = String(chunk, 0, 4)
                val size = ByteBuffer.wrap(chunk, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                if (id == "fmt ") {
                    val fmt = ByteArray(16)
                    raf.readFully(fmt)
                    val b = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                    b.short // formato
                    channels = b.short.toInt()
                    sampleRate = b.int
                    b.int; b.short
                    bits = b.short.toInt()
                } else if (id == "data") {
                    val available = raf.length() - (pos + 8)
                    // Si la grabación se cortó (cabecera sin finalizar) usamos el tamaño real.
                    val real = if (size == 0L || size > available) available else size
                    if (sampleRate == 0) return null
                    return WavInfo(sampleRate, channels, bits, pos + 8, real - real % (channels * 2))
                }
                pos += 8 + size + (size and 1)
            }
            null
        }
    }.getOrNull()
}
