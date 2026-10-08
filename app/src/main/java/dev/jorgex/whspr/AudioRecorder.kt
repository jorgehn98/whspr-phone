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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * @param endOnSilence si es true, la grabación termina sola (vía [onAutoStop]) cuando el
 *   hablante calla. Para clientes que no paran la escucha por sí mismos
 *   (RecognitionService); en el teclado manda el usuario y se deja en false.
 */
class AudioRecorder(private val context: Context, private val endOnSilence: Boolean = false) {
    @Volatile
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null
    @Volatile
    private var recording = false
    private val pcmLock = Any()
    private var pcm = ByteArrayOutputStream()
    @Volatile
    private var silence = SilenceDetector()

    /**
     * Nivel de voz normalizado (0f..1f) para animar la vista de grabación.
     * Se invoca EN EL HILO DE AUDIO (whspr-audio): el consumidor decide si
     * necesita saltar a otro hilo (p. ej. con Handler/post).
     */
    @Volatile
    var onLevel: ((Float) -> Unit)? = null

    /**
     * Se invoca EN EL HILO DE AUDIO (whspr-audio) cuando el propio recorder se
     * auto-detiene: al alcanzar MAX_PCM_BYTES (~60s), si falla la lectura del
     * micrófono o, con endOnSilence, cuando detecta el fin del habla. El consumidor decide si
     * necesita saltar a otro hilo (p. ej. con Handler/post), igual que [onLevel].
     * No se invoca en un stop()/discard() manual: solo ante el auto-stop interno.
     */
    @Volatile
    var onAutoStop: (() -> Unit)? = null

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
        val silence = SilenceDetector().also { silence = it }
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
                        var finished = false
                        synchronized(pcmLock) {
                            pcm.write(buffer, 0, read)
                            if (pcm.size() >= MAX_PCM_BYTES) {
                                recording = false
                                finished = true
                            }
                        }
                        val level = rmsLevel(buffer, read)
                        runCatching { onLevel?.invoke(level) }
                        val speechEnded = silence.shouldStop(level, read * 1000 / BYTES_PER_SECOND)
                        if (endOnSilence && speechEnded && !finished) {
                            recording = false
                            finished = true
                        }
                        // Fuera del lock: onAutoStop puede acabar llamando a stop(),
                        // que hace worker?.join(1_000) sobre este mismo hilo si no
                        // saltara a main thread primero (ver consumidor en el IME).
                        if (finished) {
                            runCatching { onAutoStop?.invoke() }
                        }
                    } else {
                        // Error de lectura (p. ej. otra app se queda el micro): la
                        // grabación ha terminado sola igual que al alcanzar el límite.
                        recording = false
                        runCatching { onAutoStop?.invoke() }
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

    /**
     * Para la grabación y devuelve sus muestras (16 kHz mono PCM16), o null si no hay
     * nada que transcribir: sin audio, o sin voz detectada. Whisper inventa frases
     * cuando recibe solo silencio, así que ese audio no llega nunca al modelo. El
     * audio vive solo en memoria: nunca se escribe en disco.
     */
    @Synchronized
    fun stop(): ShortArray? {
        val audioBytes = teardown()
        if (audioBytes == null || audioBytes.isEmpty() || !silence.heardSpeech) return null
        val samples = ShortArray(audioBytes.size / 2)
        ByteBuffer.wrap(audioBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        return samples
    }

    @Synchronized
    fun discard() {
        teardown()
    }

    private fun teardown(): ByteArray? {
        if (!recording && audioRecord == null && worker == null) return null
        recording = false
        onLevel = null
        onAutoStop = null
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

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
        private const val MAX_SECONDS = 60
        private const val BYTES_PER_SECOND = SAMPLE_RATE * CHANNELS * (BITS_PER_SAMPLE / 8)
        private const val MAX_PCM_BYTES = BYTES_PER_SECOND * MAX_SECONDS
    }
}

/**
 * Detecta si hay voz y cuándo termina a partir del nivel de cada bloque de audio (la misma escala
 * 0f..1f de [AudioRecorder.onLevel]). Un bloque cuenta como voz si destaca sobre el
 * ruido de fondo observado, así que se adapta a micrófonos y salas distintas. Pide
 * parar tras [END_SILENCE_MS] de silencio una vez oída voz, o si nadie habla en
 * [NO_SPEECH_MS]. Lógica pura, sin Android.
 */
internal class SilenceDetector {
    private var noiseFloor = Float.MAX_VALUE
    private var elapsedMs = 0
    private var speechRunMs = 0
    private var silentMs = 0
    @Volatile
    var heardSpeech = false
        private set

    fun shouldStop(level: Float, blockMs: Int): Boolean {
        elapsedMs += blockMs
        // El suelo de ruido sigue al mínimo reciente: baja de golpe y sube muy despacio,
        // para que un instante de silencio absoluto (arranque del micro) no lo fije a cero.
        if (elapsedMs > WARMUP_MS) noiseFloor = minOf(noiseFloor * NOISE_FLOOR_RISE, level)
        if (level > maxOf(MIN_SPEECH_LEVEL, noiseFloor * SPEECH_OVER_NOISE)) {
            speechRunMs += blockMs
            // Un chasquido suelto no es habla: hace falta un tramo sostenido.
            if (speechRunMs >= MIN_SPEECH_MS) heardSpeech = true
            silentMs = 0
        } else {
            speechRunMs = 0
            silentMs += blockMs
        }
        return if (heardSpeech) silentMs >= END_SILENCE_MS else elapsedMs >= NO_SPEECH_MS
    }

    private companion object {
        const val WARMUP_MS = 200
        const val NOISE_FLOOR_RISE = 1.003f
        const val MIN_SPEECH_LEVEL = 0.05f
        const val SPEECH_OVER_NOISE = 1.3f
        const val MIN_SPEECH_MS = 120
        const val END_SILENCE_MS = 1_500
        const val NO_SPEECH_MS = 8_000
    }
}
