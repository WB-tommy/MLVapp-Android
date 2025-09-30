package fm.forum.mlvapp

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import fm.forum.mlvapp.ui.theme.PurpleGrey40
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
    val curClipGuid by viewModel.clipGUID.collectAsState()

    val coroutineScope = rememberCoroutineScope()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            coroutineScope.launch {
                val newClips = uris.mapNotNull { uri ->
                    val fileName = getFileName(uri, context)
                    val extension = fileName.substringAfterLast('.', "")
                    val isMlvChunk = extension.equals("MLV", ignoreCase = true) ||
                            extension.matches(Regex("M[0-9]{2}"))
                    val isMcraw = extension.equals("mcraw", ignoreCase = true)

                    if (isMlvChunk || isMcraw) {
                        try {
                            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                                val fd = pfd.detachFd()
                                val rawClipData = NativeLib.openClipForPreview(
                                    fd,
                                    fileName,
                                    totalMemory,
                                    cpuCores
                                )
                                // Temporary anonymous object
                                object {
                                    val uri = uri
                                    val fileName = fileName
                                    val width = rawClipData.width
                                    val height = rawClipData.height
                                    val thumbnail = rawClipData.thumbnail.asImageBitmap()
                                    val guid = rawClipData.guid
                                    val extension = extension
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("FilePicker", "Error processing URI $uri", e)
                            null
                        }
                    } else {
                        null
                    }
                }

                val newClipsByGuid = newClips.groupBy { it.guid }
                val updatedFiles = selectedFiles.toMutableList()

                newClipsByGuid.forEach { (guid, clipInfos) ->
                    val existingClipIndex = updatedFiles.indexOfFirst { it.guid == guid }
                    if (existingClipIndex != -1) {
                        // Merge with existing clip
                        val existingClip = updatedFiles[existingClipIndex]
                        val newUris = clipInfos.map { it.uri }
                        val newFileNames = clipInfos.map { it.fileName }

                        val allUris = (existingClip.uris + newUris).distinct()
                        val allFileNames = (existingClip.fileNames + newFileNames).distinct()

                        updatedFiles[existingClipIndex] = existingClip.copy(
                            uris = allUris,
                            fileNames = allFileNames
                        )
                    } else {
                        // Add new clip
                        val firstClipInfo = clipInfos.first()
                        updatedFiles.add(
                            Clip(
                                uris = clipInfos.map { it.uri },
                                fileNames = clipInfos.map { it.fileName },
                                displayName = firstClipInfo.fileName,
                                width = firstClipInfo.width,
                                height = firstClipInfo.height,
                                thumbnail = firstClipInfo.thumbnail,
                                guid = firstClipInfo.guid
                            )
                        )
                    }
                }
                selectedFiles = updatedFiles
            }
        }
    }

    Scaffold(
        modifier = Modifier
            .padding(4.dp)
            .statusBarsPadding(),
        topBar = { TheTopBar(onAddFileClick = { filePickerLauncher.launch(arrayOf("application/octet-stream")) }) },
    ) { innerPadding ->
        Surface(
            modifier = Modifier.padding(innerPadding)
        ) {
            Column {
                VideoPlayerScreen(viewModel, cpuCores)
                NavigationBar(
                    Modifier
                        .fillMaxWidth()
                        .background(PurpleGrey40),
                    viewModel
                )
                FileListView(
                    clipList = selectedFiles,
                    onClipSelected = { selectedClip ->
                        if (selectedClip.guid != curClipGuid && !viewModel.isLoading.value) {
                            viewModel.changeLoadingStatus(true)
                            coroutineScope.launch {
                                // The clip object now contains all the uris.
                                // I need to sort them before getting the file descriptors.
                                val sortedUrisAndNames =
                                    selectedClip.uris.zip(selectedClip.fileNames)
                                        .sortedWith(compareBy { (_, fileName) ->
                                            val extension = fileName.substringAfterLast('.')
                                            if (extension.equals("MLV", ignoreCase = true)) {
                                                "0" // MLV comes first
                                            } else {
                                                extension
                                            }
                                        })

                                val fds = sortedUrisAndNames.mapNotNull { (uri, _) ->
                                    try {
                                        context.contentResolver.openFileDescriptor(uri, "r")
                                            ?.detachFd()
                                    } catch (e: Exception) {
                                        null
                                    }
                                }.toIntArray()

                                if (fds.isNotEmpty()) {
                                    val jniData = NativeLib.openClip(
                                        fds,
                                        selectedClip.displayName, // Pass the display name
                                        totalMemory,
                                        cpuCores
                                    )
                                    previewToClip(selectedClip, jniData)
                                    viewModel.loadNewClip(selectedClip)
                                }
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