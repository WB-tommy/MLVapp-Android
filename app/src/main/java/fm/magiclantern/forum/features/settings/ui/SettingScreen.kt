package fm.magiclantern.forum.features.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import fm.magiclantern.forum.features.settings.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavHostController
) {
    val viewModel: SettingsViewModel = hiltViewModel()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Settings") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = "Go Back")
                    }
                }
            )
        }
    ) { padding ->
        SettingsContent(
            viewModel = viewModel,
            modifier = Modifier.padding(padding)
        )
    }
}

@Composable
fun SettingsContent(viewModel: SettingsViewModel, modifier: Modifier = Modifier) {
    val isDropFrameMode by viewModel.isDropFrameMode.collectAsState()
    val experimentalRawGpuPreview by viewModel.experimentalRawGpuPreview.collectAsState()
    val experimentalMcrawParallelDecoder by
        viewModel.experimentalMcrawParallelDecoder.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        SettingsCategory(title = "Playback")

        SwitchSettingItem(
            title = "Real-Time Playback",
            summary = if (isDropFrameMode) "Drop frames to stay in sync" else "Advance one frame at a time",
            checked = isDropFrameMode,
            onCheckedChange = { enabled -> viewModel.setDropFrameMode(enabled) }
        )

        SwitchSettingItem(
            title = "Experimental RAW GPU Playback",
            summary = "MCRAW + uncompressed/LJ92 MLV: CPU RAW corrections, then " +
                "GPU levels, WB, bilinear demosaic, profile and grading",
            checked = experimentalRawGpuPreview,
            onCheckedChange = viewModel::setExperimentalRawGpuPreview
        )

        SwitchSettingItem(
            title = "MotionCam Parallel Playback Decoder",
            summary = "MCRAW type-7 CPU and GPU playback with up to 4 workers; " +
                "legacy MCRAW and MLV use their built-in decoders",
            checked = experimentalMcrawParallelDecoder,
            onCheckedChange = viewModel::setExperimentalMcrawParallelDecoder
        )

    }
}

@Composable
private fun SettingsCategory(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp)
    )
}

@Composable
private fun SwitchSettingItem(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .alpha(if (enabled) 1f else 0.38f)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}
