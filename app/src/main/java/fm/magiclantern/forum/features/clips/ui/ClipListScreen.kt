package fm.magiclantern.forum.features.clips.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fm.magiclantern.forum.domain.model.ClipMetadata
import fm.magiclantern.forum.domain.model.ClipPreview

@Composable
fun FileListView(
    clipList: List<ClipPreview>,
    onClipSelected: (ClipPreview) -> Unit,
    getMetadataForClip: (Long) -> ClipMetadata? = { null },
    modifier: Modifier
) {
    LazyColumn(modifier = modifier) {
        items(clipList) { clip ->
            ClipListItem(
                clip = clip,
                onClipSelected = onClipSelected,
                getMetadataForClip = getMetadataForClip
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ClipListItem(
    clip: ClipPreview,
    onClipSelected: (ClipPreview) -> Unit,
    getMetadataForClip: (Long) -> ClipMetadata? = { null }
) {
    var showInfoDialog by remember { mutableStateOf(false) }

    val nameAndExtension = clip.displayName.split('.')
    val name = nameAndExtension.getOrNull(0)
    val extension = nameAndExtension.getOrNull(1)
    val clipName = if (name!!.length > 8) "${name.take(8)}...${extension}" else clip.displayName
    Row(
        Modifier
            .fillMaxWidth()
            .height(96.dp)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .weight(1.5f)
                .fillMaxHeight(),
        ) {
            Image(
                bitmap = clip.thumbnail,
                contentDescription = "Preview",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        Text(
            text = clipName,
            modifier = Modifier
                .weight(2f)
                .padding(horizontal = 8.dp)
                .combinedClickable(
                    onClick = { },
                    onLongClick = { showInfoDialog = true }
                ),
            textAlign = TextAlign.Center,
            maxLines = 1
        )
        Box(
            modifier = Modifier
                .weight(0.5f)
                .fillMaxHeight()
                .clickable { onClipSelected(clip) },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = "Play Clip",
                modifier = Modifier.size(48.dp)
            )
        }
    }

    if (showInfoDialog) {
        ClipInfoDialog(
            clip = clip,
            metadata = getMetadataForClip(clip.guid),
            onDismiss = { showInfoDialog = false }
        )
    }
}


/**
 * Dialog showing clip information.
 * - Preview only: shows resolution
 * - Fully loaded: shows all metadata like desktop ClipInformation.cpp
 */
@Composable
private fun ClipInfoDialog(
    clip: ClipPreview,
    metadata: ClipMetadata? = null,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = clip.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            if (metadata != null) {
                // Full metadata available - show everything
                FullClipInfo(clip = clip, metadata = metadata)
            } else {
                // Preview only - show basic info
                PreviewClipInfo(clip = clip)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun PreviewClipInfo(clip: ClipPreview) {
    Column(modifier = Modifier.fillMaxWidth()) {
        InfoRow(label = "Resolution", value = "${clip.width} × ${clip.height}")
    }
}

@Composable
private fun FullClipInfo(clip: ClipPreview, metadata: ClipMetadata) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
    ) {
        // Camera & Lens
        if (metadata.cameraName.isNotEmpty()) {
            InfoRow(label = "Camera", value = metadata.cameraName)
        }
        if (metadata.lens.isNotEmpty()) {
            InfoRow(label = "Lens", value = metadata.lens)
        }

        // Video Specs
        if (metadata.cameraName.isNotEmpty() || metadata.lens.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }

        InfoRow(label = "Resolution", value = "${clip.width} × ${clip.height}")

        if (metadata.duration.isNotEmpty()) {
            InfoRow(label = "Duration", value = metadata.duration)
        }
        if (metadata.frames > 0) {
            InfoRow(label = "Frames", value = metadata.frames.toString())
        }
        if (metadata.fps > 0f) {
            InfoRow(label = "Frame Rate", value = "%.3f fps".format(metadata.fps))
        }
        if (metadata.bitDepth > 0) {
            InfoRow(label = "Bit Depth", value = "${metadata.bitDepth} bit")
        }

        // Exposure
        if (metadata.focalLength.isNotEmpty() ||
            metadata.shutter.isNotEmpty() ||
            metadata.aperture.isNotEmpty() ||
            metadata.iso > 0
        ) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            if (metadata.focalLength.isNotEmpty()) {
                InfoRow(label = "Focal Length", value = metadata.focalLength)
            }
            if (metadata.shutter.isNotEmpty()) {
                InfoRow(label = "Shutter", value = metadata.shutter)
            }
            if (metadata.aperture.isNotEmpty()) {
                InfoRow(label = "Aperture", value = metadata.aperture)
            }
            if (metadata.iso > 0) {
                InfoRow(label = "ISO", value = metadata.iso.toString())
            }
            if (metadata.dualISO) {
                InfoRow(label = "Dual ISO", value = "Yes")
            }
        }

        // Date/Time
        if (metadata.createdDate.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            InfoRow(label = "Date/Time", value = metadata.createdDate)
        }

        // Audio
        if (metadata.hasAudio) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            val audioInfo = buildString {
                append("${metadata.audioChannels}ch")
                if (metadata.audioSampleRate > 0) {
                    append(" @ ${metadata.audioSampleRate / 1000}kHz")
                }
            }
            InfoRow(label = "Audio", value = audioInfo)
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

