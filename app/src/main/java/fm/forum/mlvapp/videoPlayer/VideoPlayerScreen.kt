package fm.forum.mlvapp.videoPlayer

import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay

@Composable
fun VideoPlayerScreen(
    viewModel: VideoViewModel = viewModel(),
    cpuCores: Int,
) {
    val clipGUID by viewModel.clipGUID.collectAsState()
    val clipHandle by viewModel.clipHandle.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val isDrawing by viewModel.isDrawing.collectAsState()
    val currentFrame by viewModel.currentFrame.collectAsState()
    val totalFrames by viewModel.totalFrames.collectAsState()
    val fps by viewModel.fps.collectAsState()
    val playMode by viewModel.playMode.collectAsState()

    // Effect for fast, frame-by-frame playback (playMode == 1)
    // This is event-driven. It waits for `isDrawing` to be false before advancing.
    LaunchedEffect(isPlaying, playMode, isDrawing, clipHandle) {
        if (isPlaying && playMode == 1 && !isDrawing) {
            if (currentFrame < totalFrames) {
                viewModel.changeDrawingStatus(true)
                val nextFrame = currentFrame + 1
                viewModel.setCurrentFrame(nextFrame)
            }
        }
    }

    // Effect for timed playback (other playModes)
    // This is time-driven, advancing frames based on FPS.
    LaunchedEffect(isPlaying, playMode, clipHandle) {
        if (isPlaying && playMode == 2) {
            // This loop is okay because it suspends with delay()
            if (fps <= 0f) return@LaunchedEffect
            val frameDurationMillis = (1000 / fps).toLong()
            while (currentFrame < totalFrames) { // Loop will be cancelled when isPlaying becomes false
                val startTime = System.currentTimeMillis()

                // Advance frame
                val nextFrame = currentFrame + 1
                viewModel.setCurrentFrame(nextFrame)

                // Wait for next frame
                val elapsedTime = System.currentTimeMillis() - startTime
                val delayTime = (frameDurationMillis - elapsedTime).coerceAtLeast(0)
                delay(delayTime)
            }
        }
    }

    Box(
        modifier = Modifier
            .aspectRatio(16f / 9f)
            .background(Color.Black)
    ) {
        key(clipGUID) {
            if (clipHandle != 0L) {
                AndroidView(
                    factory = { context ->
                        GLSurfaceView(context).apply {
                            setEGLContextClientVersion(3)
                            setZOrderOnTop(true)
                            holder.setFormat(PixelFormat.TRANSLUCENT)
                            setRenderer(MlvRenderer(cpuCores, viewModel))
                            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                        }
                    },
                    update = { glSurfaceView ->
                        // When recomposition happens (because currentFrame changed),
                        // we tell the GLSurfaceView that it needs to redraw.
                        currentFrame.let { // This just ensures we are using the state value
                            glSurfaceView.requestRender()
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
