package dev.jorgex.whspr

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

class AudioRecorder(private val context: Context) {
    @Volatile
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null
    @Volatile
    private var recording = false
    private val pcmLock = Any()
    private var pcm = ByteArrayOutputStream()

    /**
     * Nivel de voz normalizado (0f..1f) para animar la vista de grabación.
     * Se invoca EN EL HILO DE AUDIO (whspr-audio): el consumidor decide si
     * necesita saltar a otro hilo (p. ej. con Handler/post).
     */
    @Volatile
    var onLevel: ((Float) -> Unit)? = null

    fun hasPermission(): Boolean {
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(): Boolean {
        if (!hasPermission() || recording) return false

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferSize <= 0) return false

        synchronized(pcmLock) {
            pcm.reset()
        }
        audioRecord = runCatching {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(minBufferSize * 2)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setContext(context)
                    }
                }
                .build()
        }.getOrNull()

        val recorder = audioRecord ?: return false
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            audioRecord = null
            return false
        }

        runCatching { recorder.startRecording() }.getOrElse {
            recorder.release()
            audioRecord = null
            return false
        }
        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            recorder.release()
            audioRecord = null
            return false
        }

        recording = true
        worker = runCatching {
            thread(name = "whspr-audio") {
                val buffer = ByteArray(minBufferSize)
                while (recording && audioRecord === recorder) {
                    val read = runCatching { recorder.read(buffer, 0, buffer.size) }.getOrDefault(0)
                    if (!recording || audioRecord !== recorder) break
                    if (read > 0) {
                        synchronized(pcmLock) {
                            pcm.write(buffer, 0, read)
                            if (pcm.size() >= MAX_PCM_BYTES) {
                                recording = false
                            }
                        }
                        runCatching { onLevel?.invoke(rmsLevel(buffer, read)) }
                    } else {
                        recording = false
                    }
                }
            }
        }.getOrElse {
            recording = false
            audioRecord = null
            runCatching { recorder.release() }
            return false
        }

        return true
    }

    @Synchronized
    fun stop(): File? {
        val audioBytes = teardown()
        if (audioBytes == null || audioBytes.isEmpty()) return null

        return runCatching {
            val output = File.createTempFile("whspr-dictation-", ".wav", context.cacheDir)
            FileOutputStream(output).use { stream ->
                stream.write(wavHeader(audioBytes.size))
                stream.write(audioBytes)
            }
            output
        }.getOrNull()
    }

    @Synchronized
    fun discard() {
        teardown()
    }

    private fun teardown(): ByteArray? {
        if (!recording && audioRecord == null && worker == null) return null
        recording = false
        onLevel = null
        audioRecord?.let { recorder ->
            runCatching {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop()
                }
            }
        }
        runCatching {
            worker?.join(1_000)
        }.onFailure {
            if (it is InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        worker = null
        audioRecord?.let { recorder ->
            runCatching { recorder.release() }
        }
        audioRecord = null

        return synchronized(pcmLock) {
            val bytes = pcm.toByteArray()
            pcm = ByteArrayOutputStream()
            bytes
        }
    }

    /** RMS normalizado (0f..1f) de un buffer PCM16 mono little-endian; curva sqrt para sensibilidad visual. */
    private fun rmsLevel(buffer: ByteArray, length: Int): Float {
        val sampleCount = length / 2
        if (sampleCount <= 0) return 0f

        var sumSquares = 0L
        for (i in 0 until sampleCount) {
            val lo = buffer[i * 2].toInt() and 0xFF
            val hi = buffer[i * 2 + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toInt()
            sumSquares += (sample * sample).toLong()
        }

        val meanSquare: Double = sumSquares / (1.0 * sampleCount)
        val rms = kotlin.math.sqrt(meanSquare)
        val normalized = (rms / 32768.0).coerceIn(0.0, 1.0)
        return kotlin.math.sqrt(normalized).toFloat()
    }

    private fun wavHeader(pcmBytes: Int): ByteArray {
        val totalDataLen = pcmBytes + 36
        val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(totalDataLen)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1.toShort())
            putShort(CHANNELS.toShort())
            putInt(SAMPLE_RATE)
            putInt(byteRate)
            putShort((CHANNELS * BITS_PER_SAMPLE / 8).toShort())
            putShort(BITS_PER_SAMPLE.toShort())
            put("data".toByteArray())
            putInt(pcmBytes)
        }.array()
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
        private const val MAX_SECONDS = 60
        private const val MAX_PCM_BYTES = SAMPLE_RATE * CHANNELS * (BITS_PER_SAMPLE / 8) * MAX_SECONDS
    }
}
