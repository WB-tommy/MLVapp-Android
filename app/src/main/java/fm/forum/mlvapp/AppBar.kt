package fm.forum.mlvapp

import android.util.Log
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

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
