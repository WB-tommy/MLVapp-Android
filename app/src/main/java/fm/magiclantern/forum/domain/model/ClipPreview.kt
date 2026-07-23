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
    val isMcraw: Boolean = false,
    /** DISO block is valid, including the equal-ISO metadata edge case. */
    val dualIsoValid: Boolean = false,
    /** Desktop auto-On condition: valid DISO metadata with two distinct ISOs. */
    val dualIsoAutoEnabled: Boolean = false,
    val originalBlackLevel: Int = 4096,
    val originalWhiteLevel: Int = 65013
)
