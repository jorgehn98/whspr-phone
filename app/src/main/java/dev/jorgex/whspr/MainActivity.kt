package dev.jorgex.whspr

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ImageView
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
    private lateinit var permissionTrash: ImageView
    private lateinit var downloadTrash: ImageView
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
            setOnClickListener { showLanguagePicker() }
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

        val switchButton = Button(this).apply {
            text = getString(R.string.switch_keyboard)
            setOnClickListener {
                inputMethodManager.showInputMethodPicker()
            }
        }

        permissionTrash = trashIcon { openAppSettings() }
        downloadTrash = trashIcon { deleteCurrentModel() }
        val permissionRow = buttonRow(permissionButton, permissionTrash)
        val downloadRow = buttonRow(downloadButton, downloadTrash)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val side = (24 * resources.displayMetrics.density).toInt()
            val top = (72 * resources.displayMetrics.density).toInt()
            setPadding(side, top, side, side)
            addView(title)
            addView(description)
            addView(status)
            addView(modelButton)
            addView(languageButton)
            addView(permissionRow)
            addView(downloadRow)
            addView(enableButton)
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

    private fun decorateButton(button: Button) {
        button.isAllCaps = false
        button.setTextColor(buttonTextColors())
        button.background = buttonBackground()
    }

    private fun styleButton(button: Button) {
        decorateButton(button)
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (52 * resources.displayMetrics.density).toInt(),
        )
        params.setMargins(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
        button.layoutParams = params
    }

    private fun trashIcon(onClick: () -> Unit): ImageView {
        return ImageView(this).apply {
            setImageResource(R.drawable.ic_trash)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setColorFilter(WhsprColors.forContext(this@MainActivity).accent)
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            setOnClickListener { onClick() }
        }
    }

    private fun buttonRow(button: Button, trash: ImageView): LinearLayout {
        val d = resources.displayMetrics.density
        val height = (52 * d).toInt()
        decorateButton(button)
        button.layoutParams = LinearLayout.LayoutParams(0, height, 1f)
        val trashParams = LinearLayout.LayoutParams((44 * d).toInt(), height)
        trashParams.setMargins((8 * d).toInt(), 0, 0, 0)
        trash.layoutParams = trashParams
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val rowParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            rowParams.setMargins(0, (8 * d).toInt(), 0, 0)
            layoutParams = rowParams
            addView(button)
            addView(trash)
        }
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
        languageButton.text = getString(R.string.selected_language, Languages.nameFor(settings.language))
        status.text = getString(
            R.string.status,
            if (micReady) getString(R.string.ready) else getString(R.string.missing),
            getString(R.string.checking),
        )
        permissionButton.isEnabled = !micReady
        permissionButton.text = if (micReady) getString(R.string.microphone_allowed) else getString(R.string.allow_microphone)
        permissionTrash.visibility = if (micReady) View.VISIBLE else View.GONE
        downloadButton.isEnabled = false
        downloadButton.text = getString(R.string.checking)
        downloadTrash.visibility = View.GONE

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
        downloadTrash.visibility = if (modelState == UiModelState.Ready) View.VISIBLE else View.GONE

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

    private fun showLanguagePicker() {
        val items = Languages.all
        val names = items.map { it.name }.toTypedArray()
        val current = items.indexOfFirst { it.code == settings.language }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.language_title)
            .setSingleChoiceItems(names, current) { dialog, which ->
                settings.language = items[which].code
                dialog.dismiss()
                refreshStatus()
            }
            .show()
    }

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
    }
}

private enum class UiModelState {
    Ready,
    Missing,
    Downloading,
    Failed,
}
