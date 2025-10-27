package fm.forum.mlvapp.export

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fm.forum.mlvapp.clips.ClipViewModel
import fm.forum.mlvapp.data.Clip
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ExportViewModel(
    clipViewModel: ClipViewModel,
    private val totalMemory: Long,
    private val cpuCores: Int,
    private val exportPreferences: ExportPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ExportUiState(
            outputDirectory = exportPreferences.getLastOutputDirectory()
        )
    )
    val uiState: StateFlow<ExportUiState> = _uiState.asStateFlow()

    private val _serviceConnection = MutableStateFlow<ExportService.LocalBinder?>(null)

    val exportProgress: StateFlow<Float> = _serviceConnection
        .flatMapLatest { binder ->
            binder?.getService()?.progress ?: flowOf(0f)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0f)

    val exportStatus: StateFlow<ExportService.ExportStatus> = _serviceConnection
        .flatMapLatest { binder ->
            binder?.getService()?.status ?: flowOf<ExportService.ExportStatus>(ExportService.ExportStatus.Idle)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExportService.ExportStatus.Idle)

    private var currentServiceConnection: ServiceConnection? = null
    private var boundContext: Context? = null

    init {
        viewModelScope.launch {
            clipViewModel.uiState.collect { clipState ->
                _uiState.update { current ->
                    val availableIds = clipState.clips.map { it.guid }.toSet()
                    val filteredSelection = current.selectedClips.filter { it in availableIds }.toSet()
                    current.copy(
                        clips = clipState.clips,
                        selectedClips = filteredSelection
                    )
                }
            }
        }

        viewModelScope.launch {
            exportProgress.collect { progress ->
                val isBound = _serviceConnection.value != null
                _uiState.update { current ->
                    val exporting = when {
                        progress >= 1f -> false
                        isBound -> true
                        else -> current.isExporting
                    }
                    current.copy(
                        exportProgress = progress,
                        isExporting = exporting
                    )
                }
            }
        }
    }

    fun startExport(context: Context) {
        val selectedIds = uiState.value.selectedClips
        val outputDirectory = uiState.value.outputDirectory ?: return

        if (selectedIds.isEmpty()) {
            return
        }

        val clipsToExport = buildList {
            for (clip in uiState.value.clips) {
                if (clip.guid !in selectedIds) continue

                val pairs = if (clip.fileNames.size == clip.uris.size && clip.fileNames.isNotEmpty()) {
                    clip.uris.zip(clip.fileNames)
                } else {
                    clip.uris.map { uri -> uri to (uri.lastPathSegment ?: "") }
                }

                val sortedPairs = if (pairs.isNotEmpty()) {
                    pairs.sortedWith(compareBy { (_, fileName) ->
                        val extension = fileName.substringAfterLast('.', "")
                        if (extension.equals("MLV", ignoreCase = true)) {
                            "0"
                        } else {
                            extension
                        }
                    })
                } else {
                    emptyList()
                }

                val uris = sortedPairs.map { it.first }
                if (uris.isEmpty()) continue

                val display = clip.displayName.ifBlank {
                    clip.fileNames.firstOrNull()?.substringBeforeLast('.', "")?.ifBlank { null }
                        ?: "clip_${clip.guid}"
                }

                val primaryFileName = sortedPairs.firstOrNull()?.second
                    ?: clip.fileNames.firstOrNull()
                    ?: continue

                add(ExportClipPayload(display, primaryFileName, uris))
            }
        }

        if (clipsToExport.isEmpty()) {
            return
        }

        val appContext = context.applicationContext
        val intent = Intent(appContext, ExportService::class.java).apply {
            putParcelableArrayListExtra(
                ExportService.EXTRA_EXPORT_CLIPS,
                ArrayList(clipsToExport)
            )
            putExtra(ExportService.EXTRA_OUTPUT_DIRECTORY_URI, outputDirectory)
            putExtra(ExportService.EXTRA_TOTAL_MEMORY, totalMemory)
            putExtra(ExportService.EXTRA_CPU_CORES, cpuCores)
            putExtra(ExportService.EXTRA_EXPORT_SETTINGS, uiState.value.settings)
        }

        ContextCompat.startForegroundService(appContext, intent)

        currentServiceConnection?.let { connection ->
            boundContext?.let { bound ->
                runCatching { bound.unbindService(connection) }
            }
        }

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as? ExportService.LocalBinder
                _serviceConnection.value = binder
                if (binder == null) {
                    _uiState.update { it.copy(isExporting = false, exportProgress = 0f) }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                _serviceConnection.value = null
                _uiState.update { it.copy(isExporting = false) }
            }

            override fun onBindingDied(name: ComponentName?) {
                _serviceConnection.value = null
                _uiState.update { it.copy(isExporting = false) }
            }

            override fun onNullBinding(name: ComponentName?) {
                _serviceConnection.value = null
                _uiState.update { it.copy(isExporting = false, exportProgress = 0f) }
            }
        }

        boundContext = appContext
        val bound = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (bound) {
            currentServiceConnection = connection
            _uiState.update { it.copy(isExporting = true, exportProgress = 0f) }
        } else {
            boundContext = null
            _serviceConnection.value = null
            _uiState.update { it.copy(isExporting = false) }
        }
    }

    fun cancelExport() {
        _serviceConnection.value?.getService()?.cancelExport()
    }

    fun toggleClipSelection(clip: Clip) {
        _uiState.update { currentState ->
            val selectedClips = currentState.selectedClips.toMutableSet()
            if (selectedClips.contains(clip.guid)) {
                selectedClips.remove(clip.guid)
            } else {
                selectedClips.add(clip.guid)
            }
            currentState.copy(selectedClips = selectedClips)
        }
    }

    fun onCdngNamingSchemaSelected(schema: CdngNaming) {
        updateSettings { it.copy(cdngNaming = schema) }
    }

    fun onCdngVariantSelected(variant: CdngVariant) {
        updateSettings { it.copy(cdngVariant = variant) }
    }

    fun onCodecSelected(codec: ExportCodec) {
        updateSettings { it.copy(codec = codec) }
    }

    fun onProResProfileSelected(profile: ProResProfile) {
        updateSettings { it.copy(proResProfile = profile) }
    }

    fun onProResEncoderSelected(encoder: ProResEncoder) {
        updateSettings { it.copy(proResEncoder = encoder) }
    }

    fun onDebayerQualitySelected(quality: DebayerQuality) {
        updateSettings { it.copy(debayerQuality = quality) }
    }

    fun onSmoothingOptionSelected(option: SmoothingOption) {
        updateSettings { it.copy(smoothing = option) }
    }

    fun onScalingAlgorithmSelected(algorithm: ScalingAlgorithm) {
        updateSettings { settings ->
            settings.copy(resize = settings.resize.copy(algorithm = algorithm))
        }
    }

    fun onResizeEnabledChanged(enabled: Boolean) {
        updateSettings { settings ->
            settings.copy(resize = settings.resize.copy(enabled = enabled))
        }
    }

    fun onResizeWidthChanged(width: String) {
        val value = width.toIntOrNull()?.coerceAtLeast(1) ?: return
        updateSettings { settings ->
            settings.copy(resize = settings.resize.copy(width = value))
        }
    }

    fun onResizeHeightChanged(height: String) {
        val value = height.toIntOrNull()?.coerceAtLeast(1) ?: return
        updateSettings { settings ->
            settings.copy(resize = settings.resize.copy(height = value))
        }
    }

    fun onLockAspectRatioChanged(locked: Boolean) {
        updateSettings { settings ->
            settings.copy(resize = settings.resize.copy(lockAspectRatio = locked))
        }
    }

    fun onFrameRateOverrideEnabledChanged(enabled: Boolean) {
        updateSettings { settings ->
            settings.copy(frameRate = settings.frameRate.copy(enabled = enabled))
        }
    }

    fun onFrameRateSelected(preset: FrameRatePreset) {
        updateSettings { settings ->
            settings.copy(frameRate = settings.frameRate.copy(value = preset.value))
        }
    }

    fun onHdrBlendingEnabledChanged(enabled: Boolean) {
        updateSettings { it.copy(hdrBlending = enabled) }
    }

    fun onAntiAliasingEnabledChanged(enabled: Boolean) {
        updateSettings { it.copy(antiAliasing = enabled) }
    }

    fun onIncludeAudioChanged(enabled: Boolean) {
        updateSettings { settings ->
            if (!settings.allowsAudioToggle) settings else settings.copy(includeAudio = enabled)
        }
    }

    fun onOutputDirectorySelected(uri: Uri) {
        exportPreferences.setLastOutputDirectory(uri)
        _uiState.update { it.copy(outputDirectory = uri) }
    }

    private fun updateSettings(transform: (ExportSettings) -> ExportSettings) {
        _uiState.update { current ->
            val updated = sanitizeSettings(transform(current.settings))
            current.copy(settings = updated)
        }
    }

    private fun sanitizeSettings(settings: ExportSettings): ExportSettings {
        var sanitized = settings
        if (!sanitized.requiresRawProcessing) {
            sanitized = sanitized.copy(
                smoothing = SmoothingOption.OFF,
                hdrBlending = false,
                antiAliasing = false
            )
        }
        if (!sanitized.allowsResize) {
            sanitized = sanitized.copy(
                resize = sanitized.resize.copy(enabled = false),
                smoothing = SmoothingOption.OFF,
                hdrBlending = false,
                antiAliasing = false
            )
        }
        if (!sanitized.allowsAudioToggle) {
            sanitized = sanitized.copy(includeAudio = true)
        }
        if (!sanitized.allowsFrameRateOverride) {
            sanitized = sanitized.copy(
                frameRate = sanitized.frameRate.copy(enabled = false)
            )
        }
        if (sanitized.resize.width <= 0 || sanitized.resize.height <= 0) {
            sanitized = sanitized.copy(
                resize = sanitized.resize.copy(
                    width = sanitized.resize.width.coerceAtLeast(1),
                    height = sanitized.resize.height.coerceAtLeast(1)
                )
            )
        }
        return sanitized
    }

    override fun onCleared() {
        boundContext?.let { context ->
            currentServiceConnection?.let { connection ->
                runCatching { context.unbindService(connection) }
            }
        }
        currentServiceConnection = null
        boundContext = null
        _serviceConnection.value = null
        super.onCleared()
    }
}

data class ExportUiState(
    val clips: List<Clip> = emptyList(),
    val selectedClips: Set<Long> = emptySet(),
    val isExporting: Boolean = false,
    val exportProgress: Float = 0f,
    val settings: ExportSettings = ExportSettings(),
    val outputDirectory: Uri? = null,
    val availableCodecs: List<ExportCodec> = ExportCodec.defaultOrder,
    val frameRatePresets: List<FrameRatePreset> = FrameRatePreset.values().toList()
)
