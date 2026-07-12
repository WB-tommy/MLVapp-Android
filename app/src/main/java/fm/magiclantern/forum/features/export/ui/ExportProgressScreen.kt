package fm.magiclantern.forum.features.export.ui

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
import fm.magiclantern.forum.features.export.ExportService
import fm.magiclantern.forum.features.export.viewmodel.ExportViewModel

@Composable
fun ExportProgressScreen(
    exportViewModel: ExportViewModel,
    navController: NavHostController
) {
    val progress by exportViewModel.exportProgress.collectAsState()
    val status by exportViewModel.exportStatus.collectAsState()

    val uiModel = exportProgressUiModel(status)

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
            Button(
                onClick = {
                    when (uiModel.action) {
                        ExportProgressAction.CANCEL -> exportViewModel.cancelExport()
                        ExportProgressAction.CLOSE_TO_LOCATION -> navController.popBackStack()
                        ExportProgressAction.CLOSE_TO_HOME -> {
                            navController.navigate("home") {
                                popUpTo(navController.graph.startDestinationId) {
                                    inclusive = true
                                }
                                launchSingleTop = true
                            }
                        }
                    }
                }
            ) {
                Text(uiModel.primaryButtonLabel)
            }
        }
    }
}

internal data class ExportProgressUiModel(
    val message: String,
    val showProgressBar: Boolean,
    val primaryButtonLabel: String,
    val action: ExportProgressAction
)

internal enum class ExportProgressAction {
    CANCEL,
    CLOSE_TO_LOCATION,
    CLOSE_TO_HOME
}

internal fun exportProgressUiModel(
    status: ExportService.ExportStatus
): ExportProgressUiModel = when (status) {
    is ExportService.ExportStatus.Running -> {
        val clipLabel = status.clipName?.takeIf { it.isNotBlank() }
            ?: "Clip ${status.clipIndex + 1}"
        ExportProgressUiModel(
            message = "Exporting $clipLabel " +
                "(${status.clipIndex + 1}/${status.totalClips.coerceAtLeast(1)})",
            showProgressBar = true,
            primaryButtonLabel = "Cancel",
            action = ExportProgressAction.CANCEL
        )
    }

    is ExportService.ExportStatus.Completed -> ExportProgressUiModel(
        message = "Export completed successfully.",
        showProgressBar = false,
        primaryButtonLabel = "Close",
        action = ExportProgressAction.CLOSE_TO_HOME
    )

    is ExportService.ExportStatus.Failed -> ExportProgressUiModel(
        message = "Export failed: ${status.reason}",
        showProgressBar = false,
        primaryButtonLabel = "Close",
        action = ExportProgressAction.CLOSE_TO_LOCATION
    )

    is ExportService.ExportStatus.Cancelled -> ExportProgressUiModel(
        message = "Export cancelled.",
        showProgressBar = false,
        primaryButtonLabel = "Close",
        action = ExportProgressAction.CLOSE_TO_LOCATION
    )

    ExportService.ExportStatus.Idle -> ExportProgressUiModel(
        message = "Preparing export…",
        showProgressBar = true,
        primaryButtonLabel = "Cancel",
        action = ExportProgressAction.CANCEL
    )
}
