package fm.magiclantern.forum.domain.model

import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.ImageBitmap

/**
 * Full clip data for the active/loaded clip.
 * Combines preview info with loaded metadata and native handle.
 * Contains everything needed for playback, grading, and export.
 */
@Stable
data class ClipDetails(
    val preview: ClipPreview,
    val metadata: ClipMetadata,
    val nativeHandle: Long,
    val processing: ClipProcessingData = ClipProcessingData(),
    val grading: ClipGradingData = ClipGradingData()
) {
    // Convenience accessors from preview
    val guid: Long get() = preview.guid
    val displayName: String get() = preview.displayName
    val uris: List<Uri> get() = preview.uris
    val fileNames: List<String> get() = preview.fileNames
    val thumbnail: ImageBitmap get() = preview.thumbnail
    val width: Int get() = preview.width
    val height: Int get() = preview.height
    val stretchFactorX: Float get() = preview.stretchFactorX
    val stretchFactorY: Float get() = preview.stretchFactorY
    val cameraModelId: Int get() = preview.cameraModelId
    val focusPixelMapName: String get() = preview.focusPixelMapName
    val isMcraw: Boolean get() = preview.isMcraw
    
    // Convenience accessors from metadata
    val cameraName: String get() = metadata.cameraName
    val lens: String get() = metadata.lens
    val frames: Int get() = metadata.frames
    val fps: Float get() = metadata.fps
    val duration: String get() = metadata.duration
    val bitDepth: Int get() = metadata.bitDepth
    val iso: Int get() = metadata.iso
    val dualISO: Boolean get() = metadata.dualISO
    val shutter: String get() = metadata.shutter
    val aperture: String get() = metadata.aperture
    val focalLength: String get() = metadata.focalLength
    val createdDate: String get() = metadata.createdDate
    val frameTimestamps: LongArray get() = metadata.frameTimestamps
    val hasAudio: Boolean get() = metadata.hasAudio
    val audioChannels: Int get() = metadata.audioChannels
    val audioSampleRate: Int get() = metadata.audioSampleRate
    val audioBytesPerSample: Int get() = metadata.audioBytesPerSample
    val audioBufferSize: Long get() = metadata.audioBufferSize
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ClipDetails
        return preview.guid == other.preview.guid
    }

    override fun hashCode(): Int = preview.guid.hashCode()
}

/**
 * Processing data for clip (stretch factors, etc.)
 */
@Stable
data class ClipProcessingData(
    val stretchFactorX: Float = 1.0f,
    val stretchFactorY: Float = 1.0f
)
