package dev.jorgex.whspr

import android.content.Context
import android.content.res.Configuration

/**
 * Paleta de Whspr. Estilo HUD ámbar/oro. Ver DESIGN.md.
 *
 * Hay dos paletas, [WhsprColors.Dark] y [WhsprColors.Light]. La app NO tiene
 * selector: se sigue el modo claro/oscuro del sistema vía [WhsprColors.forContext].
 * Formato ARGB (0xAARRGGBB).
 */
class WhsprPalette(
    val background: Int,
    val backgroundTop: Int,
    val surface: Int,
    val surfaceStroke: Int,
    val accent: Int,
    val accentBright: Int,
    val accentDeep: Int,
    val accentMuted: Int,
    val glow: Int,
    val onAccent: Int,
    val textPrimary: Int,
    val textMuted: Int,
    val disabled: Int,
)

object WhsprColors {
    val Dark = WhsprPalette(
        background = 0xFF0E0E10.toInt(),
        backgroundTop = 0xFF15151A.toInt(),
        surface = 0xFF1A1A20.toInt(),
        surfaceStroke = 0xFF2C2C34.toInt(),
        accent = 0xFFFFB020.toInt(),
        accentBright = 0xFFFFC861.toInt(),
        accentDeep = 0xFFFF7A1A.toInt(),
        accentMuted = 0xFF6E5320.toInt(),
        glow = 0xFFFFE3A6.toInt(),
        onAccent = 0xFF0E0E10.toInt(),
        textPrimary = 0xFFF5F0E6.toInt(),
        textMuted = 0xFF9A958C.toInt(),
        disabled = 0xFF4A4A52.toInt(),
    )

    val Light = WhsprPalette(
        background = 0xFFF6F1E8.toInt(),
        backgroundTop = 0xFFFFFFFF.toInt(),
        surface = 0xFFFFFFFF.toInt(),
        surfaceStroke = 0xFFE3DAC9.toInt(),
        accent = 0xFFB9791A.toInt(),
        accentBright = 0xFFE0962A.toInt(),
        accentDeep = 0xFFC15A12.toInt(),
        accentMuted = 0xFFB89A55.toInt(),
        glow = 0xFFFFB020.toInt(),
        onAccent = 0xFF1A150E.toInt(),
        textPrimary = 0xFF26221B.toInt(),
        textMuted = 0xFF6F685E.toInt(),
        disabled = 0xFFBDB7AC.toInt(),
    )

    fun isDark(context: Context): Boolean {
        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return night == Configuration.UI_MODE_NIGHT_YES
    }

    fun forContext(context: Context): WhsprPalette = if (isDark(context)) Dark else Light
}
