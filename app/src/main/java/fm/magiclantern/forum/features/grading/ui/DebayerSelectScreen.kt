package fm.magiclantern.forum.features.grading.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import fm.magiclantern.forum.domain.model.DebayerAlgorithm
import fm.magiclantern.forum.features.grading.viewmodel.GradingViewModel

/**
 * Debayer algorithm selection component for grading screen.
 * 
 * Sets the per-clip debayer algorithm (receipt setting) which is used:
 * - During playback (preview)
 * - During export when "Receipt" is selected in export settings
 * 
 * Mirrors desktop comboBoxDebayer functionality.
 */
@Composable
fun DebayerSelectSection(
    gradingViewModel: GradingViewModel,
    modifier: Modifier = Modifier
) {
    val currentGrading by gradingViewModel.currentGrading.collectAsState()
    val hasClipLoaded by gradingViewModel.hasClipLoaded.collectAsState()
    var showDialog by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = hasClipLoaded) { showDialog = true }
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Debayer Algorithm",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = currentGrading.debayerMode.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasClipLoaded) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    }
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = if (hasClipLoaded) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                }
            )
        }
    }

    if (showDialog && hasClipLoaded) {
        DebayerAlgorithmDialog(
            current = currentGrading.debayerMode,
            onSelect = { mode ->
                gradingViewModel.setDebayerMode(mode)
                showDialog = false
            },
            onDismiss = { showDialog = false }
        )
    }
}

@Composable
private fun DebayerAlgorithmDialog(
    current: DebayerAlgorithm,
    onSelect: (DebayerAlgorithm) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Debayer Algorithm") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                DebayerAlgorithm.entries.forEach { mode ->
                    val selected = mode == current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selected,
                                onClick = { onSelect(mode) },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = null
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = mode.displayName,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Preview
@Composable
fun DebayerSelectScreenPreview() {
    // Preview with a mock state - in real use, pass GradingViewModel
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Debayer Algorithm",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "AMaZE",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}