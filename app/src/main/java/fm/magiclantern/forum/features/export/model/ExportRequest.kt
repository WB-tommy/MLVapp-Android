package fm.magiclantern.forum.features.export.model

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ExportRequest(
    val clips: List<ExportClipPayload>,
    val settings: ExportSettings,
    val outputDirectory: Uri,
    val cacheSizeMiB: Long,
    val cpuCores: Int
) : Parcelable
