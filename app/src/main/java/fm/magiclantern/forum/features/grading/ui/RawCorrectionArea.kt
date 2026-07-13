package fm.magiclantern.forum.features.grading.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import fm.magiclantern.forum.domain.model.RawCorrectionSettings
import fm.magiclantern.forum.features.grading.viewmodel.GradingViewModel

/**
 * Raw Correction Area UI Component
 * All features call native functions via GradingViewModel
 *
 * Simplified signature - no more onStateChange callback,
 * ViewModel handles all state updates.
 */
@Composable
fun RawCorrectionArea(
    state: RawCorrectionSettings,
    gradingViewModel: GradingViewModel,
    dualIsoValid: Boolean,
    bitDepth: Int,
    onPickDarkFrame: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }

    // Check if dark frame file is loaded
    val darkFrameFileLoaded = state.darkFrameUri.isNotEmpty()

    // Calculate max white level based on bit depth
    val effectiveBitDepth = if (bitDepth in 8..16) bitDepth else 14
    val maxWhiteLevel = (2 shl (effectiveBitDepth - 1)) - 1

    // ========== RAW Levels state (lifted outside conditional to persist across expand/collapse) ==========
    // Get original values from ViewModel
    val originalBlackLevel by gradingViewModel.originalBlackLevel.collectAsState()
    val originalWhiteLevel by gradingViewModel.originalWhiteLevel.collectAsState()
    val hasClipLoaded by gradingViewModel.hasClipLoaded.collectAsState()

    // Local state for text fields (updates immediately for UI responsiveness)
    var blackLevelText by remember { mutableStateOf(state.dualIsoBlack.toString()) }
    var whiteLevelText by remember { mutableStateOf(state.dualIsoWhite.toString()) }

    // Parse current values
    val blackLevel = blackLevelText.toIntOrNull() ?: 0
    val whiteLevel = whiteLevelText.toIntOrNull() ?: 1

    // Receipt changes include direct clip-to-clip switches. Reflect them in
    // the fields without emitting setters during composition/restore.
    LaunchedEffect(state.dualIsoBlack) {
        if (blackLevelText.toIntOrNull() != state.dualIsoBlack) {
            blackLevelText = state.dualIsoBlack.toString()
        }
    }
    LaunchedEffect(state.dualIsoWhite) {
        if (whiteLevelText.toIntOrNull() != state.dualIsoWhite) {
            whiteLevelText = state.dualIsoWhite.toString()
        }
    }

    // Only user edits are debounced into the receipt/native state.
    LaunchedEffect(blackLevelText, state.dualIsoBlack, hasClipLoaded) {
        val candidate = blackLevelText.toIntOrNull() ?: return@LaunchedEffect
        if (!hasClipLoaded || candidate == state.dualIsoBlack) return@LaunchedEffect
        kotlinx.coroutines.delay(300)
        gradingViewModel.setRawBlackLevel(candidate)
    }
    LaunchedEffect(whiteLevelText, state.dualIsoWhite, hasClipLoaded) {
        val candidate = whiteLevelText.toIntOrNull() ?: return@LaunchedEffect
        if (!hasClipLoaded || candidate == state.dualIsoWhite) return@LaunchedEffect
        kotlinx.coroutines.delay(300)
        gradingViewModel.setRawWhiteLevel(candidate)
    }

    Card(
        modifier = modifier
            .fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Master Enable Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Enable RAW Correction",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = state.enabled,
                    onCheckedChange = { gradingViewModel.setRawCorrectionEnabled(it) }
                )
            }

            if (state.enabled && isExpanded) {
                HorizontalDivider()

                // Dark Frame Subtraction
                RawCorrectionSection(title = "Dark Frame Subtraction") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = state.darkFrameFileName,
                            onValueChange = { },
                            label = { Text("Dark Frame File") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            readOnly = true
                        )
                        Button(onClick = onPickDarkFrame) {
                            Text("Browse")
                        }
                    }

                    RadioButtonGroup(
                        options = listOf("Off", "Ext", "Int"),
                        selectedIndex = state.darkFrameEnabled.coerceIn(0, 2),
                        onSelectionChange = { mode ->
                            if (mode != 1 || darkFrameFileLoaded) {
                                gradingViewModel.setDarkFrameMode(mode)
                            }
                        },
                        enabled = true
                    )
                }

                HorizontalDivider()

                // Fix Focus Dots
                RawCorrectionSection(title = "Fix Focus Dots") {
                    RadioButtonGroup(
                        options = listOf("Off", "On", "CropRec"),
                        selectedIndex = state.focusPixels,
                        onSelectionChange = {
                            gradingViewModel.setFocusDotsMode(
                                it,
                                state.fpiMethod
                            )
                        }
                    )
                    if (state.focusPixels > 0) {
                        Text(
                            text = "Interpolation Method",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        RadioButtonGroup(
                            options = listOf("1", "2", "3"),
                            selectedIndex = state.fpiMethod,
                            onSelectionChange = {
                                gradingViewModel.setFocusDotsMode(
                                    state.focusPixels,
                                    it
                                )
                            }
                        )
                    }
                }

                HorizontalDivider()

                // Fix Bad Pixels
                RawCorrectionSection(title = "Fix Bad Pixels") {
                    RadioButtonGroup(
                        // "Map" will be added someday.
                        options = listOf("Off", "Auto", "Force"),
                        selectedIndex = state.badPixels,
                        onSelectionChange = {
                            gradingViewModel.setBadPixelsMode(
                                it,
                                state.bpsMethod,
                                state.bpiMethod
                            )
                        }
                    )
                    if (state.badPixels > 0) {
                        Text(
                            "Search Method",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        RadioButtonGroup(
                            options = listOf("Norm", "Aggr", "Edit"),
                            selectedIndex = state.bpsMethod,
                            onSelectionChange = {
                                gradingViewModel.setBadPixelsMode(
                                    state.badPixels,
                                    it,
                                    state.bpiMethod
                                )
                            }
                        )
                        Text(
                            "Interpolation Method",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        RadioButtonGroup(
                            options = listOf("1", "2", "3"),
                            selectedIndex = state.bpiMethod,
                            onSelectionChange = {
                                gradingViewModel.setBadPixelsMode(
                                    state.badPixels,
                                    state.bpsMethod,
                                    it
                                )
                            }
                        )
                    }
                }

                HorizontalDivider()

                // Chroma Smooth
                RawCorrectionSection(title = "Chroma Smooth") {
                    RadioButtonGroup(
                        options = listOf("Off", "2x2", "3x3", "5x5"),
                        selectedIndex = state.chromaSmooth,
                        onSelectionChange = { gradingViewModel.setChromaSmoothMode(it) }
                    )
                }

                HorizontalDivider()

                // Vertical Stripes
                RawCorrectionSection(title = "Vertical Stripes") {
                    RadioButtonGroup(
                        options = listOf("Off", "Normal", "Force"),
                        selectedIndex = state.verticalStripes,
                        onSelectionChange = { gradingViewModel.setVerticalStripesMode(it) }
                    )
                }

                HorizontalDivider()

                // Dual ISO
                RawCorrectionSection(title = "Dual ISO") {
                    RadioButtonGroup(
                        options = listOf("Off", "On", "Preview"),
                        selectedIndex = state.dualIso,
                        onSelectionChange = { gradingViewModel.setDualISO(it) }
                    )

                    if (state.dualIso > 0) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        if (!dualIsoValid) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Force",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                Checkbox(
                                    checked = state.dualIsoForced,
                                    onCheckedChange = { gradingViewModel.setDualISOForced(it) }
                                )
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        }

                        Text(
                            "Interpolation Method",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        RadioButtonGroup(
                            options = listOf("Amaze", "Mean"),
                            selectedIndex = state.dualIsoInterpolation,
                            onSelectionChange = { gradingViewModel.setDualISOInterpolation(it) }
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Alias Map",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = state.dualIsoAliasMap,
                                onCheckedChange = { gradingViewModel.setDualISOAliasMap(it) }
                            )
                        }
                    }
                }

                HorizontalDivider()

                // Pattern Noise
                RawCorrectionSection(title = "Pattern Noise") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Fix Pattern Noise",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = state.patternNoise > 0,
                            onCheckedChange = { gradingViewModel.setPatternNoise(it) }
                        )
                    }
                }

                HorizontalDivider()

                // Raw Black/White Levels
                RawCorrectionSection(title = "RAW Levels") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        // Black Level input with reset button
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                OutlinedTextField(
                                    value = blackLevelText,
                                    onValueChange = { newValue ->
                                        // Only allow digits
                                        val filtered = newValue.filter { it.isDigit() }
                                        if (filtered.isEmpty()) {
                                            blackLevelText = ""
                                            return@OutlinedTextField
                                        }
                                        val parsedValue = filtered.toIntOrNull() ?: 0
                                        // Black level can be 0 to (whiteLevel - 1)
                                        val maxBlack = (whiteLevel - 1).coerceAtLeast(0)
                                        val clamped = parsedValue.coerceIn(0, maxBlack)
                                        blackLevelText = clamped.toString()
                                    },
                                    label = { Text("Black Level") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f),
                                    enabled = hasClipLoaded
                                )
                                IconButton(
                                    onClick = {
                                        blackLevelText = originalBlackLevel.toString()
                                    },
                                    enabled = hasClipLoaded && blackLevel != originalBlackLevel
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Reset Black Level",
                                        tint = if (hasClipLoaded && blackLevel != originalBlackLevel)
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    )
                                }
                            }
                            Text(
                                text = "Original: $originalBlackLevel | Max: ${
                                    (whiteLevel - 1).coerceAtLeast(
                                        0
                                    )
                                }",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // White Level input with reset button
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                OutlinedTextField(
                                    value = whiteLevelText,
                                    onValueChange = { newValue ->
                                        // Only allow digits
                                        val filtered = newValue.filter { it.isDigit() }
                                        if (filtered.isEmpty()) {
                                            whiteLevelText = ""
                                            return@OutlinedTextField
                                        }
                                        val parsedValue = filtered.toIntOrNull() ?: 1
                                        // White level can be (blackLevel + 1) to maxWhiteLevel
                                        val minWhite = (blackLevel + 1).coerceAtLeast(1)
                                        val clamped = parsedValue.coerceIn(minWhite, maxWhiteLevel)
                                        whiteLevelText = clamped.toString()
                                    },
                                    label = { Text("White Level") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f),
                                    enabled = hasClipLoaded
                                )
                                IconButton(
                                    onClick = {
                                        whiteLevelText = originalWhiteLevel.toString()
                                    },
                                    enabled = hasClipLoaded && whiteLevel != originalWhiteLevel
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Reset White Level",
                                        tint = if (hasClipLoaded && whiteLevel != originalWhiteLevel)
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    )
                                }
                            }
                            Text(
                                text = "Original: $originalWhiteLevel | Min: ${
                                    (blackLevel + 1).coerceAtLeast(
                                        1
                                    )
                                } | Max: $maxWhiteLevel",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RawCorrectionSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        content()
    }
}

@Composable
fun RadioButtonGroup(
    options: List<String>,
    selectedIndex: Int,
    onSelectionChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEachIndexed { index, option ->
            FilterChip(
                selected = selectedIndex == index,
                onClick = { if (enabled) onSelectionChange(index) },
                label = { Text(option) },
                modifier = Modifier.weight(1f),
                enabled = enabled
            )
        }
    }
}
