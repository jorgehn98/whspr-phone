package dev.jorgex.whspr

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable

/** Convierte dp a píxeles según la densidad de pantalla. */
fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

/**
 * Fondo compartido de botones y teclas de Whspr: superficie redondeada con borde
 * y ripple del acento. Centraliza la receta usada por MainActivity y el teclado.
 * [fillColor] por defecto es [WhsprPalette.surface]; se puede invertir (p. ej.
 * CAPS_LOCK con [WhsprPalette.accentBright]) sin duplicar la receta del shape.
 */
fun surfaceRippleBackground(
    palette: WhsprPalette,
    cornerRadiusPx: Float,
    strokeWidthPx: Int,
    strokeColor: Int,
    fillColor: Int = palette.surface,
): Drawable {
    val shape = GradientDrawable().apply {
        cornerRadius = cornerRadiusPx
        setColor(fillColor)
        setStroke(strokeWidthPx, strokeColor)
    }
    val ripple = WhsprColors.withAlpha(palette.accent, 0x40)
    return RippleDrawable(ColorStateList.valueOf(ripple), shape, null)
}
