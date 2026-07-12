package fm.magiclantern.forum.features.export.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.navigation.NavHostController
import fm.magiclantern.forum.features.export.viewmodel.ExportViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportLocationScreen(
    exportViewModel: ExportViewModel,
    navController: NavHostController
) {
    val uiState by exportViewModel.uiState.collectAsState()
    val context = LocalContext.current

    val directoryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri ->
            if (uri != null) {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        flags
                    )
                    exportViewModel.onOutputDirectorySelected(uri)
                }.onFailure { throwable ->
                    exportViewModel.onOutputDirectorySelectionFailed(uri, throwable)
                }
            }
        }
    )

    LaunchedEffect(uiState.navigateToProgress) {
        if (uiState.navigateToProgress) {
            navController.navigate("export_progress")
            exportViewModel.onExportNavigationHandled()
        }
    }

    LaunchedEffect(uiState.outputDirectory) {
        exportViewModel.validateCurrentOutputDirectory(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Select Output Location") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        val hasDirectory = uiState.outputDirectory != null

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!hasDirectory) {
                Button(onClick = { directoryPickerLauncher.launch(null) }) {
                    Text("Choose Folder")
                }

                Spacer(modifier = Modifier.height(16.dp))
            } else {
                Button(onClick = { directoryPickerLauncher.launch(uiState.outputDirectory) }) {
                    Text("Change Folder")
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text("You can export immediately using the saved folder below.")
                Spacer(modifier = Modifier.height(16.dp))
            }

            uiState.outputDirectory?.let { uri ->
                val displayName = DocumentFile.fromTreeUri(context, uri)?.name
                    ?: uri.path?.substringAfter(':')
                    ?: uri.toString()
                Text("Selected folder: $displayName")
            }

            uiState.outputDirectoryError?.let { message ->
                Spacer(modifier = Modifier.height(16.dp))
                Text(message)
            }

            uiState.exportStartError?.let { message ->
                Spacer(modifier = Modifier.height(16.dp))
                Text(message)
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = { exportViewModel.startExport(context) },
                enabled = uiState.outputDirectory != null &&
                    uiState.outputDirectoryError == null &&
                    !uiState.isExporting &&
                    !uiState.isFocusPixelCheckInProgress &&
                    !uiState.isFocusPixelDownloadInProgress &&
                    uiState.focusPixelPromptStage == null
            ) {
                Text("Start Export")
            }

        }
    }
}
