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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import fm.forum.mlvapp.ui.theme.PurpleGrey40
import fm.forum.mlvapp.videoPlayer.NavigationBar
import fm.forum.mlvapp.videoPlayer.VideoPlayerScreen
import fm.forum.mlvapp.videoPlayer.VideoViewModel
import fm.forum.mlvapp.videoPlayer.VideoViewModelFactory

@Composable
fun MainScreen(
    totalMemory: Long,
    cpuCores: Int,
    navController: NavHostController,
    settingsRepository: SettingsRepository
) {
    val context = LocalContext.current
    val videoViewModelFactory = remember(settingsRepository) {
        VideoViewModelFactory(settingsRepository)
    }
    val videoViewModel: VideoViewModel = viewModel(factory = videoViewModelFactory)

    val applicationContext = context.applicationContext
    val clipRepository = remember(applicationContext) {
        ClipRepository(applicationContext)
    }
    val clipViewModelFactory = remember(clipRepository, totalMemory, cpuCores) {
        ClipViewModelFactory(clipRepository, totalMemory, cpuCores)
    }
    val clipViewModel: ClipViewModel = viewModel(factory = clipViewModelFactory)

    val clipUiState by clipViewModel.uiState.collectAsState()
    val curClipGuid by videoViewModel.clipGUID.collectAsState()
    val isPlaying by videoViewModel.isPlaying.collectAsState()

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

    LaunchedEffect(clipViewModel) {
        clipViewModel.events.collect { event ->
            when (event) {
                is ClipEvent.FocusPixelDownloadFeedback -> {
                    val messageRes = when (event.outcome) {
                        FocusPixelDownloadOutcome.SINGLE_SUCCESS,
                        FocusPixelDownloadOutcome.ALL_SUCCESS -> R.string.focus_pixel_download_success

                        FocusPixelDownloadOutcome.SINGLE_FAILURE,
                        FocusPixelDownloadOutcome.ALL_FAILURE -> R.string.focus_pixel_download_failed

                        FocusPixelDownloadOutcome.ALL_NONE_FOR_CAMERA -> R.string.focus_pixel_download_none_for_camera
                        FocusPixelDownloadOutcome.ALL_INDEX_UNAVAILABLE -> R.string.focus_pixel_download_index_unavailable
                    }
                    Toast.makeText(context, messageRes, Toast.LENGTH_SHORT).show()
                }

                is ClipEvent.LoadFailed -> {
                    Toast.makeText(
                        context,
                        R.string.clip_load_failed,
                        Toast.LENGTH_LONG
                    ).show()
                }

                is ClipEvent.ClipPreparationFailed -> {
                    val message = context.getString(
                        R.string.clip_preparation_failed,
                        event.failedCount,
                        event.totalCount
                    )
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            clipViewModel.onFilesPicked(uris)
        }
    }

    Scaffold(
        modifier = Modifier
            .padding(4.dp)
            .statusBarsPadding(),
        topBar = {
            TheTopBar(
                onAddFileClick = { filePickerLauncher.launch(arrayOf("application/octet-stream")) },
                onSettingClick = { navController.navigate("settings") }
            )
        },
    ) { innerPadding ->
        Surface(
            modifier = Modifier.padding(innerPadding)
        ) {
            Column {
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

    val focusPixelPrompt = clipUiState.focusPixelPrompt
    if (focusPixelPrompt != null) {
        FocusPixelMapToast(
            requiredFileName = focusPixelPrompt.requiredFile,
            isBusy = clipUiState.isFocusPixelDownloadInProgress,
            onSelectSingle = {
                clipViewModel.downloadFocusPixelMap()
            },
            onSelectAll = {
                clipViewModel.downloadAllFocusPixelMaps()
            },
            onDismiss = {
                clipViewModel.dismissFocusPixelPrompt()
            }
        )
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
