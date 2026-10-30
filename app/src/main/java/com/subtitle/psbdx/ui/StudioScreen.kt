package com.subtitle.psbdx.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.subtitle.psbdx.studio.DubPhase
import com.subtitle.psbdx.studio.ExportKind
import com.subtitle.psbdx.studio.StudioState
import com.subtitle.psbdx.studio.VisualState

private val TAGS = Regex("<[^>]+>")
private val RecordRed = Color(0xFFD32F2F)

private fun clock(ms: Long): String {
    val t = ms.coerceAtLeast(0)
    return "%d:%02d.%d".format(t / 60000, (t / 1000) % 60, (t % 1000) / 100)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen(vm: EditorViewModel, snack: SnackbarHostState) {
    val studio = vm.studio
    val st by studio.state.collectAsStateWithLifecycle()
    val visual by studio.visual.collectAsStateWithLifecycle()
    val origPeaks by studio.origPeaks.collectAsStateWithLifecycle()
    val clipPeaks by studio.clipPeaks.collectAsStateWithLifecycle()
    val editor by vm.state.collectAsStateWithLifecycle()
    val activeIndex by vm.activeIndex.collectAsStateWithLifecycle()
    val pos by vm.position.collectAsStateWithLifecycle()
    val dur by vm.duration.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showExport by remember { mutableStateOf(false) }
    var pendingKind by remember { mutableStateOf(ExportKind.VIDEO) }
    var pendingMic by remember { mutableStateOf<(() -> Unit)?>(null) }
    val ts = remember { TimelineState() }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingMic
        pendingMic = null
        if (granted) action?.invoke() else vm.say("Microphone permission is needed for dubbing")
    }
    fun withMic(action: () -> Unit) {
        val ok = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (ok) action() else {
            pendingMic = action
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(CreateDocumentMime()) { uri ->
        uri?.let { studio.export(it, pendingKind) }
    }

    BackHandler { if (st.busy == null) studio.exit() }

    val busyLabel = st.busy
    val recording = st.phase != DubPhase.Idle

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dubbing studio") },
                navigationIcon = {
                    IconButton(onClick = { if (st.busy == null) studio.exit() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Button(
                        onClick = { showExport = true },
                        enabled = busyLabel == null && !recording,
                        contentPadding = PaddingValues(horizontal = 16.dp),
                    ) { Text("Export") }
                    Spacer(Modifier.width(8.dp))
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (st.preparing) {
                LinearProgressIndicator(progress = { st.prepProgress }, modifier = Modifier.fillMaxWidth())
                Text("Reading the video's audio…", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
            }
            if (busyLabel != null) {
                LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth())
                Text(busyLabel, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
            }

            Box(Modifier.fillMaxWidth().height(170.dp).background(Color.Black), contentAlignment = Alignment.Center) {
                VideoPane(vm, true, editor.cues.getOrNull(activeIndex)) {}
            }
            StudioTransport(
                st = st, pos = pos, dur = dur,
                onPlay = { studio.playMix() }, onStop = { studio.stopPreview() },
                onSeek = { vm.studioSeek(it) },
            )
            TabRow(selectedTabIndex = st.tab) {
                Tab(selected = st.tab == 0, onClick = { studio.setTab(0) }, text = { Text("Record") })
                Tab(selected = st.tab == 1, onClick = { studio.setTab(1) }, text = { Text("Edit") })
            }
            if (st.tab == 0) {
                val spoken = editor.cues.filter { it.text.isNotBlank() }
                val now = spoken.lastOrNull { pos >= it.startMs && pos < it.endMs }
                val next = spoken.firstOrNull { it.startMs > pos }
                RecordTab(
                    st = st, visual = visual,
                    now = now?.text?.replace(TAGS, ""), next = next?.text?.replace(TAGS, ""),
                    onRecord = { withMic { studio.startRecord() } },
                    onStop = { studio.stopRecord() },
                    onMonitor = { withMic { studio.toggleMonitor() } },
                    onInput = { studio.selectInput(it) },
                    onOutput = { studio.selectOutput(it) },
                    onClearRange = { studio.clearRange() },
                )
            } else {
                EditTab(
                    vm = vm, st = st, ts = ts, origPeaks = origPeaks, clipPeaks = clipPeaks,
                    pos = pos, dur = dur, busy = recording || busyLabel != null,
                )
            }
        }
    }

    if (showExport) {
        AlertDialog(
            onDismissRequest = { showExport = false },
            title = { Text("Export dubbed result") },
            text = {
                Column {
                    Text(
                        "The mix is the original audio with your replaced/muted parts, plus all dub clips.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    ExportKind.entries.forEach { k ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                showExport = false
                                pendingKind = k
                                exportLauncher.launch(k.mime to "${editor.name}-dubbed.${k.ext}")
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = k == ExportKind.VIDEO, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(k.label, modifier = Modifier.padding(vertical = 12.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showExport = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StudioTransport(
    st: StudioState, pos: Long, dur: Long,
    onPlay: () -> Unit, onStop: () -> Unit, onSeek: (Long) -> Unit,
) {
    val idle = st.phase == DubPhase.Idle
    Column(Modifier.padding(horizontal = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton(onClick = { if (st.previewing) onStop() else onPlay() }, enabled = idle) {
                Text(if (st.previewing) "■" else "▶")
            }
            Spacer(Modifier.width(8.dp))
            Text("${shortTime(pos)} / ${shortTime(dur)}", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onSeek(pos - 5000) }, enabled = idle) { Text("−5s") }
            TextButton(onClick = { onSeek(pos + 5000) }, enabled = idle) { Text("+5s") }
        }
        val max = maxOf(dur, 1L).toFloat()
        Slider(
            value = pos.toFloat().coerceIn(0f, max),
            onValueChange = { onSeek(it.toLong()) },
            valueRange = 0f..max,
            enabled = dur > 0 && idle,
        )
    }
}

@Composable
private fun RecordTab(
    st: StudioState, visual: VisualState, now: String?, next: String?,
    onRecord: () -> Unit, onStop: () -> Unit, onMonitor: () -> Unit, onInput: (Int?) -> Unit, onOutput: (Int?) -> Unit, onClearRange: () -> Unit,
) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("Say now", style = MaterialTheme.typography.labelSmall)
                Text(now ?: "—", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(6.dp))
                Text("Next", style = MaterialTheme.typography.labelSmall)
                Text(next ?: "—", style = MaterialTheme.typography.bodyLarge)
            }
        }

        val a = st.inMs
        val b = st.outMs
        if (a != null && b != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Punch-in range ${clock(a)} → ${clock(b)}. Recording starts at the In point and stops at Out.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClearRange) { Text("Clear") }
            }
        } else {
            Text(
                "Recording starts from the playhead. Set In/Out in the Edit tab to dub just one part.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        when (st.phase) {
            DubPhase.Idle -> Button(
                onClick = onRecord,
                enabled = !st.preparing && !st.previewing,
                colors = ButtonDefaults.buttonColors(containerColor = RecordRed, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("● Record voice-over") }

            DubPhase.Countdown -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Get ready…", style = MaterialTheme.typography.labelLarge)
                Text("${st.countdown}", style = MaterialTheme.typography.displayMedium, textAlign = TextAlign.Center)
                TextButton(onClick = onStop) { Text("Cancel") }
            }

            DubPhase.Recording -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("● Recording  ${clock(st.elapsedMs)}", color = RecordRed, style = MaterialTheme.typography.titleMedium)
                Button(onClick = onStop, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("■ Stop and keep take") }
            }
        }

        LiveVisualizer(visual)

        FilterChip(
            selected = st.monitor, onClick = onMonitor, enabled = st.phase == DubPhase.Idle,
            label = { Text("Mic check") },
        )
        DevicePicker(
            "Record from", st.inputId,
            listOf<Pair<Int?, String>>(null to "Phone microphone (built-in)") + st.inputs.map { it.id to it.name },
            st.phase == DubPhase.Idle, onInput,
        )
        DevicePicker(
            "Hear the original audio on", st.outputId,
            listOf<Pair<Int?, String>>(null to "Nowhere — original audio stays silent") + st.outputs.map { it.id to it.name },
            true, onOutput,
        )
        Text(
            "The video's own sound is always muted. Pick Bluetooth earphones or a headset above to hear the original while you dub; it is never played through the phone speaker, so it can't leak into your recording.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun DevicePicker(
    title: String, selectedId: Int?, options: List<Pair<Int?, String>>, enabled: Boolean, onPick: (Int?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == selectedId }?.second ?: options.first().second
    Column {
        Text(title, style = MaterialTheme.typography.labelSmall)
        Box {
            OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(current, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (id, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onPick(id) })
                }
            }
        }
    }
}

@Composable
private fun ToolButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(text) }
}

@Composable
private fun EditTab(
    vm: EditorViewModel, st: StudioState, ts: TimelineState,
    origPeaks: FloatArray, clipPeaks: Map<String, FloatArray>,
    pos: Long, dur: Long, busy: Boolean,
) {
    val studio = vm.studio
    val editor by vm.state.collectAsStateWithLifecycle()
    var follow by remember { mutableStateOf(true) }
    val sel = st.clips.firstOrNull { it.id == st.selClip }
    val hasRange = st.inMs != null && st.outMs != null
    val canSplit = st.clips.any { pos > it.startMs + 50 && pos < it.startMs + it.durMs - 50 }
    val canDelete = sel != null || hasRange

    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Timeline(
            ts = ts, st = st, origPeaks = origPeaks, clipPeaks = clipPeaks, cues = editor.cues,
            posMs = pos, durMs = dur, follow = follow,
            onSeek = { vm.studioSeek(it) }, onSelectClip = { studio.selectClip(it) },
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Text(
            "Tap to move the playhead or pick a clip. Pinch to zoom, drag to scroll. Red = muted original, dimmed = replaced by a dub clip.",
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 12.dp),
        )

        Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { studio.splitAtPlayhead() },
                enabled = canSplit && !busy,
                modifier = Modifier.weight(1f),
            ) { Text("✂ Split at playhead") }
            Button(
                onClick = { studio.deleteSelectionOrRange() },
                enabled = canDelete && !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.weight(1f),
            ) { Text(if (sel != null) "Delete clip" else "Delete range") }
        }
        Text(
            "Split cuts the clip under the playhead in two. Delete removes the selected clip; with nothing selected it deletes the In–Out range (dub clips are cut there and the original is silenced). Undo reverses either.",
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 12.dp),
        )

        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolButton("Undo", st.canUndo && !busy) { studio.undo() }
            ToolButton("Redo", st.canRedo && !busy) { studio.redo() }
            ToolButton("Zoom −") { ts.zoomAtPlayhead(0.6f, pos) }
            ToolButton("Zoom +") { ts.zoomAtPlayhead(1.6f, pos) }
            FilterChip(selected = follow, onClick = { follow = !follow }, label = { Text("Follow") })
        }

        Text("Range (replace part of the original)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToolButton("Set In", !busy) { studio.setIn() }
            ToolButton("Set Out", !busy) { studio.setOut() }
            ToolButton("Clear", st.inMs != null || st.outMs != null) { studio.clearRange() }
            ToolButton("Mute range", hasRange && !busy) { studio.muteRange() }
            ToolButton("Restore original", hasRange && !busy) { studio.restoreRange() }
            ToolButton("Dub this range", hasRange && !busy) { studio.setTab(0) }
        }

        if (sel != null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Take ${sel.id} · ${clock(sel.startMs)} → ${clock(sel.startMs + sel.durMs)}",
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface,
                    )
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToolButton("Trim start", !busy) { studio.trimStartToPlayhead() }
                        ToolButton("Trim end", !busy) { studio.trimEndToPlayhead() }
                        ToolButton("Fit to range", hasRange && !busy) { studio.fitToRange() }
                        ToolButton("Move to playhead", !busy) { studio.moveToPlayhead() }
                        ToolButton("◀ 100ms", !busy) { studio.nudge(-100) }
                        ToolButton("100ms ▶", !busy) { studio.nudge(100) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Replace original under this clip", modifier = Modifier.weight(1f))
                        Switch(checked = sel.replaceOriginal, onCheckedChange = { studio.toggleReplace() }, enabled = !busy)
                    }
                    Text("Clip volume ${(sel.gain * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                    Slider(value = sel.gain, onValueChange = { studio.setClipGain(it) }, valueRange = 0f..2f, enabled = !busy)
                }
            }
        }

        Column(Modifier.padding(horizontal = 12.dp)) {
            Text("Original audio volume ${(st.origGain * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
            Slider(value = st.origGain, onValueChange = { studio.setOrigGain(it) }, valueRange = 0f..2f, enabled = !busy)
        }
    }
}
