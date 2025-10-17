package fm.forum.mlvapp.clips

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fm.forum.mlvapp.FocusPixelManager
import fm.forum.mlvapp.data.Clip
import fm.forum.mlvapp.data.ClipChunk
import fm.forum.mlvapp.data.ClipRepository
import fm.forum.mlvapp.data.FocusPixelRequirement
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ClipViewModel(
    private val repository: ClipRepository,
    private val totalMemory: Long,
    private val cpuCores: Int
) : ViewModel() {

    private val _uiState = MutableStateFlow(ClipUiState())
    val uiState: StateFlow<ClipUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ClipEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ClipEvent> = _events.asSharedFlow()

    private var currentLoadJob: Job? = null

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
                        val nextClips = mergeChunk(state.clips, chunk)
                        state.copy(clips = nextClips)
                    }
                } else {
                    failedCount++
                }
            }

            if (failedCount > 0) {
                _events.tryEmit(ClipEvent.ClipPreparationFailed(failedCount, uris.size))
            }
            _uiState.update { it.copy(isPreparingClips = false) }
        }
    }

    fun onClipSelected(clipGuid: Long) {
        val clip = _uiState.value.clips.firstOrNull { it.guid == clipGuid } ?: return
        if (_uiState.value.isActivatingClip && _uiState.value.selectedClipGuid == clipGuid) return

        currentLoadJob?.cancel()
        currentLoadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    selectedClipGuid = clipGuid,
                    isActivatingClip = true
                )
            }
            val result = runCatching {
                repository.loadClip(clip, totalMemory, cpuCores)
            }

            result.onSuccess { loadResult ->
                _uiState.update { state ->
                    val updatedClips = replaceClip(state.clips, loadResult.clip)
                    val prompt = loadResult.focusPixelRequirement
                    val nextPrompt = when {
                        prompt != null -> prompt
                        state.focusPixelPrompt?.clipGuid == loadResult.clip.guid -> null
                        else -> state.focusPixelPrompt
                    }
                    state.copy(
                        clips = updatedClips,
                        activeClip = loadResult.clip,
                        isActivatingClip = false,
                        focusPixelPrompt = nextPrompt
                    )
                }
            }.onFailure { throwable ->
                _uiState.update { it.copy(isActivatingClip = false) }
                _events.tryEmit(ClipEvent.LoadFailed(clipGuid, throwable))
            }
        }
    }

    fun dismissFocusPixelPrompt() {
        _uiState.update { state ->
            if (state.isFocusPixelDownloadInProgress) state else state.copy(focusPixelPrompt = null)
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
            val resolved = if (success) {
                repository.ensureFocusPixelMap(prompt.requiredFile)
            } else {
                false
            }
            if (resolved) {
                refreshFocusPixelFor(prompt.clipGuid)
            }
            _uiState.update { state ->
                state.copy(
                    isFocusPixelDownloadInProgress = false,
                    focusPixelPrompt = if (resolved) null else state.focusPixelPrompt
                )
            }
            _events.emit(
                ClipEvent.FocusPixelDownloadFeedback(
                    if (resolved) FocusPixelDownloadOutcome.SINGLE_SUCCESS
                    else FocusPixelDownloadOutcome.SINGLE_FAILURE
                )
            )
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
                FocusPixelManager.DownloadAllResult.SUCCESS -> {
                    val resolved = repository.ensureFocusPixelMap(prompt.requiredFile)
                    if (resolved) {
                        refreshFocusPixelFor(prompt.clipGuid)
                    }
                    if (resolved) {
                        FocusPixelDownloadOutcome.ALL_SUCCESS
                    } else {
                        FocusPixelDownloadOutcome.ALL_FAILURE
                    }
                }

                FocusPixelManager.DownloadAllResult.NONE_FOR_CAMERA ->
                    FocusPixelDownloadOutcome.ALL_NONE_FOR_CAMERA

                FocusPixelManager.DownloadAllResult.INDEX_UNAVAILABLE ->
                    FocusPixelDownloadOutcome.ALL_INDEX_UNAVAILABLE

                FocusPixelManager.DownloadAllResult.FAILED ->
                    FocusPixelDownloadOutcome.ALL_FAILURE

                null ->
                    FocusPixelDownloadOutcome.ALL_FAILURE
            }

            val shouldDismiss = outcome == FocusPixelDownloadOutcome.ALL_SUCCESS
            _uiState.update { state ->
                state.copy(
                    isFocusPixelDownloadInProgress = false,
                    focusPixelPrompt = if (shouldDismiss) null else state.focusPixelPrompt
                )
            }
            _events.emit(ClipEvent.FocusPixelDownloadFeedback(outcome))
        }
    }

    private fun refreshFocusPixelFor(clipGuid: Long) {
        val activeClip = _uiState.value.activeClip
        if (activeClip != null && activeClip.guid == clipGuid && activeClip.nativeHandle != 0L) {
            repository.refreshFocusPixelMap(activeClip.nativeHandle)
        }
    }

    private fun mergeChunk(existing: List<Clip>, chunk: ClipChunk): List<Clip> {
        val index = existing.indexOfFirst { it.guid == chunk.guid }
        if (index >= 0) {
            val current = existing[index]
            val mergedUris = (current.uris + chunk.uri).distinct()
            val mergedFileNames = (current.fileNames + chunk.fileName).distinct()
            val updated = current.copy(
                uris = mergedUris,
                fileNames = mergedFileNames
            )
            return existing.toMutableList().apply { set(index, updated) }
        }
        val mappPath = repository.prepareClipPath(chunk.guid, chunk.fileName)
        val newClip = Clip(
            uris = listOf(chunk.uri),
            fileNames = listOf(chunk.fileName),
            displayName = chunk.fileName,
            width = chunk.width,
            height = chunk.height,
            thumbnail = chunk.thumbnail,
            guid = chunk.guid,
            mappPath = mappPath
        )
        return existing + newClip
    }

    private fun replaceClip(existing: List<Clip>, updated: Clip): List<Clip> {
        val index = existing.indexOfFirst { it.guid == updated.guid }
        if (index < 0) return existing
        return existing.toMutableList().apply { set(index, updated) }
    }
}

data class ClipUiState(
    val clips: List<Clip> = emptyList(),
    val selectedClipGuid: Long? = null,
    val activeClip: Clip? = null,
    val isActivatingClip: Boolean = false,
    val isPreparingClips: Boolean = false,
    val focusPixelPrompt: FocusPixelRequirement? = null,
    val isFocusPixelDownloadInProgress: Boolean = false
) {
    val isLoading: Boolean
        get() = isActivatingClip || isPreparingClips
} 

sealed interface ClipEvent {
    data class FocusPixelDownloadFeedback(val outcome: FocusPixelDownloadOutcome) : ClipEvent
    data class LoadFailed(val clipGuid: Long, val throwable: Throwable) : ClipEvent
    data class ClipPreparationFailed(val failedCount: Int, val totalCount: Int) : ClipEvent
}

enum class FocusPixelDownloadOutcome {
    SINGLE_SUCCESS,
    SINGLE_FAILURE,
    ALL_SUCCESS,
    ALL_FAILURE,
    ALL_NONE_FOR_CAMERA,
    ALL_INDEX_UNAVAILABLE
}
