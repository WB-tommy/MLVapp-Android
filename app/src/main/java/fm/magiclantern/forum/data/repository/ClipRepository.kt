package fm.magiclantern.forum.data.repository

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import fm.magiclantern.forum.FocusPixelManager
import fm.magiclantern.forum.domain.model.ClipPreview
import fm.magiclantern.forum.nativeInterface.NativeLib
import fm.magiclantern.forum.utils.formatDuration
import fm.magiclantern.forum.utils.formatShutter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing clip data and native operations.
 *
 * This is a singleton managed by Hilt to ensure consistent state across the app.
 */
@Singleton
class ClipRepository @Inject constructor(
    @ApplicationContext private val appContext: Context
) {
    private val contentResolver: ContentResolver = appContext.contentResolver
    private val focusPixelManager: FocusPixelManager = FocusPixelManager

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
            thumbnail = preview.thumbnail.asImageBitmap(),
            stretchFactorX = preview.stretchFactorX,
            stretchFactorY = preview.stretchFactorY,
            cameraModelId = preview.cameraModelId,
            focusPixelMapName = preview.focusPixelMapName,
            isMcraw = isMcraw
        )
    }

    /**
     * Load clip from a ClipPreview and return full ClipDetails with domain models.
     * This is the preferred method for new code - returns domain types directly.
     */
    suspend fun loadClipAsDetails(
        preview: ClipPreview,
        totalMemory: Long,
        cpuCores: Int
    ): ClipDetailsLoadResult = withContext(Dispatchers.IO) {
        val sortedUrisAndNames =
            preview.uris.zip(preview.fileNames).sortedWith(compareBy { (_, fileName) ->
                val extension = fileName.substringAfterLast('.', "")
                if (extension.equals("MLV", ignoreCase = true)) "0" else extension
            })

        val fileDescriptors = sortedUrisAndNames.mapNotNull { (uri, _) ->
            runCatching {
                contentResolver.openFileDescriptor(uri, "r")?.use { pfd -> pfd.detachFd() }
            }.getOrNull()
        }.toIntArray()

        if (fileDescriptors.isEmpty()) {
            return@withContext ClipDetailsLoadResult(null, null)
        }

        val primaryFileName = sortedUrisAndNames.firstOrNull()?.second ?: preview.displayName

        val nativeMetadata = NativeLib.openClip(
            fileDescriptors,
            primaryFileName,
            totalMemory,
            cpuCores
        )

        val nativeHandle = nativeMetadata.nativeHandle
        val requiredFocusPixelMap = buildFocusPixelRequirementForPreview(
            nativeHandle = nativeHandle,
            clipGuid = preview.guid,
            isMcraw = preview.isMcraw
        )

        val audioBytesPerSample = NativeLib.getAudioBytesPerSample(nativeHandle)
        val audioBufferSize = NativeLib.getAudioBufferSize(nativeHandle)
        val timestamps = NativeLib.getVideoFrameTimestamps(nativeHandle) ?: LongArray(0)

        val aperture = if (nativeMetadata.apertureHundredths > 0) {
            String.format(Locale.US, "ƒ/%.1f", nativeMetadata.apertureHundredths / 100.0)
        } else ""

        val focalLength = if (nativeMetadata.focalLengthMm > 0) {
            String.format(Locale.US, "%d mm", nativeMetadata.focalLengthMm)
        } else ""

        val hasAudio = nativeMetadata.hasAudio &&
                nativeMetadata.audioChannels > 0 &&
                nativeMetadata.audioSampleRate > 0 &&
                audioBytesPerSample > 0 &&
                audioBufferSize > 0L

        val focusPixelMapName = NativeLib.getFpmName(nativeHandle)
        val derivedCameraModelId = preview.cameraModelId.takeIf { it != 0 }
            ?: focusPixelMapName.substringBefore('_').toIntOrNull(16) ?: 0

        // Build domain ClipMetadata
        val metadata = fm.magiclantern.forum.domain.model.ClipMetadata(
            cameraName = nativeMetadata.cameraName,
            lens = nativeMetadata.lens,
            cameraModelId = derivedCameraModelId,
            frames = nativeMetadata.frames,
            fps = nativeMetadata.fps,
            duration = formatDuration(nativeMetadata.frames, nativeMetadata.fps),
            bitDepth = nativeMetadata.losslessBpp,
            iso = nativeMetadata.iso,
            dualISO = nativeMetadata.dualIsoValid,
            shutterUs = nativeMetadata.shutterUs,
            shutter = formatShutter(nativeMetadata.shutterUs, nativeMetadata.fps),
            aperture = aperture,
            focalLength = focalLength,
            createdDate = String.format(
                Locale.US, "%04d-%02d-%02d %02d:%02d:%02d",
                nativeMetadata.year, nativeMetadata.month, nativeMetadata.day,
                nativeMetadata.hour, nativeMetadata.min, nativeMetadata.sec
            ),
            frameTimestamps = timestamps,
            hasAudio = hasAudio,
            audioChannels = if (hasAudio) nativeMetadata.audioChannels else 0,
            audioSampleRate = if (hasAudio) nativeMetadata.audioSampleRate else 0,
            audioBytesPerSample = if (hasAudio) audioBytesPerSample else 0,
            audioBufferSize = if (hasAudio) audioBufferSize else 0L,
            originalBlackLevel = nativeMetadata.originalBlackLevel,
            originalWhiteLevel = nativeMetadata.originalWhiteLevel,
            isMcraw = nativeMetadata.isMcraw,
            focusPixelMapName = focusPixelMapName
        )

        // Update preview with loaded data
        val updatedPreview = preview.copy(
            cameraModelId = derivedCameraModelId,
            focusPixelMapName = focusPixelMapName
        )

        val clipDetails = fm.magiclantern.forum.domain.model.ClipDetails(
            preview = updatedPreview,
            metadata = metadata,
            nativeHandle = nativeHandle,
            processing = fm.magiclantern.forum.domain.model.ClipProcessingData(
                stretchFactorX = preview.stretchFactorX,
                stretchFactorY = preview.stretchFactorY
            )
        )

        ClipDetailsLoadResult(
            details = clipDetails,
            focusPixelRequirement = requiredFocusPixelMap
        )
    }

    private fun buildFocusPixelRequirementForPreview(
        nativeHandle: Long,
        clipGuid: Long,
        isMcraw: Boolean
    ): FocusPixelRequirement? {
        if (isMcraw || nativeHandle == 0L) return null
        val focusMode = NativeLib.checkCameraModel(nativeHandle)
        if (focusMode == 0) return null
        NativeLib.setFixRawMode(nativeHandle, true)
        NativeLib.setFocusPixelMode(nativeHandle, focusMode)
        val requiredMap = NativeLib.getFpmName(nativeHandle)
        if (requiredMap.isBlank()) return null
        val resolved = focusPixelManager.ensureFocusPixelMap(appContext, requiredMap)
        return if (resolved) null else FocusPixelRequirement(clipGuid, requiredMap)
    }

    fun ensureFocusPixelMap(fileName: String): Boolean =
        focusPixelManager.ensureFocusPixelMap(appContext, fileName)

    suspend fun downloadFocusPixelMap(fileName: String): Boolean =
        focusPixelManager.downloadFocusPixelMap(appContext, fileName)

    suspend fun downloadFocusPixelMapsForCamera(
        cameraId: String
    ): FocusPixelManager.DownloadAllResult =
        focusPixelManager.downloadFocusPixelMapsForCamera(appContext, cameraId)

    fun focusPixelExists(mapName: String): Boolean {
        return focusPixelManager.ensureFocusPixelMap(appContext, mapName)
    }

    fun refreshFocusPixelMap(handle: Long) {
        NativeLib.refreshFocusPixelMap(handle)
    }

    fun refreshFocusPixel(handle: Long) {
        refreshFocusPixelMap(handle)
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
    val thumbnail: ImageBitmap,
    val stretchFactorX: Float = 1.0f,
    val stretchFactorY: Float = 1.0f,
    val cameraModelId: Int = 0,
    val focusPixelMapName: String = "",
    val isMcraw: Boolean = false
)

data class FocusPixelRequirement(
    val clipGuid: Long,
    val requiredFile: String
)

/**
 * Result of loading a clip as ClipDetails (domain model).
 */
data class ClipDetailsLoadResult(
    val details: fm.magiclantern.forum.domain.model.ClipDetails?,
    val focusPixelRequirement: FocusPixelRequirement?
)
