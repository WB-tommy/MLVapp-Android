package fm.magiclantern.forum.videoPlayer

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fm.magiclantern.forum.nativeInterface.NativeLib
import fm.magiclantern.forum.data.Clip
import fm.magiclantern.forum.data.ClipProcessingData
import fm.magiclantern.forum.settings.DebayerMode
import fm.magiclantern.forum.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class VideoViewModel(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val tag = "VideoViewModel"

    // true => real-time drop-frame playback, false => single-step playback
    private val _isDropFrameMode = MutableStateFlow(true)
    val isDropFrameMode: StateFlow<Boolean> = _isDropFrameMode

    private val _clipHandle = MutableStateFlow(0L)
    val clipHandle: StateFlow<Long> = _clipHandle

    private val _clipGUID = MutableStateFlow(0L)
    val clipGUID: StateFlow<Long> = _clipGUID

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _isDrawing = MutableStateFlow(false)
    val isDrawing: StateFlow<Boolean> = _isDrawing

    private val _currentFrame = MutableStateFlow(0)
    val currentFrame: StateFlow<Int> = _currentFrame

    private val _totalFrames = MutableStateFlow(0)
    val totalFrames: StateFlow<Int> = _totalFrames

    private val _fps = MutableStateFlow(0f)
    val fps: StateFlow<Float> = _fps

    private val _width = MutableStateFlow(0)
    val width: StateFlow<Int> = _width

    private val _height = MutableStateFlow(0)
    val height: StateFlow<Int> = _height

    private val _frameTimestamps = MutableStateFlow(LongArray(0))
    val frameTimestamps: StateFlow<LongArray> = _frameTimestamps

    private val _hasAudio = MutableStateFlow(false)
    val hasAudio: StateFlow<Boolean> = _hasAudio

    private val _audioBytesPerSample = MutableStateFlow(0)
    val audioBytesPerSample: StateFlow<Int> = _audioBytesPerSample

    private val _audioBufferSize = MutableStateFlow(0L)
    val audioBufferSize: StateFlow<Long> = _audioBufferSize

    private val _audioChannels = MutableStateFlow(0)
    val audioChannels: StateFlow<Int> = _audioChannels

    private val _audioSampleRate = MutableStateFlow(0)
    val audioSampleRate: StateFlow<Int> = _audioSampleRate

    private val _averageDecodeUs = MutableStateFlow(0L)
    val averageDecodeUs: StateFlow<Long> = _averageDecodeUs

    private val _averageRenderUs = MutableStateFlow(0L)
    val averageRenderUs: StateFlow<Long> = _averageRenderUs

    private val _averageProcessingUs = MutableStateFlow(0L)
    val averageProcessingUs: StateFlow<Long> = _averageProcessingUs

    private val _isMcraw = MutableStateFlow(false)
    val isMcraw: StateFlow<Boolean> = _isMcraw

    private val _debayerMode = MutableStateFlow(DebayerMode.ALWAYS_AMAZE)
    val debayerMode: StateFlow<DebayerMode> = _debayerMode

    private val _processingData = MutableStateFlow(ClipProcessingData())
    val processingData: StateFlow<ClipProcessingData> = _processingData

    private var decodeEmaUs = 0.0
    private var renderEmaUs = 0.0
    private val smoothing = 0.1

    private val playbackEngine = PlaybackEngine(
        scope = viewModelScope,
        frameUpdater = { frame -> onEngineFrame(frame) },
        onPlaybackStopped = { onEnginePlaybackStopped() },
        currentFrameProvider = { _currentFrame.value },
        averageProcessingUsProvider = { _averageProcessingUs.value }
    )

    init {
        viewModelScope.launch {
            settingsRepository.dropFrameMode.collectLatest { enabled ->
                _isDropFrameMode.value = enabled
                playbackEngine.setDropFrameMode(enabled)
            }
        }
        viewModelScope.launch {
            settingsRepository.debayerMode.collectLatest { mode ->
                _debayerMode.value = mode
                applyDebayerMode(mode)
            }
        }
    }

    fun setMetadata(clip: Clip) {
        playbackEngine.stop()

        _clipGUID.value = clip.guid
        _clipHandle.value = clip.nativeHandle
        val totalFramesCount = clip.frames ?: clip.frameTimestamps.size
        _totalFrames.value = totalFramesCount
        val fpsValue = clip.fps ?: 0f
        _fps.value = fpsValue
        _width.value = clip.width
        _height.value = clip.height
        _isMcraw.value = clip.isMcraw
        _processingData.value = clip.processing

        val timestamps = clip.frameTimestamps
        _frameTimestamps.value = timestamps
        if (Log.isLoggable(tag, Log.DEBUG)) {
            val sample = timestamps.take(5).joinToString()
            Log.d(
                tag,
                "setMetadata clipGuid=${clip.guid} timestamps=${timestamps.size} first=$sample isMcraw=${clip.isMcraw}"
            )
        }

        val audioChannels = clip.audioChannel ?: 0
        val audioSampleRate = (clip.audioSampleRate ?: 0L).toInt()
        val audioBytesPerSample = clip.audioBytesPerSample
        val audioBufferSize = clip.audioBufferSize
        val hasAudio =
            clip.hasAudio &&
                audioChannels > 0 &&
                audioSampleRate > 0 &&
                audioBytesPerSample > 0 &&
                audioBufferSize > 0L
        val sanitizedAudioChannels = if (hasAudio) audioChannels else 0
        val sanitizedSampleRate = if (hasAudio) audioSampleRate else 0
        val sanitizedBytesPerSample = if (hasAudio) audioBytesPerSample else 0
        val sanitizedBufferSize = if (hasAudio) audioBufferSize else 0L

        _hasAudio.value = hasAudio
        _audioBytesPerSample.value = sanitizedBytesPerSample
        _audioBufferSize.value = sanitizedBufferSize
        _audioChannels.value = sanitizedAudioChannels
        _audioSampleRate.value = sanitizedSampleRate

        decodeEmaUs = 0.0
        renderEmaUs = 0.0
        _averageDecodeUs.value = 0L
        _averageRenderUs.value = 0L
        _averageProcessingUs.value = 0L

        _currentFrame.value = 0
        _isPlaying.value = false

        applyDebayerMode(_debayerMode.value)

        playbackEngine.updateContext(
            PlaybackContext(
                clipHandle = clip.nativeHandle,
                frameCount = totalFramesCount,
                fps = fpsValue,
                frameTimestamps = timestamps,
                hasAudio = hasAudio,
                audioSampleRate = sanitizedSampleRate,
                audioChannels = sanitizedAudioChannels,
                audioBytesPerSample = sanitizedBytesPerSample,
                audioBufferSize = sanitizedBufferSize
            )
        )
    }

    fun setCurrentFrame(currentFrame: Int) {
        val total = _totalFrames.value
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
            playbackEngine.play()
        } else {
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

    fun releaseCurrentClip() {
        Log.d(tag, "releaseCurrentClip: Releasing clip handle ${clipHandle.value}")
        playbackEngine.stop()
        NativeLib.closeClip(clipHandle.value)
        _clipHandle.value = 0L
        _clipGUID.value = 0L
        _totalFrames.value = 0
        _fps.value = 0f
        _width.value = 0
        _height.value = 0
        _frameTimestamps.value = LongArray(0)
        _hasAudio.value = false
        _audioBytesPerSample.value = 0
        _audioBufferSize.value = 0L
        _audioChannels.value = 0
        _audioSampleRate.value = 0
        _isMcraw.value = false
        _processingData.value = ClipProcessingData()
        decodeEmaUs = 0.0
        renderEmaUs = 0.0
        _averageDecodeUs.value = 0L
        _averageRenderUs.value = 0L
        _averageProcessingUs.value = 0L
        // Reset playback state for the new clip
        _currentFrame.value = 0
        _isPlaying.value = false
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
        Log.d(tag, "onCleared: Closing clip handle ${clipHandle.value}")
        playbackEngine.stop()
        NativeLib.closeClip(clipHandle.value)
        super.onCleared()
    }

    private fun onEngineFrame(frame: Int) {
        val total = _totalFrames.value
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
}
