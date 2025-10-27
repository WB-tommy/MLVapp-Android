
package fm.forum.mlvapp.export

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import fm.forum.mlvapp.data.Clip

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSelectionScreen(
    exportViewModel: ExportViewModel,
    navController: NavHostController
) {
    val uiState by exportViewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Select Clips to Export") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // TODO: Add "Select All" / "Deselect All" logic here
                }
            )
        },
        floatingActionButton = {
            if (uiState.selectedClips.isNotEmpty()) {
                FloatingActionButton(onClick = {
                    navController.navigate("export_settings")
                }) {
                    Icon(Icons.Default.ArrowForward, contentDescription = "Next")
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(contentPadding = paddingValues) {
            items(uiState.clips) { clip ->
                ClipListItem(
                    clip = clip,
                    isSelected = uiState.selectedClips.contains(clip.guid),
                    onClipSelected = { exportViewModel.toggleClipSelection(clip) }
                )
            }
        }
    }
}

@Composable
fun ClipListItem(
    clip: Clip,
    isSelected: Boolean,
    onClipSelected: () -> Unit
) {
    Row(
        modifier = Modifier
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
            Text(text = clip.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(text = "${clip.width}x${clip.height}", style = MaterialTheme.typography.bodySmall)
        }
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onClipSelected() }
        )
    }
}
