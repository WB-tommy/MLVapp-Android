package fm.forum.mlvapp.export

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController

@Composable
fun ExportProgressScreen(
    exportViewModel: ExportViewModel,
    navController: NavHostController
) {
    val progress by exportViewModel.exportProgress.collectAsState()
    val status by exportViewModel.exportStatus.collectAsState()

    val uiModel = when (val current = status) {
        is ExportService.ExportStatus.Running -> {
            val clipLabel =
                current.clipName?.takeIf { it.isNotBlank() } ?: "Clip ${current.clipIndex + 1}"
            val msg = buildString {
                append("Exporting ")
                append(clipLabel)
                append(" (")
                append(current.clipIndex + 1)
                append('/')
                append(current.totalClips.coerceAtLeast(1))
                append(')')
            }
            ProgressUiModel(
                message = msg,
                showProgressBar = true,
                primaryButtonLabel = "Cancel",
                onPrimaryAction = {
                    exportViewModel.cancelExport()
                    navController.popBackStack()
                }
            )
        }

        is ExportService.ExportStatus.Completed -> ProgressUiModel(
            message = "Export completed successfully.",
            showProgressBar = false,
            primaryButtonLabel = "Close",
            onPrimaryAction = {
                navController.navigate("home") {
                    popUpTo(navController.graph.startDestinationId) {
                        inclusive = true
                    }
                    launchSingleTop = true
                }
            }
        )

        is ExportService.ExportStatus.Failed -> ProgressUiModel(
            message = "Export failed: ${current.reason}",
            showProgressBar = false,
            primaryButtonLabel = "Close",
            onPrimaryAction = { navController.popBackStack() }
        )

        is ExportService.ExportStatus.Cancelled -> ProgressUiModel(
            message = "Export cancelled.",
            showProgressBar = false,
            primaryButtonLabel = "Close",
            onPrimaryAction = { navController.popBackStack() }
        )

        ExportService.ExportStatus.Idle -> ProgressUiModel(
            message = "Preparing export…",
            showProgressBar = true,
            primaryButtonLabel = "Cancel",
            onPrimaryAction = {
                exportViewModel.cancelExport()
                navController.popBackStack()
            }
        )
    }

    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (uiModel.showProgressBar) {
                LinearProgressIndicator(progress = progress)
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = "${(progress * 100).toInt()}%")
                Spacer(modifier = Modifier.height(8.dp))
            }
            Text(text = uiModel.message)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = uiModel.onPrimaryAction) {
                Text(uiModel.primaryButtonLabel)
            }
        }
    }
}

private data class ProgressUiModel(
    val message: String,
    val showProgressBar: Boolean,
    val primaryButtonLabel: String,
    val onPrimaryAction: () -> Unit
)
