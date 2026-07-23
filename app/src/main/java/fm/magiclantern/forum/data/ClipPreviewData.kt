package fm.magiclantern.forum.data

import android.graphics.Bitmap

data class ClipPreviewData(
    val width: Int,
    val height: Int,
    val thumbnail: Bitmap,
    val guid: Long,
    val stretchFactorX: Float = 1.0f,
    val stretchFactorY: Float = 1.0f,
    val cameraModelId: Int = 0,
    val focusPixelMapName: String = "",
    val dualIsoValid: Boolean = false,
    val dualIsoAutoEnabled: Boolean = false,
    val originalBlackLevel: Int = 4096,
    val originalWhiteLevel: Int = 65013,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ClipPreviewData

        return guid == other.guid
    }

    override fun hashCode(): Int {
        return guid.hashCode()
    }
}
