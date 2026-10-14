package com.example.texttospeech.model

data class VoiceOption(
    val id: String,
    val name: String,
    val language: String,
    val source: String,
    val online: Boolean,
    val systemVoiceName: String? = null,
    val onlineSpeaker: String? = null
)

data class LanguageOption(val tag: String, val label: String)

data class SavedText(
    val id: Long,
    val title: String,
    val text: String,
    val updatedAt: Long,
    val offlineAudioPath: String? = null,
    val offlineWavPath: String? = null,
    val offlineVoiceId: String? = null,
    val offlineTextHash: String? = null
)

data class Playlist(
    val id: Long,
    val name: String,
    val documentIds: List<Long>,
    val updatedAt: Long
)

enum class PlaybackMode(val storedValue: String) {
    SEQUENTIAL("sequential"),
    REPEAT_ONE("repeat_one"),
    SHUFFLE("shuffle");

    companion object {
        fun fromStored(value: String?): PlaybackMode =
            entries.firstOrNull { it.storedValue == value } ?: SEQUENTIAL
    }
}

data class UiState(
    val text: String = "",
    val voiceId: String = SYSTEM_DEFAULT,
    val languageTag: String = "zh-CN",
    val rate: Float = 1f,
    val pitch: Float = 1f,
    val noise: Float = 0.667f,
    val length: Float = 1.2f,
    val format: String = "mp3",
    val serverUrl: String = "",
    val speaker: String = "",
    val voices: List<VoiceOption> = emptyList(),
    val languages: List<LanguageOption> = emptyList(),
    val savedTexts: List<SavedText> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val selectedTextId: Long? = null,
    val selectedPlaylistId: Long? = null,
    val playbackMode: PlaybackMode = PlaybackMode.SEQUENTIAL,
    val playsPerItem: Int = 1,
    val currentPlayNumber: Int = 1,
    val highlightStart: Int = -1,
    val highlightEnd: Int = -1,
    val downloadBusy: Boolean = false,
    val timerMinutes: Int = 10,
    val scheduledPlaybackAt: Long? = null,
    val timerRemainingSeconds: Long = 0,
    val storageReady: Boolean = false,
    val storageError: String? = null,
    val ttsReady: Boolean = false,
    val chineseAvailable: Boolean = false,
    val status: String = "初始化中",
    val busy: Boolean = false,
    val paused: Boolean = false
)

const val SYSTEM_DEFAULT = "system.default"
const val CUSTOM = "online.custom"
