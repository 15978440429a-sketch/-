package com.example.texttospeech

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.texttospeech.model.CUSTOM
import com.example.texttospeech.model.PlaybackMode
import com.example.texttospeech.model.Playlist
import com.example.texttospeech.model.SYSTEM_DEFAULT
import com.example.texttospeech.model.SavedText
import com.example.texttospeech.model.UiState
import com.example.texttospeech.model.VoiceOption

class MainActivity : ComponentActivity() {
    private val reader: ReaderViewModel by viewModels()
    private val voiceDataLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        reader.attachActivity()
    }
    private val exportMp3Launcher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("audio/mpeg")
    ) { destination ->
        destination?.let { reader.exportDownloadedAudio(it, "mp3") }
    }
    private val exportWavLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("audio/wav")
    ) { destination ->
        destination?.let { reader.exportDownloadedAudio(it, "wav") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val dark = isSystemInDarkTheme()
            val colors = when {
                Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
                Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ReaderScreen(
                        reader = reader,
                        installVoiceData = ::openVoiceDataSettings,
                        exportMp3 = { exportDownloadedAudio("mp3") },
                        exportWav = { exportDownloadedAudio("wav") }
                    )
                }
            }
        }
        reader.attachActivity()
    }

    private fun exportDownloadedAudio(format: String) {
        val label = if (format == "wav") "WAV" else "MP3"
        val fileName = reader.downloadedAudioFileName(format)
        if (fileName == null) {
            Toast.makeText(
                this,
                getString(R.string.error_download_missing, label),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (format == "wav") exportWavLauncher.launch(fileName)
        else exportMp3Launcher.launch(fileName)
    }

    private fun openVoiceDataSettings() {
        reader.stop()
        try {
            voiceDataLauncher.launch(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
        } catch (_: ActivityNotFoundException) {
            try {
                voiceDataLauncher.launch(Intent(Settings.ACTION_SETTINGS))
                Toast.makeText(this, R.string.install_data_failed, Toast.LENGTH_LONG).show()
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, R.string.install_data_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onStop() {
        reader.persistNowBlocking()
        super.onStop()
    }

    override fun onDestroy() {
        reader.detachActivity()
        super.onDestroy()
    }
}

@Composable
private fun ReaderScreen(
    reader: ReaderViewModel,
    installVoiceData: () -> Unit,
    exportMp3: () -> Unit,
    exportWav: () -> Unit
) {
    val ui by reader.uiState.collectAsStateWithLifecycle()
    var voiceDialog by remember { mutableStateOf(false) }
    var agreementDialog by remember { mutableStateOf(false) }
    var languageMenu by remember { mutableStateOf(false) }
    var saveNameDialog by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }
    var playlistNameDialog by remember { mutableStateOf(false) }
    var playlistName by remember { mutableStateOf("") }
    var deletePlaylistDialog by remember { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableStateOf<Long?>(null) }
    val selectedVoice = ui.voices.firstOrNull { it.id == ui.voiceId }
    val onlineVoice = selectedVoice?.online == true
    val currentDocument = ui.savedTexts.firstOrNull { it.id == ui.selectedTextId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        OutlinedTextField(
            value = ui.text,
            onValueChange = reader::updateText,
            modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp).testTag("text_input"),
            label = { Text(stringResource(R.string.input_label)) },
            placeholder = { Text(stringResource(R.string.input_hint)) },
            minLines = 7
        )
        Text(
            stringResource(R.string.character_count, ui.text.codePointCount(0, ui.text.length)),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.End)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = reader::play,
                enabled = ui.text.isNotBlank() && !ui.downloadBusy,
                modifier = Modifier.weight(1f).testTag("play_button")
            ) {
                Text(stringResource(R.string.play))
            }
            OutlinedButton(
                onClick = { reader.stop() },
                enabled = ui.busy || ui.downloadBusy,
                modifier = Modifier.weight(1f).testTag("stop_button")
            ) {
                Text(stringResource(R.string.stop))
            }
            OutlinedButton(onClick = reader::clearText, enabled = ui.text.isNotEmpty()) {
                Text(stringResource(R.string.clear))
            }
        }

        StatusCard(ui, reader, installVoiceData, onlineVoice)
        if (ui.busy && ui.highlightStart >= 0 && ui.highlightEnd > ui.highlightStart) {
            NowReadingCard(ui)
        }

        PlaybackModeSection(ui, reader)
        TimerSection(ui, reader)
        PlaylistSection(
            ui = ui,
            onCreate = {
                playlistName = ""
                playlistNameDialog = true
            },
            onDelete = { deletePlaylistDialog = true },
            onSelect = reader::selectPlaylist
        )
        TextLibrarySection(
            ui = ui,
            reader = reader,
            onSave = {
                saveName = reader.suggestedTitle()
                saveNameDialog = true
            },
            onDelete = { pendingDeleteId = it }
        )

        Text(stringResource(R.string.voice_settings), style = MaterialTheme.typography.titleLarge)
        OutlinedButton(
            onClick = { voiceDialog = true },
            modifier = Modifier.fillMaxWidth().testTag("voice_selector")
        ) {
            Text(
                stringResource(
                    R.string.voice_selected,
                    selectedVoice?.name ?: stringResource(R.string.offline_default)
                )
            )
        }
        if (selectedVoice != null) VoiceDetail(selectedVoice)
        if (onlineVoice) {
            Text(
                stringResource(R.string.network_warning),
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.bodySmall
            )
            OfflineDownloadSection(
                ui = ui,
                currentDocument = currentDocument,
                reader = reader,
                exportMp3 = exportMp3,
                exportWav = exportWav
            )
        }

        Text(stringResource(R.string.language), style = MaterialTheme.typography.titleMedium)
        Box {
            OutlinedButton(
                onClick = { languageMenu = true },
                enabled = ui.languages.isNotEmpty()
            ) {
                Text(ui.languages.firstOrNull { it.tag == ui.languageTag }?.label ?: ui.languageTag)
            }
            DropdownMenu(expanded = languageMenu, onDismissRequest = { languageMenu = false }) {
                ui.languages.forEach { language ->
                    DropdownMenuItem(
                        text = { Text(language.label) },
                        onClick = {
                            reader.selectLanguage(language.tag)
                            languageMenu = false
                        }
                    )
                }
            }
        }
        if (ui.languages.isEmpty()) {
            Text(stringResource(R.string.no_languages), color = MaterialTheme.colorScheme.error)
        }
        Text(stringResource(R.string.speech_rate, ui.rate))
        Slider(value = ui.rate, onValueChange = reader::setRate, valueRange = 0.5f..2f, steps = 14)
        Text(stringResource(R.string.pitch, ui.pitch))
        Slider(value = ui.pitch, onValueChange = reader::setPitch, valueRange = 0.5f..2f, steps = 14)
        Text(stringResource(R.string.settings_next_play), style = MaterialTheme.typography.bodySmall)

        if (onlineVoice) OnlineSettings(ui, reader)
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = { agreementDialog = true }) {
            Text(stringResource(R.string.agreement))
        }
    }

    if (voiceDialog) {
        VoicePickerDialog(
            ui = ui,
            onSelect = {
                reader.selectVoice(it)
                voiceDialog = false
            },
            onDismiss = { voiceDialog = false }
        )
    }
    if (saveNameDialog) {
        NameInputDialog(
            title = stringResource(R.string.name_document),
            value = saveName,
            label = stringResource(R.string.document_name),
            confirmLabel = stringResource(R.string.save_text),
            onValueChange = { saveName = it.take(60) },
            onConfirm = {
                reader.saveCurrentText(saveName)
                saveNameDialog = false
            },
            onDismiss = { saveNameDialog = false }
        )
    }
    if (playlistNameDialog) {
        NameInputDialog(
            title = stringResource(R.string.create_playlist),
            value = playlistName,
            label = stringResource(R.string.playlist_name),
            confirmLabel = stringResource(R.string.create),
            onValueChange = { playlistName = it.take(40) },
            onConfirm = {
                reader.createPlaylist(playlistName)
                playlistNameDialog = false
            },
            onDismiss = { playlistNameDialog = false }
        )
    }
    if (deletePlaylistDialog) {
        AlertDialog(
            onDismissRequest = { deletePlaylistDialog = false },
            title = { Text(stringResource(R.string.delete_playlist_title)) },
            text = { Text(stringResource(R.string.delete_playlist_message)) },
            confirmButton = {
                TextButton(onClick = {
                    reader.deleteSelectedPlaylist()
                    deletePlaylistDialog = false
                }) { Text(stringResource(R.string.delete_text)) }
            },
            dismissButton = {
                TextButton(onClick = { deletePlaylistDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
    if (agreementDialog) {
        AlertDialog(
            onDismissRequest = { agreementDialog = false },
            title = { Text(stringResource(R.string.agreement)) },
            text = {
                Text(
                    stringResource(R.string.agreement_content),
                    modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = { agreementDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }
    pendingDeleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = { Text(stringResource(R.string.delete_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    reader.deleteSavedText(id)
                    pendingDeleteId = null
                }) { Text(stringResource(R.string.delete_text)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun NameInputDialog(
    title: String,
    value: String,
    label: String,
    confirmLabel: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = value.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun StatusCard(
    ui: UiState,
    reader: ReaderViewModel,
    installVoiceData: () -> Unit,
    online: Boolean
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.status_title), style = MaterialTheme.typography.titleMedium)
            if (ui.downloadBusy) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.width(22.dp))
                    Text(ui.status, style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                Text(ui.status, style = MaterialTheme.typography.bodyLarge)
            }
            ui.storageError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (ui.ttsReady && !ui.chineseAvailable) {
                OutlinedButton(onClick = installVoiceData) {
                    Text(stringResource(R.string.install_data))
                }
            }
            if (online && ui.ttsReady && ui.languages.isNotEmpty()) {
                TextButton(onClick = { reader.selectVoice(SYSTEM_DEFAULT) }) {
                    Text(stringResource(R.string.switch_offline))
                }
            }
        }
    }
}

@Composable
private fun NowReadingCard(ui: UiState) {
    val text = ui.text
    val safeStart = ui.highlightStart.coerceIn(0, text.length)
    val safeEnd = ui.highlightEnd.coerceIn(safeStart, text.length)
    val contextStart = (safeStart - 36).coerceAtLeast(0)
    val contextEnd = (safeEnd + 36).coerceAtMost(text.length)
    val highlighted = buildAnnotatedString {
        if (contextStart > 0) append("…")
        append(text.substring(contextStart, safeStart))
        withStyle(
            SpanStyle(
                background = MaterialTheme.colorScheme.tertiaryContainer,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                fontWeight = FontWeight.Bold
            )
        ) {
            append(text.substring(safeStart, safeEnd))
        }
        append(text.substring(safeEnd, contextEnd))
        if (contextEnd < text.length) append("…")
    }
    val title = ui.savedTexts.firstOrNull { it.id == ui.selectedTextId }?.title
        ?: stringResource(R.string.unsaved_draft)
    Card(
        modifier = Modifier.fillMaxWidth().testTag("now_reading"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.now_reading),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(
                    R.string.reading_title_and_count,
                    title,
                    ui.currentPlayNumber,
                    ui.playsPerItem
                ),
                style = MaterialTheme.typography.labelLarge
            )
            Text(highlighted, maxLines = 8, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PlaybackModeSection(ui: UiState, reader: ReaderViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(stringResource(R.string.playback_mode), style = MaterialTheme.typography.titleLarge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PlaybackMode.entries.forEach { mode ->
                    val label = when (mode) {
                        PlaybackMode.SEQUENTIAL -> stringResource(R.string.mode_sequential)
                        PlaybackMode.REPEAT_ONE -> stringResource(R.string.mode_repeat_one)
                        PlaybackMode.SHUFFLE -> stringResource(R.string.mode_shuffle)
                    }
                    FilterChip(
                        selected = ui.playbackMode == mode,
                        onClick = { reader.setPlaybackMode(mode) },
                        label = { Text(label, maxLines = 1) },
                        modifier = Modifier.weight(1f).testTag("mode_${mode.storedValue}")
                    )
                }
            }
            HorizontalDivider()
            Text(
                stringResource(R.string.plays_per_item, ui.playsPerItem),
                fontWeight = FontWeight.SemiBold
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { reader.setPlaysPerItem(ui.playsPerItem - 1) },
                    enabled = ui.playsPerItem > 1
                ) { Text("−") }
                Text("${ui.playsPerItem}", style = MaterialTheme.typography.titleLarge)
                OutlinedButton(
                    onClick = { reader.setPlaysPerItem(ui.playsPerItem + 1) },
                    enabled = ui.playsPerItem < 99
                ) { Text("+") }
            }
            Text(stringResource(R.string.mode_help), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TimerSection(ui: UiState, reader: ReaderViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(stringResource(R.string.timer_title), style = MaterialTheme.typography.titleLarge)
            if (ui.scheduledPlaybackAt == null) {
                Text(stringResource(R.string.timer_minutes, ui.timerMinutes))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10, 30, 60).forEach { minutes ->
                        FilterChip(
                            selected = ui.timerMinutes == minutes,
                            onClick = { reader.setTimerMinutes(minutes) },
                            label = { Text(stringResource(R.string.minutes_short, minutes)) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { reader.setTimerMinutes(ui.timerMinutes - 5) },
                        enabled = ui.timerMinutes > 1
                    ) { Text("−5") }
                    OutlinedButton(
                        onClick = { reader.setTimerMinutes(ui.timerMinutes + 5) },
                        enabled = ui.timerMinutes < 1_440
                    ) { Text("+5") }
                    Button(onClick = reader::scheduleTimedPlayback, enabled = ui.text.isNotBlank()) {
                        Text(stringResource(R.string.start_timer))
                    }
                }
            } else {
                Text(
                    stringResource(
                        R.string.timer_remaining,
                        formatDuration(ui.timerRemainingSeconds)
                    ),
                    style = MaterialTheme.typography.titleMedium
                )
                OutlinedButton(onClick = reader::cancelTimedPlayback) {
                    Text(stringResource(R.string.cancel_timer))
                }
            }
            Text(stringResource(R.string.timer_help), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val hours = seconds / 3_600
    val minutes = seconds % 3_600 / 60
    val remaining = seconds % 60
    return if (hours > 0) "%02d:%02d:%02d".format(hours, minutes, remaining)
    else "%02d:%02d".format(minutes, remaining)
}

@Composable
private fun PlaylistSection(
    ui: UiState,
    onCreate: () -> Unit,
    onDelete: () -> Unit,
    onSelect: (Long?) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val selected = ui.playlists.firstOrNull { it.id == ui.selectedPlaylistId }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(stringResource(R.string.playlist_title), style = MaterialTheme.typography.titleLarge)
            Box {
                OutlinedButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.fillMaxWidth().testTag("playlist_selector")
                ) {
                    Text(selected?.name ?: stringResource(R.string.all_documents))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.all_documents)) },
                        onClick = {
                            onSelect(null)
                            menuOpen = false
                        }
                    )
                    ui.playlists.forEach { playlist ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        R.string.playlist_with_count,
                                        playlist.name,
                                        playlist.documentIds.size
                                    )
                                )
                            },
                            onClick = {
                                onSelect(playlist.id)
                                menuOpen = false
                            }
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCreate, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.create_playlist))
                }
                OutlinedButton(
                    onClick = onDelete,
                    enabled = selected != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.delete_playlist))
                }
            }
            Text(
                if (selected == null) stringResource(R.string.all_documents_help)
                else stringResource(R.string.selected_playlist_help),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun TextLibrarySection(
    ui: UiState,
    reader: ReaderViewModel,
    onSave: () -> Unit,
    onDelete: (Long) -> Unit
) {
    val playlist = ui.playlists.firstOrNull { it.id == ui.selectedPlaylistId }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.text_library), style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onSave,
                    enabled = ui.text.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.save_text))
                }
                OutlinedButton(onClick = reader::newText, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.new_text))
                }
            }
            Text(stringResource(R.string.draft_autosave), style = MaterialTheme.typography.bodySmall)
            if (ui.savedTexts.isEmpty()) {
                Text(stringResource(R.string.no_saved_texts), style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(ui.savedTexts) { _, document ->
                        SavedTextRow(
                            document = document,
                            selected = ui.selectedTextId == document.id,
                            playing = ui.busy && ui.selectedTextId == document.id,
                            playlist = playlist,
                            onOpen = { reader.loadSavedText(document.id) },
                            onTogglePlaylist = { reader.toggleDocumentInSelectedPlaylist(document.id) },
                            onDelete = { onDelete(document.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedTextRow(
    document: SavedText,
    selected: Boolean,
    playing: Boolean,
    playlist: Playlist?,
    onOpen: () -> Unit,
    onTogglePlaylist: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = if (selected) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    } else CardDefaults.cardColors()
    Card(modifier = Modifier.fillMaxWidth(), colors = colors) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
                if (playing) {
                    Text(
                        stringResource(R.string.now_playing_mark),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium
                    )
                } else if (selected) {
                    Text(
                        stringResource(R.string.current_document),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                Text(document.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    document.text.replace('\n', ' '),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (document.offlineAudioPath != null || document.offlineWavPath != null) {
                    Text(
                        stringResource(R.string.offline_audio_mark),
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = onOpen) { Text(stringResource(R.string.load_text)) }
                if (playlist != null) {
                    TextButton(onClick = onTogglePlaylist) {
                        Text(
                            if (document.id in playlist.documentIds) {
                                stringResource(R.string.remove_from_playlist)
                            } else stringResource(R.string.add_to_playlist)
                        )
                    }
                }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.delete_text)) }
            }
        }
    }
}

@Composable
private fun OfflineDownloadSection(
    ui: UiState,
    currentDocument: SavedText?,
    reader: ReaderViewModel,
    exportMp3: () -> Unit,
    exportWav: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.offline_download_title), style = MaterialTheme.typography.titleMedium)
            val hasMp3 = currentDocument?.offlineAudioPath != null
            val hasWav = currentDocument?.offlineWavPath != null
            val downloaded = hasMp3 || hasWav
            if (downloaded) {
                val voiceName = ui.voices.firstOrNull {
                    it.id == currentDocument?.offlineVoiceId
                }?.name ?: stringResource(R.string.online_voice)
                val formats = when {
                    hasMp3 && hasWav -> stringResource(R.string.downloaded_formats_both)
                    hasWav -> stringResource(R.string.downloaded_formats_wav)
                    else -> stringResource(R.string.downloaded_formats_mp3)
                }
                Text(stringResource(R.string.downloaded_voice, voiceName, formats))
            } else {
                Text(stringResource(R.string.no_downloaded_audio))
            }
            Button(
                onClick = reader::downloadCurrentVoice,
                enabled = currentDocument != null &&
                    currentDocument.text == ui.text &&
                    !ui.busy &&
                    !ui.downloadBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (downloaded) stringResource(R.string.redownload_audio)
                    else stringResource(R.string.download_audio)
                )
            }
            if (downloaded) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = exportMp3,
                        enabled = hasMp3,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.export_mp3))
                    }
                    OutlinedButton(
                        onClick = exportWav,
                        enabled = hasWav,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.export_wav))
                    }
                }
                OutlinedButton(
                    onClick = reader::removeDownloadedAudio,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.remove_download))
                }
            }
            Text(stringResource(R.string.download_help), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun VoicePickerDialog(ui: UiState, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.voice_select)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 440.dp)) {
                itemsIndexed(ui.voices) { _, voice ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(voice.id) }
                            .padding(vertical = 6.dp)
                            .testTag("voice_${voice.id}"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = ui.voiceId == voice.id, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(voice.name, style = MaterialTheme.typography.bodyLarge)
                            VoiceDetail(voice)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        }
    )
}

