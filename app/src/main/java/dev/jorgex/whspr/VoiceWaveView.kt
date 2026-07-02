package dev.jorgex.whspr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.AnimationUtils
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Visualizador de onda de voz del teclado Whspr: barras verticales centradas
 * y simétricas, monocromas. Sustituye visualmente a la antigua burbuja ASCII.
 *
 * Misma disciplina que el resto de vistas del IME (paleta resuelta una vez,
 * loop con [postInvalidateOnAnimation] guardado por visibilidad) pero con una
 * estética más simple: sin rejilla de caracteres, solo barras.
 *
 * [setLevel] se invoca desde el hilo de audio ([AudioRecorder.onLevel]): solo
 * escribe un `@Volatile Float`, nunca toca la vista. El suavizado (attack
 * rápido, decay lento) ocurre en el hilo de UI dentro de [onDraw].
 */
class VoiceWaveView(context: Context) : View(context) {

    enum class Mode { RECORDING, TRANSCRIBING }

    private companion object {
        const val BAR_COUNT = 19
        const val BAR_GAP_RATIO = 0.4f
        const val MIN_HEIGHT_RATIO = 0.08f
        const val DECAY = 0.92f

        // Rango útil de voz normal en el RMS ya normalizado que llega por [setLevel]
        // (0f..1f, pero la voz normal solo ocupa ~0.02..0.4 de esa escala). Se
        // remapea a 0..1 con saturación para que la onda se note con voz normal
        // en vez de quedarse casi plana. Puramente de render: no toca [setLevel].
        const val GAIN_FLOOR = 0.02f
        const val GAIN_CEILING = 0.4f
    }

    /** Remapea [rawLevel] del rango útil de voz a 0..1 con saturación en los extremos. */
    private fun applyGain(rawLevel: Float): Float {
        val span = GAIN_CEILING - GAIN_FLOOR
        return ((rawLevel - GAIN_FLOOR) / span).coerceIn(0f, 1f)
    }

    private var mode = Mode.RECORDING
    private var startTime = AnimationUtils.currentAnimationTimeMillis()
    private var animating = false

    @Volatile
    private var level = 0f
    private var smoothedLevel = 0f
    private val barLevels = FloatArray(BAR_COUNT)

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // El modo claro/oscuro solo cambia recreando la view (IME.onConfigurationChanged),
    // así que la paleta se resuelve una vez y no en cada frame de onDraw.
    private val palette: WhsprPalette = WhsprColors.forContext(context)

    fun setMode(newMode: Mode) {
        if (mode == newMode) return
        mode = newMode
        startTime = AnimationUtils.currentAnimationTimeMillis()
        ensureAnimating()
        invalidate()
    }

    /** Nivel de voz normalizado (0f..1f). Llamado desde el hilo de audio: solo escritura volátil. */
    fun setLevel(newLevel: Float) {
        level = newLevel.coerceIn(0f, 1f)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ensureAnimating()
    }

    override fun onDetachedFromWindow() {
        animating = false
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        ensureAnimating()
        invalidate()
    }

    // Cambiar la visibilidad PROPIA de esta vista (p. ej. GONE -> VISIBLE al
    // entrar en RECORDING) no dispara onWindowVisibilityChanged: solo lo hace
    // la visibilidad de la ventana contenedora. onVisibilityAggregated cubre
    // ambos casos (propia y de ventana), así que es el único punto fiable
    // para reenganchar la animación tras hacerse visible.
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) {
            ensureAnimating()
            invalidate()
        }
    }

    private fun ensureAnimating() {
        val shouldAnimate = isShown
        if (shouldAnimate && !animating) {
            animating = true
            postInvalidateOnAnimation()
        }
        if (!shouldAnimate) {
            animating = false
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val p = palette
        val t = (AnimationUtils.currentAnimationTimeMillis() - startTime) / 1000f
        val cy = height / 2f
        val barWidth = width / (BAR_COUNT + (BAR_COUNT - 1) * BAR_GAP_RATIO)
        val gap = barWidth * BAR_GAP_RATIO
        val maxBarHeight = height * 0.85f
        val minBarHeight = maxBarHeight * MIN_HEIGHT_RATIO

        when (mode) {
            Mode.RECORDING -> {
                // Attack rápido / decay lento sobre el nivel crudo (0..1 de fondo de
                // escala); la ganancia se aplica después para no alterar la dinámica
                // del suavizado, solo el rango visible.
                smoothedLevel = max(level, smoothedLevel * DECAY)
                val gainedLevel = applyGain(smoothedLevel)
                barPaint.color = p.accentBright
                for (i in 0 until BAR_COUNT) {
                    // Las barras centrales responden más que las de los extremos,
                    // como un ecualizador simétrico centrado en la voz.
                    val distFromCenter = abs(i - (BAR_COUNT - 1) / 2f) / (BAR_COUNT / 2f)
                    val falloff = 1f - distFromCenter * 0.5f
                    val jitter = sin(t * 9f + i * 1.7f) * 0.08f
                    barLevels[i] = (gainedLevel * falloff + jitter).coerceIn(0f, 1f)
                }
            }
            Mode.TRANSCRIBING -> {
                barPaint.color = p.accentDeep
                for (i in 0 until BAR_COUNT) {
                    val phase = t * 3.2f - i * 0.55f
                    barLevels[i] = (0.5f + 0.5f * sin(phase)).coerceIn(0f, 1f)
                }
            }
        }

        for (i in 0 until BAR_COUNT) {
            val barHeight = max(minBarHeight, maxBarHeight * barLevels[i])
            val left = i * (barWidth + gap)
            val right = left + barWidth
            val top = cy - barHeight / 2f
            val bottom = cy + barHeight / 2f
            canvas.drawRoundRect(left, top, right, bottom, barWidth / 2f, barWidth / 2f, barPaint)
        }

        if (animating) {
            postInvalidateOnAnimation()
        }
    }
}
