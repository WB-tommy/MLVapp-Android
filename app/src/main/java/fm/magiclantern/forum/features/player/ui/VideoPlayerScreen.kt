package fm.magiclantern.forum.features.player.ui

import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavHostController
import fm.magiclantern.forum.features.player.MlvRenderer
import fm.magiclantern.forum.features.player.viewmodel.PlayerViewModel

private const val FULLSCREEN_DEBOUNCE_MS = 500L

@Composable
fun VideoPlayerScreen(
    navController: NavHostController,
    screenWidth: Float,
    viewModel: PlayerViewModel,
    cpuCores: Int,
) {
    val clipGUID by viewModel.clipGUID.collectAsState()
    val clipHandle by viewModel.clipHandle.collectAsState()
    val currentFrame by viewModel.currentFrame.collectAsState()
    val processingVersion by viewModel.processingVersion.collectAsState()
    
    // Debounce for fullscreen navigation to prevent crashes from rapid toggling
    val lastFullscreenNavigationTime = remember { mutableLongStateOf(0L) }


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
                            setZOrderMediaOverlay(true)
                            holder.setFormat(PixelFormat.TRANSLUCENT)
                            setRenderer(MlvRenderer(cpuCores, viewModel))
                            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                        }
                    },
                    update = { glSurfaceView ->
                        // Read both to trigger recomposition on either change
                        currentFrame.let { _ -> }
                        processingVersion.let { _ -> }
                        glSurfaceView.requestRender()
                    },

                    modifier = Modifier.fillMaxSize()
                )

                IconButton(
                    modifier = Modifier.align(Alignment.BottomEnd),
                    onClick = {
                        val now = System.currentTimeMillis()
                        if (now - lastFullscreenNavigationTime.longValue >= FULLSCREEN_DEBOUNCE_MS) {
                            lastFullscreenNavigationTime.longValue = now
                            navController.navigate("fullscreen")
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Fullscreen,
                        contentDescription = "Go to fullscreen mode",
                        tint = Color.White
                    )
                }
            }
        }
    }
}