package fm.magiclantern.forum.features.export.model

import android.net.Uri
import android.os.Parcelable
import fm.magiclantern.forum.domain.model.DebayerAlgorithm
import fm.magiclantern.forum.domain.model.RawCorrectionSettings
import kotlinx.parcelize.Parcelize

@Parcelize
data class ExportClipPayload(
    val displayName: String,
    val primaryFileName: String,
    val uris: List<Uri>,
    val stretchFactorX: Float = 1.0f,
    val stretchFactorY: Float = 1.0f,
    // Per-clip debayer algorithm (from grading screen / receipt)
    val debayerMode: DebayerAlgorithm = DebayerAlgorithm.AMAZE,
    // Raw correction settings (per-clip grading)
    val rawCorrection: RawCorrectionSettings = RawCorrectionSettings()
) : Parcelable



