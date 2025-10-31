package fm.magiclantern.forum

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fm.magiclantern.forum.data.Clip


@Composable
fun FileListView(
    clipList: List<Clip>,
    onClipSelected: (Clip) -> Unit,
    modifier: Modifier
) {
    LazyColumn(modifier = modifier) {
        items(clipList) { clip ->
            ClipListItem(clip, onClipSelected)
        }
    }
}

@Composable
fun ClipListItem(
    clip: Clip,
    onClipSelected: (Clip) -> Unit
) {
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
                .padding(horizontal = 8.dp),
            textAlign = TextAlign.Center,
            maxLines = 1
        )
        Box(
            modifier = Modifier
                .weight(0.5f)
                .fillMaxHeight()
                .clickable { onClipSelected(clip) },
            contentAlignment = Alignment.Center // Center the content (the button) in this
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = "Play Clip",
                modifier = Modifier.size(48.dp)
            )
        }
    }
}
