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
 */
fun surfaceRippleBackground(
    palette: WhsprPalette,
    cornerRadiusPx: Float,
    strokeWidthPx: Int,
    strokeColor: Int,
): Drawable {
    val shape = GradientDrawable().apply {
        cornerRadius = cornerRadiusPx
        setColor(palette.surface)
        setStroke(strokeWidthPx, strokeColor)
    }
    val ripple = WhsprColors.withAlpha(palette.accent, 0x40)
    return RippleDrawable(ColorStateList.valueOf(ripple), shape, null)
}
