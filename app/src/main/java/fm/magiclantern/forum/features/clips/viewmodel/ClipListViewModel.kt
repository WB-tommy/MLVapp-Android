package fm.magiclantern.forum.features.clips.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fm.magiclantern.forum.FocusPixelManager
import fm.magiclantern.forum.data.repository.ClipChunk
import fm.magiclantern.forum.data.repository.ClipRepository
import fm.magiclantern.forum.data.repository.FocusPixelRequirement
import fm.magiclantern.forum.domain.model.ClipPreview
import fm.magiclantern.forum.domain.session.ActiveClipHolder
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * ViewModel for managing the clip list.
 * Handles file picking, clip loading, and delegates active clip to ActiveClipHolder.
 */
@HiltViewModel
class ClipListViewModel @Inject constructor(
    private val repository: ClipRepository,
    private val activeClipHolder: ActiveClipHolder
) : ViewModel() {

    // System info - injected via Hilt module
    private var totalMemory: Long = 4096L
    private var cpuCores: Int = 4

    private val _uiState = MutableStateFlow(ClipListUiState())
    val uiState: StateFlow<ClipListUiState> = _uiState.asStateFlow()

    private val _removalState = MutableStateFlow(ClipRemovalState())
    val removalState: StateFlow<ClipRemovalState> = _removalState.asStateFlow()

    private val _events = MutableSharedFlow<ClipListEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ClipListEvent> = _events.asSharedFlow()

    private var currentLoadJob: Job? = null
    private val promptedFocusPixelClips = mutableSetOf<Long>()

    /**
     * Set system info (memory/cores) - called from MainActivity or NavController
     */
    fun setSystemInfo(totalMemoryMiB: Long, cores: Int) {
        totalMemory = totalMemoryMiB
        cpuCores = cores
    }

    /**
     * Handle file picker result
     */
    fun onFilesPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isPreparingClips = true) }
            var failedCount = 0
            for (uri in uris) {
                val chunk = runCatching {
                    repository.prepareClipChunk(uri, totalMemory, cpuCores)
                }.getOrNull()

                if (chunk != null) {
                    _uiState.update { state ->
                        val nextPreviews = mergeChunkToPreview(state.clips, chunk)
                        state.copy(clips = nextPreviews)
                    }
                } else {
                    failedCount++
                }
            }

            if (failedCount > 0) {
                _events.tryEmit(ClipListEvent.ClipPreparationFailed(failedCount, uris.size))
            }
            _uiState.update { it.copy(isPreparingClips = false) }
        }
    }

    /**
     * Handle clip selection from list
     */
    fun onClipSelected(clipGuid: Long) {
        val preview = _uiState.value.clips.firstOrNull { it.guid == clipGuid } ?: return
        if (_uiState.value.isActivatingClip && _uiState.value.selectedClipGuid == clipGuid) return

        currentLoadJob?.cancel()
        
        // Notify ActiveClipHolder that we're selecting
        activeClipHolder.selectClip(preview)
        
        currentLoadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    selectedClipGuid = clipGuid,
                    isActivatingClip = true
                )
            }
            
            // Use new domain-based loading
            val result = runCatching {
                repository.loadClipAsDetails(preview, totalMemory, cpuCores)
            }

            result.onSuccess { loadResult ->
                val details = loadResult.details
                if (details == null) {
                    _uiState.update { it.copy(isActivatingClip = false) }
                    activeClipHolder.setLoading(false)
                    _events.tryEmit(ClipListEvent.LoadFailed(clipGuid, Exception("Failed to load clip")))
                    return@onSuccess
                }
                
                val prompt = loadResult.focusPixelRequirement
                val shouldPrompt = if (prompt != null) {
                    promptedFocusPixelClips.add(prompt.clipGuid)
                } else {
                    false
                }
                
                if (shouldPrompt && prompt != null) {
                    // .fpm file is missing. Show prompt and wait.
                    _uiState.update { state ->
                        state.copy(
                            isActivatingClip = false,
                            focusPixelPrompt = prompt
                        )
                    }
                    activeClipHolder.setLoading(false)
                } else {
                    // Activate the clip directly with domain model
                    activeClipHolder.activateClip(details)
                    
                    _uiState.update { state ->
                        state.copy(
                            isActivatingClip = false,
                            focusPixelPrompt = null
                        )
                    }
                }
            }.onFailure { throwable ->
                _uiState.update { it.copy(isActivatingClip = false) }
                activeClipHolder.setLoading(false)
                _events.tryEmit(ClipListEvent.LoadFailed(clipGuid, throwable))
            }
        }
    }

    /**
     * Enter clip removal mode - called when delete button is pressed in top bar
     */
    fun enterRemovalMode() {
        _removalState.update { ClipRemovalState(isInRemovalMode = true) }
    }

    /**
     * Toggle selection of a clip for removal
     */
    fun toggleClipSelectionForRemoval(clip: ClipPreview) {
        _removalState.update { state ->
            val selectedClips = state.selectedClips.toMutableSet()
            if (selectedClips.contains(clip.guid)) {
                selectedClips.remove(clip.guid)
            } else {
                selectedClips.add(clip.guid)
            }
            state.copy(selectedClips = selectedClips)
        }
    }

    /**
     * Select all clips for removal
     */
    fun selectAllClipsForRemoval() {
        _removalState.update { state ->
            state.copy(selectedClips = _uiState.value.clips.map { it.guid }.toSet())
        }
    }

    /**
     * Deselect all clips for removal
     */
    fun deselectAllClipsForRemoval() {
        _removalState.update { state ->
            state.copy(selectedClips = emptySet())
        }
    }

    /**
     * Confirm and execute clip removal
     */
    fun confirmClipRemoval() {
        val guidsToRemove = _removalState.value.selectedClips
        if (guidsToRemove.isEmpty()) return

        // Check if currently active clip is being removed
        val activeClipGuid = activeClipHolder.activeClip.value?.guid
        val removingActiveClip = activeClipGuid != null && guidsToRemove.contains(activeClipGuid)

        // Remove clips from the list
        _uiState.update { state ->
            state.copy(
                clips = state.clips.filter { !guidsToRemove.contains(it.guid) },
                selectedClipGuid = if (removingActiveClip) null else state.selectedClipGuid
            )
        }

        // Clear active clip if it was removed
        if (removingActiveClip) {
            activeClipHolder.clearActiveClip()
        }

        // Reset removal state
        _removalState.update { ClipRemovalState() }
    }

    /**
     * Cancel clip removal mode
     */
    fun cancelClipRemoval() {
        _removalState.update { ClipRemovalState() }
    }

    fun dismissFocusPixelPrompt() {
        val prompt = _uiState.value.focusPixelPrompt ?: return
        if (_uiState.value.isFocusPixelDownloadInProgress) return
        activatePendingClip(prompt.clipGuid)
    }

    private fun activatePendingClip(clipGuid: Long) {
        val preview = _uiState.value.clips.firstOrNull { it.guid == clipGuid }
        if (preview != null) {
            // Need to reload to get full details with nativeHandle
            viewModelScope.launch {
                val result = runCatching {
                    repository.loadClipAsDetails(preview, totalMemory, cpuCores)
                }
                result.onSuccess { loadResult ->
                    loadResult.details?.let { activeClipHolder.activateClip(it) }
                }
            }
        }
        _uiState.update { state ->
            state.copy(
                isFocusPixelDownloadInProgress = false,
                isActivatingClip = false,
                focusPixelPrompt = null
            )
        }
    }

    fun downloadFocusPixelMap() {
        val prompt = _uiState.value.focusPixelPrompt ?: return
        if (_uiState.value.isFocusPixelDownloadInProgress) return
        viewModelScope.launch {
            _uiState.update { it.copy(isFocusPixelDownloadInProgress = true) }
            val success = runCatching {
                repository.downloadFocusPixelMap(prompt.requiredFile)
            }.getOrDefault(false)

            if (success) {
                activatePendingClip(prompt.clipGuid)
                _events.emit(ClipListEvent.FocusPixelDownloadFeedback(FocusPixelDownloadOutcome.SINGLE_SUCCESS))
            } else {
                activatePendingClip(prompt.clipGuid)
                _events.emit(ClipListEvent.FocusPixelDownloadFeedback(FocusPixelDownloadOutcome.SINGLE_FAILURE))
            }
        }
    }

    fun downloadAllFocusPixelMaps() {
        val prompt = _uiState.value.focusPixelPrompt ?: return
        if (_uiState.value.isFocusPixelDownloadInProgress) return
        viewModelScope.launch {
            _uiState.update { it.copy(isFocusPixelDownloadInProgress = true) }
            val cameraId = prompt.requiredFile.substringBefore('_').ifEmpty { prompt.requiredFile }
            val result = runCatching {
                repository.downloadFocusPixelMapsForCamera(cameraId)
            }.getOrNull()

            val outcome = when (result) {
                FocusPixelManager.DownloadAllResult.SUCCESS -> FocusPixelDownloadOutcome.ALL_SUCCESS
                FocusPixelManager.DownloadAllResult.NONE_FOR_CAMERA -> FocusPixelDownloadOutcome.ALL_NONE_FOR_CAMERA
                FocusPixelManager.DownloadAllResult.INDEX_UNAVAILABLE -> FocusPixelDownloadOutcome.ALL_INDEX_UNAVAILABLE
                else -> FocusPixelDownloadOutcome.ALL_FAILURE
            }

            activatePendingClip(prompt.clipGuid)
            _events.emit(ClipListEvent.FocusPixelDownloadFeedback(outcome))
        }
    }

    // ==================== Conversion Helpers (temporary during migration) ====================

    private fun mergeChunkToPreview(existing: List<ClipPreview>, chunk: ClipChunk): List<ClipPreview> {
        val index = existing.indexOfFirst { it.guid == chunk.guid }
        if (index >= 0) {
            val current = existing[index]
            val mergedPairs = current.uris.zip(current.fileNames) + (chunk.uri to chunk.fileName)
            val dedupedPairs = mergedPairs.distinctBy { (_, name) -> name.lowercase(Locale.ROOT) }
            val (uris, fileNames) = dedupedPairs.unzip()
            val updated = current.copy(
                uris = uris,
                fileNames = fileNames,
                cameraModelId = current.cameraModelId.takeIf { it != 0 } ?: chunk.cameraModelId,
                focusPixelMapName = current.focusPixelMapName.ifBlank { chunk.focusPixelMapName },
                isMcraw = current.isMcraw || chunk.isMcraw
            )
            return existing.toMutableList().apply { set(index, updated) }
        }
        val newPreview = ClipPreview(
            guid = chunk.guid,
            displayName = chunk.fileName,
            uris = listOf(chunk.uri),
            fileNames = listOf(chunk.fileName),
            thumbnail = chunk.thumbnail,
            width = chunk.width,
            height = chunk.height,
            stretchFactorX = chunk.stretchFactorX.takeIf { it > 0f } ?: 1.0f,
            stretchFactorY = chunk.stretchFactorY.takeIf { it > 0f } ?: 1.0f,
            cameraModelId = chunk.cameraModelId,
            focusPixelMapName = chunk.focusPixelMapName,
            isMcraw = chunk.isMcraw
        )
        return existing + newPreview
    }

    // ==================== Export Support Methods ====================

    /**
     * Find clips missing focus pixel maps for export
     */
    fun findMissingFocusPixelMapsForExport(clips: List<ClipPreview>): List<FocusPixelRequirement> {
        return clips.mapNotNull { clip ->
            val mapName = clip.focusPixelMapName
            if (mapName.isNotBlank() && !repository.focusPixelExists(mapName)) {
                FocusPixelRequirement(clipGuid = clip.guid, requiredFile = mapName)
            } else {
                null
            }
        }
    }

    /**
     * Download a specific focus pixel map file
     */
    suspend fun downloadFocusPixelMapForExport(fileName: String): Boolean {
        return runCatching {
            repository.downloadFocusPixelMap(fileName)
        }.getOrDefault(false)
    }

    /**
     * Refresh the focus pixel map for a native handle
     */
    fun refreshFocusPixelMapForExport(nativeHandle: Long) {
        repository.refreshFocusPixel(nativeHandle)
    }
}

