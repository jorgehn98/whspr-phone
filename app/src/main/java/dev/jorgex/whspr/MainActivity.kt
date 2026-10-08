package dev.jorgex.whspr

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Pantalla principal: los cuatro pasos para dejar Whspr funcionando (micrófono,
 * modelo, activar el teclado y elegirlo), cada uno con su estado real, y debajo los
 * ajustes de dictado y un campo para probar el teclado.
 */
class MainActivity : Activity() {
    private lateinit var settings: AppSettings
    private lateinit var modelStore: ModelStore
    private lateinit var inputMethodManager: InputMethodManager
    private lateinit var microphoneStep: Button
    private lateinit var modelStep: Button
    private lateinit var enableStep: Button
    private lateinit var selectStep: Button
    private lateinit var modelTrash: ImageView
    private lateinit var modelButton: Button
    private lateinit var languageButton: Button
    private var statusRequest = 0
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settings = AppSettings(this)
        modelStore = ModelStore(this)
        inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val palette = WhsprColors.forContext(this)

        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 26f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setTextColor(palette.textPrimary)
        }
        val description = TextView(this).apply {
            text = getString(R.string.home_description)
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(palette.textMuted)
        }

        microphoneStep = stepButton {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
        }
        modelStep = stepButton { startModelDownload() }
        enableStep = stepButton { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        selectStep = stepButton { inputMethodManager.showInputMethodPicker() }
        modelTrash = ImageView(this).apply {
            setImageResource(R.drawable.ic_trash)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setColorFilter(palette.accent)
            contentDescription = getString(R.string.delete_model)
            val pad = dp(12)
            setPadding(pad, pad, pad, pad)
            setOnClickListener { deleteCurrentModel() }
        }
        val modelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(modelStep, LinearLayout.LayoutParams(0, dp(BUTTON_HEIGHT_DP), 1f))
            addView(modelTrash, LinearLayout.LayoutParams(dp(44), dp(BUTTON_HEIGHT_DP)).apply { marginStart = dp(8) })
        }

        modelButton = optionButton { showModelPicker() }
        languageButton = optionButton { showLanguagePicker() }
        val moreSettingsButton = optionButton {
            startActivity(Intent(this, SettingsActivity::class.java))
        }.apply { text = getString(R.string.more_settings) }

        val tryField = EditText(this).apply {
            // Con id, Android conserva el texto al recrear la pantalla (rotación, modo oscuro).
            id = R.id.try_field
            hint = getString(R.string.try_keyboard_hint)
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            gravity = Gravity.TOP or Gravity.START
            setTextColor(palette.textPrimary)
            setHintTextColor(palette.textMuted)
            background = surfaceRippleBackground(palette, dp(14).toFloat(), dp(1), palette.surfaceStroke)
            val pad = dp(14)
            setPadding(pad, pad, pad, pad)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val side = dp(24)
            setPadding(side, dp(48), side, side)
            setBackgroundColor(palette.background)
            addView(title)
            addView(description)
            addView(sectionHeader(R.string.section_setup))
            addView(microphoneStep, rowParams())
            addView(modelRow, rowParams(LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(enableStep, rowParams())
            addView(selectStep, rowParams())
            addView(sectionHeader(R.string.section_dictation))
            addView(modelButton, rowParams())
            addView(languageButton, rowParams())
            addView(sectionHeader(R.string.section_try))
            addView(tryField, rowParams(LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(moreSettingsButton, rowParams().apply { topMargin = dp(24) })
        }

        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(palette.background)
                addView(root)
            },
        )
        refreshStatus()
    }

    private fun optionButton(onClick: () -> Unit): Button {
        return Button(this).apply {
            isAllCaps = false
            setTextColor(defaultButtonTextColors())
            background = defaultButtonBackground()
            setOnClickListener { onClick() }
        }
    }

    /** Botón de paso: monoespaciado y alineado a la izquierda para que las casillas cuadren. */
    private fun stepButton(onClick: () -> Unit): Button {
        return optionButton(onClick).apply {
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(dp(16), 0, dp(16), 0)
        }
    }

    private fun rowParams(height: Int = dp(BUTTON_HEIGHT_DP)): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height).apply {
            topMargin = dp(8)
        }
    }

    private fun setStep(button: Button, done: Boolean, label: String) {
        button.text = getString(if (done) R.string.step_done else R.string.step_pending, label)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // Activar o elegir el teclado ocurre en diálogos y pantallas del sistema: al
    // recuperar el foco se vuelve a leer el estado real.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshStatus()
    }

    override fun onPause() {
        statusRequest += 1
        refreshHandler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        statusRequest += 1
        refreshHandler.removeCallbacks(refreshRunnable)
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO) return
        val denied = grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED
        // Denegado "para siempre": Android ya no muestra el diálogo, solo queda ir a
        // los ajustes de la app.
        if (denied && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            openAppSettings()
        }
        refreshStatus()
    }

    private fun refreshStatus() {
        val model = ModelCatalog.byId(settings.modelId)
        val request = ++statusRequest
        refreshHandler.removeCallbacks(refreshRunnable)

        val micReady = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        setStep(microphoneStep, micReady, getString(if (micReady) R.string.microphone_allowed else R.string.allow_microphone))
        microphoneStep.isEnabled = !micReady

        val enabled = inputMethodManager.enabledInputMethodList.any { it.packageName == packageName }
        setStep(enableStep, enabled, getString(if (enabled) R.string.keyboard_enabled else R.string.enable_keyboard))
        val selected = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith("$packageName/") == true
        setStep(selectStep, selected, getString(if (selected) R.string.keyboard_selected else R.string.switch_keyboard))

        modelButton.text = getString(R.string.selected_model, model.label, model.sizeLabel)
        languageButton.text = getString(R.string.selected_language, Languages.nameFor(settings.language))

        // Resolver el estado puede instalar la descarga (SHA-256 y copia): fuera del hilo principal.
        Thread({
            val state = modelState(model)
            val percent = if (state == ModelStatus.Downloading) {
                modelStore.downloadPercent(settings.pendingDownloadId)
            } else {
                null
            }
            refreshHandler.post {
                if (request != statusRequest) return@post
                applyModelState(state, percent)
            }
        }, "whspr-status").start()
    }

    private fun applyModelState(state: ModelStatus, percent: Int?) {
        val label = when (state) {
            ModelStatus.Ready -> getString(R.string.model_downloaded)
            ModelStatus.Downloading ->
                if (percent == null) getString(R.string.downloading) else getString(R.string.downloading_percent, percent)
            ModelStatus.Failed -> getString(R.string.download_failed)
            ModelStatus.Missing -> getString(R.string.download_model)
        }
        setStep(modelStep, state == ModelStatus.Ready, label)
        modelStep.isEnabled = state != ModelStatus.Ready && state != ModelStatus.Downloading
        modelTrash.visibility = if (state == ModelStatus.Missing) View.GONE else View.VISIBLE
        if (state == ModelStatus.Downloading) {
            refreshHandler.postDelayed(refreshRunnable, 1_000)
        }
    }

    private fun startModelDownload() {
        val model = ModelCatalog.byId(settings.modelId)
        clearPendingDownload()
        val downloadId = modelStore.download(model)
        if (downloadId > 0L) {
            settings.pendingModelId = model.id
            settings.pendingDownloadId = downloadId
        } else {
            Toast.makeText(this, R.string.error_download_start_failed, Toast.LENGTH_SHORT).show()
        }
        refreshStatus()
    }

    private fun clearPendingDownload() {
        modelStore.cancelDownload(settings.pendingDownloadId)
        settings.pendingModelId?.let(ModelCatalog::findById)?.let(modelStore::deleteDownload)
        settings.clearPendingDownload()
    }

    private fun modelState(model: SpeechModel): ModelStatus {
        return modelStore.resolveStatus(settings, model)
    }

    private fun showModelPicker() {
        val models = ModelCatalog.models
        val names = models.map { getString(R.string.model_option, it.label, it.sizeLabel) }
        val current = models.indexOfFirst { it.id == settings.modelId }.coerceAtLeast(0)
        showSingleChoicePicker(R.string.model_title, names, current) { which ->
            if (models[which].id != settings.modelId) {
                // Una descarga a medias del modelo anterior ya no interesa.
                clearPendingDownload()
                settings.modelId = models[which].id
            }
            refreshStatus()
        }
    }

    private fun showLanguagePicker() {
        val items = Languages.all
        val names = items.map { it.name }
        val current = items.indexOfFirst { it.code == settings.language }.coerceAtLeast(0)
        showSingleChoicePicker(R.string.language_title, names, current) { which ->
            settings.language = items[which].code
            refreshStatus()
        }
    }

    /** Papelera: cancela la descarga en curso o borra el modelo ya descargado. */
    private fun deleteCurrentModel() {
        clearPendingDownload()
        modelStore.delete(ModelCatalog.byId(settings.modelId))
        refreshStatus()
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null),
                ),
            )
        }
    }

    companion object {
        private const val REQUEST_RECORD_AUDIO = 10
        private const val BUTTON_HEIGHT_DP = 52
    }
}
