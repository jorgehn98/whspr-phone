package dev.jorgex.whspr

import android.content.Context

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("whspr", Context.MODE_PRIVATE)

    var modelId: String
        get() = prefs.getString(KEY_MODEL_ID, ModelCatalog.default.id) ?: ModelCatalog.default.id
        set(value) = prefs.edit().putString(KEY_MODEL_ID, value).apply()

    var language: String
        get() = cleanLanguage(prefs.getString(KEY_LANGUAGE, LANGUAGE_SPANISH))
        set(value) = prefs.edit().putString(KEY_LANGUAGE, cleanLanguage(value)).apply()

    var keyboardLanguage: KeyboardLanguage
        get() = cleanKeyboardLanguage(prefs.getString(KEY_KEYBOARD_LANGUAGE, null))
        set(value) = prefs.edit().putString(KEY_KEYBOARD_LANGUAGE, value.name).apply()

    var periodSide: PeriodSide
        get() = cleanPeriodSide(prefs.getString(KEY_PERIOD_SIDE, null))
        set(value) = prefs.edit().putString(KEY_PERIOD_SIDE, value.name).apply()

    var showNumberRow: Boolean
        get() = prefs.getBoolean(KEY_SHOW_NUMBER_ROW, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_NUMBER_ROW, value).apply()

    var pendingModelId: String?
        get() = prefs.getString(KEY_PENDING_MODEL_ID, null)
        set(value) = prefs.edit().putString(KEY_PENDING_MODEL_ID, value).apply()

    var pendingDownloadId: Long
        get() = prefs.getLong(KEY_PENDING_DOWNLOAD_ID, -1L)
        set(value) = prefs.edit().putLong(KEY_PENDING_DOWNLOAD_ID, value).apply()

    fun clearPendingDownload() {
        prefs.edit()
            .remove(KEY_PENDING_MODEL_ID)
            .remove(KEY_PENDING_DOWNLOAD_ID)
            .apply()
    }

    companion object {
        const val LANGUAGE_SPANISH = "es"
        const val LANGUAGE_AUTO = "auto"

        private const val KEY_MODEL_ID = "model_id"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_KEYBOARD_LANGUAGE = "keyboard_language"
        private const val KEY_PERIOD_SIDE = "period_side"
        private const val KEY_SHOW_NUMBER_ROW = "show_number_row"
        private const val KEY_PENDING_MODEL_ID = "pending_model_id"
        private const val KEY_PENDING_DOWNLOAD_ID = "pending_download_id"
    }

    private fun cleanLanguage(value: String?): String {
        return if (value != null && Languages.isValid(value)) value else LANGUAGE_SPANISH
    }

    private fun cleanKeyboardLanguage(value: String?): KeyboardLanguage {
        return KeyboardLanguage.entries.firstOrNull { it.name == value } ?: KeyboardLanguage.ES
    }

    private fun cleanPeriodSide(value: String?): PeriodSide {
        return PeriodSide.entries.firstOrNull { it.name == value } ?: PeriodSide.LEFT
    }
}
