package fm.forum.mlvapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fm.forum.mlvapp.data.MLVFileForList
import fm.forum.mlvapp.ui.theme.MLVappTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    init {
        System.loadLibrary("mlvcore")
    }

    external fun openMlvForPreview(fd: Int, isMlv: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MLVappTheme {
                // var selectedFileUris by remember { mutableStateOf<List<Uri>>(emptyList()) } // Maybe not needed if only an intermediate
                var selectedMLVFiles by remember { mutableStateOf<List<MLVFileForList>>(emptyList()) }
                // Coroutine scope for background processing
                val coroutineScope = rememberCoroutineScope()

                val filePickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenMultipleDocuments()
                ) { uris: List<Uri> ->
                    if (uris.isNotEmpty()) {
                        coroutineScope.launch {
                            // Simulate processing URIs into MLVFileForList objects
                            // This would be your actual logic to get file names, details, etc.
                            val newMLVFiles = uris.mapNotNull { uri ->
                                // Placeholder: Replace with your actual conversion/validation
                                val fileName = getFileName(uri, applicationContext) // Your function
                                if (fileName.endsWith(
                                        ".mlv",
                                        ignoreCase = true
                                    ) || fileName.endsWith(".mcraw", ignoreCase = true)
                                ) {
                                    try {
                                        contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                                            // Detach the file descriptor so it's not closed when pfd is closed.
                                            // The native code will be responsible for closing it.
                                            val fd = pfd.detachFd()
                                            openMlvForPreview(
                                                fd,
                                                fileName.endsWith(".mlv", ignoreCase = true)
                                            )
                                        }
                                    } catch (e: ErrnoException) {
                                        Log.e("FilePicker", "Failed to open file descriptor for $uri", e)
                                    } catch (e: Exception) {
                                        Log.e("FilePicker", "Error processing URI $uri", e)
                                    }
//                                    MLVFileForList(uri, fileName, "Details for $fileName")
                                } else {
                                    null // Skip files that don't match
                                }
                            }
//                            // Update the primary state that the UI observes
//                            selectedMLVFiles =
//                                selectedMLVFiles + newMLVFiles // Append to existing or replace
                        }
                    }
                }

                Scaffold(
                    modifier = Modifier
                        .padding(4.dp)
                        .statusBarsPadding(),
                    topBar = { TheTopBar(onAddFileClick = { filePickerLauncher.launch(arrayOf("application/octet-stream")) }) },
                    bottomBar = { TheBottomBar(Modifier) }
                ) { innerPadding ->
                    Surface {
                        Text(text = "Playback Area")
                    }
                    Text(text = "Timeline Control Area")
                    FileListView(selectedMLVFiles)
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TheTopBar(onAddFileClick: () -> Unit) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            titleContentColor = MaterialTheme.colorScheme.primary,
        ),
        title = { Text("") },
        actions = {
            IconButton(onClick = { Log.d("FilePicker", "Export") }) {
                Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = "Export"
                )
            }
            IconButton(onClick = onAddFileClick) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = "Add File"
                )
            }
        },
    )
}

@Composable
fun TheBottomBar(modifier: Modifier) {
    BottomAppBar(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.primary,
    ) {
        Row(
        ) {
            Text("Files")
            Text("Edit")
        }
    }
}

fun getFileName(uri: Uri, context: Context): String {
    var name = uri.pathSegments.lastOrNull() ?: "Unknown"
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1) {
                name = cursor.getString(nameIndex)
            }
        }
    }
    return name
}

@Composable
fun FilePreviewCard(mlvFile: MLVFileForList) {
    OutlinedCard(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(1.dp, Color.Black),
        modifier = Modifier
            .size(width = 240.dp, height = 100.dp)
    ) {
        Text(
            text = "Outlined",
            modifier = Modifier
                .padding(16.dp),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun FileListView(mlvFileList: List<MLVFileForList>) {
    LazyRow {
        items(mlvFileList) { mlvFile ->
            FilePreviewCard(mlvFile)
        }
    }
}
