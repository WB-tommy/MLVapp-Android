package fm.forum.mlvapp.videoPlayer

import android.opengl.GLSurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun VideoPlayerScreen(
    viewModel: VideoViewModel = viewModel(),
    cpuCores: Int,
) {
    val clipHandle by viewModel.clipHandle.collectAsState()
    // By collecting the frame state, we ensure this screen recomposes when the frame changes.
    val currentFrame by viewModel.currentFrame.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(Color.Black)
    ) {
        if (clipHandle != 0L) {
            AndroidView(
                factory = { context ->
                    GLSurfaceView(context).apply {
                        setEGLContextClientVersion(3)
                        setRenderer(MlvRenderer(cpuCores, viewModel))
                        renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                    }
                },
                update = { view ->
                    // When recomposition happens (because currentFrame changed),
                    // we tell the GLSurfaceView that it needs to redraw.
                    currentFrame.let { // This just ensures we are using the state value
                        view.requestRender()
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
