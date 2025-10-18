package fm.forum.mlvapp

import android.app.Activity
import android.net.Uri
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import fm.forum.mlvapp.clips.ClipEvent
import fm.forum.mlvapp.clips.ClipViewModel
import fm.forum.mlvapp.clips.ClipViewModelFactory
import fm.forum.mlvapp.clips.FocusPixelDownloadOutcome
import fm.forum.mlvapp.data.ClipRepository
import fm.forum.mlvapp.settings.SettingsRepository
import fm.forum.mlvapp.videoPlayer.NavigationBar
import fm.forum.mlvapp.videoPlayer.VideoPlayerScreen
import fm.forum.mlvapp.videoPlayer.VideoViewModel
import fm.forum.mlvapp.videoPlayer.VideoViewModelFactory

@Composable
fun MainScreen(
    windowSizeClass: WindowSizeClass,
    totalMemory: Long,
    cpuCores: Int,
    navController: NavHostController,
    settingsRepository: SettingsRepository
) {
    val context = LocalContext.current

    // Create ViewModels
    val videoViewModel: VideoViewModel = viewModel(
        factory = remember(settingsRepository) { VideoViewModelFactory(settingsRepository) }
    )
    val clipViewModel: ClipViewModel = viewModel(
        factory = remember(context.applicationContext, totalMemory, cpuCores) {
            ClipViewModelFactory(ClipRepository(context.applicationContext), totalMemory, cpuCores)
        }
    )

    val clipUiState by clipViewModel.uiState.collectAsState()
    val isPlaying by videoViewModel.isPlaying.collectAsState()

    // Keep screen on when playing
    val view = LocalView.current
    DisposableEffect(isPlaying) {
        val window = (view.context as? Activity)?.window
        if (isPlaying) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Connect ViewModels
    LaunchedEffect(clipUiState.isActivatingClip) {
        videoViewModel.changeLoadingStatus(clipUiState.isActivatingClip)
    }

    LaunchedEffect(clipUiState.activeClip?.nativeHandle) {
        val activeClip = clipUiState.activeClip
        if (activeClip != null) {
            videoViewModel.setMetadata(activeClip)
            videoViewModel.changeDrawingStatus(true)
        } else {
            videoViewModel.changeDrawingStatus(false)
        }
    }

    // Handle one-off events
    LaunchedEffect(clipViewModel) {
        clipViewModel.events.collect { event ->
            when (event) {
                is ClipEvent.FocusPixelDownloadFeedback -> {
                    val messageRes = when (event.outcome) {
                        FocusPixelDownloadOutcome.SINGLE_SUCCESS, FocusPixelDownloadOutcome.ALL_SUCCESS -> R.string.focus_pixel_download_success
                        FocusPixelDownloadOutcome.SINGLE_FAILURE, FocusPixelDownloadOutcome.ALL_FAILURE -> R.string.focus_pixel_download_failed
                        FocusPixelDownloadOutcome.ALL_NONE_FOR_CAMERA -> R.string.focus_pixel_download_none_for_camera
                        FocusPixelDownloadOutcome.ALL_INDEX_UNAVAILABLE -> R.string.focus_pixel_download_index_unavailable
                    }
                    Toast.makeText(context, messageRes, Toast.LENGTH_SHORT).show()
                }

                is ClipEvent.LoadFailed -> {
                    Toast.makeText(context, R.string.clip_load_failed, Toast.LENGTH_LONG).show()
                }

                is ClipEvent.ClipPreparationFailed -> {
                    val message = context.getString(R.string.clip_preparation_failed, event.failedCount, event.totalCount)
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Show Focus Pixel prompt if needed
    val focusPixelPrompt = clipUiState.focusPixelPrompt
    if (focusPixelPrompt != null) {
        FocusPixelMapToast(
            requiredFileName = focusPixelPrompt.requiredFile,
            isBusy = clipUiState.isFocusPixelDownloadInProgress,
            onSelectSingle = { clipViewModel.downloadFocusPixelMap() },
            onSelectAll = { clipViewModel.downloadAllFocusPixelMaps() },
            onDismiss = { clipViewModel.dismissFocusPixelPrompt() }
        )
    }

    // Choose layout based on screen size
    when (windowSizeClass.widthSizeClass) {
        WindowWidthSizeClass.Expanded -> {
            TabletLayout(
                clipViewModel = clipViewModel,
                videoViewModel = videoViewModel,
                navController = navController,
                cpuCores = cpuCores
            )
        }
        else -> {
            MobileLayout(
                clipViewModel = clipViewModel,
                videoViewModel = videoViewModel,
                navController = navController,
                cpuCores = cpuCores
            )
        }
    }
}

@Composable
private fun MobileLayout(
    clipViewModel: ClipViewModel,
    videoViewModel: VideoViewModel,
    navController: NavHostController,
    cpuCores: Int
) {
    val clipUiState by clipViewModel.uiState.collectAsState()
    val curClipGuid by videoViewModel.clipGUID.collectAsState()
    val isPlaying by videoViewModel.isPlaying.collectAsState()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris: List<Uri> -> if (uris.isNotEmpty()) clipViewModel.onFilesPicked(uris) }
    )

    Scaffold(
        modifier = Modifier.statusBarsPadding(),
        topBar = {
            TheTopBar(
                onAddFileClick = { filePickerLauncher.launch(arrayOf("application/octet-stream")) },
                onSettingClick = { navController.navigate("settings") }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            VideoPlayerScreen(videoViewModel, cpuCores)
            NavigationBar(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primaryContainer),
                videoViewModel
            )
            LoadingIndicatorBar(isLoading = clipUiState.isLoading)
            FileListView(
                clipList = clipUiState.clips,
                onClipSelected = { selectedClip ->
                    if (selectedClip.guid != curClipGuid && !clipUiState.isActivatingClip && !isPlaying) {
                        clipViewModel.onClipSelected(selectedClip.guid)
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
            )
        }
    }
}

@Composable
private fun TabletLayout(
    clipViewModel: ClipViewModel,
    videoViewModel: VideoViewModel,
    navController: NavHostController,
    cpuCores: Int
) {
    val clipUiState by clipViewModel.uiState.collectAsState()
    val curClipGuid by videoViewModel.clipGUID.collectAsState()
    val isPlaying by videoViewModel.isPlaying.collectAsState()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris: List<Uri> -> if (uris.isNotEmpty()) clipViewModel.onFilesPicked(uris) }
    )

    Scaffold(
        modifier = Modifier.statusBarsPadding(),
        topBar = {
            TheTopBar(
                onAddFileClick = { filePickerLauncher.launch(arrayOf("application/octet-stream")) },
                onSettingClick = { navController.navigate("settings") }
            )
        }
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Left Panel (Player and File List)
            Column(modifier = Modifier.weight(2f)) {
                VideoPlayerScreen(videoViewModel, cpuCores)
                NavigationBar(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    videoViewModel
                )
                LoadingIndicatorBar(isLoading = clipUiState.isLoading)
                FileListView(
                    clipList = clipUiState.clips,
                    onClipSelected = { selectedClip ->
                        if (selectedClip.guid != curClipGuid && !clipUiState.isActivatingClip && !isPlaying) {
                            clipViewModel.onClipSelected(selectedClip.guid)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                )
            }

            // Right Panel
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                Text("Processing function will be coming soon!")
            }
        }
    }
}

@Composable
fun LoadingIndicatorBar(isLoading: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = isLoading,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        LinearProgressIndicator(
            modifier = modifier
                .fillMaxWidth()
                .height(8.dp)
        )
    }
}
