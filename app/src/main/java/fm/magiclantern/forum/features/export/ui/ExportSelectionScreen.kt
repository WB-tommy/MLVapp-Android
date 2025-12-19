package fm.magiclantern.forum.features.export.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import fm.magiclantern.forum.features.clips.ui.SelectableClipListItem
import fm.magiclantern.forum.features.export.viewmodel.ExportUiState
import fm.magiclantern.forum.features.export.viewmodel.ExportViewModel
import fm.magiclantern.forum.features.export.viewmodel.FocusPixelPromptStage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSelectionScreen(
    exportViewModel: ExportViewModel,
    navController: NavHostController
) {
    val uiState by exportViewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(uiState.navigateToExportSettings) {
        if (uiState.navigateToExportSettings) {
            navController.navigate("export_settings")
            exportViewModel.onExportSettingsNavigationHandled()
        }
    }

    val showPrompt = uiState.focusPixelPromptStage == FocusPixelPromptStage.SELECTION &&
        uiState.focusPixelRequirements.isNotEmpty()
    if (showPrompt) {
        FocusPixelPromptDialog(
            uiState = uiState,
            onDownload = { exportViewModel.downloadMissingFocusPixelMaps(context) },
            onSkip = { exportViewModel.skipFocusPixelDownload(context) },
            onCancel = exportViewModel::cancelFocusPixelPrompt,
            disableInteractions = uiState.isFocusPixelDownloadInProgress
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Select Clips to Export") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    val allSelected = uiState.selectedClips.size == uiState.clips.size
                            && uiState.clips.isNotEmpty()
                    TextButton(
                        onClick = {
                            if (allSelected) {
                                exportViewModel.deselectAllClips()
                            } else {
                                exportViewModel.selectAllClips()
                            }
                        }
                    ) {
                        Text(if (allSelected) "Deselect All" else "Select All")
                    }
                }
            )
        },
        floatingActionButton = {
            if (uiState.selectedClips.isNotEmpty()) {
                val isBusy = uiState.isFocusPixelCheckInProgress || uiState.isFocusPixelDownloadInProgress
                FloatingActionButton(
                    onClick = {
                        if (!isBusy) {
                            exportViewModel.onSelectionNextRequested()
                        }
                    }
                ) {
                    Icon(Icons.Default.ArrowForward, contentDescription = "Next")
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            if (uiState.isFocusPixelCheckInProgress) {
                item {
                    Surface(
                        tonalElevation = 4.dp,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 3.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Checking required focus pixel maps…")
                        }
                    }
                }
            }

            items(uiState.clips) { clip ->
                SelectableClipListItem(
                    clip = clip,
                    isSelected = uiState.selectedClips.contains(clip.guid),
                    onClipSelected = { exportViewModel.toggleClipSelection(clip) }
                )
            }
        }
    }
}

@Composable
private fun FocusPixelPromptDialog(
    uiState: ExportUiState,
    onDownload: () -> Unit,
    onSkip: () -> Unit,
    onCancel: () -> Unit,
    disableInteractions: Boolean
) {
    AlertDialog(
        onDismissRequest = {
            if (!disableInteractions) onCancel()
        },
        title = { Text("Focus Pixel Maps Required") },
        text = {
            Column {
                Text("The following clips need focus pixel maps before continuing:")
                Spacer(modifier = Modifier.height(12.dp))
                uiState.focusPixelRequirements.forEach { requirement ->
                    Text("${requirement.clipName}: ${requirement.requiredFile}")
                }
                if (disableInteractions) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Downloading focus pixel maps…")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDownload,
                enabled = !disableInteractions
            ) {
                if (disableInteractions) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Download")
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = onSkip,
                    enabled = !disableInteractions
                ) {
                    Text("Skip")
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    onClick = onCancel,
                    enabled = !disableInteractions
                ) {
                    Text("Cancel")
                }
            }
        }
    )
}
