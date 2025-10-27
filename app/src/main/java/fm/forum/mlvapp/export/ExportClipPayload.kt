package fm.forum.mlvapp.export

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ExportClipPayload(
    val displayName: String,
    val primaryFileName: String,
    val uris: List<Uri>
) : Parcelable
