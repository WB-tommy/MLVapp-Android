package fm.magiclantern.forum.features.player.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import fm.magiclantern.forum.features.player.MlvRenderer
import fm.magiclantern.forum.features.player.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay

private const val AUTO_HIDE_DELAY_MILLIS = 2000L

@Composable
fun FullScreenView(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    cpuCores: Int,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    val context = LocalContext.current

    DisposableEffect(lifecycleOwner) {
        val activity = (context as? Activity) ?: return@DisposableEffect onDispose {}
        val window = activity.window
        val insetsController = WindowCompat.getInsetsController(window, view)

        // Set landscape orientation
        val originalOrientation = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        // Hide the system bars
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        onDispose {
            // Restore the system bars and orientation
            insetsController.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = originalOrientation
        }
    }

    val clipGUID by playerViewModel.clipGUID.collectAsState()
    val clipHandle by playerViewModel.clipHandle.collectAsState()
    val currentFrame by playerViewModel.currentFrame.collectAsState()
    val processingVersion by playerViewModel.processingVersion.collectAsState()

    var controlsVisible by rememberSaveable(clipGUID) { mutableStateOf(true) }
    var autoHideJobKey by remember(clipGUID) { mutableLongStateOf(0L) }
    var navBarBounds by remember { mutableStateOf<Rect?>(null) }
    var exitButtonBounds by remember { mutableStateOf<Rect?>(null) }
    var overlayOffsetInRoot by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(clipGUID) {
        controlsVisible = true
        autoHideJobKey++
    }

    LaunchedEffect(autoHideJobKey) {
        if (controlsVisible) {
            delay(AUTO_HIDE_DELAY_MILLIS)
            controlsVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        key(clipGUID) {
            if (clipHandle != 0L) {
                val renderer = remember { MlvRenderer(cpuCores, playerViewModel) }
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
                        // Read both to trigger recomposition on either change
                        currentFrame.let { _ -> }
                        processingVersion.let { _ -> }
                        glSurfaceView.requestRender()
                    },
                    onRelease = { glSurfaceView ->
                        glSurfaceView.onPause()
                        renderer.onSurfaceDestroyed()
                    },
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .onGloballyPositioned { overlayOffsetInRoot = it.positionInRoot() }
                        .pointerInput(clipHandle, controlsVisible, navBarBounds, exitButtonBounds, overlayOffsetInRoot) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val downChange = event.changes.firstOrNull { it.changedToDownIgnoreConsumed() } ?: continue
                                    val rootPosition = downChange.position + overlayOffsetInRoot
                                    val touchedPersistentControl = navBarBounds?.contains(rootPosition) == true ||
                                        exitButtonBounds?.contains(rootPosition) == true
                                    handleControlsTap(
                                        controlsCurrentlyVisible = controlsVisible,
                                        touchedControl = touchedPersistentControl,
                                        showControls = {
                                            controlsVisible = true
                                            autoHideJobKey++
                                        },
                                        hideControls = { controlsVisible = false },
                                        extendAutoHide = { autoHideJobKey++ }
                                    )
                                }
                            }
                        }
                ) {
                    AnimatedVisibility(
                        visible = controlsVisible,
                        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
                        exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 })
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            IconButton(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .onGloballyPositioned { exitButtonBounds = it.boundsInRoot() },
                                onClick = { navController.popBackStack() }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FullscreenExit,
                                    contentDescription = "Exit fullscreen mode",
                                    tint = Color.White
                                )
                            }
                            NavigationBar(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.BottomCenter)
                                    .onGloballyPositioned { navBarBounds = it.boundsInRoot() },
                                viewModel = playerViewModel
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun handleControlsTap(
    controlsCurrentlyVisible: Boolean,
    touchedControl: Boolean,
    showControls: () -> Unit,
    hideControls: () -> Unit,
    extendAutoHide: () -> Unit,
) {
    when {
        !controlsCurrentlyVisible -> showControls()
        touchedControl -> extendAutoHide()
        else -> hideControls()
    }
}
