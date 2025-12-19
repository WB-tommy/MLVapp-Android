package fm.magiclantern.forum.features.grading.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import fm.magiclantern.forum.features.grading.viewmodel.GradingViewModel

/**
 * Main Color Grading Screen
 * Container for all color grading and processing tools
 *
 * Now gets metadata (dualISO, bitDepth) directly from GradingViewModel
 * which observes ActiveClipHolder - no more prop drilling!
 */
@Composable
fun ColorGradingScreen(
    gradingViewModel: GradingViewModel,
    modifier: Modifier = Modifier
) {
    // Observe current clip's grading state from ViewModel
    val currentGrading by gradingViewModel.currentGrading.collectAsState()

    // Get metadata directly from ViewModel (derived from ActiveClipHolder)
    val dualIsoValid by gradingViewModel.dualIsoValid.collectAsState()
    val bitDepth by gradingViewModel.bitDepth.collectAsState()

    val context = LocalContext.current

    val scrollState = rememberScrollState()

    // Dark frame file picker
    val darkFramePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            gradingViewModel.setDarkFrameFile(context, it)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Raw Correction Section
        RawCorrectionArea(
            state = currentGrading.rawCorrection,
            gradingViewModel = gradingViewModel,
            dualIsoValid = dualIsoValid,
            bitDepth = bitDepth,
            onPickDarkFrame = {
                darkFramePickerLauncher.launch(arrayOf("*/*"))
            },
            modifier = Modifier.fillMaxWidth()
        )

        // Debayer Algorithm Selection (per-clip receipt setting)
        DebayerSelectSection(gradingViewModel = gradingViewModel)
    }
}
