package dev.jorgex.whspr

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicLong

/** Resultado de un dictado ya transcrito y filtrado. */
sealed class DictationResult {
    data class Text(val text: String) : DictationResult()

    /** El audio no contenía habla: no hay nada que insertar y no es un error. */
    object NoSpeech : DictationResult()

    /** No hay modelo instalado, o no superaba la validación SHA-256 y se ha borrado. */
    object InvalidModel : DictationResult()

    /** Fallo del motor o transcripción cancelada. */
    object Failed : DictationResult()
}

/**
 * Flujo completo de transcripción compartido por el IME y el RecognitionService:
 * valida el modelo por SHA-256, transcribe y filtra etiquetas no verbales.
 */
class LocalTranscriber(private val modelStore: ModelStore) {

    /** Token para poder cancelar una transcripción concreta con [cancel]. */
    fun newToken(): Long = NativeWhisper.newToken()

    fun cancel(token: Long) = NativeWhisper.cancel(token)

    /** Bloqueante: llamar fuera del hilo principal. [samples] es audio 16 kHz mono PCM16. */
    fun transcribe(samples: ShortArray, model: SpeechModel, language: String, token: Long): DictationResult {
        when (modelStore.validate(model)) {
            ModelValidity.Valid -> Unit
            ModelValidity.Missing, ModelValidity.Corrupt -> return DictationResult.InvalidModel
            ModelValidity.Unreadable -> return DictationResult.Failed
        }
        val raw = runCatching {
            NativeWhisper.transcribe(samples, modelStore.fileFor(model).absolutePath, language, token)
        }.getOrNull() ?: return DictationResult.Failed
        val text = stripNonVerbalTags(raw)
        return if (text.isBlank()) DictationResult.NoSpeech else DictationResult.Text(text)
    }
}

object NativeWhisper {
    private val available = runCatching {
        System.loadLibrary("whspr")
    }.isSuccess

    private val tokens = AtomicLong()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val releaseRunnable = Runnable { if (available) releaseNative() }

    fun newToken(): Long = tokens.incrementAndGet()

    fun transcribe(samples: ShortArray, modelPath: String, language: String, token: Long): String? {
        if (!available) return null
        mainHandler.removeCallbacks(releaseRunnable)
        try {
            return transcribeNative(samples, modelPath, language, token)?.toString(Charsets.UTF_8)?.trim()
        } finally {
            // El modelo cargado ocupa decenas o cientos de MB: se suelta tras un rato
            // sin dictar en vez de retenerlo mientras viva el proceso del teclado.
            mainHandler.removeCallbacks(releaseRunnable)
            mainHandler.postDelayed(releaseRunnable, IDLE_RELEASE_MS)
        }
    }

    fun cancel(token: Long) {
        if (available) cancelNative(token)
    }

    @JvmStatic
    private external fun transcribeNative(samples: ShortArray, modelPath: String, language: String, token: Long): ByteArray?

    @JvmStatic
    private external fun cancelNative(token: Long)

    @JvmStatic
    private external fun releaseNative()

    private const val IDLE_RELEASE_MS = 5 * 60_000L
}

private val NON_VERBAL_TAG_PATTERN = Regex("[\\[(][^\\[\\]()]+[\\])]")
private val NON_VERBAL_LABELS = setOf(
    "musica", "music",
    "aplausos", "applause",
    "risas", "laughter",
    "ruido", "noise",
    "silencio", "silence",
    "sonido", "sound",
    "suspiros", "sighs",
)
private val NON_VERBAL_SYMBOLS = Regex("[♪♫]")

/**
 * Elimina de [text] las etiquetas no verbales que Whisper emite cuando el audio no
 * tiene habla (p. ej. "[MÚSICA]", "(music)", "♪"). Lista blanca cerrada: solo se
 * elimina un token entre corchetes/paréntesis si su contenido, en minúsculas y sin
 * acentos, coincide exactamente con una etiqueta conocida; cualquier otro corchete o
 * paréntesis (con texto dictado real dentro) se deja intacto. Tras filtrar, normaliza
 * espacios repetidos y hace trim. También descarta los créditos de subtítulos que
 * Whisper alucina en audio sin habla.
 */
internal fun stripNonVerbalTags(text: String): String {
    val withoutTags = NON_VERBAL_TAG_PATTERN.replace(text) { match ->
        val inner = match.value.substring(1, match.value.length - 1)
        if (normalizeTagLabel(inner) in NON_VERBAL_LABELS) "" else match.value
    }
    val cleaned = withoutTags.replace(NON_VERBAL_SYMBOLS, "")
        .replace(Regex("\\s+"), " ")
        .trim()
    // Alucinación conocida de Whisper con silencio o ruido: créditos de subtítulos
    // ("Subtítulos realizados por la comunidad de Amara.org"). Nadie dicta eso.
    return if (cleaned.contains("amara.org", ignoreCase = true)) "" else cleaned
}

private fun normalizeTagLabel(label: String): String {
    return java.text.Normalizer.normalize(label.trim().lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
}
