package fm.forum.mlvapp.videoPlayer

import androidx.lifecycle.ViewModel
import fm.forum.mlvapp.NativeInterface.NativeLib
import fm.forum.mlvapp.data.Clip
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class VideoViewModel : ViewModel() {

    // 1 means play every frame mode,
    // 2 means drop frame mode which is sync to audio
    private val _playMode = MutableStateFlow(1)
    val playMode: StateFlow<Int> = _playMode

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

    fun setMetadata(clip: Clip) {
        _clipGUID.value = clip.guid
        _clipHandle.value = clip.nativeHandle!!
        _totalFrames.value = clip.frames!!
        _fps.value = clip.fps!!
        _width.value = clip.width
        _height.value = clip.height
        // Reset playback state for the new clip
        _currentFrame.value = 0
        _isPlaying.value = false
    }

    fun setCurrentFrame(currentFrame: Int) {
        if (currentFrame >= 0 && currentFrame < totalFrames.value) {
            _currentFrame.value = currentFrame
        }
    }

    fun togglePlayback() {
        _isPlaying.value = !_isPlaying.value
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
        // Reset playback state for the new clip
        _currentFrame.value = 0
        _isPlaying.value = false
    }

    override fun onCleared() {
        NativeLib.closeClip(clipHandle.value)
        super.onCleared()
    }
}
