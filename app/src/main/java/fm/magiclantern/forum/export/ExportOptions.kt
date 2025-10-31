package fm.magiclantern.forum.export

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Bundle of export-specific options passed directly to the native layer.
 * Keep fields aligned with export_options_t in the JNI bridge.
 */
@Parcelize
data class ExportOptions(
    val codec: ExportCodec,
    val codecOption: Int,
    val cdngVariant: CdngVariant,
    val cdngNaming: CdngNaming,
    val includeAudio: Boolean,
    val enableRawFixes: Boolean,
    val frameRateOverrideEnabled: Boolean,
    val frameRateValue: Float,
    val sourceFileName: String,
    val clipUriPath: String,
    val audioTempDir: String,
    val stretchFactorX: Float = 1.0f,
    val stretchFactorY: Float = 1.0f
) : Parcelable
