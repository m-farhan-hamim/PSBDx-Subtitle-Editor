package com.subtitle.psbdx.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.subtitle.psbdx.SubtitleApp
import com.subtitle.psbdx.data.ProjectEntity
import com.subtitle.psbdx.data.ProjectRepository
import com.subtitle.psbdx.domain.Cue
import com.subtitle.psbdx.domain.SubtitleCodec
import com.subtitle.psbdx.domain.SubtitleFormat
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SaveStatus { Saved, Saving }

data class EditorState(
    val inEditor: Boolean = false,
    val projectId: Long = 0,
    val name: String = "",
    val videoUri: String? = null,
    val subtitleUri: String? = null,
    val format: SubtitleFormat = SubtitleFormat.SRT,
    val cues: List<Cue> = emptyList(),
    val selectedId: Long? = null,
    val focusId: Long? = null,
    val saveStatus: SaveStatus = SaveStatus.Saved,
    val writeThrough: Boolean = false,
)

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as SubtitleApp).repository

    val player: ExoPlayer = ExoPlayer.Builder(app).build().apply {
        setSeekParameters(SeekParameters.EXACT)
    }

    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    val projects: StateFlow<List<ProjectEntity>> =
        repo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()
    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Index of the cue active at the current playback position, or -1. */
    val activeIndex: StateFlow<Int> =
        combine(_position, _state.map { it.cues }.distinctUntilChanged()) { pos, cues ->
            cues.indexOfLast { pos >= it.startMs && pos < it.endMs }
        }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, -1)

    private var nextId = 1L
    private var saveJob: Job? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { _isPlaying.value = isPlaying }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) _duration.value = player.duration.coerceAtLeast(0)
            }
            override fun onPlayerError(error: PlaybackException) {
                say("Playback error: ${error.errorCodeName}")
            }
        })
        viewModelScope.launch {
            while (isActive) {
                if (_state.value.videoUri != null) _position.value = player.currentPosition
                delay(80)
            }
        }
    }

    private fun say(msg: String) { _messages.tryEmit(msg) }

    // ---------- Project lifecycle ----------

    fun importSubtitle(uri: Uri) {
        viewModelScope.launch {
            try {
                persist(uri)
                val fileName = repo.displayName(uri) ?: "subtitle.srt"
                val text = repo.readText(uri)
                val format = SubtitleCodec.detect(fileName, text)
                val cues = SubtitleCodec.parse(text, format)
                openNew(fileName.substringBeforeLast('.'), null, uri.toString(), format, cues)
                if (cues.isEmpty()) say("No cues found in this file")
            } catch (e: Exception) {
                say("Import failed: ${e.message}")
            }
        }
    }

    fun newProjectFromVideo(uri: Uri) {
        viewModelScope.launch {
            try {
                persist(uri)
                val name = (repo.displayName(uri) ?: "New project").substringBeforeLast('.')
                openNew(name, uri.toString(), null, SubtitleFormat.SRT, emptyList())
            } catch (e: Exception) {
                say("Could not create project: ${e.message}")
            }
        }
    }

    fun openProject(p: ProjectEntity) {
        viewModelScope.launch {
            try {
                val cues = ProjectRepository.cuesFromJson(p.cuesJson)
                nextId = (cues.maxOfOrNull { it.id } ?: 0) + 1
                _state.value = EditorState(
                    inEditor = true,
                    projectId = p.id,
                    name = p.name,
                    videoUri = p.videoUri,
                    subtitleUri = p.subtitleUri,
                    format = SubtitleFormat.entries.firstOrNull { it.name == p.format } ?: SubtitleFormat.SRT,
                    cues = cues,
                    writeThrough = p.writeThrough,
                )
                loadVideo(p.videoUri)
            } catch (e: Exception) {
                say("Could not open project: ${e.message}")
            }
        }
    }

    fun deleteProject(id: Long) {
        viewModelScope.launch { repo.delete(id) }
    }

    private suspend fun openNew(name: String, video: String?, sub: String?, format: SubtitleFormat, cues: List<Cue>) {
        nextId = (cues.maxOfOrNull { it.id } ?: 0) + 1
        _state.value = EditorState(
            inEditor = true, name = name, videoUri = video, subtitleUri = sub, format = format, cues = cues,
        )
        loadVideo(video)
        saveNow()
    }

    fun exitEditor() {
        viewModelScope.launch {
            saveJob?.cancel()
            saveNow()
            player.pause()
            player.clearMediaItems()
            _position.value = 0
            _duration.value = 0
            _state.value = EditorState()
        }
    }

    fun setVideo(uri: Uri) {
        persist(uri)
        _state.update { it.copy(videoUri = uri.toString()) }
        loadVideo(uri.toString())
        scheduleSave()
    }

    private fun loadVideo(uri: String?) {
        _position.value = 0
        _duration.value = 0
        if (uri == null) {
            player.clearMediaItems()
        } else {
            player.setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
            player.prepare()
            player.playWhenReady = false
        }
    }

    private fun persist(uri: Uri) {
        val cr = getApplication<Application>().contentResolver
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            cr.takePersistableUriPermission(uri, rw)
        } catch (_: Exception) {
            try { cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        }
    }

    // ---------- Playback ----------

    fun togglePlay() { if (player.isPlaying) player.pause() else player.play() }
    fun pause() { player.pause() }

    fun seekTo(ms: Long) {
        val t = ms.coerceAtLeast(0)
        if (_state.value.videoUri != null) player.seekTo(t)
        _position.value = t
    }

    fun seekBy(deltaMs: Long) = seekTo(_position.value + deltaMs)

    /** Subtitle -> video: select the cue and jump the video to its start. */
    fun onCueTap(id: Long) {
        val cue = _state.value.cues.firstOrNull { it.id == id } ?: return
        _state.update { it.copy(selectedId = id) }
        seekTo(cue.startMs)
    }

    // ---------- Editing ----------

    private fun mutate(resort: Boolean = false, selected: Long? = null, focus: Long? = null, block: (List<Cue>) -> List<Cue>) {
        _state.update { s ->
            var n = block(s.cues)
            if (resort) n = n.sortedBy { it.startMs }
            s.copy(cues = n, selectedId = selected ?: s.selectedId, focusId = focus ?: s.focusId)
        }
        scheduleSave()
    }

    fun updateText(id: Long, text: String) = mutate { l -> l.map { if (it.id == id) it.copy(text = text) else it } }

    fun updateTimes(id: Long, start: Long, end: Long) {
        val s = start.coerceAtLeast(0)
        val e = maxOf(end, s + 1)
        mutate(resort = true) { l -> l.map { if (it.id == id) it.copy(startMs = s, endMs = e) else it } }
    }

    private fun selectedCue(): Cue? = _state.value.let { s -> s.cues.firstOrNull { it.id == s.selectedId } }

    fun nudgeSelected(dStart: Long, dEnd: Long) {
        val c = selectedCue() ?: return
        updateTimes(c.id, c.startMs + dStart, c.endMs + dEnd)
    }

    fun setSelectedStartNow() {
        val c = selectedCue() ?: return
        val now = _position.value
        updateTimes(c.id, now, maxOf(c.endMs, now + 1))
    }

    fun setSelectedEndNow() {
        val c = selectedCue() ?: return
        updateTimes(c.id, c.startMs, maxOf(_position.value, c.startMs + 1))
    }

    fun deleteCue(id: Long) = mutate { l -> l.filterNot { it.id == id } }

    fun insertAfter(id: Long) {
        val c = _state.value.cues.firstOrNull { it.id == id } ?: return
        val newCue = Cue(id = nextId++, startMs = c.endMs, endMs = c.endMs + 2000, text = "")
        mutate(resort = true, selected = newCue.id, focus = newCue.id) { it + newCue }
    }

    /** One-tap stamping: creates a cue starting at the current playback position. */
    fun markCue() {
        val now = _position.value
        val newCue = Cue(id = nextId++, startMs = now, endMs = now + 2000, text = "")
        mutate(resort = true, selected = newCue.id, focus = newCue.id) { l ->
            l.map { if (it.startMs < now && it.endMs > now) it.copy(endMs = now) else it } + newCue
        }
    }

    fun consumeFocus() { _state.update { it.copy(focusId = null) } }

    fun shiftAll(deltaMs: Long) {
        mutate { l -> l.map { it.copy(startMs = (it.startMs + deltaMs).coerceAtLeast(0), endMs = (it.endMs + deltaMs).coerceAtLeast(1)) } }
    }

    fun setWriteThrough(on: Boolean) {
        _state.update { it.copy(writeThrough = on) }
        scheduleSave()
    }

    // ---------- Export ----------

    fun export(uri: Uri, format: SubtitleFormat) {
        viewModelScope.launch {
            try {
                val s = _state.value
                repo.writeText(uri, SubtitleCodec.write(s.cues, format, s.name))
                say("Exported ${s.cues.size} cues as .${format.ext}")
            } catch (e: Exception) {
                say("Export failed: ${e.message}")
            }
        }
    }

    // ---------- Auto-save ----------

    private fun scheduleSave() {
        _state.update { it.copy(saveStatus = SaveStatus.Saving) }
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(700)
            saveNow()
        }
    }

    fun flushSave() {
        saveJob?.cancel()
        viewModelScope.launch { saveNow() }
    }

    private suspend fun saveNow() {
        val s = _state.value
        if (!s.inEditor) return
        withContext(NonCancellable) {
            val id = repo.save(
                ProjectEntity(
                    id = s.projectId,
                    name = s.name,
                    videoUri = s.videoUri,
                    subtitleUri = s.subtitleUri,
                    format = s.format.name,
                    cuesJson = ProjectRepository.cuesToJson(s.cues),
                    cueCount = s.cues.size,
                    writeThrough = s.writeThrough,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            if (s.writeThrough && s.subtitleUri != null) {
                try {
                    repo.writeText(Uri.parse(s.subtitleUri), SubtitleCodec.write(s.cues, s.format, s.name))
                } catch (e: Exception) {
                    say("Could not write to the opened file: ${e.message}")
                }
            }
            _state.update { cur ->
                if (cur.cues === s.cues) cur.copy(projectId = id, saveStatus = SaveStatus.Saved)
                else cur.copy(projectId = id)
            }
        }
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }
}
