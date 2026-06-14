package dev.jorgex.whspr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import android.view.animation.AnimationUtils
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Burbuja de micrófono del teclado Whspr, estilo ASCII art.
 *
 * Dibuja con Canvas (sin dependencias) una rejilla de caracteres monoespaciados
 * que forma un orbe: el centro brilla con el acento y los bordes se atenúan. El
 * campo ondula con el tiempo (no reactivo al audio). El IME fija el estado con
 * [setMode]. Colores desde [WhsprColors] según el modo claro/oscuro del sistema.
 */
class BubbleMicView(context: Context) : View(context) {

    enum class Mode { IDLE, LISTENING, PROCESSING, DISABLED }

    private val ramp = " .:-=+*#%@".toCharArray()

    private var mode = Mode.IDLE
    private var startTime = AnimationUtils.currentAnimationTimeMillis()
    private var animating = false

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.LEFT
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath = Path()

    private var cols = 0
    private var rows = 0
    private var advance = 0f
    private var cellH = 0f
    private var ascent = 0f
    private var gridLeft = 0f
    private var gridTop = 0f
    private var grid: Array<CharArray> = emptyArray()

    private fun palette(): WhsprPalette = WhsprColors.forContext(context)

    fun setMode(newMode: Mode) {
        if (mode == newMode) return
        mode = newMode
        startTime = AnimationUtils.currentAnimationTimeMillis()
        buildGlow()
        ensureAnimating()
        invalidate()
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        ensureGrid()
        buildGlow()
    }

    private fun ensureAnimating() {
        val shouldAnimate = mode != Mode.DISABLED && isShown
        if (shouldAnimate && !animating) {
            animating = true
            postInvalidateOnAnimation()
        }
        if (!shouldAnimate) {
            animating = false
        }
    }

    private fun hotColor(p: WhsprPalette): Int = when (mode) {
        Mode.LISTENING -> p.accentBright
        Mode.PROCESSING -> p.accentDeep
        Mode.DISABLED -> p.disabled
        Mode.IDLE -> p.accent
    }

    private fun buildGlow() {
        if (width == 0 || height == 0) return
        val r = min(width, height) / 2f * 1.05f
        val hot = hotColor(palette())
        glowPaint.shader = RadialGradient(
            width / 2f, height / 2f, r,
            intArrayOf(withAlpha(hot, 70), withAlpha(hot, 0)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
        )
    }

    private fun ensureGrid() {
        if (width == 0 || height == 0) return
        textPaint.textSize = height * 0.075f
        val fm = textPaint.fontMetrics
        advance = textPaint.measureText("0")
        cellH = (fm.descent - fm.ascent) * 0.96f
        ascent = fm.ascent
        val newCols = max(1, (width / advance).toInt())
        val newRows = max(1, (height / cellH).toInt())
        if (newCols != cols || newRows != rows) {
            cols = newCols
            rows = newRows
            grid = Array(rows) { CharArray(cols) }
        }
        gridLeft = (width - cols * advance) / 2f
        gridTop = (height - rows * cellH) / 2f
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        if (cols == 0 || rows == 0) ensureGrid()
        if (glowPaint.shader == null) buildGlow()
        val p = palette()
        val t = (AnimationUtils.currentAnimationTimeMillis() - startTime) / 1000f

        val amp: Float
        val speed: Float
        val waveK: Float
        val breatheSpeed: Float
        val intensity: Float
        when (mode) {
            Mode.LISTENING -> { amp = 0.95f; speed = 6.0f; waveK = 7f; breatheSpeed = 3.2f; intensity = 1f }
            Mode.PROCESSING -> { amp = 0.65f; speed = 10f; waveK = 9f; breatheSpeed = 2.4f; intensity = 0.9f }
            Mode.IDLE -> { amp = 0.35f; speed = 2.0f; waveK = 5f; breatheSpeed = 1.6f; intensity = 0.6f }
            Mode.DISABLED -> { amp = 0f; speed = 0f; waveK = 5f; breatheSpeed = 0f; intensity = 0.4f }
        }
        val breath = 0.5f + 0.5f * sin(t * breatheSpeed)
        val halfW = cols * advance / 2f
        val halfH = rows * cellH / 2f
        val cx = width / 2f
        val cy = height / 2f

        for (r in 0 until rows) {
            val cellCy = gridTop + r * cellH + cellH / 2f
            val ndy = (cellCy - cy) / halfH
            val line = grid[r]
            for (c in 0 until cols) {
                val cellCx = gridLeft + c * advance + advance / 2f
                val ndx = (cellCx - cx) / halfW
                val d = sqrt(ndx * ndx + ndy * ndy)
                if (d > 1.12f) {
                    line[c] = ' '
                    continue
                }
                val ripple = sin(d * waveK - t * speed)
                val shimmer = sin(ndx * 3.1f + t * speed * 0.6f) * sin(ndy * 3.1f - t * speed * 0.5f)
                var v = (1f - d) * (0.65f + 0.35f * breath) + amp * 0.45f * ripple + amp * 0.22f * shimmer
                v -= d * d * 0.15f
                val idx = (v.coerceIn(0f, 1f) * (ramp.size - 1)).toInt()
                line[c] = ramp[idx]
            }
        }

        canvas.drawCircle(cx, cy, min(width, height) / 2f * 1.05f, glowPaint)

        // capa base (atenuada)
        textPaint.color = withAlpha(if (mode == Mode.DISABLED) p.disabled else p.accentMuted, (110f * intensity + 45f).toInt())
        for (r in 0 until rows) {
            canvas.drawText(grid[r], 0, cols, gridLeft, gridTop - ascent + r * cellH, textPaint)
        }

        // capa central brillante (recortada a un círculo que late)
        val coreR = min(halfW, halfH) * (0.5f + 0.12f * breath * intensity)
        clipPath.reset()
        clipPath.addCircle(cx, cy, coreR, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(clipPath)
        textPaint.color = withAlpha(hotColor(p), (200f * intensity + 55f).toInt())
        for (r in 0 until rows) {
            canvas.drawText(grid[r], 0, cols, gridLeft, gridTop - ascent + r * cellH, textPaint)
        }
        canvas.restoreToCount(save)

        if (animating) {
            postInvalidateOnAnimation()
        }
    }

    private fun withAlpha(color: Int, a: Int): Int {
        val c = if (a < 0) 0 else if (a > 255) 255 else a
        return (color and 0x00FFFFFF) or (c shl 24)
    }
}
