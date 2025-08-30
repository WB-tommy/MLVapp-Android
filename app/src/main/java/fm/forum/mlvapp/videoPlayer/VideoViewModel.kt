package fm.forum.mlvapp.videoPlayer

import androidx.lifecycle.ViewModel
import fm.forum.mlvapp.NativeInterface.NativeLib
import fm.forum.mlvapp.data.Clip
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class VideoViewModel : ViewModel() {

//    // If you want to hold a Clip here, make it nullable since initial state is null
//    private val _clip = MutableStateFlow<Clip?>(null)
//    val clip: StateFlow<Clip?> = _clip

    private val _clipHandle = MutableStateFlow(0L)
    val clipHandle: StateFlow<Long> = _clipHandle

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

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

    private val _lastFrameTimeNanos = MutableStateFlow(0L)
    val lastFrameTimeNanos: StateFlow<Long> = _lastFrameTimeNanos

    fun setLastFrameTime(time: Long) {
        _lastFrameTimeNanos.value = time
    }

    fun setMetadata(clip: Clip) {
        _clipHandle.value = clip.nativeHandle!!
        _totalFrames.value = clip.frames!!
        _fps.value = clip.fps!!
        _width.value = clip.width
        _height.value = clip.height
        // Reset playback state for the new clip
        _currentFrame.value = 0
        _isPlaying.value = false
        _lastFrameTimeNanos.value = 0L
    }

    fun setCurrentFrame(currentFrame: Int) {
        if (currentFrame >= 0 && currentFrame < totalFrames.value) {
            _currentFrame.value = currentFrame
        }
    }

    fun togglePlayback() {
        _isPlaying.value = !_isPlaying.value
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

    fun loopToStart() {
        _currentFrame.value = 0
    }

    override fun onCleared() {
        NativeLib.closeClip(clipHandle.value)
        super.onCleared()
    }
}
