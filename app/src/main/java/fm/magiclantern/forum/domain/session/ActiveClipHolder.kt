package fm.magiclantern.forum.domain.session

import fm.magiclantern.forum.domain.model.ClipDetails
import fm.magiclantern.forum.domain.model.ClipPreview
import fm.magiclantern.forum.domain.model.DebayerAlgorithm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for the currently active clip.
 * All ViewModels that need clip data should observe this.
 */
@Singleton
class ActiveClipHolder @Inject constructor() {
    
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
    
    private val _currentReceiptDebayerMode = MutableStateFlow(DebayerAlgorithm.AMAZE)
    
    /**
     * The current clip's receipt debayer mode (from grading settings).
     * Used by PlayerViewModel when playback is paused to show high-quality preview.
     * Updated by GradingViewModel when user changes debayer in grading screen.
     */
    val currentReceiptDebayerMode: StateFlow<DebayerAlgorithm> = _currentReceiptDebayerMode.asStateFlow()
    
    /**
     * Update the current clip's receipt debayer mode.
     * Called by GradingViewModel when debayer setting changes.
     */
    fun setReceiptDebayerMode(mode: DebayerAlgorithm) {
        _currentReceiptDebayerMode.value = mode
    }
    
    /**
     * Call this when grading/processing settings are changed to trigger a redraw.
     */
    fun notifyProcessingChanged() {
        _processingVersion.value++
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
        _activeClip.value = details
        _selectedPreview.value = details.preview
        _isLoading.value = false
    }
    
    /**
     * Clear the active clip (e.g., when clip is deleted or closed).
     */
    fun clearActiveClip() {
        _activeClip.value = null
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
