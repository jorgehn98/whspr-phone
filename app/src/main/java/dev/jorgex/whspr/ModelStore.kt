package dev.jorgex.whspr

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest

/**
 * Modelos Whisper en disco. `DownloadManager` solo sabe escribir en almacenamiento
 * externo, que en Android 9 y 10 otras apps pueden modificar; por eso la descarga es
 * solo una zona de paso: se valida por SHA-256 mientras se copia al almacenamiento
 * interno ([fileFor]) y Whisper únicamente carga desde ahí.
 */
class ModelStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("whspr_models", Context.MODE_PRIVATE)

    /** Modelo instalado, en almacenamiento interno: el único archivo que carga Whisper. */
    fun fileFor(model: SpeechModel): File {
        return File(File(context.filesDir, "models"), model.fileName)
    }

    /** Comprobación barata (sin SHA) de que el modelo está instalado; apta para el hilo principal. */
    fun isInstalled(model: SpeechModel): Boolean {
        val file = fileFor(model)
        return file.isFile && file.length() >= model.minBytes
    }

    /**
     * Valida el modelo instalado contra su SHA-256 (con caché por tamaño y fecha, fiable
     * porque el archivo es interno). Si el contenido no coincide, lo borra. Un error de
     * lectura no borra nada: puede ser transitorio.
     */
    fun validate(model: SpeechModel): ModelValidity {
        val file = fileFor(model)
        if (!isInstalled(model)) return ModelValidity.Missing
        val cacheKey = integrityCacheKey(model, file)
        if (prefs.getBoolean(cacheKey, false)) return ModelValidity.Valid

        val actual = sha256(file, copyTo = null) ?: return ModelValidity.Unreadable
        if (actual != model.sha256) {
            delete(model)
            return ModelValidity.Corrupt
        }
        prefs.edit().putBoolean(cacheKey, true).apply()
        return ModelValidity.Valid
    }

    fun download(model: SpeechModel): Long {
        val target = downloadFileFor(model) ?: return -1L
        if (target.exists() && !target.delete()) return -1L

        val request = DownloadManager.Request(Uri.parse(model.url))
            .setTitle(context.getString(R.string.download_title, model.label))
            .setDescription(context.getString(R.string.download_description))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(context, DOWNLOAD_DIR, model.fileName)

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return runCatching { manager.enqueue(request) }.getOrDefault(-1L)
    }

    /** Borra el modelo instalado y cualquier resto de su descarga. */
    fun delete(model: SpeechModel) {
        runCatching { fileFor(model).delete() }
        deleteDownload(model)
        clearIntegrityCache(model)
    }

    fun deleteDownload(model: SpeechModel) {
        runCatching { downloadFileFor(model)?.delete() }
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

    /** Porcentaje descargado (0..100), o null si el sistema aún no conoce el tamaño total. */
    fun downloadPercent(downloadId: Long): Int? {
        if (downloadId <= 0L) return null
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = runCatching {
            manager.query(DownloadManager.Query().setFilterById(downloadId))
        }.getOrNull() ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            val doneIndex = it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalIndex = it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            if (doneIndex < 0 || totalIndex < 0) return null
            val total = it.getLong(totalIndex)
            if (total <= 0L) return null
            return (it.getLong(doneIndex) * 100 / total).toInt().coerceIn(0, 100)
        }
    }

    /**
     * Estado del modelo seleccionado, resolviendo la descarga pendiente: al completarse
     * la instala (valida y copia a interno) y limpia el registro. Calcula SHA-256 y
     * copia archivos grandes: llamar fuera del hilo principal. La decisión vive en
     * [decideModelStatus] (pura, sin Android).
     */
    fun resolveStatus(settings: AppSettings, model: SpeechModel): ModelStatus = synchronized(INSTALL_LOCK) {
        val pending = settings.pendingModelId == model.id
        val status = if (pending) downloadStatus(settings.pendingDownloadId) else ModelDownloadStatus.None
        val decision = decideModelStatus(pending, status) {
            if (pending) install(model) else validate(model) == ModelValidity.Valid
        }
        if (decision.clearPending) settings.clearPendingDownload()
        if (decision.deleteDownload) deleteDownload(model)
        decision.status
    }

    /**
     * Copia la descarga al almacenamiento interno calculando su SHA-256 en la misma
     * pasada; solo si coincide pasa a ser el modelo instalado.
     */
    private fun install(model: SpeechModel): Boolean {
        val source = downloadFileFor(model) ?: return false
        if (!source.isFile || source.length() < model.minBytes) return false
        val target = fileFor(model)
        val staging = File(target.parentFile, "${model.fileName}.tmp")
        val installed = runCatching {
            staging.parentFile?.mkdirs()
            sha256(source, copyTo = staging) == model.sha256 && staging.renameTo(target)
        }.getOrDefault(false)
        runCatching { staging.delete() }
        if (installed) {
            clearIntegrityCache(model)
            prefs.edit().putBoolean(integrityCacheKey(model, target), true).apply()
        }
        return installed
    }

    /** SHA-256 de [file] en hexadecimal, copiándolo a [copyTo] si se indica; null si falla la E/S. */
    private fun sha256(file: File, copyTo: File?): String? {
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                (copyTo?.outputStream()).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        digest.update(buffer, 0, read)
                        output?.write(buffer, 0, read)
                    }
                }
            }
            digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        }.getOrNull()
    }

    private fun downloadFileFor(model: SpeechModel): File? {
        return context.getExternalFilesDir(DOWNLOAD_DIR)?.let { File(it, model.fileName) }
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

    private companion object {
        const val DOWNLOAD_DIR = "downloads"

        // Varias pantallas o hilos pueden resolver el estado a la vez; la instalación
        // escribe un archivo de paso compartido y debe hacerla uno solo.
        val INSTALL_LOCK = Any()
    }
}

enum class ModelValidity { Valid, Missing, Corrupt, Unreadable }

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
    val deleteDownload: Boolean,
)

/**
 * Decisión pura del estado del modelo a partir de los hechos observados; no toca
 * disco ni prefs.
 *
 * @param pending si hay una descarga registrada para este modelo
 * @param status estado de esa descarga (irrelevante si !pending)
 * @param usable si el modelo queda utilizable: instalar la descarga recién completada
 *   o validar el ya instalado (perezosa: solo se evalúa cuando no está descargándose)
 */
fun decideModelStatus(
    pending: Boolean,
    status: ModelDownloadStatus,
    usable: () -> Boolean,
): ModelDecision {
    if (pending) {
        when (status) {
            ModelDownloadStatus.Running ->
                return ModelDecision(ModelStatus.Downloading, clearPending = false, deleteDownload = false)
            ModelDownloadStatus.Failed ->
                return ModelDecision(ModelStatus.Failed, clearPending = true, deleteDownload = true)
            ModelDownloadStatus.None ->
                return ModelDecision(ModelStatus.Missing, clearPending = true, deleteDownload = true)
            ModelDownloadStatus.Success -> Unit // descarga completa: decide la comprobación de uso
        }
    }
    // Aquí: o no había descarga pendiente, o acababa de completarse (Success).
    val failStatus = if (pending) ModelStatus.Failed else ModelStatus.Missing
    return if (usable()) {
        ModelDecision(ModelStatus.Ready, clearPending = pending, deleteDownload = pending)
    } else {
        ModelDecision(failStatus, clearPending = pending, deleteDownload = true)
    }
}
