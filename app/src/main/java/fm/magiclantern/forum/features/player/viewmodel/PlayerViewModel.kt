package fm.magiclantern.forum.features.player.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fm.magiclantern.forum.domain.model.ClipDetails
import fm.magiclantern.forum.domain.model.DebayerAlgorithm
import fm.magiclantern.forum.domain.session.ActiveClipHolder
import fm.magiclantern.forum.nativeInterface.NativeLib
import fm.magiclantern.forum.features.settings.viewmodel.DebayerMode
import fm.magiclantern.forum.features.settings.viewmodel.SettingsRepository
import fm.magiclantern.forum.features.player.PlaybackContext
import fm.magiclantern.forum.features.player.PlaybackEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for video playback controls.
 * 
 * Observes ActiveClipHolder for clip data instead of receiving setMetadata() calls.
 * Only manages playback state (play/pause/seek), not clip metadata storage.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val activeClipHolder: ActiveClipHolder
) : ViewModel() {

    private val tag = "PlayerViewModel"

    // Playback state
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _isDrawing = MutableStateFlow(false)
    val isDrawing: StateFlow<Boolean> = _isDrawing

    private val _currentFrame = MutableStateFlow(0)
    val currentFrame: StateFlow<Int> = _currentFrame

    // Drop frame mode
    private val _isDropFrameMode = MutableStateFlow(true)
    val isDropFrameMode: StateFlow<Boolean> = _isDropFrameMode

    // Performance timing
    private val _averageDecodeUs = MutableStateFlow(0L)
    val averageDecodeUs: StateFlow<Long> = _averageDecodeUs

    private val _averageRenderUs = MutableStateFlow(0L)
    val averageRenderUs: StateFlow<Long> = _averageRenderUs

    private val _averageProcessingUs = MutableStateFlow(0L)
    val averageProcessingUs: StateFlow<Long> = _averageProcessingUs

    // Debayer mode
    private val _debayerMode = MutableStateFlow(DebayerMode.AMAZE)
    val debayerMode: StateFlow<DebayerMode> = _debayerMode

    private var decodeEmaUs = 0.0
    private var renderEmaUs = 0.0
    private val smoothing = 0.1
    
    // Shared frame buffer for all renderers - prevents OOM during rapid view transitions
    @Volatile
    private var sharedFrameBuffer: java.nio.ByteBuffer? = null
    private val bufferLock = Any()
    
    /**
     * Gets or allocates a shared frame buffer for rendering.
     * All MlvRenderers share this buffer to prevent OOM during rapid fullscreen toggles.
     */
    fun getOrAllocateFrameBuffer(width: Int, height: Int): java.nio.ByteBuffer? {
        if (width <= 0 || height <= 0) return null
        val needed = width * height * 3 * 4 // RGB 32-bit float
        synchronized(bufferLock) {
            val current = sharedFrameBuffer
            if (current == null || current.capacity() != needed) {
                sharedFrameBuffer = java.nio.ByteBuffer.allocateDirect(needed)
                    .order(java.nio.ByteOrder.nativeOrder())
            }
            return sharedFrameBuffer
        }
    }
    
    /**
     * Releases the shared frame buffer to free memory.
     */
    private fun releaseFrameBuffer() {
        synchronized(bufferLock) {
            sharedFrameBuffer = null
        }
    }

    // Derived from active clip
    val activeClip: StateFlow<ClipDetails?> = activeClipHolder.activeClip
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val clipHandle: StateFlow<Long> = activeClipHolder.activeClip
        .map { it?.nativeHandle ?: 0L }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    val clipGUID: StateFlow<Long> = activeClipHolder.activeClip
        .map { it?.guid ?: 0L }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    val totalFrames: StateFlow<Int> = activeClipHolder.activeClip
        .map { it?.frames ?: 0 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val fps: StateFlow<Float> = activeClipHolder.activeClip
        .map { it?.fps ?: 0f }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0f)

    val width: StateFlow<Int> = activeClipHolder.activeClip
        .map { it?.width ?: 0 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val height: StateFlow<Int> = activeClipHolder.activeClip
        .map { it?.height ?: 0 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val hasAudio: StateFlow<Boolean> = activeClipHolder.activeClip
        .map { it?.hasAudio ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isMcraw: StateFlow<Boolean> = activeClipHolder.activeClip
        .map { it?.isMcraw ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val processingData: StateFlow<fm.magiclantern.forum.domain.model.ClipProcessingData> = activeClipHolder.activeClip
        .map { it?.processing ?: fm.magiclantern.forum.domain.model.ClipProcessingData() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, fm.magiclantern.forum.domain.model.ClipProcessingData())

    /**
     * Increments when processing parameters change. Observe to trigger redraws.
     */
    val processingVersion: StateFlow<Long> = activeClipHolder.processingVersion


    private val playbackEngine = PlaybackEngine(
        scope = viewModelScope,
        frameUpdater = { frame -> onEngineFrame(frame) },
        onPlaybackStopped = { onEnginePlaybackStopped() },
        currentFrameProvider = { _currentFrame.value },
        averageProcessingUsProvider = { _averageProcessingUs.value }
    )

    init {
        // Observe settings
        viewModelScope.launch {
            settingsRepository.dropFrameMode.collectLatest { enabled ->
                _isDropFrameMode.value = enabled
                playbackEngine.setDropFrameMode(enabled)
            }
        }
        viewModelScope.launch {
            settingsRepository.debayerMode.collectLatest { mode ->
                _debayerMode.value = mode
                // Only apply playback debayer if currently playing
                if (_isPlaying.value) {
                    applyDebayerMode(mode)
                }
            }
        }
        
        // Observe receipt debayer mode changes (from grading screen)
        viewModelScope.launch {
            activeClipHolder.currentReceiptDebayerMode.collectLatest { mode ->
                // Only apply receipt debayer if currently paused
                if (!_isPlaying.value && clipHandle.value != 0L) {
                    NativeLib.setDebayerMode(clipHandle.value, mode.nativeId)
                }
            }
        }
        
        // Observe active clip changes
        viewModelScope.launch {
            activeClipHolder.activeClip.collectLatest { details ->
                if (details != null) {
                    onClipActivated(details)
                } else {
                    onClipDeactivated()
                }
            }
        }
    }

    private fun onClipActivated(details: ClipDetails) {
        playbackEngine.stop()
        
        val metadata = details.metadata
        
        // Reset timing
        decodeEmaUs = 0.0
        renderEmaUs = 0.0
        _averageDecodeUs.value = 0L
        _averageRenderUs.value = 0L
        _averageProcessingUs.value = 0L
        
        // Reset playback state
        _currentFrame.value = 0
        _isPlaying.value = false
        
        // Clip starts in paused state, so use receipt debayer for quality preview
        // Note: The receipt debayer will be set by GradingViewModel via ActiveClipHolder
        // This is a fallback in case grading hasn't loaded yet
        applyReceiptDebayerMode()
        
        // Update playback engine context
        playbackEngine.updateContext(
            PlaybackContext(
                clipHandle = details.nativeHandle,
                frameCount = metadata.frames,
                fps = metadata.fps,
                frameTimestamps = metadata.frameTimestamps,
                hasAudio = metadata.hasAudio,
                audioSampleRate = metadata.audioSampleRate,
                audioChannels = metadata.audioChannels,
                audioBytesPerSample = metadata.audioBytesPerSample,
                audioBufferSize = metadata.audioBufferSize
            )
        )
    }

    private fun onClipDeactivated() {
        playbackEngine.stop()
        _currentFrame.value = 0
        _isPlaying.value = false
        decodeEmaUs = 0.0
        renderEmaUs = 0.0
        _averageDecodeUs.value = 0L
        _averageRenderUs.value = 0L  
        _averageProcessingUs.value = 0L
    }

    fun setCurrentFrame(currentFrame: Int) {
        val total = totalFrames.value
        if (total <= 0) return
        val clamped = currentFrame.coerceIn(0, total - 1)
        _currentFrame.value = clamped
        if (!_isDropFrameMode.value) {
            _isDrawing.value = true
        }
        playbackEngine.onSeek(clamped)
    }

    fun togglePlayback() {
        val shouldPlay = !_isPlaying.value
        _isPlaying.value = shouldPlay
        if (shouldPlay) {
            // Playing: Use settings debayer (fast algorithm for smooth playback)
            applyDebayerMode(_debayerMode.value)
            playbackEngine.play()
        } else {
            // Paused: Use receipt debayer (quality algorithm for accurate preview)
            applyReceiptDebayerMode()
            playbackEngine.pause()
        }
    }

    fun stopPlayback() {
        playbackEngine.stop()
        _isPlaying.value = false
    }

    fun changeDrawingStatus(status: Boolean) {
        _isDrawing.value = status
        if (!status && _isPlaying.value && !_isDropFrameMode.value) {
            playbackEngine.advanceFrameSequential()
        }
    }

    fun changeLoadingStatus(status: Boolean) {
        _isLoading.value = status
    }

    fun nextFrame() {
        if (currentFrame.value < totalFrames.value - 1) {
            playbackEngine.pause()
            _isPlaying.value = false
            setCurrentFrame(currentFrame.value + 1)
        }
    }

    fun previousFrame() {
        if (currentFrame.value > 0) {
            playbackEngine.pause()
            _isPlaying.value = false
            setCurrentFrame(currentFrame.value - 1)
        }
    }

    fun goToFirstFrame() {
        playbackEngine.pause()
        _isPlaying.value = false
        setCurrentFrame(0)
    }

    fun goToLastFrame() {
        val last = (totalFrames.value - 1).coerceAtLeast(0)
        playbackEngine.pause()
        _isPlaying.value = false
        setCurrentFrame(last)
    }

    fun setDropFrameMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDropFrameMode(enabled)
        }
    }

    fun setDebayerMode(mode: DebayerMode) {
        viewModelScope.launch {
            settingsRepository.setDebayerMode(mode)
        }
    }

    fun reportFrameTiming(decodeNs: Long, renderNs: Long) {
        val decodeUs = decodeNs / 1000.0
        val renderUs = renderNs / 1000.0

        if (!decodeUs.isNaN() && decodeUs.isFinite()) {
            decodeEmaUs = if (decodeEmaUs == 0.0) {
                decodeUs
            } else {
                decodeEmaUs * (1 - smoothing) + decodeUs * smoothing
            }
            _averageDecodeUs.value = decodeEmaUs.toLong()
        }

        if (!renderUs.isNaN() && renderUs.isFinite()) {
            renderEmaUs = if (renderEmaUs == 0.0) {
                renderUs
            } else {
                renderEmaUs * (1 - smoothing) + renderUs * smoothing
            }
            _averageRenderUs.value = renderEmaUs.toLong()
        }
        _averageProcessingUs.value = (decodeEmaUs + renderEmaUs).toLong()
    }

    override fun onCleared() {
        playbackEngine.stop()
        // Note: Native clip handle is managed by ActiveClipHolder, not here
        super.onCleared()
    }

    private fun onEngineFrame(frame: Int) {
        val total = totalFrames.value
        if (total <= 0) return
        val clamped = frame.coerceIn(0, total - 1)
        _currentFrame.value = clamped
        if (!_isDropFrameMode.value) {
            _isDrawing.value = true
        }
    }

    private fun onEnginePlaybackStopped() {
        _isPlaying.value = false
    }

    private fun applyDebayerMode(mode: DebayerMode) {
        val handle = clipHandle.value
        if (handle == 0L) return
        NativeLib.setDebayerMode(handle, mode.nativeId)
    }
    
    /**
     * Apply the current clip's receipt debayer mode (from grading settings).
     * Used when playback is paused for high-quality preview matching export output.
     */
    private fun applyReceiptDebayerMode() {
        val handle = clipHandle.value
        if (handle == 0L) return
        val receiptMode = activeClipHolder.currentReceiptDebayerMode.value
        NativeLib.setDebayerMode(handle, receiptMode.nativeId)
    }
}
