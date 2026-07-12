package fm.magiclantern.forum.features.export

import android.content.ContentResolver
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import java.util.Collections

class ExportFdProvider(
    private val contentResolver: ContentResolver,
    private val outputDirectory: DocumentFile
) {
    private val createdDocuments = Collections.synchronizedList(mutableListOf<DocumentFile>())

    fun openFrameFd(frameIndex: Int, relativeName: String): Int =
        createFile(relativeName, "image/x-adobe-dng")

    fun openContainerFd(relativeName: String): Int =
        createFile(relativeName, "application/octet-stream")

    fun openAudioFd(relativeName: String): Int =
        createFile(relativeName, "audio/wav")

    private fun createFile(relativeName: String, fallbackMime: String): Int {
        val resolvedMime = when (relativeName.substringAfterLast('.', "").lowercase()) {
            "dng" -> "image/x-adobe-dng"
            "tif", "tiff" -> "image/tiff"
            "png" -> "image/png"
            "jp2", "j2k", "j2c" -> "image/jp2"
            "jpg", "jpeg" -> "image/jpeg"
            "mov" -> "video/quicktime"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "wav" -> "audio/wav"
            else -> fallbackMime
        }
        val target = outputDirectory.createFile(resolvedMime, relativeName)
            ?: throw IllegalStateException("Failed to create SAF document for $relativeName")
        createdDocuments += target
        val pfd: ParcelFileDescriptor = contentResolver.openFileDescriptor(target.uri, "w")
            ?: throw IllegalStateException("Unable to open descriptor for ${target.uri}")
        // detachFd transfers ownership of the raw fd to native export.
        return pfd.detachFd()
    }

    fun deleteCreatedDocuments(): Int {
        val snapshot = synchronized(createdDocuments) {
            val copy = createdDocuments.toList()
            createdDocuments.clear()
            copy
        }
        return snapshot.count { document ->
            runCatching { document.delete() }.getOrDefault(false)
        }
    }

    fun hasCreatedDocuments(): Boolean = synchronized(createdDocuments) {
        createdDocuments.isNotEmpty()
    }
}
