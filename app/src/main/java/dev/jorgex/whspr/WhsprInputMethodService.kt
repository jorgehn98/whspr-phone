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
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.Toast

class WhsprInputMethodService : InputMethodService() {

    /** Máquina de estados del IME. Único punto de transición: sin flags booleanos sueltos. */
    private enum class DictationState { KEYBOARD, RECORDING, TRANSCRIBING }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val recorder by lazy { AudioRecorder(this) }
    private val settings by lazy { AppSettings(this) }
    private val modelStore by lazy { ModelStore(this) }
    private val transcriber by lazy { LocalTranscriber(modelStore) }

    private var state = DictationState.KEYBOARD
    private var isSecureInput = false
    private var isProseInput = false
    private var editorAction = EditorInfo.IME_ACTION_NONE
    private var noEnterAction = false
    private var inputSession = 0
    private var dictationModel: SpeechModel? = null
    private var transcriptionToken = 0L
    private var destroyed = false

    private var keyboardView: KeyboardView? = null
    private var dictationView: DictationView? = null

    override fun onEvaluateFullscreenMode(): Boolean {
        return false
    }

    override fun onCreateInputView(): View {
        val palette = WhsprColors.forContext(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(8), dp(4), dp(8))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(palette.backgroundTop, palette.background),
            )
            // Anti-tapjacking: si otra ventana se superpone al teclado (overlay
            // malicioso), Android marca los MotionEvent entrantes con
            // FLAG_WINDOW_IS_(PARTIALLY_)OBSCURED. Al activarlo en la vista raíz,
            // dispatchTouchEvent descarta esos eventos antes de que lleguen a
            // ninguna tecla hija (KeyboardView), sin necesidad de tocar las teclas
            // individuales.
            filterTouchesWhenObscured = true
        }

        // Teclado y panel de dictado comparten la MISMA altura (KeyboardView.heightDp):
        // alternar entre ellos (applyState) solo cambia qué vista es VISIBLE/GONE,
        // nunca el alto del contenedor del IME, para no dar un salto de layout a
        // la app de debajo.
        val keyboard = KeyboardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(KeyboardView.heightDp(this@WhsprInputMethodService)),
            )
            setLanguage(settings.keyboardLanguage)
            setPeriodSide(settings.periodSide)
            setShowNumberRow(settings.showNumberRow)
            onText = { text -> typeText(text) }
            onBackspace = {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                updateAutoShift()
            }
            onEnter = {
                pressEnter()
                updateAutoShift()
            }
            onLanguageToggle = { toggleKeyboardLanguage() }
            onSwitchKeyboard = {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }
            onMic = { toggleDictation() }
        }
        keyboardView = keyboard
        keyboard.setSecureInput(isSecureInput)

        val dictation = DictationView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(KeyboardView.heightDp(this@WhsprInputMethodService)),
            )
            visibility = View.GONE
            onFinish = { toggleDictation() }
            onCancel = { cancelDictation() }
        }
        dictationView = dictation

        root.addView(dictation)
        root.addView(keyboard)
        applyState()
        return root
    }

    private fun typeText(text: String) {
        val connection = currentInputConnection ?: return
        // Doble espacio tras una palabra: punto y espacio, como en el resto de teclados.
        if (text == " " && isProseInput) {
            val before = connection.getTextBeforeCursor(2, 0)
            if (before != null && before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
                connection.deleteSurroundingText(1, 0)
                connection.commitText(". ", 1)
                updateAutoShift()
                return
            }
        }
        connection.commitText(text, 1)
        updateAutoShift()
    }

    /** Pide al editor si el cursor está donde toca mayúscula y lo refleja en SHIFT. */
    private fun updateAutoShift() {
        val inputType = currentInputEditorInfo?.inputType ?: InputType.TYPE_NULL
        val caps = if (inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT) {
            currentInputConnection?.getCursorCapsMode(inputType) ?: 0
        } else {
            0
        }
        keyboardView?.setAutoShift(caps != 0)
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
        abandonDictation()
        isSecureInput = attribute?.let { isPasswordInput(it.inputType) } ?: false
        isProseInput = attribute?.let { isProseInput(it.inputType) } ?: false
        editorAction = attribute?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        noEnterAction = (attribute?.imeOptions?.and(EditorInfo.IME_FLAG_NO_ENTER_ACTION) ?: 0) != 0
        refreshKeyboardSettings()
        applyState()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!restarting) {
            val inputClass = (info?.inputType ?: 0) and InputType.TYPE_MASK_CLASS
            keyboardView?.resetForField(
                numeric = inputClass == InputType.TYPE_CLASS_NUMBER ||
                    inputClass == InputType.TYPE_CLASS_PHONE ||
                    inputClass == InputType.TYPE_CLASS_DATETIME,
            )
        }
        updateAutoShift()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (state == DictationState.KEYBOARD) updateAutoShift()
    }

    override fun onFinishInput() {
        keyboardView?.dismissLongPressPopup()
        abandonDictation()
        isSecureInput = false
        editorAction = EditorInfo.IME_ACTION_NONE
        noEnterAction = false
        keyboardView?.setSecureInput(false)
        applyState()
        super.onFinishInput()
    }

    override fun onWindowHidden() {
        // Con el teclado oculto no hay forma de ver ni parar la grabación: no se deja
        // el micrófono abierto. Una transcripción ya en curso sí puede terminar.
        if (state == DictationState.RECORDING) cancelDictation()
        super.onWindowHidden()
    }

    override fun onDestroy() {
        destroyed = true
        abandonDictation()
        keyboardView = null
        dictationView = null
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
            openSettings()
            return
        }
        if (modelStore.resolveStatus(settings, model) { modelStore.isDownloaded(model) } != ModelStatus.Ready) {
            showMessage(R.string.error_missing_model)
            openSettings()
            return
        }
        dictationModel = model
        recorder.onLevel = { level -> dictationView?.setLevel(level) }
        // Se invoca desde el hilo whspr-audio: saltar a main con post() antes de
        // tocar estado/finishDictation (que llama a recorder.stop() -> worker?.join,
        // y worker ES ese mismo hilo de audio: llamarlo síncronamente aquí bloquearía).
        val session = inputSession
        recorder.onAutoStop = {
            mainHandler.post {
                if (destroyed || session != inputSession) return@post
                if (state != DictationState.RECORDING) return@post
                finishDictation()
            }
        }
        if (!recorder.start()) {
            recorder.onLevel = null
            recorder.onAutoStop = null
            dictationModel = null
            showMessage(R.string.error_recording_failed)
            return
        }
        transitionTo(DictationState.RECORDING)
        applyState()
    }

    private fun finishDictation() {
        val model = dictationModel ?: ModelCatalog.byId(settings.modelId)
        val language = settings.language
        val session = inputSession
        val audioFile = recorder.stop()
        if (audioFile == null) {
            cancelDictation()
            showMessage(R.string.error_no_audio)
            return
        }
        val token = transcriber.newToken()
        transcriptionToken = token
        transitionTo(DictationState.TRANSCRIBING)
        applyState()

        Thread({
            val result = transcriber.transcribe(audioFile, model, language, token)
            mainHandler.post {
                if (destroyed || session != inputSession) return@post
                dictationModel = null
                transitionTo(DictationState.KEYBOARD)
                applyState()
                when (result) {
                    is DictationResult.Text -> commitTranscription(result.text)
                    // Dictado sin habla: no hay nada que pegar y no es un error.
                    DictationResult.NoSpeech -> Unit
                    DictationResult.InvalidModel -> showMessage(R.string.error_invalid_model)
                    DictationResult.Failed -> showMessage(R.string.error_transcriber_not_ready)
                }
            }
        }, "whspr-transcribe").start()
    }

    /** Cancelación pedida por el usuario: descarta audio o transcripción y vuelve al teclado. */
    private fun cancelDictation() {
        abandonDictation()
        applyState()
    }

    /**
     * Invalida el dictado en curso sin tocar las vistas: suelta el micrófono, aborta
     * la transcripción y avanza [inputSession] para que un resultado tardío se ignore.
     */
    private fun abandonDictation() {
        inputSession += 1
        if (state == DictationState.RECORDING) recorder.discard()
        if (state == DictationState.TRANSCRIBING) transcriber.cancel(transcriptionToken)
        dictationModel = null
        transitionTo(DictationState.KEYBOARD)
    }

    private fun transitionTo(newState: DictationState) {
        state = newState
    }

    private fun toggleKeyboardLanguage() {
        val next = if (settings.keyboardLanguage == KeyboardLanguage.ES) KeyboardLanguage.EN else KeyboardLanguage.ES
        settings.keyboardLanguage = next
        keyboardView?.setLanguage(next)
    }

    /**
     * Relee language/periodSide/showNumberRow de [settings] en cada onStartInput: si el
     * proceso del IME sigue vivo tras cambiar un ajuste en la app, el teclado debe
     * reflejarlo sin esperar a rotar. Cada setter de KeyboardView ya compara
     * contra su valor actual y solo re-renderiza si cambió, así que llamarlos siempre
     * aquí es barato y no tiene efectos secundarios cuando nada cambió.
     */
    private fun refreshKeyboardSettings() {
        val keyboard = keyboardView ?: return
        keyboard.setLanguage(settings.keyboardLanguage)
        keyboard.setPeriodSide(settings.periodSide)
        keyboard.setShowNumberRow(settings.showNumberRow)
        keyboard.setSecureInput(isSecureInput)
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
        // El texto dictado no debe quedar pegado a lo que ya había: si justo antes
        // del cursor hay una palabra o un signo de cierre, se separa con un espacio.
        val previous = connection.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        val needsSeparator = previous.isNotEmpty() &&
            (previous[0].isLetterOrDigit() || previous[0] in SEPARATED_AFTER)
        connection.commitText(if (needsSeparator) " $text" else text, 1)
        // .toString() fuerza comparación por contenido: algunos editores devuelven
        // SpannableString/SpannableStringBuilder, que no sobrescriben equals() y
        // comparan por identidad, provocando un espacio duplicado.
        val beforeCursor = connection.getTextBeforeCursor(1, 0)?.toString()
        if (beforeCursor != " ") {
            connection.commitText(" ", 1)
        }
        updateAutoShift()
    }

    /** Refleja [state] en las vistas: KEYBOARD/RECORDING/TRANSCRIBING intercambian teclado y panel. */
    private fun applyState() {
        val keyboard = keyboardView ?: return
        val dictation = dictationView ?: return
        val dictating = state != DictationState.KEYBOARD
        keyboard.visibility = if (dictating) View.GONE else View.VISIBLE
        dictation.visibility = if (dictating) View.VISIBLE else View.GONE
        when (state) {
            DictationState.KEYBOARD -> Unit
            DictationState.RECORDING -> dictation.setMode(VoiceWaveView.Mode.RECORDING)
            DictationState.TRANSCRIBING -> dictation.setMode(VoiceWaveView.Mode.TRANSCRIBING)
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

    /** Texto corriente: ni contraseñas ni URL/correo, donde el doble espacio no es un punto. */
    private fun isProseInput(inputType: Int): Boolean {
        if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        if (isPasswordInput(inputType)) return false
        return when (inputType and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> false
            else -> true
        }
    }

    private companion object {
        /** Signos tras los que el dictado se separa con un espacio. */
        const val SEPARATED_AFTER = ".,;:!?)]»"
    }
}
