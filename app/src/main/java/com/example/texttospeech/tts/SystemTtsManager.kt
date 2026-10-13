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
        fun onSpeaking()
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
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String) {
                        mainHandler.post {
                            if (utteranceId.startsWith(activePrefix) && activePrefix.isNotEmpty()) {
                                listener.onSpeaking()
                            }
                        }
                    }

                    override fun onDone(utteranceId: String) {
                        mainHandler.post {
                            if (utteranceId == finalUtterance && activePrefix.isNotEmpty()) {
                                activePrefix = ""
                                finalUtterance = ""
                                listener.onFinished()
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
                                tts.stop()
                                listener.onSpeechError(code)
                            }
                        }
                    }
                })

                offlineVoices = runCatching {
                    tts.voices.orEmpty().filter { it.isInstalledOffline() }
                        .sortedWith(compareBy({ it.locale.toLanguageTag() }, { it.name }))
                }.getOrDefault(emptyList())
                // A few engines omit getVoices(); probe their languages and keep only
                // entries for which the current engine actually chooses an offline voice.
                val fallbackLocales = if (offlineVoices.isEmpty()) {
                    val previousVoice = tts.voice
                    val found = runCatching {
                        tts.availableLanguages.orEmpty().filter { locale ->
                            tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE &&
                                tts.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE &&
                                tts.voice?.isInstalledOffline() == true
                        }
                    }.getOrDefault(emptyList())
                    if (previousVoice != null) tts.setVoice(previousVoice)
                    found
                } else emptyList()
                val locales = (offlineVoices.map { it.locale } + fallbackLocales)
                    .distinctBy { it.toLanguageTag() }
                    .sortedBy { it.getDisplayName(Locale.CHINA) }
                val languages = locales.map {
                    LanguageOption(it.toLanguageTag(), "${it.getDisplayName(Locale.CHINA)} (${it.toLanguageTag()})")
                }
                val source = tts.defaultEngine ?: appContext.getString(R.string.system_source)
                val voices = offlineVoices.map {
                    VoiceOption(
                        id = "system.${it.name}",
                        name = it.name,
                        language = it.locale.toLanguageTag(),
                        source = source,
                        online = false,
                        systemVoiceName = it.name
                    )
                }
                val chineseAvailable = locales.any { it.language == Locale.CHINESE.language }
                isReady = true
                listener.onReady(voices, languages, chineseAvailable)
            }
            }
        } catch (_: RuntimeException) {
            shutdown()
            listener.onInitializationFailed()
        }
    }

    /** Returns a localized error, or null if the utterances were successfully queued. */
    fun speak(chunks: List<String>, languageTag: String, voiceName: String?, rate: Float, pitch: Float): String? {
        val tts = engine ?: return appContext.getString(R.string.error_not_ready)
        if (!isReady) return appContext.getString(R.string.error_not_ready)
        val locale = Locale.forLanguageTag(languageTag)
        if (locale.language.isBlank() ||
            tts.isLanguageAvailable(locale) < TextToSpeech.LANG_AVAILABLE ||
            tts.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE
        ) return appContext.getString(R.string.error_language)

        val voice = if (voiceName == null) {
            offlineVoices.firstOrNull { it.locale.toLanguageTag() == languageTag }
                ?: offlineVoices.firstOrNull { it.locale.language == locale.language }
        } else {
            offlineVoices.firstOrNull { it.name == voiceName && it.locale.language == locale.language }
        }
        if (voice == null) {
            // Some engines do not enumerate voices. Only accept a verified offline default.
            if (voiceName != null || tts.voice?.isInstalledOffline() != true) {
                return appContext.getString(R.string.error_voice)
            }
        } else if (tts.setVoice(voice) != TextToSpeech.SUCCESS) {
            return appContext.getString(R.string.error_voice)
        }
        if (tts.setSpeechRate(rate) != TextToSpeech.SUCCESS ||
            tts.setPitch(pitch) != TextToSpeech.SUCCESS
        ) return appContext.getString(R.string.error_speak)

        if (chunks.isEmpty()) return appContext.getString(R.string.error_empty)
        stop()
        activePrefix = UUID.randomUUID().toString()
        finalUtterance = "$activePrefix-${chunks.lastIndex}"
        chunks.forEachIndexed { index, chunk ->
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            if (tts.speak(chunk, mode, null, "$activePrefix-$index") != TextToSpeech.SUCCESS) {
                stop()
                return appContext.getString(R.string.error_speak)
            }
        }
        return null
    }

    fun stop() {
        activePrefix = ""
        finalUtterance = ""
        engine?.stop()
    }

    fun shutdown() {
        generation++
        stop()
        engine?.shutdown()
        engine = null
        offlineVoices = emptyList()
        isReady = false
    }

    private fun Voice.isInstalledOffline(): Boolean =
        !isNetworkConnectionRequired &&
            !features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
}
