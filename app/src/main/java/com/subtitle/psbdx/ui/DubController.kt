package com.subtitle.psbdx.ui

import android.app.Application
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

enum class DubPhase { Idle, Countdown, Recording }

/** One recorded voice-over. [startMs] is the video position where recording began. */
data class Take(val file: File, val createdAt: Long, val startMs: Long)

data class DubState(
    val mode: Boolean = false,
    val phase: DubPhase = DubPhase.Idle,
    val countdown: Int = 0,
    val elapsedMs: Long = 0,
    val takes: List<Take> = emptyList(),
    val playingTake: String? = null,
)

/**
 * Dubbing: the video plays muted (subtitles stay visible) while the microphone records a voice-over.
 * Takes are stored per project in app storage and can be replayed in sync with the video or saved out.
 */
class DubController(
    private val app: Application,
    private val player: ExoPlayer,
    private val scope: CoroutineScope,
    private val say: (String) -> Unit,
) {
    private val _state = MutableStateFlow(DubState())
    val state: StateFlow<DubState> = _state.asStateFlow()

    private var dir: File? = null
    private var recorder: MediaRecorder? = null
    private var current: File? = null
    private var startMs = 0L
    private var job: Job? = null
    private var takePlayer: MediaPlayer? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    if (_state.value.phase == DubPhase.Recording) stop()
                    if (_state.value.playingTake != null) stopPlayback()
                }
            }
        })
    }

    fun enter(projectId: Long) {
        dir = File(app.filesDir, "dubs/$projectId").apply { mkdirs() }
        player.pause()
        player.volume = 0f
        _state.value = DubState(mode = true, takes = loadTakes())
    }

    fun exit() {
        stop()
        stopPlayback()
        player.pause()
        player.volume = 1f
        _state.value = DubState()
    }

    fun release() {
        stop()
        stopPlayback()
    }

    /** 3-2-1 countdown, then the muted video starts playing and the mic starts recording. */
    fun start() {
        if (_state.value.phase != DubPhase.Idle) return
        stopPlayback()
        player.pause()
        job?.cancel()
        job = scope.launch {
            for (n in 3 downTo 1) {
                _state.update { it.copy(phase = DubPhase.Countdown, countdown = n) }
                delay(1000)
            }
            beginRecording()
        }
    }

    private fun beginRecording() {
        val d = dir ?: return
        if (player.duration > 0 && player.currentPosition >= player.duration - 300) player.seekTo(0)
        startMs = player.currentPosition
        val file = File(d, "take_${System.currentTimeMillis()}_$startMs.m4a")
        try {
            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(app) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(128_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            current = file
        } catch (e: Exception) {
            file.delete()
            say("Could not start recording: ${e.message}")
            _state.update { it.copy(phase = DubPhase.Idle, countdown = 0) }
            return
        }
        player.volume = 0f
        player.play()
        _state.update { it.copy(phase = DubPhase.Recording, countdown = 0, elapsedMs = 0) }
        job = scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            while (isActive) {
                _state.update { it.copy(elapsedMs = SystemClock.elapsedRealtime() - t0) }
                delay(200)
            }
        }
    }

    fun stop() {
        val phase = _state.value.phase
        if (phase == DubPhase.Idle) return
        job?.cancel()
        job = null
        player.pause()
        if (phase == DubPhase.Recording) {
            val r = recorder
            recorder = null
            var ok = true
            try { r?.stop() } catch (_: RuntimeException) { ok = false }
            r?.release()
            val f = current
            current = null
            if (!ok) {
                f?.delete()
                say("Recording was too short and was discarded")
            } else {
                say("Take saved")
            }
        }
        _state.update { it.copy(phase = DubPhase.Idle, countdown = 0, takes = loadTakes()) }
    }

    /** Replays a take in sync with the (muted) video, from the position where it was recorded. */
    fun playTake(take: Take) {
        if (_state.value.phase != DubPhase.Idle) return
        stopPlayback()
        try {
            val mp = MediaPlayer()
            mp.setDataSource(take.file.absolutePath)
            mp.prepare()
            mp.setOnCompletionListener { stopPlayback() }
            takePlayer = mp
            player.volume = 0f
            player.seekTo(take.startMs)
            player.play()
            mp.start()
            _state.update { it.copy(playingTake = take.file.name) }
        } catch (e: Exception) {
            say("Could not play take: ${e.message}")
        }
    }

    fun stopPlayback() {
        takePlayer?.let { mp ->
            try { mp.stop() } catch (_: Exception) {}
            mp.release()
        }
        takePlayer = null
        if (_state.value.playingTake != null) {
            player.pause()
            _state.update { it.copy(playingTake = null) }
        }
    }

    fun deleteTake(take: Take) {
        if (_state.value.playingTake == take.file.name) stopPlayback()
        take.file.delete()
        _state.update { it.copy(takes = loadTakes()) }
    }

    fun exportTake(uri: Uri, take: Take) {
        scope.launch(Dispatchers.IO) {
            try {
                app.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    take.file.inputStream().use { it.copyTo(out) }
                } ?: error("Cannot write file")
                say("Voice-over saved")
            } catch (e: Exception) {
                say("Save failed: ${e.message}")
            }
        }
    }

    private fun loadTakes(): List<Take> {
        val files = dir?.listFiles { f -> f.name.startsWith("take_") && f.name.endsWith(".m4a") } ?: return emptyList()
        return files.mapNotNull { f ->
            val parts = f.name.removeSuffix(".m4a").split('_')
            val created = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            Take(f, created, parts.getOrNull(2)?.toLongOrNull() ?: 0L)
        }.sortedByDescending { it.createdAt }
    }
}
