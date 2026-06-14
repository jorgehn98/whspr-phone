package dev.jorgex.whspr

import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
    private var micButton: BubbleMicView? = null
    private var micLabel: TextView? = null

    override fun onEvaluateFullscreenMode(): Boolean {
        return false
    }

    override fun onCreateInputView(): View {
        val palette = WhsprColors.forContext(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(12), dp(20), dp(44))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(palette.backgroundTop, palette.background),
            )
        }

        val settingsButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_settings)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setColorFilter(palette.accent)
            val pad = dp(10)
            setPadding(pad, pad, pad, pad)
            val params = LinearLayout.LayoutParams(dp(44), dp(44))
            params.setMargins(dp(2), 0, dp(6), 0)
            layoutParams = params
            setOnClickListener { openSettings() }
        }

        val bubble = BubbleMicView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(170),
            )
            setOnClickListener { toggleDictation() }
        }
        micButton = bubble

        val label = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(palette.textPrimary)
            setPadding(0, dp(10), 0, dp(16))
        }
        micLabel = label

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(settingsButton)
            addView(key(getString(R.string.key_space)) {
                pressSpace()
            })
            addView(key(getString(R.string.key_delete)) {
                currentInputConnection?.deleteSurroundingTextInCodePoints(1, 0)
            })
            addView(key(getString(R.string.key_enter)) {
                pressEnter()
            })
        }

        root.addView(bubble)
        root.addView(label)
        root.addView(controls)
        updateMicButton()
        return root
    }

    private fun openSettings() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        if (isListening) recorder.discard()
        inputSession += 1
        stopListening()
        isSecureInput = attribute?.let { isPasswordInput(it.inputType) } ?: false
        editorAction = attribute?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        noEnterAction = (attribute?.imeOptions?.and(EditorInfo.IME_FLAG_NO_ENTER_ACTION) ?: 0) != 0
        updateMicButton()
    }

    override fun onFinishInput() {
        if (isListening) recorder.discard()
        inputSession += 1
        isSecureInput = false
        editorAction = EditorInfo.IME_ACTION_NONE
        noEnterAction = false
        stopListening()
        super.onFinishInput()
    }

    override fun onDestroy() {
        destroyed = true
        if (isListening) recorder.discard()
        inputSession += 1
        dictationModelId = null
        isProcessing = false
        micButton = null
        micLabel = null
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (micButton != null) {
            setInputView(onCreateInputView())
            updateMicButton()
        }
    }

    private fun key(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = "[$label]"
            isAllCaps = false
            isSingleLine = true
            typeface = Typeface.MONOSPACE
            minWidth = 0
            minHeight = 0
            setPadding(dp(3), 0, dp(3), 0)
            setTextColor(WhsprColors.forContext(context).accent)
            setAutoSizeTextTypeUniformWithConfiguration(8, 12, 1, TypedValue.COMPLEX_UNIT_SP)
            background = keyBackground()
            val params = LinearLayout.LayoutParams(0, dp(44), 1f)
            params.setMargins(dp(4), 0, dp(4), 0)
            layoutParams = params
            setOnClickListener { action() }
        }
    }

    private fun keyBackground(): Drawable {
        val palette = WhsprColors.forContext(this)
        return surfaceRippleBackground(palette, dp(5).toFloat(), dp(1), palette.accentMuted)
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
                    modelOk = modelStore.hasExpectedSha256(model)
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
        micButton?.setMode(
            when {
                isSecureInput -> BubbleMicView.Mode.DISABLED
                isProcessing -> BubbleMicView.Mode.PROCESSING
                isListening -> BubbleMicView.Mode.LISTENING
                else -> BubbleMicView.Mode.IDLE
            },
        )
        val labelText = if (isSecureInput) getString(R.string.mic_disabled_secure) else ""
        micLabel?.text = labelText
        micLabel?.visibility = if (labelText.isEmpty()) View.GONE else View.VISIBLE
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
