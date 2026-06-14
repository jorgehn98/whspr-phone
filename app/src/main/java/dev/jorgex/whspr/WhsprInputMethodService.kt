package dev.jorgex.whspr

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast

class WhsprInputMethodService : InputMethodService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recorder by lazy { AudioRecorder(this) }
    private val settings by lazy { AppSettings(this) }
    private val modelStore by lazy { ModelStore(this) }
    private val transcriber = LocalTranscriber()
    private var isListening = false
    private var isProcessing = false
    private var isSecureInput = false
    private var editorAction = EditorInfo.IME_ACTION_NONE
    private var noEnterAction = false
    private var inputSession = 0
    private var dictationModelId: String? = null
    private var destroyed = false
    private var micButton: Button? = null

    override fun onEvaluateFullscreenMode(): Boolean {
        return false
    }

    override fun onCreateInputView(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(16, 16, 16, 16)
            setBackgroundColor(0xFF111111.toInt())

            micButton = Button(context).apply {
                text = getString(R.string.mic_idle)
                textSize = 18f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                setOnClickListener { toggleDictation() }
            }
            updateMicButton()

            val controls = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER

                addView(key(getString(R.string.key_space)) {
                    pressSpace()
                })
                addView(key(getString(R.string.key_delete)) {
                    currentInputConnection?.deleteSurroundingTextInCodePoints(1, 0)
                })
                addView(key(getString(R.string.key_enter)) {
                    pressEnter()
                })
                addView(key(getString(R.string.key_next)) {
                    if (isListening || isProcessing) {
                        showMessage(R.string.error_stop_before_switching)
                    } else {
                        switchKeyboard()
                    }
                })
            }

            addView(micButton)
            addView(controls)
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        if (isListening) recorder.stop()
        inputSession += 1
        stopListening()
        isSecureInput = attribute?.let { isPasswordInput(it.inputType) } ?: false
        editorAction = attribute?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        noEnterAction = (attribute?.imeOptions?.and(EditorInfo.IME_FLAG_NO_ENTER_ACTION) ?: 0) != 0
        updateMicButton()
    }

    override fun onFinishInput() {
        if (isListening) recorder.stop()
        inputSession += 1
        isSecureInput = false
        editorAction = EditorInfo.IME_ACTION_NONE
        noEnterAction = false
        stopListening()
        super.onFinishInput()
    }

    override fun onDestroy() {
        destroyed = true
        if (isListening) recorder.stop()
        inputSession += 1
        dictationModelId = null
        isProcessing = false
        micButton = null
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun key(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            )
            setOnClickListener { action() }
        }
    }

    private fun toggleDictation() {
        if (isProcessing) return
        if (isListening) {
            finishDictation()
        } else {
            startListening()
        }
    }

    private fun startListening() {
        val model = ModelCatalog.byId(settings.modelId)
        if (isSecureInput) {
            showMessage(R.string.error_secure_input)
            return
        }
        if (!recorder.hasPermission()) {
            showMessage(R.string.error_missing_microphone_permission)
            return
        }
        if (settings.pendingModelId == model.id) {
            when (modelStore.downloadStatus(settings.pendingDownloadId)) {
                ModelDownloadStatus.Running -> {
                    showMessage(R.string.error_missing_model)
                    return
                }
                ModelDownloadStatus.Failed,
                ModelDownloadStatus.None -> {
                    modelStore.deleteUnready(model)
                    settings.clearPendingDownload()
                    showMessage(R.string.error_missing_model)
                    return
                }
                ModelDownloadStatus.Success -> {
                    settings.clearPendingDownload()
                    if (!modelStore.isDownloaded(model)) {
                        modelStore.delete(model)
                        showMessage(R.string.error_missing_model)
                        return
                    }
                }
            }
        }
        if (!modelStore.isDownloaded(model)) {
            modelStore.deleteUnready(model)
            showMessage(R.string.error_missing_model)
            return
        }
        dictationModelId = model.id
        if (!recorder.start()) {
            dictationModelId = null
            showMessage(R.string.error_recording_failed)
            return
        }
        isListening = true
        updateMicButton()
    }

    private fun stopListening() {
        isListening = false
        if (!isProcessing) {
            dictationModelId = null
        }
        updateMicButton()
    }

    private fun finishDictation() {
        val sessionModelId = dictationModelId ?: settings.modelId
        val audioFile = recorder.stop()
        stopListening()
        if (audioFile == null) {
            showMessage(R.string.error_no_audio)
            return
        }

        val session = inputSession
        if (settings.modelId != sessionModelId) {
            runCatching { audioFile.delete() }
            return
        }
        val model = ModelCatalog.byId(sessionModelId)
        val language = settings.language
        isProcessing = true
        updateMicButton()

        Thread({
            var modelOk = false
            var text: String? = null
            try {
                runCatching {
                    if (settings.modelId != sessionModelId) {
                        return@runCatching
                    }
                    modelOk = modelStore.hasExpectedSha1(model)
                    if (!modelOk) {
                        modelStore.delete(model)
                    } else if (settings.modelId == sessionModelId) {
                        text = transcriber.transcribe(audioFile, modelStore.fileFor(model), language)
                    }
                }
            } finally {
                runCatching { audioFile.delete() }
            }
            mainHandler.post {
                if (destroyed) return@post
                isProcessing = false
                updateMicButton()
                if (session != inputSession) return@post
                if (settings.modelId != sessionModelId) return@post
                val finalText = text
                if (!modelOk) {
                    showMessage(R.string.error_invalid_model)
                } else if (finalText == null) {
                    showMessage(R.string.error_transcriber_not_ready)
                } else if (finalText.isBlank()) {
                    showMessage(R.string.error_no_match)
                } else {
                    commitTranscription(finalText)
                }
            }
        }, "whspr-transcribe").start()
    }

    private fun showMessage(messageRes: Int) {
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
    }

    private fun switchKeyboard() {
        if (!switchToNextInputMethod(false)) {
            val inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager.showInputMethodPicker()
        }
    }

    private fun pressEnter() {
        if (!noEnterAction &&
            editorAction != EditorInfo.IME_ACTION_NONE &&
            editorAction != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            currentInputConnection?.performEditorAction(editorAction)
        } else {
            currentInputConnection?.commitText("\n", 1)
        }
    }

    private fun pressSpace() {
        val beforeCursor = currentInputConnection?.getTextBeforeCursor(1, 0)
        if (beforeCursor != " ") {
            currentInputConnection?.commitText(" ", 1)
        }
    }

    private fun commitTranscription(text: String) {
        currentInputConnection?.commitText(text, 1)
        pressSpace()
    }

    private fun updateMicButton() {
        micButton?.isEnabled = !isSecureInput && !isProcessing
        micButton?.text = when {
            isSecureInput -> getString(R.string.mic_disabled_secure)
            isProcessing -> getString(R.string.mic_processing)
            isListening -> getString(R.string.mic_listening)
            else -> getString(R.string.mic_idle)
        }
    }

    private fun isPasswordInput(inputType: Int): Boolean {
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputClass) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }
}
