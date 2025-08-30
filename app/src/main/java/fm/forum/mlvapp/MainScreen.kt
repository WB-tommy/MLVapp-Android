package fm.forum.mlvapp

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import fm.forum.mlvapp.NativeInterface.NativeLib
import fm.forum.mlvapp.data.Clip
import fm.forum.mlvapp.data.ClipMetaData
import fm.forum.mlvapp.data.ClipPreviewData
import fm.forum.mlvapp.videoPlayer.NavigationBar
import fm.forum.mlvapp.videoPlayer.VideoPlayerScreen
import fm.forum.mlvapp.videoPlayer.VideoViewModel
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    totalMemory: Long,
    cpuCores: Int,
    viewModel: VideoViewModel = viewModel()
) {
    val context = LocalContext.current

    var selectedFiles by remember { mutableStateOf<List<Clip>>(emptyList()) }

    val coroutineScope = rememberCoroutineScope()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            coroutineScope.launch {
                val newMLVFiles = uris.mapNotNull { uri ->
                    val fileName = getFileName(uri, context)
                    if (fileName.endsWith(".mlv", ignoreCase = true) ||
                        fileName.endsWith(".mcraw", ignoreCase = true)
                    ) {
                        try {
                            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                                val fd = pfd.detachFd()
                                val rawClipData = NativeLib.openClipForPreview(
                                    fd,
                                    fileName,
                                    totalMemory,
                                    cpuCores
                                )

                                return@mapNotNull Clip(
                                    uri = uri,
                                    fileName = fileName,
                                    width = rawClipData.width,
                                    height = rawClipData.height,
                                    thumbnail = rawClipData.thumbnail.asImageBitmap(),
                                    guid = rawClipData.guid,
                                )
                            }
                        } catch (e: Exception) {
                            Log.e("FilePicker", "Error processing URI $uri", e)
                            null
                        }
                    } else {
                        null
                    }
                }
                selectedFiles = (selectedFiles + newMLVFiles).distinctBy { it.guid }
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
        Surface(
            modifier = Modifier.padding(innerPadding)
        ) {
            Column {
                VideoPlayerScreen(viewModel, cpuCores)
                NavigationBar(Modifier.fillMaxWidth(), viewModel)
                FileListView(
                    clipList = selectedFiles,
                    onClipSelected = { selectedFile ->
                        coroutineScope.launch {
                            context.contentResolver.openFileDescriptor(selectedFile.uri, "r")
                                ?.use { pfd ->
                                    val fd = pfd.detachFd()
                                    val jniData = NativeLib.openClip(
                                        fd,
                                        selectedFile.fileName,
                                        totalMemory,
                                        cpuCores
                                    )
                                    previewToClip(selectedFile, jniData)
                                    viewModel.setMetadata(selectedFile)
                                    Log.d("FileListView", "Selected file: ${viewModel.totalFrames.value}, ${viewModel.fps.value}")
                                }
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }
        }
    }
}

private fun getFileName(uri: Uri, context: Context): String {
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

private fun formatDuration(frames: Int, fps: Float): String {
    if (fps <= 0f) return "-"
    val totalSeconds = (frames / fps).toInt()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d:%02d", hours, minutes, seconds)
}

private fun formatShutter(shutterUs: Int, fps: Float): String {
    if (shutterUs <= 0) return "-"
    val shutterSpeed = 1_000_000.0 / shutterUs.toDouble()
    val shutterAngle = if (fps > 0f) (fps * 360.0 / shutterSpeed) else 0.0
    val denom = shutterSpeed.toInt().coerceAtLeast(1)
    return String.format("1/%d s,  %.0f deg,  %d µs", denom, shutterAngle, shutterUs)
}

private fun previewToClip(selectedClip: Clip, clipMetaData: ClipMetaData) {
    selectedClip.nativeHandle = clipMetaData.nativeHandle
    selectedClip.frames = clipMetaData.frames
    selectedClip.fps = clipMetaData.fps
    selectedClip.cameraName = clipMetaData.cameraName
    selectedClip.lens = clipMetaData.lens
    selectedClip.duration = formatDuration(clipMetaData.frames, clipMetaData.fps)
    selectedClip.focalLength = clipMetaData.focalLengthMm.toString() + " mm"
    selectedClip.shutter = formatShutter(clipMetaData.shutterUs, clipMetaData.fps)
    selectedClip.aperture = "ƒ/%.1f".format(clipMetaData.apertureHundredths / 100.0)
    selectedClip.iso = clipMetaData.iso
    selectedClip.dualISO = clipMetaData.dualIsoValid
    selectedClip.bitDepth = clipMetaData.losslessBpp
    selectedClip.createdDate = "%s-%s-%s %s:%s:%s".format(
        clipMetaData.year,
        clipMetaData.month,
        clipMetaData.day,
        clipMetaData.hour,
        clipMetaData.min,
        clipMetaData.sec
    )
    selectedClip.audioChannel = if (clipMetaData.hasAudio) clipMetaData.audioChannels else 0
    selectedClip.audioSampleRate =
        if (clipMetaData.hasAudio) clipMetaData.audioSampleRate.toLong() else 0L
}