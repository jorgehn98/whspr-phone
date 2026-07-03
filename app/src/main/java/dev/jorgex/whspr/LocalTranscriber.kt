package dev.jorgex.whspr

import java.io.File

class LocalTranscriber {
    fun transcribe(audioFile: File, modelFile: File, language: String): String? {
        if (!modelFile.exists()) return null
        if (!audioFile.exists()) return null
        return runCatching {
            NativeWhisper.transcribe(audioFile.absolutePath, modelFile.absolutePath, language)
        }.getOrNull()
    }
}

object NativeWhisper {
    private val available = runCatching {
        System.loadLibrary("whspr")
    }.isSuccess

    fun transcribe(audioPath: String, modelPath: String, language: String): String? {
        if (!available) return null
        return transcribeNative(audioPath, modelPath, language)?.trim()
    }

    @JvmStatic
    private external fun transcribeNative(audioPath: String, modelPath: String, language: String): String?
}

/**
 * Elimina de [text] las etiquetas no verbales que Whisper emite cuando el audio no
 * tiene habla (p. ej. "[MÚSICA]", "(music)", "♪"). Lista blanca cerrada: solo se
 * elimina un token entre corchetes/paréntesis si su contenido, en minúsculas y sin
 * acentos, coincide exactamente con una etiqueta conocida; cualquier otro corchete o
 * paréntesis (con texto dictado real dentro) se deja intacto. Tras filtrar, normaliza
 * espacios repetidos y hace trim.
 */
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

internal fun stripNonVerbalTags(text: String): String {
    val withoutTags = NON_VERBAL_TAG_PATTERN.replace(text) { match ->
        val inner = match.value.substring(1, match.value.length - 1)
        if (normalizeTagLabel(inner) in NON_VERBAL_LABELS) "" else match.value
    }
    return withoutTags.replace(NON_VERBAL_SYMBOLS, "")
        .replace(Regex("\\s+"), " ")
        .trim()
}

private fun normalizeTagLabel(label: String): String {
    return java.text.Normalizer.normalize(label.trim().lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
}
