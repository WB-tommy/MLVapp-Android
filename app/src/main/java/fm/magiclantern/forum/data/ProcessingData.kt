package fm.magiclantern.forum.data

import androidx.compose.runtime.Stable
@Stable
data class ProcessingData(
    val exposure: Float = 0.0f,
    val temperature: Int = 5600,
    val tint: Float = 0.0f,
    val contrast: Float = 0.0f,
    val highlights: Float = 0.0f,
    val shadows: Float = 0.0f,
    val saturation: Float = 0.0f,
    val gamma: Float = 1.0f,
    val whiteBalance: WhiteBalance = WhiteBalance(),
    val colorMatrix: FloatArray = floatArrayOf(
        1.0f, 0.0f, 0.0f,
        0.0f, 1.0f, 0.0f,
        0.0f, 0.0f, 1.0f
    ),
    val enableCA: Boolean = false,
    val caRed: Float = 0.0f,
    val caBlue: Float = 0.0f
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ProcessingData

        return exposure == other.exposure &&
                temperature == other.temperature &&
                tint == other.tint
    }

    override fun hashCode(): Int {
        var result = exposure.hashCode()
        result = 31 * result + temperature
        result = 31 * result + tint.hashCode()
        return result
    }
}

@Stable
data class WhiteBalance(
    val multiplierR: Float = 1.0f,
    val multiplierG: Float = 1.0f,
    val multiplierB: Float = 1.0f
)
