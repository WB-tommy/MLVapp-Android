package fm.magiclantern.forum

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TheTopBar(
    onAddFileClick: () -> Unit,
    onSettingClick: () -> Unit,
    onExportClick: () -> Unit,
) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color(0xFF686868),
            titleContentColor = Color(0xFFFFFFFF),
        ),
        title = { Text("") },
        actions = {
            IconButton(onClick = onExportClick) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "Export",
                    tint = Color.White

                )
            }
            IconButton(onClick = onAddFileClick) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add File",
                    tint = Color.White
                )
            }
            IconButton(onClick = onSettingClick) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = Color.White
                )
            }
        },
    )
}
