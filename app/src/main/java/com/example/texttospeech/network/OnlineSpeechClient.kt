package com.example.texttospeech.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Native GPT-SoVITS /tts. The reference path identifies a file on the computer. */
class OnlineSpeechClient(context: Context) {
    private val cacheDir = context.applicationContext.cacheDir
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class Parameters(
        val text: String,
        val speedFactor: Float = 1f,
        val temperature: Float = 1f
    )

    class ServerFailure(val code: Int, val detail: String) : IOException("HTTP $code: $detail")
    class InvalidAudio : IOException("Response is not a WAV file")
    class AudioTooLarge : IOException("Audio exceeds 60 MiB")
    class InvalidAddress : IOException("Invalid server address")

    fun cancelActive() {
        activeCalls.forEach { it.cancel() }
    }

    suspend fun synthesize(server: String, parameters: Parameters): File {
        var createdFile: File? = null
        try {
            return withContext(Dispatchers.IO) {
                val url = buildRequestUrl(server, parameters)
                val request = Request.Builder().url(url).header("Accept", "audio/wav").get().build()
                val call = client.newCall(request)
                val file = File.createTempFile("sovits-", ".wav", cacheDir).also { createdFile = it }
                activeCalls.add(call)
                try {
                    currentCoroutineContext().ensureActive()
                    call.execute().use { response ->
                        if (!response.isSuccessful ||
                            response.header("Content-Type").orEmpty().contains("json", ignoreCase = true)
                        ) throw serverFailure(response)
                        val body = response.body ?: throw InvalidAudio()
                        body.byteStream().use { input ->
                            file.outputStream().use { output ->
                                val buffer = ByteArray(16 * 1024)
                                var size = 0L
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count == -1) break
                                    size += count
                                    if (size > MAX_AUDIO_BYTES) throw AudioTooLarge()
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                        val signature = file.inputStream().use { input ->
                            ByteArray(12).also { input.read(it) }
                        }
                        val wav = String(signature, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                            String(signature, 8, 4, Charsets.US_ASCII) == "WAVE"
                        if (file.length() < 128 || !wav) {
                            val errorText = file.inputStream().use { input ->
                                val bytes = ByteArray(MAX_ERROR_BYTES)
                                val count = input.read(bytes)
                                if (count > 0) String(bytes, 0, count, Charsets.UTF_8) else ""
                            }
                            val message = jsonErrorDetail(errorText)
                            if (message != null) throw ServerFailure(response.code, message)
                            throw InvalidAudio()
                        }
                        file
                    }
                } finally {
                    activeCalls.remove(call)
                }
            }
        } catch (error: Throwable) {
            // A cancelled dispatch boundary can discard a successful result too.
            withContext(NonCancellable + Dispatchers.IO) { createdFile?.delete() }
            throw error
        }
    }

    private fun serverFailure(response: Response): ServerFailure {
        val text = response.peekBody(MAX_ERROR_BYTES.toLong()).string()
        val detail = jsonErrorDetail(text) ?: response.message.ifBlank { "服务器未提供错误说明" }
        return ServerFailure(response.code, detail)
    }

    companion object {
        private const val MAX_AUDIO_BYTES = 60L * 1024L * 1024L
        private const val MAX_ERROR_BYTES = 16 * 1024
        private const val REFERENCE_AUDIO_PATH = "D:/AI-TTS/ayaka_v2/ref_ayaka.wav"
        private const val REFERENCE_PROMPT = "感觉又多了解了你一些"

        /** Accept either http://computer:9880 or http://computer:9880/tts. */
        fun buildRequestUrl(server: String, parameters: Parameters): HttpUrl {
            val base = server.trim().toHttpUrlOrNull() ?: throw InvalidAddress()
            if (base.scheme !in setOf("http", "https")) throw InvalidAddress()
            val path = base.encodedPath.trimEnd('/')
            val endpoint = if (path.substringAfterLast('/') == "tts") path else "$path/tts"
            return base.newBuilder()
                .encodedPath(endpoint)
                .query(null)
                .fragment(null)
                .addQueryParameter("text", parameters.text)
                .addQueryParameter("text_lang", "zh")
                .addQueryParameter("ref_audio_path", REFERENCE_AUDIO_PATH)
                .addQueryParameter("prompt_lang", "zh")
                .addQueryParameter("prompt_text", REFERENCE_PROMPT)
                .addQueryParameter("text_split_method", "cut5")
                .addQueryParameter("batch_size", "1")
                .addQueryParameter("media_type", "wav")
                .addQueryParameter("streaming_mode", "false")
                .addQueryParameter(
                    "speed_factor",
                    String.format(Locale.US, "%.2f", parameters.speedFactor.coerceIn(0.5f, 2f))
                )
                .addQueryParameter(
                    "temperature",
                    String.format(Locale.US, "%.3f", parameters.temperature.coerceIn(0.1f, 2f))
                )
                .build()
        }

        private fun jsonErrorDetail(text: String): String? = runCatching {
            val json = JSONObject(text)
            val message = json.optString("message").trim()
            val detail = json.optString("Exception").trim().ifBlank {
                json.opt("detail")?.toString()?.takeUnless { it == "null" }.orEmpty()
            }
            listOf(message, detail).filter(String::isNotBlank).distinct()
                .joinToString("；").take(500).takeIf(String::isNotBlank)
        }.getOrNull()
    }
}
