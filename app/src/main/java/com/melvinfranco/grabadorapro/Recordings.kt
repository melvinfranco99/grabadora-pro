package com.melvinfranco.grabadorapro

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.melvinfranco.grabadorapro.audio.Wav
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Recording(
    val file: File,
    val name: String,
    val date: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val enhanced: Boolean,
)

/** Las grabaciones viven en la carpeta privada de la app: sólo aparecen las hechas con ella. */
object Recordings {
    const val ENHANCED_SUFFIX = " (mejorada)"

    fun dir(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        return File(base, "Grabaciones").apply { mkdirs() }
    }

    fun previewDir(context: Context): File = File(context.cacheDir, "preview").apply { mkdirs() }

    fun newFile(context: Context): File {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
        return uniqueFile(dir(context), "Grabación $stamp")
    }

    fun enhancedFileFor(context: Context, source: File): File =
        uniqueFile(dir(context), source.nameWithoutExtension.removeSuffix(ENHANCED_SUFFIX) + ENHANCED_SUFFIX)

    private fun uniqueFile(dir: File, base: String): File {
        var f = File(dir, "$base.wav")
        var i = 2
        while (f.exists()) f = File(dir, "$base ($i).wav").also { i++ }
        return f
    }

    fun list(context: Context, exclude: File? = null): List<Recording> =
        dir(context).listFiles { f -> f.isFile && f.extension.equals("wav", true) }
            .orEmpty()
            .filter { it != exclude }
            .map { f ->
                val info = Wav.read(f)
                Recording(
                    file = f,
                    name = f.nameWithoutExtension,
                    date = f.lastModified(),
                    durationMs = info?.durationMs ?: 0,
                    sizeBytes = f.length(),
                    enhanced = f.nameWithoutExtension.contains(ENHANCED_SUFFIX),
                )
            }
            .sortedByDescending { it.date }

    /** Repara cabeceras de grabaciones interrumpidas (p. ej. si el sistema mató la app). */
    fun repairHeaders(context: Context, exclude: File?) {
        dir(context).listFiles().orEmpty().filter { it != exclude && it.extension == "wav" }.forEach { f ->
            val info = Wav.read(f) ?: return@forEach
            if (info.dataOffset == Wav.HEADER_SIZE.toLong() && readDeclaredSize(f) != info.dataSize) {
                runCatching { Wav.finalizeHeader(f, info.sampleRate, info.channels) }
            }
        }
    }

    private fun readDeclaredSize(f: File): Long = runCatching {
        java.io.RandomAccessFile(f, "r").use { raf ->
            raf.seek(40)
            val b = ByteArray(4); raf.readFully(b)
            (b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or
                ((b[2].toLong() and 0xFF) shl 16) or ((b[3].toLong() and 0xFF) shl 24)
        }
    }.getOrDefault(-1L)

    fun rename(rec: Recording, newName: String): File? {
        val clean = newName.trim().replace(Regex("[/\\\\:*?\"<>|]"), "_")
        if (clean.isEmpty()) return null
        val target = File(rec.file.parentFile, "$clean.wav")
        if (target.exists()) return null
        return if (rec.file.renameTo(target)) target else null
    }

    /** Copia la grabación a la carpeta pública Música/GrabadoraPro. */
    fun exportToMusic(context: Context, file: File): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/GrabadoraPro")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out, 1 shl 16) } }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            }
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }
}

object Format {
    fun duration(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }

    fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
        else -> "%d KB".format(bytes / 1024)
    }

    fun date(ms: Long): String = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ms))

    fun maxDuration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "$m min"
            m == 0 -> "$h h"
            else -> "$h h $m min"
        }
    }
}
