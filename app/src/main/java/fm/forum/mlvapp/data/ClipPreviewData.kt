package fm.forum.mlvapp.data

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
