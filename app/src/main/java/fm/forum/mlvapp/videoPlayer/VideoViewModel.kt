package fm.forum.mlvapp.videoPlayer

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fm.forum.mlvapp.NativeInterface.NativeLib
import fm.forum.mlvapp.data.Clip
import fm.forum.mlvapp.settings.DebayerMode
import fm.forum.mlvapp.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.min

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

    private var decodeEmaUs = 0.0
    private var renderEmaUs = 0.0
    private val smoothing = 0.1

    init {
        viewModelScope.launch {
            settingsRepository.dropFrameMode.collectLatest { enabled ->
                _isDropFrameMode.value = enabled
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
        _clipGUID.value = clip.guid
        _clipHandle.value = clip.nativeHandle
        _totalFrames.value = clip.frames!!
        _fps.value = clip.fps!!
        _width.value = clip.width
        _height.value = clip.height
        _isMcraw.value = clip.isMcraw
        val sanitizedTimestamps = sanitizeTimestamps(
            clip.frameTimestamps,
            clip.fps ?: 0f,
            clip.isMcraw,
            clip.frames ?: clip.frameTimestamps.size
        )
        _frameTimestamps.value = sanitizedTimestamps
        if (Log.isLoggable(tag, Log.DEBUG)) {
            val sample = sanitizedTimestamps.take(5).joinToString()
            Log.d(
                tag,
                "setMetadata clipGuid=${clip.guid} timestamps=${sanitizedTimestamps.size} first=$sample sanitized=${sanitizedTimestamps !== clip.frameTimestamps} isMcraw=${clip.isMcraw}"
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
        _hasAudio.value = hasAudio
        _audioBytesPerSample.value = if (hasAudio) audioBytesPerSample else 0
        _audioBufferSize.value = if (hasAudio) audioBufferSize else 0L
        _audioChannels.value = if (hasAudio) audioChannels else 0
        _audioSampleRate.value = if (hasAudio) audioSampleRate else 0
        decodeEmaUs = 0.0
        renderEmaUs = 0.0
        _averageDecodeUs.value = 0L
        _averageRenderUs.value = 0L
        _averageProcessingUs.value = 0L
        // Reset playback state for the new clip
        _currentFrame.value = 0
        _isPlaying.value = false
        applyDebayerMode(_debayerMode.value)
    }

    fun setCurrentFrame(currentFrame: Int) {
        if (currentFrame >= 0 && currentFrame < totalFrames.value) {
            _currentFrame.value = currentFrame
        }
    }

    fun togglePlayback() {
        _isPlaying.value = !_isPlaying.value
    }

    fun stopPlayback() {
        _isPlaying.value = false
    }

    fun changeDrawingStatus(status: Boolean) {
        _isDrawing.value = status
    }

    fun changeLoadingStatus(status: Boolean) {
        _isLoading.value = status
    }

    fun nextFrame() {
        if (currentFrame.value < totalFrames.value - 1) {
            _isPlaying.value = false
            _currentFrame.value++
        }
    }

    fun previousFrame() {
        if (currentFrame.value > 0) {
            _isPlaying.value = false
            _currentFrame.value--
        }
    }

    fun goToFirstFrame() {
        _isPlaying.value = false
        _currentFrame.value = 0
    }

    fun goToLastFrame() {
        _isPlaying.value = false
        _currentFrame.value = totalFrames.value - 1
    }

    fun releaseCurrentClip() {
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
        NativeLib.closeClip(clipHandle.value)
        super.onCleared()
    }

    private fun applyDebayerMode(mode: DebayerMode) {
        val handle = clipHandle.value
        if (handle == 0L) return
        NativeLib.setDebayerMode(handle, mode.nativeId)
    }

    private fun sanitizeTimestamps(
        raw: LongArray,
        fps: Float,
        isMcraw: Boolean,
        frameCountHint: Int
    ): LongArray {
        val targetCount = when {
            frameCountHint > 0 -> frameCountHint
            raw.isNotEmpty() -> raw.size
            else -> 0
        }
        if (targetCount <= 0) return LongArray(0)

        val frameDurationUs = if (fps > 0f) {
            (1_000_000f / fps).toLong().coerceAtLeast(1L)
        } else {
            41_667L // ~24fps
        }
        fun syntheticTimeline(): LongArray = LongArray(targetCount) { i -> i * frameDurationUs }

        if (raw.isEmpty()) return syntheticTimeline()

        val inspected = if (raw.size == targetCount) {
            raw
        } else {
            LongArray(targetCount).also { adjusted ->
                val copyCount = min(raw.size, targetCount)
                raw.copyInto(adjusted, endIndex = copyCount)
                if (copyCount <= 0) {
                    for (i in 0 until targetCount) {
                        adjusted[i] = i * frameDurationUs
                    }
                } else {
                    var last = adjusted[copyCount - 1]
                    for (i in copyCount until targetCount) {
                        last += frameDurationUs
                        adjusted[i] = last
                    }
                }
            }
        }

        val minExpectedDelta = (frameDurationUs / 5).coerceAtLeast(1L) // allow some jitter
        val maxExpectedDelta = frameDurationUs * 5

        var nonIncreasingCount = 0
        var aberrantDeltaCount = 0

        var prev = inspected[0]
        for (i in 1 until inspected.size) {
            val ts = inspected[i]
            val delta = ts - prev
            if (delta <= 0L) {
                nonIncreasingCount++
            } else if (delta < minExpectedDelta || delta > maxExpectedDelta) {
                aberrantDeltaCount++
            }
            prev = ts
        }

        if (nonIncreasingCount == 0 && aberrantDeltaCount == 0) {
            return inspected
        }

        Log.w(
            tag,
            "sanitizeTimestamps fallback applied (nonMonotonic=$nonIncreasingCount, aberrant=$aberrantDeltaCount), fps=$fps, frameDurationUs=$frameDurationUs"
        )
        return syntheticTimeline()
    }
}
