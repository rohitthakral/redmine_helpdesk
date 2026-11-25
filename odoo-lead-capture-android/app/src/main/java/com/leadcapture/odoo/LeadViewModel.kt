package com.leadcapture.odoo

import android.app.Application
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LeadUiState(
    val photo: File? = null,
    val processingPhoto: Boolean = false,
    val audio: File? = null,
    val audioDurationMs: Long = 0,
    val recording: Boolean = false,
    val recordingStartedAt: Long = 0, // SystemClock.elapsedRealtime()
    val playing: Boolean = false,
    val submitting: Boolean = false,
)

sealed interface LeadEvent {
    data class Submitted(val result: SubmitResult) : LeadEvent
    data class Failed(val message: String) : LeadEvent
    data class Error(val message: String) : LeadEvent
}

/**
 * Holds the photo / recording for the lead being captured. Paths are kept in the
 * SavedStateHandle so they survive the process being killed while the camera app is open.
 */
class LeadViewModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {

    private val captureDir = File(app.cacheDir, "captures").apply { mkdirs() }

    private val _state = MutableStateFlow(
        LeadUiState(
            photo = saved.get<String>(KEY_PHOTO)?.let(::File)?.takeIf { it.exists() },
            audio = saved.get<String>(KEY_AUDIO)?.let(::File)?.takeIf { it.exists() },
            audioDurationMs = saved.get<Long>(KEY_AUDIO_MS) ?: 0L,
        )
    )
    val state: StateFlow<LeadUiState> = _state.asStateFlow()

    private val _events = Channel<LeadEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var player: MediaPlayer? = null

    // ---- Photo ---------------------------------------------------------------------------

    /** Creates the file the camera app should write into. */
    fun newRawPhotoFile(): File {
        val f = File(captureDir, "raw_${stamp()}.jpg")
        saved[KEY_PENDING_RAW] = f.absolutePath
        return f
    }

    fun onPhotoTaken(success: Boolean) {
        val raw = saved.get<String>(KEY_PENDING_RAW)?.let(::File) ?: return
        saved.remove<String>(KEY_PENDING_RAW)
        if (!success || !raw.exists() || raw.length() == 0L) {
            raw.delete()
            return
        }
        _state.update { it.copy(processingPhoto = true) }
        viewModelScope.launch {
            val out = File(captureDir, "business_card_${stamp()}.jpg")
            val ok = withContext(Dispatchers.IO) {
                try {
                    ImageUtils.shrinkJpeg(raw, out); true
                } catch (e: Exception) {
                    // Fall back to the original if we could not process it.
                    runCatching { raw.copyTo(out, overwrite = true) }.isSuccess
                } finally {
                    raw.delete()
                }
            }
            if (ok) setPhoto(out)
            else _events.send(LeadEvent.Error("Could not save the photo"))
            _state.update { it.copy(processingPhoto = false) }
        }
    }

    fun removePhoto() = setPhoto(null)

    private fun setPhoto(file: File?) {
        _state.value.photo?.takeIf { it != file }?.delete()
        saved[KEY_PHOTO] = file?.absolutePath
        _state.update { it.copy(photo = file) }
    }

    // ---- Audio ---------------------------------------------------------------------------

    fun startRecording() {
        if (recorder != null) return
        stopPlayback()
        val file = File(captureDir, "voice_note_${stamp()}.m4a")
        val ctx = getApplication<Application>()
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(ctx) else MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64_000)
            r.setAudioSamplingRate(44_100)
            r.setAudioChannels(1)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            file.delete()
            _events.trySend(LeadEvent.Error("Could not start recording: ${e.message}"))
            return
        }
        recorder = r
        recordingFile = file
        _state.update { it.copy(recording = true, recordingStartedAt = SystemClock.elapsedRealtime()) }
    }

    fun stopRecording() {
        val r = recorder ?: return
        val file = recordingFile
        recorder = null
        recordingFile = null
        val duration = SystemClock.elapsedRealtime() - _state.value.recordingStartedAt
        val ok = try {
            r.stop(); true
        } catch (e: RuntimeException) {
            false // Happens when stopped right after starting: no valid data.
        } finally {
            r.release()
        }
        _state.update { it.copy(recording = false) }
        if (ok && file != null && file.length() > 0) {
            setAudio(file, duration)
        } else {
            file?.delete()
        }
    }

    fun deleteAudio() {
        stopPlayback()
        setAudio(null, 0)
    }

    private fun setAudio(file: File?, durationMs: Long) {
        _state.value.audio?.takeIf { it != file }?.delete()
        saved[KEY_AUDIO] = file?.absolutePath
        saved[KEY_AUDIO_MS] = durationMs
        _state.update { it.copy(audio = file, audioDurationMs = durationMs) }
    }

    fun togglePlayback() {
        if (player != null) {
            stopPlayback(); return
        }
        val file = _state.value.audio ?: return
        try {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { stopPlayback() }
                prepare()
                start()
            }
            _state.update { it.copy(playing = true) }
        } catch (e: Exception) {
            stopPlayback()
            _events.trySend(LeadEvent.Error("Could not play recording: ${e.message}"))
        }
    }

    fun stopPlayback() {
        player?.release()
        player = null
        _state.update { it.copy(playing = false) }
    }

    // ---- Submit --------------------------------------------------------------------------

    fun submit(config: OdooConfig, lead: LeadInput) {
        if (_state.value.submitting) return
        stopRecording()
        stopPlayback()
        _state.update { it.copy(submitting = true) }
        val photo = _state.value.photo
        val audio = _state.value.audio
        viewModelScope.launch {
            val event = withContext(Dispatchers.IO) {
                try {
                    val attachments = buildList {
                        if (photo != null) add(Attachment(photo.name, "image/jpeg", photo.readBytes()))
                        if (audio != null) add(Attachment(audio.name, "audio/mp4", audio.readBytes()))
                    }
                    LeadEvent.Submitted(OdooClient(config).submitLead(lead, attachments))
                } catch (e: OdooException) {
                    LeadEvent.Failed(e.message ?: "Unknown error")
                } catch (e: Exception) {
                    LeadEvent.Failed(e.message ?: e.javaClass.simpleName)
                }
            }
            if (event is LeadEvent.Submitted) clearMedia()
            _state.update { it.copy(submitting = false) }
            _events.send(event)
        }
    }

    fun clearMedia() {
        stopRecording()
        stopPlayback()
        setPhoto(null)
        setAudio(null, 0)
    }

    override fun onCleared() {
        recorder?.release()
        recorder = null
        recordingFile?.delete()
        player?.release()
        player = null
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private companion object {
        const val KEY_PHOTO = "photo"
        const val KEY_AUDIO = "audio"
        const val KEY_AUDIO_MS = "audio_ms"
        const val KEY_PENDING_RAW = "pending_raw"
    }
}
