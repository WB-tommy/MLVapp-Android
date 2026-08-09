package fm.magiclantern.forum.domain.session

import fm.magiclantern.forum.domain.model.ClipDetails
import fm.magiclantern.forum.domain.model.ClipPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

data class CpuProcessingPreviewRequirement(
    val required: Boolean = false,
    val revision: Long = 0L
)

/**
 * Single source of truth for the currently active clip.
 * All ViewModels that need clip data should observe this.
 */
@Singleton
class ActiveClipHolder @Inject constructor() {
    private val activationLock = Any()
    
    private val _activeClip = MutableStateFlow<ClipDetails?>(null)
    
    /**
     * The currently active/loaded clip with full metadata.
     * Null if no clip is selected or loading.
     */
    val activeClip: StateFlow<ClipDetails?> = _activeClip.asStateFlow()
    
    private val _selectedPreview = MutableStateFlow<ClipPreview?>(null)
    
    /**
     * The currently selected clip preview (may be loading).
     * Use this to show "selected" state in clip list even while loading.
     */
    val selectedPreview: StateFlow<ClipPreview?> = _selectedPreview.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    
    /**
     * Whether a clip is currently being loaded.
     */
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _processingVersion = MutableStateFlow(0L)
    
    /**
     * Version counter that increments when processing parameters change.
     * Observers (like PlayerViewModel) should trigger a redraw when this changes.
     */
    val processingVersion: StateFlow<Long> = _processingVersion.asStateFlow()

    private val _cpuProcessingPreviewRequirement =
        MutableStateFlow(CpuProcessingPreviewRequirement())
    @Volatile
    private var processingReceiptReady = true

    /** True while active settings need processing stages absent from RAW GPU preview. */
    val cpuProcessingPreviewRequirement: StateFlow<CpuProcessingPreviewRequirement> =
        _cpuProcessingPreviewRequirement.asStateFlow()

    private val _currentCutIn = MutableStateFlow(1)
    private val _currentCutOut = MutableStateFlow(0)
    
    /**
     * Cut In frame (1-based). Default 1 = first frame.
     * Updated by GradingViewModel when user sets cut marks.
     */
    val currentCutIn: StateFlow<Int> = _currentCutIn.asStateFlow()
    
    /**
     * Cut Out frame (1-based). 0 = not set (use last frame).
     * Updated by GradingViewModel when user sets cut marks.
     */
    val currentCutOut: StateFlow<Int> = _currentCutOut.asStateFlow()
    
    /**
     * Update cut marks. Called by GradingViewModel when cut values change.
     */
    fun setCutMarks(cutIn: Int, cutOut: Int) {
        _currentCutIn.value = cutIn
        _currentCutOut.value = cutOut
    }
    
    /**
     * Call this when grading/processing settings are changed to trigger a redraw.
     */
    fun notifyProcessingChanged() {
        _processingVersion.update { version -> version + 1L }
    }

    fun setRequiresCpuProcessingPreview(required: Boolean) {
        synchronized(activationLock) {
            val effectiveRequired = required ||
                (_activeClip.value != null && !processingReceiptReady)
            publishCpuProcessingRequirement(effectiveRequired)
        }
    }

    /** Release the activation safety gate only after native receipt commit. */
    fun completeProcessingReceiptRestore(
        expectedHandle: Long,
        expectedGuid: Long,
        required: Boolean
    ): Boolean = synchronized(activationLock) {
        val active = _activeClip.value
        if (active?.nativeHandle != expectedHandle || active.guid != expectedGuid) {
            return@synchronized false
        }
        processingReceiptReady = true
        publishCpuProcessingRequirement(required)
        true
    }

    private fun publishCpuProcessingRequirement(required: Boolean) {
        _cpuProcessingPreviewRequirement.update { current ->
            if (current.required == required) current
            else CpuProcessingPreviewRequirement(required, current.revision + 1L)
        }
    }

    
    /**
     * Mark a clip as selected and start loading.
     */
    fun selectClip(preview: ClipPreview) {
        _selectedPreview.value = preview
        _isLoading.value = true
    }
    
    /**
     * Set the fully loaded clip as active.
     */
    fun activateClip(details: ClipDetails) {
        // Reset cut marks to defaults before activating, so PlayerViewModel
        // never reads stale marks from the previous clip in the race window
        // before GradingViewModel loads the new clip's grading data.
        synchronized(activationLock) {
            _currentCutIn.value = 1
            _currentCutOut.value = 0
            processingReceiptReady = false
            publishCpuProcessingRequirement(true)
            _activeClip.value = details
        }
        _selectedPreview.value = details.preview
        _isLoading.value = false
    }
    
    /**
     * Clear the active clip (e.g., when clip is deleted or closed).
     */
    fun clearActiveClip() {
        synchronized(activationLock) {
            _activeClip.value = null
            processingReceiptReady = true
            publishCpuProcessingRequirement(false)
        }
        _selectedPreview.value = null
        _isLoading.value = false
    }
    
    /**
     * Update loading state (e.g., on error).
     */
    fun setLoading(loading: Boolean) {
        _isLoading.value = loading
    }
}
