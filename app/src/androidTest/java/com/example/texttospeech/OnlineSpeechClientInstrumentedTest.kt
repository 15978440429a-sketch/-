package com.example.texttospeech

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.texttospeech.network.HuggingFaceVitsClient
import com.example.texttospeech.network.OnlineSpeechClient
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class OnlineSpeechClientInstrumentedTest {
    @Test
    fun buildsGradioQueuePayloadForAyaka() {
        val payload = JSONObject(
            HuggingFaceVitsClient.buildDataPayload(
                sessionHash = "session-1",
                fnIndex = 0,
                speakerIndex = 87,
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
        assertEquals(0, data.getInt(1))
        assertEquals(87, data.getInt(2))
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
        assertEquals(1, config.speakerIndexes["神里绫华"])
        assertEquals(2, config.speakerIndexes["可莉"])
        assertEquals(3, config.speakerIndexes["优菈"])
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
