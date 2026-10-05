package com.melvinfranco.grabadorapro

import android.content.Context
import com.melvinfranco.grabadorapro.audio.AudioProcessor
import com.melvinfranco.grabadorapro.audio.Wav
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class ProcState(
    val source: File,
    val preview: Boolean,
    val progress: Float = 0f,
    val running: Boolean = true,
    val result: File? = null,
    val error: String? = null,
)

/** Ejecuta el post-procesado fuera de la actividad para que sobreviva a rotaciones. */
object Processing {
    const val PREVIEW_SECONDS = 20

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val _state = MutableStateFlow<ProcState?>(null)
    val state: StateFlow<ProcState?> = _state.asStateFlow()

    val isRunning get() = _state.value?.running == true

    fun start(context: Context, source: File, noise: Float, voice: Float, previewFromMs: Long? = null) {
        if (isRunning) return
        val app = context.applicationContext
        val preview = previewFromMs != null
        _state.value = ProcState(source, preview)
        job = scope.launch {
            val out = if (preview) File(Recordings.previewDir(app), "preview.wav")
            else Recordings.enhancedFileFor(app, source)
            val tmp = File(out.parentFile, out.name + ".part")
            try {
                val info = Wav.read(source) ?: error("El fichero no es un WAV válido")
                val startFrame = (previewFromMs ?: 0) * info.sampleRate / 1000
                val maxFrames = if (preview) PREVIEW_SECONDS.toLong() * info.sampleRate else -1L
                AudioProcessor(noise, voice).process(
                    source, tmp, startFrame, maxFrames,
                    onProgress = { p -> _state.value = _state.value?.copy(progress = p) },
                    isCancelled = { !isActive },
                )
                out.delete()
                if (!tmp.renameTo(out)) error("No se pudo guardar el resultado")
                _state.value = ProcState(source, preview, 1f, running = false, result = out)
            } catch (e: AudioProcessor.Cancelled) {
                tmp.delete()
                _state.value = null
            } catch (e: Throwable) {
                tmp.delete()
                _state.value = ProcState(source, preview, 0f, running = false, error = e.message ?: e.toString())
            }
        }
    }

    fun cancel() { job?.cancel() }

    /** La interfaz ya ha mostrado el resultado. */
    fun consume() { if (!isRunning) _state.value = null }
}
