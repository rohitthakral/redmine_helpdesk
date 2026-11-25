package com.leadcapture.odoo

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Patterns
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.leadcapture.odoo.databinding.ActivityMainBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val vm: LeadViewModel by viewModels()
    private lateinit var settings: SettingsStore
    private var shownPhoto: File? = null

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        vm.onPhotoTaken(ok)
    }

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startRecording()
        else Toast.makeText(this, R.string.error_mic_permission, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = SettingsStore(this)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        binding.toolbar.inflateMenu(R.menu.main)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> { openSettings(); true }
                R.id.action_clear -> { confirmClear(); true }
                else -> false
            }
        }

        binding.btnPhoto.setOnClickListener { launchCamera() }
        binding.btnRemovePhoto.setOnClickListener { vm.removePhoto() }
        binding.btnRecord.setOnClickListener { onRecordClicked() }
        binding.btnPlay.setOnClickListener { vm.togglePlayback() }
        binding.btnDeleteAudio.setOnClickListener { vm.deleteAudio() }
        binding.btnSubmit.setOnClickListener { submit() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.state.collect { render(it) } }
                launch { vm.events.collect { handleEvent(it) } }
                launch { tickRecordingTimer() }
            }
        }

        if (savedInstanceState == null && !settings.load().isComplete) openSettings()
    }

    override fun onStop() {
        super.onStop()
        // Android blocks the microphone for background apps, so don't keep a silent recording going.
        if (!isChangingConfigurations) {
            vm.stopRecording()
            vm.stopPlayback()
        }
    }

    private fun render(s: LeadUiState) {
        // Photo
        if (s.photo != shownPhoto) {
            shownPhoto = s.photo
            val bmp = s.photo?.let { decodePreview(it) }
            binding.photoPreview.setImageBitmap(bmp)
        }
        val hasPhoto = s.photo != null
        binding.photoPreview.visibility = if (hasPhoto) View.VISIBLE else View.GONE
        binding.photoEmpty.visibility = if (hasPhoto) View.GONE else View.VISIBLE
        binding.btnRemovePhoto.visibility = if (hasPhoto) View.VISIBLE else View.GONE
        binding.btnPhoto.setText(if (hasPhoto) R.string.retake_photo else R.string.take_photo)
        binding.btnPhoto.isEnabled = !s.processingPhoto && !s.submitting

        // Audio
        binding.btnRecord.setText(if (s.recording) R.string.stop else R.string.record)
        binding.btnRecord.isEnabled = !s.submitting
        val hasAudio = s.audio != null && !s.recording
        binding.btnPlay.visibility = if (hasAudio) View.VISIBLE else View.GONE
        binding.btnDeleteAudio.visibility = if (hasAudio) View.VISIBLE else View.GONE
        binding.btnPlay.setText(if (s.playing) R.string.stop else R.string.play)
        binding.btnDeleteAudio.isEnabled = !s.submitting
        updateAudioStatus(s)

        // Submit
        binding.progress.visibility = if (s.submitting) View.VISIBLE else View.GONE
        binding.btnSubmit.isEnabled = !s.submitting && !s.processingPhoto
        binding.btnSubmit.setText(if (s.submitting) R.string.submitting else R.string.submit)
    }

    private fun updateAudioStatus(s: LeadUiState) {
        binding.audioStatus.text = when {
            s.recording -> getString(
                R.string.audio_recording,
                formatDuration(SystemClock.elapsedRealtime() - s.recordingStartedAt),
            )
            s.audio != null -> getString(R.string.audio_ready, formatDuration(s.audioDurationMs))
            else -> getString(R.string.audio_none)
        }
    }

    private suspend fun tickRecordingTimer() {
        while (true) {
            val s = vm.state.value
            if (s.recording) updateAudioStatus(s)
            delay(500)
        }
    }

    private fun handleEvent(e: LeadEvent) {
        when (e) {
            is LeadEvent.Error -> Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
            is LeadEvent.Failed -> MaterialAlertDialogBuilder(this)
                .setTitle(R.string.submit_failed)
                .setMessage(e.message)
                .setPositiveButton(R.string.ok, null)
                .show()
            is LeadEvent.Submitted -> {
                clearFields()
                val r = e.result
                val msg = if (r.attachmentErrors.isEmpty()) getString(R.string.lead_created_msg, r.leadId)
                else getString(R.string.lead_partial_msg, r.leadId, r.attachmentErrors.joinToString("\n"))
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.lead_created_title)
                    .setMessage(msg)
                    .setPositiveButton(R.string.ok, null)
                    .setNeutralButton(R.string.open_in_odoo) { _, _ ->
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.leadUrl)))
                        } catch (_: ActivityNotFoundException) {
                        }
                    }
                    .show()
            }
        }
    }

    private fun launchCamera() {
        val file = vm.newRawPhotoFile()
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        try {
            takePicture.launch(uri)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.error_no_camera, Toast.LENGTH_LONG).show()
        }
    }

    private fun onRecordClicked() {
        if (vm.state.value.recording) {
            vm.stopRecording()
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            vm.startRecording()
        } else {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun submit() {
        val config = settings.load()
        if (!config.isComplete) {
            Toast.makeText(this, R.string.error_not_configured, Toast.LENGTH_LONG).show()
            openSettings()
            return
        }
        val lead = LeadInput(
            contactName = binding.etName.text?.toString().orEmpty(),
            companyName = binding.etCompany.text?.toString().orEmpty(),
            email = binding.etEmail.text?.toString().orEmpty(),
            phone = binding.etPhone.text?.toString().orEmpty(),
            note = binding.etNote.text?.toString().orEmpty(),
        )
        val s = vm.state.value
        val hasContactData = listOf(lead.contactName, lead.companyName, lead.email, lead.phone).any { it.isNotBlank() }
        if (!hasContactData && s.photo == null) {
            Toast.makeText(this, R.string.error_empty, Toast.LENGTH_LONG).show()
            return
        }
        if (lead.email.isNotBlank() && !Patterns.EMAIL_ADDRESS.matcher(lead.email.trim()).matches()) {
            binding.tilEmail.error = getString(R.string.error_email)
            binding.etEmail.requestFocus()
            return
        }
        binding.tilEmail.error = null
        vm.submit(config, lead)
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.discard_title)
            .setMessage(R.string.discard_msg)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.clear) { _, _ ->
                clearFields()
                vm.clearMedia()
            }
            .show()
    }

    private fun clearFields() {
        listOf(binding.etName, binding.etCompany, binding.etEmail, binding.etPhone, binding.etNote)
            .forEach { it.setText("") }
        binding.tilEmail.error = null
        binding.scroll.scrollTo(0, 0)
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    private fun decodePreview(file: File) = try {
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        BitmapFactory.decodeFile(file.absolutePath, opts)
    } catch (_: Exception) {
        null
    }

    private fun formatDuration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }
}
