package com.melvinfranco.grabadorapro

import android.media.MediaPlayer
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.melvinfranco.grabadorapro.audio.Wav
import com.melvinfranco.grabadorapro.databinding.ActivityPlayerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PlayerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_FILE = "file"
    }

    private lateinit var b: ActivityPlayerBinding
    private lateinit var prefs: Prefs
    private lateinit var file: File
    private var player: MediaPlayer? = null
    private var previewFile: File? = null
    private var playingPreview = false
    private var previewStartMs = 0L
    private var userSeeking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        prefs = Prefs(this)

        file = File(intent.getStringExtra(EXTRA_FILE) ?: run { finish(); return })
        if (!file.exists()) { finish(); return }
        bindInfo()

        b.noise.value = prefs.lastNoise.toFloat()
        b.voice.value = prefs.lastVoice.toFloat()
        updateLabels()
        b.noise.addOnChangeListener { _, _, _ -> updateLabels(); invalidatePreview() }
        b.voice.addOnChangeListener { _, _, _ -> updateLabels(); invalidatePreview() }

        b.btnPlay.setOnClickListener { togglePlay() }
        b.btnBack10.setOnClickListener { seekBy(-10_000) }
        b.btnFwd10.setOnClickListener { seekBy(10_000) }
        b.seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) b.position.text = Format.duration(p.toLong())
            }
            override fun onStartTrackingTouch(sb: SeekBar) { userSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                userSeeking = false
                player?.seekTo(sb.progress)
            }
        })
        b.abGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) switchSource(id == R.id.btnProcessed)
        }

        b.btnPreview.setOnClickListener {
            saveSliders()
            val pos = if (playingPreview) previewStartMs + (player?.currentPosition ?: 0) else player?.currentPosition?.toLong() ?: 0L
            val total = (Wav.read(file)?.durationMs ?: 0L)
            previewStartMs = pos.coerceAtMost(total - Processing.PREVIEW_SECONDS * 1000L).coerceAtLeast(0)
            pausePlayer()
            Processing.start(this, file, b.noise.value / 100f, b.voice.value / 100f, previewFromMs = previewStartMs)
        }
        b.btnApply.setOnClickListener {
            saveSliders()
            pausePlayer()
            Processing.start(this, file, b.noise.value / 100f, b.voice.value / 100f)
        }
        b.btnCancel.setOnClickListener { Processing.cancel() }

        loadPlayer(file, 0)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Processing.state.collect(::renderProcessing) }
                launch {
                    while (isActive) {
                        val p = player
                        if (p != null && !userSeeking) {
                            b.seek.progress = p.currentPosition
                            b.position.text = Format.duration(p.currentPosition.toLong())
                        }
                        delay(200)
                    }
                }
            }
        }
    }

    private fun bindInfo() {
        supportActionBar?.title = file.nameWithoutExtension
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) { Wav.read(file) }
            b.info.text = buildString {
                append(Format.date(file.lastModified()))
                if (info != null) append(" · ${Format.duration(info.durationMs)} · ${info.sampleRate / 1000.0} kHz")
                append(" · ${Format.size(file.length())}")
            }
        }
    }

    private fun updateLabels() {
        b.noiseValue.text = b.noise.value.toInt().toString()
        b.voiceValue.text = b.voice.value.toInt().toString()
    }

    private fun saveSliders() {
        prefs.lastNoise = b.noise.value.toInt()
        prefs.lastVoice = b.voice.value.toInt()
    }

    /** Si cambian los ajustes, la previsualización anterior ya no es representativa. */
    private fun invalidatePreview() {
        if (previewFile == null || Processing.isRunning) return
        previewFile = null
        if (playingPreview) {
            b.abGroup.check(R.id.btnOriginal)
        }
        b.btnProcessed.isEnabled = false
    }

    private fun renderProcessing(s: ProcState?) {
        val mine = s != null && s.source == file
        val running = mine && s!!.running
        b.procBox.isVisible = running
        b.btnPreview.isEnabled = !Processing.isRunning
        b.btnApply.isEnabled = !Processing.isRunning
        b.noise.isEnabled = !running
        b.voice.isEnabled = !running
        if (running) {
            b.procLabel.text = getString(if (s!!.preview) R.string.processing_preview else R.string.processing_full)
            b.procProgress.setProgressCompat((s.progress * 100).toInt(), true)
            b.procPercent.text = "${(s.progress * 100).toInt()} %"
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (!mine || s!!.running) return
        when {
            s.error != null -> Snackbar.make(b.root, getString(R.string.processing_error, s.error), Snackbar.LENGTH_LONG).show()
            s.preview && s.result != null -> {
                previewFile = s.result
                b.btnProcessed.isEnabled = true
                loadPlayer(s.result, 0)
                playingPreview = true
                b.abGroup.check(R.id.btnProcessed)
                player?.start(); updatePlayIcon()
            }
            s.result != null -> {
                val out = s.result
                Snackbar.make(b.root, getString(R.string.saved, out.nameWithoutExtension), Snackbar.LENGTH_INDEFINITE)
                    .setAction(R.string.open) {
                        startActivity(android.content.Intent(this, PlayerActivity::class.java).putExtra(EXTRA_FILE, out.absolutePath))
                        finish()
                    }.show()
            }
        }
        Processing.consume()
    }

    private fun switchSource(processed: Boolean) {
        val pf = previewFile
        if (processed && pf == null) return
        if (processed == playingPreview) return
        val wasPlaying = player?.isPlaying == true
        val pos = player?.currentPosition?.toLong() ?: 0L
        if (processed) {
            // La previsualización empieza en previewStartMs del original.
            val rel = (pos - previewStartMs).coerceIn(0, Processing.PREVIEW_SECONDS * 1000L)
            loadPlayer(pf!!, rel.toInt())
        } else {
            loadPlayer(file, (if (playingPreview) previewStartMs + pos else pos).toInt())
        }
        playingPreview = processed
        if (wasPlaying) player?.start()
        updatePlayIcon()
    }

    private fun loadPlayer(f: File, positionMs: Int) {
        player?.release()
        player = try {
            MediaPlayer().apply {
                setDataSource(f.absolutePath)
                prepare()
                seekTo(positionMs)
                setOnCompletionListener { updatePlayIcon() }
            }
        } catch (e: Exception) {
            Snackbar.make(b.root, R.string.cannot_play, Snackbar.LENGTH_LONG).show()
            null
        }
        val d = player?.duration ?: 0
        b.seek.max = d
        b.seek.progress = positionMs
        b.duration.text = Format.duration(d.toLong())
        b.sourceLabel.text = getString(if (f == file) R.string.listening_original else R.string.listening_preview)
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.start()
        updatePlayIcon()
    }

    private fun pausePlayer() {
        player?.let { if (it.isPlaying) it.pause() }
        updatePlayIcon()
    }

    private fun seekBy(ms: Int) {
        val p = player ?: return
        p.seekTo((p.currentPosition + ms).coerceIn(0, p.duration))
    }

    private fun updatePlayIcon() {
        b.btnPlay.setIconResource(if (player?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play)
    }

    override fun onStop() {
        super.onStop()
        pausePlayer()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.player, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            R.id.action_share -> MainActivity.share(this, file)
            R.id.action_export -> lifecycleScope.launch {
                val uri = withContext(Dispatchers.IO) { Recordings.exportToMusic(this@PlayerActivity, file) }
                Snackbar.make(b.root, if (uri != null) R.string.exported else R.string.export_failed, Snackbar.LENGTH_LONG).show()
            }
            R.id.action_delete -> MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_title)
                .setMessage(getString(R.string.delete_msg, file.nameWithoutExtension))
                .setPositiveButton(R.string.delete) { _, _ ->
                    if (Processing.state.value?.source == file) Processing.cancel()
                    player?.release(); player = null
                    file.delete(); finish()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }
}
