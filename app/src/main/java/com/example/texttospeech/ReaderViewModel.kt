package com.example.texttospeech

import android.app.Application
import android.content.Context
import android.net.Uri
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
import com.example.texttospeech.model.Playlist
import com.example.texttospeech.model.SYSTEM_DEFAULT
import com.example.texttospeech.model.SavedText
import com.example.texttospeech.model.UiState
import com.example.texttospeech.model.VoiceOption
import com.example.texttospeech.network.HuggingFaceVitsClient
import com.example.texttospeech.network.OnlineSpeechClient
import com.example.texttospeech.playback.AudioFilePlayer
import com.example.texttospeech.playback.OfflineAudioExporter
import com.example.texttospeech.storage.TextRepository
import com.example.texttospeech.tts.SystemTtsManager
import com.example.texttospeech.util.TextSegmenter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.Locale
import kotlin.random.Random

class ReaderViewModel(
    application: Application,
    private val saved: SavedStateHandle
) : AndroidViewModel(application), SystemTtsManager.Listener {
    private val app = application
    private val tts = SystemTtsManager(app, this)
    private val huggingFace = HuggingFaceVitsClient(app)
    private val online = OnlineSpeechClient(app)
    private val audio = AudioFilePlayer()
    private val audioExporter = OfflineAudioExporter()
    private val textRepository = TextRepository(app)
    private val preferences = app.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE)

    private var onlineJob: Job? = null
    private var offlineJob: Job? = null
    private var downloadJob: Job? = null
    private var draftSaveJob: Job? = null
    private var advanceJob: Job? = null
    private var timerJob: Job? = null
    private var playbackGeneration = 0L
    private var activePlaybackToken = -1L
    private var activeSegments: List<TextSegmenter.Segment> = emptyList()
    private var completedPlaysForItem = 0
    private var editVersion = saved.get<Long>("storage_revision") ?: 0L
    private var storageLoaded = false
    private var changedBeforeStorageLoad = false

    private val storedTimerTarget = preferences.getLong(PREF_TIMER_TARGET, 0L).takeIf { it > 0L }
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
            selectedPlaylistId = saved.get<Long>("selected_playlist_id"),
            playbackMode = PlaybackMode.fromStored(saved["playback_mode"]),
            playsPerItem = (saved.get<Int>("plays_per_item") ?: 1).coerceIn(1, 99),
            timerMinutes = preferences.getInt(PREF_TIMER_MINUTES, 10).coerceIn(1, 1_440),
            scheduledPlaybackAt = storedTimerTarget,
            timerRemainingSeconds = storedTimerTarget?.let(::remainingSeconds) ?: 0,
            voices = fixedVoices()
        )
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        loadPersistentText()
    }

    private fun fixedVoices() = listOf(
        VoiceOption(
            SYSTEM_DEFAULT,
            app.getString(R.string.offline_default),
            "跟随语言选择",
            app.getString(R.string.system_source),
            online = false
        ),
        VoiceOption(
            AYAKA,
            app.getString(R.string.ayaka),
            "中文",
            app.getString(R.string.public_source),
            online = true,
            onlineSpeaker = "神里绫华"
        ),
        VoiceOption(
            EULA,
            app.getString(R.string.eula),
            "中文",
            app.getString(R.string.public_source),
            online = true,
            onlineSpeaker = "优菈"
        ),
        VoiceOption(
            KLEE,
            app.getString(R.string.klee),
            "中文",
            app.getString(R.string.public_source),
            online = true,
            onlineSpeaker = "可莉"
        ),
        VoiceOption(
            CUSTOM,
            app.getString(R.string.custom_voice),
            "由服务器决定",
            app.getString(R.string.custom_source),
            online = true
        )
    )

    private fun loadPersistentText() {
        viewModelScope.launch {
            try {
                val draft = textRepository.loadDraft()
                val documents = textRepository.loadDocuments()
                val playlists = textRepository.loadPlaylists()
                val current = _uiState.value
                val restoredText = saved.get<String>("text")
                val hasLocalText = restoredText != null || changedBeforeStorageLoad
                val chosenText = if (hasLocalText) current.text else draft.text
                editVersion = if (hasLocalText) maxOf(editVersion, draft.revision + 1)
                else draft.revision
                saved["storage_revision"] = editVersion

                val selectedId = current.selectedTextId ?: draft.selectedDocumentId
                val validSelectedId = selectedId?.takeIf { id -> documents.any { it.id == id } }
                val playlistId = current.selectedPlaylistId ?: draft.selectedPlaylistId
                val validPlaylistId = playlistId?.takeIf { id -> playlists.any { it.id == id } }
                val chosenMode = saved.get<String>("playback_mode")
                    ?.let(PlaybackMode::fromStored) ?: draft.playbackMode
                val chosenPlays = saved.get<Int>("plays_per_item")
                    ?.coerceIn(1, 99) ?: draft.playsPerItem

                saveTextInSavedState(chosenText)
                saved["selected_text_id"] = validSelectedId
                saved["selected_playlist_id"] = validPlaylistId
                saved["playback_mode"] = chosenMode.storedValue
                saved["plays_per_item"] = chosenPlays
                _uiState.value = current.copy(
                    text = chosenText,
                    savedTexts = documents,
                    playlists = playlists,
                    selectedTextId = validSelectedId,
                    selectedPlaylistId = validPlaylistId,
                    playbackMode = chosenMode,
                    playsPerItem = chosenPlays,
                    storageReady = true,
                    storageError = null
                )
                storageLoaded = true
                scheduleDraftSave(0)
                startTimerMonitor()
            } catch (_: Exception) {
                storageLoaded = true
                _uiState.value = _uiState.value.copy(
                    storageReady = true,
                    storageError = app.getString(R.string.error_storage)
                )
                startTimerMonitor()
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
        if (_uiState.value.busy || _uiState.value.downloadBusy) stop()
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

    fun suggestedTitle(): String {
        val state = _uiState.value
        state.savedTexts.firstOrNull { it.id == state.selectedTextId }?.let { return it.title }
        val line = state.text.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty)
            ?: app.getString(R.string.unnamed_document)
        return line.take(40)
    }

    fun saveCurrentText(title: String) {
        val state = _uiState.value
        if (state.text.isBlank()) {
            showError(app.getString(R.string.error_empty))
            return
        }
        if (title.isBlank()) {
            showError(app.getString(R.string.error_title_empty))
            return
        }
        val capturedText = state.text
        val capturedId = state.selectedTextId
        val previous = state.savedTexts.firstOrNull { it.id == capturedId }
        val keepAudio = previous?.text == capturedText
        viewModelScope.launch {
            try {
                val id = textRepository.saveDocument(
                    capturedId,
                    title,
                    capturedText,
                    keepOfflineAudio = keepAudio
                )
                if (!keepAudio) previous?.offlineAudioPath?.let { File(it).delete() }
                val selectedPlaylist = _uiState.value.playlists
                    .firstOrNull { it.id == _uiState.value.selectedPlaylistId }
                if (capturedId == null && selectedPlaylist != null) {
                    textRepository.addToPlaylist(selectedPlaylist, id)
                }
                refreshLibrary()
                saved["selected_text_id"] = id
                bumpRevision()
                _uiState.value = _uiState.value.copy(
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
        val document = _uiState.value.savedTexts.firstOrNull { it.id == id }
        if (_uiState.value.selectedTextId == id) stop(showStatus = false)
        viewModelScope.launch {
            try {
                textRepository.deleteDocument(id)
                document?.offlineAudioPath?.let { File(it).delete() }
                refreshLibrary()
                val selectedId = _uiState.value.selectedTextId.takeIf { it != id }
                saved["selected_text_id"] = selectedId
                bumpRevision()
                _uiState.value = _uiState.value.copy(
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

    fun createPlaylist(name: String) {
        if (name.isBlank()) {
            showError(app.getString(R.string.error_playlist_name))
            return
        }
        viewModelScope.launch {
            try {
                val id = textRepository.createPlaylist(name)
                refreshLibrary()
                selectPlaylist(id)
                _uiState.value = _uiState.value.copy(
                    status = app.getString(R.string.status_playlist_created)
                )
            } catch (_: Exception) {
                showStorageError()
            }
        }
    }

    fun deleteSelectedPlaylist() {
        val id = _uiState.value.selectedPlaylistId ?: return
        stop(showStatus = false)
        viewModelScope.launch {
            try {
                textRepository.deletePlaylist(id)
                refreshLibrary()
                selectPlaylist(null)
                _uiState.value = _uiState.value.copy(
                    status = app.getString(R.string.status_playlist_deleted)
                )
            } catch (_: Exception) {
                showStorageError()
            }
        }
    }

    fun selectPlaylist(id: Long?) {
        if (id != null && _uiState.value.playlists.none { it.id == id }) return
        stop(showStatus = false)
        bumpRevision()
        saved["selected_playlist_id"] = id
        _uiState.value = _uiState.value.copy(selectedPlaylistId = id)
        scheduleDraftSave(0)
    }

    fun toggleDocumentInSelectedPlaylist(documentId: Long) {
        val state = _uiState.value
        val playlist = state.playlists.firstOrNull { it.id == state.selectedPlaylistId } ?: return
        viewModelScope.launch {
            try {
                if (documentId in playlist.documentIds) {
                    textRepository.removeFromPlaylist(playlist.id, documentId)
                } else {
                    textRepository.addToPlaylist(playlist, documentId)
                }
                refreshLibrary()
                _uiState.value = _uiState.value.copy(
                    status = app.getString(R.string.status_playlist_updated)
                )
            } catch (_: Exception) {
                showStorageError()
            }
        }
    }

    private suspend fun refreshLibrary() {
        val documents = textRepository.loadDocuments()
        val playlists = textRepository.loadPlaylists()
        val validPlaylist = _uiState.value.selectedPlaylistId
            ?.takeIf { selected -> playlists.any { it.id == selected } }
        _uiState.value = _uiState.value.copy(
            savedTexts = documents,
            playlists = playlists,
            selectedPlaylistId = validPlaylist
        )
        saved["selected_playlist_id"] = validPlaylist
    }

    fun setPlaybackMode(mode: PlaybackMode) {
        stop(showStatus = false)
        bumpRevision()
        saved["playback_mode"] = mode.storedValue
        _uiState.value = _uiState.value.copy(playbackMode = mode)
        scheduleDraftSave(0)
    }

    fun setPlaysPerItem(value: Int) {
        val plays = value.coerceIn(1, 99)
        stop(showStatus = false)
        bumpRevision()
        saved["plays_per_item"] = plays
        _uiState.value = _uiState.value.copy(playsPerItem = plays, currentPlayNumber = 1)
        scheduleDraftSave(0)
    }

    fun persistNow() {
        draftSaveJob?.cancel()
        val state = _uiState.value
        val revision = editVersion
        draftSaveJob = viewModelScope.launch { persistSnapshot(state, revision) }
    }

    fun persistNowBlocking() {
        draftSaveJob?.cancel()
        val state = _uiState.value
        runCatching {
            textRepository.saveDraftBlocking(
                state.text,
                state.playbackMode,
                state.selectedTextId,
                state.selectedPlaylistId,
                state.playsPerItem,
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
            textRepository.saveDraft(
                state.text,
                state.playbackMode,
                state.selectedTextId,
                state.selectedPlaylistId,
                state.playsPerItem,
                revision
            )
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
        completedPlaysForItem = 0
        _uiState.value = _uiState.value.copy(currentPlayNumber = 1)
        startCurrentPlayback(playbackGeneration)
    }

    private fun startCurrentPlayback(token: Long) {
        val state = _uiState.value
        if (state.text.isBlank() || token != playbackGeneration) return
        activePlaybackToken = token
        val option = state.voices.firstOrNull { it.id == state.voiceId }
        val cached = cachedAudioFor(state)
        when {
            cached != null -> playDownloadedAudio(state, cached, token)
            option?.online == true -> playOnline(state, option, token)
            else -> playOffline(state, option, token)
        }
    }

    private fun cachedAudioFor(state: UiState): File? {
        val document = state.savedTexts.firstOrNull { it.id == state.selectedTextId } ?: return null
        if (document.offlineVoiceId != state.voiceId) return null
        if (document.offlineTextHash != textHash(state.text)) return null
        return document.offlineAudioPath?.let(::File)?.takeIf(File::isFile)
    }

    private fun playDownloadedAudio(state: UiState, file: File, token: Long) {
        activeSegments = listOf(TextSegmenter.Segment(state.text, 0, state.text.length))
        _uiState.value = _uiState.value.copy(
            busy = true,
            status = app.getString(R.string.status_offline_mp3_playing),
            highlightStart = 0,
            highlightEnd = state.text.length
        )
        onlineJob = viewModelScope.launch {
            val job = currentCoroutineContext()[Job]
            try {
                audio.play(file)
            } catch (_: CancellationException) {
                return@launch
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                showError(onlineError(error))
                return@launch
            } finally {
                if (onlineJob === job) onlineJob = null
            }
            completePlayback(token)
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
            val job = currentCoroutineContext()[Job]
            try {
                val publicVoice = state.voiceId != CUSTOM
                val segmentLimit = if (publicVoice) 96 else 180
                val maximumSegments = if (publicVoice) 125 else 80
                val segments = withContext(Dispatchers.Default) {
                    TextSegmenter.splitWithRanges(state.text, segmentLimit)
                }
                if (segments.size > maximumSegments) {
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
                        segment.text,
                        speaker,
                        state.format,
                        state.noise,
                        state.length
                    )
                    val file = if (publicVoice) {
                        synthesizePreset(parameters, index + 1, segments.size, segment)
                    } else {
                        online.synthesize(customServer, parameters)
                    }
                    try {
                        showActiveSegment(
                            segment,
                            app.getString(
                                R.string.status_online_playing,
                                index + 1,
                                segments.size
                            )
                        )
                        audio.play(file)
                    } finally {
                        withContext(NonCancellable + Dispatchers.IO) { file.delete() }
                    }
                }
            } catch (_: CancellationException) {
                return@launch
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                showError(onlineError(error))
                return@launch
            } finally {
                if (onlineJob === job) onlineJob = null
            }
            completePlayback(token)
        }
    }

    private suspend fun synthesizePreset(
        parameters: OnlineSpeechClient.Parameters,
        part: Int,
        total: Int,
        segment: TextSegmenter.Segment?
    ): File = try {
        huggingFace.synthesize(
            HuggingFaceVitsClient.Parameters(
                text = parameters.text,
                speaker = parameters.speaker,
                noise = parameters.noise,
                length = parameters.length
            )
        ) { stage ->
            val stringId = when (stage) {
                HuggingFaceVitsClient.Stage.CONNECTING -> R.string.status_hf_connecting
                HuggingFaceVitsClient.Stage.WAITING -> R.string.status_hf_waiting
                HuggingFaceVitsClient.Stage.SYNTHESIZING -> R.string.status_hf_synthesizing
            }
            updateSynthesisStatus(segment, app.getString(stringId, part, total))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (huggingFaceError: Exception) {
        currentCoroutineContext().ensureActive()
        updateSynthesisStatus(
            segment,
            app.getString(R.string.status_fallback_public, part, total)
        )
        try {
            online.synthesizeFirstAvailable(
                OnlineSpeechClient.PUBLIC_ENDPOINTS,
                parameters
            ) { attempt, _ ->
                if (attempt > 1) {
                    updateSynthesisStatus(
                        segment,
                        app.getString(R.string.status_retrying_public, part, total)
                    )
                }
            }
        } catch (legacyError: Exception) {
            legacyError.addSuppressed(huggingFaceError)
            throw legacyError
        }
    }

    private fun updateSynthesisStatus(segment: TextSegmenter.Segment?, status: String) {
        if (segment == null) {
            _uiState.value = _uiState.value.copy(status = status)
        } else {
            showActiveSegment(segment, status)
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
            val job = currentCoroutineContext()[Job]
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
        completedPlaysForItem++
        val state = _uiState.value
        _uiState.value = state.copy(
            busy = false,
            status = app.getString(R.string.status_finished),
            highlightStart = -1,
            highlightEnd = -1
        )

        if (state.playbackMode == PlaybackMode.REPEAT_ONE) {
            _uiState.value = _uiState.value.copy(
                currentPlayNumber = completedPlaysForItem % state.playsPerItem + 1
            )
            scheduleAutomaticPlayback(token, null)
            return
        }
        if (completedPlaysForItem < state.playsPerItem) {
            _uiState.value = _uiState.value.copy(currentPlayNumber = completedPlaysForItem + 1)
            scheduleAutomaticPlayback(token, null)
            return
        }

        completedPlaysForItem = 0
        _uiState.value = _uiState.value.copy(currentPlayNumber = 1)
        val queue = playbackQueue(state)
        when (state.playbackMode) {
            PlaybackMode.REPEAT_ONE -> Unit
            PlaybackMode.SEQUENTIAL -> {
                if (queue.isNotEmpty()) {
                    val currentIndex = queue.indexOfFirst { it.id == state.selectedTextId }
                    val nextIndex = if (currentIndex < 0) 0 else (currentIndex + 1) % queue.size
                    scheduleAutomaticPlayback(token, queue[nextIndex])
                }
            }

            PlaybackMode.SHUFFLE -> {
                val choices = queue.filter { it.id != state.selectedTextId }
                val pool = if (choices.isNotEmpty()) choices else queue
                if (pool.isNotEmpty()) {
                    scheduleAutomaticPlayback(token, pool[Random.nextInt(pool.size)])
                }
            }
        }
    }

    private fun playbackQueue(state: UiState): List<SavedText> {
        val playlist = state.playlists.firstOrNull { it.id == state.selectedPlaylistId }
            ?: return state.savedTexts
        val byId = state.savedTexts.associateBy(SavedText::id)
        return playlist.documentIds.mapNotNull(byId::get)
    }

    private fun scheduleAutomaticPlayback(token: Long, document: SavedText?) {
        advanceJob?.cancel()
        advanceJob = viewModelScope.launch {
            delay(450)
            if (playbackGeneration != token) return@launch
            advanceJob = null
            if (document != null) {
                loadDocumentIntoEditor(
                    document,
                    app.getString(R.string.status_next_document, document.title)
                )
            }
            startCurrentPlayback(token)
        }
    }

    fun downloadCurrentVoice() {
        val state = _uiState.value
        val document = state.savedTexts.firstOrNull { it.id == state.selectedTextId }
        val option = state.voices.firstOrNull { it.id == state.voiceId }
        if (document == null || document.text != state.text) {
            showError(app.getString(R.string.error_save_before_download))
            return
        }
        if (option?.online != true) {
            showError(app.getString(R.string.error_online_voice_required))
            return
        }
        if (state.voiceId == CUSTOM && state.speaker.isBlank()) {
            showError(app.getString(R.string.error_speaker))
            return
        }
        if (state.voiceId == CUSTOM && !validServer(state.serverUrl)) {
            showError(app.getString(R.string.error_server_url))
            return
        }

        stop(showStatus = false)
        _uiState.value = _uiState.value.copy(
            downloadBusy = true,
            status = app.getString(R.string.status_download_start)
        )
        downloadJob = viewModelScope.launch {
            val job = currentCoroutineContext()[Job]
            val parts = mutableListOf<File>()
            try {
                val publicVoice = state.voiceId != CUSTOM
                val segments = withContext(Dispatchers.Default) {
                    TextSegmenter.splitWithRanges(state.text, if (publicVoice) 96 else 180)
                }
                val maximumSegments = if (publicVoice) 125 else 80
                if (segments.size > maximumSegments) {
                    showError(app.getString(R.string.error_online_length))
                    return@launch
                }
                val speaker = option.onlineSpeaker ?: state.speaker.trim()
                segments.forEachIndexed { index, segment ->
                    currentCoroutineContext().ensureActive()
                    _uiState.value = _uiState.value.copy(
                        status = app.getString(
                            R.string.status_download_synthesizing,
                            index + 1,
                            segments.size
                        )
                    )
                    val parameters = OnlineSpeechClient.Parameters(
                        text = segment.text,
                        speaker = speaker,
                        format = "wav",
                        noise = state.noise,
                        length = state.length
                    )
                    parts += if (publicVoice) {
                        synthesizePreset(parameters, index + 1, segments.size, null)
                    } else {
                        online.synthesize(state.serverUrl.trim(), parameters)
                    }
                }

                _uiState.value = _uiState.value.copy(
                    status = app.getString(R.string.status_encoding_mp3)
                )
                val directory = File(app.filesDir, "offline_audio")
                val destination = File(directory, "${document.id}-${state.voiceId}.mp3")
                val previousPath = document.offlineAudioPath
                audioExporter.createMp3(parts, destination)
                textRepository.attachOfflineAudio(
                    document.id,
                    destination.absolutePath,
                    state.voiceId,
                    textHash(state.text)
                )
                if (previousPath != null && previousPath != destination.absolutePath) {
                    File(previousPath).delete()
                }
                refreshLibrary()
                _uiState.value = _uiState.value.copy(
                    downloadBusy = false,
                    status = app.getString(R.string.status_download_complete)
                )
            } catch (_: CancellationException) {
                return@launch
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                val message = when (error) {
                    is OfflineAudioExporter.UnsupportedWave,
                    is OfflineAudioExporter.EncodingFailed ->
                        app.getString(R.string.error_mp3_encoding)
                    else -> onlineError(error)
                }
                showError(message)
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { parts.forEach(File::delete) }
                if (downloadJob === job) downloadJob = null
                if (_uiState.value.downloadBusy) {
                    _uiState.value = _uiState.value.copy(downloadBusy = false)
                }
            }
        }
    }

    fun removeDownloadedAudio() {
        val document = currentDocument() ?: return
        val path = document.offlineAudioPath ?: return
        if (_uiState.value.busy) stop(showStatus = false)
        viewModelScope.launch {
            try {
                textRepository.clearOfflineAudio(document.id)
                withContext(Dispatchers.IO) { File(path).delete() }
                refreshLibrary()
                _uiState.value = _uiState.value.copy(
                    status = app.getString(R.string.status_download_removed)
                )
            } catch (_: Exception) {
                showStorageError()
            }
        }
    }

    fun downloadedAudioFileName(): String? {
        val document = currentDocument() ?: return null
        val path = document.offlineAudioPath ?: return null
        if (!File(path).isFile) return null
        return sanitizeFileName("${document.title}-${voiceDisplayName(document.offlineVoiceId)}.mp3")
    }

    fun exportDownloadedAudio(destination: Uri) {
        val document = currentDocument() ?: return
        val source = document.offlineAudioPath?.let(::File)?.takeIf(File::isFile) ?: run {
            showError(app.getString(R.string.error_download_missing))
            return
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(destination, "w")?.use { output ->
                        source.inputStream().use { input -> input.copyTo(output) }
                    } ?: throw java.io.IOException("Cannot open destination")
                }
                _uiState.value = _uiState.value.copy(
                    status = app.getString(R.string.status_export_complete)
                )
            } catch (_: Exception) {
                showError(app.getString(R.string.error_export))
            }
        }
    }

    private fun currentDocument(): SavedText? {
        val state = _uiState.value
        return state.savedTexts.firstOrNull { it.id == state.selectedTextId }
    }

    private fun voiceDisplayName(id: String?): String =
        _uiState.value.voices.firstOrNull { it.id == id }?.name
            ?.replace("（在线）", "")
            ?: app.getString(R.string.online_voice)

    fun setTimerMinutes(value: Int) {
        val minutes = value.coerceIn(1, 1_440)
        preferences.edit().putInt(PREF_TIMER_MINUTES, minutes).apply()
        _uiState.value = _uiState.value.copy(timerMinutes = minutes)
    }

    fun scheduleTimedPlayback() {
        if (_uiState.value.text.isBlank()) {
            showError(app.getString(R.string.error_empty))
            return
        }
        val target = System.currentTimeMillis() + _uiState.value.timerMinutes * 60_000L
        preferences.edit().putLong(PREF_TIMER_TARGET, target).apply()
        _uiState.value = _uiState.value.copy(
            scheduledPlaybackAt = target,
            timerRemainingSeconds = remainingSeconds(target),
            status = app.getString(R.string.status_timer_scheduled, _uiState.value.timerMinutes)
        )
        startTimerMonitor()
    }

    fun cancelTimedPlayback() {
        timerJob?.cancel()
        timerJob = null
        preferences.edit().remove(PREF_TIMER_TARGET).apply()
        _uiState.value = _uiState.value.copy(
            scheduledPlaybackAt = null,
            timerRemainingSeconds = 0,
            status = app.getString(R.string.status_timer_cancelled)
        )
    }

    private fun startTimerMonitor() {
        timerJob?.cancel()
        val target = _uiState.value.scheduledPlaybackAt ?: return
        timerJob = viewModelScope.launch {
            while (true) {
                val remaining = remainingSeconds(target)
                _uiState.value = _uiState.value.copy(timerRemainingSeconds = remaining)
                if (remaining <= 0) break
                delay(1_000)
            }
            if (_uiState.value.scheduledPlaybackAt != target) return@launch
            preferences.edit().remove(PREF_TIMER_TARGET).apply()
            _uiState.value = _uiState.value.copy(
                scheduledPlaybackAt = null,
                timerRemainingSeconds = 0,
                status = app.getString(R.string.status_timer_starting)
            )
            while (!_uiState.value.storageReady) delay(100)
            val state = _uiState.value
            val option = state.voices.firstOrNull { it.id == state.voiceId }
            if (option?.online != true && cachedAudioFor(state) == null && !state.ttsReady) {
                var attempts = 0
                while (!_uiState.value.ttsReady && attempts < 150) {
                    delay(100)
                    attempts++
                }
            }
            play()
            timerJob = null
        }
    }

    private fun remainingSeconds(target: Long): Long =
        ((target - System.currentTimeMillis()).coerceAtLeast(0L) + 999L) / 1_000L

    private fun validServer(value: String): Boolean {
        val url = value.trim().toHttpUrlOrNull()
        return url?.scheme == "https" || url?.scheme == "http"
    }

    private fun onlineError(error: Exception): String = when (error) {
        is OnlineSpeechClient.AllServersUnavailable -> app.getString(R.string.error_public_service)
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
        completedPlaysForItem = 0
        advanceJob?.cancel()
        advanceJob = null
        offlineJob?.cancel()
        offlineJob = null
        onlineJob?.cancel()
        onlineJob = null
        downloadJob?.cancel()
        downloadJob = null
        runCatching { huggingFace.cancelActive() }
        runCatching { online.cancelActive() }
        runCatching { audio.stop() }
        tts.stop()
        _uiState.value = _uiState.value.copy(
            busy = false,
            downloadBusy = false,
            currentPlayNumber = 1,
            highlightStart = -1,
            highlightEnd = -1,
            status = if (showStatus) app.getString(R.string.status_stopped)
            else _uiState.value.status
        )
    }

    private fun showError(message: String) {
        activePlaybackToken = -1L
        activeSegments = emptyList()
        _uiState.value = _uiState.value.copy(
            busy = false,
            downloadBusy = false,
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
            status = if (current.busy || current.downloadBusy) current.status else app.getString(
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
            status = if (_uiState.value.busy || _uiState.value.downloadBusy) _uiState.value.status
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
        timerJob?.cancel()
        stop()
        tts.shutdown()
        super.onCleared()
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

    private fun textHash(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }

    private fun sanitizeFileName(value: String): String = value
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .take(100)

    companion object {
        private const val SAVED_STATE_TEXT_LIMIT = 20_000
        private const val SETTINGS_NAME = "reader_settings"
        private const val PREF_TIMER_TARGET = "timer_target"
        private const val PREF_TIMER_MINUTES = "timer_minutes"
    }
}
