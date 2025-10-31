package fm.magiclantern.forum.videoPlayer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun NavigationBar(
    modifier: Modifier = Modifier,
    viewModel: VideoViewModel
) {
    val isPlaying by viewModel.isPlaying.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val totalFrames by viewModel.totalFrames.collectAsState()
    val currentFrame by viewModel.currentFrame.collectAsState()

    // Local state for the slider position to avoid sending too many updates
    var sliderPosition by remember(currentFrame) {
        mutableStateOf(currentFrame.toFloat())
    }

    Column(
        modifier = modifier
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Slider(
                value = sliderPosition,
                onValueChange = { sliderPosition = it },
                onValueChangeFinished = { viewModel.setCurrentFrame(sliderPosition.toInt()) },
                valueRange = 0f..(totalFrames.toFloat().takeIf { it > 0f } ?: 0f),
                steps = (totalFrames - 1).coerceAtLeast(0),
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    inactiveTrackColor = Color.White
                )
            )
            Text(text = "${if (totalFrames == 0) 0 else currentFrame + 1}/$totalFrames")
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { if (viewModel.clipHandle.value != 0L && !isLoading) viewModel.goToFirstFrame() }) {
                Icon(imageVector = Icons.Default.FastRewind, contentDescription = "First Frame")
            }
            IconButton(onClick = { if (viewModel.clipHandle.value != 0L && !isLoading) viewModel.previousFrame() }) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Previous Frame"
                )
            }
            IconButton(onClick = { if (viewModel.clipHandle.value != 0L && !isLoading) viewModel.togglePlayback() }) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play"
                )
            }
            IconButton(onClick = { if (viewModel.clipHandle.value != 0L && !isLoading) viewModel.nextFrame() }) {
                Icon(imageVector = Icons.Default.SkipNext, contentDescription = "Next Frame")
            }
            IconButton(onClick = { if (viewModel.clipHandle.value != 0L && !isLoading) viewModel.goToLastFrame() }) {
                Icon(imageVector = Icons.Default.FastForward, contentDescription = "Last Frame")
            }
        }
    }
}