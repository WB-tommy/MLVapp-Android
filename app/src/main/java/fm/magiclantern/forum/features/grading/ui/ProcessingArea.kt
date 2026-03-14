package fm.magiclantern.forum.features.grading.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import fm.magiclantern.forum.domain.model.ColorGradingSettings
import fm.magiclantern.forum.features.grading.viewmodel.GradingViewModel

@Composable
fun ProcessingArea(
    state: ColorGradingSettings,
    gradingViewModel: GradingViewModel,
    hasClipLoaded: Boolean,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(true) }
    val focusManager = LocalFocusManager.current

    val originalKelvin by gradingViewModel.originalWhiteBalanceKelvin.collectAsState()
    val originalTint by gradingViewModel.originalWhiteBalanceTint.collectAsState()

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

                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Exposure",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = String.format(java.util.Locale.US, "%.2f EV", state.exposure),
                                style = MaterialTheme.typography.labelMedium
                            )
                            IconButton(
                                onClick = {
                                    gradingViewModel.setExposure(0f)
                                },
                                enabled = hasClipLoaded && state.exposure != 0f,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Reset Exposure",
                                    tint = if (hasClipLoaded && state.exposure != 0f)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                    
                    var expText by remember(state.exposure) {
                        mutableStateOf(String.format(java.util.Locale.US, "%.2f", state.exposure))
                    }
                    val commitExposure = {
                        val parsed = expText.toFloatOrNull()
                        if (parsed != null) {
                            val clamped = parsed.coerceIn(-4f, 4f)
                            gradingViewModel.setExposure(clamped)
                            expText = String.format(java.util.Locale.US, "%.2f", clamped)
                        } else {
                            expText = String.format(java.util.Locale.US, "%.2f", state.exposure)
                        }
                    }
                    OutlinedTextField(
                        value = expText,
                        onValueChange = { expText = it },
                        singleLine = true,
                        enabled = hasClipLoaded,
                        label = { Text("EV (-4.0 to 4.0)") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = {
                            commitExposure()
                            focusManager.clearFocus()
                        }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { if (!it.isFocused) commitExposure() }
                    )
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Temperature",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "${state.temperature}K",
                                style = MaterialTheme.typography.labelMedium
                            )
                            IconButton(
                                onClick = {
                                    gradingViewModel.setTemperature(originalKelvin)
                                },
                                enabled = hasClipLoaded && state.temperature != originalKelvin,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Reset Temperature",
                                    tint = if (hasClipLoaded && state.temperature != originalKelvin)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                    
                    var tempText by remember(state.temperature) {
                        mutableStateOf(state.temperature.toString())
                    }
                    val commitTemperature = {
                        val parsed = tempText.toIntOrNull()
                        if (parsed != null) {
                            val clamped = parsed.coerceIn(2000, 10000)
                            gradingViewModel.setTemperature(clamped)
                            tempText = clamped.toString()
                        } else {
                            tempText = state.temperature.toString()
                        }
                    }
                    OutlinedTextField(
                        value = tempText,
                        onValueChange = { tempText = it },
                        singleLine = true,
                        enabled = hasClipLoaded,
                        label = { Text("Kelvin (2000 - 10000)") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = {
                            commitTemperature()
                            focusManager.clearFocus()
                        }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { if (!it.isFocused) commitTemperature() }
                    )
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Tint",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "${state.tint}",
                                style = MaterialTheme.typography.labelMedium
                            )
                            IconButton(
                                onClick = {
                                    gradingViewModel.setTint(originalTint)
                                },
                                enabled = hasClipLoaded && state.tint != originalTint,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Reset Tint",
                                    tint = if (hasClipLoaded && state.tint != originalTint)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                    
                    var tintText by remember(state.tint) {
                        mutableStateOf(state.tint.toString())
                    }
                    val commitTint = {
                        val parsed = tintText.toIntOrNull()
                        if (parsed != null) {
                            val clamped = parsed.coerceIn(-100, 100)
                            gradingViewModel.setTint(clamped)
                            tintText = clamped.toString()
                        } else {
                            tintText = state.tint.toString()
                        }
                    }
                    OutlinedTextField(
                        value = tintText,
                        onValueChange = { tintText = it },
                        singleLine = true,
                        enabled = hasClipLoaded,
                        label = { Text("Tint (-100 to 100)") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = {
                            commitTint()
                            focusManager.clearFocus()
                        }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { if (!it.isFocused) commitTint() }
                    )
                }
            }
        }
    }
}
