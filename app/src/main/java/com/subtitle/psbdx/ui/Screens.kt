package com.subtitle.psbdx.ui

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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.subtitle.psbdx.data.ProjectEntity
import com.subtitle.psbdx.domain.Cue
import com.subtitle.psbdx.domain.SubtitleCodec
import com.subtitle.psbdx.domain.SubtitleFormat
import java.text.DateFormat
import java.util.Date

private val HTML_TAG = Regex("<[^>]+>")

private fun shortTime(ms: Long): String {
    val t = ms.coerceAtLeast(0) / 1000
    val h = t / 3600
    val m = (t / 60) % 60
    val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

@Composable
fun AppRoot(vm: EditorViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snack.showSnackbar(it) } }
    if (state.inEditor) EditorScreen(vm, snack) else HomeScreen(vm, snack)
}

// ======================= Home =======================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: EditorViewModel, snack: SnackbarHostState) {
    val projects by vm.projects.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<ProjectEntity?>(null) }
    val openSub = rememberLauncherForActivityResult(OpenDocumentRw()) { it?.let(vm::importSubtitle) }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::newProjectFromVideo) }
    val dateFmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Subtitle Editor") }) },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { pickVideo.launch(arrayOf("video/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text("New subtitle project from video")
            }
            FilledTonalButton(onClick = { openSub.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text("Open subtitle file (.srt .vtt .sbv .ass .sub .txt)")
            }
            Text("Projects", style = MaterialTheme.typography.titleMedium)
            if (projects.isEmpty()) {
                Text("No projects yet. Open a subtitle file or start from a video.", style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(projects, key = { it.id }) { p ->
                        ListItem(
                            headlineContent = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text("${p.format} · ${p.cueCount} cues · ${dateFmt.format(Date(p.updatedAt))}")
                            },
                            trailingContent = {
                                IconButton(onClick = { toDelete = p }) { Icon(Icons.Default.Delete, contentDescription = "Delete project") }
                            },
                            modifier = Modifier.clickableRow { vm.openProject(p) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    toDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete project?") },
            text = { Text("\"${p.name}\" will be removed from this app. Exported or original files are not touched.") },
            confirmButton = { TextButton(onClick = { vm.deleteProject(p.id); toDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(onClick = onClick))

// ======================= Editor =======================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: EditorViewModel, snack: SnackbarHostState) {
    val state by vm.state.collectAsStateWithLifecycle()
    val activeIndex by vm.activeIndex.collectAsStateWithLifecycle()

    var showExport by remember { mutableStateOf(false) }
    var showShift by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var autoFollow by remember { mutableStateOf(true) }
    var pendingFormat by remember { mutableStateOf(SubtitleFormat.SRT) }

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::setVideo) }
    val exportLauncher = rememberLauncherForActivityResult(CreateDocumentMime()) { uri ->
        uri?.let { vm.export(it, pendingFormat) }
    }

    BackHandler { vm.exitEditor() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (state.saveStatus == SaveStatus.Saving) "Saving…" else "All changes saved",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { vm.exitEditor() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Button(onClick = { showExport = true }, contentPadding = PaddingValues(horizontal = 16.dp)) { Text("Export") }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Load / change video") }, onClick = {
                                menuOpen = false; pickVideo.launch(arrayOf("video/*"))
                            })
                            DropdownMenuItem(text = { Text("Shift all timestamps…") }, onClick = {
                                menuOpen = false; showShift = true
                            })
                            if (state.subtitleUri != null) {
                                DropdownMenuItem(
                                    text = { Text("Also save changes to opened file") },
                                    leadingIcon = { Checkbox(checked = state.writeThrough, onCheckedChange = null) },
                                    onClick = { menuOpen = false; vm.setWriteThrough(!state.writeThrough) },
                                )
                            }
                            DropdownMenuItem(text = { Text("Close project") }, onClick = {
                                menuOpen = false; vm.exitEditor()
                            })
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            VideoPane(vm, state.videoUri != null, state.cues.getOrNull(activeIndex)) { pickVideo.launch(arrayOf("video/*")) }
            PlayerControls(vm)
            CueToolbar(vm, hasSelection = state.selectedId != null, autoFollow = autoFollow) { autoFollow = it }
            HorizontalDivider()
            CueList(vm, state, activeIndex, autoFollow, Modifier.weight(1f))
        }
    }

    if (showExport) {
        AlertDialog(
            onDismissRequest = { showExport = false },
            title = { Text("Export subtitles") },
            text = {
                Column {
                    SubtitleFormat.entries.forEach { f ->
                        Row(
                            Modifier.fillMaxWidth().clickableRow {
                                showExport = false
                                pendingFormat = f
                                exportLauncher.launch(f.mime to "${state.name}.${f.ext}")
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = f == state.format, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(f.label, modifier = Modifier.padding(vertical = 12.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showExport = false }) { Text("Cancel") } },
        )
    }

    if (showShift) {
        var input by remember { mutableStateOf("0") }
        AlertDialog(
            onDismissRequest = { showShift = false },
            title = { Text("Shift all timestamps") },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Milliseconds (negative = earlier)") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    input.trim().toLongOrNull()?.let { vm.shiftAll(it) }
                    showShift = false
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { showShift = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPane(vm: EditorViewModel, hasVideo: Boolean, activeCue: Cue?, onPickVideo: () -> Unit) {
    val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val sizeMod = if (imeOpen) Modifier.height(110.dp) else Modifier.aspectRatio(16f / 9f)
    Box(Modifier.fillMaxWidth().then(sizeMod).background(Color.Black)) {
        if (hasVideo) {
            AndroidView(
                factory = { ctx -> PlayerView(ctx).apply { useController = false; player = vm.player } },
                update = { it.player = vm.player },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No video loaded", color = Color.White)
                TextButton(onClick = onPickVideo) { Text("Load video") }
            }
        }
        val overlay = activeCue?.text?.replace(HTML_TAG, "").orEmpty()
        if (overlay.isNotBlank()) {
            Text(
                overlay,
                color = Color.White,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun PlayerControls(vm: EditorViewModel) {
    val pos by vm.position.collectAsStateWithLifecycle()
    val dur by vm.duration.collectAsStateWithLifecycle()
    val playing by vm.isPlaying.collectAsStateWithLifecycle()
    Column(Modifier.padding(horizontal = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton(onClick = vm::togglePlay) { Text(if (playing) "❚❚" else "▶") }
            Spacer(Modifier.width(8.dp))
            Text("${shortTime(pos)} / ${shortTime(dur)}", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { vm.seekBy(-5000) }) { Text("−5s") }
            TextButton(onClick = { vm.seekBy(5000) }) { Text("+5s") }
        }
        val max = maxOf(dur, 1L).toFloat()
        Slider(
            value = pos.toFloat().coerceIn(0f, max),
            onValueChange = { vm.seekTo(it.toLong()) },
            valueRange = 0f..max,
            enabled = dur > 0,
        )
    }
}

@Composable
private fun CueToolbar(vm: EditorViewModel, hasSelection: Boolean, autoFollow: Boolean, onAutoFollow: (Boolean) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = vm::markCue, contentPadding = PaddingValues(horizontal = 14.dp)) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Mark cue")
        }
        FilterChip(selected = autoFollow, onClick = { onAutoFollow(!autoFollow) }, label = { Text("Auto-scroll") })
        val small = PaddingValues(horizontal = 12.dp)
        FilledTonalButton(onClick = vm::setSelectedStartNow, enabled = hasSelection, contentPadding = small) { Text("Start = now") }
        FilledTonalButton(onClick = vm::setSelectedEndNow, enabled = hasSelection, contentPadding = small) { Text("End = now") }
        FilledTonalButton(onClick = { vm.nudgeSelected(-100, 0) }, enabled = hasSelection, contentPadding = small) { Text("Start −0.1s") }
        FilledTonalButton(onClick = { vm.nudgeSelected(100, 0) }, enabled = hasSelection, contentPadding = small) { Text("Start +0.1s") }
        FilledTonalButton(onClick = { vm.nudgeSelected(0, -100) }, enabled = hasSelection, contentPadding = small) { Text("End −0.1s") }
        FilledTonalButton(onClick = { vm.nudgeSelected(0, 100) }, enabled = hasSelection, contentPadding = small) { Text("End +0.1s") }
    }
}

@Composable
private fun CueList(vm: EditorViewModel, state: EditorState, activeIndex: Int, autoFollow: Boolean, modifier: Modifier) {
    val listState = rememberLazyListState()
    var editingId by remember { mutableStateOf<Long?>(null) }

    // Video -> subtitle: keep the active cue in view while playing.
    LaunchedEffect(activeIndex, autoFollow) {
        if (autoFollow && activeIndex >= 0 && editingId == null) {
            listState.animateScrollToItem(maxOf(activeIndex - 1, 0))
        }
    }
    // Bring a freshly created cue into view so it can be focused.
    LaunchedEffect(state.focusId) {
        val id = state.focusId ?: return@LaunchedEffect
        val idx = state.cues.indexOfFirst { it.id == id }
        if (idx >= 0) listState.animateScrollToItem(idx)
    }

    if (state.cues.isEmpty()) {
        Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.TopCenter) {
            Text(
                "No cues yet. Load a video, press play, and tap “Mark cue” at each line you want to caption.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    LazyColumn(state = listState, modifier = modifier.fillMaxWidth()) {
        itemsIndexed(state.cues, key = { _, c -> c.id }) { index, cue ->
            CueRow(
                index = index,
                cue = cue,
                selected = cue.id == state.selectedId,
                active = index == activeIndex,
                requestFocus = cue.id == state.focusId,
                onTap = { vm.onCueTap(cue.id) },
                onText = { vm.updateText(cue.id, it) },
                onTimes = { s, e -> vm.updateTimes(cue.id, s, e) },
                onDelete = { vm.deleteCue(cue.id) },
                onInsertAfter = { vm.insertAfter(cue.id) },
                onFocusChange = { focused ->
                    if (focused) { editingId = cue.id; vm.onCueTap(cue.id) }
                    else if (editingId == cue.id) editingId = null
                },
                onFocusConsumed = vm::consumeFocus,
            )
        }
    }
}

@Composable
private fun CueRow(
    index: Int,
    cue: Cue,
    selected: Boolean,
    active: Boolean,
    requestFocus: Boolean,
    onTap: () -> Unit,
    onText: (String) -> Unit,
    onTimes: (Long, Long) -> Unit,
    onDelete: () -> Unit,
    onInsertAfter: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onFocusConsumed: () -> Unit,
) {
    val container = when {
        active -> MaterialTheme.colorScheme.primaryContainer
        selected -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        onClick = onTap,
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("#${index + 1}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                TimeField(cue.startMs, Modifier.weight(1f)) { onTimes(it, cue.endMs) }
                Text("→", modifier = Modifier.padding(horizontal = 6.dp))
                TimeField(cue.endMs, Modifier.weight(1f)) { onTimes(cue.startMs, it) }
                IconButton(onClick = onInsertAfter) { Icon(Icons.Default.Add, contentDescription = "Add cue below") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete cue") }
            }
            Spacer(Modifier.height(6.dp))
            var text by remember(cue.id) { mutableStateOf(cue.text) }
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(requestFocus) {
                if (requestFocus) {
                    runCatching { focusRequester.requestFocus() }
                    onFocusConsumed()
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; onText(it) },
                placeholder = { Text("Subtitle text") },
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { onFocusChange(it.isFocused) },
            )
        }
    }
}

@Composable
private fun TimeField(ms: Long, modifier: Modifier, onCommit: (Long) -> Unit) {
    var text by remember(ms) { mutableStateOf(SubtitleCodec.formatTime(ms, '.')) }
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    fun commit() {
        val parsed = SubtitleCodec.parseTime(text)
        if (parsed == null) text = SubtitleCodec.formatTime(ms, '.')
        else if (parsed != ms) onCommit(parsed)
    }

    BasicTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        textStyle = LocalTextStyle.current.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit(); focusManager.clearFocus() }),
        modifier = modifier
            .onFocusChanged {
                if (focused && !it.isFocused) commit()
                focused = it.isFocused
            }
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}
