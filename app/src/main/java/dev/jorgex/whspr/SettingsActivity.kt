package dev.jorgex.whspr

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Pantalla secundaria de ajustes del teclado ("Más ajustes"), accesible desde
 * MainActivity. Views a mano, sin Compose/AndroidX, mismos tokens y patrones
 * que MainActivity. Preparada para más secciones a futuro: por ahora solo
 * "Teclado" con los ajustes movidos de MainActivity (posición del punto,
 * fila de números).
 */
class SettingsActivity : Activity() {
    private lateinit var settings: AppSettings
    private lateinit var periodSideButton: Button
    private lateinit var showNumberRowButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settings = AppSettings(this)
        title = getString(R.string.settings_title)

        val keyboardHeader = sectionHeader(R.string.section_keyboard)

        periodSideButton = Button(this).apply {
            setOnClickListener { showPeriodSidePicker() }
        }

        showNumberRowButton = Button(this).apply {
            setOnClickListener { showNumberRowPicker() }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val side = dp(24)
            val top = dp(24)
            setPadding(side, top, side, side)
            addView(keyboardHeader)
            addView(periodSideButton)
            addView(showNumberRowButton)
        }

        val palette = WhsprColors.forContext(this)
        root.setBackgroundColor(palette.background)
        listOf(periodSideButton, showNumberRowButton).forEach { styleButton(it) }

        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(palette.background)
                addView(root)
            },
        )
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun sectionHeader(textRes: Int): TextView {
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
            params.setMargins(0, dp(8), 0, dp(4))
            layoutParams = params
        }
    }

    private fun styleButton(button: Button) {
        button.isAllCaps = false
        button.setTextColor(buttonTextColors())
        button.background = buttonBackground()
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(52),
        )
        params.setMargins(0, dp(8), 0, 0)
        button.layoutParams = params
    }

    private fun buttonBackground(): Drawable {
        val palette = WhsprColors.forContext(this)
        return surfaceRippleBackground(palette, dp(14).toFloat(), dp(1), palette.surfaceStroke)
    }

    private fun buttonTextColors(): ColorStateList {
        val palette = WhsprColors.forContext(this)
        val states = arrayOf(
            intArrayOf(android.R.attr.state_enabled),
            intArrayOf(-android.R.attr.state_enabled),
        )
        val colors = intArrayOf(palette.textPrimary, palette.textMuted)
        return ColorStateList(states, colors)
    }

    private fun refreshStatus() {
        periodSideButton.text = getString(R.string.selected_period_side, periodSideName(settings.periodSide))
        showNumberRowButton.text = getString(R.string.selected_show_number_row, showNumberRowName(settings.showNumberRow))
    }

    private fun periodSideName(side: PeriodSide): String {
        return when (side) {
            PeriodSide.LEFT -> getString(R.string.period_side_left)
            PeriodSide.RIGHT -> getString(R.string.period_side_right)
        }
    }

    private fun showPeriodSidePicker() {
        val items = PeriodSide.entries.toTypedArray()
        val names = items.map { periodSideName(it) }.toTypedArray()
        val current = items.indexOf(settings.periodSide).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.period_side_title)
            .setSingleChoiceItems(names, current) { dialog, which ->
                settings.periodSide = items[which]
                dialog.dismiss()
                refreshStatus()
            }
            .show()
    }

    private fun showNumberRowName(show: Boolean): String {
        return if (show) getString(R.string.show_number_row_on) else getString(R.string.show_number_row_off)
    }

    private fun showNumberRowPicker() {
        val items = booleanArrayOf(true, false)
        val names = items.map { showNumberRowName(it) }.toTypedArray()
        val current = items.indexOf(settings.showNumberRow).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.show_number_row_title)
            .setSingleChoiceItems(names, current) { dialog, which ->
                settings.showNumberRow = items[which]
                dialog.dismiss()
                refreshStatus()
            }
            .show()
    }
}
