package fm.magiclantern.forum.features.clips.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import fm.magiclantern.forum.features.clips.viewmodel.ClipListViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipRemovalScreen(
    clipListViewModel: ClipListViewModel,
    navController: NavHostController
) {
    val uiState by clipListViewModel.uiState.collectAsState()
    val removalState by clipListViewModel.removalState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Select Clips to Remove") },
                navigationIcon = {
                    IconButton(onClick = {
                        clipListViewModel.cancelClipRemoval()
                        navController.popBackStack()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    val allSelected = removalState.selectedClips.size == uiState.clips.size
                            && uiState.clips.isNotEmpty()
                    TextButton(
                        onClick = {
                            if (allSelected) {
                                clipListViewModel.deselectAllClipsForRemoval()
                            } else {
                                clipListViewModel.selectAllClipsForRemoval()
                            }
                        }
                    ) {
                        Text(if (allSelected) "Deselect All" else "Select All")
                    }
                }
            )
        },
        floatingActionButton = {
            if (removalState.selectedClips.isNotEmpty()) {
                FloatingActionButton(
                    onClick = {
                        clipListViewModel.confirmClipRemoval()
                        navController.popBackStack()
                    },
                    containerColor = MaterialTheme.colorScheme.error
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete Selected",
                        tint = MaterialTheme.colorScheme.onError
                    )
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            items(uiState.clips) { clip ->
                SelectableClipListItem(
                    clip = clip,
                    isSelected = removalState.selectedClips.contains(clip.guid),
                    onClipSelected = { clipListViewModel.toggleClipSelectionForRemoval(clip) }
                )
            }
        }
    }
}
