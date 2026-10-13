package com.example.texttospeech

import android.app.Application
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.texttospeech.model.AYAKA
import com.example.texttospeech.model.CUSTOM
import com.example.texttospeech.model.EULA
import com.example.texttospeech.model.KLEE
import com.example.texttospeech.model.LanguageOption
import com.example.texttospeech.model.SYSTEM_DEFAULT
import com.example.texttospeech.model.UiState
import com.example.texttospeech.model.VoiceOption
import com.example.texttospeech.network.OnlineSpeechClient
import com.example.texttospeech.playback.AudioFilePlayer
import com.example.texttospeech.tts.SystemTtsManager
import com.example.texttospeech.util.TextSegmenter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale

class ReaderViewModel(
    application: Application,
    private val saved: SavedStateHandle
) : AndroidViewModel(application), SystemTtsManager.Listener {
    private val app = application
    private val tts = SystemTtsManager(app, this)
    private val online = OnlineSpeechClient(app)
    private val audio = AudioFilePlayer()
    private var onlineJob: Job? = null
    private var offlineJob: Job? = null

    private val _uiState = MutableStateFlow(
        UiState(
            text = saved["text"] ?: "",
            voiceId = saved["voice"] ?: SYSTEM_DEFAULT,
            languageTag = saved["language"] ?: "zh-CN",
            rate = saved["rate"] ?: 1f,
            pitch = saved["pitch"] ?: 1f,
            noise = saved["noise"] ?: 0.667f,
            length = saved["length"] ?: 1.2f,
            format = saved["format"] ?: "mp3",
            serverUrl = saved["server"] ?: "",
            speaker = saved["speaker"] ?: "",
            voices = fixedVoices()
        )
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private fun fixedVoices() = listOf(
        VoiceOption(
            SYSTEM_DEFAULT, app.getString(R.string.offline_default), "跟随语言选择",
            app.getString(R.string.system_source), online = false
        ),
        VoiceOption(
            AYAKA, app.getString(R.string.ayaka), "中文",
            app.getString(R.string.public_source), online = true, onlineSpeaker = "神里绫华"
        ),
        VoiceOption(
            EULA, app.getString(R.string.eula), "中文",
            app.getString(R.string.public_source), online = true, onlineSpeaker = "优菈"
        ),
        VoiceOption(
            KLEE, app.getString(R.string.klee), "中文",
            app.getString(R.string.public_source), online = true, onlineSpeaker = "可莉"
        ),
        VoiceOption(
            CUSTOM, app.getString(R.string.custom_voice), "由服务器决定",
            app.getString(R.string.custom_source), online = true
        )
    )

    fun attachActivity() {
        _uiState.value = _uiState.value.copy(
            ttsReady = false,
            status = app.getString(R.string.status_initializing)
        )
        tts.initialize()
    }

    fun detachActivity() {
        stop()
        tts.shutdown()
        _uiState.value = _uiState.value.copy(ttsReady = false)
    }

    fun updateText(value: String) {
        // Keep rotation state in the retained ViewModel. Bound saved Bundle data for huge drafts.
        saved["text"] = if (value.length <= 20_000) value else ""
        _uiState.value = _uiState.value.copy(text = value)
    }

    fun clearText() {
        stop()
        updateText("")
    }

    fun selectVoice(id: String) {
        val option = _uiState.value.voices.firstOrNull { it.id == id } ?: return
        stop()
        saved["voice"] = id
        if (option.systemVoiceName != null) saved["language"] = option.language
        _uiState.value = _uiState.value.copy(
            voiceId = id,
            languageTag = if (option.systemVoiceName != null) option.language else _uiState.value.languageTag
        )
    }

    fun selectLanguage(tag: String) {
        if (_uiState.value.languages.none { it.tag == tag }) return
        stop()
        saved["language"] = tag
        val oldVoice = _uiState.value.voiceId
        val newVoice = if (oldVoice.startsWith("system.") && oldVoice != SYSTEM_DEFAULT) {
            saved["voice"] = SYSTEM_DEFAULT
            SYSTEM_DEFAULT
        } else oldVoice
        _uiState.value = _uiState.value.copy(languageTag = tag, voiceId = newVoice)
    }

    fun setRate(value: Float) {
        val rate = value.coerceIn(0.5f, 2f)
        saved["rate"] = rate
        _uiState.value = _uiState.value.copy(rate = rate)
    }

    fun setPitch(value: Float) {
        val pitch = value.coerceIn(0.5f, 2f)
        saved["pitch"] = pitch
        _uiState.value = _uiState.value.copy(pitch = pitch)
    }

    fun setNoise(value: Float) {
        val noise = value.coerceIn(0f, 1f)
        saved["noise"] = noise
        _uiState.value = _uiState.value.copy(noise = noise)
    }

    fun setLength(value: Float) {
        val length = value.coerceIn(0.5f, 2f)
        saved["length"] = length
        _uiState.value = _uiState.value.copy(length = length)
    }

    fun setFormat(value: String) {
        if (value != "mp3" && value != "wav") return
        saved["format"] = value
        _uiState.value = _uiState.value.copy(format = value)
    }

    fun setServer(value: String) {
        saved["server"] = value
        _uiState.value = _uiState.value.copy(serverUrl = value)
    }

    fun setSpeaker(value: String) {
        saved["speaker"] = value
        _uiState.value = _uiState.value.copy(speaker = value)
    }

    fun play() {
        val state = _uiState.value
        if (state.text.isBlank()) {
            showError(app.getString(R.string.error_empty))
            return
        }
        stop(showStatus = false)
        val option = state.voices.firstOrNull { it.id == state.voiceId }
        if (option?.online == true) {
            if (state.voiceId == CUSTOM && state.speaker.isBlank()) {
                showError(app.getString(R.string.error_speaker))
                return
            }
            if (state.voiceId == CUSTOM && !validServer(state.serverUrl)) {
                showError(app.getString(R.string.error_server_url))
                return
            }
            val server = if (state.voiceId == CUSTOM) state.serverUrl.trim()
                else OnlineSpeechClient.PUBLIC_ENDPOINT
            val speaker = option.onlineSpeaker ?: state.speaker.trim()
            _uiState.value = _uiState.value.copy(
                busy = true, status = app.getString(R.string.status_preparing)
            )
            onlineJob = viewModelScope.launch {
                val job = coroutineContext[Job]
                try {
                    val pieces = withContext(Dispatchers.Default) {
                        TextSegmenter.split(state.text, 180)
                    }
                    if (pieces.size > 80) {
                        showError(app.getString(R.string.error_online_length))
                        return@launch
                    }
                    pieces.forEachIndexed { index, piece ->
                        _uiState.value = _uiState.value.copy(
                            status = app.getString(R.string.status_requesting, index + 1, pieces.size)
                        )
                        val file = online.synthesize(
                            server,
                            OnlineSpeechClient.Parameters(
                                piece, speaker, state.format, state.noise, state.length
                            )
                        )
                        try {
                            _uiState.value = _uiState.value.copy(
                                status = app.getString(R.string.status_online_playing, index + 1, pieces.size)
                            )
                            audio.play(file)
                        } finally {
                            withContext(NonCancellable + Dispatchers.IO) { file.delete() }
                        }
                    }
                    _uiState.value = _uiState.value.copy(status = app.getString(R.string.status_finished))
                } catch (_: CancellationException) {
                    // The Stop button has already set the status.
                } catch (error: Exception) {
                    // A cancelled HTTP request may fail with IOException. Do not let
                    // an old request overwrite the state of a later playback.
                    coroutineContext.ensureActive()
                    showError(onlineError(error))
                } finally {
                    if (onlineJob === job) {
                        onlineJob = null
                        _uiState.value = _uiState.value.copy(busy = false)
                    }
                }
            }
        } else {
            if (!state.ttsReady) {
                showError(app.getString(R.string.error_not_ready))
                return
            }
            _uiState.value = _uiState.value.copy(
                busy = true, status = app.getString(R.string.status_preparing)
            )
            offlineJob = viewModelScope.launch {
                val job = coroutineContext[Job]
                try {
                    val chunks = withContext(Dispatchers.Default) {
                        TextSegmenter.split(
                            state.text, minOf(3500, TextToSpeech.getMaxSpeechInputLength() - 1)
                        )
                    }
                    val error = tts.speak(
                        chunks, state.languageTag, option?.systemVoiceName, state.rate, state.pitch
                    )
                    if (error != null) showError(error)
                } catch (_: CancellationException) {
                    // Stop has already updated the UI.
                } finally {
                    if (offlineJob === job) offlineJob = null
                }
            }
        }
    }

    private fun validServer(value: String): Boolean {
        val url = value.trim().toHttpUrlOrNull()
        return url?.scheme == "https" || url?.scheme == "http"
    }

    private fun onlineError(error: Exception): String = when (error) {
        is OnlineSpeechClient.HttpFailure -> app.getString(R.string.error_http, error.code)
        is OnlineSpeechClient.InvalidAudio -> app.getString(R.string.error_non_audio)
        is OnlineSpeechClient.AudioTooLarge -> app.getString(R.string.error_audio_too_large)
        is OnlineSpeechClient.InvalidAddress -> app.getString(R.string.error_server_url)
        is AudioFilePlayer.PlaybackFailure -> app.getString(R.string.error_playback)
        is SocketTimeoutException -> app.getString(R.string.error_timeout)
        is java.io.InterruptedIOException -> app.getString(R.string.error_timeout)
        is UnknownHostException, is ConnectException -> app.getString(R.string.error_network)
        is java.io.IOException -> app.getString(R.string.error_network)
        else -> app.getString(R.string.error_playback)
    }

    fun stop(showStatus: Boolean = true) {
        offlineJob?.cancel()
        offlineJob = null
        onlineJob?.cancel()
        onlineJob = null
        online.cancelActive()
        audio.stop()
        tts.stop()
        _uiState.value = _uiState.value.copy(
            busy = false,
            status = if (showStatus) app.getString(R.string.status_stopped) else _uiState.value.status
        )
    }

    private fun showError(message: String) {
        _uiState.value = _uiState.value.copy(busy = false, status = message)
    }

    override fun onReady(
        voices: List<VoiceOption>, languages: List<LanguageOption>, chineseAvailable: Boolean
    ) {
        val current = _uiState.value
        val available = if (languages.isEmpty() && chineseAvailable) {
            listOf(LanguageOption("zh-CN", "中文（中国） (zh-CN)"))
        } else languages
        val selected = if (available.any { it.tag == current.languageTag }) current.languageTag
            else available.firstOrNull { it.tag == "zh-CN" }?.tag
                ?: available.firstOrNull {
                    Locale.forLanguageTag(it.tag).language == Locale.CHINESE.language
                }?.tag
                ?: available.firstOrNull()?.tag ?: current.languageTag
        saved["language"] = selected
        val allVoices = listOf(fixedVoices().first()) + voices + fixedVoices().drop(1)
        val voiceId = if (allVoices.any { it.id == current.voiceId }) current.voiceId else SYSTEM_DEFAULT
        saved["voice"] = voiceId
        _uiState.value = current.copy(
            voices = allVoices,
            voiceId = voiceId,
            languages = available,
            languageTag = selected,
            ttsReady = true,
            chineseAvailable = chineseAvailable,
            status = if (current.busy) current.status else app.getString(
                if (chineseAvailable) R.string.status_ready else R.string.status_no_chinese
            )
        )
    }

    override fun onInitializationFailed() {
        _uiState.value = _uiState.value.copy(
            ttsReady = false,
            voices = fixedVoices(),
            languages = emptyList(),
            status = if (_uiState.value.busy) _uiState.value.status
                else app.getString(R.string.status_init_failed)
        )
    }

    override fun onSpeaking() {
        _uiState.value = _uiState.value.copy(status = app.getString(R.string.status_reading))
    }

    override fun onFinished() {
        _uiState.value = _uiState.value.copy(
            busy = false, status = app.getString(R.string.status_finished)
        )
    }

    override fun onSpeechError(code: Int) {
        showError(app.getString(R.string.error_tts_code, code))
    }

    override fun onCleared() {
        stop()
        tts.shutdown()
        super.onCleared()
    }
}
