package com.subtitle.psbdx.studio

import android.app.Application
import android.net.Uri
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
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

enum class DubPhase { Idle, Countdown, Recording }

enum class ExportKind(val label: String, val ext: String, val mime: String) {
    VIDEO("Video with the new audio (.mp4)", "mp4", "video/mp4"),
    M4A("Mixed audio (.m4a)", "m4a", "audio/mp4"),
    WAV("Mixed audio, lossless (.wav)", "wav", "audio/x-wav"),
}

data class StudioState(
    val mode: Boolean = false,
    val tab: Int = 0,
    val preparing: Boolean = false,
    val prepProgress: Float = 0f,
    val hasOriginal: Boolean = false,
    val phase: DubPhase = DubPhase.Idle,
    val countdown: Int = 0,
    val elapsedMs: Long = 0,
    val monitor: Boolean = false,
    val guide: Boolean = false,
    val clips: List<Clip> = emptyList(),
    val mutes: List<MsRange> = emptyList(),
    val origGain: Float = 1f,
    val selClip: Long? = null,
    val inMs: Long? = null,
    val outMs: Long? = null,
    val previewing: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val busy: String? = null,
    val progress: Float = 0f,
)

class VisualState(
    val levels: FloatArray = FloatArray(StudioController.HIST),
    val pitches: FloatArray = FloatArray(StudioController.HIST),
    val bands: FloatArray = FloatArray(StudioController.BANDS),
    val db: Float = -90f,
    val peakDb: Float = -90f,
    val pitchHz: Float = 0f,
)

/**
 * Dubbing studio engine: punch-in recording with live analysis, a two-track timeline
 * (original audio + dub clips) with replace/mute/trim/split/undo, real-time mix preview and export.
 */