@Composable
private fun VoiceDetail(voice: VoiceOption) {
    Text(
        stringResource(
            R.string.voice_details,
            if (voice.online) stringResource(R.string.online_type)
            else stringResource(R.string.offline_type),
            voice.language,
            voice.source
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun OnlineSettings(ui: UiState, reader: ReaderViewModel) {
    Text(stringResource(R.string.online_options), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.noise, ui.noise))
    Slider(value = ui.noise, onValueChange = reader::setNoise, valueRange = 0f..1f, steps = 19)
    Text(stringResource(R.string.length, ui.length))
    Slider(value = ui.length, onValueChange = reader::setLength, valueRange = 0.5f..2f, steps = 14)
    TextButton(onClick = { reader.setFormat(if (ui.format == "mp3") "wav" else "mp3") }) {
        Text(stringResource(R.string.format, ui.format))
    }
    if (ui.voiceId != CUSTOM) {
        Text(
            stringResource(R.string.hf_format_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (ui.voiceId == CUSTOM) {
        Text(stringResource(R.string.custom_server), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = ui.serverUrl,
            onValueChange = reader::setServer,
            label = { Text(stringResource(R.string.custom_server)) },
            placeholder = { Text(stringResource(R.string.server_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = ui.speaker,
            onValueChange = reader::setSpeaker,
            label = { Text(stringResource(R.string.custom_speaker)) },
            placeholder = { Text(stringResource(R.string.speaker_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Text(stringResource(R.string.custom_contract), style = MaterialTheme.typography.bodySmall)
    }
}
