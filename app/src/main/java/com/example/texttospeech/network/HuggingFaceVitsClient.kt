package com.example.texttospeech.network

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Client for the public Gradio 3.7 VITS Space used by the three preset voices.
 *
 * The Space does not expose a conventional REST audio endpoint. Gradio first asks for a
 * session hash over WebSocket, places the request in a queue, asks for the input data and
 * finally returns a base64 WAV data URI. Keeping this protocol in its own class prevents
 * the custom GET server contract from becoming coupled to a third-party queue protocol.
 */
class HuggingFaceVitsClient(context: Context) {
    private val cacheDir = context.applicationContext.cacheDir
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()
    private val activeSockets = ConcurrentHashMap.newKeySet<WebSocket>()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val webSocketClient = httpClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var cachedConfig: SpaceConfig? = null

    data class Parameters(
        val text: String,
        val speaker: String,
        val noise: Float,
        val length: Float
    )

    enum class Stage {
        CONNECTING,
        WAITING,
        SYNTHESIZING
    }

    class QueueFull : IOException("The public Gradio queue is full")
    class InvalidResponse(message: String = "Invalid Gradio response") : IOException(message)
    class SynthesisFailure(message: String) : IOException(message)
    class AudioTooLarge : IOException("Audio exceeds 20 MiB")

    fun cancelActive() {
        activeCalls.forEach { it.cancel() }
        activeSockets.forEach { it.cancel() }
    }

    suspend fun synthesize(
        parameters: Parameters,
        onStage: (Stage) -> Unit = {}
    ): File {
        require(parameters.text.isNotBlank()) { "Text must not be blank" }
        currentCoroutineContext().ensureActive()
        onStage(Stage.CONNECTING)

        val config = resolveConfig()
        val speakerIndex = config.speakerIndexes[parameters.speaker]
            ?: DEFAULT_SPEAKER_INDEXES[parameters.speaker]
            ?: throw InvalidResponse("Speaker is not available")

        val audioDataUri = try {
            withTimeout(QUEUE_TIMEOUT_MILLIS) {
                awaitQueueResult(config.fnIndex, speakerIndex, parameters, onStage)
            }
        } catch (_: TimeoutCancellationException) {
            throw SocketTimeoutException("Public Gradio synthesis timed out")
        }

        return writeAudioDataUri(audioDataUri)
    }

