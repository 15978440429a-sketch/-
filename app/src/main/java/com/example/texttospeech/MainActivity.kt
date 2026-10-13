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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.texttospeech.model.CUSTOM
import com.example.texttospeech.model.SYSTEM_DEFAULT
import com.example.texttospeech.model.UiState
import com.example.texttospeech.model.VoiceOption

class MainActivity : ComponentActivity() {
    private val reader: ReaderViewModel by viewModels()
    private val voiceDataLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Voice packages may have been installed without recreating this Activity.
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

    override fun onDestroy() {
        reader.detachActivity() // Also runs on rotation; the new Activity starts a fresh engine.
        super.onDestroy()
    }
}

@Composable
private fun ReaderScreen(reader: ReaderViewModel, installVoiceData: () -> Unit) {
    val ui by reader.uiState.collectAsStateWithLifecycle()
    var voiceDialog by remember { mutableStateOf(false) }
    var agreementDialog by remember { mutableStateOf(false) }
    var languageMenu by remember { mutableStateOf(false) }
    val selected = ui.voices.firstOrNull { it.id == ui.voiceId }
    val online = selected?.online == true

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = ui.text,
            onValueChange = reader::updateText,
            modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
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
            Button(onClick = reader::play, enabled = ui.text.isNotBlank(), modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.play))
            }
            OutlinedButton(onClick = { reader.stop() }, enabled = ui.busy, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.stop))
            }
            OutlinedButton(onClick = reader::clearText, enabled = ui.text.isNotEmpty()) {
                Text(stringResource(R.string.clear))
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.status_title), style = MaterialTheme.typography.titleMedium)
                Text(ui.status, style = MaterialTheme.typography.bodyLarge)
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

        Text(stringResource(R.string.voice_settings), style = MaterialTheme.typography.titleLarge)
        OutlinedButton(onClick = { voiceDialog = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.voice_selected, selected?.name ?: stringResource(R.string.offline_default)))
        }
        if (selected != null) {
            VoiceDetail(selected)
        }
        if (online) {
            Text(stringResource(R.string.network_warning), color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.bodySmall)
        }

        Text(stringResource(R.string.language), style = MaterialTheme.typography.titleMedium)
        Box {
            OutlinedButton(onClick = { languageMenu = true }, enabled = ui.languages.isNotEmpty()) {
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

        if (online) {
            OnlineSettings(ui, reader)
        }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = { agreementDialog = true }) {
            Text(stringResource(R.string.agreement))
        }
    }

    if (voiceDialog) {
        AlertDialog(
            onDismissRequest = { voiceDialog = false },
            title = { Text(stringResource(R.string.voice_select)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    items(ui.voices, key = { it.id }) { voice ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = ui.voiceId == voice.id,
                                onClick = { reader.selectVoice(voice.id); voiceDialog = false }
                            )
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
                TextButton(onClick = { voiceDialog = false }) { Text(stringResource(R.string.close)) }
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
                TextButton(onClick = { agreementDialog = false }) { Text(stringResource(R.string.close)) }
            }
        )
    }
}

@Composable
private fun VoiceDetail(voice: VoiceOption) {
    Text(
        stringResource(
            R.string.voice_details,
            if (voice.online) stringResource(R.string.online_type) else stringResource(R.string.offline_type),
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
            value = ui.serverUrl, onValueChange = reader::setServer,
            label = { Text(stringResource(R.string.custom_server)) },
            placeholder = { Text(stringResource(R.string.server_hint)) },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = ui.speaker, onValueChange = reader::setSpeaker,
            label = { Text(stringResource(R.string.custom_speaker)) },
            placeholder = { Text(stringResource(R.string.speaker_hint)) },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Text(stringResource(R.string.custom_contract), style = MaterialTheme.typography.bodySmall)
    }
}
