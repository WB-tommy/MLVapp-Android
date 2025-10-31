package fm.magiclantern.forum

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
fun FocusPixelMapToast(
    requiredFileName: String,
    isBusy: Boolean,
    onSelectSingle: () -> Unit,
    onSelectAll: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {
            if (!isBusy) {
                onDismiss()
            }
        },
        title = { Text(text = stringResource(id = R.string.focus_pixel_missing_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(
                        id = R.string.focus_pixel_missing_body,
                        requiredFileName
                    )
                )
                if (isBusy) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text(text = stringResource(id = R.string.focus_pixel_download_in_progress))
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onSelectSingle, enabled = !isBusy) {
                    Text(text = stringResource(id = R.string.focus_pixel_download_single))
                }
                TextButton(onClick = onSelectAll, enabled = !isBusy) {
                    Text(text = stringResource(id = R.string.focus_pixel_download_all))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isBusy) {
                Text(text = stringResource(id = android.R.string.cancel))
            }
        }
    )
}
