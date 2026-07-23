package fm.magiclantern.forum.features.clips.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fm.magiclantern.forum.FocusPixelManager
import fm.magiclantern.forum.data.repository.ClipRepository
import fm.magiclantern.forum.data.repository.FocusPixelRequirement
import fm.magiclantern.forum.data.repository.PreparedClipFile
import fm.magiclantern.forum.domain.model.ClipPreview
import fm.magiclantern.forum.domain.session.ActiveClipHolder
import fm.magiclantern.forum.utils.MlvFileRole
import fm.magiclantern.forum.utils.dedupeAndSortByMlvFileRole
import fm.magiclantern.forum.utils.mlvClipStem
import fm.magiclantern.forum.utils.mlvFileRole
import fm.magiclantern.forum.utils.semanticDedupeKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    private var cacheSizeMiB: Long = 4096L
    private var cpuCores: Int = 4

    private val _uiState = MutableStateFlow(ClipListUiState())
    val uiState: StateFlow<ClipListUiState> = _uiState.asStateFlow()

    private val _removalState = MutableStateFlow(ClipRemovalState())
    val removalState: StateFlow<ClipRemovalState> = _removalState.asStateFlow()

    private val _events = MutableSharedFlow<ClipListEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ClipListEvent> = _events.asSharedFlow()

    private var currentLoadJob: Job? = null
    private val promptedFocusPixelClips = mutableSetOf<Long>()
    private val pendingChunksByGuid = mutableMapOf<Long, List<PreparedClipFile>>()
    private val pendingChunksByStem = mutableMapOf<String, List<PreparedClipFile>>()

    /**
     * Set system info (memory/cores) - called from MainActivity or NavController
     */
    fun setSystemInfo(cacheSizeMiB: Long, cores: Int) {
        this.cacheSizeMiB = cacheSizeMiB
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
            var attachedChunkCount = 0
            var pendingChunkCount = 0
            var activeClipReloadGuid: Long? = null
            for (uri in uris) {
                val preparedFile = runCatching {
                    repository.prepareClipFile(uri, cacheSizeMiB, cpuCores)
                }.getOrNull()

                if (preparedFile != null) {
                    val outcome = mergePreparedFile(preparedFile)
                    attachedChunkCount += outcome.attachedChunks
                    pendingChunkCount += outcome.pendingChunks
                    if (outcome.attachedChunks > 0 &&
                        activeClipHolder.activeClip.value?.guid == outcome.changedClipGuid
                    ) {
                        activeClipReloadGuid = outcome.changedClipGuid
                    }
                } else {
                    failedCount++
                }
            }

            if (attachedChunkCount > 0 || pendingChunkCount > 0) {
                _events.tryEmit(
                    ClipListEvent.ChunkImportFeedback(
                        attachedCount = attachedChunkCount,
                        pendingCount = pendingChunkCount
                    )
                )
            }
            if (failedCount > 0) {
                _events.tryEmit(ClipListEvent.ClipPreparationFailed(failedCount, uris.size))
            }
            _uiState.update { it.copy(isPreparingClips = false) }
            activeClipReloadGuid?.let { reloadActiveClipIfUrisChanged(it) }
        }
    }

    /**
     * Handle clip selection from list
     */
    fun onClipSelected(clipGuid: Long) {
        activateClipByGuid(clipGuid, forceReload = false)
    }

    private fun activateClipByGuid(clipGuid: Long, forceReload: Boolean) {
        val preview = _uiState.value.clips.firstOrNull { it.guid == clipGuid } ?: return
        if (!forceReload && _uiState.value.isActivatingClip && _uiState.value.selectedClipGuid == clipGuid) return

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
                repository.loadClipAsDetails(preview, cacheSizeMiB, cpuCores)
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

    private fun reloadActiveClipIfUrisChanged(clipGuid: Long) {
        val activeClip = activeClipHolder.activeClip.value ?: return
        if (activeClip.guid != clipGuid) return
        val updatedPreview = _uiState.value.clips.firstOrNull { it.guid == clipGuid } ?: return
        if (updatedPreview.uris == activeClip.uris && updatedPreview.fileNames == activeClip.fileNames) return
        activateClipByGuid(clipGuid, forceReload = true)
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
                    repository.loadClipAsDetails(preview, cacheSizeMiB, cpuCores)
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

    private fun mergePreparedFile(file: PreparedClipFile): PreparedFileMergeOutcome {
        return when (file.role) {
            MlvFileRole.BaseMlv, MlvFileRole.Mcraw -> {
                val pendingChunks = if (file.role == MlvFileRole.BaseMlv) {
                    dedupeAndSortPreparedFiles(
                        pendingChunksByGuid.remove(file.guid).orEmpty() +
                                pendingChunksByStem.remove(file.clipStem).orEmpty()
                    )
                } else {
                    emptyList()
                }
                var attachedChunks = 0
                _uiState.update { state ->
                    val result = mergePrimaryFileToPreview(state.clips, file, pendingChunks)
                    attachedChunks = result.attachedChunkCount
                    state.copy(clips = result.clips)
                }
                PreparedFileMergeOutcome(
                    attachedChunks = attachedChunks,
                    changedClipGuid = file.guid
                )
            }
            is MlvFileRole.Chunk -> {
                val existing = _uiState.value.clips
                val index = findExistingMlvClipIndexForChunk(existing, file)
                if (index >= 0) {
                    val changedClipGuid = existing[index].guid
                    var attachedChunks = 0
                    _uiState.update { state ->
                        val result = mergeChunksToExistingPreview(state.clips, file, listOf(file))
                        attachedChunks = result.attachedChunkCount
                        state.copy(clips = result.clips)
                    }
                    PreparedFileMergeOutcome(
                        attachedChunks = attachedChunks,
                        changedClipGuid = changedClipGuid
                    )
                } else {
                    val wasPending = isChunkPending(file)
                    storePendingChunk(file)
                    PreparedFileMergeOutcome(
                        pendingChunks = if (wasPending) 0 else 1
                    )
                }
            }
            MlvFileRole.Unsupported -> PreparedFileMergeOutcome()
        }
    }

    private fun mergePrimaryFileToPreview(
        existing: List<ClipPreview>,
        primary: PreparedClipFile,
        pendingChunks: List<PreparedClipFile>
    ): PreviewMergeResult {
        val index = existing.indexOfFirst { it.guid == primary.guid }
        if (index >= 0) {
            val current = existing[index]
            val attachedCount = countNewFileRoles(
                existingPairs = current.uris.zip(current.fileNames),
                newFiles = pendingChunks
            )
            val (uris, fileNames) = mergeFilePairs(
                existingPairs = current.uris.zip(current.fileNames),
                newFiles = listOf(primary) + pendingChunks
            )
            val updated = current.copy(
                displayName = if (primary.role == MlvFileRole.BaseMlv) primary.fileName else current.displayName,
                uris = uris,
                fileNames = fileNames,
                cameraModelId = current.cameraModelId.takeIf { it != 0 } ?: primary.cameraModelId,
                focusPixelMapName = current.focusPixelMapName.ifBlank { primary.focusPixelMapName },
                isMcraw = current.isMcraw || primary.isMcraw,
                dualIsoValid = primary.dualIsoValid,
                dualIsoAutoEnabled = primary.dualIsoAutoEnabled,
                originalBlackLevel = primary.originalBlackLevel,
                originalWhiteLevel = primary.originalWhiteLevel
            )
            return PreviewMergeResult(
                clips = existing.toMutableList().apply { set(index, updated) },
                attachedChunkCount = attachedCount
            )
        }
        val thumbnail = primary.thumbnail ?: return PreviewMergeResult(existing)
        val (uris, fileNames) = mergeFilePairs(
            existingPairs = emptyList(),
            newFiles = listOf(primary) + pendingChunks
        )
        val newPreview = ClipPreview(
            guid = primary.guid,
            displayName = primary.fileName,
            uris = uris,
            fileNames = fileNames,
            thumbnail = thumbnail,
            width = primary.width,
            height = primary.height,
            stretchFactorX = primary.stretchFactorX.takeIf { it > 0f } ?: 1.0f,
            stretchFactorY = primary.stretchFactorY.takeIf { it > 0f } ?: 1.0f,
            cameraModelId = primary.cameraModelId,
            focusPixelMapName = primary.focusPixelMapName,
            isMcraw = primary.isMcraw,
            dualIsoValid = primary.dualIsoValid,
            dualIsoAutoEnabled = primary.dualIsoAutoEnabled,
            originalBlackLevel = primary.originalBlackLevel,
            originalWhiteLevel = primary.originalWhiteLevel
        )
        return PreviewMergeResult(
            clips = existing + newPreview,
            attachedChunkCount = pendingChunks.size
        )
    }

    private fun mergeChunksToExistingPreview(
        existing: List<ClipPreview>,
        chunk: PreparedClipFile,
        chunks: List<PreparedClipFile>
    ): PreviewMergeResult {
        val index = findExistingMlvClipIndexForChunk(existing, chunk)
        if (index < 0) return PreviewMergeResult(existing)
        val current = existing[index]
        val attachedCount = countNewFileRoles(
            existingPairs = current.uris.zip(current.fileNames),
            newFiles = chunks
        )
        val (uris, fileNames) = mergeFilePairs(
            existingPairs = current.uris.zip(current.fileNames),
            newFiles = chunks
        )
        val updated = current.copy(uris = uris, fileNames = fileNames)
        return PreviewMergeResult(
            clips = existing.toMutableList().apply { set(index, updated) },
            attachedChunkCount = attachedCount
        )
    }

    private fun mergeFilePairs(
        existingPairs: List<Pair<Uri, String>>,
        newFiles: List<PreparedClipFile>
    ): Pair<List<Uri>, List<String>> {
        val mergedPairs = existingPairs + newFiles.map { it.uri to it.fileName }
        return mergedPairs
            .dedupeAndSortByMlvFileRole { (_, fileName) -> fileName }
            .unzip()
    }

    private fun countNewFileRoles(
        existingPairs: List<Pair<Uri, String>>,
        newFiles: List<PreparedClipFile>
    ): Int {
        val existingKeys = existingPairs
            .map { (_, fileName) -> mlvFileRole(fileName).semanticDedupeKey(fileName) }
            .toSet()
        return newFiles
            .distinctBy { it.role.semanticDedupeKey(it.fileName) }
            .count { it.role is MlvFileRole.Chunk && it.role.semanticDedupeKey(it.fileName) !in existingKeys }
    }

    private fun dedupeAndSortPreparedFiles(files: List<PreparedClipFile>): List<PreparedClipFile> {
        return files.dedupeAndSortByMlvFileRole { it.fileName }
    }

    private fun findExistingMlvClipIndexForChunk(
        existing: List<ClipPreview>,
        chunk: PreparedClipFile
    ): Int {
        return existing.indexOfFirst { preview ->
            !preview.isMcraw && (
                    (chunk.guid != 0L && preview.guid == chunk.guid) ||
                            preview.fileNames.any { fileName ->
                                mlvFileRole(fileName) == MlvFileRole.BaseMlv &&
                                        mlvClipStem(fileName) == chunk.clipStem
                            }
                    )
        }
    }

    private fun isChunkPending(chunk: PreparedClipFile): Boolean {
        val key = chunk.role.semanticDedupeKey(chunk.fileName)
        return pendingChunksByStem[chunk.clipStem].orEmpty()
            .any { it.role.semanticDedupeKey(it.fileName) == key } ||
                (chunk.guid != 0L && pendingChunksByGuid[chunk.guid].orEmpty()
                    .any { it.role.semanticDedupeKey(it.fileName) == key })
    }

    private fun storePendingChunk(chunk: PreparedClipFile) {
        pendingChunksByStem[chunk.clipStem] = dedupeAndSortPreparedFiles(
            pendingChunksByStem[chunk.clipStem].orEmpty() + chunk
        )
        if (chunk.guid != 0L) {
            pendingChunksByGuid[chunk.guid] = dedupeAndSortPreparedFiles(
                pendingChunksByGuid[chunk.guid].orEmpty() + chunk
            )
        }
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
    data class ChunkImportFeedback(val attachedCount: Int, val pendingCount: Int) : ClipListEvent
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

private data class PreparedFileMergeOutcome(
    val attachedChunks: Int = 0,
    val pendingChunks: Int = 0,
    val changedClipGuid: Long? = null
)

private data class PreviewMergeResult(
    val clips: List<ClipPreview>,
    val attachedChunkCount: Int = 0
)
