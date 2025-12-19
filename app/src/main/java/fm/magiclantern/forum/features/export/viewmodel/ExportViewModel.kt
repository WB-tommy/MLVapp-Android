package fm.magiclantern.forum.features.export.viewmodel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fm.magiclantern.forum.data.repository.FocusPixelRequirement
import fm.magiclantern.forum.domain.model.ClipPreview
import fm.magiclantern.forum.domain.model.RawCorrectionSettings
import fm.magiclantern.forum.features.clips.viewmodel.ClipListViewModel
import fm.magiclantern.forum.features.export.ExportPreferences
import fm.magiclantern.forum.features.export.ExportService
import fm.magiclantern.forum.features.grading.viewmodel.GradingViewModel
import fm.magiclantern.forum.features.export.model.CdngNaming
import fm.magiclantern.forum.features.export.model.CdngVariant
import fm.magiclantern.forum.features.export.model.DebayerQuality
import fm.magiclantern.forum.features.export.model.DnxhdProfile
import fm.magiclantern.forum.features.export.model.DnxhrProfile
import fm.magiclantern.forum.features.export.model.ExportClipPayload
import fm.magiclantern.forum.features.export.model.ExportCodec
import fm.magiclantern.forum.features.export.model.ExportSettings
import fm.magiclantern.forum.features.export.model.FrameRatePreset
import fm.magiclantern.forum.features.export.model.H264Container
import fm.magiclantern.forum.features.export.model.H264Quality
import fm.magiclantern.forum.features.export.model.H265BitDepth
import fm.magiclantern.forum.features.export.model.H265Container
import fm.magiclantern.forum.features.export.model.H265Quality
import fm.magiclantern.forum.features.export.model.PngBitDepth
import fm.magiclantern.forum.features.export.model.ProResEncoder
import fm.magiclantern.forum.features.export.model.ProResProfile
import fm.magiclantern.forum.features.export.model.SmoothingOption
import fm.magiclantern.forum.features.export.model.Vp9Quality
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
    private val clipListViewModel: ClipListViewModel,
    private val gradingViewModel: GradingViewModel,
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
            binder?.getService()?.status
                ?: flowOf<ExportService.ExportStatus>(ExportService.ExportStatus.Idle)
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ExportService.ExportStatus.Idle
        )

    private var currentServiceConnection: ServiceConnection? = null
    private var boundContext: Context? = null

    private fun resetPendingExportData() {
        pendingExportClips = emptyList()
        pendingExportPayload = emptyList()
        pendingOutputDirectory = null
    }

    private fun clipDisplayName(clip: ClipPreview): String {
        if (clip.displayName.isNotBlank()) return clip.displayName
        val fromFile = clip.fileNames.firstOrNull()?.substringBeforeLast('.', "")
        if (!fromFile.isNullOrBlank()) return fromFile
        return "clip_${clip.guid}"
    }

    private var pendingExportClips: List<ClipPreview> = emptyList()
    private var pendingExportPayload: List<ExportClipPayload> = emptyList()
    private var pendingOutputDirectory: Uri? = null

    init {
        viewModelScope.launch {
            clipListViewModel.uiState.collect { clipState ->
                _uiState.update { current ->
                    val availableIds = clipState.clips.map { it.guid }.toSet()
                    val filteredSelection =
                        current.selectedClips.filter { it in availableIds }.toSet()
                    // Use ClipPreviews directly
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

        viewModelScope.launch {
            exportStatus.collect { status ->
                if (status is ExportService.ExportStatus.Completed) {
                    resetPendingExportData()
                    _uiState.update { current ->
                        current.copy(
                            selectedClips = emptySet()
                        )
                    }
                }
            }
        }
    }

    fun onSelectionNextRequested() {
        val selectedIds = uiState.value.selectedClips
        if (selectedIds.isEmpty()) {
            return
        }

        val clipsForExport = uiState.value.clips.filter { it.guid in selectedIds }
        if (clipsForExport.isEmpty()) {
            return
        }

        if (uiState.value.isFocusPixelCheckInProgress || uiState.value.isFocusPixelDownloadInProgress) {
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isFocusPixelCheckInProgress = true,
                    focusPixelPromptStage = FocusPixelPromptStage.SELECTION,
                    navigateToExportSettings = false
                )
            }

            val exportPayload = buildExportPayload(clipsForExport)
            if (exportPayload.isEmpty()) {
                _uiState.update { current ->
                    current.copy(
                        isFocusPixelCheckInProgress = false,
                        focusPixelRequirements = emptyList(),
                        focusPixelPromptStage = null
                    )
                }
                resetPendingExportData()
                return@launch
            }

            pendingExportClips = clipsForExport
            pendingExportPayload = exportPayload
            pendingOutputDirectory = null

            val missingMaps = clipListViewModel.findMissingFocusPixelMapsForExport(clipsForExport)
            if (missingMaps.isNotEmpty()) {
                val requirements = missingMaps.mapNotNull { requirement: FocusPixelRequirement ->
                    val clip = clipsForExport.firstOrNull { it.guid == requirement.clipGuid }
                        ?: return@mapNotNull null
                    FocusPixelExportRequirement(
                        clipGuid = clip.guid,
                        clipName = clipDisplayName(clip),
                        requiredFile = requirement.requiredFile
                    )
                }

                _uiState.update {
                    it.copy(
                        isFocusPixelCheckInProgress = false,
                        focusPixelRequirements = requirements,
                        isFocusPixelDownloadInProgress = false,
                        focusPixelPromptStage = FocusPixelPromptStage.SELECTION,
                        navigateToExportSettings = false
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    isFocusPixelCheckInProgress = false,
                    focusPixelRequirements = emptyList(),
                    isFocusPixelDownloadInProgress = false,
                    focusPixelPromptStage = null,
                    navigateToExportSettings = true
                )
            }
        }
    }

    fun startExport(context: Context) {
        val outputDirectory = uiState.value.outputDirectory ?: return

        // Try to use cached payload first
        var exportPayload = pendingExportPayload

        // If no cached payload, rebuild from current selection
        if (exportPayload.isEmpty()) {
            val selectedIds = uiState.value.selectedClips
            if (selectedIds.isEmpty()) {
                Log.w("ExportViewModel", "No clips selected for export.")
                return
            }

            val clipsForExport = uiState.value.clips.filter { it.guid in selectedIds }
            exportPayload = buildExportPayload(clipsForExport)

            if (exportPayload.isEmpty()) {
                Log.w("ExportViewModel", "Failed to build export payload.")
                return
            }
        }

        // Launch export with payload (either cached or rebuilt)
        launchExport(context, exportPayload, outputDirectory)
        resetPendingExportData()
    }

    private fun buildExportPayload(clips: List<ClipPreview>): List<ExportClipPayload> = buildList {
        // Get all grading data for export
        val allGrading = gradingViewModel.getAllGradingForExport()

        for (clip in clips) {
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

            val primaryFileName = sortedPairs.firstOrNull()?.second
                ?: clip.fileNames.firstOrNull()
                ?: continue

            // Look up grading for this clip
            val grading = allGrading[clip.guid]
            val rawCorrection = grading?.rawCorrection ?: RawCorrectionSettings()
            // Use clip's debayer mode from grading, or default to AMAZE
            val debayerMode = grading?.debayerMode ?: fm.magiclantern.forum.domain.model.DebayerAlgorithm.AMAZE

            add(
                ExportClipPayload(
                    displayName = clipDisplayName(clip),
                    primaryFileName = primaryFileName,
                    uris = uris,
                    stretchFactorX = clip.stretchFactorX,
                    stretchFactorY = clip.stretchFactorY,
                    debayerMode = debayerMode,
                    rawCorrection = rawCorrection
                )
            )
        }
    }

    private fun launchExport(
        context: Context,
        payload: List<ExportClipPayload>,
        outputDirectory: Uri
    ) {
        if (payload.isEmpty()) {
            Log.w("ExportViewModel", "launchExport: payload is empty, aborting")
            return
        }

        val appContext = context.applicationContext
        val intent = Intent(appContext, ExportService::class.java).apply {
            putParcelableArrayListExtra(
                ExportService.EXTRA_EXPORT_CLIPS,
                ArrayList(payload)
            )
            putExtra(ExportService.EXTRA_OUTPUT_DIRECTORY_URI, outputDirectory)
            putExtra(ExportService.EXTRA_TOTAL_MEMORY, totalMemory)
            putExtra(ExportService.EXTRA_CPU_CORES, cpuCores)
            putExtra(ExportService.EXTRA_EXPORT_SETTINGS, uiState.value.settings)

            // Grant read permission for clip URIs
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        // Grant URI permissions for each clip URI
        payload.flatMap { it.uris }.forEach { uri ->
            context.grantUriPermission(
                appContext.packageName,
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
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
                    _uiState.update {
                        it.copy(
                            isExporting = false,
                            exportProgress = 0f,
                            navigateToProgress = false
                        )
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                _serviceConnection.value = null
                _uiState.update { it.copy(isExporting = false, navigateToProgress = false) }
            }

            override fun onBindingDied(name: ComponentName?) {
                _serviceConnection.value = null
                _uiState.update { it.copy(isExporting = false, navigateToProgress = false) }
            }

            override fun onNullBinding(name: ComponentName?) {
                _serviceConnection.value = null
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        exportProgress = 0f,
                        navigateToProgress = false
                    )
                }
            }
        }

        boundContext = appContext
        val bound = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (bound) {
            currentServiceConnection = connection
            _uiState.update {
                it.copy(
                    isExporting = true,
                    exportProgress = 0f,
                    navigateToProgress = true,
                    focusPixelRequirements = emptyList(),
                    isFocusPixelDownloadInProgress = false
                )
            }
        } else {
            boundContext = null
            _serviceConnection.value = null
            _uiState.update { it.copy(isExporting = false, navigateToProgress = false) }
        }
    }

    fun downloadMissingFocusPixelMaps(context: Context) {
        when (uiState.value.focusPixelPromptStage) {
            FocusPixelPromptStage.SELECTION -> {
                val clipsSnapshot = pendingExportClips
                if (clipsSnapshot.isEmpty()) return
                val requirementsSnapshot = uiState.value.focusPixelRequirements
                viewModelScope.launch {
                    downloadFocusPixelMapsForSelection(requirementsSnapshot, clipsSnapshot)
                }
            }

            FocusPixelPromptStage.EXPORT -> {
                val payloadSnapshot = pendingExportPayload
                val outputDirectorySnapshot = pendingOutputDirectory
                val clipsSnapshot = pendingExportClips
                if (payloadSnapshot.isEmpty() || outputDirectorySnapshot == null || clipsSnapshot.isEmpty()) {
                    return
                }
                viewModelScope.launch {
                    downloadFocusPixelMapsForExport(
                        context = context,
                        requirementsSnapshot = uiState.value.focusPixelRequirements,
                        payloadSnapshot = payloadSnapshot,
                        outputDirectorySnapshot = outputDirectorySnapshot,
                        clipsSnapshot = clipsSnapshot
                    )
                }
            }

            null -> Unit
        }
    }

    private suspend fun downloadFocusPixelMapsForSelection(
        requirementsSnapshot: List<FocusPixelExportRequirement>,
        clipsSnapshot: List<ClipPreview>
    ) {
        if (requirementsSnapshot.isEmpty()) {
            _uiState.update {
                it.copy(
                    focusPixelRequirements = emptyList(),
                    isFocusPixelDownloadInProgress = false,
                    focusPixelPromptStage = null,
                    navigateToExportSettings = true
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                isFocusPixelDownloadInProgress = true,
                navigateToExportSettings = false
            )
        }

        val grouped = requirementsSnapshot.groupBy { it.requiredFile }
        for ((fileName, _) in grouped) {
            clipListViewModel.downloadFocusPixelMapForExport(fileName)
            // Note: focus pixel refresh will happen when clip is loaded for export
        }

        val remaining = clipListViewModel.findMissingFocusPixelMapsForExport(clipsSnapshot)
        if (remaining.isEmpty()) {
            _uiState.update {
                it.copy(
                    focusPixelRequirements = emptyList(),
                    isFocusPixelDownloadInProgress = false,
                    focusPixelPromptStage = null,
                    navigateToExportSettings = true
                )
            }
        } else {
            val updatedRequirements = remaining.mapNotNull { requirement: FocusPixelRequirement ->
                val clip = clipsSnapshot.firstOrNull { it.guid == requirement.clipGuid }
                    ?: return@mapNotNull null
                FocusPixelExportRequirement(
                    clipGuid = clip.guid,
                    clipName = clipDisplayName(clip),
                    requiredFile = requirement.requiredFile
                )
            }
            _uiState.update {
                it.copy(
                    focusPixelRequirements = updatedRequirements,
                    isFocusPixelDownloadInProgress = false,
                    focusPixelPromptStage = FocusPixelPromptStage.SELECTION,
                    navigateToExportSettings = false
                )
            }
        }
    }

    private suspend fun downloadFocusPixelMapsForExport(
        context: Context,
        requirementsSnapshot: List<FocusPixelExportRequirement>,
        payloadSnapshot: List<ExportClipPayload>,
        outputDirectorySnapshot: Uri,
        clipsSnapshot: List<ClipPreview>
    ) {
        if (requirementsSnapshot.isEmpty()) {
            _uiState.update {
                it.copy(
                    focusPixelRequirements = emptyList(),
                    isFocusPixelDownloadInProgress = false,
                    focusPixelPromptStage = null,
                    navigateToProgress = false
                )
            }
            launchExport(context, payloadSnapshot, outputDirectorySnapshot)
            resetPendingExportData()
            return
        }

        _uiState.update {
            it.copy(
                isFocusPixelDownloadInProgress = true,
                navigateToProgress = false
            )
        }

        val grouped = requirementsSnapshot.groupBy { it.requiredFile }
        for ((fileName, _) in grouped) {
            clipListViewModel.downloadFocusPixelMapForExport(fileName)
            // Note: focus pixel refresh will happen when clip is loaded for export
        }

        val remaining = clipListViewModel.findMissingFocusPixelMapsForExport(clipsSnapshot)
        if (remaining.isEmpty()) {
            _uiState.update {
                it.copy(
                    focusPixelRequirements = emptyList(),
                    isFocusPixelDownloadInProgress = false,
                    focusPixelPromptStage = null,
                    navigateToProgress = false
                )
            }
            launchExport(context, payloadSnapshot, outputDirectorySnapshot)
            resetPendingExportData()
        } else {
            val updatedRequirements = remaining.mapNotNull { requirement: FocusPixelRequirement ->
                val clip = clipsSnapshot.firstOrNull { it.guid == requirement.clipGuid }
                    ?: return@mapNotNull null
                FocusPixelExportRequirement(
                    clipGuid = clip.guid,
                    clipName = clipDisplayName(clip),
                    requiredFile = requirement.requiredFile
                )
            }
            _uiState.update {
                it.copy(
                    focusPixelRequirements = updatedRequirements,
                    isFocusPixelDownloadInProgress = false,
                    focusPixelPromptStage = FocusPixelPromptStage.EXPORT,
                    navigateToProgress = false
                )
            }
        }
    }

    fun skipFocusPixelDownload(context: Context) {
        when (uiState.value.focusPixelPromptStage) {
            FocusPixelPromptStage.SELECTION -> {
                val clipsSnapshot = pendingExportClips
                if (clipsSnapshot.isEmpty()) return
                _uiState.update {
                    it.copy(
                        focusPixelRequirements = emptyList(),
                        isFocusPixelDownloadInProgress = false,
                        isFocusPixelCheckInProgress = false,
                        focusPixelPromptStage = null,
                        navigateToExportSettings = true
                    )
                }
            }

            FocusPixelPromptStage.EXPORT -> {
                val payloadSnapshot = pendingExportPayload
                val outputDirectorySnapshot = pendingOutputDirectory
                val clipsSnapshot = pendingExportClips
                if (payloadSnapshot.isEmpty() || outputDirectorySnapshot == null || clipsSnapshot.isEmpty()) {
                    return
                }
                _uiState.update {
                    it.copy(
                        focusPixelRequirements = emptyList(),
                        isFocusPixelDownloadInProgress = false,
                        isFocusPixelCheckInProgress = false,
                        focusPixelPromptStage = null,
                        navigateToProgress = false
                    )
                }
                launchExport(context, payloadSnapshot, outputDirectorySnapshot)
                resetPendingExportData()
            }

            null -> Unit
        }
    }

    fun cancelFocusPixelPrompt() {
        when (uiState.value.focusPixelPromptStage) {
            FocusPixelPromptStage.SELECTION -> {
                _uiState.update {
                    it.copy(
                        focusPixelRequirements = emptyList(),
                        isFocusPixelDownloadInProgress = false,
                        isFocusPixelCheckInProgress = false,
                        focusPixelPromptStage = null,
                        navigateToExportSettings = false
                    )
                }
            }

            FocusPixelPromptStage.EXPORT -> {
                _uiState.update {
                    it.copy(
                        focusPixelRequirements = emptyList(),
                        isFocusPixelDownloadInProgress = false,
                        isFocusPixelCheckInProgress = false,
                        focusPixelPromptStage = null,
                        navigateToProgress = false
                    )
                }
                resetPendingExportData()
            }

            null -> Unit
        }
    }

    fun onExportNavigationHandled() {
        _uiState.update { it.copy(navigateToProgress = false) }
    }

    fun onExportSettingsNavigationHandled() {
        _uiState.update { it.copy(navigateToExportSettings = false) }
    }

    fun cancelExport() {
        _serviceConnection.value?.getService()?.cancelExport()
    }

    fun toggleClipSelection(clip: ClipPreview) {
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

    fun selectAllClips() {
        _uiState.update { currentState ->
            currentState.copy(selectedClips = currentState.clips.map { it.guid }.toSet())
        }
    }

    fun deselectAllClips() {
        _uiState.update { currentState ->
            currentState.copy(selectedClips = emptySet())
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

    // H.264 options
    fun onH264QualitySelected(quality: H264Quality) {
        updateSettings { it.copy(h264Quality = quality) }
    }

    fun onH264ContainerSelected(container: H264Container) {
        updateSettings { it.copy(h264Container = container) }
    }

    // H.265 options
    fun onH265BitDepthSelected(bitDepth: H265BitDepth) {
        updateSettings { it.copy(h265BitDepth = bitDepth) }
    }

    fun onH265QualitySelected(quality: H265Quality) {
        updateSettings { it.copy(h265Quality = quality) }
    }

    fun onH265ContainerSelected(container: H265Container) {
        updateSettings { it.copy(h265Container = container) }
    }

    // PNG options
    fun onPngBitDepthSelected(bitDepth: PngBitDepth) {
        updateSettings { it.copy(pngBitDepth = bitDepth) }
    }

    // DNxHR options
    fun onDnxhrProfileSelected(profile: DnxhrProfile) {
        updateSettings { it.copy(dnxhrProfile = profile) }
    }

    // DNxHD options
    fun onDnxhdProfileSelected(profile: DnxhdProfile) {
        updateSettings { it.copy(dnxhdProfile = profile) }
    }

    // VP9 options
    fun onVp9QualitySelected(quality: Vp9Quality) {
        updateSettings { it.copy(vp9Quality = quality) }
    }

    fun onDebayerQualitySelected(quality: DebayerQuality) {
        updateSettings { it.copy(debayerQuality = quality) }
    }

    fun onSmoothingOptionSelected(option: SmoothingOption) {
        updateSettings { it.copy(smoothing = option) }
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

    fun onFrameRateOverrideEnabledChanged(enabled: Boolean) {
        updateSettings { settings ->
            settings.copy(frameRate = settings.frameRate.copy(enabled = enabled))
        }
    }

    fun onFrameRateChanged(fps: String) {
        val value = fps.toFloatOrNull()
        if (value != null && value >= 1.0f && value <= 60.0f) {
            updateSettings { settings ->
                settings.copy(frameRate = settings.frameRate.copy(value = value))
            }
        }
    }

    fun onHdrBlendingEnabledChanged(enabled: Boolean) {
        updateSettings { it.copy(hdrBlending = enabled) }
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

    // FPS override disables audio
    if (sanitized.frameRate.enabled) {
        sanitized = sanitized.copy(includeAudio = false)
    }

    // Audio-only forces audio on
    if (sanitized.codec == ExportCodec.AUDIO_ONLY) {
        sanitized = sanitized.copy(includeAudio = true)
    }

    // Smoothing disabled for codecs that don't support it
    if (!sanitized.allowsSmoothing) {
        sanitized = sanitized.copy(smoothing = SmoothingOption.OFF)
    }

    // HDR blending disabled for codecs that don't support it
    if (!sanitized.allowsHdrBlending) {
        sanitized = sanitized.copy(hdrBlending = false)
    }

    // Resize disabled for codecs that don't support it
    if (!sanitized.allowsResize) {
        sanitized = sanitized.copy(
            resize = sanitized.resize.copy(enabled = false)
        )
    }

    // Audio toggle not allowed forces audio on
    if (!sanitized.allowsAudioToggle) {
        sanitized = sanitized.copy(includeAudio = true)
    }

    // FPS override disabled for codecs that don't support it
    if (!sanitized.allowsFrameRateOverride) {
        sanitized = sanitized.copy(
            frameRate = sanitized.frameRate.copy(enabled = false)
        )
    }

    // Validate resize dimensions
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
    val clips: List<ClipPreview> = emptyList(),
    val selectedClips: Set<Long> = emptySet(),
    val isExporting: Boolean = false,
    val exportProgress: Float = 0f,
    val settings: ExportSettings = ExportSettings(),
    val outputDirectory: Uri? = null,
    val availableCodecs: List<ExportCodec> = ExportCodec.defaultOrder,
    val frameRatePresets: List<FrameRatePreset> = FrameRatePreset.values().toList(),
    val isFocusPixelCheckInProgress: Boolean = false,
    val focusPixelRequirements: List<FocusPixelExportRequirement> = emptyList(),
    val isFocusPixelDownloadInProgress: Boolean = false,
    val navigateToExportSettings: Boolean = false,
    val navigateToProgress: Boolean = false,
    val focusPixelPromptStage: FocusPixelPromptStage? = null
)

data class FocusPixelExportRequirement(
    val clipGuid: Long,
    val clipName: String,
    val requiredFile: String
)

enum class FocusPixelPromptStage {
    SELECTION,
    EXPORT
}
