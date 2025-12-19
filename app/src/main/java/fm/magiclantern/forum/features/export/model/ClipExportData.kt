package fm.magiclantern.forum.features.export.model

import fm.magiclantern.forum.domain.model.DebayerAlgorithm
import fm.magiclantern.forum.domain.model.RawCorrectionSettings

/**
 * Data class for passing clip information to native batch export.
 * Contains all per-clip data needed for export while allowing
 * shared export options to be passed separately.
 */
data class ClipExportData(
    val fds: IntArray,                    // File descriptors for clip files
    val fileName: String,                 // Full file name (e.g., "clip.MLV")
    val baseName: String,                 // Base name without extension
    val clipUriPath: String,              // Output directory URI path
    val stretchFactorX: Float,            // Horizontal stretch factor
    val stretchFactorY: Float,            // Vertical stretch factor
    val debayerMode: Int = DebayerAlgorithm.AMAZE.nativeId,  // Per-clip debayer mode (native ID)
    val rawCorrection: RawCorrectionSettings = RawCorrectionSettings()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ClipExportData

        if (!fds.contentEquals(other.fds)) return false
        if (fileName != other.fileName) return false
        if (baseName != other.baseName) return false
        if (clipUriPath != other.clipUriPath) return false
        if (stretchFactorX != other.stretchFactorX) return false
        if (stretchFactorY != other.stretchFactorY) return false
        if (debayerMode != other.debayerMode) return false
        if (rawCorrection != other.rawCorrection) return false

        return true
    }

    override fun hashCode(): Int {
        var result = fds.contentHashCode()
        result = 31 * result + fileName.hashCode()
        result = 31 * result + baseName.hashCode()
        result = 31 * result + clipUriPath.hashCode()
        result = 31 * result + stretchFactorX.hashCode()
        result = 31 * result + stretchFactorY.hashCode()
        result = 31 * result + debayerMode.hashCode()
        result = 31 * result + rawCorrection.hashCode()
        return result
    }
}

