package com.example.texttospeech

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.texttospeech.network.OnlineSpeechClient
import com.example.texttospeech.playback.OfflineAudioExporter
import com.example.texttospeech.util.TextSegmenter
import kotlinx.coroutines.runBlocking
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
    fun downloadsAudioFromCustomSovitsCompatibleServer() = runBlocking {
        OneShotHttpServer(200, validWav(), "audio/wav").use { server ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val client = OnlineSpeechClient(context)
            val audio = client.synthesize(
                server.url,
                OnlineSpeechClient.Parameters(
                    text = "你好",
                    speaker = "神里绫华-v2",
                    format = "wav",
                    noise = 0.667f,
                    length = 1.2f
                )
            )

            try {
                assertTrue(audio.exists())
                assertEquals(128L, audio.length())
                assertEquals(1, server.requestCount.get())
            } finally {
                audio.delete()
            }
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
            val expectedPauseBytes = (22_050L * 350L / 1_000L) * 2L
            assertEquals(44L + 44_100L * 2L + expectedPauseBytes, wav.length())
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
        val sampleRate = 22_050
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

    private fun validWav(): ByteArray = ByteArray(128).also { bytes ->
        "RIFF".toByteArray(StandardCharsets.US_ASCII).copyInto(bytes, destinationOffset = 0)
        "WAVE".toByteArray(StandardCharsets.US_ASCII).copyInto(bytes, destinationOffset = 8)
    }

    private class OneShotHttpServer(
        private val statusCode: Int,
        private val body: ByteArray,
        private val contentType: String
    ) : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newSingleThreadExecutor()
        val requestCount = AtomicInteger(0)
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
