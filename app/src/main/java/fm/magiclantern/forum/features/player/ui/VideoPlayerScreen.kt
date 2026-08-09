package fm.magiclantern.forum.features.player.ui

import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import fm.magiclantern.forum.features.grading.viewmodel.GradingViewModel
import fm.magiclantern.forum.features.player.MlvRenderer
import fm.magiclantern.forum.features.player.viewmodel.PlayerViewModel

private const val FULLSCREEN_DEBOUNCE_MS = 500L

@Composable
fun VideoPlayerScreen(
    navController: NavHostController,
    screenWidth: Float,
    viewModel: PlayerViewModel,
    cpuCores: Int,
    gradingViewModel: GradingViewModel,
) {
    val clipGUID by viewModel.clipGUID.collectAsState()
    val clipHandle by viewModel.clipHandle.collectAsState()
    val currentFrame by viewModel.currentFrame.collectAsState()
    val processingVersion by viewModel.processingVersion.collectAsState()
    val experimentalRawGpuPreview by viewModel.experimentalRawGpuPreview.collectAsState()
    val experimentalMcrawParallelDecoder by viewModel.experimentalMcrawParallelDecoder.collectAsState()
    val activeClip by viewModel.activeClip.collectAsState()
    val whiteBalancePickerActive by
        gradingViewModel.whiteBalancePickerActive.collectAsState()
    val whiteBalancePickInProgress by
        gradingViewModel.whiteBalancePickInProgress.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val settledStillPreview by
        viewModel.settledStillPreview.collectAsState()

    LaunchedEffect(whiteBalancePickerActive, isPlaying) {
        if (whiteBalancePickerActive && isPlaying) {
            viewModel.togglePlayback()
        }
    }
    
    // Debounce for fullscreen navigation to prevent crashes from rapid toggling
    val lastFullscreenNavigationTime = remember { mutableLongStateOf(0L) }


    Box(
        modifier = Modifier
            .aspectRatio(screenWidth / 9f)
            .background(Color.Black)
    ) {
        key(clipGUID) {
            if (clipHandle != 0L) {
                val renderer = remember { MlvRenderer(cpuCores, viewModel) }
                AndroidView(
                    factory = { context ->
                        GLSurfaceView(context).apply {
                            setEGLContextClientVersion(3)
                            setZOrderMediaOverlay(true)
                            holder.setFormat(PixelFormat.TRANSLUCENT)
                            setRenderer(renderer)
                            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                        }
                    },
                    update = { glSurfaceView ->
                        // Redraw for frame, processing, renderer-policy, or settled-still changes.
                        currentFrame.let { _ -> }
                        processingVersion.let { _ -> }
                        experimentalRawGpuPreview.let { _ -> }
                        experimentalMcrawParallelDecoder.let { _ -> }
                        settledStillPreview.let { _ -> }
                        glSurfaceView.requestRender()
                    },
                    onRelease = { glSurfaceView ->
                        glSurfaceView.onPause()
                        renderer.onSurfaceDestroyed()
                    },

                    modifier = Modifier.fillMaxSize()
                )

                if (whiteBalancePickerActive) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .pointerInput(
                                clipHandle,
                                activeClip,
                                whiteBalancePickInProgress,
                                isPlaying
                            ) {
                                detectTapGestures { tap ->
                                    if (whiteBalancePickInProgress || isPlaying) {
                                        return@detectTapGestures
                                    }
                                    val pickerClip = activeClip ?: return@detectTapGestures
                                    if (pickerClip.nativeHandle != clipHandle) {
                                        return@detectTapGestures
                                    }
                                    val source = mapPreviewTapToSource(
                                        tapX = tap.x,
                                        tapY = tap.y,
                                        surfaceWidth = size.width,
                                        surfaceHeight = size.height,
                                        sourceWidth = pickerClip.width,
                                        sourceHeight = pickerClip.height,
                                        stretchX = pickerClip.processing.stretchFactorX,
                                        stretchY = pickerClip.processing.stretchFactorY
                                    ) ?: return@detectTapGestures
                                    gradingViewModel.pickWhiteBalance(
                                        expectedHandle = pickerClip.nativeHandle,
                                        expectedGuid = pickerClip.guid,
                                        frameIndex = viewModel.presentedFrameFor(
                                            pickerClip.nativeHandle
                                        ) ?: return@detectTapGestures,
                                        sourceX = source.x,
                                        sourceY = source.y
                                    )
                                }
                            }
                    )
                    Text(
                        text = when {
                            isPlaying -> "Pausing playback…"
                            whiteBalancePickInProgress -> "Finding white balance…"
                            else -> "Tap the image to pick white balance"
                        },
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .background(Color.Black.copy(alpha = 0.65f))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                if (!whiteBalancePickerActive) {
                    IconButton(
                        modifier = Modifier.align(Alignment.BottomEnd),
                        onClick = {
                            val now = System.currentTimeMillis()
                            if (now - lastFullscreenNavigationTime.longValue >=
                                FULLSCREEN_DEBOUNCE_MS
                            ) {
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
}
