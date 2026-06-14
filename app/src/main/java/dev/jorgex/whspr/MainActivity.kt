package dev.jorgex.whspr

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var settings: AppSettings
    private lateinit var modelStore: ModelStore
    private lateinit var status: TextView
    private lateinit var modelButton: Button
    private lateinit var languageButton: Button
    private lateinit var permissionButton: Button
    private lateinit var downloadButton: Button
    private var statusRequest = 0
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settings = AppSettings(this)
        modelStore = ModelStore(this)
        val inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager

        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 26f
            gravity = Gravity.CENTER
        }

        val description = TextView(this).apply {
            text = getString(R.string.home_description)
            textSize = 16f
            gravity = Gravity.CENTER
        }

        status = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
        }

        modelButton = Button(this).apply {
            setOnClickListener {
                val next = nextModel()
                if (next.id != settings.modelId) {
                    clearPendingDownload()
                    settings.modelId = next.id
                }
                refreshStatus()
            }
        }

        languageButton = Button(this).apply {
            setOnClickListener {
                settings.language = if (settings.language == AppSettings.LANGUAGE_SPANISH) {
                    AppSettings.LANGUAGE_AUTO
                } else {
                    AppSettings.LANGUAGE_SPANISH
                }
                refreshStatus()
            }
        }

        permissionButton = Button(this).apply {
            text = getString(R.string.allow_microphone)
            setOnClickListener {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            }
        }

        downloadButton = Button(this).apply {
            text = getString(R.string.download_model)
            setOnClickListener {
                val model = ModelCatalog.byId(settings.modelId)
                if (modelState(model) != UiModelState.Downloading) {
                    clearPendingDownload()
                    val downloadId = modelStore.download(model)
                    if (downloadId > 0L) {
                        settings.pendingModelId = model.id
                        settings.pendingDownloadId = downloadId
                    } else {
                        clearPendingDownload()
                        Toast.makeText(this@MainActivity, R.string.error_download_start_failed, Toast.LENGTH_SHORT).show()
                    }
                    refreshStatus()
                }
            }
        }

        val enableButton = Button(this).apply {
            text = getString(R.string.enable_keyboard)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
        }

        val voiceSettingsButton = Button(this).apply {
            text = getString(R.string.voice_input_settings)
            setOnClickListener {
                openVoiceInputSettings()
            }
        }

        val switchButton = Button(this).apply {
            text = getString(R.string.switch_keyboard)
            setOnClickListener {
                inputMethodManager.showInputMethodPicker()
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(40, 40, 40, 40)
            addView(title)
            addView(description)
            addView(status)
            addView(modelButton)
            addView(languageButton)
            addView(permissionButton)
            addView(downloadButton)
            addView(enableButton)
            addView(voiceSettingsButton)
            addView(switchButton)
        }

        val palette = WhsprColors.forContext(this)
        title.setTextColor(palette.textPrimary)
        description.setTextColor(palette.textMuted)
        status.setTextColor(palette.textPrimary)
        root.setBackgroundColor(palette.background)
        for (i in 0 until root.childCount) {
            (root.getChildAt(i) as? Button)?.let { styleButton(it) }
        }

        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(palette.background)
                addView(root)
            },
        )
        refreshStatus()
    }

    private fun styleButton(button: Button) {
        button.isAllCaps = false
        button.setTextColor(buttonTextColors())
        button.background = buttonBackground()
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (52 * resources.displayMetrics.density).toInt(),
        )
        params.setMargins(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
        button.layoutParams = params
    }

    private fun buttonBackground(): Drawable {
        val palette = WhsprColors.forContext(this)
        val shape = GradientDrawable().apply {
            cornerRadius = 14 * resources.displayMetrics.density
            setColor(palette.surface)
            setStroke(resources.displayMetrics.density.toInt(), palette.surfaceStroke)
        }
        val ripple = (palette.accent and 0x00FFFFFF) or 0x40000000
        return RippleDrawable(ColorStateList.valueOf(ripple), shape, null)
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

    override fun onResume() {
        super.onResume()
        refreshStatus()
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
        if (requestCode == REQUEST_RECORD_AUDIO) refreshStatus()
    }

    private fun refreshStatus() {
        val model = ModelCatalog.byId(settings.modelId)
        val micReady = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val request = ++statusRequest

        modelButton.text = getString(R.string.selected_model, model.label, model.sizeLabel)
        languageButton.text = getString(
            R.string.selected_language,
            if (settings.language == AppSettings.LANGUAGE_SPANISH) {
                getString(R.string.language_spanish)
            } else {
                getString(R.string.language_auto)
            },
        )
        status.text = getString(
            R.string.status,
            if (micReady) getString(R.string.ready) else getString(R.string.missing),
            getString(R.string.checking),
        )
        permissionButton.isEnabled = !micReady
        permissionButton.text = if (micReady) getString(R.string.microphone_allowed) else getString(R.string.allow_microphone)
        downloadButton.isEnabled = false
        downloadButton.text = getString(R.string.checking)

        Thread({
            val state = modelState(model)
            refreshHandler.post {
                if (request != statusRequest) return@post
                applyModelState(micReady, state)
            }
        }, "whspr-status").start()
    }

    private fun applyModelState(micReady: Boolean, modelState: UiModelState) {
        status.text = getString(
            R.string.status,
            if (micReady) getString(R.string.ready) else getString(R.string.missing),
            labelFor(modelState),
        )
        downloadButton.isEnabled = modelState != UiModelState.Ready && modelState != UiModelState.Downloading
        downloadButton.text = when (modelState) {
            UiModelState.Ready -> getString(R.string.model_downloaded)
            UiModelState.Downloading -> getString(R.string.downloading)
            else -> getString(R.string.download_model)
        }

        refreshHandler.removeCallbacks(refreshRunnable)
        if (modelState == UiModelState.Downloading) {
            refreshHandler.postDelayed(refreshRunnable, 1_000)
        }
    }

    private fun nextModel(): SpeechModel {
        val models = ModelCatalog.models
        val current = models.indexOfFirst { it.id == settings.modelId }
        if (current < 0) return ModelCatalog.default
        return models[(current + 1) % models.size]
    }

    private fun clearPendingDownload() {
        val pendingModel = settings.pendingModelId?.let(ModelCatalog::findById)
        modelStore.cancelDownload(settings.pendingDownloadId)
        if (pendingModel != null) {
            modelStore.deleteUnready(pendingModel)
        }
        settings.clearPendingDownload()
    }

    private fun modelState(model: SpeechModel): UiModelState {
        if (settings.pendingModelId == model.id) {
            return when (modelStore.downloadStatus(settings.pendingDownloadId)) {
                ModelDownloadStatus.Running -> UiModelState.Downloading
                ModelDownloadStatus.Success -> {
                    settings.clearPendingDownload()
                    if (modelStore.isReady(model)) {
                        UiModelState.Ready
                    } else {
                        modelStore.delete(model)
                        UiModelState.Failed
                    }
                }
                ModelDownloadStatus.Failed -> {
                    settings.clearPendingDownload()
                    modelStore.deleteUnready(model)
                    UiModelState.Failed
                }
                ModelDownloadStatus.None -> {
                    settings.clearPendingDownload()
                    modelStore.deleteUnready(model)
                    UiModelState.Missing
                }
            }
        }

        if (modelStore.isReady(model)) {
            return UiModelState.Ready
        }

        modelStore.deleteUnready(model)
        return UiModelState.Missing
    }

    private fun labelFor(state: UiModelState): String {
        return when (state) {
            UiModelState.Ready -> getString(R.string.ready)
            UiModelState.Missing -> getString(R.string.missing)
            UiModelState.Downloading -> getString(R.string.downloading)
            UiModelState.Failed -> getString(R.string.failed)
        }
    }

    private fun openVoiceInputSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        }.onFailure {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
    }

    companion object {
        private const val REQUEST_RECORD_AUDIO = 10
    }
}

private enum class UiModelState {
    Ready,
    Missing,
    Downloading,
    Failed,
}
