package com.example.texttospeech.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class OnlineSpeechClient(context: Context) {
    private val cacheDir = context.applicationContext.cacheDir
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class Parameters(
        val text: String,
        val speaker: String,
        val format: String,
        val noise: Float,
        val length: Float
    )

    class HttpFailure(val code: Int) : IOException("HTTP $code")
    class InvalidAudio : IOException("Response is not MP3 or WAV")
    class AudioTooLarge : IOException("Audio exceeds 20 MiB")
    class InvalidAddress : IOException("Invalid server address")
    fun cancelActive() {
        activeCalls.forEach { it.cancel() }
    }

    suspend fun synthesize(server: String, parameters: Parameters): File {
        var createdFile: File? = null
        try {
            return withContext(Dispatchers.IO) {
                val base = server.trim().toHttpUrlOrNull() ?: throw InvalidAddress()
                if (base.scheme != "http" && base.scheme != "https") throw InvalidAddress()
                val url = base.newBuilder()
                    .setQueryParameter("text", parameters.text)
                    .setQueryParameter("speaker", parameters.speaker)
                    .setQueryParameter("format", parameters.format)
                    .setQueryParameter("noise", String.format(Locale.US, "%.3f", parameters.noise))
                    .setQueryParameter("length", String.format(Locale.US, "%.2f", parameters.length))
                    .build()
                val call = client.newCall(Request.Builder().url(url).get().build())
                val file = File.createTempFile("voice-", ".${parameters.format}", cacheDir)
                    .also { createdFile = it }
                activeCalls.add(call)
                try {
                    // Stop cancels the coroutine before cancelling registered calls.
                    // This also covers a call registered just after that cancellation.
                    currentCoroutineContext().ensureActive()
                    call.execute().use { response ->
                        if (!response.isSuccessful) throw HttpFailure(response.code)
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
                                    if (size > 20L * 1024L * 1024L) throw AudioTooLarge()
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                    }
                    val signature = file.inputStream().use { stream ->
                        ByteArray(12).also { stream.read(it) }
                    }
                    val wav = String(signature, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                        String(signature, 8, 4, Charsets.US_ASCII) == "WAVE"
                    val mp3 = String(signature, 0, 3, Charsets.US_ASCII) == "ID3" ||
                        ((signature[0].toInt() and 0xff) == 0xff &&
                            (signature[1].toInt() and 0xe0) == 0xe0)
                    if (file.length() < 128 || !(wav || mp3)) throw InvalidAudio()
                    file
                } finally {
                    activeCalls.remove(call)
                }
            }
        } catch (error: Throwable) {
            // withContext may discard a successful result when its caller is cancelled.
            // Cleanup must surround that dispatch boundary to avoid leaking the file.
            withContext(NonCancellable + Dispatchers.IO) { createdFile?.delete() }
            throw error
        }
    }
}
