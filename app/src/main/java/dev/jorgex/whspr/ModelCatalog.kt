package dev.jorgex.whspr

data class SpeechModel(
    val id: String,
    val label: String,
    val fileName: String,
    val sizeLabel: String,
    val minBytes: Long,
    val sha256: String,
    val url: String,
)

/**
 * Modelos Whisper multilingües cuantizados (q5_1) de whisper.cpp, de menor a mayor:
 * cada salto mejora la precisión y tarda más en transcribir.
 */
object ModelCatalog {
    val models = listOf(
        SpeechModel(
            id = "tiny-q5_1",
            label = "Rápido",
            fileName = "ggml-tiny-q5_1.bin",
            sizeLabel = "31 MB",
            minBytes = 25L * 1024L * 1024L,
            sha256 = "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin",
        ),
        SpeechModel(
            id = "base-q5_1",
            label = "Equilibrado",
            fileName = "ggml-base-q5_1.bin",
            sizeLabel = "57 MB",
            minBytes = 50L * 1024L * 1024L,
            sha256 = "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin",
        ),
        SpeechModel(
            id = "small-q5_1",
            label = "Preciso",
            fileName = "ggml-small-q5_1.bin",
            sizeLabel = "181 MB",
            minBytes = 170L * 1024L * 1024L,
            sha256 = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin",
        ),
    )

    val default: SpeechModel = models.first { it.id == "base-q5_1" }

    fun findById(id: String): SpeechModel? = models.firstOrNull { it.id == id }

    fun byId(id: String): SpeechModel = findById(id) ?: default
}
