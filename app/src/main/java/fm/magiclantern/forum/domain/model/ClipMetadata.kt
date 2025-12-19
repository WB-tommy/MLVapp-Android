package fm.magiclantern.forum.domain.model

import androidx.compose.runtime.Stable

/**
 * Full clip metadata, loaded when clip is selected for playback/grading.
 * Contains all information from the MLV file header.
 */
@Stable
data class ClipMetadata(
    // Camera info
    val cameraName: String = "",
    val lens: String = "",
    val cameraModelId: Int = 0,
    
    // Video specs
    val frames: Int = 0,
    val fps: Float = 0f,
    val duration: String = "",
    val bitDepth: Int = 14,
    
    // Exposure
    val iso: Int = 0,
    val dualISO: Boolean = false,
    val shutterUs: Int = 0,
    val shutter: String = "",
    val aperture: String = "",
    val focalLength: String = "",
    
    // Timestamps
    val createdDate: String = "",
    val frameTimestamps: LongArray = LongArray(0),
    
    // Audio
    val hasAudio: Boolean = false,
    val audioChannels: Int = 0,
    val audioSampleRate: Int = 0,
    val audioBytesPerSample: Int = 0,
    val audioBufferSize: Long = 0L,
    
    // RAW levels (from file, not user-adjusted)
    val originalBlackLevel: Int = 0,
    val originalWhiteLevel: Int = 1,
    
    // Processing
    val isMcraw: Boolean = false,
    val focusPixelMapName: String = ""
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ClipMetadata
        return cameraName == other.cameraName &&
               frames == other.frames &&
               fps == other.fps
    }

    override fun hashCode(): Int = cameraName.hashCode() + frames
}
