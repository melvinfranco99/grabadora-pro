package com.melvinfranco.grabadorapro

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.melvinfranco.grabadorapro.databinding.ActivityMainBinding
import com.melvinfranco.grabadorapro.databinding.DialogSettingsBinding
import com.melvinfranco.grabadorapro.databinding.ItemRecordingBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private val adapter = RecordingAdapter(::openPlayer, ::showItemMenu)

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.RECORD_AUDIO] == true) Recorder.start(this)
        else Snackbar.make(b.root, R.string.need_mic, Snackbar.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        prefs = Prefs(this)

        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter

        b.btnRecord.setOnClickListener {
            if (Recorder.state.value.status == RecStatus.IDLE) startRecording() else Recorder.stop(this)
        }
        b.btnPause.setOnClickListener {
            when (Recorder.state.value.status) {
                RecStatus.RECORDING -> Recorder.pause(this)
                RecStatus.PAUSED -> Recorder.resume(this)
                RecStatus.IDLE -> Unit
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Recorder.state.collect(::render) }
                launch {
                    Recorder.events.collect { e ->
                        when (e) {
                            is RecEvent.Finished -> {
                                refresh()
                                val msg = if (e.autoStopped) getString(R.string.auto_stopped_text, Format.maxDuration(prefs.maxMinutes))
                                else getString(R.string.saved, e.file.nameWithoutExtension)
                                Snackbar.make(b.root, msg, Snackbar.LENGTH_LONG)
                                    .setAction(R.string.open) { openPlayer(e.file) }.show()
                            }
                            is RecEvent.Error -> Snackbar.make(b.root, e.message, Snackbar.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        refresh()
    }

    private fun startRecording() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) Recorder.start(this) else permLauncher.launch(missing.toTypedArray())
    }

    private fun render(s: RecState) {
        val active = s.status != RecStatus.IDLE
        b.timer.text = Format.duration(s.elapsedMs).let { if (it.length == 5) "00:$it" else it }
        b.limit.text = if (active) getString(R.string.limit_remaining, Format.duration((s.maxMs - s.elapsedMs).coerceAtLeast(0)))
        else getString(R.string.limit_label, Format.maxDuration(prefs.maxMinutes))
        b.level.setProgressCompat((s.level * 100).toInt(), false)
        b.level.isVisible = active
        b.progressLimit.isVisible = active
        if (s.maxMs > 0) b.progressLimit.progress = (s.elapsedMs * 1000 / s.maxMs).toInt()
        b.status.text = getString(
            when (s.status) {
                RecStatus.IDLE -> R.string.status_idle
                RecStatus.RECORDING -> R.string.status_recording
                RecStatus.PAUSED -> R.string.status_paused
            }
        )
        b.recDot.isVisible = s.status == RecStatus.RECORDING
        b.btnRecord.setIconResource(if (active) R.drawable.ic_stop else R.drawable.ic_mic)
        b.btnRecord.text = getString(if (active) R.string.stop else R.string.record)
        b.btnPause.isVisible = active
        b.btnPause.setIconResource(if (s.status == RecStatus.PAUSED) R.drawable.ic_play else R.drawable.ic_pause)
        b.btnPause.text = getString(if (s.status == RecStatus.PAUSED) R.string.resume else R.string.pause)
        if (active) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun refresh() {
        lifecycleScope.launch {
            val current = Recorder.state.value.file
            val items = withContext(Dispatchers.IO) {
                Recordings.repairHeaders(this@MainActivity, current)
                Recordings.list(this@MainActivity, exclude = current)
            }
            adapter.submitList(items)
            b.empty.isVisible = items.isEmpty()
            b.count.text = resources.getQuantityString(R.plurals.count, items.size, items.size)
        }
    }

    private fun openPlayer(file: File) {
        startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_FILE, file.absolutePath))
    }

    private fun showItemMenu(rec: Recording, anchor: View) {
        PopupMenu(this, anchor).apply {
            menuInflater.inflate(R.menu.item_recording, menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_share -> share(this@MainActivity, rec.file)
                    R.id.action_export -> export(rec.file)
                    R.id.action_rename -> rename(rec)
                    R.id.action_delete -> delete(rec)
                }
                true
            }
        }.show()
    }

    private fun export(file: File) {
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) { Recordings.exportToMusic(this@MainActivity, file) }
            Snackbar.make(b.root, if (uri != null) R.string.exported else R.string.export_failed, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun rename(rec: Recording) {
        val input = EditText(this).apply { setText(rec.name); setSelection(text.length) }
        val box = FrameLayout(this).apply {
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename)
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ ->
                if (Recordings.rename(rec, input.text.toString()) == null)
                    Toast.makeText(this, R.string.rename_failed, Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun delete(rec: Recording) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_msg, rec.name))
            .setPositiveButton(R.string.delete) { _, _ -> rec.file.delete(); refresh() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_settings) { showSettings(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun showSettings() {
        val d = DialogSettingsBinding.inflate(layoutInflater)
        val max = prefs.maxMinutes
        d.hours.minValue = 0; d.hours.maxValue = 23; d.hours.value = max / 60
        d.minutes.minValue = 0; d.minutes.maxValue = 59; d.minutes.value = max % 60
        if (prefs.sampleRate == 44100) d.rate44.isChecked = true else d.rate48.isChecked = true
        d.liveNs.isChecked = prefs.liveNoiseSuppressor
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings)
            .setView(d.root)
            .setPositiveButton(R.string.save) { _, _ ->
                prefs.maxMinutes = (d.hours.value * 60 + d.minutes.value).coerceAtLeast(1)
                prefs.sampleRate = if (d.rate44.isChecked) 44100 else 48000
                prefs.liveNoiseSuppressor = d.liveNs.isChecked
                render(Recorder.state.value)
                if (Recorder.state.value.status != RecStatus.IDLE)
                    Toast.makeText(this, R.string.settings_next, Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        fun share(activity: AppCompatActivity, file: File) {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType("audio/wav")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            activity.startActivity(Intent.createChooser(send, activity.getString(R.string.share)))
        }
    }
}

class RecordingAdapter(
    private val onClick: (File) -> Unit,
    private val onMenu: (Recording, View) -> Unit,
) : ListAdapter<Recording, RecordingAdapter.VH>(Diff) {

    class VH(val b: ItemRecordingBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemRecordingBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val r = getItem(position)
        h.b.name.text = r.name
        h.b.details.text = "${Format.date(r.date)} · ${Format.duration(r.durationMs)} · ${Format.size(r.sizeBytes)}"
        h.b.tagEnhanced.isVisible = r.enhanced
        h.b.icon.setImageResource(if (r.enhanced) R.drawable.ic_sparkle else R.drawable.ic_wave)
        h.b.root.setOnClickListener { onClick(r.file) }
        h.b.more.setOnClickListener { onMenu(r, it) }
    }

    object Diff : DiffUtil.ItemCallback<Recording>() {
        override fun areItemsTheSame(a: Recording, b: Recording) = a.file == b.file
        override fun areContentsTheSame(a: Recording, b: Recording) = a == b
    }
}
