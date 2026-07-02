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
 * y simétricas, monocromas. Sustituye visualmente a la burbuja ASCII.
 *
 * Sigue la disciplina de [BubbleMicView] (paleta resuelta una vez, loop con
 * [postInvalidateOnAnimation] guardado por visibilidad) pero con una estética
 * más simple: sin rejilla de caracteres, solo barras.
 *
 * [setLevel] se invoca desde el hilo de audio ([AudioRecorder.onLevel]): solo
 * escribe un `@Volatile Float`, nunca toca la vista. El suavizado (attack
 * rápido, decay lento) ocurre en el hilo de UI dentro de [onDraw].
 */
class VoiceWaveView(context: Context) : View(context) {

    enum class Mode { RECORDING, TRANSCRIBING }

    private companion object {
        const val BAR_COUNT = 9
        const val BAR_GAP_RATIO = 0.4f
        const val MIN_HEIGHT_RATIO = 0.08f
        const val DECAY = 0.92f
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
                smoothedLevel = max(level, smoothedLevel * DECAY)
                barPaint.color = p.accentBright
                for (i in 0 until BAR_COUNT) {
                    // Las barras centrales responden más que las de los extremos,
                    // como un ecualizador simétrico centrado en la voz.
                    val distFromCenter = abs(i - (BAR_COUNT - 1) / 2f) / (BAR_COUNT / 2f)
                    val falloff = 1f - distFromCenter * 0.5f
                    val jitter = sin(t * 9f + i * 1.7f) * 0.08f
                    barLevels[i] = (smoothedLevel * falloff + jitter).coerceIn(0f, 1f)
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
