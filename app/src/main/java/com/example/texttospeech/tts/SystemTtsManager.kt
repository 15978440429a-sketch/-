package com.example.texttospeech.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.example.texttospeech.R
import com.example.texttospeech.model.LanguageOption
import com.example.texttospeech.model.VoiceOption
import java.util.Locale
import java.util.UUID

class SystemTtsManager(context: Context, private val listener: Listener) {
    interface Listener {
        fun onReady(voices: List<VoiceOption>, languages: List<LanguageOption>, chineseAvailable: Boolean)
        fun onInitializationFailed()
        fun onSpeaking(segmentIndex: Int)
        fun onSentencePause(segmentIndex: Int)
        fun onFinished()
        fun onSpeechError(code: Int)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var generation = 0
    private var activePrefix = ""
    private var finalUtterance = ""
    private var offlineVoices: List<Voice> = emptyList()
    var isReady: Boolean = false
        private set

    fun initialize() {
        shutdown()
        val token = generation
        try {
            engine = TextToSpeech(appContext) { result ->
                mainHandler.post {
                    if (generation != token) return@post
                    val tts = engine ?: return@post
                    if (result != TextToSpeech.SUCCESS) {
                        shutdown()
                        listener.onInitializationFailed()
                        return@post
                    }
                    try {
                        installProgressListener(tts)
                        loadAvailableVoices(tts)
                    } catch (_: RuntimeException) {
                        shutdown()
                        listener.onInitializationFailed()
                    }
                }
            }
        } catch (_: RuntimeException) {
            shutdown()
            listener.onInitializationFailed()
        }
    }

    private fun installProgressListener(tts: TextToSpeech) {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                mainHandler.post {
                    if (activePrefix.isNotEmpty() && utteranceId.startsWith(activePrefix)) {
                        val index = utteranceId.substringAfterLast('-').toIntOrNull() ?: 0
                        listener.onSpeaking(index)
                    }
                }
            }

            override fun onDone(utteranceId: String) {
                mainHandler.post {
                    if (activePrefix.isNotEmpty() && utteranceId.startsWith(activePrefix)) {
                        if (utteranceId == finalUtterance) {
                            activePrefix = ""
                            finalUtterance = ""
                            listener.onFinished()
                        } else {
                            val index = utteranceId.substringAfterLast('-').toIntOrNull() ?: 0
                            listener.onSentencePause(index)
                        }
                    }
                }
            }

            override fun onError(utteranceId: String) = reportError(utteranceId, -1)

            override fun onError(utteranceId: String, errorCode: Int) =
                reportError(utteranceId, errorCode)

            private fun reportError(utteranceId: String, code: Int) {
                mainHandler.post {
                    if (activePrefix.isNotEmpty() && utteranceId.startsWith(activePrefix)) {
                        activePrefix = ""
                        finalUtterance = ""
                        runCatching { tts.stop() }
                        listener.onSpeechError(code)
                    }
                }
            }
        })
    }

    private fun loadAvailableVoices(tts: TextToSpeech) {
        offlineVoices = runCatching {
            tts.voices.orEmpty()
                .filter { it.isInstalledOffline() }
                .distinctBy { it.name }
                .sortedWith(compareBy({ it.locale.toLanguageTag() }, { it.name }))
        }.getOrDefault(emptyList())

        val fallbackLocales = if (offlineVoices.isEmpty()) {
            val previousVoice = runCatching { tts.voice }.getOrNull()
            val found = runCatching {
                tts.availableLanguages.orEmpty().filter { locale ->
                    tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE &&
                        tts.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE &&
                        tts.voice?.isInstalledOffline() == true
                }
            }.getOrDefault(emptyList())
            if (previousVoice != null) runCatching { tts.setVoice(previousVoice) }
            found
        } else emptyList()

        val locales = (offlineVoices.map { it.locale } + fallbackLocales)
            .distinctBy { it.toLanguageTag() }
            .sortedBy { it.getDisplayName(Locale.CHINA) }
        val languages = locales.map {
            LanguageOption(
                it.toLanguageTag(),
                "${it.getDisplayName(Locale.CHINA)} (${it.toLanguageTag()})"
            )
        }
        val source = runCatching { tts.defaultEngine }.getOrNull()
            ?: appContext.getString(R.string.system_source)
        val voices = offlineVoices.map {
            VoiceOption(
                id = "system.${it.name}",
                name = it.name,
                language = it.locale.toLanguageTag(),
                source = source,
                online = false,
                systemVoiceName = it.name
            )
        }.distinctBy { it.id }
        val chineseAvailable = locales.any { it.language == Locale.CHINESE.language }
        isReady = true
        listener.onReady(voices, languages, chineseAvailable)
    }

    /** Returns a localized error, or null if every utterance was queued. */
    fun speak(
        chunks: List<String>,
        languageTag: String,
        voiceName: String?,
        rate: Float,
        pitch: Float,
        sentencePauseMillis: Long
    ): String? {
        val tts = engine ?: return appContext.getString(R.string.error_not_ready)
        if (!isReady) return appContext.getString(R.string.error_not_ready)
        if (chunks.isEmpty()) return appContext.getString(R.string.error_empty)

        return try {
            val locale = Locale.forLanguageTag(languageTag)
            if (locale.language.isBlank() ||
                tts.isLanguageAvailable(locale) < TextToSpeech.LANG_AVAILABLE ||
                tts.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE
            ) return appContext.getString(R.string.error_language)

            val voice = if (voiceName == null) {
                offlineVoices.firstOrNull { it.locale.toLanguageTag() == languageTag }
                    ?: offlineVoices.firstOrNull { it.locale.language == locale.language }
            } else {
                offlineVoices.firstOrNull {
                    it.name == voiceName && it.locale.language == locale.language
                }
            }
            if (voice == null) {
                if (voiceName != null || tts.voice?.isInstalledOffline() != true) {
                    return appContext.getString(R.string.error_voice)
                }
            } else if (tts.setVoice(voice) != TextToSpeech.SUCCESS) {
                return appContext.getString(R.string.error_voice)
            }

            if (tts.setSpeechRate(rate) != TextToSpeech.SUCCESS ||
                tts.setPitch(pitch) != TextToSpeech.SUCCESS
            ) return appContext.getString(R.string.error_speak)

            stop()
            activePrefix = UUID.randomUUID().toString()
            finalUtterance = "$activePrefix-${chunks.lastIndex}"
            chunks.forEachIndexed { index, chunk ->
                val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                if (tts.speak(chunk, mode, null, "$activePrefix-$index") != TextToSpeech.SUCCESS) {
                    stop()
                    return appContext.getString(R.string.error_speak)
                }
                if (index < chunks.lastIndex && sentencePauseMillis > 0L) {
                    val pauseId = "sentence-pause-${UUID.randomUUID()}"
                    if (tts.playSilentUtterance(
                            sentencePauseMillis,
                            TextToSpeech.QUEUE_ADD,
                            pauseId
                        ) != TextToSpeech.SUCCESS
                    ) {
                        stop()
                        return appContext.getString(R.string.error_speak)
                    }
                }
            }
            null
        } catch (_: RuntimeException) {
            stop()
            appContext.getString(R.string.error_speak)
        }
    }

    fun stop() {
        activePrefix = ""
        finalUtterance = ""
        runCatching { engine?.stop() }
    }

    fun shutdown() {
        generation++
        stop()
        val previous = engine
        engine = null
        runCatching { previous?.shutdown() }
        offlineVoices = emptyList()
        isReady = false
    }

    private fun Voice.isInstalledOffline(): Boolean =
        !isNetworkConnectionRequired &&
            !features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
}
