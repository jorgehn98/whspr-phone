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
import java.util.Locale

@SuppressLint("UseRequiresApi")
class WhsprRecognitionService : RecognitionService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val settings by lazy { AppSettings(this) }
    private val modelStore by lazy { ModelStore(this) }
    private val transcriber by lazy { LocalTranscriber(modelStore) }
    private var recorder: AudioRecorder? = null
    private var currentCallback: Callback? = null
    private var currentLanguage = AppSettings.LANGUAGE_SPANISH
    private var currentModel: SpeechModel? = null
    private var transcriptionToken = 0L
    private var processing = false
    private var recognitionSession = 0
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

        // Muchos clientes nunca llaman a stopListening: la grabación termina sola por
        // silencio, por límite de duración o por error, y aquí se cierra la escucha.
        val sessionRecorder = AudioRecorder(recordingContext(listener), endOnSilence = true)
        sessionRecorder.onAutoStop = { mainHandler.post { finishListening(listener) } }
        sessionRecorder.onLevel = { level -> notifyClient { listener.rmsChanged(level * MAX_RMS_DB) } }
        recorder = sessionRecorder
        val model = ModelCatalog.byId(settings.modelId)
        synchronized(lock) {
            currentModel = model
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
        }
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
            .apply {
                Languages.all.filter { it.code != Languages.AUTO }.forEach { addInstalledOnDeviceLanguage(it.code) }
            }
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
        var cancelledToken = 0L
        synchronized(lock) {
            if (currentCallback === listener) {
                recognitionSession += 1
                currentCallback = null
                currentModel = null
                processing = false
                shouldCancel = true
                cancelledToken = transcriptionToken
            }
        }
        if (!shouldCancel) {
            return
        }
        transcriber.cancel(cancelledToken)
        discardRecorder()
    }

    override fun onDestroy() {
        discardRecorder()
        synchronized(lock) {
            currentCallback = null
            currentModel = null
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
        val token = transcriber.newToken()
        val model: SpeechModel
        val language: String
        synchronized(lock) {
            transcriptionToken = token
            model = currentModel ?: ModelCatalog.byId(settings.modelId)
            language = currentLanguage
        }
        Thread({
            val result = transcriber.transcribe(audioFile, model, language, token)
            complete(session) {
                when (result) {
                    is DictationResult.Text ->
                        listener.results(Bundle().apply {
                            // Clave que leen los clientes de SpeechRecognizer en onResults.
                            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(result.text))
                        })
                    DictationResult.NoSpeech ->
                        listener.error(SpeechRecognizer.ERROR_NO_MATCH)
                    DictationResult.InvalidModel, DictationResult.Failed ->
                        listener.error(SpeechRecognizer.ERROR_CLIENT)
                }
            }
        }, "whspr-recognition").start()
    }

    private fun modelReady(model: SpeechModel): Boolean {
        return modelStore.resolveStatus(settings, model) { modelStore.isDownloaded(model) } == ModelStatus.Ready
    }

    private fun fail(listener: Callback, error: Int) {
        synchronized(lock) {
            if (currentCallback === listener) currentCallback = null
            currentModel = null
            recognitionSession += 1
            processing = false
        }
        discardRecorder()
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
                    currentModel = null
                    recognitionSession += 1
                    processing = false
                }
            }
        }
    }

    /** Idioma Whisper para la etiqueta BCP 47 que pide el cliente; auto si no se reconoce. */
    private fun languageFor(recognizerIntent: android.content.Intent?): String {
        val requested = recognizerIntent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE) ?: return settings.language
        val iso = Locale.forLanguageTag(requested.replace('_', '-')).language
        val code = WHISPER_CODE_FOR_ISO[iso] ?: iso
        return if (code != Languages.AUTO && Languages.isValid(code)) code else AppSettings.LANGUAGE_AUTO
    }

    private fun notifyClient(callback: () -> Unit): Boolean {
        return runCatching { callback() }.isSuccess
    }

    private fun stopRecorder(): java.io.File? {
        val activeRecorder = recorder ?: return null
        recorder = null
        return activeRecorder.stop()
    }

    private fun discardRecorder() {
        val activeRecorder = recorder ?: return
        recorder = null
        activeRecorder.discard()
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
        // rmsChanged espera decibelios; los clientes animan con valores en torno a 0..10.
        private const val MAX_RMS_DB = 10f

        // Códigos ISO que Whisper nombra de otra forma.
        private val WHISPER_CODE_FOR_ISO = mapOf(
            "nb" to "no",
            "iw" to "he",
            "in" to "id",
            "jv" to "jw",
            "fil" to "tl",
        )
    }
}

