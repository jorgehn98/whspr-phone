package dev.jorgex.whspr

data class SpeechModel(
    val id: String,
    val label: String,
    val fileName: String,
    val sizeLabel: String,
    val minBytes: Long,
    val sha1: String,
    val url: String,
)

object ModelCatalog {
    val models = listOf(
        SpeechModel(
            id = "tiny-q5_1",
            label = "Tiny multilingüe",
            fileName = "ggml-tiny-q5_1.bin",
            sizeLabel = "31 MB",
            minBytes = 25L * 1024L * 1024L,
            sha1 = "2827a03e495b1ed3048ef28a6a4620537db4ee51",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin",
        ),
        SpeechModel(
            id = "tiny",
            label = "Tiny multilingüe preciso",
            fileName = "ggml-tiny.bin",
            sizeLabel = "75 MB",
            minBytes = 70L * 1024L * 1024L,
            sha1 = "bd577a113a864445d4c299885e0cb97d4ba92b5f",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin",
        ),
    )

    val default: SpeechModel = models.first()

    fun findById(id: String): SpeechModel? = models.firstOrNull { it.id == id }

    fun byId(id: String): SpeechModel = findById(id) ?: default
}
