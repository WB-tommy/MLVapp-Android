package fm.forum.mlvapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fm.forum.mlvapp.data.Clip

@Composable
fun FileListView(
    clipList: List<Clip>,
    modifier: Modifier,
    onClipSelected: (Clip) -> Unit
) {
    LazyColumn(
        modifier = modifier
    ) {
        items(clipList) { clip ->
            FilePreviewCard(clip, onClipSelected)
        }
    }
}

@Composable
fun FilePreviewCard(
    clip: Clip,
    onClipSelected: (Clip) -> Unit
) {
    OutlinedCard(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(1.dp, Color.Black),
        modifier = Modifier
            .height(100.dp)
    ) {
        Row {
            Image(clip.thumbnail, contentDescription = "Preview")
            Text(
                text = clip.fileName,
                modifier = Modifier
                    .padding(16.dp),
                textAlign = TextAlign.Center,
            )
            IconButton(onClick = { onClipSelected(clip) } ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = "Play Clip"
                )
            }
        }
    }
}
