package fm.forum.mlvapp.videoPlayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
fun NavigationBar(
    modifier: Modifier = Modifier,
    viewModel: VideoViewModel
) {
    val isPlaying by viewModel.isPlaying.collectAsState()
    val totalFrames = viewModel.totalFrames
//    val currentFrame = viewModel.currentFrame
//    var curFrame by remember { mutableStateOf(currentFrame.toFloat()) }

    Column(
        modifier = modifier,
//        horizontalArrangement = Arrangement.SpaceEvenly,
//        verticalAlignment = Alignment.CenterVertically
    ) {
//        Row(
//            modifier = modifier,
//            horizontalArrangement = Arrangement.SpaceEvenly,
//            verticalAlignment = Alignment.CenterVertically
//        ) {
////            Slider(
////                value = sliderValue,
////                onValueChange = {
////                    sliderValue = it
////                    onFrameChanged(it.toInt()) // notify caller
////                },
////                valueRange = 0f..totalFrames.toFloat(),
////                steps = totalFrames - 1, // makes it step by 1
////                modifier = Modifier.fillMaxWidth()
////            )
//            Text(text = 1.toString() + "/" + totalFrames.toString())
////            Text(text = curFrame.toString() + "/" + totalFrames.toString())
//        }
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { viewModel.goToFirstFrame() }) {
                Icon(imageVector = Icons.Default.FastRewind, contentDescription = "First Frame")
            }
            IconButton(onClick = { viewModel.previousFrame() }) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Previous Frame"
                )
            }
            IconButton(onClick = { viewModel.togglePlayback() }) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Stop" else "Play"
                    // "Play/Pause"
                )
            }
            IconButton(onClick = { viewModel.nextFrame() }) {
                Icon(imageVector = Icons.Default.SkipNext, contentDescription = "Next Frame")
            }
            IconButton(onClick = { viewModel.goToLastFrame() }) {
                Icon(imageVector = Icons.Default.FastForward, contentDescription = "Last Frame")
            }
        }
    }
}
