package fm.forum.mlvapp.videoPlayer

import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun VideoPlayerScreen(
    screenWidth: Float,
    viewModel: VideoViewModel,
    cpuCores: Int,
) {
    val clipGUID by viewModel.clipGUID.collectAsState()
    val clipHandle by viewModel.clipHandle.collectAsState()
    val currentFrame by viewModel.currentFrame.collectAsState()

    Box(
        modifier = Modifier
            .aspectRatio(screenWidth / 9f)
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
                        currentFrame.let {
                            glSurfaceView.requestRender()
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
