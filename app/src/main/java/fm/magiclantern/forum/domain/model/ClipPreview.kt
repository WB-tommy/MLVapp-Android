package fm.magiclantern.forum.domain.model

import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.ImageBitmap

/**
 * Lightweight clip data for the clip list.
 * Contains only what's needed to display in the list before full loading.
 */
@Stable
data class ClipPreview(
    val guid: Long,
    val displayName: String,
    val uris: List<Uri>,
    val fileNames: List<String>,
    val thumbnail: ImageBitmap,
    val width: Int,
    val height: Int,
    val stretchFactorX: Float = 1.0f,
    val stretchFactorY: Float = 1.0f,
    val cameraModelId: Int = 0,
    val focusPixelMapName: String = "",
    val isMcraw: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ClipPreview
        return guid == other.guid
    }

    override fun hashCode(): Int = guid.hashCode()
}
