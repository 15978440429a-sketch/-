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
import com.example.texttospeech.model.PlaybackMode
import com.example.texttospeech.model.SYSTEM_DEFAULT
import com.example.texttospeech.model.SavedText
import com.example.texttospeech.model.UiState
import com.example.texttospeech.model.VoiceOption
import com.example.texttospeech.network.OnlineSpeechClient
import com.example.texttospeech.playback.AudioFilePlayer
import com.example.texttospeech.storage.TextRepository
import com.example.texttospeech.tts.SystemTtsManager
import com.example.texttospeech.util.TextSegmenter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
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
import kotlin.random.Random

class ReaderViewModel(
    application: Application,
    private val saved: SavedStateHandle
) : AndroidViewModel(application), SystemTtsManager.Listener {
    private val app = application
    private val tts = SystemTtsManager(app, this)
    private val online = OnlineSpeechClient(app)
    private val audio = AudioFilePlayer()
    private val textRepository = TextRepository(app)

    private var onlineJob: Job? = null
    private var offlineJob: Job? = null
    private var draftSaveJob: Job? = null
    private var advanceJob: Job? = null
    private var playbackGeneration = 0L
    private var activePlaybackToken = -1L
    private var activeSegments: List<TextSegmenter.Segment> = emptyList()
    private var editVersion = saved.get<Long>("storage_revision") ?: 0L
    private var storageLoaded = false
    private var changedBeforeStorageLoad = false

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
            selectedTextId = saved.get<Long>("selected_text_id"),
            playbackMode = PlaybackMode.fromStored(saved["playback_mode"]),
            voices = fixedVoices()
        )
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        loadPersistentText()
    }

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

    private fun loadPersistentText() {
        viewModelScope.launch {
            try {
                val draft = textRepository.loadDraft()
                val documents = textRepository.loadDocuments()
                val current = _uiState.value
                val restoredText = saved.get<String>("text")
                val hasLocalText = restoredText != null || changedBeforeStorageLoad
                val chosenText = if (hasLocalText) current.text else draft.text
                editVersion = if (hasLocalText) maxOf(editVersion, draft.revision + 1)
                else draft.revision
                saved["storage_revision"] = editVersion
                val selectedId = current.selectedTextId ?: draft.selectedDocumentId
                val validSelectedId = selectedId?.takeIf { id -> documents.any { it.id == id } }
                val chosenMode = saved.get<String>("playback_mode")
                    ?.let(PlaybackMode::fromStored) ?: draft.playbackMode
                saveTextInSavedState(chosenText)
                saved["selected_text_id"] = validSelectedId
                saved["playback_mode"] = chosenMode.storedValue
                _uiState.value = current.copy(
                    text = chosenText,
                    savedTexts = documents,
                    selectedTextId = validSelectedId,
                    playbackMode = chosenMode,
                    storageReady = true,
                    storageError = null
                )
                storageLoaded = true
                scheduleDraftSave(0)
            } catch (_: Exception) {
                storageLoaded = true
                _uiState.value = _uiState.value.copy(
                    storageReady = true,
                    storageError = app.getString(R.string.error_storage)
                )
            }
        }
    }

    fun attachActivity() {
        _uiState.value = _uiState.value.copy(
            ttsReady = false,
            status = app.getString(R.string.status_initializing)
        )
        tts.initialize()
    }

    fun detachActivity() {
        persistNow()
        stop()
        tts.shutdown()
        _uiState.value = _uiState.value.copy(ttsReady = false)
    }

    fun updateText(value: String) {
        if (_uiState.value.busy) stop()
        bumpRevision()
        saveTextInSavedState(value)
        _uiState.value = _uiState.value.copy(
            text = value,
            highlightStart = -1,
            highlightEnd = -1
        )
        scheduleDraftSave()
    }

    fun clearText() {
        stop()
        updateText("")
    }

    fun newText() {
        stop()
        bumpRevision()
        saved["text"] = ""
        saved.remove<Long>("selected_text_id")
        _uiState.value = _uiState.value.copy(
            text = "",
            selectedTextId = null,
            highlightStart = -1,
            highlightEnd = -1,
            status = app.getString(R.string.status_new_document)
        )
        scheduleDraftSave(0)
    }

    fun saveCurrentText() {
        val state = _uiState.value
        if (state.text.isBlank()) {
            showError(app.getString(R.string.error_empty))
            return
        }
        val capturedText = state.text
        val capturedId = state.selectedTextId
        viewModelScope.launch {
            try {
                val id = textRepository.saveDocument(capturedId, capturedText)
                val documents = textRepository.loadDocuments()
                val current = _uiState.value
                saved["selected_text_id"] = id
                bumpRevision()
                _uiState.value = current.copy(
                    savedTexts = documents,
                    selectedTextId = id,
                    storageReady = true,
                    storageError = null,
                    status = app.getString(R.string.status_document_saved)
                )
                scheduleDraftSave(0)
            } catch (_: Exception) {
                showStorageError()
            }
        }
    }

    fun loadSavedText(id: Long) {
        val document = _uiState.value.savedTexts.firstOrNull { it.id == id } ?: return
        stop(showStatus = false)
        loadDocumentIntoEditor(document, app.getString(R.string.status_document_loaded))
    }

    fun deleteSavedText(id: Long) {
        if (_uiState.value.selectedTextId == id) stop(showStatus = false)
        viewModelScope.launch {
            try {
                textRepository.deleteDocument(id)
                val documents = textRepository.loadDocuments()
                val current = _uiState.value
                val selectedId = current.selectedTextId.takeIf { it != id }
                saved["selected_text_id"] = selectedId
                bumpRevision()
                _uiState.value = current.copy(
                    savedTexts = documents,
                    selectedTextId = selectedId,
                    storageError = null,
                    status = app.getString(R.string.status_document_deleted)
                )
                scheduleDraftSave(0)
            } catch (_: Exception) {
                showStorageError()
            }
        }
    }

    fun setPlaybackMode(mode: PlaybackMode) {
        bumpRevision()
        saved["playback_mode"] = mode.storedValue
        _uiState.value = _uiState.value.copy(playbackMode = mode)
        scheduleDraftSave(0)
    }

    fun persistNow() {
        draftSaveJob?.cancel()
        val state = _uiState.value
        val revision = editVersion
        draftSaveJob = viewModelScope.launch {
            persistSnapshot(state, revision)
        }
    }

    fun persistNowBlocking() {
        draftSaveJob?.cancel()
        val state = _uiState.value
        runCatching {
            textRepository.saveDraftBlocking(
                state.text,
                state.playbackMode,
                state.selectedTextId,
                editVersion
            )
        }.onFailure {
            _uiState.value = _uiState.value.copy(storageError = app.getString(R.string.error_storage))
        }
    }

    private fun scheduleDraftSave(delayMillis: Long = 350) {
        draftSaveJob?.cancel()
        val state = _uiState.value
        val revision = editVersion
        draftSaveJob = viewModelScope.launch {
            if (delayMillis > 0) delay(delayMillis)
            persistSnapshot(state, revision)
        }
    }

    private suspend fun persistSnapshot(state: UiState, revision: Long) {
        try {
            textRepository.saveDraft(state.text, state.playbackMode, state.selectedTextId, revision)
            if (_uiState.value.storageError != null) {
                _uiState.value = _uiState.value.copy(storageError = null)
            }
        } catch (_: Exception) {
            showStorageError()
        }
    }

    private fun showStorageError() {
        _uiState.value = _uiState.value.copy(
            storageReady = true,
            storageError = app.getString(R.string.error_storage)
        )
    }

    private fun loadDocumentIntoEditor(document: SavedText, status: String) {
        bumpRevision()
        saveTextInSavedState(document.text)
        saved["selected_text_id"] = document.id
        _uiState.value = _uiState.value.copy(
            text = document.text,
            selectedTextId = document.id,
            highlightStart = -1,
            highlightEnd = -1,
            status = status
        )
        scheduleDraftSave(0)
    }

    fun selectVoice(id: String) {
        val option = _uiState.value.voices.firstOrNull { it.id == id } ?: return
        stop()
        saved["voice"] = id
        if (option.systemVoiceName != null) saved["language"] = option.language
        _uiState.value = _uiState.value.copy(
            voiceId = id,
            languageTag = if (option.systemVoiceName != null) option.language
            else _uiState.value.languageTag
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
        val token = playbackGeneration
        activePlaybackToken = token
        val option = state.voices.firstOrNull { it.id == state.voiceId }
        if (option?.online == true) {
            playOnline(state, option, token)
        } else {
            playOffline(state, option, token)
        }
    }

    private fun playOnline(state: UiState, option: VoiceOption, token: Long) {
        if (state.voiceId == CUSTOM && state.speaker.isBlank()) {
            showError(app.getString(R.string.error_speaker))
            return
        }
        if (state.voiceId == CUSTOM && !validServer(state.serverUrl)) {
            showError(app.getString(R.string.error_server_url))
            return
        }
        val customServer = state.serverUrl.trim()
        val speaker = option.onlineSpeaker ?: state.speaker.trim()
        _uiState.value = _uiState.value.copy(
            busy = true,
            status = app.getString(R.string.status_preparing)
        )
        onlineJob = viewModelScope.launch {
            val job = coroutineContext[Job]
            try {
                val segments = withContext(Dispatchers.Default) {
                    TextSegmenter.splitWithRanges(state.text, 180)
                }
                if (segments.size > 80) {
                    showError(app.getString(R.string.error_online_length))
                    return@launch
                }
                activeSegments = segments
                segments.forEachIndexed { index, segment ->
                    if (activePlaybackToken != token) return@launch
                    showActiveSegment(
                        segment,
                        app.getString(R.string.status_requesting, index + 1, segments.size)
                    )
                    val parameters = OnlineSpeechClient.Parameters(
                        segment.text, speaker, state.format, state.noise, state.length
                    )
                    val file = if (state.voiceId == CUSTOM) {
                        online.synthesize(customServer, parameters)
                    } else {
                        online.synthesizeFirstAvailable(
                            OnlineSpeechClient.PUBLIC_ENDPOINTS,
                            parameters
                        ) { attempt, _ ->
                            if (attempt > 1) {
                                showActiveSegment(
                                    segment,
                                    app.getString(
                                        R.string.status_retrying_public,
                                        index + 1,
                                        segments.size
                                    )
                                )
                            }
                        }
                    }
                    try {
                        showActiveSegment(
                            segment,
                            app.getString(R.string.status_online_playing, index + 1, segments.size)
                        )
                        audio.play(file)
                    } finally {
                        withContext(NonCancellable + Dispatchers.IO) { file.delete() }
                    }
                }
            } catch (_: CancellationException) {
                return@launch
            } catch (error: Exception) {
                coroutineContext.ensureActive()
                showError(onlineError(error))
                return@launch
            } finally {
                if (onlineJob === job) onlineJob = null
            }
            completePlayback(token)
        }
    }

    private fun playOffline(state: UiState, option: VoiceOption?, token: Long) {
        if (!state.ttsReady) {
            showError(app.getString(R.string.error_not_ready))
            return
        }
        _uiState.value = _uiState.value.copy(
            busy = true,
            status = app.getString(R.string.status_preparing)
        )
        offlineJob = viewModelScope.launch {
            val job = coroutineContext[Job]
            try {
                val segments = withContext(Dispatchers.Default) {
                    TextSegmenter.splitWithRanges(
                        state.text,
                        minOf(280, TextToSpeech.getMaxSpeechInputLength() - 1)
                    )
                }
                if (activePlaybackToken != token) return@launch
                activeSegments = segments
                val error = tts.speak(
                    segments.map { it.text },
                    state.languageTag,
                    option?.systemVoiceName,
                    state.rate,
                    state.pitch
                )
                if (error != null) showError(error)
            } catch (_: CancellationException) {
                return@launch
            } catch (_: RuntimeException) {
                showError(app.getString(R.string.error_speak))
            } finally {
                if (offlineJob === job) offlineJob = null
            }
        }
    }

    private fun showActiveSegment(segment: TextSegmenter.Segment, status: String) {
        _uiState.value = _uiState.value.copy(
            busy = true,
            status = status,
            highlightStart = segment.start,
            highlightEnd = segment.endExclusive
        )
    }

    private fun completePlayback(token: Long) {
        if (token < 0 || activePlaybackToken != token) return
        activePlaybackToken = -1L
        activeSegments = emptyList()
        val state = _uiState.value
        _uiState.value = state.copy(
            busy = false,
            status = app.getString(R.string.status_finished),
            highlightStart = -1,
            highlightEnd = -1
        )

        when (state.playbackMode) {
            PlaybackMode.REPEAT_ONE -> scheduleAutomaticPlayback(token, null)
            PlaybackMode.SEQUENTIAL -> {
                val currentIndex = state.savedTexts.indexOfFirst { it.id == state.selectedTextId }
                val next = if (currentIndex < 0) state.savedTexts.firstOrNull()
                else state.savedTexts.getOrNull(currentIndex + 1)
                if (next != null) scheduleAutomaticPlayback(token, next)
            }

            PlaybackMode.SHUFFLE -> {
                val choices = state.savedTexts.filter { it.id != state.selectedTextId }
                val pool = if (choices.isNotEmpty()) choices else state.savedTexts
                if (pool.isNotEmpty()) {
                    scheduleAutomaticPlayback(token, pool[Random.nextInt(pool.size)])
                }
            }
        }
    }

    private fun scheduleAutomaticPlayback(token: Long, document: SavedText?) {
        advanceJob?.cancel()
        advanceJob = viewModelScope.launch {
            delay(450)
            if (playbackGeneration != token) return@launch
            advanceJob = null
            if (document != null) {
                loadDocumentIntoEditor(document, app.getString(R.string.status_next_document, document.title))
            }
            play()
        }
    }

    private fun validServer(value: String): Boolean {
        val url = value.trim().toHttpUrlOrNull()
        return url?.scheme == "https" || url?.scheme == "http"
    }

    private fun onlineError(error: Exception): String = when (error) {
        is OnlineSpeechClient.AllServersUnavailable ->
            app.getString(R.string.error_public_service)
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
        playbackGeneration++
        activePlaybackToken = -1L
        activeSegments = emptyList()
        advanceJob?.cancel()
        advanceJob = null
        offlineJob?.cancel()
        offlineJob = null
        onlineJob?.cancel()
        onlineJob = null
        runCatching { online.cancelActive() }
        runCatching { audio.stop() }
        tts.stop()
        _uiState.value = _uiState.value.copy(
            busy = false,
            highlightStart = -1,
            highlightEnd = -1,
            status = if (showStatus) app.getString(R.string.status_stopped) else _uiState.value.status
        )
    }

    private fun showError(message: String) {
        activePlaybackToken = -1L
        activeSegments = emptyList()
        _uiState.value = _uiState.value.copy(
            busy = false,
            highlightStart = -1,
            highlightEnd = -1,
            status = message
        )
    }

    override fun onReady(
        voices: List<VoiceOption>,
        languages: List<LanguageOption>,
        chineseAvailable: Boolean
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
            ?: available.firstOrNull()?.tag
            ?: current.languageTag
        saved["language"] = selected
        val allVoices = (fixedVoices() + voices).distinctBy { it.id }
        val voiceId = if (allVoices.any { it.id == current.voiceId }) current.voiceId
        else SYSTEM_DEFAULT
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
        val currentVoice = _uiState.value.voiceId
        val availableVoices = fixedVoices()
        val safeVoice = if (availableVoices.any { it.id == currentVoice }) currentVoice
        else SYSTEM_DEFAULT
        saved["voice"] = safeVoice
        _uiState.value = _uiState.value.copy(
            ttsReady = false,
            voices = availableVoices,
            voiceId = safeVoice,
            languages = emptyList(),
            status = if (_uiState.value.busy) _uiState.value.status
            else app.getString(R.string.status_init_failed)
        )
    }

    override fun onSpeaking(segmentIndex: Int) {
        val segment = activeSegments.getOrNull(segmentIndex)
        _uiState.value = _uiState.value.copy(
            status = app.getString(R.string.status_reading),
            highlightStart = segment?.start ?: -1,
            highlightEnd = segment?.endExclusive ?: -1
        )
    }

    override fun onFinished() {
        completePlayback(activePlaybackToken)
    }

    override fun onSpeechError(code: Int) {
        showError(app.getString(R.string.error_tts_code, code))
    }

    override fun onCleared() {
        persistNowBlocking()
        stop()
        tts.shutdown()
        super.onCleared()
    }

    companion object {
        private const val SAVED_STATE_TEXT_LIMIT = 20_000
    }

    private fun bumpRevision() {
        if (!storageLoaded) changedBeforeStorageLoad = true
        editVersion++
        saved["storage_revision"] = editVersion
    }

    private fun saveTextInSavedState(text: String) {
        if (text.length <= SAVED_STATE_TEXT_LIMIT) saved["text"] = text
        else saved.remove<String>("text")
    }
}
