package dev.jorgex.whspr

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.content.res.Configuration
import android.view.Gravity
import android.view.HapticFeedbackConstants
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
 *
 * El toque se resuelve aquí y no en cada tecla ([onInterceptTouchEvent]): así no hay
 * zonas muertas entre teclas (gana la más cercana), se puede corregir deslizando el
 * dedo antes de soltar y, al escribir rápido con dos pulgares, pulsar la siguiente
 * tecla confirma la anterior aunque el primer dedo siga apoyado.
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

    /** Pulsación larga del globo: el IME abre el selector de teclados del sistema. */
    var onSwitchKeyboard: () -> Unit = {}

    private var language = KeyboardLanguage.ES
    private var layer = KeyboardLayer.LETTERS
    private var shiftState = ShiftState.NONE

    // true si el SHIFT actual lo puso la mayúscula automática y no el usuario: solo
    // ese se puede retirar solo cuando el cursor deja de pedir mayúscula.
    private var autoShifted = false

    // Lo último que pidió el editor, para reponer la mayúscula automática al volver
    // de símbolos sin esperar al siguiente cambio de texto.
    private var autoShiftWanted = false
    private var periodSide = PeriodSide.LEFT
    private var showNumberRow = true
    private var isSecureInput = false

    private val palette = WhsprColors.forContext(context)
    private val handler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null
    private var longPressRunnable: Runnable? = null
    private var lastShiftTapAt = 0L
    private var longPressPopup: PopupWindow? = null

    /** Una tecla ya construida: su modelo y las vistas que cambian con SHIFT. */
    private class KeyHolder(val key: Key, val view: View, val label: TextView?, val icon: ImageView?)

    private val rows = ArrayList<List<KeyHolder>>()

    // Tecla que tiene pulsada cada dedo (por pointerId). Un dedo sale del mapa cuando
    // su tecla ya se ha resuelto (confirmada, long-press o cancelada).
    private val pressed = HashMap<Int, KeyHolder>()

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

    /**
     * Vuelve a la capa inicial al entrar en un campo nuevo: letras, o símbolos (con
     * los dígitos) si el campo es numérico. Descarta el SHIFT transitorio.
     */
    fun resetForField(numeric: Boolean) {
        val target = if (numeric) KeyboardLayer.SYMBOLS_1 else KeyboardLayer.LETTERS
        if (shiftState == ShiftState.SHIFT) shiftState = ShiftState.NONE
        autoShifted = false
        layer = target
        render()
    }

    /**
     * Mayúscula automática: el IME indica si el cursor pide mayúscula (inicio de frase,
     * campo de nombres…). No toca CAPS_LOCK ni un SHIFT puesto a mano por el usuario.
     */
    fun setAutoShift(wanted: Boolean) {
        autoShiftWanted = wanted
        if (shiftState == ShiftState.CAPS_LOCK) return
        if (shiftState == ShiftState.SHIFT && !autoShifted) return
        val next = if (wanted) ShiftState.SHIFT else ShiftState.NONE
        if (next == shiftState) return
        shiftState = next
        autoShifted = wanted
        refreshShift()
    }

    /** Atenúa la tecla MIC cuando el campo activo es de contraseña (IME) y re-renderiza. */
    fun setSecureInput(newIsSecureInput: Boolean) {
        if (isSecureInput == newIsSecureInput) return
        isSecureInput = newIsSecureInput
        render()
    }

    override fun onDetachedFromWindow() {
        releaseAll()
        dismissLongPressPopup()
        super.onDetachedFromWindow()
    }

    private fun render() {
        // removeAllViews() saca de la jerarquía teclas que pudieran estar pulsadas:
        // sin esto, la repetición de BACKSPACE seguiría borrando indefinidamente
        // sobre una vista ya desconectada.
        releaseAll()
        removeAllViews()
        rows.clear()
        val keyboardLayout = KeyboardLayouts.layoutFor(language, layer, periodSide, showNumberRow)
        // Las filas se reparten el alto fijo del teclado a partes iguales: con la fila
        // numérica oculta en LETRAS quedan 4 filas en vez de 5 y cada una crece.
        for (row in keyboardLayout.rows) {
            val holders = row.map(::buildKey)
            rows.add(holders)
            addView(
                LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    for (holder in holders) addView(holder.view)
                },
                LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
    }

    /**
     * Contenedor de cada tecla: [FrameLayout] con fondo y caja táctil, que aloja o bien
     * un [ImageView] centrado en ambos ejes (teclas con icono propio: SHIFT, BACKSPACE,
     * GLOBE, MIC, ENTER) o un [TextView] centrado (teclas con label). Un compound
     * drawable de TextView con label vacío no centra verticalmente el drawable (queda
     * pegado arriba); el ImageView con CENTER_INSIDE sí centra en ambos ejes.
     */
    private fun buildKey(key: Key): KeyHolder {
        val iconRes = keyIconRes(key)
        val icon = if (iconRes == null) null else ImageView(context).apply {
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            imageTintList = ColorStateList.valueOf(keyTextColor(key))
        }
        val label = if (iconRes != null) null else TextView(context).apply {
            text = displayLabel(key)
            isSingleLine = true
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
            textSize = if (key.type == KeyType.LAYER_PAGE) 14f else 18f
            setTextColor(keyTextColor(key))
        }
        val view = FrameLayout(context).apply {
            if (icon != null) {
                addView(
                    icon,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER,
                    ),
                )
            }
            if (label != null) {
                addView(
                    label,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
            background = keyBackground(key)
            contentDescription = contentDescriptionFor(key)
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, key.weight)
            params.setMargins(context.dp(2), context.dp(2), context.dp(2), context.dp(2))
            layoutParams = params
            // El toque real lo resuelve KeyboardView.onTouchEvent; este click solo llega
            // desde servicios de accesibilidad (TalkBack: doble toque sobre la tecla).
            setOnClickListener { handleTap(key) }
        }
        return KeyHolder(key, view, label, icon)
    }

    /** Aplica el estado de SHIFT a las teclas ya construidas, sin reconstruir el grid. */
    private fun refreshShift() {
        for (row in rows) {
            for (holder in row) {
                when (holder.key.type) {
                    KeyType.CHAR -> holder.label?.text = displayLabel(holder.key)
                    KeyType.SHIFT -> {
                        keyIconRes(holder.key)?.let { holder.icon?.setImageResource(it) }
                        holder.icon?.imageTintList = ColorStateList.valueOf(keyTextColor(holder.key))
                        holder.view.background = keyBackground(holder.key)
                    }
                    else -> Unit
                }
            }
        }
    }

    // --- Toque: una sola entrada para todas las teclas ---

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = true

    @SuppressLint("ClickableViewAccessibility") // accesibilidad: click propio de cada tecla
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // Escritura rápida: un dedo nuevo confirma lo que aún estuviera pulsado.
                for (id in pressed.keys.toList()) release(id, commit = true)
                val index = event.actionIndex
                dismissLongPressPopup()
                keyAt(event.getX(index), event.getY(index))?.let {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    press(event.getPointerId(index), it)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    val id = event.getPointerId(index)
                    val current = pressed[id] ?: continue
                    val target = keyAt(event.getX(index), event.getY(index))
                    if (target != null && target !== current) {
                        release(id, commit = false)
                        press(id, target)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                release(event.getPointerId(event.actionIndex), commit = true)
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        return true
    }

    /**
     * Tecla que corresponde al punto. Se calcula con los pesos del layout y no con la
     * geometría de las vistas: no deja huecos entre teclas y funciona aunque el grid
     * se acabe de reconstruir y aún no tenga layout (cambio de capa con otro dedo ya
     * bajando).
     */
    private fun keyAt(x: Float, y: Float): KeyHolder? {
        if (rows.isEmpty() || width == 0 || height == 0) return null
        val row = rows[(y / height * rows.size).toInt().coerceIn(0, rows.size - 1)]
        var remaining = (x / width).coerceIn(0f, 1f) * row.sumOf { it.key.weight.toDouble() }.toFloat()
        for (holder in row) {
            remaining -= holder.key.weight
            if (remaining <= 0f) return holder
        }
        return row.lastOrNull()
    }

    private fun press(pointerId: Int, holder: KeyHolder) {
        pressed[pointerId] = holder
        holder.view.isPressed = true
        cancelTimers()
        when {
            // BACKSPACE actúa al pulsar y repite mientras se mantiene.
            holder.key.type == KeyType.BACKSPACE -> {
                onBackspace()
                scheduleRepeat()
            }
            holder.key.longPress.isNotEmpty() || holder.key.type == KeyType.GLOBE ->
                scheduleLongPress(pointerId, holder)
        }
    }

    private fun release(pointerId: Int, commit: Boolean) {
        val holder = pressed.remove(pointerId) ?: return
        holder.view.isPressed = false
        cancelTimers()
        if (commit && holder.key.type != KeyType.BACKSPACE) handleTap(holder.key)
    }

    private fun releaseAll() {
        for (id in pressed.keys.toList()) release(id, commit = false)
    }

    private fun scheduleLongPress(pointerId: Int, holder: KeyHolder) {
        val runnable = Runnable {
            if (pressed[pointerId] !== holder) return@Runnable
            // La tecla queda resuelta por la pulsación larga: soltar ya no escribe.
            release(pointerId, commit = false)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            if (holder.key.type == KeyType.GLOBE) onSwitchKeyboard() else showLongPressPopup(holder.view, holder.key)
        }
        longPressRunnable = runnable
        handler.postDelayed(runnable, LONG_PRESS_MS)
    }

    private fun scheduleRepeat() {
        val runnable = object : Runnable {
            // Tope de seguridad: si el ACTION_UP no llega nunca (p. ej. lo filtra una
            // ventana superpuesta), el borrado no sigue hasta vaciar el campo.
            var remaining = MAX_REPEATS

            override fun run() {
                onBackspace()
                if (--remaining > 0) handler.postDelayed(this, REPEAT_INTERVAL_MS)
            }
        }
        repeatRunnable = runnable
        handler.postDelayed(runnable, REPEAT_INITIAL_DELAY_MS)
    }

    private fun cancelTimers() {
        repeatRunnable?.let(handler::removeCallbacks)
        repeatRunnable = null
        longPressRunnable?.let(handler::removeCallbacks)
        longPressRunnable = null
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
            KeyType.CHAR -> typeAndConsumeShift(displayLabel(key))
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

    // El SHIFT de una letra se gasta ANTES de avisar al IME: así, si el editor pide
    // mayúscula también para la siguiente (campo solo de mayúsculas), la respuesta
    // del IME lo vuelve a activar en vez de quedar pisada.
    private fun typeAndConsumeShift(text: String) {
        consumeShiftIfNeeded()
        onText(text)
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
        autoShifted = false
        refreshShift()
    }

    private fun consumeShiftIfNeeded() {
        if (shiftState == ShiftState.SHIFT) {
            shiftState = ShiftState.NONE
            lastShiftTapAt = 0L
            autoShifted = false
            refreshShift()
        }
    }

    private fun setLayer(newLayer: KeyboardLayer) {
        layer = newLayer
        resetShiftTransient()
        if (newLayer == KeyboardLayer.LETTERS && autoShiftWanted && shiftState == ShiftState.NONE) {
            shiftState = ShiftState.SHIFT
            autoShifted = true
        }
        render()
    }

    /** Descarta el SHIFT transitorio (una mayúscula) al cambiar de capa; CAPS_LOCK no se toca. */
    private fun resetShiftTransient() {
        if (shiftState == ShiftState.SHIFT) {
            shiftState = ShiftState.NONE
            lastShiftTapAt = 0L
            autoShifted = false
        }
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
            key.type == KeyType.MIC && isSecureInput -> palette.disabled
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
                        typeAndConsumeShift(variant)
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

        // El popup es una ventana propia (WindowManager), no desciende de la raíz del
        // IME: no hereda su filterTouchesWhenObscured y hay que activarlo aquí también.
        row.filterTouchesWhenObscured = true
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

    companion object {
        private const val HEIGHT_DP = 240
        private const val LANDSCAPE_MIN_HEIGHT_DP = 160

        /**
         * Alto total del área de teclas, igual para el teclado y el panel de dictado
         * (alternar entre ambos no cambia el alto del IME). Fijo en vertical; en
         * horizontal se limita a la mitad de la pantalla para no tapar la app.
         */
        fun heightDp(context: Context): Int {
            val config = context.resources.configuration
            if (config.orientation != Configuration.ORIENTATION_LANDSCAPE) return HEIGHT_DP
            return (config.screenHeightDp / 2).coerceIn(LANDSCAPE_MIN_HEIGHT_DP, HEIGHT_DP)
        }

        private const val DOUBLE_TAP_WINDOW_MS = 500L
        private const val REPEAT_INITIAL_DELAY_MS = 400L
        private const val REPEAT_INTERVAL_MS = 50L
        private const val MAX_REPEATS = 600
        private const val LONG_PRESS_MS = 350L
    }
}
