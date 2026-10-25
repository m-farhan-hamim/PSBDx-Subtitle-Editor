package com.subtitle.psbdx.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date

private val TAGS = Regex("<[^>]+>")
private val RecordRed = Color(0xFFD32F2F)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DubScreen(vm: EditorViewModel, snack: SnackbarHostState) {
    val dub by vm.dub.state.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val activeIndex by vm.activeIndex.collectAsStateWithLifecycle()
    val pos by vm.position.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var saving by remember { mutableStateOf<Take?>(null) }
    val dateFmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.dub.start() else vm.say("Microphone permission is needed to record a voice-over")
    }
    val saveLauncher = rememberLauncherForActivityResult(CreateDocumentMime()) { uri ->
        val take = saving
        if (uri != null && take != null) vm.dub.exportTake(uri, take)
    }

    BackHandler { vm.dub.exit() }

    val busy = dub.phase != DubPhase.Idle
    val spoken = state.cues.filter { it.text.isNotBlank() }
    val now = spoken.lastOrNull { pos >= it.startMs && pos < it.endMs }
    val next = spoken.firstOrNull { it.startMs > pos }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dubbing studio") },
                navigationIcon = {
                    IconButton(onClick = { vm.dub.exit() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            VideoPane(vm, true, state.cues.getOrNull(activeIndex)) {}
            if (!busy && dub.playingTake == null) PlayerControls(vm)

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("Say now", style = MaterialTheme.typography.labelSmall)
                    Text(
                        now?.text?.replace(TAGS, "") ?: "—",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Next", style = MaterialTheme.typography.labelSmall)
                    Text(next?.text?.replace(TAGS, "") ?: "—", style = MaterialTheme.typography.bodyLarge)
                }
            }

            when (dub.phase) {
                DubPhase.Idle -> Column(Modifier.padding(horizontal = 12.dp)) {
                    Button(
                        onClick = {
                            val ok = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                PackageManager.PERMISSION_GRANTED
                            if (ok) vm.dub.start() else permission.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        enabled = dub.playingTake == null,
                        colors = ButtonDefaults.buttonColors(containerColor = RecordRed, contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("● Record voice-over") }
                    Text(
                        "The video plays muted with subtitles. Recording starts from the current position after a 3-second countdown.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                DubPhase.Countdown -> Column(Modifier.fillMaxWidth().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Get ready…", style = MaterialTheme.typography.labelLarge)
                    Text("${dub.countdown}", style = MaterialTheme.typography.displayMedium, textAlign = TextAlign.Center)
                    TextButton(onClick = { vm.dub.stop() }) { Text("Cancel") }
                }

                DubPhase.Recording -> Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("● Recording  ${shortTime(dub.elapsedMs)}", color = RecordRed, style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { vm.dub.stop() }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Text("■ Stop and save take")
                    }
                }
            }

            Text("Takes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp))
            if (dub.takes.isEmpty()) {
                Text("No takes yet.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp))
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(dub.takes, key = { _, t -> t.file.name }) { i, take ->
                        val playing = dub.playingTake == take.file.name
                        ListItem(
                            headlineContent = { Text("Take ${dub.takes.size - i}") },
                            supportingContent = {
                                Text("from ${shortTime(take.startMs)} · ${dateFmt.format(Date(take.createdAt))}")
                            },
                            trailingContent = {
                                Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                                    TextButton(
                                        onClick = { if (playing) vm.dub.stopPlayback() else vm.dub.playTake(take) },
                                        enabled = !busy,
                                    ) { Text(if (playing) "Stop" else "Play") }
                                    TextButton(onClick = {
                                        saving = take
                                        saveLauncher.launch("audio/mp4" to "${state.name}-voiceover-${dub.takes.size - i}.m4a")
                                    }) { Text("Save") }
                                    TextButton(onClick = { vm.dub.deleteTake(take) }, enabled = !busy) { Text("Delete") }
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
