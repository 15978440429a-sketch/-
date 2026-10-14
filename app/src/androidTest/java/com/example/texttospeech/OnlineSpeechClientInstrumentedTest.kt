package com.example.texttospeech

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.texttospeech.network.HuggingFaceVitsClient
import com.example.texttospeech.network.OnlineSpeechClient
import com.example.texttospeech.playback.OfflineAudioExporter
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
    fun buildsGradioQueuePayloadForAyaka() {
        val payload = JSONObject(
            HuggingFaceVitsClient.buildDataPayload(
                sessionHash = "session-1",
                fnIndex = 0,
                speakerValue = "神里绫华（龟龟）",
                parameters = HuggingFaceVitsClient.Parameters(
                    text = "你好",
                    speaker = "神里绫华",
                    noise = 0.6f,
                    length = 1.2f
                )
            )
        )

        assertEquals("session-1", payload.getString("session_hash"))
        assertEquals(0, payload.getInt("fn_index"))
        val data = payload.getJSONArray("data")
        assertEquals("你好", data.getString(0))
        assertEquals("中文", data.getString(1))
        assertEquals("神里绫华（龟龟）", data.getString(2))
        assertEquals(0.668, data.getDouble(4), 0.0001)
        assertEquals(1.2, data.getDouble(5), 0.0001)
    }

    @Test
    fun discoversAllThreeSpeakersFromSpaceConfig() {
        val configHtml = """
            <script>window.gradio_config = {
              "enable_queue": true,
              "components": [
                {"id": 6, "props": {"label": "Text (100 words limitation)"}},
                {"id": 13, "props": {"label": "Speaker", "choices": [
                  "其他角色", "神里绫华（龟龟）", "可莉", "优菈"
                ]}}
              ],
              "dependencies": [
                {"backend_fn": false, "inputs": []},
                {"backend_fn": true, "inputs": [6, 13], "outputs": [22, 23, 24]}
              ]
            };</script>
        """.trimIndent()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = HuggingFaceVitsClient(context).parseConfig(configHtml)

        assertEquals(1, config.fnIndex)
        assertEquals("神里绫华（龟龟）", config.speakerValues["神里绫华"])
        assertEquals("可莉", config.speakerValues["可莉"])
        assertEquals("优菈", config.speakerValues["优菈"])
    }

    @Test
    fun readsBase64AudioFromCompletedQueueMessage() {
        val expected = "data:audio/wav;base64,UklGRg=="
        val message = JSONObject()
            .put("msg", "process_completed")
            .put("success", true)
            .put(
                "output",
                JSONObject().put(
                    "data",
                    org.json.JSONArray().put("生成成功").put(expected).put("完成")
                )
            )

        assertEquals(expected, HuggingFaceVitsClient.extractAudioDataUri(message))
    }

    @Test
    fun retriesBackupServerAndAcceptsReturnedAudio() = runBlocking {
        OneShotHttpServer(503, ByteArray(0), "text/plain").use { primary ->
            OneShotHttpServer(200, validWav(), "audio/wav").use { backup ->
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val client = OnlineSpeechClient(context)
                val attempts = mutableListOf<Int>()

                val audio = client.synthesizeFirstAvailable(
                    listOf(primary.url, backup.url),
                    OnlineSpeechClient.Parameters(
                        text = "你好",
                        speaker = "神里绫华",
                        format = "wav",
                        noise = 0.667f,
                        length = 1.2f
                    )
                ) { attempt, _ -> attempts += attempt }

                try {
                    assertTrue(audio.exists())
                    assertEquals(128L, audio.length())
                    assertEquals(listOf(1, 2), attempts)
                    assertEquals(1, primary.requestCount.get())
                    assertEquals(1, backup.requestCount.get())
                } finally {
                    audio.delete()
                }
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
                wavDestination = wav
            )

            assertEquals(mp3.absolutePath, result.mp3.absolutePath)
            assertEquals(wav.absolutePath, result.wav.absolutePath)
            assertTrue(wav.length() > 44)
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