class StudioController(
    private val app: Application,
    private val player: ExoPlayer,
    private val scope: CoroutineScope,
    private val say: (String) -> Unit,
) {
    companion object {
        const val PEAK_WIN = 441      // 10 ms
        const val HIST = 140
        const val BANDS = 40
    }

    private class Snap(val clips: List<Clip>, val mutes: List<MsRange>, val origGain: Float)

    private val _state = MutableStateFlow(StudioState())
    val state: StateFlow<StudioState> = _state.asStateFlow()
    private val _visual = MutableStateFlow(VisualState())
    val visual: StateFlow<VisualState> = _visual.asStateFlow()
    private val _origPeaks = MutableStateFlow(FloatArray(0))
    val origPeaks: StateFlow<FloatArray> = _origPeaks.asStateFlow()
    private val _clipPeaks = MutableStateFlow<Map<String, FloatArray>>(emptyMap())
    val clipPeaks: StateFlow<Map<String, FloatArray>> = _clipPeaks.asStateFlow()

    private var dir: File? = null
    private var videoUri: String? = null
    private var nextClipId = 1L
    private val mic = MicEngine(BANDS) { onFrame(it) }
    private var engine: PlayEngine? = null
    private var guideEngine: PlayEngine? = null
    private var recJob: Job? = null
    private var prepJob: Job? = null
    private var saveJob: Job? = null
    private var recFile: File? = null
    private var recStartMs = 0L
    private var recEndMs: Long? = null
    private var peakHold = -90f
    private val undoStack = ArrayDeque<Snap>()
    private val redoStack = ArrayDeque<Snap>()

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    if (_state.value.phase == DubPhase.Recording) stopRecord()
                    if (_state.value.previewing) stopPreview()
                }
            }
        })
    }

    private fun originalFile(): File? = dir?.let { File(it, "original.wav") }?.takeIf { it.exists() }
    private fun pos(): Long = player.currentPosition.coerceAtLeast(0)

    // ---------------------------------------------------------------- lifecycle

    fun enter(projectId: Long, video: String?) {
        val d = File(app.filesDir, "studio/$projectId").apply { mkdirs() }
        dir = d
        videoUri = video
        undoStack.clear()
        redoStack.clear()
        player.pause()
        player.volume = 0f
        _origPeaks.value = FloatArray(0)
        _clipPeaks.value = emptyMap()
        _visual.value = VisualState()
        val loaded = loadModel(d)
        _state.value = StudioState(
            mode = true, preparing = video != null,
            clips = loaded.clips, mutes = loaded.mutes, origGain = loaded.origGain,
        )
        loaded.clips.forEach { ensurePeaks(it.file) }
        prepJob?.cancel()
        prepJob = scope.launch { prepareOriginal() }
    }

    fun exit() {
        stopAll()
        saveJob?.cancel()
        saveNow()
        prepJob?.cancel()
        player.pause()
        player.volume = 1f
        _state.value = StudioState()
        _visual.value = VisualState()
    }

    fun release() {
        stopAll()
        saveNow()
    }

    /** App went to the background: nothing may keep recording or playing. */
    fun onBackground() {
        if (!_state.value.mode) return
        stopAll()
        saveNow()
    }

    private fun stopAll() {
        if (_state.value.phase != DubPhase.Idle) stopRecord()
        stopPreview()
        stopMonitor()
    }

    private suspend fun prepareOriginal() {
        val d = dir ?: return
        val video = videoUri
        if (video == null) {
            _state.update { it.copy(preparing = false) }
            return
        }
        var ok = false
        var peaks = FloatArray(0)
        withContext(Dispatchers.IO) {
            val job = coroutineContext[Job]
            val wav = File(d, "original.wav")
            val key = File(d, "original.key")
            val cached = wav.exists() && key.exists() && key.readText() == video
            ok = if (cached) true else {
                wav.delete()
                val done = AudioDecoder.decode(app, Uri.parse(video), wav, { job?.isActive == false }) { p ->
                    _state.update { it.copy(prepProgress = p) }
                }
                if (done) key.writeText(video) else wav.delete()
                done
            }
            if (ok) peaks = Wav.peaks(wav, PEAK_WIN)
        }
        _origPeaks.value = peaks
        _state.update { it.copy(preparing = false, hasOriginal = ok) }
        if (!ok) say("Couldn't read this video's audio. You can still record dub tracks.")
    }

    // ---------------------------------------------------------------- tabs / selection

    fun setTab(tab: Int) = _state.update { it.copy(tab = tab) }
    fun selectClip(id: Long?) = _state.update { it.copy(selClip = id) }

    fun seek(ms: Long) {
        val resume = _state.value.previewing
        if (resume) stopPreview()
        player.seekTo(ms.coerceAtLeast(0))
        if (resume) playMix()
    }

    fun setIn() {
        val p = pos()
        _state.update { s -> s.copy(inMs = p, outMs = s.outMs?.takeIf { it > p }) }
    }

    fun setOut() {
        val p = pos()
        _state.update { s -> s.copy(outMs = p, inMs = s.inMs?.takeIf { it < p }) }
    }

    fun clearRange() = _state.update { it.copy(inMs = null, outMs = null) }

    fun toggleGuide() = _state.update { it.copy(guide = !it.guide) }

    // ---------------------------------------------------------------- mic monitor + recording

    private fun onFrame(f: MicFrame) {
        val v = _visual.value
        peakHold = max(f.peakDb, peakHold - 2f)
        val levels = FloatArray(HIST)
        System.arraycopy(v.levels, 1, levels, 0, HIST - 1)
        levels[HIST - 1] = f.level
        val pitches = FloatArray(HIST)
        System.arraycopy(v.pitches, 1, pitches, 0, HIST - 1)
        pitches[HIST - 1] = f.pitchHz
        val bands = FloatArray(BANDS) { i -> max(f.bands[i], v.bands[i] * 0.78f) }
        _visual.value = VisualState(levels, pitches, bands, f.db, peakHold, f.pitchHz)
    }

    fun toggleMonitor() {
        if (_state.value.phase != DubPhase.Idle) return
        if (_state.value.monitor) {
            stopMonitor()
        } else {
            val err = mic.start(null)
            if (err != null) say(err) else _state.update { it.copy(monitor = true) }
        }
    }

    private fun stopMonitor() {
        if (_state.value.monitor) {
            mic.stop()
            _state.update { it.copy(monitor = false) }
            _visual.value = VisualState()
        }
    }

    /** Punch-in aware: records the In..Out range if one is set, otherwise from the playhead. */
    fun startRecord() {
        val s = _state.value
        if (s.phase != DubPhase.Idle || s.preparing) return
        stopPreview()
        stopMonitor()
        val inMs = s.inMs
        val outMs = s.outMs
        val startMs = if (inMs != null && outMs != null) inMs else pos()
        recEndMs = if (inMs != null && outMs != null) outMs else null
        recJob?.cancel()
        recJob = scope.launch {
            player.pause()
            player.seekTo(startMs)
            for (n in 3 downTo 1) {
                _state.update { it.copy(phase = DubPhase.Countdown, countdown = n) }
                delay(1000)
            }
            val d = dir
            if (d == null) {
                _state.update { it.copy(phase = DubPhase.Idle, countdown = 0) }
                return@launch
            }
            val file = File(d, "take_${System.currentTimeMillis()}.wav")
            val err = mic.start(file)
            if (err != null) {
                say(err)
                _state.update { it.copy(phase = DubPhase.Idle, countdown = 0) }
                return@launch
            }
            recFile = file
            recStartMs = startMs
            player.volume = 0f
            player.seekTo(startMs)
            player.play()
            if (_state.value.guide) startGuide(startMs)
            _state.update { it.copy(phase = DubPhase.Recording, countdown = 0, elapsedMs = 0) }
            val t0 = SystemClock.elapsedRealtime()
            while (isActive) {
                _state.update { it.copy(elapsedMs = SystemClock.elapsedRealtime() - t0) }
                val end = recEndMs
                if (end != null && player.currentPosition >= end) {
                    finishRecord()
                    break
                }
                delay(50)
            }
        }
    }

    fun stopRecord() {
        when (_state.value.phase) {
            DubPhase.Idle -> return
            DubPhase.Countdown -> {
                recJob?.cancel()
                _state.update { it.copy(phase = DubPhase.Idle, countdown = 0) }
            }
            DubPhase.Recording -> {
                recJob?.cancel()
                finishRecord()
            }
        }
    }

    private fun finishRecord() {
        player.pause()
        stopGuide()
        val samples = mic.stop()
        val file = recFile
        recFile = null
        _visual.value = VisualState()
        _state.update { it.copy(phase = DubPhase.Idle, countdown = 0) }
        if (file == null) return
        if (samples < Wav.SR / 10) {
            file.delete()
            say("Take was too short and was discarded")
            return
        }
        var durMs = Wav.samplesToMs(samples)
        val end = recEndMs
        if (end != null) durMs = min(durMs, end - recStartMs)
        if (durMs < 50) {
            file.delete()
            return
        }
        val clip = Clip(id = nextClipId++, file = file.name, startMs = recStartMs, srcStartMs = 0, durMs = durMs)
        commit { it.copy(clips = it.clips + clip, selClip = clip.id) }
        ensurePeaks(file.name)
        say("Take recorded — switch to Edit to trim or move it")
    }

    // ---------------------------------------------------------------- preview playback

    private fun totalMs(forVideo: Boolean): Long {
        val d = player.duration.takeIf { it > 0 } ?: 0L
        if (forVideo && d > 0) return d
        val orig = originalFile()?.let { Wav.samplesToMs(Wav.sampleCount(it)) } ?: 0L
        val clipsEnd = _state.value.clips.maxOfOrNull { it.startMs + it.durMs } ?: 0L
        return max(max(d, orig), clipsEnd)
    }

    private fun newMixer(): Mixer {
        val s = _state.value
        return Mixer(dir!!, MixModel(s.clips, s.mutes, s.origGain), originalFile())
    }

    /** Plays the final mix (original with replaced parts + dub clips) in sync with the muted video. */
    fun playMix() {
        val s = _state.value
        if (s.phase != DubPhase.Idle || dir == null) return
        stopPreview()
        stopMonitor()
        val total = totalMs(false)
        var startMs = pos()
        if (startMs >= total - 200) startMs = 0
        val eng = PlayEngine(newMixer(), Wav.msToSamples(startMs), Wav.msToSamples(total), true, true, true) {
            scope.launch { stopPreview() }
        }
        engine = eng
        eng.start()
        player.volume = 0f
        player.seekTo(startMs)
        player.play()
        _state.update { it.copy(previewing = true) }
    }

    fun stopPreview() {
        val e = engine
        engine = null
        e?.stop()
        if (_state.value.previewing) {
            player.pause()
            _state.update { it.copy(previewing = false) }
        }
    }

    private fun startGuide(fromMs: Long) {
        stopGuide()
        val eng = PlayEngine(newMixer(), Wav.msToSamples(fromMs), Wav.msToSamples(totalMs(false)), true, false, false) {}
        guideEngine = eng
        eng.start()
    }

    private fun stopGuide() {
        guideEngine?.stop()
        guideEngine = null
    }

    // ---------------------------------------------------------------- editing

    private fun snap() = _state.value.let { Snap(it.clips, it.mutes, it.origGain) }

    private fun commit(undoable: Boolean = true, change: (StudioState) -> StudioState) {
        if (_state.value.previewing) stopPreview()
        if (undoable) {
            undoStack.addLast(snap())
            if (undoStack.size > 60) undoStack.removeFirst()
            redoStack.clear()
        }
        _state.update { s ->
            change(s).copy(canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty())
        }
        scheduleSave()
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        stopPreview()
        redoStack.addLast(snap())
        applySnap(undoStack.removeLast())
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        stopPreview()
        undoStack.addLast(snap())
        applySnap(redoStack.removeLast())
    }

    private fun applySnap(s: Snap) {
        _state.update {
            it.copy(
                clips = s.clips, mutes = s.mutes, origGain = s.origGain,
                selClip = it.selClip?.takeIf { id -> s.clips.any { c -> c.id == id } },
                canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty(),
            )
        }
        s.clips.forEach { ensurePeaks(it.file) }
        scheduleSave()
    }

    private fun selected(): Clip? = _state.value.let { s -> s.clips.firstOrNull { it.id == s.selClip } }

    private fun updateClip(id: Long, undoable: Boolean = true, f: (Clip) -> Clip) =
        commit(undoable) { s -> s.copy(clips = s.clips.map { if (it.id == id) f(it) else it }) }

    fun muteRange() {
        val s = _state.value
        val a = s.inMs
        val b = s.outMs
        if (a == null || b == null) { say("Set an In and Out point first"); return }
        commit { it.copy(mutes = mergeRanges(it.mutes + MsRange(a, b))) }
    }

    fun restoreRange() {
        val s = _state.value
        val a = s.inMs
        val b = s.outMs
        if (a == null || b == null) { say("Set an In and Out point first"); return }
        commit { it.copy(mutes = subtractRange(it.mutes, a, b)) }
    }

    fun splitAtPlayhead() {
        val c = selected() ?: return
        val p = pos()
        if (p <= c.startMs + 50 || p >= c.startMs + c.durMs - 50) { say("Move the playhead inside the selected clip"); return }
        val second = c.copy(id = nextClipId++, startMs = p, srcStartMs = c.srcStartMs + (p - c.startMs), durMs = c.startMs + c.durMs - p)
        val first = c.copy(durMs = p - c.startMs)
        commit { s -> s.copy(clips = s.clips.flatMap { if (it.id == c.id) listOf(first, second) else listOf(it) }, selClip = second.id) }
    }

    fun trimStartToPlayhead() {
        val c = selected() ?: return
        val p = pos()
        if (p <= c.startMs || p >= c.startMs + c.durMs - 50) { say("Move the playhead inside the selected clip"); return }
        updateClip(c.id) { it.copy(startMs = p, srcStartMs = it.srcStartMs + (p - it.startMs), durMs = it.startMs + it.durMs - p) }
    }

    fun trimEndToPlayhead() {
        val c = selected() ?: return
        val p = pos()
        if (p <= c.startMs + 50 || p >= c.startMs + c.durMs) { say("Move the playhead inside the selected clip"); return }
        updateClip(c.id) { it.copy(durMs = p - it.startMs) }
    }

    /** Moves the clip to the In point and trims it so it doesn't run past the Out point. */
    fun fitToRange() {
        val c = selected() ?: return
        val s = _state.value
        val a = s.inMs
        val b = s.outMs
        if (a == null || b == null) { say("Set an In and Out point first"); return }
        updateClip(c.id) { it.copy(startMs = a, durMs = min(it.durMs, b - a)) }
    }

    fun moveToPlayhead() {
        val c = selected() ?: return
        val p = pos()
        updateClip(c.id) { it.copy(startMs = p) }
    }

    fun nudge(deltaMs: Long) {
        val c = selected() ?: return
        updateClip(c.id) { it.copy(startMs = max(0, it.startMs + deltaMs)) }
    }

    fun deleteSelected() {
        val c = selected() ?: return
        commit { s -> s.copy(clips = s.clips.filterNot { it.id == c.id }, selClip = null) }
    }

    fun setClipGain(g: Float) {
        val c = selected() ?: return
        updateClip(c.id, undoable = false) { it.copy(gain = g) }
    }

    fun toggleReplace() {
        val c = selected() ?: return
        updateClip(c.id) { it.copy(replaceOriginal = !it.replaceOriginal) }
    }

    fun setOrigGain(g: Float) = commit(undoable = false) { it.copy(origGain = g) }

    private fun mergeRanges(list: List<MsRange>): List<MsRange> {
        val sorted = list.filter { it.b > it.a }.sortedBy { it.a }
        val out = mutableListOf<MsRange>()
        for (r in sorted) {
            val last = out.lastOrNull()
            if (last != null && r.a <= last.b) out[out.lastIndex] = MsRange(last.a, max(last.b, r.b)) else out.add(r)
        }
        return out
    }

    private fun subtractRange(list: List<MsRange>, a: Long, b: Long): List<MsRange> =
        list.flatMap { r ->
            if (r.b <= a || r.a >= b) listOf(r)
            else buildList {
                if (r.a < a) add(MsRange(r.a, a))
                if (r.b > b) add(MsRange(b, r.b))
            }
        }

    // ---------------------------------------------------------------- peaks + persistence

    private fun ensurePeaks(name: String) {
        val d = dir ?: return
        if (_clipPeaks.value.containsKey(name)) return
        scope.launch {
            val p = withContext(Dispatchers.IO) { runCatching { Wav.peaks(File(d, name), PEAK_WIN) }.getOrDefault(FloatArray(0)) }
            _clipPeaks.update { it + (name to p) }
        }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(500)
            saveNow()
        }
    }

    private fun saveNow() {
        val d = dir ?: return
        val s = _state.value
        if (!s.mode) return
        try {
            val o = JSONObject()
            o.put("origGain", s.origGain.toDouble())
            o.put("nextId", nextClipId)
            val clips = JSONArray()
            s.clips.forEach {
                clips.put(
                    JSONObject().put("id", it.id).put("file", it.file).put("start", it.startMs)
                        .put("src", it.srcStartMs).put("dur", it.durMs).put("gain", it.gain.toDouble())
                        .put("replace", it.replaceOriginal)
                )
            }
            o.put("clips", clips)
            val mutes = JSONArray()
            s.mutes.forEach { mutes.put(JSONArray().put(it.a).put(it.b)) }
            o.put("mutes", mutes)
            File(d, "studio.json").writeText(o.toString())
        } catch (_: Exception) {
        }
    }

    private fun loadModel(d: File): MixModel {
        val f = File(d, "studio.json")
        nextClipId = 1
        if (!f.exists()) return MixModel(emptyList(), emptyList(), 1f)
        return try {
            val o = JSONObject(f.readText())
            nextClipId = o.optLong("nextId", 1)
            val ca = o.optJSONArray("clips") ?: JSONArray()
            val clips = (0 until ca.length()).map { i ->
                val c = ca.getJSONObject(i)
                Clip(
                    c.getLong("id"), c.getString("file"), c.getLong("start"), c.getLong("src"), c.getLong("dur"),
                    c.optDouble("gain", 1.0).toFloat(), c.optBoolean("replace", true),
                )
            }.filter { File(d, it.file).exists() }
            val ma = o.optJSONArray("mutes") ?: JSONArray()
            val mutes = (0 until ma.length()).map { i ->
                val r = ma.getJSONArray(i)
                MsRange(r.getLong(0), r.getLong(1))
            }
            MixModel(clips, mutes, o.optDouble("origGain", 1.0).toFloat())
        } catch (_: Exception) {
            MixModel(emptyList(), emptyList(), 1f)
        }
    }

    // ---------------------------------------------------------------- export

    fun export(uri: Uri, kind: ExportKind) {
        val d = dir ?: return
        val s = _state.value
        if (s.busy != null || s.phase != DubPhase.Idle) return
        stopPreview()
        stopMonitor()
        val video = videoUri
        val total = totalMs(kind == ExportKind.VIDEO)
        if (total <= 0) { say("Nothing to export yet"); return }
        if (s.clips.isEmpty() && s.mutes.isEmpty()) { say("Add a dub take or mute a range before exporting") }
        _state.update { it.copy(busy = "Exporting ${kind.ext.uppercase()}…", progress = 0f) }
        scope.launch {
            var failure: String? = null
            withContext(Dispatchers.IO) {
                val tmp = File(app.cacheDir, "export_tmp.${kind.ext}")
                try {
                    tmp.delete()
                    val totalSamples = Wav.msToSamples(total)
                    var last = 0
                    val progress: (Float) -> Unit = { p ->
                        val pct = (p * 100).toInt()
                        if (pct != last) { last = pct; _state.update { it.copy(progress = p) } }
                    }
                    Mixer(d, MixModel(s.clips, s.mutes, s.origGain), originalFile()).use { mixer ->
                        when (kind) {
                            ExportKind.WAV -> Exporter.renderWav(mixer, totalSamples, tmp, progress)
                            ExportKind.M4A -> Exporter.encodeAac(app, null, mixer, totalSamples, tmp, progress)
                            ExportKind.VIDEO -> Exporter.encodeAac(app, video?.let { Uri.parse(it) }, mixer, totalSamples, tmp, progress)
                        }
                    }
                    app.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        tmp.inputStream().use { it.copyTo(out) }
                    } ?: error("Cannot write the destination file")
                } catch (e: Exception) {
                    failure = if (kind == ExportKind.VIDEO)
                        "Couldn't build the video (${e.message}). Some video formats can't be re-muxed here — export the audio instead and merge it with your video editor."
                    else "Export failed: ${e.message}"
                } finally {
                    tmp.delete()
                }
            }
            _state.update { it.copy(busy = null, progress = 0f) }
            say(failure ?: "Export finished")
        }
    }
}
