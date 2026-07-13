package fm.magiclantern.forum.features.grading.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import fm.magiclantern.forum.domain.model.ColorGradingSettings
import fm.magiclantern.forum.features.grading.viewmodel.GradingViewModel
import fm.magiclantern.forum.features.grading.viewmodel.WhiteBalancePickerMode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * CPU controls matching desktop MainWindow.ui's Processing group.
 * Gradation curves are intentionally excluded.
 */
@Composable
fun ProcessingArea(
    state: ColorGradingSettings,
    gradingViewModel: GradingViewModel,
    hasClipLoaded: Boolean,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(true) }
    val originalKelvin by gradingViewModel.originalWhiteBalanceKelvin.collectAsState()
    val originalTint by gradingViewModel.originalWhiteBalanceTint.collectAsState()
    val whiteBalancePickerActive by
        gradingViewModel.whiteBalancePickerActive.collectAsState()
    val whiteBalancePickerMode by
        gradingViewModel.whiteBalancePickerMode.collectAsState()
    val whiteBalancePickInProgress by
        gradingViewModel.whiteBalancePickInProgress.collectAsState()
    val creativeAdjustmentsEnabled = hasClipLoaded && state.allowCreativeAdjustments == 1

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
                    text = "Processing",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
            }

            if (isExpanded) {
                HorizontalDivider()

                ProcessingSlider(
                    label = "Exposure",
                    value = state.exposure,
                    valueRange = -4f..4f,
                    resetValue = 0f,
                    enabled = hasClipLoaded,
                    displayValue = { String.format(Locale.US, "%.2f EV", it) },
                    editValue = { String.format(Locale.US, "%.2f", it) },
                    normalize = { (it * 100f).roundToInt() / 100f },
                    rangeDescription = "-4.00 to 4.00 EV",
                    onValueCommitted = gradingViewModel::setExposure
                )

                IntegerProcessingSlider(
                    label = "Contrast",
                    value = state.contrast,
                    valueRange = -100..100,
                    resetValue = 0,
                    enabled = creativeAdjustmentsEnabled,
                    onValueCommitted = gradingViewModel::setContrast
                )

                IntegerProcessingSlider(
                    label = "Pivot",
                    value = state.pivot,
                    valueRange = 0..100,
                    resetValue = 75,
                    enabled = creativeAdjustmentsEnabled,
                    displayValue = { String.format(Locale.US, "%.2f", it / 100f) },
                    onValueCommitted = gradingViewModel::setPivot
                )

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = gradingViewModel::toggleWhiteBalancePicker,
                        enabled = hasClipLoaded && !whiteBalancePickInProgress
                    ) {
                        Text(
                            when {
                                whiteBalancePickInProgress -> "Picking…"
                                whiteBalancePickerActive -> "Cancel WB Picker"
                                else -> "Pick WB from Preview"
                            }
                        )
                    }
                    TextButton(
                        onClick = {
                            gradingViewModel.setWhiteBalancePickerMode(
                                if (whiteBalancePickerMode == WhiteBalancePickerMode.GREY) {
                                    WhiteBalancePickerMode.SKIN
                                } else {
                                    WhiteBalancePickerMode.GREY
                                }
                            )
                        },
                        enabled = hasClipLoaded && !whiteBalancePickInProgress
                    ) {
                        Text("Mode: ${whiteBalancePickerMode.displayName}")
                    }
                }

                if (whiteBalancePickerActive) {
                    Text(
                        text = if (whiteBalancePickerMode == WhiteBalancePickerMode.GREY) {
                            "Tap a neutral grey area in the preview."
                        } else {
                            "Tap a representative skin area in the preview."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IntegerProcessingSlider(
                    label = "Temperature",
                    value = state.temperature,
                    valueRange = 2000..10000,
                    resetValue = originalKelvin.coerceIn(2000, 10000),
                    enabled = hasClipLoaded && !whiteBalancePickInProgress,
                    displayValue = { "$it K" },
                    onValueCommitted = gradingViewModel::setTemperature
                )

                IntegerProcessingSlider(
                    label = "Tint",
                    value = state.tint,
                    valueRange = -100..100,
                    resetValue = originalTint.coerceIn(-100, 100),
                    enabled = hasClipLoaded && !whiteBalancePickInProgress,
                    onValueCommitted = gradingViewModel::setTint
                )

                HorizontalDivider()

                IntegerProcessingSlider(
                    label = "Clarity",
                    value = state.clarity,
                    valueRange = -100..100,
                    resetValue = 0,
                    enabled = creativeAdjustmentsEnabled,
                    onValueCommitted = gradingViewModel::setClarity
                )

                IntegerProcessingSlider(
                    label = "Vibrance",
                    value = state.vibrance,
                    valueRange = -100..100,
                    resetValue = 0,
                    enabled = creativeAdjustmentsEnabled,
                    onValueCommitted = gradingViewModel::setVibrance
                )

                IntegerProcessingSlider(
                    label = "Saturation",
                    value = state.saturation,
                    valueRange = -100..100,
                    resetValue = 0,
                    enabled = creativeAdjustmentsEnabled,
                    onValueCommitted = gradingViewModel::setSaturation
                )

                HorizontalDivider()

                IntegerProcessingSlider(
                    label = "Dark Strength",
                    value = state.ds,
                    valueRange = 0..100,
                    resetValue = 20,
                    enabled = creativeAdjustmentsEnabled,
                    onValueCommitted = gradingViewModel::setDarkStrength
                )

                IntegerProcessingSlider(
                    label = "Dark Range",
                    value = state.dr,
                    valueRange = 0..100,
                    resetValue = 70,
                    enabled = creativeAdjustmentsEnabled,
                    onValueCommitted = gradingViewModel::setDarkRange
                )

                IntegerProcessingSlider(
                    label = "Light Strength",
                    value = state.ls,
                    valueRange = 0..100,
                    resetValue = 0,
                    enabled = creativeAdjustmentsEnabled,
                    onValueCommitted = gradingViewModel::setLightStrength
                )

                IntegerProcessingSlider(
                    label = "Light Range",
                    value = state.lr,
                    valueRange = 0..100,
                    resetValue = 50,
                    enabled = creativeAdjustmentsEnabled,
                    onValueCommitted = gradingViewModel::setLightRange
                )

                IntegerProcessingSlider(
                    label = "Lighten",
                    value = state.lightening,
                    valueRange = 0..100,
                    resetValue = 0,
                    enabled = creativeAdjustmentsEnabled,
                    onValueCommitted = gradingViewModel::setLightening
                )

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = state.highlightReconstruction == 1,
                        onCheckedChange = gradingViewModel::setHighlightReconstruction,
                        enabled = hasClipLoaded
                    )
                    Text(
                        text = "Highlight Reconstruction",
                        style = MaterialTheme.typography.bodyMedium,
                        color = enabledContentColor(hasClipLoaded),
                        modifier = Modifier.clickable(enabled = hasClipLoaded) {
                            gradingViewModel.setHighlightReconstruction(
                                state.highlightReconstruction != 1
                            )
                        }
                    )
                }

                IntegerProcessingSlider(
                    label = "Highlights",
                    value = state.highlights,
                    valueRange = -100..100,
                    resetValue = 0,
                    enabled = creativeAdjustmentsEnabled,
                    onValueCommitted = gradingViewModel::setHighlights
                )

                IntegerProcessingSlider(
                    label = "Shadows",
                    value = state.shadows,
                    valueRange = -100..100,
                    resetValue = 0,
                    enabled = creativeAdjustmentsEnabled,
                    onValueCommitted = gradingViewModel::setShadows
                )

                if (hasClipLoaded && !creativeAdjustmentsEnabled) {
                    Text(
                        text = "The selected profile disables creative adjustments. " +
                            "Exposure, white balance, and highlight reconstruction remain available.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun IntegerProcessingSlider(
    label: String,
    value: Int,
    valueRange: IntRange,
    resetValue: Int,
    enabled: Boolean,
    displayValue: (Int) -> String = { it.toString() },
    onValueCommitted: (Int) -> Unit
) {
    ProcessingSlider(
        label = label,
        value = value.toFloat(),
        valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
        resetValue = resetValue.toFloat(),
        enabled = enabled,
        displayValue = { displayValue(it.roundToInt()) },
        editValue = { it.roundToInt().toString() },
        normalize = { it.roundToInt().toFloat() },
        rangeDescription = "${valueRange.first} to ${valueRange.last}",
        onValueCommitted = { onValueCommitted(it.roundToInt()) }
    )
}

@Composable
private fun ProcessingSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    resetValue: Float,
    enabled: Boolean,
    displayValue: (Float) -> String,
    editValue: (Float) -> String,
    normalize: (Float) -> Float,
    rangeDescription: String,
    onValueCommitted: (Float) -> Unit
) {
    var pendingValue by remember(value) {
        mutableFloatStateOf(value.coerceIn(valueRange.start, valueRange.endInclusive))
    }
    var showEditor by remember { mutableStateOf(false) }
    var editorText by remember { mutableStateOf("") }

    fun commit(rawValue: Float) {
        val committed = normalize(
            rawValue.coerceIn(valueRange.start, valueRange.endInclusive)
        ).coerceIn(valueRange.start, valueRange.endInclusive)
        pendingValue = committed
        if (abs(committed - value) > 0.0001f) {
            onValueCommitted(committed)
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        editorText = editValue(pendingValue)
                        showEditor = true
                    },
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text(displayValue(pendingValue))
                }
                IconButton(
                    onClick = { commit(resetValue) },
                    enabled = enabled && abs(value - resetValue) > 0.0001f,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reset $label",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Slider(
            value = pendingValue,
            onValueChange = { pendingValue = it },
            onValueChangeFinished = { commit(pendingValue) },
            valueRange = valueRange,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        )
    }

    if (showEditor) {
        val parsedValue = editorText.toFloatOrNull()?.takeIf(Float::isFinite)
        AlertDialog(
            onDismissRequest = { showEditor = false },
            title = { Text("Set $label") },
            text = {
                OutlinedTextField(
                    value = editorText,
                    onValueChange = { editorText = it },
                    singleLine = true,
                    label = { Text(rangeDescription) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        parsedValue?.let(::commit)
                        showEditor = false
                    },
                    enabled = parsedValue != null
                ) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditor = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun enabledContentColor(enabled: Boolean) =
    if (enabled) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
