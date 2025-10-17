package fm.forum.mlvapp.data

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import fm.forum.mlvapp.FocusPixelManager
import fm.forum.mlvapp.MappStorage
import fm.forum.mlvapp.NativeInterface.NativeLib
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.text.RegexOption

class ClipRepository(
    context: Context,
    private val focusPixelManager: FocusPixelManager = FocusPixelManager
) {
    private val appContext = context.applicationContext
    private val contentResolver: ContentResolver = appContext.contentResolver

    suspend fun prepareClipChunk(
        uri: Uri,
        totalMemory: Long,
        cpuCores: Int
    ): ClipChunk? = withContext(Dispatchers.IO) {
        val fileName = resolveFileName(uri) ?: return@withContext null
        val extension = fileName.substringAfterLast('.', "")
        val isMlvChunk = extension.equals("MLV", ignoreCase = true) ||
            extension.matches(Regex("M[0-9]{2}", RegexOption.IGNORE_CASE))
        val isMcraw = extension.equals("mcraw", ignoreCase = true)
        if (!isMlvChunk && !isMcraw) return@withContext null

        val preview = runCatching {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val fd = pfd.detachFd()
                NativeLib.openClipForPreview(
                    fd,
                    fileName,
                    totalMemory,
                    cpuCores
                )
            }
        }.getOrNull() ?: return@withContext null

        ClipChunk(
            uri = uri,
            fileName = fileName,
            guid = preview.guid,
            width = preview.width,
            height = preview.height,
            thumbnail = preview.thumbnail.asImageBitmap()
        )
    }

    fun prepareClipPath(guid: Long, displayName: String): String =
        MappStorage.prepareClipPath(appContext, guid, displayName)

    suspend fun loadClip(
        clip: Clip,
        totalMemory: Long,
        cpuCores: Int
    ): ClipLoadResult = withContext(Dispatchers.IO) {
        // For multi-chunk files, we must sort the URIs to ensure the native layer
        // receives them in the correct order (.MLV, then .M00, .M01, etc.).
        // We achieve this by mapping the primary ".MLV" extension to "0" so it
        // always comes first in an alphanumeric sort.
        val sortedUris = clip.uris.zip(clip.fileNames).sortedWith(compareBy { (_, fileName) ->
            val extension = fileName.substringAfterLast('.', "")
            if (extension.equals("MLV", ignoreCase = true)) {
                "0"
            } else {
                extension
            }
        })

        val fileDescriptors = sortedUris.mapNotNull { (uri, _) ->
            runCatching {
                contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    pfd.detachFd()
                }
            }.getOrNull()
        }.toIntArray()

        if (fileDescriptors.isEmpty()) {
            return@withContext ClipLoadResult(clip, focusPixelRequirement = null)
        }

        val clipPath = clip.mappPath.ifEmpty {
            prepareClipPath(clip.guid, clip.displayName)
        }

        val metadata = NativeLib.openClip(
            fileDescriptors,
            clipPath,
            totalMemory,
            cpuCores
        )

        val nativeHandle = metadata.nativeHandle
        val requiredFocusPixelMap = buildFocusPixelRequirement(
            nativeHandle = nativeHandle,
            clipGuid = clip.guid
        )

        val audioBytesPerSample = NativeLib.getAudioBytesPerSample(nativeHandle)
        val audioBufferSize = NativeLib.getAudioBufferSize(nativeHandle)
        val timestamps = NativeLib.getVideoFrameTimestamps(nativeHandle) ?: LongArray(0)

        val aperture = if (metadata.apertureHundredths > 0) {
            String.format(
                Locale.US,
                "ƒ/%.1f",
                metadata.apertureHundredths / 100.0
            )
        } else {
            ""
        }

        val focalLength = if (metadata.focalLengthMm > 0) {
            String.format(Locale.US, "%d mm", metadata.focalLengthMm)
        } else {
            ""
        }

        val hasAudio = metadata.hasAudio &&
            metadata.audioChannels > 0 &&
            metadata.audioSampleRate > 0 &&
            audioBytesPerSample > 0 &&
            audioBufferSize > 0L

        val updatedClip = clip.copy(
            mappPath = clipPath,
            nativeHandle = nativeHandle,
            cameraName = metadata.cameraName,
            lens = metadata.lens,
            frames = metadata.frames,
            fps = metadata.fps,
            duration = formatDuration(metadata.frames, metadata.fps),
            focalLength = focalLength,
            shutter = formatShutter(metadata.shutterUs, metadata.fps),
            aperture = aperture,
            iso = metadata.iso,
            dualISO = metadata.dualIsoValid,
            bitDepth = metadata.losslessBpp,
            createdDate = String.format(
                Locale.US,
                "%04d-%02d-%02d %02d:%02d:%02d",
                metadata.year,
                metadata.month,
                metadata.day,
                metadata.hour,
                metadata.min,
                metadata.sec
            ),
            audioChannel = if (hasAudio) metadata.audioChannels else 0,
            audioSampleRate = if (hasAudio) metadata.audioSampleRate.toLong() else 0L,
            hasAudio = hasAudio,
            audioBytesPerSample = if (hasAudio) audioBytesPerSample else 0,
            audioBufferSize = if (hasAudio) audioBufferSize else 0L,
            frameTimestamps = timestamps,
            isMcraw = metadata.isMcraw
        )

        ClipLoadResult(
            clip = updatedClip,
            focusPixelRequirement = requiredFocusPixelMap
        )
    }

    fun ensureFocusPixelMap(fileName: String): Boolean =
        focusPixelManager.ensureFocusPixelMap(appContext, fileName)

    suspend fun downloadFocusPixelMap(fileName: String): Boolean =
        focusPixelManager.downloadFocusPixelMap(appContext, fileName)

    suspend fun downloadFocusPixelMapsForCamera(
        cameraId: String
    ): FocusPixelManager.DownloadAllResult =
        focusPixelManager.downloadFocusPixelMapsForCamera(appContext, cameraId)

    fun refreshFocusPixelMap(handle: Long) {
        NativeLib.refreshFocusPixelMap(handle)
    }

    private fun buildFocusPixelRequirement(
        nativeHandle: Long,
        clipGuid: Long
    ): FocusPixelRequirement? {
        if (nativeHandle == 0L) return null
        val focusMode = NativeLib.checkCameraModel(nativeHandle)
        if (focusMode == 0) return null
        NativeLib.setFixRawMode(nativeHandle, true)
        NativeLib.setFocusPixelMode(nativeHandle, focusMode)
        val requiredMap = NativeLib.getFpmName(nativeHandle)
        if (requiredMap.isBlank()) {
            return null
        }
        val resolved = focusPixelManager.ensureFocusPixelMap(appContext, requiredMap)
        return if (resolved) {
            null
        } else {
            FocusPixelRequirement(
                clipGuid = clipGuid,
                requiredFile = requiredMap
            )
        }
    }

    private fun resolveFileName(uri: Uri): String? {
        var result: String? = uri.lastPathSegment
        query(uri) { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    result = cursor.getString(nameIndex)
                }
            }
        }
        return result
    }

    private inline fun query(uri: Uri, block: (Cursor) -> Unit) {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            block(cursor)
        }
    }
}

data class ClipChunk(
    val uri: Uri,
    val fileName: String,
    val guid: Long,
    val width: Int,
    val height: Int,
    val thumbnail: ImageBitmap
)

data class ClipLoadResult(
    val clip: Clip,
    val focusPixelRequirement: FocusPixelRequirement?
)

data class FocusPixelRequirement(
    val clipGuid: Long,
    val requiredFile: String
)
