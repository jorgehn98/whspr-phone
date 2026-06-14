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

object ModelCatalog {
    val models = listOf(
        SpeechModel(
            id = "tiny-q5_1",
            label = "Tiny multilingüe",
            fileName = "ggml-tiny-q5_1.bin",
            sizeLabel = "31 MB",
            minBytes = 25L * 1024L * 1024L,
            sha256 = "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin",
        ),
        SpeechModel(
            id = "tiny",
            label = "Tiny multilingüe preciso",
            fileName = "ggml-tiny.bin",
            sizeLabel = "75 MB",
            minBytes = 70L * 1024L * 1024L,
            sha256 = "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin",
        ),
    )

    val default: SpeechModel = models.first()

    fun findById(id: String): SpeechModel? = models.firstOrNull { it.id == id }

    fun byId(id: String): SpeechModel = findById(id) ?: default
}
