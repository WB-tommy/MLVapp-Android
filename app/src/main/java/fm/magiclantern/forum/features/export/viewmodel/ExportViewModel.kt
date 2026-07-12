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
import fm.magiclantern.forum.domain.model.ClipPreview
import fm.magiclantern.forum.features.clips.viewmodel.ClipListViewModel
import fm.magiclantern.forum.features.export.ExportPreferences
import fm.magiclantern.forum.features.export.ExportRequestBuilder
import fm.magiclantern.forum.features.export.ExportService
import fm.magiclantern.forum.features.export.FocusPixelPreflightCoordinator
import fm.magiclantern.forum.features.export.OutputDirectoryValidator
import fm.magiclantern.forum.features.export.exportFailureMessage
import fm.magiclantern.forum.features.export.sanitized
import fm.magiclantern.forum.features.grading.viewmodel.GradingViewModel
import fm.magiclantern.forum.features.export.model.CdngNaming
import fm.magiclantern.forum.features.export.model.CdngVariant
import fm.magiclantern.forum.features.export.model.DebayerQuality
import fm.magiclantern.forum.features.export.model.DnxhdProfile
import fm.magiclantern.forum.features.export.model.DnxhrProfile
import fm.magiclantern.forum.features.export.model.ExportCodec
import fm.magiclantern.forum.features.export.model.ExportRequest
import fm.magiclantern.forum.features.export.model.ExportSettings
import fm.magiclantern.forum.features.export.model.ExportDraft
import fm.magiclantern.forum.features.export.model.FrameRatePreset
import fm.magiclantern.forum.features.export.model.FocusPixelExportRequirement
import fm.magiclantern.forum.features.export.model.FocusPixelPreflightResult
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
    private val cacheSizeMiB: Long,
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
    private val _serviceStatusFallback =
        MutableStateFlow<ExportService.ExportStatus>(ExportService.ExportStatus.Idle)

    val exportProgress: StateFlow<Float> = _serviceConnection
        .flatMapLatest { binder ->
            binder?.getService()?.progress ?: flowOf(0f)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0f)

    val exportStatus: StateFlow<ExportService.ExportStatus> = _serviceConnection
        .flatMapLatest { binder ->
            binder?.getService()?.status
                ?: _serviceStatusFallback
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ExportService.ExportStatus.Idle
        )

    private var currentServiceConnection: ServiceConnection? = null
    private var boundContext: Context? = null

    private val focusPixelPreflightCoordinator = FocusPixelPreflightCoordinator(
        findMissing = clipListViewModel::findMissingFocusPixelMapsForExport,
        downloadMap = clipListViewModel::downloadFocusPixelMapForExport
    )

    private var currentDraft: ExportDraft? = null

    private fun resetExportDraft() {
        currentDraft = null
    }

    private fun focusPixelRequirementsFor(draft: ExportDraft?): List<FocusPixelExportRequirement> {
        return when (val preflight = draft?.fpmPreflight) {
            is FocusPixelPreflightResult.Missing -> preflight.requirements
            else -> emptyList()
        }
    }

    private fun focusPixelDownloadFailuresFor(draft: ExportDraft?): List<String> {
        return when (val preflight = draft?.fpmPreflight) {
            is FocusPixelPreflightResult.Missing -> preflight.failedDownloads
            else -> emptyList()
        }
    }

    private fun focusPixelPromptStageFor(draft: ExportDraft?): FocusPixelPromptStage? {
        return if (draft?.fpmPreflight is FocusPixelPreflightResult.Missing) {
            FocusPixelPromptStage.SELECTION
        } else {
            null
        }
    }

    private fun ExportUiState.withFocusPixelStateFrom(
        draft: ExportDraft?
    ): ExportUiState = copy(
        focusPixelRequirements = focusPixelRequirementsFor(draft),
        focusPixelDownloadFailures = focusPixelDownloadFailuresFor(draft),
        focusPixelPromptStage = focusPixelPromptStageFor(draft)
    )

    init {
        viewModelScope.launch {
            clipListViewModel.uiState.collect { clipState ->
                _uiState.update { current ->
                    val availableIds = clipState.clips.map { it.guid }.toSet()
                    val filteredSelection =
                        current.selectedClips.filter { it in availableIds }.toSet()
                    val selectionChanged = filteredSelection != current.selectedClips
                    if (selectionChanged) {
                        resetExportDraft()
                    }
                    // Use ClipPreviews directly
                    current.copy(
                        clips = clipState.clips,
                        selectedClips = filteredSelection,
                        focusPixelRequirements = focusPixelRequirementsFor(currentDraft),
                        focusPixelDownloadFailures = focusPixelDownloadFailuresFor(currentDraft),
                        focusPixelPromptStage = focusPixelPromptStageFor(currentDraft),
                        isFocusPixelCheckInProgress = if (selectionChanged) {
                            false
                        } else {
                            current.isFocusPixelCheckInProgress
                        },
                        isFocusPixelDownloadInProgress = if (selectionChanged) {
                            false
                        } else {
                            current.isFocusPixelDownloadInProgress
                        },
                        focusPixelPreflightError = if (selectionChanged) {
                            null
                        } else {
                            current.focusPixelPreflightError
                        },
                        navigateToExportSettings = if (selectionChanged) {
                            false
                        } else {
                            current.navigateToExportSettings
                        }
                    )
                }
            }
        }

        viewModelScope.launch {
            exportProgress.collect { progress ->
                _uiState.update { current ->
                    current.copy(exportProgress = progress)
                }
            }
        }

        viewModelScope.launch {
            exportStatus.collect { status ->
                when (status) {
                    is ExportService.ExportStatus.Completed -> {
                        if (shouldClearExportDraft(status)) {
                            resetExportDraft()
                        }
                        _uiState.update { current ->
                            current.copy(
                                selectedClips = emptySet(),
                                isExporting = false,
                                exportProgress = 1f
                            )
                        }
                    }

                    is ExportService.ExportStatus.Cancelled,
                    is ExportService.ExportStatus.Failed,
                    ExportService.ExportStatus.Idle -> {
                        _uiState.update { it.copy(isExporting = false) }
                    }

                    is ExportService.ExportStatus.Running -> {
                        _uiState.update { it.copy(isExporting = true) }
                    }
                }
            }
        }
    }

    fun onSelectionNextRequested() {
        val clipsForExport = selectedClipsForExport()
        if (clipsForExport.isEmpty()) {
            return
        }

        if (uiState.value.isFocusPixelCheckInProgress || uiState.value.isFocusPixelDownloadInProgress) {
            return
        }

        val draft = ExportDraft(
            selectedClips = clipsForExport,
            settings = uiState.value.settings,
            outputDirectory = uiState.value.outputDirectory
        )
        currentDraft = draft

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isFocusPixelCheckInProgress = true,
                    focusPixelPromptStage = focusPixelPromptStageFor(draft),
                    focusPixelPreflightError = null,
                    navigateToExportSettings = false
                )
            }

            val result = try {
                focusPixelPreflightCoordinator.check(clipsForExport)
            } catch (throwable: Throwable) {
                handleFocusPixelPreflightFailure(draft, throwable)
                return@launch
            }
            applyFocusPixelPreflightResult(
                draft = draft,
                result = result
            )
        }
    }

    private fun selectedClipsForExport(): List<ClipPreview> {
        val selectedIds = uiState.value.selectedClips
        if (selectedIds.isEmpty()) return emptyList()
        return uiState.value.clips.filter { it.guid in selectedIds }
    }

    private fun applyFocusPixelPreflightResult(
        draft: ExportDraft,
        result: FocusPixelPreflightResult
    ) {
        if (currentDraft !== draft) return
        val updatedDraft = draft.copy(fpmPreflight = result)
        currentDraft = updatedDraft

        when (result) {
            FocusPixelPreflightResult.Ready,
            is FocusPixelPreflightResult.Skipped -> {
                _uiState.update {
                    it.withFocusPixelStateFrom(updatedDraft).copy(
                        isFocusPixelCheckInProgress = false,
                        isFocusPixelDownloadInProgress = false,
                        focusPixelPreflightError = null,
                        navigateToExportSettings = true
                    )
                }
            }

            is FocusPixelPreflightResult.Missing -> {
                _uiState.update {
                    it.withFocusPixelStateFrom(updatedDraft).copy(
                        isFocusPixelCheckInProgress = false,
                        isFocusPixelDownloadInProgress = false,
                        focusPixelPreflightError = null,
                        navigateToExportSettings = false
                    )
                }
            }

            FocusPixelPreflightResult.Unchecked -> {
                _uiState.update {
                    it.withFocusPixelStateFrom(updatedDraft).copy(
                        isFocusPixelCheckInProgress = false,
                        isFocusPixelDownloadInProgress = false,
                        focusPixelPreflightError = null,
                        navigateToExportSettings = false
                    )
                }
            }
        }
    }

    fun onSettingsNextRequested(): Boolean {
        val draft = currentDraft
        if (draft == null || !draft.canConfigureSettings) {
            Log.w(TAG, "Cannot continue to location without a prepared export draft.")
            return false
        }

        currentDraft = draft.copy(
            settings = uiState.value.settings,
            gradingSnapshot = gradingViewModel.getAllGradingForExport()
        )
        return true
    }

    fun startExport(context: Context) {
        if (uiState.value.isExporting) {
            Log.w(TAG, "Export is already running.")
            return
        }
        val draft = currentDraft
        val outputDirectory = draft?.outputDirectory ?: uiState.value.outputDirectory ?: return
        if (uiState.value.outputDirectoryError != null) {
            Log.w(TAG, "Cannot start export while output directory has an unresolved error.")
            return
        }
        OutputDirectoryValidator.validationError(context, outputDirectory)?.let { message ->
            _uiState.update { it.copy(outputDirectoryError = message) }
            return
        }
        val readyDraft = draft?.copy(outputDirectory = outputDirectory)
        if (readyDraft == null || !readyDraft.canStartExport) {
            Log.w(TAG, "No prepared export draft is ready to start.")
            return
        }

        val request = ExportRequestBuilder.build(
            draft = readyDraft,
            cacheSizeMiB = cacheSizeMiB,
            cpuCores = cpuCores
        )
        if (request == null) {
            Log.w(TAG, "Failed to build export request.")
            return
        }

        // Failed and cancelled attempts reuse this immutable snapshot when the user retries.
        currentDraft = readyDraft
        launchExport(
            context = context,
            request = request
        )
    }

    fun validateCurrentOutputDirectory(context: Context) {
        val outputDirectory = uiState.value.outputDirectory ?: return
        val validationError = OutputDirectoryValidator.validationError(context, outputDirectory)
        _uiState.update { current ->
            if (current.outputDirectory == outputDirectory) {
                current.copy(outputDirectoryError = validationError)
            } else {
                current
            }
        }
    }

    private fun launchExport(context: Context, request: ExportRequest) {
        if (request.clips.isEmpty()) {
            Log.w(TAG, "launchExport: request has no clips, aborting")
            return
        }

        val appContext = context.applicationContext
        val intent = Intent(appContext, ExportService::class.java).apply {
            putExtra(ExportService.EXTRA_EXPORT_REQUEST, request)

            // Keep the read grant on the service start intent as a fallback.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        _uiState.update {
            it.copy(
                isExporting = true,
                exportProgress = 0f,
                outputDirectoryError = null,
                exportStartError = null
            )
        }
        _serviceStatusFallback.value = ExportService.ExportStatus.Idle

        try {
            request.clips
                .flatMap { it.uris }
                .distinct()
                .forEach { uri ->
                    context.grantUriPermission(
                        appContext.packageName,
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            ContextCompat.startForegroundService(appContext, intent)
        } catch (throwable: RuntimeException) {
            recordExportStartFailure(throwable)
            return
        }

        currentServiceConnection?.let { connection ->
            boundContext?.let { bound ->
                runCatching { bound.unbindService(connection) }
            }
        }
        currentServiceConnection = null
        boundContext = null

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as? ExportService.LocalBinder
                _serviceConnection.value = binder
                if (binder == null) {
                    _serviceStatusFallback.value = ExportService.ExportStatus.Failed(
                        "Unable to connect to the export service."
                    )
                    _uiState.update {
                        it.copy(
                            isExporting = false,
                            exportProgress = 0f,
                            navigateToProgress = false,
                            exportStartError = "Unable to connect to the export service."
                        )
                    }
                } else {
                    _uiState.update {
                        it.withFocusPixelStateFrom(null).copy(
                            isExporting = true,
                            exportProgress = 0f,
                            navigateToProgress = true,
                            isFocusPixelDownloadInProgress = false,
                            exportStartError = null
                        )
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                _serviceConnection.value = null
                _serviceStatusFallback.value = ExportService.ExportStatus.Failed(
                    "Export service disconnected before completion."
                )
                _uiState.update { it.copy(isExporting = false, navigateToProgress = false) }
            }

            override fun onBindingDied(name: ComponentName?) {
                _serviceConnection.value = null
                _serviceStatusFallback.value = ExportService.ExportStatus.Failed(
                    "Export service connection was lost."
                )
                _uiState.update { it.copy(isExporting = false, navigateToProgress = false) }
            }

            override fun onNullBinding(name: ComponentName?) {
                _serviceConnection.value = null
                _serviceStatusFallback.value = ExportService.ExportStatus.Failed(
                    "Unable to connect to the export service."
                )
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        exportProgress = 0f,
                        navigateToProgress = false,
                        exportStartError = "Unable to connect to the export service."
                    )
                }
            }
        }

        boundContext = appContext
        val bound = try {
            appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (throwable: RuntimeException) {
            appContext.stopService(intent)
            recordExportStartFailure(throwable)
            false
        }
        if (bound) {
            currentServiceConnection = connection
        } else {
            appContext.stopService(intent)
            boundContext = null
            _serviceConnection.value = null
            _serviceStatusFallback.value = ExportService.ExportStatus.Failed(
                "Unable to connect to the export service."
            )
            _uiState.update {
                it.copy(
                    isExporting = false,
                    navigateToProgress = false,
                    exportStartError = it.exportStartError
                        ?: "Unable to connect to the export service."
                )
            }
        }
    }

    private fun recordExportStartFailure(throwable: Throwable) {
        val detail = throwable.exportFailureMessage(default = throwable::class.java.simpleName)
        Log.e(TAG, "Unable to start export: $detail", throwable)
        _uiState.update {
            it.copy(
                isExporting = false,
                exportProgress = 0f,
                navigateToProgress = false,
                exportStartError = "Unable to start export: $detail"
            )
        }
        _serviceStatusFallback.value = ExportService.ExportStatus.Failed(
            "Unable to start export: $detail"
        )
    }

    fun downloadMissingFocusPixelMaps() {
        val draft = currentDraft ?: return
        val requirements = (draft.fpmPreflight as? FocusPixelPreflightResult.Missing)
            ?.requirements
            .orEmpty()
        if (requirements.isEmpty()) return

        viewModelScope.launch {
            downloadFocusPixelMapsForSelection(requirements, draft)
        }
    }

    private suspend fun downloadFocusPixelMapsForSelection(
        requirementsSnapshot: List<FocusPixelExportRequirement>,
        draft: ExportDraft
    ) {
        if (requirementsSnapshot.isEmpty()) {
            val updatedDraft = draft.copy(fpmPreflight = FocusPixelPreflightResult.Ready)
            currentDraft = updatedDraft
            _uiState.update {
                it.withFocusPixelStateFrom(updatedDraft).copy(
                    isFocusPixelDownloadInProgress = false,
                    focusPixelPreflightError = null,
                    navigateToExportSettings = true
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                isFocusPixelDownloadInProgress = true,
                focusPixelPreflightError = null,
                navigateToExportSettings = false
            )
        }

        val result = try {
            focusPixelPreflightCoordinator.downloadMissing(
                clips = draft.selectedClips,
                requirements = requirementsSnapshot
            )
        } catch (throwable: Throwable) {
            handleFocusPixelPreflightFailure(draft, throwable)
            return
        }
        applyFocusPixelPreflightResult(
            draft = draft,
            result = result
        )
    }

    private fun handleFocusPixelPreflightFailure(draft: ExportDraft, throwable: Throwable) {
        if (currentDraft !== draft) return
        Log.e(TAG, "Unable to check focus pixel maps", throwable)
        _uiState.update {
            it.copy(
                isFocusPixelCheckInProgress = false,
                isFocusPixelDownloadInProgress = false,
                navigateToExportSettings = false,
                focusPixelPreflightError =
                    "Unable to check focus pixel maps. Check storage access and try again."
            )
        }
    }

    fun skipFocusPixelDownload() {
        val draft = currentDraft ?: return
        val requirements = (draft.fpmPreflight as? FocusPixelPreflightResult.Missing)
            ?.requirements
            .orEmpty()
        val updatedDraft = draft.copy(
            fpmPreflight = FocusPixelPreflightResult.Skipped(requirements)
        )
        currentDraft = updatedDraft
        _uiState.update {
            it.withFocusPixelStateFrom(updatedDraft).copy(
                isFocusPixelDownloadInProgress = false,
                isFocusPixelCheckInProgress = false,
                focusPixelPreflightError = null,
                navigateToExportSettings = true
            )
        }
    }

    fun cancelFocusPixelPrompt() {
        resetExportDraft()
        _uiState.update {
            it.withFocusPixelStateFrom(null).copy(
                isFocusPixelDownloadInProgress = false,
                isFocusPixelCheckInProgress = false,
                focusPixelPreflightError = null,
                navigateToExportSettings = false,
                navigateToProgress = false
            )
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
        resetExportDraft()
        _uiState.update { currentState ->
            val selectedClips = currentState.selectedClips.toMutableSet()
            if (selectedClips.contains(clip.guid)) {
                selectedClips.remove(clip.guid)
            } else {
                selectedClips.add(clip.guid)
            }
            currentState.copy(
                selectedClips = selectedClips,
                focusPixelRequirements = focusPixelRequirementsFor(null),
                focusPixelDownloadFailures = focusPixelDownloadFailuresFor(null),
                focusPixelPromptStage = focusPixelPromptStageFor(null),
                isFocusPixelCheckInProgress = false,
                isFocusPixelDownloadInProgress = false,
                focusPixelPreflightError = null,
                navigateToExportSettings = false
            )
        }
    }

    fun selectAllClips() {
        resetExportDraft()
        _uiState.update { currentState ->
            currentState.copy(
                selectedClips = currentState.clips.map { it.guid }.toSet(),
                focusPixelRequirements = focusPixelRequirementsFor(null),
                focusPixelDownloadFailures = focusPixelDownloadFailuresFor(null),
                focusPixelPromptStage = focusPixelPromptStageFor(null),
                isFocusPixelCheckInProgress = false,
                isFocusPixelDownloadInProgress = false,
                focusPixelPreflightError = null,
                navigateToExportSettings = false
            )
        }
    }

    fun deselectAllClips() {
        resetExportDraft()
        _uiState.update { currentState ->
            currentState.copy(
                selectedClips = emptySet(),
                focusPixelRequirements = focusPixelRequirementsFor(null),
                focusPixelDownloadFailures = focusPixelDownloadFailuresFor(null),
                focusPixelPromptStage = focusPixelPromptStageFor(null),
                isFocusPixelCheckInProgress = false,
                isFocusPixelDownloadInProgress = false,
                focusPixelPreflightError = null,
                navigateToExportSettings = false
            )
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
        currentDraft = currentDraft?.copy(outputDirectory = uri)
        _uiState.update {
            it.copy(
                outputDirectory = uri,
                outputDirectoryError = null,
                exportStartError = null
            )
        }
    }

    fun onOutputDirectorySelectionFailed(uri: Uri, reason: Throwable) {
        Log.e(TAG, "Unable to persist access to output directory: $uri", reason)
        _uiState.update {
            it.copy(
                outputDirectoryError = "Unable to keep access to that folder. Choose another folder."
            )
        }
    }

    private fun updateSettings(transform: (ExportSettings) -> ExportSettings) {
        _uiState.update { current ->
            val updated = transform(current.settings).sanitized()
            current.copy(settings = updated)
        }
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

    private companion object {
        private const val TAG = "fm.forum.mlv.ExportViewModel"
    }
}

data class ExportUiState(
    val clips: List<ClipPreview> = emptyList(),
    val selectedClips: Set<Long> = emptySet(),
    val isExporting: Boolean = false,
    val exportProgress: Float = 0f,
    val settings: ExportSettings = ExportSettings(),
    val outputDirectory: Uri? = null,
    val outputDirectoryError: String? = null,
    val exportStartError: String? = null,
    val availableCodecs: List<ExportCodec> = ExportCodec.defaultOrder,
    val frameRatePresets: List<FrameRatePreset> = FrameRatePreset.values().toList(),
    val isFocusPixelCheckInProgress: Boolean = false,
    val focusPixelRequirements: List<FocusPixelExportRequirement> = emptyList(),
    val focusPixelDownloadFailures: List<String> = emptyList(),
    val focusPixelPreflightError: String? = null,
    val isFocusPixelDownloadInProgress: Boolean = false,
    val navigateToExportSettings: Boolean = false,
    val navigateToProgress: Boolean = false,
    val focusPixelPromptStage: FocusPixelPromptStage? = null
)

enum class FocusPixelPromptStage {
    SELECTION
}

internal fun shouldClearExportDraft(status: ExportService.ExportStatus): Boolean {
    return status is ExportService.ExportStatus.Completed
}
