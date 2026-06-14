package dev.jorgex.whspr

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.AttributionSource
import android.content.Context
import android.content.ContextParams
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.ModelDownloadListener
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

@SuppressLint("UseRequiresApi")
class WhsprRecognitionService : RecognitionService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val settings by lazy { AppSettings(this) }
    private val modelStore by lazy { ModelStore(this) }
    private val transcriber = LocalTranscriber()
    private var recorder: AudioRecorder? = null
    private var currentCallback: Callback? = null
    private var currentLanguage = AppSettings.LANGUAGE_SPANISH
    private var currentModelId: String? = null
    private var processing = false
    private var recognitionSession = 0
    private val timeoutRunnable = Runnable {
        synchronized(lock) {
            currentCallback
        }?.let(::finishListening)
    }

    override fun onStartListening(recognizerIntent: android.content.Intent?, listener: Callback) {
        var busy = false
        synchronized(lock) {
            if (currentCallback != null || processing) {
                busy = true
            } else {
                recognitionSession += 1
                currentCallback = listener
                currentLanguage = languageFor(recognizerIntent)
            }
        }
        if (busy) {
            notifyClient {
                listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            }
            return
        }

        val sessionRecorder = AudioRecorder(recordingContext(listener))
        recorder = sessionRecorder
        val model = ModelCatalog.byId(settings.modelId)
        synchronized(lock) {
            currentModelId = model.id
        }
        if (!sessionRecorder.hasPermission()) {
            fail(listener, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            return
        }
        if (!modelReady(model)) {
            fail(listener, SpeechRecognizer.ERROR_CLIENT)
            return
        }
        if (!sessionRecorder.start()) {
            fail(listener, SpeechRecognizer.ERROR_AUDIO)
            return
        }

        val clientReady = notifyClient {
            listener.readyForSpeech(Bundle.EMPTY)
            listener.beginningOfSpeech()
        }
        if (!clientReady) {
            onCancel(listener)
            return
        }
        mainHandler.postDelayed(timeoutRunnable, MAX_RECOGNITION_MS)
    }

    override fun onStopListening(listener: Callback) {
        finishListening(listener)
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCheckRecognitionSupport(
        recognizerIntent: Intent,
        supportCallback: SupportCallback,
    ) {
        reportRecognitionSupport(supportCallback)
    }

    @TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onCheckRecognitionSupport(
        recognizerIntent: Intent,
        attributionSource: AttributionSource,
        supportCallback: SupportCallback,
    ) {
        reportRecognitionSupport(supportCallback)
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private fun reportRecognitionSupport(supportCallback: SupportCallback) {
        val model = ModelCatalog.byId(settings.modelId)
        if (!modelStore.isReady(model)) {
            notifyClient {
                supportCallback.onError(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
            }
            return
        }

        val support = RecognitionSupport.Builder()
            .addInstalledOnDeviceLanguage("es-ES")
            .addInstalledOnDeviceLanguage(AppSettings.LANGUAGE_SPANISH)
            .build()
        notifyClient {
            supportCallback.onSupportResult(support)
        }
    }

    @TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onTriggerModelDownload(
        recognizerIntent: Intent,
        attributionSource: AttributionSource,
    ) {
        scheduleModelDownload()
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    override fun onTriggerModelDownload(recognizerIntent: Intent) {
        scheduleModelDownload()
    }

    @TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onTriggerModelDownload(
        recognizerIntent: Intent,
        attributionSource: AttributionSource,
        listener: ModelDownloadListener,
    ) {
        val model = ModelCatalog.byId(settings.modelId)
        notifyClient {
            if (modelStore.isReady(model)) {
                listener.onSuccess()
            } else if (scheduleModelDownload()) {
                listener.onScheduled()
            } else {
                listener.onError(SpeechRecognizer.ERROR_NETWORK)
            }
        }
    }

    private fun scheduleModelDownload(): Boolean {
        val model = ModelCatalog.byId(settings.modelId)
        if (modelStore.isReady(model)) return true
        if (settings.pendingModelId == model.id &&
            modelStore.downloadStatus(settings.pendingDownloadId) == ModelDownloadStatus.Running
        ) {
            return true
        }

        modelStore.deleteUnready(model)
        val downloadId = modelStore.download(model)
        if (downloadId <= 0L) return false
        settings.pendingModelId = model.id
        settings.pendingDownloadId = downloadId
        return true
    }

    override fun onCancel(listener: Callback) {
        var shouldCancel = false
        synchronized(lock) {
            if (currentCallback === listener) {
                recognitionSession += 1
                currentCallback = null
                currentModelId = null
                processing = false
                shouldCancel = true
            }
        }
        if (!shouldCancel) {
            return
        }
        mainHandler.removeCallbacks(timeoutRunnable)
        stopRecorder()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(timeoutRunnable)
        stopRecorder()
        synchronized(lock) {
            currentCallback = null
            currentModelId = null
            processing = false
            recognitionSession += 1
        }
        super.onDestroy()
    }

    private fun finishListening(listener: Callback) {
        val session = synchronized(lock) {
            recognitionSession
        }
        var shouldFinish = false
        synchronized(lock) {
            if (currentCallback === listener && !processing) {
                processing = true
                shouldFinish = true
            }
        }
        if (!shouldFinish) {
            return
        }
        mainHandler.removeCallbacks(timeoutRunnable)
        notifyClient {
            listener.endOfSpeech()
        }

        val audioFile = stopRecorder()
        if (audioFile == null) {
            complete(session) {
                listener.error(SpeechRecognizer.ERROR_NO_MATCH)
            }
            return
        }

        val sessionModelId = synchronized(lock) {
            currentModelId
        } ?: settings.modelId
        if (settings.modelId != sessionModelId) {
            runCatching { audioFile.delete() }
            fail(listener, SpeechRecognizer.ERROR_CLIENT)
            return
        }
        val model = ModelCatalog.byId(sessionModelId)
        val language = synchronized(lock) {
            currentLanguage
        }
        Thread({
            var modelOk = false
            var text: String? = null
            var modelChanged = false
            try {
                runCatching {
                    if (settings.modelId != sessionModelId) {
                        modelChanged = true
                        return@runCatching
                    }
                    modelOk = modelStore.hasExpectedSha1(model)
                    if (!modelOk) {
                        modelStore.delete(model)
                    } else if (settings.modelId == sessionModelId) {
                        text = transcriber.transcribe(audioFile, modelStore.fileFor(model), language)
                    }
                    if (settings.modelId != sessionModelId) {
                        modelChanged = true
                    }
                }
            } finally {
                runCatching { audioFile.delete() }
            }
            complete(session) {
                val finalText = text
                if (modelChanged) {
                    listener.error(SpeechRecognizer.ERROR_CLIENT)
                } else if (!modelOk) {
                    listener.error(SpeechRecognizer.ERROR_CLIENT)
                } else if (finalText == null) {
                    listener.error(SpeechRecognizer.ERROR_CLIENT)
                } else if (finalText.isBlank()) {
                    listener.error(SpeechRecognizer.ERROR_NO_MATCH)
                } else {
                    listener.results(Bundle().apply {
                        putStringArrayList(RecognizerIntent.EXTRA_RESULTS, arrayListOf(finalText))
                    })
                }
            }
        }, "whspr-recognition").start()
    }

    private fun modelReady(model: SpeechModel): Boolean {
        if (settings.pendingModelId == model.id) {
            when (modelStore.downloadStatus(settings.pendingDownloadId)) {
                ModelDownloadStatus.Running -> return false
                ModelDownloadStatus.Failed,
                ModelDownloadStatus.None -> {
                    modelStore.deleteUnready(model)
                    settings.clearPendingDownload()
                    return false
                }
                ModelDownloadStatus.Success -> {
                    settings.clearPendingDownload()
                    if (!modelStore.isDownloaded(model)) {
                        modelStore.delete(model)
                        return false
                    }
                }
            }
        }
        if (!modelStore.isDownloaded(model)) {
            modelStore.deleteUnready(model)
            return false
        }
        return true
    }

    private fun fail(listener: Callback, error: Int) {
        synchronized(lock) {
            if (currentCallback === listener) currentCallback = null
            currentModelId = null
            recognitionSession += 1
            processing = false
        }
        mainHandler.removeCallbacks(timeoutRunnable)
        stopRecorder()
        notifyClient {
            listener.error(error)
        }
    }

    private fun complete(session: Int, callback: () -> Unit) {
        mainHandler.post {
            val shouldReport = synchronized(lock) {
                session == recognitionSession
            }
            if (shouldReport) {
                notifyClient(callback)
            }
            synchronized(lock) {
                if (session == recognitionSession) {
                    currentCallback = null
                    currentModelId = null
                    recognitionSession += 1
                    processing = false
                }
            }
        }
    }

    private fun languageFor(recognizerIntent: android.content.Intent?): String {
        val requested = recognizerIntent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)
        return when {
            requested == null -> settings.language
            requested.startsWith(AppSettings.LANGUAGE_SPANISH, ignoreCase = true) -> AppSettings.LANGUAGE_SPANISH
            else -> AppSettings.LANGUAGE_AUTO
        }
    }

    private fun notifyClient(callback: () -> Unit): Boolean {
        return runCatching { callback() }.isSuccess
    }

    private fun stopRecorder(): java.io.File? {
        val activeRecorder = recorder ?: return null
        recorder = null
        return activeRecorder.stop()
    }

    @TargetApi(Build.VERSION_CODES.S)
    private fun callerAttributionContext(listener: Callback): Context {
        return createContext(
            ContextParams.Builder()
                .setNextAttributionSource(listener.callingAttributionSource)
                .build(),
        )
    }

    private fun recordingContext(listener: Callback): Context {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            callerAttributionContext(listener)
        } else {
            this
        }
    }

    companion object {
        private const val MAX_RECOGNITION_MS = 60_000L
    }
}

