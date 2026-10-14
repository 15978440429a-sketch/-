package com.example.texttospeech.playback

import de.sciss.jump3r.Main
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext

/** Combines PCM WAV parts and encodes a real MP3 in the app's private storage. */
class OfflineAudioExporter {
    class UnsupportedWave(message: String) : IOException(message)
    class EncodingFailed : IOException("MP3 encoding failed")

    suspend fun createMp3(wavParts: List<File>, destination: File): File =
        withContext(Dispatchers.IO) {
            require(wavParts.isNotEmpty()) { "At least one WAV part is required" }
            val infos = wavParts.map(::inspectWave)
            val format = infos.first()
            if (infos.any {
                    it.audioFormat != format.audioFormat ||
                        it.channels != format.channels ||
                        it.sampleRate != format.sampleRate ||
                        it.bitsPerSample != format.bitsPerSample
                }
            ) {
                throw UnsupportedWave("WAV parts use different audio formats")
            }
            if (format.audioFormat != PCM_FORMAT || format.bitsPerSample != 16) {
                throw UnsupportedWave("Only 16-bit PCM WAV audio can be saved as MP3")
            }

            val outputDirectory = checkNotNull(destination.parentFile)
            outputDirectory.mkdirs()
            val combinedWave = File.createTempFile("combined-", ".wav", outputDirectory)
            val temporaryMp3 = File.createTempFile("encoded-", ".mp3", outputDirectory)
            try {
                writeCombinedWave(wavParts, infos, combinedWave)
                coroutineContext.ensureActive()
                val result = Main().run(
                    arrayOf(
                        "--silent",
                        "-b",
                        "128",
                        combinedWave.absolutePath,
                        temporaryMp3.absolutePath
                    )
                )
                coroutineContext.ensureActive()
                if (result != 0 || !isMp3(temporaryMp3)) throw EncodingFailed()
                if (destination.exists() && !destination.delete()) throw EncodingFailed()
                if (!temporaryMp3.renameTo(destination)) {
                    temporaryMp3.copyTo(destination, overwrite = true)
                    temporaryMp3.delete()
                }
                destination
            } finally {
                combinedWave.delete()
                if (temporaryMp3 != destination) temporaryMp3.delete()
            }
        }

    private suspend fun writeCombinedWave(
        files: List<File>,
        infos: List<WaveInfo>,
        output: File
    ) {
        val totalDataLength = infos.sumOf { it.dataLength }
        if (totalDataLength <= 0 || totalDataLength > MAX_PCM_BYTES) {
            throw UnsupportedWave("Combined audio is empty or too large")
        }
        val first = infos.first()
        DataOutputStream(BufferedOutputStream(output.outputStream())).use { sink ->
            sink.writeAscii("RIFF")
            sink.writeLittleInt((36L + totalDataLength).toInt())
            sink.writeAscii("WAVE")
            sink.writeAscii("fmt ")
            sink.writeLittleInt(16)
            sink.writeLittleShort(first.audioFormat)
            sink.writeLittleShort(first.channels)
            sink.writeLittleInt(first.sampleRate)
            val blockAlign = first.channels * first.bitsPerSample / 8
            sink.writeLittleInt(first.sampleRate * blockAlign)
            sink.writeLittleShort(blockAlign)
            sink.writeLittleShort(first.bitsPerSample)
            sink.writeAscii("data")
            sink.writeLittleInt(totalDataLength.toInt())

            val buffer = ByteArray(32 * 1024)
            files.zip(infos).forEach { (file, info) ->
                RandomAccessFile(file, "r").use { source ->
                    source.seek(info.dataOffset)
                    var remaining = info.dataLength
                    while (remaining > 0) {
                        coroutineContext.ensureActive()
                        val count = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (count < 0) throw UnsupportedWave("WAV data ended unexpectedly")
                        sink.write(buffer, 0, count)
                        remaining -= count
                    }
                }
            }
        }
    }

    private fun inspectWave(file: File): WaveInfo = RandomAccessFile(file, "r").use { source ->
        if (source.length() < 44 || source.readAscii(4) != "RIFF") {
            throw UnsupportedWave("Audio is not a RIFF WAV file")
        }
        source.readLittleUnsignedInt()
        if (source.readAscii(4) != "WAVE") throw UnsupportedWave("Audio is not a WAVE file")

        var audioFormat = -1
        var channels = -1
        var sampleRate = -1
        var bitsPerSample = -1
        var dataOffset = -1L
        var dataLength = -1L
        while (source.filePointer + 8 <= source.length()) {
            val chunkId = source.readAscii(4)
            val chunkLength = source.readLittleUnsignedInt()
            val chunkStart = source.filePointer
            if (chunkLength > source.length() - chunkStart) {
                throw UnsupportedWave("WAV chunk is truncated")
            }
            when (chunkId) {
                "fmt " -> {
                    if (chunkLength < 16) throw UnsupportedWave("WAV format chunk is invalid")
                    audioFormat = source.readLittleUnsignedShort()
                    channels = source.readLittleUnsignedShort()
                    sampleRate = source.readLittleInt()
                    source.skipBytes(6)
                    bitsPerSample = source.readLittleUnsignedShort()
                }

                "data" -> {
                    dataOffset = chunkStart
                    dataLength = chunkLength
                }
            }
            val paddedLength = chunkLength + (chunkLength and 1L)
            source.seek(chunkStart + paddedLength)
            if (audioFormat > 0 && dataOffset >= 0) break
        }
        if (channels !in 1..2 || sampleRate !in 8_000..192_000 || dataLength <= 0) {
            throw UnsupportedWave("WAV format is unsupported")
        }
        WaveInfo(audioFormat, channels, sampleRate, bitsPerSample, dataOffset, dataLength)
    }

    private fun isMp3(file: File): Boolean {
        if (!file.isFile || file.length() < 128) return false
        val bytes = file.inputStream().use { input -> ByteArray(3).also { input.read(it) } }
        return bytes.toString(Charsets.US_ASCII) == "ID3" ||
            ((bytes[0].toInt() and 0xff) == 0xff && (bytes[1].toInt() and 0xe0) == 0xe0)
    }

    private data class WaveInfo(
        val audioFormat: Int,
        val channels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
        val dataOffset: Long,
        val dataLength: Long
    )

    companion object {
        private const val PCM_FORMAT = 1
        private const val MAX_PCM_BYTES = 512L * 1024L * 1024L
    }
}

private fun RandomAccessFile.readAscii(count: Int): String =
    ByteArray(count).also { readFully(it) }.toString(Charsets.US_ASCII)

private fun RandomAccessFile.readLittleUnsignedShort(): Int {
    val low = readUnsignedByte()
    val high = readUnsignedByte()
    return low or (high shl 8)
}

private fun RandomAccessFile.readLittleInt(): Int {
    val b0 = readUnsignedByte()
    val b1 = readUnsignedByte()
    val b2 = readUnsignedByte()
    val b3 = readUnsignedByte()
    return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
}

private fun RandomAccessFile.readLittleUnsignedInt(): Long =
    readLittleInt().toLong() and 0xffff_ffffL

private fun DataOutputStream.writeAscii(value: String) {
    write(value.toByteArray(Charsets.US_ASCII))
}

private fun DataOutputStream.writeLittleShort(value: Int) {
    writeByte(value and 0xff)
    writeByte((value ushr 8) and 0xff)
}

private fun DataOutputStream.writeLittleInt(value: Int) {
    writeByte(value and 0xff)
    writeByte((value ushr 8) and 0xff)
    writeByte((value ushr 16) and 0xff)
    writeByte((value ushr 24) and 0xff)
}
