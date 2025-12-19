package fm.magiclantern.forum

import android.app.Activity
import android.net.Uri
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
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
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import fm.magiclantern.forum.features.clips.ui.FileListView
import fm.magiclantern.forum.features.clips.viewmodel.ClipListEvent
import fm.magiclantern.forum.features.clips.viewmodel.ClipListViewModel
import fm.magiclantern.forum.features.clips.viewmodel.FocusPixelDownloadOutcome
import fm.magiclantern.forum.features.grading.ui.ColorGradingScreen
import fm.magiclantern.forum.features.grading.viewmodel.GradingViewModel
import fm.magiclantern.forum.features.player.ui.NavigationBar
import fm.magiclantern.forum.features.player.ui.VideoPlayerScreen
import fm.magiclantern.forum.features.player.viewmodel.PlayerViewModel

@Composable
fun MainScreen(
    windowSizeClass: WindowSizeClass,
    totalMemory: Long,
    cpuCores: Int,
    navController: NavHostController,
    clipListViewModel: ClipListViewModel,
    playerViewModel: PlayerViewModel,
    gradingViewModel: GradingViewModel
) {
    val context = LocalContext.current

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* no-op */ }

    val clipUiState by clipListViewModel.uiState.collectAsState()
    val isPlaying by playerViewModel.isPlaying.collectAsState()

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

    // Connect loading state
    LaunchedEffect(clipUiState.isActivatingClip) {
        playerViewModel.changeLoadingStatus(clipUiState.isActivatingClip)
    }

    // Handle one-off events
    LaunchedEffect(clipListViewModel) {
        clipListViewModel.events.collect { event ->
            when (event) {
                is ClipListEvent.FocusPixelDownloadFeedback -> {
                    val messageRes = when (event.outcome) {
                        FocusPixelDownloadOutcome.SINGLE_SUCCESS, FocusPixelDownloadOutcome.ALL_SUCCESS -> R.string.focus_pixel_download_success
                        FocusPixelDownloadOutcome.SINGLE_FAILURE, FocusPixelDownloadOutcome.ALL_FAILURE -> R.string.focus_pixel_download_failed
                        FocusPixelDownloadOutcome.ALL_NONE_FOR_CAMERA -> R.string.focus_pixel_download_none_for_camera
                        FocusPixelDownloadOutcome.ALL_INDEX_UNAVAILABLE -> R.string.focus_pixel_download_index_unavailable
                    }
                    Toast.makeText(context, messageRes, Toast.LENGTH_SHORT).show()
                }

                is ClipListEvent.LoadFailed -> {
                    Toast.makeText(context, R.string.clip_load_failed, Toast.LENGTH_LONG).show()
                }

                is ClipListEvent.ClipPreparationFailed -> {
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

    // Show Focus Pixel prompt if needed
    val focusPixelPrompt = clipUiState.focusPixelPrompt
    if (focusPixelPrompt != null) {
        FocusPixelMapToast(
            requiredFileName = focusPixelPrompt.requiredFile,
            isBusy = clipUiState.isFocusPixelDownloadInProgress,
            onSelectSingle = { clipListViewModel.downloadFocusPixelMap() },
            onSelectAll = { clipListViewModel.downloadAllFocusPixelMaps() },
            onDismiss = { clipListViewModel.dismissFocusPixelPrompt() }
        )
    }

    // Choose layout based on screen size
    when (windowSizeClass.widthSizeClass) {
        WindowWidthSizeClass.Expanded -> {
            TabletLayout(
                clipListViewModel = clipListViewModel,
                playerViewModel = playerViewModel,
                navController = navController,
                cpuCores = cpuCores,
                gradingViewModel = gradingViewModel
            )
        }

        else -> {
            MobileLayout(
                clipListViewModel = clipListViewModel,
                playerViewModel = playerViewModel,
                navController = navController,
                cpuCores = cpuCores,
                gradingViewModel = gradingViewModel
            )
        }
    }
}

@Composable
private fun MobileLayout(
    clipListViewModel: ClipListViewModel,
    playerViewModel: PlayerViewModel,
    navController: NavHostController,
    cpuCores: Int,
    gradingViewModel: GradingViewModel
) {
    val clipUiState by clipListViewModel.uiState.collectAsState()
    val curClipGuid by playerViewModel.clipGUID.collectAsState()
    val isPlaying by playerViewModel.isPlaying.collectAsState()

    // Panel state for mobile switching
    var currentPanel by remember { mutableStateOf(MobilePanelType.FILE_LIST) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris: List<Uri> -> if (uris.isNotEmpty()) clipListViewModel.onFilesPicked(uris) }
    )

    Scaffold(
        modifier = Modifier.statusBarsPadding(),
        topBar = {
            TheTopBar(
                onAddFileClick = { filePickerLauncher.launch(arrayOf("application/octet-stream")) },
                onSettingClick = { navController.navigate("settings") },
                onExportClick = { navController.navigate("export_selection") },
                currentPanel = currentPanel,
                onPanelSwapClick = {
                    currentPanel = when (currentPanel) {
                        MobilePanelType.FILE_LIST -> MobilePanelType.GRADING
                        MobilePanelType.GRADING -> MobilePanelType.FILE_LIST
                    }
                },
                onDeleteFileClick = { navController.navigate("clip_removal") }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            VideoPlayerScreen(navController, 16f, playerViewModel, cpuCores)
            NavigationBar(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primaryContainer),
                playerViewModel
            )
            LoadingIndicatorBar(isLoading = clipUiState.isLoading)

            // Switchable panel with smooth transition
            Crossfade(
                targetState = currentPanel,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
            ) { panel ->
                when (panel) {
                    MobilePanelType.FILE_LIST -> {
                        val activeClip by gradingViewModel.activeClip.collectAsState()
                        FileListView(
                            clipList = clipUiState.clips,
                            onClipSelected = { selectedClip ->
                                if (selectedClip.guid != curClipGuid && !clipUiState.isActivatingClip && !isPlaying) {
                                    clipListViewModel.onClipSelected(selectedClip.guid)
                                }
                            },
                            getMetadataForClip = { guid ->
                                activeClip?.takeIf { it.guid == guid }?.metadata
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    MobilePanelType.GRADING -> {
                        // Use new ColorGradingScreen - no prop drilling!
                        ColorGradingScreen(
                            gradingViewModel = gradingViewModel,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TabletLayout(
    clipListViewModel: ClipListViewModel,
    playerViewModel: PlayerViewModel,
    navController: NavHostController,
    cpuCores: Int,
    gradingViewModel: GradingViewModel
) {
    val clipUiState by clipListViewModel.uiState.collectAsState()
    val curClipGuid by playerViewModel.clipGUID.collectAsState()
    val isPlaying by playerViewModel.isPlaying.collectAsState()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris: List<Uri> -> if (uris.isNotEmpty()) clipListViewModel.onFilesPicked(uris) }
    )

    Scaffold(
        modifier = Modifier.statusBarsPadding(),
        topBar = {
            TheTopBar(
                onAddFileClick = { filePickerLauncher.launch(arrayOf("application/octet-stream")) },
                onSettingClick = { navController.navigate("settings") },
                onExportClick = { navController.navigate("export_selection") },
                onDeleteFileClick = { navController.navigate("clip_removal") }
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
                VideoPlayerScreen(navController, 21f, playerViewModel, cpuCores)
                NavigationBar(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    playerViewModel
                )
                LoadingIndicatorBar(isLoading = clipUiState.isLoading)
                val activeClip by gradingViewModel.activeClip.collectAsState()
                FileListView(
                    clipList = clipUiState.clips,
                    onClipSelected = { selectedClip ->
                        if (selectedClip.guid != curClipGuid && !clipUiState.isActivatingClip && !isPlaying) {
                            clipListViewModel.onClipSelected(selectedClip.guid)
                        }
                    },
                    getMetadataForClip = { guid ->
                        activeClip?.takeIf { it.guid == guid }?.metadata
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                )
            }

            // Right Panel - Color Grading (no prop drilling!)
            ColorGradingScreen(
                gradingViewModel = gradingViewModel,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
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
