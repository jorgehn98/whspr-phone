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
        return isDownloaded(model) && hasExpectedSha1(model)
    }

    fun hasExpectedSha1(model: SpeechModel): Boolean {
        val file = fileFor(model)
        if (!isDownloaded(model)) return false
        val cacheKey = integrityCacheKey(model, file)
        if (prefs.getBoolean(cacheKey, false)) return true

        val digest = MessageDigest.getInstance("SHA-1")
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
            (actual == model.sha1).also { verified ->
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
        if (file.exists() && (file.length() < model.minBytes || !hasExpectedSha1(model))) {
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

    private fun modelDir(): File {
        return context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
    }

    private fun integrityCacheKey(model: SpeechModel, file: File): String {
        return "${model.id}:${model.sha1}:${file.length()}:${file.lastModified()}"
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