    private suspend fun resolveConfig(): SpaceConfig {
        cachedConfig?.let { return it }
        return try {
            fetchConfig().also { cachedConfig = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The current known indices let synthesis continue when only the HTML/config request
            // is filtered. The queue response still validates whether those indices remain valid.
            DEFAULT_CONFIG
        }
    }

    private suspend fun fetchConfig(): SpaceConfig = withContext(Dispatchers.IO) {
        val call = httpClient.newCall(
            Request.Builder()
                .url(SPACE_ORIGIN)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
        )
        activeCalls.add(call)
        try {
            currentCoroutineContext().ensureActive()
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Space config returned HTTP ${response.code}")
                }
                val body = response.body ?: throw InvalidResponse("Missing Space config")
                val declaredLength = body.contentLength()
                if (declaredLength > MAX_CONFIG_BYTES) {
                    throw InvalidResponse("Space config is too large")
                }
                val bytes = body.bytes()
                if (bytes.size > MAX_CONFIG_BYTES) {
                    throw InvalidResponse("Space config is too large")
                }
                parseConfig(bytes.toString(Charsets.UTF_8))
            }
        } finally {
            activeCalls.remove(call)
        }
    }

    internal fun parseConfig(html: String): SpaceConfig {
        val markerStart = html.indexOf(CONFIG_MARKER)
        if (markerStart < 0) throw InvalidResponse("Missing Gradio config marker")
        val jsonStart = markerStart + CONFIG_MARKER.length
        val jsonEnd = html.indexOf(";</script>", jsonStart)
        if (jsonEnd < 0) throw InvalidResponse("Incomplete Gradio config")
        val root = JSONObject(html.substring(jsonStart, jsonEnd))
        if (!root.optBoolean("enable_queue", false)) {
            throw InvalidResponse("The public Space queue is disabled")
        }

        val components = root.getJSONArray("components")
        var textComponentId = -1
        var speakerComponentId = -1
        var speakerChoices: JSONArray? = null
        for (index in 0 until components.length()) {
            val component = components.getJSONObject(index)
            val props = component.optJSONObject("props") ?: continue
            when (props.optString("label")) {
                "Text (100 words limitation)" -> textComponentId = component.getInt("id")
                "Speaker" -> {
                    speakerComponentId = component.getInt("id")
                    speakerChoices = props.optJSONArray("choices")
                }
            }
        }
        if (textComponentId < 0 || speakerComponentId < 0 || speakerChoices == null) {
            throw InvalidResponse("Required Gradio components are missing")
        }

        val dependencies = root.getJSONArray("dependencies")
        var fnIndex = -1
        for (index in 0 until dependencies.length()) {
            val dependency = dependencies.getJSONObject(index)
            if (!dependency.optBoolean("backend_fn", false)) continue
            val inputs = dependency.optJSONArray("inputs") ?: continue
            if (inputs.containsInt(textComponentId) && inputs.containsInt(speakerComponentId)) {
                fnIndex = index
                break
            }
        }
        if (fnIndex < 0) throw InvalidResponse("Synthesis function is missing")

        val indexes = DEFAULT_SPEAKER_INDEXES.mapValues { (speaker, fallback) ->
            speakerChoices.indexOfSpeaker(speaker).takeIf { it >= 0 } ?: fallback
        }
        return SpaceConfig(fnIndex, indexes)
    }

    private suspend fun awaitQueueResult(
        fnIndex: Int,
        speakerIndex: Int,
        parameters: Parameters,
        onStage: (Stage) -> Unit
    ): String = suspendCancellableCoroutine { continuation ->
        val completed = AtomicBoolean(false)
        val sessionHash = UUID.randomUUID().toString().replace("-", "")

        fun finishFailure(webSocket: WebSocket, error: Throwable) {
            if (!completed.compareAndSet(false, true)) return
            activeSockets.remove(webSocket)
            webSocket.cancel()
            continuation.resumeWith(Result.failure(error))
        }

        fun finishSuccess(webSocket: WebSocket, value: String) {
            if (!completed.compareAndSet(false, true)) return
            activeSockets.remove(webSocket)
            webSocket.close(1000, "complete")
            continuation.resumeWith(Result.success(value))
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                activeSockets.add(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = JSONObject(text)
                    when (message.optString("msg")) {
                        "send_hash" -> {
                            val accepted = webSocket.send(buildHashPayload(sessionHash, fnIndex))
                            if (!accepted) finishFailure(webSocket, IOException("Queue connection closed"))
                        }

                        "send_data" -> {
                            onStageSafely(onStage, Stage.SYNTHESIZING)
                            val payload = buildDataPayload(
                                sessionHash = sessionHash,
                                fnIndex = fnIndex,
                                speakerIndex = speakerIndex,
                                parameters = parameters
                            )
                            if (!webSocket.send(payload)) {
                                finishFailure(webSocket, IOException("Queue connection closed"))
                            }
                        }

                        "estimation" -> onStageSafely(onStage, Stage.WAITING)
                        "process_starts", "process_generating" ->
                            onStageSafely(onStage, Stage.SYNTHESIZING)

                        "queue_full" -> finishFailure(webSocket, QueueFull())
                        "process_completed" -> {
                            if (!message.optBoolean("success", false)) {
                                val detail = message.optJSONObject("output")
                                    ?.optString("error")
                                    ?.take(200)
                                    .orEmpty()
                                finishFailure(
                                    webSocket,
                                    SynthesisFailure(detail.ifBlank { "Public VITS synthesis failed" })
                                )
                            } else {
                                finishSuccess(webSocket, extractAudioDataUri(message))
                            }
                        }
                    }
                } catch (error: Exception) {
                    finishFailure(webSocket, error)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (!completed.get()) {
                    finishFailure(webSocket, IOException("Queue closed before completion"))
                }
            }

            override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                val failure = if (error is IOException) error else IOException(error)
                finishFailure(webSocket, failure)
            }
        }

        val request = Request.Builder()
            .url(QUEUE_URL)
            .header("User-Agent", USER_AGENT)
            .header("Origin", SPACE_ORIGIN)
            .build()
        val webSocket = webSocketClient.newWebSocket(request, listener)
        activeSockets.add(webSocket)
        continuation.invokeOnCancellation {
            if (completed.compareAndSet(false, true)) {
                activeSockets.remove(webSocket)
                webSocket.cancel()
            }
        }
    }

    private suspend fun writeAudioDataUri(dataUri: String): File {
        var createdFile: File? = null
        try {
            return withContext(Dispatchers.IO) {
                val comma = dataUri.indexOf(',')
                if (comma <= 0 || !dataUri.substring(0, comma).contains(";base64")) {
                    throw InvalidResponse("Audio is not base64 encoded")
                }
                val metadata = dataUri.substring(0, comma).lowercase()
                val extension = when {
                    "audio/wav" in metadata || "audio/x-wav" in metadata -> "wav"
                    "audio/mpeg" in metadata || "audio/mp3" in metadata -> "mp3"
                    else -> throw InvalidResponse("Unsupported audio type")
                }
                val encoded = dataUri.substring(comma + 1)
                if (encoded.length > MAX_BASE64_CHARACTERS) throw AudioTooLarge()
                val bytes = try {
                    Base64.decode(encoded, Base64.DEFAULT)
                } catch (_: IllegalArgumentException) {
                    throw InvalidResponse("Malformed base64 audio")
                }
                if (bytes.size > MAX_AUDIO_BYTES) throw AudioTooLarge()
                validateAudio(bytes)

                File.createTempFile("hf-voice-", ".$extension", cacheDir).also { file ->
                    createdFile = file
                    file.outputStream().use { it.write(bytes) }
                }
            }
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { createdFile?.delete() }
            throw error
        }
    }

    private fun validateAudio(bytes: ByteArray) {
        if (bytes.size < 128) throw InvalidResponse("Audio is empty")
        val wav = bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
            bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WAVE"
        val mp3 = bytes.copyOfRange(0, 3).toString(Charsets.US_ASCII) == "ID3" ||
            ((bytes[0].toInt() and 0xff) == 0xff && (bytes[1].toInt() and 0xe0) == 0xe0)
        if (!wav && !mp3) throw InvalidResponse("Returned data is not WAV or MP3")
    }

    private fun onStageSafely(callback: (Stage) -> Unit, stage: Stage) {
        runCatching { callback(stage) }
    }

    internal data class SpaceConfig(
        val fnIndex: Int,
        val speakerIndexes: Map<String, Int>
    )

    companion object {
        const val SPACE_ORIGIN = "https://zomehwh-vits-uma-genshin-honkai.hf.space"
        const val QUEUE_URL = "wss://zomehwh-vits-uma-genshin-honkai.hf.space/queue/join"
        private const val USER_AGENT = "TextToSpeech-Android/1.3"
        private const val CONFIG_MARKER = "window.gradio_config = "
        private const val MAX_CONFIG_BYTES = 2 * 1024 * 1024
        private const val MAX_AUDIO_BYTES = 20 * 1024 * 1024
        private const val MAX_BASE64_CHARACTERS = 28 * 1024 * 1024
        private const val QUEUE_TIMEOUT_MILLIS = 120_000L
        private const val DEFAULT_NOISE_SCALE_W = 0.668

        internal val DEFAULT_SPEAKER_INDEXES = mapOf(
            "神里绫华" to 87,
            "可莉" to 103,
            "优菈" to 124
        )
        private val DEFAULT_CONFIG = SpaceConfig(0, DEFAULT_SPEAKER_INDEXES)

        internal fun buildHashPayload(sessionHash: String, fnIndex: Int): String =
            JSONObject()
                .put("session_hash", sessionHash)
                .put("fn_index", fnIndex)
                .toString()

        internal fun buildDataPayload(
            sessionHash: String,
            fnIndex: Int,
            speakerIndex: Int,
            parameters: Parameters
        ): String = JSONObject()
            .put(
                "data",
                JSONArray()
                    .put(parameters.text)
                    .put(0)
                    .put(speakerIndex)
                    .put(parameters.noise.toDouble())
                    .put(DEFAULT_NOISE_SCALE_W)
                    .put(parameters.length.toDouble())
            )
            .put("fn_index", fnIndex)
            .put("session_hash", sessionHash)
            .toString()

        internal fun extractAudioDataUri(message: JSONObject): String {
            val output = message.optJSONObject("output")
                ?: throw InvalidResponse("Missing synthesis output")
            val data = output.optJSONArray("data")
                ?: throw InvalidResponse("Missing synthesis data")
            val audioValue = data.opt(1)
            val dataUri = when (audioValue) {
                is String -> audioValue
                is JSONObject -> audioValue.optString("data")
                else -> ""
            }
            if (!dataUri.startsWith("data:audio/")) {
                throw InvalidResponse("Missing base64 audio")
            }
            return dataUri
        }
    }
}

private fun JSONArray.containsInt(value: Int): Boolean {
    for (index in 0 until length()) {
        if (optInt(index, Int.MIN_VALUE) == value) return true
    }
    return false
}

private fun JSONArray.indexOfSpeaker(speaker: String): Int {
    for (index in 0 until length()) {
        val choice = optString(index)
        if (choice == speaker || choice.startsWith("$speaker（") || choice.startsWith("$speaker(")) {
            return index
        }
    }
    return -1
}
