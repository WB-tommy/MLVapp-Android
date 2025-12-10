
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
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
                        options = CdngVariant.entries,
                        selectedOption = settings.cdngVariant,
                        onOptionSelected = exportViewModel::onCdngVariantSelected,
                        optionLabel = { it.displayName }
                    )
                    DropdownSetting(
                        label = "Naming Schema",
                        options = CdngNaming.entries,
                        selectedOption = settings.cdngNaming,
                        onOptionSelected = exportViewModel::onCdngNamingSchemaSelected,
                        optionLabel = { it.displayName }
                    )
                }
                ExportCodec.PRORES -> {
                    SectionTitle("ProRes Options")
                    DropdownSetting(
                        label = "Profile",
                        options = ProResProfile.entries,
                        selectedOption = settings.proResProfile,
                        onOptionSelected = exportViewModel::onProResProfileSelected,
                        optionLabel = { it.displayName }
                    )
                    DropdownSetting(
                        label = "Encoder",
                        options = ProResEncoder.entries,
                        selectedOption = settings.proResEncoder,
                        onOptionSelected = exportViewModel::onProResEncoderSelected,
                        optionLabel = { it.displayName }
                    )
                }
                ExportCodec.H264 -> {
                    SectionTitle("H.264 Options")
                    Text(
                        text = "Hardware encoding (MediaCodec) with software fallback (libx264).",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    DropdownSetting(
                        label = "Quality",
                        options = H264Quality.entries,
                        selectedOption = settings.h264Quality,
                        onOptionSelected = exportViewModel::onH264QualitySelected,
                        optionLabel = { it.displayName }
                    )
                    DropdownSetting(
                        label = "Container",
                        options = H264Container.entries,
                        selectedOption = settings.h264Container,
                        onOptionSelected = exportViewModel::onH264ContainerSelected,
                        optionLabel = { it.displayName }
                    )
                }
                ExportCodec.H265 -> {
                    SectionTitle("H.265/HEVC Options")
                    Text(
                        text = "Hardware encoding (MediaCodec) with software fallback (libx265). Uses hvc1 tag for Apple compatibility.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    DropdownSetting(
                        label = "Bit Depth",
                        options = H265BitDepth.entries,
                        selectedOption = settings.h265BitDepth,
                        onOptionSelected = exportViewModel::onH265BitDepthSelected,
                        optionLabel = { it.displayName }
                    )
                    DropdownSetting(
                        label = "Quality",
                        options = H265Quality.entries,
                        selectedOption = settings.h265Quality,
                        onOptionSelected = exportViewModel::onH265QualitySelected,
                        optionLabel = { it.displayName }
                    )
                    DropdownSetting(
                        label = "Container",
                        options = H265Container.entries,
                        selectedOption = settings.h265Container,
                        onOptionSelected = exportViewModel::onH265ContainerSelected,
                        optionLabel = { it.displayName }
                    )
                }
                ExportCodec.DNXHR -> {
                    SectionTitle("DNxHR Options")
                    DropdownSetting(
                        label = "Profile",
                        options = DnxhrProfile.entries,
                        selectedOption = settings.dnxhrProfile,
                        onOptionSelected = exportViewModel::onDnxhrProfileSelected,
                        optionLabel = { it.displayName }
                    )
                }
                ExportCodec.DNXHD -> {
                    SectionTitle("DNxHD Options")
                    DropdownSetting(
                        label = "Preset",
                        options = DnxhdProfile.entries,
                        selectedOption = settings.dnxhdProfile,
                        onOptionSelected = exportViewModel::onDnxhdProfileSelected,
                        optionLabel = { it.displayName }
                    )
                }
                ExportCodec.VP9 -> {
                    SectionTitle("VP9/WebM Options")
                    DropdownSetting(
                        label = "Quality",
                        options = Vp9Quality.entries,
                        selectedOption = settings.vp9Quality,
                        onOptionSelected = exportViewModel::onVp9QualitySelected,
                        optionLabel = { it.displayName }
                    )
                }
                ExportCodec.TIFF -> {
                    SectionTitle("TIFF Options")
                    Text(
                        text = "16-bit RGB TIFF image sequence with BT.709 color space.",
                        style = MaterialTheme. typography.bodySmall
                    )
                }
                ExportCodec.PNG -> {
                    SectionTitle("PNG Options")
                    DropdownSetting(
                        label = "Bit Depth",
                        options = PngBitDepth.entries,
                        selectedOption = settings.pngBitDepth,
                        onOptionSelected = exportViewModel::onPngBitDepthSelected,
                        optionLabel = { it.displayName }
                    )
                }
                ExportCodec.JPEG2000 -> {
                    SectionTitle("JPEG 2000 Options")
                    Text(
                        text = "JPEG 2000 image sequence (YUV 4:4:4).",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                ExportCodec.AUDIO_ONLY -> {
                    SectionTitle("Audio Export")
                    Text(
                        text = "Only audio will be written. Video processing options are disabled.",
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
                var frameRateInput by remember { mutableStateOf(settings.frameRate.value.toBigDecimal().toPlainString()) }
                val isError = frameRateInput.toFloatOrNull() == null || frameRateInput.toFloat() !in 1f..120f

                OutlinedTextField(
                    value = frameRateInput,
                    onValueChange = {
                        frameRateInput = it
                        exportViewModel.onFrameRateChanged(it)
                    },
                    label = { Text("Target FPS") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = isError,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = true
                )
            }

            DropdownSetting(
                label = "Aliasing / Moire",
                options = SmoothingOption.entries,
                selectedOption = settings.smoothing,
                onOptionSelected = exportViewModel::onSmoothingOptionSelected,
                optionLabel = { it.displayName },
                enabled = processingOptionsEnabled
            )

            // General Options
            SectionTitle("General")
            SwitchSetting(
                label = "Include Audio",
                checked = settings.includeAudio,
                onCheckedChange = exportViewModel::onIncludeAudioChanged,
                enabled = !settings.frameRate.enabled
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
