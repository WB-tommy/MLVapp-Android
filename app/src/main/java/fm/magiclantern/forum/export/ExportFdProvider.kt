package fm.magiclantern.forum.export

import android.content.ContentResolver
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile

class ExportFdProvider(
    private val contentResolver: ContentResolver,
    private val outputDirectory: DocumentFile
) {

    fun openFrameFd(frameIndex: Int, relativeName: String): Int =
        createFile(relativeName, "image/x-adobe-dng")

    fun openContainerFd(relativeName: String): Int =
        createFile(relativeName, "application/octet-stream")

    fun openAudioFd(relativeName: String): Int =
        createFile(relativeName, "audio/wav")

    private fun createFile(relativeName: String, mimeType: String): Int {
        val target = outputDirectory.createFile(mimeType, relativeName)
            ?: throw IllegalStateException("Failed to create SAF document for $relativeName")
        val pfd: ParcelFileDescriptor = contentResolver.openFileDescriptor(target.uri, "w")
            ?: throw IllegalStateException("Unable to open descriptor for ${target.uri}")
        return pfd.detachFd()
    }
}
