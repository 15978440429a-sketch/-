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
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
                    ReaderScreen(reader, ::openVoiceDataSettings)
                }
            }
        }
        reader.attachActivity()
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
private fun ReaderScreen(reader: ReaderViewModel, installVoiceData: () -> Unit) {
    val ui by reader.uiState.collectAsStateWithLifecycle()
    var voiceDialog by remember { mutableStateOf(false) }
    var agreementDialog by remember { mutableStateOf(false) }
    var languageMenu by remember { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableStateOf<Long?>(null) }
    val selected = ui.voices.firstOrNull { it.id == ui.voiceId }
    val online = selected?.online == true

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
                enabled = ui.text.isNotBlank(),
                modifier = Modifier.weight(1f).testTag("play_button")
            ) {
                Text(stringResource(R.string.play))
            }
            OutlinedButton(
                onClick = { reader.stop() },
                enabled = ui.busy,
                modifier = Modifier.weight(1f).testTag("stop_button")
            ) {
                Text(stringResource(R.string.stop))
            }
            OutlinedButton(onClick = reader::clearText, enabled = ui.text.isNotEmpty()) {
                Text(stringResource(R.string.clear))
            }
        }

        StatusCard(ui, reader, installVoiceData, online)
        if (ui.busy && ui.highlightStart >= 0 && ui.highlightEnd > ui.highlightStart) {
            NowReadingCard(ui)
        }

        PlaybackModeSection(ui, reader)
        TextLibrarySection(ui, reader, onDelete = { pendingDeleteId = it })

        Text(stringResource(R.string.voice_settings), style = MaterialTheme.typography.titleLarge)
        OutlinedButton(
            onClick = { voiceDialog = true },
            modifier = Modifier.fillMaxWidth().testTag("voice_selector")
        ) {
            Text(
                stringResource(
                    R.string.voice_selected,
                    selected?.name ?: stringResource(R.string.offline_default)
                )
            )
        }
        if (selected != null) VoiceDetail(selected)
        if (online) {
            Text(
                stringResource(R.string.network_warning),
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.bodySmall
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

        if (online) OnlineSettings(ui, reader)
        Spacer(Modifier.height(4.dp))
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
                }) {
                    Text(stringResource(R.string.delete_text))
                }
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
private fun StatusCard(
    ui: UiState,
    reader: ReaderViewModel,
    installVoiceData: () -> Unit,
    online: Boolean
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.status_title), style = MaterialTheme.typography.titleMedium)
            Text(ui.status, style = MaterialTheme.typography.bodyLarge)
            ui.storageError?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
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
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(highlighted, maxLines = 8, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PlaybackModeSection(ui: UiState, reader: ReaderViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        Text(stringResource(R.string.mode_help), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TextLibrarySection(
    ui: UiState,
    reader: ReaderViewModel,
    onDelete: (Long) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.text_library), style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = reader::saveCurrentText,
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
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(ui.savedTexts) { _, document ->
                        SavedTextRow(
                            document = document,
                            selected = ui.selectedTextId == document.id,
                            playing = ui.busy && ui.selectedTextId == document.id,
                            onOpen = { reader.loadSavedText(document.id) },
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
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = if (selected) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    } else CardDefaults.cardColors()
    Card(modifier = Modifier.fillMaxWidth(), colors = colors) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f).clickable(onClick = onOpen),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
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
            }
            TextButton(onClick = onOpen) { Text(stringResource(R.string.load_text)) }
            TextButton(onClick = onDelete) { Text(stringResource(R.string.delete_text)) }
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
