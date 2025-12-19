package fm.magiclantern.forum.features.export.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import fm.magiclantern.forum.features.export.model.CdngNaming
import fm.magiclantern.forum.features.export.model.CdngVariant
import fm.magiclantern.forum.features.export.model.DebayerQuality
import fm.magiclantern.forum.features.export.model.DnxhdProfile
import fm.magiclantern.forum.features.export.model.DnxhrProfile
import fm.magiclantern.forum.features.export.model.ExportCodec
import fm.magiclantern.forum.features.export.model.H264Container
import fm.magiclantern.forum.features.export.model.H264Quality
import fm.magiclantern.forum.features.export.model.H265BitDepth
import fm.magiclantern.forum.features.export.model.H265Container
import fm.magiclantern.forum.features.export.model.H265Quality
import fm.magiclantern.forum.features.export.model.PngBitDepth
import fm.magiclantern.forum.features.export.model.ProResEncoder
import fm.magiclantern.forum.features.export.model.ProResProfile
import fm.magiclantern.forum.features.export.model.SmoothingOption
import fm.magiclantern.forum.features.export.model.Vp9Quality
import fm.magiclantern.forum.features.export.viewmodel.ExportViewModel

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

            // Processing Options - use granular codec capability flags
            val debayerEnabled = settings.allowsDebayer
            val resizeEnabled = settings.allowsResize
            val smoothingEnabled = settings.allowsSmoothing
            val hdrBlendingEnabled = settings.allowsHdrBlending
            val fpsOverrideEnabled = settings.allowsFrameRateOverride
            val audioToggleEnabled = settings.allowsAudioToggle && !settings.frameRate.enabled

            // Debayer Section
            if (debayerEnabled) {
                SectionTitle("Debayer")
                DropdownSetting(
                    label = "Debayer Algorithm",
                    options = DebayerQuality.entries,
                    selectedOption = settings.debayerQuality,
                    onOptionSelected = exportViewModel::onDebayerQualitySelected,
                    optionLabel = { it.displayName }
                )
            }

            // Resize Settings
            if (resizeEnabled) {
                SectionTitle("Output Size")
                SwitchSetting(
                    label = "Resize Output",
                    checked = settings.resize.enabled,
                    onCheckedChange = exportViewModel::onResizeEnabledChanged,
                )

                if (settings.resize.enabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        var widthInput by remember { mutableStateOf(settings.resize.width.toString()) }
                        var heightInput by remember { mutableStateOf(settings.resize.height.toString()) }

                        OutlinedTextField(
                            value = widthInput,
                            onValueChange = {
                                widthInput = it
                                exportViewModel.onResizeWidthChanged(it)
                            },
                            label = { Text("Width") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                            enabled = true
                        )

                        OutlinedTextField(
                            value = heightInput,
                            onValueChange = {
                                heightInput = it
                                exportViewModel.onResizeHeightChanged(it)
                            },
                            label = { Text("Height") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                            enabled = true
                        )
                    }
                }
            }

            // Frame Rate Override
            if (fpsOverrideEnabled) {
                SectionTitle("Frame Rate")
                SwitchSetting(
                    label = "Frame Rate Override",
                    checked = settings.frameRate.enabled,
                    onCheckedChange = exportViewModel::onFrameRateOverrideEnabledChanged,
                )
                if (settings.frameRate.enabled) {
                    var frameRateInput by remember { mutableStateOf(settings.frameRate.value.toBigDecimal().toPlainString()) }
                    val isError = frameRateInput.toFloatOrNull() == null || frameRateInput.toFloat() !in 1f..60f

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
            }

            // Post-Processing Section (HDR Blending, Smoothing)
            if (hdrBlendingEnabled || smoothingEnabled) {
                SectionTitle("Post-Processing")

                if (hdrBlendingEnabled) {
                    SwitchSetting(
                        label = "HDR Blending",
                        checked = settings.hdrBlending,
                        onCheckedChange = exportViewModel::onHdrBlendingEnabledChanged,
                    )
                }

                if (smoothingEnabled) {
                    DropdownSetting(
                        label = "Aliasing / Moire",
                        options = SmoothingOption.entries,
                        selectedOption = settings.smoothing,
                        onOptionSelected = exportViewModel::onSmoothingOptionSelected,
                        optionLabel = { it.displayName }
                    )
                }
            }

            // General Options
            SectionTitle("General")
            SwitchSetting(
                label = "Include Audio",
                checked = settings.includeAudio,
                onCheckedChange = exportViewModel::onIncludeAudioChanged,
                enabled = audioToggleEnabled
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
