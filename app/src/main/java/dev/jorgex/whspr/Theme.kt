package dev.jorgex.whspr

import android.content.Context
import android.content.res.Configuration

/**
 * Paleta de Whspr. Monocromo: blancos y grises sobre fondo neutro. Ver DESIGN.md.
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
    // Oscuro: contenido claro (blancos/grises) sobre carbón.
    val Dark = WhsprPalette(
        background = 0xFF0E0E10.toInt(),
        backgroundTop = 0xFF161619.toInt(),
        surface = 0xFF1B1B1F.toInt(),
        surfaceStroke = 0xFF2E2E33.toInt(),
        accent = 0xFFF2F2F2.toInt(),
        accentBright = 0xFFFFFFFF.toInt(),
        accentDeep = 0xFFB8B8C0.toInt(),
        accentMuted = 0xFF6A6A72.toInt(),
        glow = 0xFFFFFFFF.toInt(),
        onAccent = 0xFF0E0E10.toInt(),
        textPrimary = 0xFFF2F2F2.toInt(),
        textMuted = 0xFF9A9AA2.toInt(),
        disabled = 0xFF4A4A52.toInt(),
    )

    // Claro: contenido oscuro (grises/negro) sobre blanco.
    val Light = WhsprPalette(
        background = 0xFFF5F5F6.toInt(),
        backgroundTop = 0xFFFFFFFF.toInt(),
        surface = 0xFFFFFFFF.toInt(),
        surfaceStroke = 0xFFE2E2E6.toInt(),
        accent = 0xFF1F1F22.toInt(),
        accentBright = 0xFF101012.toInt(),
        accentDeep = 0xFF44444A.toInt(),
        accentMuted = 0xFF9A9AA0.toInt(),
        glow = 0xFF1F1F22.toInt(),
        onAccent = 0xFFF5F5F6.toInt(),
        textPrimary = 0xFF1A1A1D.toInt(),
        textMuted = 0xFF6A6A70.toInt(),
        disabled = 0xFFC2C2C8.toInt(),
    )

    fun isDark(context: Context): Boolean {
        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return night == Configuration.UI_MODE_NIGHT_YES
    }

    fun forContext(context: Context): WhsprPalette = if (isDark(context)) Dark else Light
}
