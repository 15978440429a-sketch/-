package com.example.texttospeech

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SettingsVoice
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.texttospeech.model.CUSTOM
import com.example.texttospeech.model.PlaybackMode
import com.example.texttospeech.model.Playlist
import com.example.texttospeech.model.SYSTEM_DEFAULT
import com.example.texttospeech.model.SavedText
import com.example.texttospeech.model.UiState
import com.example.texttospeech.model.VoiceOption
import com.example.texttospeech.util.TextSegmenter

private val LightPalette = lightColorScheme(
    primary = Color(0xFF77613A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF3E3BA),
    onPrimaryContainer = Color(0xFF2A210E),
    secondary = Color(0xFF566744),
    secondaryContainer = Color(0xFFD9E8C5),
    tertiary = Color(0xFF8A4F45),
    background = Color(0xFFFBF8F1),
    surface = Color(0xFFFFFBF3),
    surfaceVariant = Color(0xFFEDE8DD)
)

private val DarkPalette = darkColorScheme(
    primary = Color(0xFFE0C58B),
    onPrimary = Color(0xFF3F3010),
    primaryContainer = Color(0xFF574619),
    onPrimaryContainer = Color(0xFFFBE7AE),
    secondary = Color(0xFFBECEA8),
    secondaryContainer = Color(0xFF3F4F31),
    tertiary = Color(0xFFFFB4A8),
    background = Color(0xFF171612),
    surface = Color(0xFF1E1D18),
    surfaceVariant = Color(0xFF34322B)
)

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
            MaterialTheme(
                colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) {
                    DarkPalette
                } else {
                    LightPalette
                }
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ReaderApp(
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

private enum class AppPage { PLAYER, EDITOR }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderApp(
    reader: ReaderViewModel,
    installVoiceData: () -> Unit,
    exportMp3: () -> Unit,
    exportWav: () -> Unit
) {
    val ui by reader.uiState.collectAsStateWithLifecycle()
    var pageName by rememberSaveable { mutableStateOf(AppPage.PLAYER.name) }
    val page = AppPage.valueOf(pageName)
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var queueOpen by rememberSaveable { mutableStateOf(false) }
    var voiceDialog by rememberSaveable { mutableStateOf(false) }
    var agreementDialog by rememberSaveable { mutableStateOf(false) }
    var saveNameDialog by rememberSaveable { mutableStateOf(false) }
    var saveName by rememberSaveable { mutableStateOf("") }
    var playlistNameDialog by rememberSaveable { mutableStateOf(false) }
    var playlistName by rememberSaveable { mutableStateOf("") }
    var deletePlaylistDialog by rememberSaveable { mutableStateOf(false) }
    var pendingDeleteId by rememberSaveable { mutableStateOf<Long?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            PlayerControlBar(
                ui = ui,
                page = page,
                onSettings = { settingsOpen = true },
                onTogglePage = {
                    pageName = if (page == AppPage.PLAYER) {
                        AppPage.EDITOR.name
                    } else {
                        AppPage.PLAYER.name
                    }
                },
                onPlayPause = reader::togglePlayback,
                onCycleMode = reader::cyclePlaybackMode,
                onQueue = { queueOpen = true }
            )
        }
    ) { innerPadding ->
        when (page) {
            AppPage.PLAYER -> PlayerScreen(
                ui = ui,
                reader = reader,
                modifier = Modifier.padding(innerPadding),
                onOpenEditor = { pageName = AppPage.EDITOR.name }
            )
            AppPage.EDITOR -> EditorScreen(
                ui = ui,
                reader = reader,
                modifier = Modifier.padding(innerPadding),
                exportMp3 = exportMp3,
                exportWav = exportWav,
                onSave = {
                    saveName = ""
                    saveNameDialog = true
                },
                onCreatePlaylist = {
                    playlistName = ""
                    playlistNameDialog = true
                },
                onDeletePlaylist = { deletePlaylistDialog = true },
                onDeleteDocument = { pendingDeleteId = it },
                onReturnToPlayer = { pageName = AppPage.PLAYER.name }
            )
        }
    }

    if (settingsOpen) {
        AudioSettingsSheet(
            ui = ui,
            reader = reader,
            installVoiceData = installVoiceData,
            onPickVoice = { voiceDialog = true },
            onAgreement = { agreementDialog = true },
            onDismiss = { settingsOpen = false }
        )
    }
    if (queueOpen) {
        PlaybackQueueSheet(
            ui = ui,
            onPlay = { id ->
                reader.playSavedText(id)
                pageName = AppPage.PLAYER.name
                queueOpen = false
            },
            onCycleMode = reader::cyclePlaybackMode,
            onDismiss = { queueOpen = false }
        )
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
            fieldTestTag = "document_name_input",
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
            fieldTestTag = "playlist_name_input",
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
    if (agreementDialog) {
        AgreementDialog(onDismiss = { agreementDialog = false })
    }
}

@Composable
private fun PlayerScreen(
    ui: UiState,
    reader: ReaderViewModel,
    modifier: Modifier = Modifier,
    onOpenEditor: () -> Unit
) {
    val document = ui.savedTexts.firstOrNull { it.id == ui.selectedTextId }
    val selectedVoice = ui.voices.firstOrNull { it.id == ui.voiceId }
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            PlaylistSelector(
                ui = ui,
                onSelect = reader::selectPlaylist,
                modifier = Modifier.weight(1f),
                testTag = "playlist_selector"
            )
            if (ui.busy || ui.paused || ui.downloadBusy) {
                OutlinedButton(
                    onClick = { reader.stop() },
                    modifier = Modifier.testTag("stop_button")
                ) {
                    Icon(Icons.Rounded.Stop, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.stop))
                }
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(
                document?.title ?: stringResource(R.string.unsaved_draft),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                selectedVoice?.name ?: stringResource(R.string.offline_default),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }

        SegmentedReadingSurface(
            ui = ui,
            modifier = Modifier.fillMaxWidth().weight(1f),
            onOpenEditor = onOpenEditor
        )

        StatusStrip(ui)
    }
}

