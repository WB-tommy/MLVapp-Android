
package fm.magiclantern.forum.export

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSettingsScreen(
    exportViewModel: ExportViewModel,
    navController: NavHostController
) {
    val uiState by exportViewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Export Settings") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            Button(
                onClick = { navController.navigate("export_location") },
                modifier = Modifier
                    .navigationBarsPadding()
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text("Next")
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            val settings = uiState.settings

            // Codec Selection
            SectionTitle("Codec")
            // NOTE(keeper): Only CinemaDNG is wired end-to-end right now. Other codecs remain
            // in enums/state for future work but the selector is intentionally disabled so
            // automated tooling doesn't re-enable partially implemented paths.
            DropdownSetting(
                label = "Codec",
                options = uiState.availableCodecs,
                selectedOption = settings.codec,
                onOptionSelected = exportViewModel::onCodecSelected,
                optionLabel = { it.displayName }
            )

            // Codec-Specific Options
            when (settings.codec) {
                ExportCodec.CINEMA_DNG -> {
                    SectionTitle("CinemaDNG Options")
                    DropdownSetting(
                        label = "Variant",
                        options = CdngVariant.values().toList(),
                        selectedOption = settings.cdngVariant,
                        onOptionSelected = exportViewModel::onCdngVariantSelected,
                        optionLabel = { it.displayName }
                    )
                    DropdownSetting(
                        label = "Naming Schema",
                        options = CdngNaming.values().toList(),
                        selectedOption = settings.cdngNaming,
                        onOptionSelected = exportViewModel::onCdngNamingSchemaSelected,
                        optionLabel = { it.displayName }
                    )
                }
                ExportCodec.AUDIO_ONLY -> {
                    SectionTitle("Audio Export")
                    Text(
                        text = "Only audio will be written. Video processing options are disabled.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                ExportCodec.PRORES,
                ExportCodec.H264,
                ExportCodec.H265,
                ExportCodec.TIFF,
                ExportCodec.PNG -> {
                    // NOTE(keeper): UI intentionally capped to supported variants. These branches
                    // should remain inert until full export support is implemented.
                    SectionTitle("${settings.codec.displayName} Options")
                    Text(
                        text = "This codec is not yet available on Android.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Processing Options
            val processingOptionsEnabled = settings.requiresRawProcessing
            val resizeEnabled = processingOptionsEnabled && settings.allowsResize
            SectionTitle("Processing", enabled = processingOptionsEnabled)
            // NOTE(keeper): Processing pipeline beyond FPS override is pending port. UI controls kept minimal.
            SwitchSetting(
                label = "Frame Rate Override",
                checked = settings.frameRate.enabled,
                onCheckedChange = exportViewModel::onFrameRateOverrideEnabledChanged,
                enabled = true
            )
            if (settings.frameRate.enabled) {
                val selectedPreset = uiState.frameRatePresets.firstOrNull {
                    abs(it.value - settings.frameRate.value) < 0.01f
                } ?: FrameRatePreset.FPS_23976
                DropdownSetting(
                    label = "Target FPS",
                    options = uiState.frameRatePresets,
                    selectedOption = selectedPreset,
                    onOptionSelected = exportViewModel::onFrameRateSelected,
                    optionLabel = { it.displayLabel },
                    enabled = true
                )
            }

            // General Options
            SectionTitle("General")
            SwitchSetting(
                label = "Include Audio",
                checked = settings.includeAudio,
                onCheckedChange = exportViewModel::onIncludeAudioChanged,
                enabled = true
            )
        }
    }
}

@Composable
fun SectionTitle(title: String, enabled: Boolean = true) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> DropdownSetting(
    label: String,
    options: List<T>,
    selectedOption: T,
    onOptionSelected: (T) -> Unit,
    optionLabel: (T) -> String,
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth()
    ) {
    OutlinedTextField(
        value = optionLabel(selectedOption),
        onValueChange = { _ -> },
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            enabled = enabled
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onOptionSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun SwitchSetting(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}
