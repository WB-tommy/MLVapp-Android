package fm.magiclantern.forum.features.grading.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.unit.dp
import fm.magiclantern.forum.R
import fm.magiclantern.forum.domain.model.ColorGradingSettings
import fm.magiclantern.forum.domain.model.ProfilePreset
import fm.magiclantern.forum.features.grading.viewmodel.GradingViewModel

/**
 * Profiles Section — matches the desktop groupBoxProfiles layout.
 *
 * Layout (matching MainWindow.ui rows):
 *  - Profile Preset dropdown
 *  - Processing Gamut dropdown (enabled only when Camera Matrix > 0)
 *  - Transfer Function text field + reset button
 *  - Allow Creative Adjustments checkbox
 *  - Camera Matrix dropdown
 *  - Cyan Highlight Fix checkbox (enabled only when Camera Matrix > 0)
 *  - AgX checkbox
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesArea(
    state: ColorGradingSettings,
    gradingViewModel: GradingViewModel,
    hasClipLoaded: Boolean,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(true) }

    // Derived state: is color science enabled (camera matrix active)?
    val camMatrixEnabled = state.camMatrixUsed > 0

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Profiles",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
            }

            if (isExpanded) {
                HorizontalDivider()

                // ── Profile Preset Dropdown ──
                ProfilePresetDropdown(
                    state = state,
                    gradingViewModel = gradingViewModel,
                    hasClipLoaded = hasClipLoaded
                )

                // ── Processing Gamut Dropdown ──
                ProcessingGamutDropdown(
                    state = state,
                    gradingViewModel = gradingViewModel,
                    hasClipLoaded = hasClipLoaded,
                    camMatrixEnabled = camMatrixEnabled
                )

                // ── Transfer Function ──
                TransferFunctionField(
                    state = state,
                    gradingViewModel = gradingViewModel,
                    hasClipLoaded = hasClipLoaded
                )

                // ── Allow Creative Adjustments ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = state.allowCreativeAdjustments == 1,
                        onCheckedChange = { gradingViewModel.setCreativeAdjustments(it) },
                        enabled = hasClipLoaded
                    )
                    Text(
                        text = "Allow Creative Adjustments",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.clickable(enabled = hasClipLoaded) {
                            gradingViewModel.setCreativeAdjustments(state.allowCreativeAdjustments != 1)
                        }
                    )
                }

                HorizontalDivider()

                // ── Camera Matrix Dropdown ──
                CameraMatrixDropdown(
                    state = state,
                    gradingViewModel = gradingViewModel,
                    hasClipLoaded = hasClipLoaded
                )

                // ── Cyan Highlight Fix ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = state.exrMode == 1,
                        onCheckedChange = { gradingViewModel.setExrMode(it) },
                        enabled = hasClipLoaded && camMatrixEnabled
                    )
                    Text(
                        text = "Cyan Highlight Fix",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (hasClipLoaded && camMatrixEnabled)
                            MaterialTheme.colorScheme.onSurface
                        else
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        modifier = Modifier.clickable(enabled = hasClipLoaded && camMatrixEnabled) {
                            gradingViewModel.setExrMode(state.exrMode != 1)
                        }
                    )
                }

                // ── AgX ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = state.agx == 1,
                        onCheckedChange = { gradingViewModel.setAgX(it) },
                        enabled = hasClipLoaded
                    )
                    Text(
                        text = "AgX",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.clickable(enabled = hasClipLoaded) {
                            gradingViewModel.setAgX(state.agx != 1)
                        }
                    )
                }
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Sub-components (ISP: each dropdown is its own composable)
// ────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfilePresetDropdown(
    state: ColorGradingSettings,
    gradingViewModel: GradingViewModel,
    hasClipLoaded: Boolean
) {
    val presetNames = listOf("Select Preset...") + ProfilePreset.all.map { it.displayName }
    var expanded by remember { mutableStateOf(false) }

    Text(
        text = "Profile Preset",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
    )

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (hasClipLoaded) expanded = !expanded },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = presetNames.getOrElse(state.profileIndex) { "Select Preset..." },
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier
                .menuAnchor(
                    type = ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                    enabled = hasClipLoaded
                )
                .fillMaxWidth(),
            enabled = hasClipLoaded
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            // Skip index 0 ("Select Preset...") — presets start at index 1
            ProfilePreset.all.forEachIndexed { index, preset ->
                DropdownMenuItem(
                    text = { Text(preset.displayName) },
                    onClick = {
                        gradingViewModel.applyProfilePreset(preset)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProcessingGamutDropdown(
    state: ColorGradingSettings,
    gradingViewModel: GradingViewModel,
    hasClipLoaded: Boolean,
    camMatrixEnabled: Boolean
) {
    val gamutOptions = stringArrayResource(R.array.gamut_options)
    var expanded by remember { mutableStateOf(false) }

    val isEnabled = hasClipLoaded && camMatrixEnabled

    Text(
        text = "Processing Gamut",
        style = MaterialTheme.typography.labelLarge,
        color = if (isEnabled) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    )

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (isEnabled) expanded = !expanded },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = gamutOptions.getOrElse(state.gamut) { "Unknown" },
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier
                .menuAnchor(
                    type = ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                    enabled = isEnabled
                )
                .fillMaxWidth(),
            enabled = isEnabled
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            gamutOptions.forEachIndexed { index, name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        gradingViewModel.setGamut(index)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun TransferFunctionField(
    state: ColorGradingSettings,
    gradingViewModel: GradingViewModel,
    hasClipLoaded: Boolean
) {
    val defaultTransferFunction = "(x < 0.0) ? 0 : pow(x / (1.0 + x), 1/3.15)"

    Text(
        text = "Transfer Function",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = state.transferFunction,
            onValueChange = { gradingViewModel.setTransferFunction(it) },
            modifier = Modifier.weight(1f),
            enabled = hasClipLoaded,
            textStyle = MaterialTheme.typography.bodySmall
        )
        IconButton(
            onClick = { gradingViewModel.setTransferFunction(defaultTransferFunction) },
            enabled = hasClipLoaded && state.transferFunction != defaultTransferFunction
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Reset Transfer Function",
                tint = if (hasClipLoaded && state.transferFunction != defaultTransferFunction)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CameraMatrixDropdown(
    state: ColorGradingSettings,
    gradingViewModel: GradingViewModel,
    hasClipLoaded: Boolean
) {
    val matrixOptions = stringArrayResource(R.array.camera_matrix_options)
    var expanded by remember { mutableStateOf(false) }

    Text(
        text = "Camera Matrix",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
    )

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (hasClipLoaded) expanded = !expanded },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = matrixOptions.getOrElse(state.camMatrixUsed) { "Unknown" },
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier
                .menuAnchor(
                    type = ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                    enabled = hasClipLoaded
                )
                .fillMaxWidth(),
            enabled = hasClipLoaded
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            matrixOptions.forEachIndexed { index, name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        gradingViewModel.setCameraMatrix(index)
                        expanded = false
                    }
                )
            }
        }
    }
}
