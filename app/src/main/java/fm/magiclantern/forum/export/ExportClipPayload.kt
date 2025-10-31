package fm.magiclantern.forum.export

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ExportClipPayload(
    val displayName: String,
    val primaryFileName: String,
    val uris: List<Uri>,
    val stretchFactorX: Float = 1.0f,
    val stretchFactorY: Float = 1.0f
) : Parcelable
