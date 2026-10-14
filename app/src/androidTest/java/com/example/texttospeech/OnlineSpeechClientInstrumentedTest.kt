package com.example.texttospeech

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.texttospeech.network.OnlineSpeechClient
import com.example.texttospeech.playback.AudioFilePlayer
import com.example.texttospeech.playback.OfflineAudioExporter
import com.example.texttospeech.util.TextSegmenter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class OnlineSpeechClientInstrumentedTest {
    @Test
    fun splitsTextAtEverySentenceForPauseAndHighlight() {
        val text = "第一句话。第二句话？第三句话！"
        val segments = TextSegmenter.splitWithRanges(text, 96)

        assertEquals(listOf("第一句话。", "第二句话？", "第三句话！"), segments.map { it.text })
        assertEquals(listOf(0, 5, 10), segments.map { it.start })
        assertEquals(listOf(5, 10, 15), segments.map { it.endExclusive })
    }

    @Test
    fun requestsNativeSovitsParametersAndPlaysReturnedWav() = runBlocking {
        OneShotHttpServer(200, pcmWave(frequency = 440.0), "audio/wav").use { server ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val client = OnlineSpeechClient(context)
            val audio = client.synthesize(
                server.url,
                OnlineSpeechClient.Parameters(
                    text = "你好",
                    speedFactor = 1.25f,
                    temperature = 1.467f
                )
            )

            try {
                assertTrue(audio.exists())
                assertEquals(64_044L, audio.length())
                assertEquals(1, server.requestCount.get())
                val requestedUrl = (server.url.trimEnd('/') + server.requestTarget).toHttpUrl()
                assertEquals("/tts", requestedUrl.encodedPath)
                assertEquals("你好", requestedUrl.queryParameter("text"))
                assertEquals("zh", requestedUrl.queryParameter("text_lang"))
                assertEquals("zh", requestedUrl.queryParameter("prompt_lang"))
                assertEquals("cut5", requestedUrl.queryParameter("text_split_method"))
                assertEquals("1", requestedUrl.queryParameter("batch_size"))
                assertEquals("wav", requestedUrl.queryParameter("media_type"))
                assertEquals("false", requestedUrl.queryParameter("streaming_mode"))
                assertEquals("1.25", requestedUrl.queryParameter("speed_factor"))
                assertEquals("D:/AI-TTS/ayaka_v2/ref_ayaka.wav", requestedUrl.queryParameter("ref_audio_path"))
                assertEquals("感觉又多了解了你一些", requestedUrl.queryParameter("prompt_text"))
                assertEquals("1.467", requestedUrl.queryParameter("temperature"))
                assertNull(requestedUrl.queryParameter("speaker"))
                assertNull(requestedUrl.queryParameter("format"))
                assertNull(requestedUrl.queryParameter("noise"))
                assertNull(requestedUrl.queryParameter("length"))
                withContext(Dispatchers.Main) { AudioFilePlayer().play(audio) }
            } finally {
                audio.delete()
            }
        }
    }

    @Test
    fun acceptsBaseAddressOrNativeEndpointWithoutDuplicateTts() {
        val parameters = OnlineSpeechClient.Parameters("你好 & 世界？")
        val root = OnlineSpeechClient.buildRequestUrl("http://192.168.1.2:9880", parameters)
        val endpoint = OnlineSpeechClient.buildRequestUrl(
            "http://192.168.1.2:9880/tts/?ref_audio_path=client.wav&speaker=old", parameters
        )
        assertEquals(root, endpoint)
        assertEquals("你好 & 世界？", root.queryParameter("text"))
        assertEquals("D:/AI-TTS/ayaka_v2/ref_ayaka.wav", endpoint.queryParameter("ref_audio_path"))
        assertNull(endpoint.queryParameter("speaker"))
    }

    @Test
    fun reportsNativeJsonErrorsInsteadOfPretendingPhoneIsOffline() = runBlocking {
        OneShotHttpServer(
            400,
            "{\"message\":\"tts failed\",\"Exception\":\"reference audio is missing\"}".toByteArray(),
            "application/json"
        ).use { server ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val failure = runCatching {
                OnlineSpeechClient(context).synthesize(server.url, OnlineSpeechClient.Parameters("你好"))
            }.exceptionOrNull()
            assertTrue(failure is OnlineSpeechClient.ServerFailure)
            failure as OnlineSpeechClient.ServerFailure
            assertEquals(400, failure.code)
            assertTrue(failure.detail.contains("reference audio is missing"))
        }
    }

    @Test
    fun rejectsJsonReturnedWithSuccessStatus() = runBlocking {
        OneShotHttpServer(200, "{\"message\":\"model not ready\"}".toByteArray(), "application/json").use { server ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val failure = runCatching {
                OnlineSpeechClient(context).synthesize(server.url, OnlineSpeechClient.Parameters("你好"))
            }.exceptionOrNull()
            assertTrue(failure is OnlineSpeechClient.ServerFailure)
            assertEquals("model not ready", (failure as OnlineSpeechClient.ServerFailure).detail)
        }
    }

    @Test
    fun keepsOriginalWavAndEncodesPlayableMp3() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "audio-export-test").apply { mkdirs() }
        val first = File(directory, "first.wav")
        val second = File(directory, "second.wav")
        val wav = File(directory, "combined.wav")
        val mp3 = File(directory, "combined.mp3")
        try {
            first.writeBytes(pcmWave(frequency = 440.0))
            second.writeBytes(pcmWave(frequency = 660.0))

            val result = OfflineAudioExporter().createMp3AndWav(
                wavParts = listOf(first, second),
                mp3Destination = mp3,
                wavDestination = wav,
                sentencePauseMillis = 350L
            )

            assertEquals(mp3.absolutePath, result.mp3.absolutePath)
            assertEquals(wav.absolutePath, result.wav.absolutePath)
            assertTrue(wav.length() > 44)
            val expectedPauseBytes = (32_000L * 350L / 1_000L) * 2L
            assertEquals(44L + 64_000L * 2L + expectedPauseBytes, wav.length())
            assertEquals("RIFF", wav.inputStream().use { input ->
                ByteArray(4).also { input.read(it) }.toString(StandardCharsets.US_ASCII)
            })
            assertTrue(mp3.length() > 128)
            val mp3Header = mp3.inputStream().use { input ->
                ByteArray(3).also { input.read(it) }
            }
            val hasId3 = mp3Header.toString(StandardCharsets.US_ASCII) == "ID3"
            val hasFrameSync = (mp3Header[0].toInt() and 0xff) == 0xff &&
                (mp3Header[1].toInt() and 0xe0) == 0xe0
            assertTrue(hasId3 || hasFrameSync)
        } finally {
            listOf(first, second, wav, mp3).forEach(File::delete)
            directory.delete()
        }
    }

    private fun pcmWave(frequency: Double): ByteArray {
        val sampleRate = 32_000
        val sampleCount = sampleRate
        val dataSize = sampleCount * 2
        return ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(StandardCharsets.US_ASCII))
            putInt(36 + dataSize)
            put("WAVE".toByteArray(StandardCharsets.US_ASCII))
            put("fmt ".toByteArray(StandardCharsets.US_ASCII))
            putInt(16)
            putShort(1.toShort())
            putShort(1.toShort())
            putInt(sampleRate)
            putInt(sampleRate * 2)
            putShort(2.toShort())
            putShort(16.toShort())
            put("data".toByteArray(StandardCharsets.US_ASCII))
            putInt(dataSize)
            repeat(sampleCount) { index ->
                val sample = (sin(2.0 * PI * frequency * index / sampleRate) * 10_000).toInt()
                putShort(sample.toShort())
            }
        }.array()
    }

    private class OneShotHttpServer(
        private val statusCode: Int,
        private val body: ByteArray,
        private val contentType: String
    ) : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newSingleThreadExecutor()
        val requestCount = AtomicInteger(0)
        @Volatile
        var requestTarget: String = ""
            private set
        val url = "http://127.0.0.1:${server.localPort}/"

        init {
            executor.execute {
                runCatching {
                    server.accept().use(::respond)
                }
            }
        }

        private fun respond(socket: Socket) {
            requestCount.incrementAndGet()
            socket.soTimeout = 5_000
            val reader = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
            requestTarget = reader.readLine().orEmpty().split(' ').getOrElse(1) { "" }
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val reason = if (statusCode in 200..299) "OK" else "Service Unavailable"
            val headers = buildString {
                append("HTTP/1.1 $statusCode $reason\r\n")
                append("Content-Type: $contentType\r\n")
                append("Content-Length: ${body.size}\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(StandardCharsets.US_ASCII)
            socket.getOutputStream().use { output ->
                output.write(headers)
                output.write(body)
                output.flush()
            }
        }

        override fun close() {
            runCatching { server.close() }
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
    }
}
