package com.melvinfranco.grabadorapro

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.melvinfranco.grabadorapro.audio.Wav
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.max

enum class RecStatus { IDLE, RECORDING, PAUSED }

data class RecState(
    val status: RecStatus = RecStatus.IDLE,
    val elapsedMs: Long = 0,
    val maxMs: Long = 0,
    val level: Float = 0f,
    val file: File? = null,
)

sealed class RecEvent {
    data class Finished(val file: File, val autoStopped: Boolean) : RecEvent()
    data class Error(val message: String) : RecEvent()
}

/** Estado compartido entre el servicio y la interfaz (mismo proceso). */
object Recorder {
    internal val _state = MutableStateFlow(RecState())
    val state: StateFlow<RecState> = _state.asStateFlow()
    internal val _events = MutableSharedFlow<RecEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<RecEvent> = _events.asSharedFlow()

    fun start(context: Context) = send(context, RecorderService.ACTION_START)
    fun pause(context: Context) = send(context, RecorderService.ACTION_PAUSE)
    fun resume(context: Context) = send(context, RecorderService.ACTION_RESUME)
    fun stop(context: Context) = send(context, RecorderService.ACTION_STOP)

    private fun send(context: Context, action: String) {
        val i = Intent(context, RecorderService::class.java).setAction(action)
        if (action == RecorderService.ACTION_START) ContextCompat.startForegroundService(context, i)
        else if (state.value.status != RecStatus.IDLE) context.startService(i)
    }
}

class RecorderService : Service() {
    companion object {
        const val ACTION_START = "start"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_STOP = "stop"
        private const val CHANNEL = "grabacion"
        private const val NOTIF_ID = 1
    }

    @Volatile private var running = false
    @Volatile private var paused = false
    private var thread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotifSecond = -1L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!running) startRecording()
            ACTION_PAUSE -> if (running) { paused = true; publish(status = RecStatus.PAUSED) }
            ACTION_RESUME -> if (running) { paused = false; publish(status = RecStatus.RECORDING) }
            ACTION_STOP -> if (running) running = false else stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun publish(status: RecStatus? = null, elapsed: Long? = null, level: Float? = null) {
        val s = Recorder._state.value
        val ns = s.copy(
            status = status ?: s.status,
            elapsedMs = elapsed ?: s.elapsedMs,
            level = level ?: s.level,
        )
        Recorder._state.value = ns
        val sec = ns.elapsedMs / 1000
        if (status != null || sec != lastNotifSecond) {
            lastNotifSecond = sec
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(ns))
        }
    }

    private fun startRecording() {
        createChannel()
        val prefs = Prefs(this)
        val maxMs = prefs.maxMinutes * 60_000L
        val file = Recordings.newFile(this)
        Recorder._state.value = RecState(RecStatus.RECORDING, 0, maxMs, 0f, file)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(Recorder.state.value), type)
        } catch (e: Exception) {
            fail("No se pudo iniciar la grabación en segundo plano: ${e.message}")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("Falta el permiso de micrófono")
            return
        }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GrabadoraPro:rec")
            .apply { acquire(maxMs + 10 * 60_000L) }
        running = true
        paused = false
        thread = Thread({ recordLoop(file, prefs.sampleRate, maxMs, prefs.liveNoiseSuppressor) }, "grabacion").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(preferredRate: Int): Pair<AudioRecord, Int>? {
        for (rate in listOf(preferredRate, 48000, 44100, 16000).distinct()) {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min * 4, rate))
            } catch (e: Exception) { continue }
            if (rec.state == AudioRecord.STATE_INITIALIZED) return rec to rate
            rec.release()
        }
        return null
    }

    private fun recordLoop(file: File, preferredRate: Int, maxMs: Long, liveNs: Boolean) {
        val created = createAudioRecord(preferredRate)
        if (created == null) { fail("No se pudo acceder al micrófono"); return }
        val (rec, rate) = created
        var ns: NoiseSuppressor? = null
        var agc: AutomaticGainControl? = null
        if (liveNs && NoiseSuppressor.isAvailable()) ns = runCatching { NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } }.getOrNull()
        if (AutomaticGainControl.isAvailable()) agc = runCatching { AutomaticGainControl.create(rec.audioSessionId)?.apply { enabled = false } }.getOrNull()

        val maxFrames = maxMs * rate / 1000
        var frames = 0L
        var autoStopped = false
        val buf = ShortArray(rate / 20) // bloques de 50 ms
        val bytes = ByteArray(buf.size * 2)
        try {
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(0)
                raf.write(Wav.header(rate, 1, 0))
                rec.startRecording()
                var lastHeader = 0L
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) { if (n < 0) throw IllegalStateException("Error de lectura ($n)"); continue }
                    var peak = 0
                    for (i in 0 until n) peak = max(peak, abs(buf[i].toInt()))
                    if (!paused) {
                        val take = minOf(n.toLong(), maxFrames - frames).toInt()
                        for (i in 0 until take) {
                            bytes[i * 2] = buf[i].toInt().toByte()
                            bytes[i * 2 + 1] = (buf[i].toInt() shr 8).toByte()
                        }
                        raf.write(bytes, 0, take * 2)
                        frames += take
                        if (frames >= maxFrames) { autoStopped = true; running = false }
                        // Cabecera válida cada ~10 s por si el sistema mata el proceso.
                        if (frames - lastHeader > rate * 10L) {
                            lastHeader = frames
                            val pos = raf.filePointer
                            raf.seek(0); raf.write(Wav.header(rate, 1, frames * 2)); raf.seek(pos)
                        }
                    }
                    publish(elapsed = frames * 1000 / rate, level = if (paused) 0f else peak / 32768f)
                }
                raf.seek(0)
                raf.write(Wav.header(rate, 1, frames * 2))
            }
        } catch (e: Exception) {
            Recorder._events.tryEmit(RecEvent.Error("Error durante la grabación: ${e.message}"))
        } finally {
            runCatching { rec.stop() }
            rec.release()
            ns?.release(); agc?.release()
        }
        if (frames == 0L) file.delete()
        else Recorder._events.tryEmit(RecEvent.Finished(file, autoStopped))
        finish(autoStopped)
    }

    private fun fail(msg: String) {
        Recorder._events.tryEmit(RecEvent.Error(msg))
        Recorder._state.value.file?.let { if (it.exists() && it.length() <= Wav.HEADER_SIZE) it.delete() }
        finish(false)
    }

    private fun finish(autoStopped: Boolean) {
        running = false
        Recorder._state.value = RecState()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (autoStopped) {
            val n = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_mic)
                .setContentTitle(getString(R.string.auto_stopped_title))
                .setContentText(getString(R.string.auto_stopped_text, Format.maxDuration(Prefs(this).maxMinutes)))
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID + 1, n)
        }
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun actionIntent(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, RecorderService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildNotification(s: RecState): Notification {
        val paused = s.status == RecStatus.PAUSED
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(getString(if (paused) R.string.notif_paused else R.string.notif_recording))
            .setContentText("${Format.duration(s.elapsedMs)} / ${Format.duration(s.maxMs)}")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openAppIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (paused) b.addAction(0, getString(R.string.resume), actionIntent(ACTION_RESUME, 1))
        else b.addAction(0, getString(R.string.pause), actionIntent(ACTION_PAUSE, 2))
        b.addAction(0, getString(R.string.stop), actionIntent(ACTION_STOP, 3))
        return b.build()
    }
}
