package fm.magiclantern.forum.features.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fm.magiclantern.forum.features.grading.viewmodel.GradingViewModel
import fm.magiclantern.forum.features.player.viewmodel.PlayerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationBar(
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel,
    gradingViewModel: GradingViewModel? = null
) {
    val isPlaying by viewModel.isPlaying.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val totalFrames by viewModel.totalFrames.collectAsState()
    val currentFrame by viewModel.currentFrame.collectAsState()

    // Cut In / Cut Out state from grading
    val grading by gradingViewModel?.currentGrading?.collectAsState()
        ?: remember { mutableStateOf(null) }
    val cutIn = grading?.cutIn ?: 1
    val cutOut = grading?.cutOut ?: 0

    // Effective cut values for display (resolve cutOut=0 to totalFrames)
    val effectiveCutOut = if (cutOut > 0) cutOut else totalFrames
    val hasCutMarkers = cutIn > 1 || (cutOut > 0 && cutOut < totalFrames)

    // Local state for the slider position to avoid sending too many updates
    var sliderPosition by remember(currentFrame) {
        mutableStateOf(currentFrame.toFloat())
    }

    val clipReady = viewModel.clipHandle.value != 0L && !isLoading

    Column(
        modifier = modifier
            .background(Color(0xFF686868))
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // Slider row with frame counter
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                // Custom trim region drawn behind the slider
                if (hasCutMarkers && totalFrames > 0) {
                    TrimRegionOverlay(
                        cutIn = cutIn,
                        effectiveCutOut = effectiveCutOut,
                        totalFrames = totalFrames,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp) // Match slider touch target height
                    )
                }
                Slider(
                    value = sliderPosition,
                    onValueChange = { sliderPosition = it },
                    onValueChangeFinished = { viewModel.setCurrentFrame(sliderPosition.toInt()) },
                    valueRange = 0f..((totalFrames - 1).toFloat().coerceAtLeast(0f)),
                    steps = (totalFrames - 2).coerceAtLeast(0),
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        activeTrackColor = if (hasCutMarkers) Color.Transparent else Color.White,
                        inactiveTrackColor = if (hasCutMarkers) Color.Transparent else Color.White,
                        thumbColor = Color.White,
                    )
                )
            }
            Text(
                text = "${if (totalFrames == 0) 0 else currentFrame + 1}/$totalFrames",
                color = Color.White,
                fontSize = 12.sp
            )
        }

        // Cut info display (only when markers are set)
        if (hasCutMarkers) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "In: $cutIn",
                    color = Color(0xFFAADDFF),
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "Out: $effectiveCutOut",
                    color = Color(0xFFFFAABB),
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "Duration: ${effectiveCutOut - cutIn + 1}",
                    color = Color.White,
                    fontSize = 11.sp
                )
            }
        }

        // Button row: Mark In, controls, Mark Out
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Mark In button
            IconButton(
                onClick = {
                    if (clipReady) {
                        gradingViewModel?.setCutIn(currentFrame + 1) // Convert 0-based to 1-based
                    }
                },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Login,
                    contentDescription = "Mark In",
                    tint = if (cutIn > 1) Color(0xFFAADDFF) else Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            // First Frame
            IconButton(onClick = { if (clipReady) viewModel.goToFirstFrame() }) {
                Icon(
                    imageVector = Icons.Default.FastRewind,
                    contentDescription = "First Frame",
                    tint = Color.White
                )
            }
            // Previous Frame
            IconButton(onClick = { if (clipReady) viewModel.previousFrame() }) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Previous Frame",
                    tint = Color.White
                )
            }
            // Play/Pause
            IconButton(onClick = { if (clipReady) viewModel.togglePlayback() }) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White
                )
            }
            // Next Frame
            IconButton(onClick = { if (clipReady) viewModel.nextFrame() }) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next Frame",
                    tint = Color.White
                )
            }
            // Last Frame
            IconButton(onClick = { if (clipReady) viewModel.goToLastFrame() }) {
                Icon(
                    imageVector = Icons.Default.FastForward,
                    contentDescription = "Last Frame",
                    tint = Color.White
                )
            }

            // Mark Out button
            IconButton(
                onClick = {
                    if (clipReady) {
                        gradingViewModel?.setCutOut(currentFrame + 1) // Convert 0-based to 1-based
                    }
                },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Logout,
                    contentDescription = "Mark Out",
                    tint = if (cutOut > 0 && cutOut < totalFrames) Color(0xFFFFAABB) else Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * Draws the trim region behind the slider.
 * The area between cutIn and cutOut is highlighted in white;
 * the areas outside are dimmed grey.
 */
@Composable
private fun TrimRegionOverlay(
    cutIn: Int,
    effectiveCutOut: Int,
    totalFrames: Int,
    modifier: Modifier = Modifier
) {
    val activeColor = Color.White
    val inactiveColor = Color(0xFF444444)
    val trackHeight = 4.dp

    Canvas(modifier = modifier) {
        val width = size.width
        val centerY = size.height / 2f
        val trackHeightPx = trackHeight.toPx()

        // Horizontal padding to match Material3 Slider internal padding (~16dp thumb radius)
        val horizontalPad = 10.dp.toPx()
        val trackWidth = width - 2 * horizontalPad

        // Calculate mark positions as fractions using (totalFrames - 1) as denominator
        // to align with the slider's value range of 0..(totalFrames-1)
        val maxIndex = (totalFrames - 1).coerceAtLeast(1)
        val inFraction = if (totalFrames > 0) ((cutIn - 1).toFloat() / maxIndex) else 0f
        val outFraction = if (totalFrames > 0) ((effectiveCutOut - 1).toFloat() / maxIndex) else 1f

        val inX = horizontalPad + trackWidth * inFraction
        val outX = horizontalPad + trackWidth * outFraction

        // Draw inactive region before cut in
        if (inX > horizontalPad) {
            drawLine(
                color = inactiveColor,
                start = Offset(horizontalPad, centerY),
                end = Offset(inX, centerY),
                strokeWidth = trackHeightPx
            )
        }

        // Draw active region between cut in and cut out
        drawLine(
            color = activeColor,
            start = Offset(inX, centerY),
            end = Offset(outX, centerY),
            strokeWidth = trackHeightPx
        )

        // Draw inactive region after cut out
        if (outX < horizontalPad + trackWidth) {
            drawLine(
                color = inactiveColor,
                start = Offset(outX, centerY),
                end = Offset(horizontalPad + trackWidth, centerY),
                strokeWidth = trackHeightPx
            )
        }
    }
}