/**
 * UI state for clip list management
 */
data class ClipListUiState(
    val clips: List<ClipPreview> = emptyList(),
    val selectedClipGuid: Long? = null,
    val isActivatingClip: Boolean = false,
    val isPreparingClips: Boolean = false,
    val focusPixelPrompt: FocusPixelRequirement? = null,
    val isFocusPixelDownloadInProgress: Boolean = false
) {
    val isLoading: Boolean
        get() = isActivatingClip || isPreparingClips
}

sealed interface ClipListEvent {
    data class FocusPixelDownloadFeedback(val outcome: FocusPixelDownloadOutcome) : ClipListEvent
    data class LoadFailed(val clipGuid: Long, val throwable: Throwable) : ClipListEvent
    data class ClipPreparationFailed(val failedCount: Int, val totalCount: Int) : ClipListEvent
}

enum class FocusPixelDownloadOutcome {
    SINGLE_SUCCESS,
    SINGLE_FAILURE,
    ALL_SUCCESS,
    ALL_FAILURE,
    ALL_NONE_FOR_CAMERA,
    ALL_INDEX_UNAVAILABLE
}

/**
 * State for clip removal selection
 */
data class ClipRemovalState(
    val isInRemovalMode: Boolean = false,
    val selectedClips: Set<Long> = emptySet()
)
