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
    var mappPath: String = "",

    // Metadata (mirrors desktop MainWindow.cpp updateMetadata inputs)
    var cameraName: String? = "",
    var lens: String? = "",
    var duration: String? = "",
    var frames: Int? = 0,
    var fps: Float? = 0f,
    var focalLength: String? = "",
    var shutter: String? = "",
    var aperture: String? = "",
    var iso: Int? = 0,
    var dualISO: Boolean? = false,
    var bitDepth: Int? = 0,
    var createdDate: String? = "",
    var audioChannel: Int? = 0,
    var audioSampleRate: Long? = 0L,
    var size: Long? = 0L,
    var dataRate: Long? = 0L,
    var hasAudio: Boolean = false,
    var audioBytesPerSample: Int = 0,
    var audioBufferSize: Long = 0L,
    var frameTimestamps: LongArray = LongArray(0),
    var isMcraw: Boolean = false,
    var nativeHandle: Long = 0L,
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
