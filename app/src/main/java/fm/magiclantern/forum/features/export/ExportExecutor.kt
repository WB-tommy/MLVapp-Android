package fm.magiclantern.forum.features.export

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import fm.magiclantern.forum.domain.model.ColorGradingSettings
import fm.magiclantern.forum.domain.model.DebayerAlgorithm
import fm.magiclantern.forum.domain.model.RawCorrectionSettings
import fm.magiclantern.forum.features.export.model.CdngNaming
import fm.magiclantern.forum.features.export.model.ExportClipPayload
import fm.magiclantern.forum.features.export.model.ExportCodec
import fm.magiclantern.forum.features.export.model.ExportOptions
import fm.magiclantern.forum.features.export.model.ExportSettings
import fm.magiclantern.forum.features.export.model.ProgressListener
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.Collections
import kotlin.coroutines.coroutineContext

class ExportExecutor(
    private val context: Context,
    private val contentResolver: ContentResolver,
    private val filesDir: File,
    private val cacheDir: File,
    private val cacheSizeMiB: Long,
    private val cpuCores: Int,
    private val exportSettings: ExportSettings,
    private val nativeExportEngine: NativeExportEngine = NativeLibExportEngine,
    private val callbacks: Callbacks
) {
    private val tempAudioArtifacts = Collections.synchronizedList(mutableListOf<File>())

    interface Callbacks {
        fun onClipStarted(index: Int, totalClips: Int, clipName: String)
        fun onClipProgress(
            index: Int,
            totalClips: Int,
            clipName: String,
            clipProgress: Int,
            overallProgress: Float
        )

        fun onClipCompleted(
            index: Int,
            totalClips: Int,
            clipName: String,
            overallProgress: Float
        )
    }

    suspend fun run(clips: List<ExportClipPayload>, outputDirectoryUri: Uri) {
        val outputDir = DocumentFile.fromTreeUri(context, outputDirectoryUri)
            ?: throw IllegalStateException("Output directory is unavailable")

        if (!outputDir.exists()) {
            throw IllegalStateException("Output directory does not exist")
        }

        val total = clips.size.coerceAtLeast(1)
        try {
            clips.forEachIndexed { index, clip ->
                coroutineContext.ensureActive()
                val clipName = clip.displayName.ifBlank {
                    clip.uris.firstOrNull()?.let { resolveClipName(it) } ?: "Clip ${index + 1}"
                }
                callbacks.onClipStarted(index, total, clipName)
                exportClip(clip, outputDir, index, total, clipName)
                coroutineContext.ensureActive()
                callbacks.onClipCompleted(
                    index = index,
                    totalClips = total,
                    clipName = clipName,
                    overallProgress = (index + 1f) / total
                )
            }
        } finally {
            cleanupTempAudioArtifacts()
        }
    }

    fun cancel() {
        nativeExportEngine.cancel()
        cleanupTempAudioArtifacts()
    }

    fun cleanup() {
        cleanupTempAudioArtifacts()
    }

    private suspend fun exportClip(
        clip: ExportClipPayload,
        outputDir: DocumentFile,
        index: Int,
        totalClips: Int,
        clipName: String
    ) {
        coroutineContext.ensureActive()
        nativeExportEngine.prepare()
        // If cancellation raced with prepare(), stop before creating any output.
        coroutineContext.ensureActive()
        cleanupTempAudioForClip(clipName, clip.primaryFileName)

        if (exportSettings.codec == ExportCodec.AUDIO_ONLY) {
            exportAudioOnly(clip, outputDir, index, totalClips, clipName)
            return
        }

        val needsSubdirectory = exportSettings.codec in listOf(
            ExportCodec.CINEMA_DNG,
            ExportCodec.TIFF,
            ExportCodec.PNG,
            ExportCodec.JPEG2000
        )

        val outputTarget = if (needsSubdirectory) {
            createClipOutputTarget(outputDir, clipName, exportSettings)
        } else {
            ClipOutputTarget(directory = outputDir, createdDirectory = false)
        }
        val clipOutputDir = outputTarget.directory

        val sourceFileName = clip.primaryFileName
        val baseName = sourceFileName.substringBeforeLast('.', sourceFileName)
        val exportOptions = exportSettings.toExportOptions(
            sourceFileName = sourceFileName,
            clipUriPath = clipOutputDir.uri.toString(),
            audioTempDir = filesDir.absolutePath,
            stretchFactorX = clip.stretchFactorX,
            stretchFactorY = clip.stretchFactorY,
            clipDebayerMode = clip.debayerMode.nativeId,
            rawCorrection = clip.rawCorrection,
            colorGrading = clip.colorGrading,
            cutIn = clip.cutIn,
            cutOut = clip.cutOut
        )

        val provider = ExportFdProvider(contentResolver, clipOutputDir)

        try {
            val clipFds = openClipDescriptors(clip)
            nativeExportEngine.export(
                memSize = cacheSizeMiB,
                cpuCores = cpuCores,
                clipFds = clipFds,
                options = exportOptions,
                progressListener = ProgressListener { progress ->
                    reportProgress(index, totalClips, clipName, progress)
                },
                fileProvider = provider
            )
            coroutineContext.ensureActive()
            if (!provider.hasCreatedDocuments()) {
                throw IllegalStateException("Export produced no output for $clipName")
            }
        } catch (throwable: Throwable) {
            cleanupPartialOutput(clipName, provider, outputTarget)
            cleanupTempAudioForClip(clipName, clip.primaryFileName)
            throwable.exportCancellationCause()?.let { throw it }
            throw IllegalStateException(
                "Failed to export ${exportSettings.codec.displayName} for $clipName: " +
                    throwable.exportFailureMessage(default = throwable::class.java.simpleName),
                throwable
            )
        }

        try {
            handleAudioArtifact(baseName, clipOutputDir)
            coroutineContext.ensureActive()
        } catch (throwable: Throwable) {
            throwable.exportCancellationCause()?.let { cancellation ->
                cleanupPartialOutput(clipName, provider, outputTarget)
                cleanupTempAudioForClip(clipName, clip.primaryFileName)
                throw cancellation
            }
            cleanupPartialOutput(clipName, provider, outputTarget)
            cleanupTempAudioForClip(clipName, clip.primaryFileName)
            throw IllegalStateException(
                "Exported ${exportSettings.codec.displayName} for $clipName, " +
                    "but failed to move audio: " +
                    throwable.exportFailureMessage(default = throwable::class.java.simpleName),
                throwable
            )
        }
    }

    private suspend fun exportAudioOnly(
        clip: ExportClipPayload,
        outputDir: DocumentFile,
        index: Int,
        totalClips: Int,
        clipName: String
    ) {
        val tempDir = filesDir.absolutePath
        val exportOptions = exportSettings.toExportOptions(
            sourceFileName = clip.primaryFileName,
            clipUriPath = "",
            audioTempDir = tempDir,
            stretchFactorX = clip.stretchFactorX,
            stretchFactorY = clip.stretchFactorY
        )

        var createdOutput: DocumentFile? = null
        try {
            val clipFds = openClipDescriptors(clip)
            nativeExportEngine.export(
                memSize = cacheSizeMiB,
                cpuCores = cpuCores,
                clipFds = clipFds,
                options = exportOptions,
                progressListener = ProgressListener { progress ->
                    reportProgress(index, totalClips, clipName, progress)
                },
                fileProvider = null
            )
            coroutineContext.ensureActive()
            createdOutput = moveTempAudioToOutput(tempDir, clipName, outputDir)
            coroutineContext.ensureActive()
        } catch (throwable: Throwable) {
            createdOutput?.let(::deleteDocumentQuietly)
            cleanupTempAudioForClip(clipName, clip.primaryFileName)
            throwable.exportCancellationCause()?.let { throw it }
            throw IllegalStateException(
                "Failed to export audio for $clipName: " +
                    throwable.exportFailureMessage(default = throwable::class.java.simpleName),
                throwable
            )
        }
    }

    private fun reportProgress(
        index: Int,
        totalClips: Int,
        clipName: String,
        progress: Int
    ) {
        val bounded = progress.coerceIn(0, 100)
        val perClipFraction = bounded / 100f
        callbacks.onClipProgress(
            index = index,
            totalClips = totalClips,
            clipName = clipName,
            clipProgress = bounded,
            overallProgress = (index + perClipFraction) / totalClips.coerceAtLeast(1)
        )
    }

    private fun resolveClipName(uri: Uri): String? =
        DocumentFile.fromSingleUri(context, uri)?.name

    private suspend fun openClipDescriptors(clip: ExportClipPayload): IntArray {
        if (clip.uris.isEmpty()) {
            throw IllegalStateException("No source files are available for ${clip.displayName}")
        }

        val parcelDescriptors = mutableListOf<ParcelFileDescriptor>()
        val detachedFds = mutableListOf<Int>()
        try {
            clip.uris.forEach { uri ->
                val descriptor = contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IllegalStateException("Failed to obtain descriptor for $uri")
                parcelDescriptors += descriptor
            }

            // Check cancellation while Kotlin still owns closable ParcelFileDescriptors.
            coroutineContext.ensureActive()
            parcelDescriptors.forEach { descriptor ->
                detachedFds += descriptor.detachFd()
            }
            // Ownership of every detached fd transfers to native when export() is invoked.
            return detachedFds.toIntArray()
        } catch (throwable: Throwable) {
            parcelDescriptors.forEach { descriptor ->
                runCatching { descriptor.close() }
            }
            detachedFds.forEach { fd ->
                runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
            }
            Log.e(TAG, "Unable to open every SAF source for ${clip.displayName}", throwable)
            throw IllegalStateException(
                "Unable to open every source file for ${clip.displayName}",
                throwable
            )
        }
    }

    private fun ExportSettings.toExportOptions(
        sourceFileName: String,
        clipUriPath: String,
        audioTempDir: String,
        stretchFactorX: Float,
        stretchFactorY: Float,
        clipDebayerMode: Int = DebayerAlgorithm.AMAZE.nativeId,
        rawCorrection: RawCorrectionSettings = RawCorrectionSettings(),
        colorGrading: ColorGradingSettings = ColorGradingSettings(),
        cutIn: Int = 1,
        cutOut: Int = 0
    ): ExportOptions {
        val codecOption = when (codec) {
            ExportCodec.CINEMA_DNG -> cdngVariant.nativeId
            else -> 0
        }

        return ExportOptions(
            codec = codec,
            codecOption = codecOption,
            cdngVariant = cdngVariant,
            cdngNaming = cdngNaming,
            includeAudio = includeAudio,
            enableRawFixes = true,
            frameRateOverrideEnabled = frameRate.enabled,
            frameRateValue = frameRate.value,
            sourceFileName = sourceFileName,
            clipUriPath = clipUriPath,
            audioTempDir = audioTempDir,
            stretchFactorX = stretchFactorX,
            stretchFactorY = stretchFactorY,
            proResProfile = proResProfile,
            proResEncoder = proResEncoder,
            h264Quality = h264Quality,
            h264Container = h264Container,
            h265BitDepth = h265BitDepth,
            h265Quality = h265Quality,
            h265Container = h265Container,
            pngBitDepth = pngBitDepth,
            dnxhrProfile = dnxhrProfile,
            dnxhdProfile = dnxhdProfile,
            vp9Quality = vp9Quality,
            debayerQuality = debayerQuality,
            clipDebayerMode = clipDebayerMode,
            smoothing = smoothing,
            resize = resize,
            hdrBlending = hdrBlending,
            antiAliasing = antiAliasing,
            rawCorrection = rawCorrection,
            colorGrading = colorGrading,
            cutIn = cutIn,
            cutOut = cutOut
        )
    }

    private fun moveTempAudioToOutput(
        tempDirPath: String,
        clipName: String,
        destinationDir: DocumentFile
    ): DocumentFile {
        val baseName = clipName.substringBefore('.')
        val tempDir = File(tempDirPath)
        val audioFile = tempDir.listFiles()?.firstOrNull { file ->
            file.isFile && file.name.endsWith(".wav") &&
                (file.name.startsWith(baseName) || file.name.startsWith("${baseName}_"))
        } ?: throw IllegalStateException("The selected clip does not contain exportable audio")

        val targetDocument = destinationDir.createFile("audio/wav", audioFile.name)
            ?: run {
                deleteQuietly(audioFile)
                throw IllegalStateException("Failed to create audio DocumentFile for ${audioFile.name}")
            }

        try {
            contentResolver.openOutputStream(targetDocument.uri, "w")?.use { output ->
                audioFile.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("Failed to open output stream for ${targetDocument.uri}")
        } catch (throwable: Throwable) {
            deleteDocumentQuietly(targetDocument)
            throwable.exportCancellationCause()?.let { throw it }
            throw IllegalStateException(
                "Failed to move audio for $clipName: " +
                    throwable.exportFailureMessage(default = throwable::class.java.simpleName),
                throwable
            )
        } finally {
            deleteQuietly(audioFile)
        }
        return targetDocument
    }

    private fun createClipOutputTarget(
        parent: DocumentFile,
        clipName: String,
        settings: ExportSettings
    ): ClipOutputTarget {
        val baseName = clipName.split('.').first()
        val folderName: String = when (settings.cdngNaming) {
            CdngNaming.DEFAULT -> baseName
            CdngNaming.DAVINCI_RESOLVE -> {
                // Placeholder until we propagate recording date from native metadata.
                baseName
            }
        }
        parent.findFile(folderName)?.let { existing ->
            if (existing.isDirectory) {
                return ClipOutputTarget(directory = existing, createdDirectory = false)
            }
        }
        val directory = parent.createDirectory(folderName)
            ?: throw IllegalStateException("Failed to create directory for $folderName")
        return ClipOutputTarget(directory = directory, createdDirectory = true)
    }

    private fun handleAudioArtifact(
        baseClipName: String,
        clipOutputDir: DocumentFile
    ) {
        val tempAudio = locateTempAudioFile(baseClipName) ?: return
        if (!tempAudio.exists()) {
            return
        }
        if (!exportSettings.includeAudio) {
            deleteQuietly(tempAudio)
            return
        }
        val hasSubdirectory = exportSettings.codec in listOf(
            ExportCodec.CINEMA_DNG,
            ExportCodec.TIFF,
            ExportCodec.PNG,
            ExportCodec.JPEG2000,
            ExportCodec.AUDIO_ONLY
        )
        if (hasSubdirectory) {
            try {
                moveAudioToTarget(tempAudio, clipOutputDir, tempAudio.name)
            } catch (throwable: Throwable) {
                deleteQuietly(tempAudio)
                throwable.exportCancellationCause()?.let { throw it }
                throw IllegalStateException(
                    "Failed to move audio for $baseClipName: " +
                        throwable.exportFailureMessage(default = throwable::class.java.simpleName),
                    throwable
                )
            }
        } else {
            tempAudioArtifacts += tempAudio
        }
    }

    private fun locateTempAudioFile(baseClipName: String): File? {
        val searchDirs = listOfNotNull(filesDir, cacheDir)
        searchDirs.forEach { dir ->
            dir.listFiles()?.firstOrNull { file ->
                file.isFile &&
                    file.name.lowercase().endsWith(".wav") &&
                    (file.name.startsWith(baseClipName) || file.name.startsWith("${baseClipName}_"))
            }?.let { return it }
        }
        return null
    }

    private fun cleanupTempAudioForClip(vararg clipNames: String) {
        val baseNames = clipNames
            .map { name ->
                val leafName = name.substringAfterLast('/')
                leafName.substringBeforeLast('.', leafName)
            }
            .filter { it.isNotBlank() }
            .distinct()
        if (baseNames.isEmpty()) return

        listOfNotNull(filesDir, cacheDir).forEach { dir ->
            dir.listFiles()?.forEach { file ->
                if (
                    file.isFile &&
                    file.name.lowercase().endsWith(".wav") &&
                    baseNames.any { baseName ->
                        file.name.startsWith(baseName) || file.name.startsWith("${baseName}_")
                    }
                ) {
                    deleteQuietly(file)
                }
            }
        }
    }

    private fun moveAudioToTarget(
        tempAudio: File,
        destinationDir: DocumentFile,
        targetFileName: String
    ) {
        destinationDir.findFile(targetFileName)?.let { existing ->
            if (!deleteDocumentQuietly(existing)) {
                throw IllegalStateException("Failed to replace existing audio file $targetFileName")
            }
        }

        val targetDocument = destinationDir.createFile("audio/wav", targetFileName)
            ?: throw IllegalStateException("Failed to create audio DocumentFile for $targetFileName")

        try {
            contentResolver.openOutputStream(targetDocument.uri, "w")?.use { output ->
                tempAudio.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Failed to open output stream for ${targetDocument.uri}")
        } catch (throwable: Throwable) {
            deleteDocumentQuietly(targetDocument)
            throw throwable
        }

        deleteQuietly(tempAudio)
    }

    private fun cleanupPartialOutput(
        clipName: String,
        provider: ExportFdProvider,
        outputTarget: ClipOutputTarget
    ) {
        // Cancelled exports use the same cleanup policy to avoid leaving partial files behind.
        val deletedFiles = provider.deleteCreatedDocuments()
        val directoryDeleted = if (outputTarget.createdDirectory) {
            deleteDocumentQuietly(outputTarget.directory)
        } else {
            false
        }
        Log.w(
            TAG,
            "Cleaned partial export for $clipName: deletedFiles=$deletedFiles, " +
                "deletedDirectory=$directoryDeleted"
        )
    }

    private fun deleteDocumentQuietly(document: DocumentFile): Boolean {
        return runCatching { document.delete() }
            .onFailure { throwable ->
                Log.w(TAG, "Unable to delete SAF document ${document.uri}", throwable)
            }
            .getOrDefault(false)
    }

    private fun cleanupTempAudioArtifacts() {
        val snapshot = synchronized(tempAudioArtifacts) {
            val copy = tempAudioArtifacts.toList()
            tempAudioArtifacts.clear()
            copy
        }
        snapshot.forEach { file ->
            deleteQuietly(file)
        }
    }

    private fun deleteQuietly(file: File) {
        if (!file.exists()) return
        if (!file.delete()) {
            Log.w(TAG, "Unable to delete temporary audio file: ${file.absolutePath}")
        }
    }

    private companion object {
        private const val TAG = "fm.forum.mlv.ExportExecutor"
    }

    private data class ClipOutputTarget(
        val directory: DocumentFile,
        val createdDirectory: Boolean
    )
}
