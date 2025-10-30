package fm.forum.mlvapp.data

import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.ImageBitmap

@Stable
data class Clip(
    val uris: List<Uri>,
    val fileNames: List<String>,
    val displayName: String,
    val width: Int,
    val height: Int,
    val thumbnail: ImageBitmap,
    val guid: Long = 0L,
    val mappPath: String = "",
    val processing: ClipProcessingData = ClipProcessingData(),

    // Metadata (mirrors desktop MainWindow.cpp updateMetadata inputs)
    val cameraName: String? = "",
    val lens: String? = "",
    val duration: String? = "",
    val frames: Int? = 0,
    val fps: Float? = 0f,
    val focalLength: String? = "",
    val shutter: String? = "",
    val aperture: String? = "",
    val iso: Int? = 0,
    val dualISO: Boolean? = false,
    val bitDepth: Int? = 0,
    val createdDate: String? = "",
    val audioChannel: Int? = 0,
    val audioSampleRate: Long? = 0L,
    val size: Long? = 0L,
    val dataRate: Long? = 0L,
    val hasAudio: Boolean = false,
    val audioBytesPerSample: Int = 0,
    val audioBufferSize: Long = 0L,
    val frameTimestamps: LongArray = LongArray(0),
    val isMcraw: Boolean = false,
    val nativeHandle: Long = 0L,
    val cameraModelId: Int = 0,
    val focusPixelMapName: String = "",
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Clip

        return guid == other.guid
    }

    override fun hashCode(): Int {
        return guid.hashCode()
    }
}

sealed interface PlayerState {
    object Idle : PlayerState
    data class Loading(val clip: Clip) : PlayerState
    data class Ready(val clip: Clip, val isPlaying: Boolean = false) : PlayerState
    data class Error(val clip: Clip, val message: String) : PlayerState
}
