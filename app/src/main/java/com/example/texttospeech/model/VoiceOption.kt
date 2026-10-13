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
    val ttsReady: Boolean = false,
    val chineseAvailable: Boolean = false,
    val status: String = "初始化中",
    val busy: Boolean = false
)

const val SYSTEM_DEFAULT = "system.default"
const val AYAKA = "online.ayaka"
const val EULA = "online.eula"
const val KLEE = "online.klee"
const val CUSTOM = "online.custom"
