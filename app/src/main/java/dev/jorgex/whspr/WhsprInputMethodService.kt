package dev.jorgex.whspr

import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.Toast

class WhsprInputMethodService : InputMethodService() {

    /** Máquina de estados del IME. Único punto de transición: sin flags booleanos sueltos. */
    private enum class DictationState { KEYBOARD, RECORDING, TRANSCRIBING }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val recorder by lazy { AudioRecorder(this) }
    private val settings by lazy { AppSettings(this) }
    private val modelStore by lazy { ModelStore(this) }
    private val transcriber = LocalTranscriber()

    private var state = DictationState.KEYBOARD
    private var isSecureInput = false
    private var editorAction = EditorInfo.IME_ACTION_NONE
    private var noEnterAction = false
    private var inputSession = 0
    private var dictationModelId: String? = null
    private var destroyed = false

    private var keyboardView: KeyboardView? = null
    private var voiceWaveView: VoiceWaveView? = null

    override fun onEvaluateFullscreenMode(): Boolean {
        return false
    }

    override fun onCreateInputView(): View {
        val palette = WhsprColors.forContext(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(44))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(palette.backgroundTop, palette.background),
            )
        }

        // Teclado y onda comparten la MISMA altura fija (KeyboardView.HEIGHT_DP):
        // alternar entre ellos (applyState) solo cambia qué vista es VISIBLE/GONE,
        // nunca el alto del contenedor del IME, para no dar un salto de layout a
        // la app de debajo.
        val keyboard = KeyboardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(KeyboardView.HEIGHT_DP),
            )
            setLanguage(settings.keyboardLanguage)
            setPeriodSide(settings.periodSide)
            onText = { text -> currentInputConnection?.commitText(text, 1) }
            onBackspace = { sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) }
            onEnter = { pressEnter() }
            onLanguageToggle = { toggleKeyboardLanguage() }
            onMic = { toggleDictation() }
        }
        keyboardView = keyboard

        val wave = VoiceWaveView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(KeyboardView.HEIGHT_DP),
            )
            visibility = View.GONE
            setOnClickListener { toggleDictation() }
        }
        voiceWaveView = wave

        root.addView(wave)
        root.addView(keyboard)
        applyState()
        return root
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

    private fun openSettings() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        keyboardView?.dismissLongPressPopup()
        if (state != DictationState.KEYBOARD) recorder.discard()
        inputSession += 1
        transitionTo(DictationState.KEYBOARD)
        isSecureInput = attribute?.let { isPasswordInput(it.inputType) } ?: false
        editorAction = attribute?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        noEnterAction = (attribute?.imeOptions?.and(EditorInfo.IME_FLAG_NO_ENTER_ACTION) ?: 0) != 0
        applyState()
    }

    override fun onFinishInput() {
        keyboardView?.dismissLongPressPopup()
        if (state != DictationState.KEYBOARD) recorder.discard()
        inputSession += 1
        isSecureInput = false
        editorAction = EditorInfo.IME_ACTION_NONE
        noEnterAction = false
        transitionTo(DictationState.KEYBOARD)
        super.onFinishInput()
    }

    override fun onDestroy() {
        destroyed = true
        if (state != DictationState.KEYBOARD) recorder.discard()
        inputSession += 1
        dictationModelId = null
        state = DictationState.KEYBOARD
        keyboardView = null
        voiceWaveView = null
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (keyboardView != null) {
            setInputView(onCreateInputView())
        }
    }

    // --- Máquina de estados del dictado ---

    private fun toggleDictation() {
        when (state) {
            DictationState.KEYBOARD -> startListening()
            DictationState.RECORDING -> finishDictation()
            DictationState.TRANSCRIBING -> Unit // idempotente: ignorar mientras transcribe
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
        if (modelStore.resolveStatus(settings, model) { modelStore.isDownloaded(model) } != ModelStatus.Ready) {
            showMessage(R.string.error_missing_model)
            openSettings()
            return
        }
        dictationModelId = model.id
        recorder.onLevel = { level -> voiceWaveView?.setLevel(level) }
        if (!recorder.start()) {
            recorder.onLevel = null
            dictationModelId = null
            showMessage(R.string.error_recording_failed)
            return
        }
        transitionTo(DictationState.RECORDING)
        applyState()
    }

    private fun finishDictation() {
        val sessionModelId = dictationModelId ?: settings.modelId
        val audioFile = recorder.stop()
        recorder.onLevel = null
        transitionTo(DictationState.TRANSCRIBING)
        applyState()
        if (audioFile == null) {
            dictationModelId = null
            showMessage(R.string.error_no_audio)
            transitionTo(DictationState.KEYBOARD)
            applyState()
            return
        }

        val session = inputSession
        // El usuario cambió de modelo a mitad de grabación: se descarta el
        // resultado en silencio (sin Toast) porque no fue un fallo, fue una
        // decisión suya de cambiar de modelo antes de terminar.
        if (settings.modelId != sessionModelId) {
            dictationModelId = null
            runCatching { audioFile.delete() }
            transitionTo(DictationState.KEYBOARD)
            applyState()
            return
        }
        val model = ModelCatalog.byId(sessionModelId)
        val language = settings.language

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
                dictationModelId = null
                if (session != inputSession) return@post
                transitionTo(DictationState.KEYBOARD)
                applyState()
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

    private fun transitionTo(newState: DictationState) {
        state = newState
    }

    private fun toggleKeyboardLanguage() {
        val next = if (settings.keyboardLanguage == KeyboardLanguage.ES) KeyboardLanguage.EN else KeyboardLanguage.ES
        settings.keyboardLanguage = next
        keyboardView?.setLanguage(next)
    }

    private fun showMessage(messageRes: Int) {
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
    }

    private fun commitTranscription(text: String) {
        val connection = currentInputConnection
        if (connection == null) {
            showMessage(R.string.error_commit_lost)
            return
        }
        connection.commitText(text, 1)
        val beforeCursor = connection.getTextBeforeCursor(1, 0)
        if (beforeCursor != " ") {
            connection.commitText(" ", 1)
        }
    }

    /** Refleja [state] en las vistas: KEYBOARD/RECORDING/TRANSCRIBING intercambian teclado y onda. */
    private fun applyState() {
        val keyboard = keyboardView ?: return
        val wave = voiceWaveView ?: return
        when (state) {
            DictationState.KEYBOARD -> {
                keyboard.visibility = View.VISIBLE
                wave.visibility = View.GONE
            }
            DictationState.RECORDING -> {
                keyboard.visibility = View.GONE
                wave.visibility = View.VISIBLE
                wave.setMode(VoiceWaveView.Mode.RECORDING)
            }
            DictationState.TRANSCRIBING -> {
                keyboard.visibility = View.GONE
                wave.visibility = View.VISIBLE
                wave.setMode(VoiceWaveView.Mode.TRANSCRIBING)
            }
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
