package dev.jorgex.whspr

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest

class ModelStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("whspr_models", Context.MODE_PRIVATE)

    fun fileFor(model: SpeechModel): File {
        return File(modelDir(), model.fileName)
    }

    fun isDownloaded(model: SpeechModel): Boolean {
        val file = fileFor(model)
        return file.exists() && file.length() >= model.minBytes
    }

    fun isReady(model: SpeechModel): Boolean {
        return isDownloaded(model) && hasExpectedSha256(model)
    }

    fun hasExpectedSha256(model: SpeechModel): Boolean {
        val file = fileFor(model)
        if (!isDownloaded(model)) return false
        val cacheKey = integrityCacheKey(model, file)
        if (prefs.getBoolean(cacheKey, false)) return true

        val digest = MessageDigest.getInstance("SHA-256")
        val digestReady = runCatching {
                file.inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        digest.update(buffer, 0, read)
                    }
                }
            }.isSuccess
        if (!digestReady) {
            return false
        }
        return digest.digest().joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }.let { actual ->
            (actual == model.sha256).also { verified ->
                if (verified) {
                    prefs.edit().putBoolean(cacheKey, true).apply()
                }
            }
        }
    }

    fun download(model: SpeechModel): Long {
        val existingFile = fileFor(model)
        if (existingFile.exists()) {
            if (!existingFile.delete()) return -1L
            clearIntegrityCache(model)
        }

        val request = DownloadManager.Request(Uri.parse(model.url))
            .setTitle(model.label)
            .setDescription(context.getString(R.string.download_description))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(
                context,
                "models",
                model.fileName,
            )

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return runCatching { manager.enqueue(request) }.getOrDefault(-1L)
    }

    fun delete(model: SpeechModel) {
        val file = fileFor(model)
        if (file.exists()) runCatching { file.delete() }
        clearIntegrityCache(model)
    }

    fun deleteUnready(model: SpeechModel) {
        val file = fileFor(model)
        if (file.exists() && (file.length() < model.minBytes || !hasExpectedSha256(model))) {
            runCatching { file.delete() }
            clearIntegrityCache(model)
        }
    }

    fun cancelDownload(downloadId: Long) {
        if (downloadId <= 0L) return
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        runCatching { manager.remove(downloadId) }
    }

    fun downloadStatus(downloadId: Long): ModelDownloadStatus {
        if (downloadId <= 0L) return ModelDownloadStatus.None

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = runCatching {
            manager.query(DownloadManager.Query().setFilterById(downloadId))
        }.getOrNull() ?: return ModelDownloadStatus.None
        cursor.use {
            if (!it.moveToFirst()) return ModelDownloadStatus.None
            val statusIndex = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
            if (statusIndex < 0) return ModelDownloadStatus.None
            return when (it.getInt(statusIndex)) {
                DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_PAUSED,
                DownloadManager.STATUS_RUNNING -> ModelDownloadStatus.Running
                DownloadManager.STATUS_SUCCESSFUL -> ModelDownloadStatus.Success
                DownloadManager.STATUS_FAILED -> ModelDownloadStatus.Failed
                else -> ModelDownloadStatus.None
            }
        }
    }

    /**
     * Estado del modelo seleccionado resolviendo la descarga pendiente: aplica las
     * limpiezas (registro pendiente, archivo no utilizable) una sola vez. [usable]
     * es la comprobación de uso elegida por el llamante —[isDownloaded] para grabar,
     * [isReady] (con SHA) para la UI— y es perezosa: no se evalúa durante una descarga
     * en curso. La decisión vive en [decideModelStatus] (pura, sin Android).
     */
    fun resolveStatus(settings: AppSettings, model: SpeechModel, usable: () -> Boolean): ModelStatus {
        val pending = settings.pendingModelId == model.id
        val status = if (pending) downloadStatus(settings.pendingDownloadId) else ModelDownloadStatus.None
        val decision = decideModelStatus(pending, status, usable)
        if (decision.clearPending) settings.clearPendingDownload()
        if (decision.deleteUnusable) deleteUnready(model)
        return decision.status
    }

    private fun modelDir(): File {
        return context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
    }

    private fun integrityCacheKey(model: SpeechModel, file: File): String {
        return "${model.id}:${model.sha256}:${file.length()}:${file.lastModified()}"
    }

    private fun clearIntegrityCache(model: SpeechModel) {
        val prefix = "${model.id}:"
        val keys = prefs.all.keys.filter { it.startsWith(prefix) }
        if (keys.isEmpty()) return
        prefs.edit().apply {
            keys.forEach(::remove)
        }.apply()
    }
}

enum class ModelDownloadStatus {
    None,
    Running,
    Success,
    Failed,
}

enum class ModelStatus { Downloading, Ready, Failed, Missing }

/** Qué reportar y qué efectos aplicar tras resolver el estado del modelo. */
data class ModelDecision(
    val status: ModelStatus,
    val clearPending: Boolean,
    val deleteUnusable: Boolean,
)

/**
 * Decisión pura del estado del modelo a partir de los hechos observados; no toca
 * disco ni prefs. Reúne la máquina de estados antes duplicada en MainActivity, el
 * IME y el RecognitionService.
 *
 * @param pending si hay una descarga registrada para este modelo
 * @param status estado de esa descarga (irrelevante si !pending)
 * @param usable comprobación de uso del llamante (perezosa: solo se evalúa cuando
 *   el modelo no está descargándose)
 */
fun decideModelStatus(
    pending: Boolean,
    status: ModelDownloadStatus,
    usable: () -> Boolean,
): ModelDecision {
    if (pending) {
        when (status) {
            ModelDownloadStatus.Running ->
                return ModelDecision(ModelStatus.Downloading, clearPending = false, deleteUnusable = false)
            ModelDownloadStatus.Failed ->
                return ModelDecision(ModelStatus.Failed, clearPending = true, deleteUnusable = true)
            ModelDownloadStatus.None ->
                return ModelDecision(ModelStatus.Missing, clearPending = true, deleteUnusable = true)
            ModelDownloadStatus.Success -> Unit // descarga completa: decide la comprobación de uso
        }
    }
    // Aquí: o no había descarga pendiente, o acababa de completarse (Success).
    val failStatus = if (pending) ModelStatus.Failed else ModelStatus.Missing
    return if (usable()) {
        ModelDecision(ModelStatus.Ready, clearPending = pending, deleteUnusable = false)
    } else {
        ModelDecision(failStatus, clearPending = pending, deleteUnusable = true)
    }
}
