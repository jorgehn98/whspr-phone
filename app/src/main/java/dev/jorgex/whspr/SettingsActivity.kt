package dev.jorgex.whspr

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Pantalla secundaria ("Más ajustes"), accesible desde MainActivity: ajustes del
 * teclado (posición del punto, fila de números) y la sección "Acerca de" con las
 * licencias de terceros. Views a mano, sin Compose/AndroidX.
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

        val licensesButton = Button(this).apply {
            text = getString(R.string.third_party_licenses)
            setOnClickListener { showLicenses() }
        }
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        val aboutHeader = sectionHeader(R.string.section_about)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val side = dp(24)
            val top = dp(24)
            setPadding(side, top, side, side)
            addView(keyboardHeader)
            addView(periodSideButton)
            addView(showNumberRowButton)
            addView(aboutHeader)
            addView(licensesButton)
            addView(
                TextView(this@SettingsActivity).apply {
                    text = getString(R.string.about_version, version)
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(WhsprColors.forContext(this@SettingsActivity).textMuted)
                    setPadding(0, dp(16), 0, 0)
                },
            )
        }

        val palette = WhsprColors.forContext(this)
        root.setBackgroundColor(palette.background)
        listOf(periodSideButton, showNumberRowButton, licensesButton).forEach { styleButton(it) }

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

    private fun showLicenses() {
        val notices = resources.openRawResource(R.raw.third_party_licenses).bufferedReader().use { it.readText() }
        AlertDialog.Builder(this)
            .setTitle(R.string.third_party_licenses)
            .setMessage(notices)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun styleButton(button: Button) {
        button.isAllCaps = false
        button.setTextColor(defaultButtonTextColors())
        button.background = defaultButtonBackground()
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(52),
        )
        params.setMargins(0, dp(8), 0, 0)
        button.layoutParams = params
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
        val names = items.map { periodSideName(it) }
        val current = items.indexOf(settings.periodSide).coerceAtLeast(0)
        showSingleChoicePicker(R.string.period_side_title, names, current) { which ->
            settings.periodSide = items[which]
            refreshStatus()
        }
    }

    private fun showNumberRowName(show: Boolean): String {
        return if (show) getString(R.string.show_number_row_on) else getString(R.string.show_number_row_off)
    }

    private fun showNumberRowPicker() {
        val items = booleanArrayOf(true, false)
        val names = items.map { showNumberRowName(it) }
        val current = items.indexOf(settings.showNumberRow).coerceAtLeast(0)
        showSingleChoicePicker(R.string.show_number_row_title, names, current) { which ->
            settings.showNumberRow = items[which]
            refreshStatus()
        }
    }
}
