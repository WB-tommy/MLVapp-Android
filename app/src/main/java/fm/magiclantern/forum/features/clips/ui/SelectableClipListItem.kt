package fm.magiclantern.forum.features.clips.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fm.magiclantern.forum.domain.model.ClipPreview

/**
 * Reusable clip list item with checkbox selection.
 * Used by both ExportSelectionScreen and ClipRemovalScreen.
 */
@Composable
fun SelectableClipListItem(
    clip: ClipPreview,
    isSelected: Boolean,
    onClipSelected: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClipSelected)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            bitmap = clip.thumbnail,
            contentDescription = "Thumbnail for ${clip.displayName}",
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = clip.displayName,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "${clip.width} × ${clip.height}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onClipSelected() }
        )
    }
}