@Composable
private fun SegmentedReadingSurface(
    ui: UiState,
    modifier: Modifier,
    onOpenEditor: () -> Unit
) {
    val segments = remember(ui.text) {
        if (ui.text.isBlank()) emptyList() else TextSegmenter.splitWithRanges(ui.text, 140)
    }
    val activeIndex = remember(segments, ui.highlightStart, ui.highlightEnd) {
        if (ui.highlightStart < 0) -1 else segments.indexOfFirst {
            ui.highlightStart < it.endExclusive && ui.highlightEnd > it.start
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) {
            listState.animateScrollToItem((activeIndex - 1).coerceAtLeast(0))
        }
    }

    Card(
        modifier = modifier.testTag("segmented_text"),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
        )
    ) {
        if (segments.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().clickable(onClick = onOpenEditor).padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Rounded.Description,
                    contentDescription = null,
                    modifier = Modifier.size(52.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.empty_player_text),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.empty_player_help),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 70.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                itemsIndexed(
                    items = segments,
                    key = { _, segment -> "${segment.start}-${segment.endExclusive}" }
                ) { index, segment ->
                    val active = index == activeIndex
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = if (active) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            Color.Transparent
                        }
                    ) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                            if (active) {
                                Text(
                                    stringResource(
                                        if (ui.paused) R.string.current_segment_paused
                                        else R.string.current_segment
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(4.dp))
                            }
                            Text(
                                segment.text,
                                style = if (active) {
                                    MaterialTheme.typography.headlineSmall
                                } else {
                                    MaterialTheme.typography.titleLarge
                                },
                                color = if (active) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                lineHeight = MaterialTheme.typography.headlineSmall.lineHeight
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusStrip(ui: UiState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (ui.downloadBusy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    if (ui.busy && !ui.paused) Icons.Rounded.GraphicEq else Icons.Rounded.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Column(Modifier.weight(1f)) {
                Text(ui.status, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                if (ui.busy || ui.paused) {
                    Text(
                        stringResource(
                            R.string.play_count_compact,
                            ui.currentPlayNumber,
                            ui.playsPerItem
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ui.storageError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun PlayerControlBar(
    ui: UiState,
    page: AppPage,
    onSettings: () -> Unit,
    onTogglePage: () -> Unit,
    onPlayPause: () -> Unit,
    onCycleMode: () -> Unit,
    onQueue: () -> Unit
) {
    Surface(
        tonalElevation = 8.dp,
        shadowElevation = 12.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(88.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BottomDockAction(
                label = stringResource(R.string.quality),
                icon = Icons.Rounded.SettingsVoice,
                selected = false,
                testTag = "quality_settings",
                onClick = onSettings,
                modifier = Modifier.weight(1f)
            )
            BottomDockAction(
                label = stringResource(
                    if (page == AppPage.PLAYER) R.string.documents else R.string.player_page
                ),
                icon = if (page == AppPage.PLAYER) Icons.Rounded.EditNote else Icons.Rounded.ArrowBack,
                selected = page == AppPage.EDITOR,
                testTag = "editor_tab",
                onClick = onTogglePage,
                modifier = Modifier.weight(1f)
            )
            Column(
                modifier = Modifier.weight(1.16f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                FilledIconButton(
                    onClick = onPlayPause,
                    enabled = ui.text.isNotBlank() && !ui.downloadBusy,
                    modifier = Modifier.size(58.dp).testTag("play_pause_button"),
                    shape = CircleShape
                ) {
                    Icon(
                        if (ui.busy && !ui.paused) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(
                            if (ui.busy && !ui.paused) R.string.pause else R.string.play
                        ),
                        modifier = Modifier.size(34.dp)
                    )
                }
                Text(
                    stringResource(if (ui.busy && !ui.paused) R.string.pause else R.string.play),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            BottomDockAction(
                label = playbackModeShortLabel(ui.playbackMode),
                icon = playbackModeIcon(ui.playbackMode),
                selected = false,
                testTag = "mode_cycle",
                onClick = onCycleMode,
                modifier = Modifier.weight(1f)
            )
            BottomDockAction(
                label = stringResource(R.string.queue),
                icon = Icons.Rounded.QueueMusic,
                selected = false,
                testTag = "queue_button",
                onClick = onQueue,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun BottomDockAction(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .height(72.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp)
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            icon,
            contentDescription = label,
            modifier = Modifier.size(27.dp),
            tint = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

private fun playbackModeIcon(mode: PlaybackMode): ImageVector = when (mode) {
    PlaybackMode.SEQUENTIAL -> Icons.Rounded.Repeat
    PlaybackMode.REPEAT_ONE -> Icons.Rounded.RepeatOne
    PlaybackMode.SHUFFLE -> Icons.Rounded.Shuffle
}

@Composable
private fun playbackModeShortLabel(mode: PlaybackMode): String = when (mode) {
    PlaybackMode.SEQUENTIAL -> stringResource(R.string.mode_sequential_short)
    PlaybackMode.REPEAT_ONE -> stringResource(R.string.mode_repeat_one_short)
    PlaybackMode.SHUFFLE -> stringResource(R.string.mode_shuffle_short)
}

@Composable
private fun PlaylistSelector(
    ui: UiState,
    onSelect: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    testTag: String
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = ui.playlists.firstOrNull { it.id == ui.selectedPlaylistId }
    Box(modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().testTag(testTag)
        ) {
            Icon(Icons.Rounded.LibraryMusic, contentDescription = null)
            Spacer(Modifier.width(7.dp))
            Text(
                selected?.name ?: stringResource(R.string.all_documents),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.all_documents)) },
                onClick = {
                    onSelect(null)
                    expanded = false
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
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun EditorScreen(
    ui: UiState,
    reader: ReaderViewModel,
    modifier: Modifier,
    exportMp3: () -> Unit,
    exportWav: () -> Unit,
    onSave: () -> Unit,
    onCreatePlaylist: () -> Unit,
    onDeletePlaylist: () -> Unit,
    onDeleteDocument: (Long) -> Unit,
    onReturnToPlayer: () -> Unit
) {
    val selectedVoice = ui.voices.firstOrNull { it.id == ui.voiceId }
    val currentDocument = ui.savedTexts.firstOrNull { it.id == ui.selectedTextId }
    val selectedPlaylist = ui.playlists.firstOrNull { it.id == ui.selectedPlaylistId }

    LazyColumn(
        modifier = modifier.fillMaxSize().statusBarsPadding().imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.documents_and_playlists),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(R.string.editor_page_help),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                TextButton(onClick = onReturnToPlayer) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = null)
                    Text(stringResource(R.string.player_page))
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(22.dp)) {
                Column(
                    Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = ui.text,
                        onValueChange = reader::updateText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 230.dp)
                            .testTag("text_input"),
                        label = { Text(stringResource(R.string.input_label)) },
                        placeholder = { Text(stringResource(R.string.input_hint)) },
                        minLines = 8
                    )
                    Text(
                        stringResource(
                            R.string.character_count,
                            ui.text.codePointCount(0, ui.text.length)
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.align(Alignment.End)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onSave,
                            enabled = ui.text.isNotBlank(),
                            modifier = Modifier.weight(1f).testTag("save_document")
                        ) {
                            Icon(Icons.Rounded.Save, contentDescription = null)
                            Spacer(Modifier.width(5.dp))
                            Text(stringResource(R.string.save_text))
                        }
                        OutlinedButton(onClick = reader::newText, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.new_text))
                        }
                        OutlinedButton(onClick = reader::clearText, enabled = ui.text.isNotEmpty()) {
                            Text(stringResource(R.string.clear))
                        }
                    }
                    Text(
                        stringResource(R.string.draft_autosave),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item {
            PlaylistEditorCard(
                ui = ui,
                reader = reader,
                selected = selectedPlaylist,
                onCreate = onCreatePlaylist,
                onDelete = onDeletePlaylist
            )
        }
        item { TimerCard(ui, reader) }
        if (selectedVoice?.online == true) {
            item {
                OfflineDownloadCard(
                    ui = ui,
                    currentDocument = currentDocument,
                    reader = reader,
                    exportMp3 = exportMp3,
                    exportWav = exportWav
                )
            }
        }
        item {
            Text(
                stringResource(R.string.text_library),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            if (ui.savedTexts.isEmpty()) {
                Text(
                    stringResource(R.string.no_saved_texts),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(ui.savedTexts, key = SavedText::id) { document ->
            SavedDocumentCard(
                document = document,
                selected = ui.selectedTextId == document.id,
                playing = (ui.busy || ui.paused) && ui.selectedTextId == document.id,
                playlist = selectedPlaylist,
                onOpen = { reader.loadSavedText(document.id) },
                onPlay = {
                    reader.playSavedText(document.id)
                    onReturnToPlayer()
                },
                onTogglePlaylist = { reader.toggleDocumentInSelectedPlaylist(document.id) },
                onDelete = { onDeleteDocument(document.id) }
            )
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun PlaylistEditorCard(
    ui: UiState,
    reader: ReaderViewModel,
    selected: Playlist?,
    onCreate: () -> Unit,
    onDelete: () -> Unit
) {
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.playlist_edit), style = MaterialTheme.typography.titleLarge)
            PlaylistSelector(
                ui = ui,
                onSelect = reader::selectPlaylist,
                modifier = Modifier.fillMaxWidth(),
                testTag = "editor_playlist_selector"
            )
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
            HorizontalDivider()
            Text(
                stringResource(R.string.plays_per_item, ui.playsPerItem),
                fontWeight = FontWeight.SemiBold
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
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
private fun TimerCard(ui: UiState, reader: ReaderViewModel) {
    Card(shape = RoundedCornerShape(22.dp)) {
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
private fun SavedDocumentCard(
    document: SavedText,
    selected: Boolean,
    playing: Boolean,
    playlist: Playlist?,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onTogglePlaylist: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Column(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
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
                Text(
                    document.title,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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
                TextButton(onClick = onPlay) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Text(stringResource(R.string.play))
                }
                TextButton(onClick = onOpen) { Text(stringResource(R.string.load_text)) }
                if (playlist != null) {
                    TextButton(onClick = onTogglePlaylist) {
                        Text(
                            if (document.id in playlist.documentIds) {
                                stringResource(R.string.remove_from_playlist)
                            } else {
                                stringResource(R.string.add_to_playlist)
                            }
                        )
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.delete_text)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioSettingsSheet(
    ui: UiState,
    reader: ReaderViewModel,
    installVoiceData: () -> Unit,
    onPickVoice: () -> Unit,
    onAgreement: () -> Unit,
    onDismiss: () -> Unit
) {
    val selectedVoice = ui.voices.firstOrNull { it.id == ui.voiceId }
    var languageMenu by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 700.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.quality_and_voice),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.quality_sheet_help),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = onPickVoice,
                modifier = Modifier.fillMaxWidth().testTag("voice_selector")
            ) {
                Icon(Icons.Rounded.SettingsVoice, contentDescription = null)
                Spacer(Modifier.width(7.dp))
                Text(
                    stringResource(
                        R.string.voice_selected,
                        selectedVoice?.name ?: stringResource(R.string.offline_default)
                    ),
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null)
            }
            selectedVoice?.let { VoiceDetail(it) }
            if (selectedVoice?.online == true) {
                Text(
                    stringResource(R.string.network_warning),
                    color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = { reader.selectVoice(SYSTEM_DEFAULT) }) {
                    Text(stringResource(R.string.switch_offline))
                }
            }
            HorizontalDivider()
            Text(stringResource(R.string.language), style = MaterialTheme.typography.titleMedium)
            Box {
                OutlinedButton(
                    onClick = { languageMenu = true },
                    enabled = ui.languages.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        ui.languages.firstOrNull { it.tag == ui.languageTag }?.label
                            ?: ui.languageTag,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null)
                }
                DropdownMenu(
                    expanded = languageMenu,
                    onDismissRequest = { languageMenu = false }
                ) {
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
                Text(
                    stringResource(R.string.no_languages),
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (ui.ttsReady && !ui.chineseAvailable) {
                OutlinedButton(onClick = installVoiceData) {
                    Text(stringResource(R.string.install_data))
                }
            }
            Text(stringResource(R.string.speech_rate, ui.rate))
            Slider(
                value = ui.rate,
                onValueChange = reader::setRate,
                valueRange = 0.5f..2f,
                steps = 14
            )
            Text(stringResource(R.string.pitch, ui.pitch))
            Slider(
                value = ui.pitch,
                onValueChange = reader::setPitch,
                valueRange = 0.5f..2f,
                steps = 14
            )
            Text(stringResource(R.string.settings_next_play), style = MaterialTheme.typography.bodySmall)
            if (selectedVoice?.online == true) {
                OnlineSettings(ui, reader)
            }
            HorizontalDivider()
            TextButton(onClick = onAgreement) {
                Text(stringResource(R.string.agreement))
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.close))
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackQueueSheet(
    ui: UiState,
    onPlay: (Long) -> Unit,
    onCycleMode: () -> Unit,
    onDismiss: () -> Unit
) {
    val playlist = ui.playlists.firstOrNull { it.id == ui.selectedPlaylistId }
    val byId = ui.savedTexts.associateBy(SavedText::id)
    val queue = playlist?.documentIds?.mapNotNull(byId::get) ?: ui.savedTexts
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.playback_queue),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(
                            R.string.queue_playlist_and_count,
                            playlist?.name ?: stringResource(R.string.all_documents),
                            queue.size
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilterChip(
                    selected = true,
                    onClick = onCycleMode,
                    label = { Text(playbackModeShortLabel(ui.playbackMode)) },
                    leadingIcon = {
                        Icon(playbackModeIcon(ui.playbackMode), contentDescription = null)
                    },
                    modifier = Modifier.testTag("queue_mode_cycle")
                )
            }
            HorizontalDivider()
            if (queue.isEmpty()) {
                Text(
                    stringResource(R.string.queue_empty),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 540.dp),
                    contentPadding = PaddingValues(bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    itemsIndexed(queue, key = { _, item -> item.id }) { index, document ->
                        val current = document.id == ui.selectedTextId
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPlay(document.id) }
                                .testTag("queue_item_${document.id}"),
                            shape = RoundedCornerShape(14.dp),
                            color = if (current) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                Color.Transparent
                            }
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${index + 1}",
                                    modifier = Modifier.width(28.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        document.title,
                                        fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        document.text.replace('\n', ' '),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (current && (ui.busy || ui.paused)) {
                                    Icon(
                                        if (ui.paused) Icons.Rounded.Pause else Icons.Rounded.GraphicEq,
                                        contentDescription = stringResource(R.string.now_playing_mark),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    Icon(
                                        Icons.Rounded.PlayArrow,
                                        contentDescription = stringResource(R.string.play)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflineDownloadCard(
    ui: UiState,
    currentDocument: SavedText?,
    reader: ReaderViewModel,
    exportMp3: () -> Unit,
    exportWav: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.offline_download_title),
                style = MaterialTheme.typography.titleMedium
            )
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
                    ) { Text(stringResource(R.string.export_mp3)) }
                    OutlinedButton(
                        onClick = exportWav,
                        enabled = hasWav,
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.export_wav)) }
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
private fun NameInputDialog(
    title: String,
    value: String,
    label: String,
    confirmLabel: String,
    fieldTestTag: String,
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
                modifier = Modifier.fillMaxWidth().testTag(fieldTestTag)
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = value.isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun VoicePickerDialog(
    ui: UiState,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.voice_select)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 470.dp)) {
                items(ui.voices, key = VoiceOption::id) { voice ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(voice.id) }
                            .padding(vertical = 7.dp)
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
    Slider(
        value = ui.noise,
        onValueChange = reader::setNoise,
        valueRange = 0f..1f,
        steps = 19
    )
    Text(stringResource(R.string.length, ui.length))
    Slider(
        value = ui.length,
        onValueChange = reader::setLength,
        valueRange = 0.5f..2f,
        steps = 14
    )
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

@Composable
private fun AgreementDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.agreement)) },
        text = {
            Text(
                stringResource(R.string.agreement_content),
                modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        }
    )
}
