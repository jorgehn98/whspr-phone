package dev.jorgex.whspr

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/**
 * Vista del teclado QWERTY completo: grid de [TextView] generado dinámicamente a partir de
 * [KeyboardLayout]. Sin lógica de negocio propia: solo notifica al IME vía callbacks.
 * Ver AGENTS.md / DESIGN.md — sin Compose, sin AndroidX, [TextView] en vez de [android.widget.Button].
 */
class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    /** Estado de mayúsculas: NONE (minúsculas), SHIFT (una letra) o CAPS_LOCK (fijo). */
    private enum class ShiftState { NONE, SHIFT, CAPS_LOCK }

    var onText: (String) -> Unit = {}
    var onBackspace: () -> Unit = {}
    var onEnter: () -> Unit = {}
    var onMic: () -> Unit = {}
    var onLanguageToggle: () -> Unit = {}

    private var language = KeyboardLanguage.ES
    private var layer = KeyboardLayer.LETTERS
    private var shiftState = ShiftState.NONE

    private val palette = WhsprColors.forContext(context)
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null
    private var lastShiftTapAt = 0L
    private var longPressPopup: PopupWindow? = null

    init {
        orientation = VERTICAL
        render()
    }

    /** Cambia el idioma de letras desde fuera (p.ej. tras GLOBE + selección) y re-renderiza. */
    fun setLanguage(newLanguage: KeyboardLanguage) {
        if (language == newLanguage) return
        language = newLanguage
        render()
    }

    override fun onDetachedFromWindow() {
        cancelRepeat()
        dismissLongPressPopup()
        super.onDetachedFromWindow()
    }

    private fun render() {
        removeAllViews()
        val keyboardLayout = KeyboardLayouts.layoutFor(language, layer)
        for (row in keyboardLayout.rows) {
            addView(buildRow(row))
        }
    }

    private fun buildRow(row: List<Key>): LinearLayout {
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            val params = LayoutParams(LayoutParams.MATCH_PARENT, context.dp(ROW_HEIGHT_DP))
            layoutParams = params
            for (key in row) {
                addView(buildKeyView(key))
            }
        }
    }

    private fun buildKeyView(key: Key): TextView {
        return TextView(context).apply {
            text = displayLabel(key)
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
            textSize = 18f
            setTextColor(keyTextColor(key))
            background = surfaceRippleBackground(
                palette,
                context.dp(6).toFloat(),
                context.dp(1),
                palette.surfaceStroke,
            )
            contentDescription = contentDescriptionFor(key)
            val params = LayoutParams(0, LayoutParams.MATCH_PARENT, key.weight)
            params.setMargins(context.dp(2), context.dp(2), context.dp(2), context.dp(2))
            layoutParams = params
            setOnClickListener { handleTap(key) }
            if (key.longPress.isNotEmpty()) {
                setOnLongClickListener {
                    showLongPressPopup(this, key)
                    true
                }
            }
            if (key.type == KeyType.BACKSPACE) {
                attachRepeat(this)
            }
        }
    }

    private fun handleTap(key: Key) {
        when (key.type) {
            KeyType.CHAR -> {
                onText(displayLabel(key))
                consumeShiftIfNeeded()
            }
            KeyType.SHIFT -> handleShiftTap()
            KeyType.BACKSPACE -> onBackspace()
            KeyType.LAYER_SYMBOLS -> setLayer(KeyboardLayer.SYMBOLS_1)
            KeyType.LAYER_ABC -> setLayer(KeyboardLayer.LETTERS)
            KeyType.LAYER_PAGE -> togglePage()
            KeyType.GLOBE -> onLanguageToggle()
            KeyType.SPACE -> onText(" ")
            KeyType.PERIOD -> onText(".")
            KeyType.MIC -> onMic()
            KeyType.ENTER -> onEnter()
        }
    }

    private fun handleShiftTap() {
        val now = System.currentTimeMillis()
        shiftState = when {
            shiftState == ShiftState.CAPS_LOCK -> ShiftState.NONE
            now - lastShiftTapAt <= DOUBLE_TAP_WINDOW_MS -> ShiftState.CAPS_LOCK
            shiftState == ShiftState.SHIFT -> ShiftState.NONE
            else -> ShiftState.SHIFT
        }
        lastShiftTapAt = now
        render()
    }

    private fun consumeShiftIfNeeded() {
        if (shiftState == ShiftState.SHIFT) {
            shiftState = ShiftState.NONE
            render()
        }
    }

    private fun setLayer(newLayer: KeyboardLayer) {
        layer = newLayer
        render()
    }

    private fun togglePage() {
        layer = if (layer == KeyboardLayer.SYMBOLS_1) KeyboardLayer.SYMBOLS_2 else KeyboardLayer.SYMBOLS_1
        render()
    }

    private fun displayLabel(key: Key): String {
        if (key.type != KeyType.CHAR) return key.label
        val upper = shiftState != ShiftState.NONE
        return if (upper) key.label.uppercase() else key.label
    }

    private fun keyTextColor(key: Key): Int {
        return when {
            key.type == KeyType.SHIFT && shiftState == ShiftState.CAPS_LOCK -> palette.accentBright
            key.type == KeyType.SHIFT && shiftState == ShiftState.SHIFT -> palette.accentBright
            else -> palette.textPrimary
        }
    }

    private fun contentDescriptionFor(key: Key): String {
        return when (key.type) {
            KeyType.SHIFT -> context.getString(R.string.key_shift_description)
            KeyType.BACKSPACE -> context.getString(R.string.key_backspace_description)
            KeyType.GLOBE -> context.getString(R.string.key_globe_description)
            KeyType.MIC -> context.getString(R.string.key_mic_description)
            KeyType.ENTER -> context.getString(R.string.key_enter_description)
            KeyType.SPACE -> context.getString(R.string.key_space)
            else -> key.label
        }
    }

    // --- Long-press: mini-popup con variantes ---

    private fun showLongPressPopup(anchor: View, key: Key) {
        dismissLongPressPopup()
        val variants = listOf(displayLabel(key)) + key.longPress.map {
            if (shiftState != ShiftState.NONE) it.uppercase() else it
        }
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setBackgroundColor(palette.surface)
            setPadding(context.dp(4), context.dp(4), context.dp(4), context.dp(4))
        }
        for (variant in variants) {
            row.addView(
                TextView(context).apply {
                    text = variant
                    gravity = Gravity.CENTER
                    typeface = Typeface.MONOSPACE
                    textSize = 18f
                    setTextColor(palette.textPrimary)
                    setPadding(context.dp(14), context.dp(10), context.dp(14), context.dp(10))
                    setOnClickListener {
                        onText(variant)
                        consumeShiftIfNeeded()
                        dismissLongPressPopup()
                    }
                },
            )
        }
        val frame = GradientDrawable().apply {
            cornerRadius = context.dp(6).toFloat()
            setColor(palette.surface)
            setStroke(context.dp(1), palette.surfaceStroke)
        }
        row.background = frame

        val popup = PopupWindow(row, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        popup.isOutsideTouchable = true
        popup.isFocusable = false
        longPressPopup = popup
        val location = IntArray(2)
        anchor.getLocationInWindow(location)
        popup.showAtLocation(
            this,
            Gravity.NO_GRAVITY,
            location[0],
            location[1] - anchor.height - context.dp(8),
        )
    }

    private fun dismissLongPressPopup() {
        longPressPopup?.dismiss()
        longPressPopup = null
    }

    // --- Repetición de BACKSPACE al mantener pulsado ---

    private fun attachRepeat(keyView: TextView) {
        keyView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> scheduleRepeat()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelRepeat()
            }
            false
        }
    }

    private fun scheduleRepeat() {
        cancelRepeat()
        val runnable = object : Runnable {
            override fun run() {
                onBackspace()
                repeatHandler.postDelayed(this, REPEAT_INTERVAL_MS)
            }
        }
        repeatRunnable = runnable
        repeatHandler.postDelayed(runnable, REPEAT_INITIAL_DELAY_MS)
    }

    private fun cancelRepeat() {
        repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
        repeatRunnable = null
    }

    companion object {
        private const val ROW_HEIGHT_DP = 48
        private const val DOUBLE_TAP_WINDOW_MS = 300L
        private const val REPEAT_INITIAL_DELAY_MS = 400L
        private const val REPEAT_INTERVAL_MS = 50L
    }
}
