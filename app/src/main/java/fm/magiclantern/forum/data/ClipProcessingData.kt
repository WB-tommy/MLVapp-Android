package fm.magiclantern.forum.data

import androidx.compose.runtime.Stable

@Stable
data class ClipProcessingData(
    val stretchFactorX: Float = 1.0f,
    val stretchFactorY: Float = 1.0f
)
