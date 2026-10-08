package dev.jorgex.whspr

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Panel de dictado que sustituye al teclado mientras se graba o transcribe: una
 * cabecera (estado a la izquierda, "Cancelar" a la derecha) y debajo la onda
 * ([VoiceWaveView]), que ocupa el resto y termina la grabación al tocarla. Sin
 * lógica propia: el IME decide qué hacer en [onFinish] y [onCancel].
 */
class DictationView(context: Context) : LinearLayout(context) {

    var onFinish: () -> Unit = {}
    var onCancel: () -> Unit = {}

    private val palette = WhsprColors.forContext(context)
    private val wave = VoiceWaveView(context)
    private val status = TextView(context).apply {
        typeface = Typeface.MONOSPACE
        textSize = 14f
        isSingleLine = true
        setTextColor(palette.textMuted)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(8), 0, context.dp(8), 0)
    }

    init {
        orientation = VERTICAL
        val cancel = TextView(context).apply {
            setText(R.string.dictation_cancel)
            typeface = Typeface.MONOSPACE
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(palette.textPrimary)
            setPadding(context.dp(16), 0, context.dp(16), 0)
            background = surfaceRippleBackground(palette, context.dp(6).toFloat(), context.dp(1), palette.surfaceStroke)
            setOnClickListener { onCancel() }
        }
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(status, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(cancel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(HEADER_HEIGHT_DP)))
        addView(wave, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        wave.setOnClickListener { onFinish() }
        setMode(VoiceWaveView.Mode.RECORDING)
    }

    fun setMode(mode: VoiceWaveView.Mode) {
        wave.setMode(mode)
        val recording = mode == VoiceWaveView.Mode.RECORDING
        status.setText(if (recording) R.string.dictation_listening else R.string.dictation_transcribing)
        // Solo la grabación se termina tocando la onda; transcribiendo no hace nada.
        wave.isClickable = recording
        wave.contentDescription = if (recording) context.getString(R.string.dictation_finish_description) else null
    }

    /** Nivel de voz (0f..1f). Seguro desde el hilo de audio: ver [VoiceWaveView.setLevel]. */
    fun setLevel(level: Float) = wave.setLevel(level)

    private companion object {
        const val HEADER_HEIGHT_DP = 40
    }
}
