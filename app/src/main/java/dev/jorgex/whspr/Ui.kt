package dev.jorgex.whspr

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.TextView

/** Convierte dp a píxeles según la densidad de pantalla. */
fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

/**
 * AlertDialog de selección única compartido por los pickers de MainActivity y
 * SettingsActivity (idioma, lado del punto, fila numérica): mismo esqueleto de
 * título + lista de opciones + cierre y callback al elegir. [currentIndex] lo
 * calcula el llamador (p. ej. indexOfFirst por código en vez de indexOf) porque
 * cada picker mapea su selección actual de forma distinta.
 */
fun android.app.Activity.showSingleChoicePicker(
    titleRes: Int,
    items: List<String>,
    currentIndex: Int,
    onSelected: (Int) -> Unit,
) {
    AlertDialog.Builder(this)
        .setTitle(titleRes)
        .setSingleChoiceItems(items.toTypedArray(), currentIndex) { dialog, which ->
            onSelected(which)
            dialog.dismiss()
        }
        .show()
}

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

/**
 * Fondo por defecto de los botones de MainActivity y SettingsActivity: misma
 * receta de [surfaceRippleBackground] con los tokens estándar de superficie.
 */
fun Context.defaultButtonBackground(): Drawable {
    val palette = WhsprColors.forContext(this)
    return surfaceRippleBackground(palette, dp(14).toFloat(), dp(1), palette.surfaceStroke)
}

/** Colores de texto por defecto de los botones, según estén habilitados o no. */
fun Context.defaultButtonTextColors(): ColorStateList {
    val palette = WhsprColors.forContext(this)
    val states = arrayOf(
        intArrayOf(android.R.attr.state_enabled),
        intArrayOf(-android.R.attr.state_enabled),
    )
    val colors = intArrayOf(palette.textPrimary, palette.textMuted)
    return ColorStateList(states, colors)
}

/** Encabezado de sección de las pantallas de la app (MainActivity y SettingsActivity). */
fun Context.sectionHeader(textRes: Int): TextView {
    val palette = WhsprColors.forContext(this)
    return TextView(this).apply {
        text = getString(textRes)
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(palette.textMuted)
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.setMargins(0, dp(24), 0, dp(4))
        layoutParams = params
    }
}
