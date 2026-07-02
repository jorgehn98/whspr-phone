package dev.jorgex.whspr

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/**
 * Vista del teclado QWERTY completo: grid de teclas generado dinámicamente a partir de
 * [KeyboardLayout]. Cada tecla es un [FrameLayout] (fondo/click) con un [TextView] o
 * [ImageView] centrado dentro. Sin lógica de negocio propia: solo notifica al IME vía
 * callbacks. Ver AGENTS.md / DESIGN.md — sin Compose, sin AndroidX, sin [android.widget.Button].
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
    private var periodSide = PeriodSide.LEFT
    private var showNumberRow = true

    private val palette = WhsprColors.forContext(context)
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null
    private var lastShiftTapAt = 0L
    private var longPressPopup: PopupWindow? = null

    // El OnTouchListener de BACKSPACE devuelve false para dejar pasar el click
    // normal (tap simple = un borrado). Pero si la repetición por hold ya
    // disparó al menos un borrado, el ACTION_UP final no debe generar además
    // un click: repeatFired lo suprime y se resetea en el siguiente ACTION_DOWN.
    private var repeatFired = false

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

    /** Cambia el lado del punto desde fuera (ajuste de MainActivity) y re-renderiza. */
    fun setPeriodSide(newPeriodSide: PeriodSide) {
        if (periodSide == newPeriodSide) return
        periodSide = newPeriodSide
        render()
    }

    /** Muestra/oculta la fila numérica en LETRAS desde fuera (ajuste de MainActivity) y re-renderiza. */
    fun setShowNumberRow(newShowNumberRow: Boolean) {
        if (showNumberRow == newShowNumberRow) return
        showNumberRow = newShowNumberRow
        render()
    }

    override fun onDetachedFromWindow() {
        cancelRepeat()
        dismissLongPressPopup()
        super.onDetachedFromWindow()
    }

    private fun render() {
        // removeAllViews() saca de la jerarquía la tecla de BACKSPACE que pudiera
        // estar en hold: sin ACTION_UP/CANCEL, repeatRunnable seguiría borrando
        // indefinidamente sobre una vista ya desconectada. Cortar el repeat aquí
        // cubre también el re-render disparado por un cambio de ajuste a mitad
        // de hold (setLanguage/setPeriodSide/setShowNumberRow), no solo el tap.
        cancelRepeat()
        repeatFired = false
        removeAllViews()
        val keyboardLayout = KeyboardLayouts.layoutFor(language, layer, periodSide, showNumberRow)
        // La altura total del grid es siempre HEIGHT_DP (constante), la reparten
        // las filas que haya: con la fila numérica oculta en LETRAS quedan 4 filas
        // en vez de 5 y cada una crece para llenar el mismo alto total.
        val rowHeightDp = HEIGHT_DP / keyboardLayout.rows.size
        for (row in keyboardLayout.rows) {
            addView(buildRow(row, rowHeightDp))
        }
    }

    private fun buildRow(row: List<Key>, rowHeightDp: Int): LinearLayout {
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            val params = LayoutParams(LayoutParams.MATCH_PARENT, context.dp(rowHeightDp))
            layoutParams = params
            for (key in row) {
                addView(buildKeyView(key))
            }
        }
    }

    /**
     * Contenedor de cada tecla: [FrameLayout] con fondo/click/caja táctil, que aloja
     * o bien un [ImageView] centrado en ambos ejes (teclas con icono propio: SHIFT,
     * BACKSPACE, GLOBE, MIC, ENTER) o un [TextView] centrado (teclas con label). Un
     * compound drawable de TextView con label vacío no centra verticalmente el
     * drawable (queda pegado arriba); el ImageView con CENTER_INSIDE sí centra en
     * ambos ejes dentro de la misma caja táctil.
     */
    private fun buildKeyView(key: Key): View {
        return FrameLayout(context).apply {
            val iconRes = keyIconRes(key)
            if (iconRes != null) {
                addView(
                    ImageView(context).apply {
                        setImageResource(iconRes)
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                        imageTintList = ColorStateList.valueOf(keyTextColor(key))
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.CENTER,
                        )
                    },
                )
            } else {
                addView(
                    TextView(context).apply {
                        text = displayLabel(key)
                        isSingleLine = true
                        gravity = Gravity.CENTER
                        typeface = Typeface.MONOSPACE
                        textSize = if (key.type == KeyType.LAYER_PAGE) 14f else 18f
                        setTextColor(keyTextColor(key))
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            Gravity.CENTER,
                        )
                    },
                )
            }
            background = keyBackground(key)
            contentDescription = contentDescriptionFor(key)
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, key.weight)
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

    /** Drawable de icono vectorial para teclas sin texto propio, o null si la tecla usa label. */
    private fun keyIconRes(key: Key): Int? {
        return when (key.type) {
            KeyType.SHIFT -> if (shiftState == ShiftState.CAPS_LOCK) {
                R.drawable.ic_key_shift_caps
            } else {
                R.drawable.ic_key_shift
            }
            KeyType.BACKSPACE -> R.drawable.ic_key_backspace
            KeyType.GLOBE -> R.drawable.ic_key_globe
            KeyType.MIC -> R.drawable.ic_key_mic
            KeyType.ENTER -> R.drawable.ic_key_enter
            else -> null
        }
    }

    /**
     * Fondo de la tecla: superficie redondeada con borde, sin ripple expansivo.
     * Estado normal = [fillColor] según SHIFT/CAPS_LOCK (ver más abajo); estado
     * pulsado = un tono más resaltado del mismo [fillColor], con transición
     * instantánea en ambas direcciones (sin fundido de entrada ni salida) para que
     * el feedback táctil se sienta inmediato en vez de la onda expansiva del ripple
     * por defecto de Android.
     *
     * SHIFT según estado: NONE = superficie normal; SHIFT transitorio = superficie
     * resaltada (surfaceStroke, un paso más claro que surface); CAPS_LOCK = invertida
     * (accentBright) para ser inconfundible.
     */
    private fun keyBackground(key: Key): Drawable {
        val fillColor = when {
            key.type != KeyType.SHIFT -> palette.surface
            shiftState == ShiftState.CAPS_LOCK -> palette.accentBright
            shiftState == ShiftState.SHIFT -> palette.surfaceStroke
            else -> palette.surface
        }
        return keyPressBackground(fillColor)
    }

    /**
     * [StateListDrawable] con dos shapes fijos (normal / pressed) y sin animación:
     * al tocar cambia de color al instante, al soltar vuelve al instante. Sustituye
     * al ripple compartido de [surfaceRippleBackground] solo para las teclas del
     * teclado (MainActivity sigue usando el ripple normal).
     */
    private fun keyPressBackground(fillColor: Int): Drawable {
        fun shape(color: Int) = GradientDrawable().apply {
            cornerRadius = context.dp(6).toFloat()
            setColor(color)
            setStroke(context.dp(1), palette.surfaceStroke)
        }
        return StateListDrawable().apply {
            setExitFadeDuration(0)
            addState(intArrayOf(android.R.attr.state_pressed), shape(pressedColorFor(fillColor)))
            addState(intArrayOf(), shape(fillColor))
        }
    }

    /** Tono "un paso más resaltado" que [fillColor] para el estado pulsado de una tecla. */
    private fun pressedColorFor(fillColor: Int): Int {
        return when (fillColor) {
            palette.accentBright -> palette.accentDeep
            palette.surfaceStroke -> palette.accentMuted
            else -> palette.surfaceStroke
        }
    }

    private fun handleTap(key: Key) {
        when (key.type) {
            KeyType.CHAR -> {
                onText(displayLabel(key))
                consumeShiftIfNeeded()
            }
            KeyType.SHIFT -> handleShiftTap()
            KeyType.BACKSPACE -> {
                if (repeatFired) {
                    repeatFired = false
                } else {
                    onBackspace()
                }
            }
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
            lastShiftTapAt = 0L
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
            // CAPS_LOCK invierte la tecla (fondo accentBright): el icono debe ir
            // oscuro (onAccent) para mantener contraste, no accentBright sobre sí mismo.
            key.type == KeyType.SHIFT && shiftState == ShiftState.CAPS_LOCK -> palette.onAccent
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

    /** Cierra el popup de long-press si está abierto. Llamable desde el IME (p. ej. onStartInput/onFinishInput). */
    fun dismissLongPressPopup() {
        longPressPopup?.dismiss()
        longPressPopup = null
    }

    // --- Repetición de BACKSPACE al mantener pulsado ---

    private fun attachRepeat(keyView: View) {
        keyView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    repeatFired = false
                    scheduleRepeat()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelRepeat()
            }
            false
        }
    }

    private fun scheduleRepeat() {
        cancelRepeat()
        val runnable = object : Runnable {
            override fun run() {
                repeatFired = true
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
        // SYMBOLS_1/SYMBOLS_2 declaran siempre 5 filas (dígitos, 2 filas de símbolos,
        // fila con page/backspace, fila inferior); es la referencia de altura total.
        // LETRAS declara 5 filas por defecto o 4 si showNumberRow está desactivado:
        // en ese caso cada fila crece para mantener el mismo alto total HEIGHT_DP
        // (ver render()), que es siempre constante y determinista, sin requerir
        // medir tras layout: el IME fija la altura de la onda a KeyboardView.HEIGHT_DP
        // (ver WhsprInputMethodService.onCreateInputView) para no cambiar de alto
        // al alternar entre teclado y onda.
        private const val ROW_HEIGHT_DP = 48
        private const val ROW_COUNT = 5
        const val HEIGHT_DP = ROW_HEIGHT_DP * ROW_COUNT

        private const val DOUBLE_TAP_WINDOW_MS = 500L
        private const val REPEAT_INITIAL_DELAY_MS = 400L
        private const val REPEAT_INTERVAL_MS = 50L
    }
}
