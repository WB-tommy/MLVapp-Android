package fm.forum.mlvapp.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import fm.forum.mlvapp.NativeInterface.NativeLib
import java.io.File
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

class ExportService : Service() {

    private val binder = LocalBinder()

    private val tempAudioArtifacts = Collections.synchronizedList(mutableListOf<java.io.File>())

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _status = MutableStateFlow<ExportStatus>(ExportStatus.Idle)
    val status: StateFlow<ExportStatus> = _status.asStateFlow()

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var currentExportJob: Job? = null

    @Volatile
    private var totalClips: Int = 0

    @Volatile
    private var activeClipIndex: Int = 0

    @Volatile
    private var completedClips: Int = 0

    @Volatile
    private var cacheSize: Long = 0L

    @Volatile
    private var cpuCores: Int = 1

    @Volatile
    private var exportSettings: ExportSettings = ExportSettings()

    private val notificationManager: NotificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    inner class LocalBinder : Binder() {
        fun getService(): ExportService = this@ExportService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val safeIntent = intent ?: run {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        @Suppress("DEPRECATION")
        val clipPayloads =
            safeIntent.getParcelableArrayListExtra<ExportClipPayload>(EXTRA_EXPORT_CLIPS)
        val outputDirectoryUri = safeIntent.getParcelableExtra<Uri>(EXTRA_OUTPUT_DIRECTORY_URI)

        if (clipPayloads.isNullOrEmpty() || outputDirectoryUri == null) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        exportSettings = safeIntent.getParcelableExtra(EXTRA_EXPORT_SETTINGS) ?: ExportSettings()

        cacheSize = safeIntent.getLongExtra(EXTRA_TOTAL_MEMORY, cacheSize).coerceAtLeast(0L)
        cpuCores = safeIntent.getIntExtra(EXTRA_CPU_CORES, cpuCores).coerceAtLeast(1)

        totalClips = clipPayloads.size
        activeClipIndex = 0
        completedClips = 0
        _progress.value = 0f
        _status.value =
            ExportStatus.Running(clipIndex = 0, totalClips = totalClips, clipName = null)

        startForeground(
            NOTIFICATION_ID,
            buildProgressNotification(
                title = "Exporting clips",
                text = "Preparing export...",
                progressPercent = 0,
                indeterminate = true
            )
        )

        currentExportJob?.cancel()
        currentExportJob = serviceScope.launch {
            try {
                runExport(clipPayloads, outputDirectoryUri)
                _progress.value = 1f
                _status.value = ExportStatus.Completed(totalClips)
                updateNotificationCompleted()
            } catch (ex: CancellationException) {
                _status.value = ExportStatus.Cancelled(completedClips)
                updateNotificationCancelled()
                throw ex
            } catch (throwable: Throwable) {
                val message = throwable.message ?: "Export failed"
                _status.value = ExportStatus.Failed(message)
                updateNotificationFailed(message)
            } finally {
                stopForegroundCompat(detachNotification = true)
                stopSelfResult(startId)
            }
        }

        return START_REDELIVER_INTENT
    }

    fun cancelExport() {
        currentExportJob?.cancel()
        cleanupTempAudioArtifacts()
    }

    override fun onDestroy() {
        super.onDestroy()
        currentExportJob?.cancel()
        serviceJob.cancel()
        _status.value = ExportStatus.Idle
        _progress.value = 0f
        completedClips = 0
        cleanupTempAudioArtifacts()
    }

    private suspend fun runExport(clips: List<ExportClipPayload>, outputDirectoryUri: Uri) {
        val outputDir = DocumentFile.fromTreeUri(applicationContext, outputDirectoryUri)
            ?: throw IllegalStateException("Output directory is unavailable")

        if (!outputDir.exists()) {
            throw IllegalStateException("Output directory does not exist")
        }

        try {
            clips.forEachIndexed { index, clip ->
                coroutineContext.ensureActive()
                activeClipIndex = index
                val clipName = clip.displayName.ifBlank {
                    clip.uris.firstOrNull()?.let { resolveClipName(it) } ?: "Clip ${index + 1}"
                }
                _status.value = ExportStatus.Running(index, totalClips, clipName)
                updateNotificationProgress(index, clipName, clipProgress = 0)

                exportClip(clip, outputDir, index, clipName)

                updateNotificationProgress(index, clipName, clipProgress = 100)
                val overall = (index + 1f) / totalClips.coerceAtLeast(1)
                _progress.value = overall
                completedClips = index + 1
            }
        } finally {
            cleanupTempAudioArtifacts()
        }
    }

    private fun exportClip(
        clip: ExportClipPayload,
        outputDir: DocumentFile,
        index: Int,
        clipName: String
    ) {
        val clipFds = openClipDescriptors(clip)
        if (clipFds.isEmpty()) {
            throw IllegalStateException("No data to export for $clipName")
        }

        if (exportSettings.codec == ExportCodec.AUDIO_ONLY) {
            val tempDir = filesDir.absolutePath
            val exportOptions = exportSettings.toExportOptions(
                sourceFileName = clip.primaryFileName,
                clipUriPath = "",
                audioTempDir = tempDir
            )
            try {
                NativeLib.exportHandler(
                    memSize = cacheSize,
                    cpuCores = cpuCores,
                    clipFds = clipFds,
                    options = exportOptions,
                    progressListener = ProgressListener { progress ->
                        val bounded = progress.coerceIn(0, 100)
                        val perClipFraction = bounded / 100f
                        _progress.value = ((index + perClipFraction) / totalClips.coerceAtLeast(1))
                        updateNotificationProgress(index, clipName, bounded)
                    },
                    fileProvider = null
                )
            } catch (throwable: Throwable) {
                throw IllegalStateException("Failed to export audio for $clipName", throwable)
            }
            moveTempAudioToOutput(tempDir, clipName, outputDir)
            return
        }

        val clipOutputDir = createClipOutputDirectory(outputDir, clipName, exportSettings)
        val clipUriPath = clipOutputDir.uri.toString()
        val audioTempDir = filesDir.absolutePath
        val sourceFileName = clip.primaryFileName
        val baseName = sourceFileName.substringBeforeLast('.', sourceFileName)
        val exportOptions = exportSettings.toExportOptions(sourceFileName, clipUriPath, audioTempDir)

        val provider = if (exportSettings.codec == ExportCodec.CINEMA_DNG) {
            ExportFdProvider(contentResolver, clipOutputDir)
        } else {
            null
        }

        val total = totalClips.coerceAtLeast(1)

        try {
            NativeLib.exportHandler(
                memSize = cacheSize,
                cpuCores = cpuCores,
                clipFds = clipFds,
                options = exportOptions,
                progressListener = ProgressListener { progress ->
                    val boundedProgress = progress.coerceIn(0, 100)
                    val perClipFraction = boundedProgress / 100f
                    _progress.value = ((index + perClipFraction) / total)
                    updateNotificationProgress(index, clipName, boundedProgress)
                },
                fileProvider = provider
            )
        } catch (throwable: Throwable) {
            throw IllegalStateException("Failed to export $clipName", throwable)
        }

        handleAudioArtifact(baseName, clipOutputDir)
    }

    private fun resolveClipName(uri: Uri): String? =
        DocumentFile.fromSingleUri(applicationContext, uri)?.name

    private fun openClipDescriptors(clip: ExportClipPayload): IntArray {
        return clip.uris.mapNotNull { uri ->
            runCatching {
                val pfd = contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IllegalStateException("Failed to obtain descriptor for $uri")
                pfd.use { it.detachFd() }
            }.getOrElse { throwable ->
                Log.e(TAG, "Unable to open SAF descriptor for $uri", throwable)
                null
            }
        }.toIntArray()
    }

    private fun ExportSettings.toExportOptions(
        sourceFileName: String,
        clipUriPath: String,
        audioTempDir: String
    ): ExportOptions {
        val codecOption = when (codec) {
            ExportCodec.CINEMA_DNG -> cdngVariant.nativeId
            else -> 0 // TODO: populate once additional codecs are supported
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
            audioTempDir = audioTempDir
        )
    }

    private fun moveTempAudioToOutput(
        tempDirPath: String,
        clipName: String,
        destinationDir: DocumentFile
    ) {
        val baseName = clipName.substringBefore('.')
        val tempDir = File(tempDirPath)
        val audioFile = tempDir.listFiles()?.firstOrNull { file ->
            file.isFile && file.name?.endsWith(".wav") == true &&
                (file.name?.startsWith(baseName) == true || file.name?.startsWith("${baseName}_") == true)
        } ?: return

        val targetName = audioFile.name ?: "$baseName.wav"
        val targetDocument = destinationDir.createFile("audio/wav", targetName) ?: return

        contentResolver.openOutputStream(targetDocument.uri)?.use { output ->
            audioFile.inputStream().use { input -> input.copyTo(output) }
        }
        audioFile.delete()
    }

    private fun createClipOutputDirectory(
        parent: DocumentFile,
        clipName: String,
        settings: ExportSettings
    ): DocumentFile {
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
                return existing
            }
        }
        return parent.createDirectory(folderName)
            ?: throw IllegalStateException("Failed to create directory for $folderName")
    }

    private fun buildProgressNotification(
        title: String,
        text: String,
        progressPercent: Int,
        indeterminate: Boolean = false
    ): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOnlyAlertOnce(true)
            .setOngoing(true)

        if (indeterminate) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(100, progressPercent.coerceIn(0, 100), false)
        }
        return builder.build()
    }

    private fun updateNotificationProgress(
        clipIndex: Int,
        clipName: String?,
        clipProgress: Int
    ) {
        val total = totalClips.coerceAtLeast(1)
        val overallPercent = (((clipIndex + clipProgress / 100f) / total.toFloat()) * 100)
            .roundToInt()
            .coerceIn(0, 100)

        val contentText = buildString {
            append("Clip ${clipIndex + 1} of $total")
            if (!clipName.isNullOrBlank()) {
                append(": ")
                append(clipName)
            }
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Exporting clips")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, overallPercent, false)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun updateNotificationCompleted() {
        val text = if (totalClips == 1) {
            "Exported 1 clip"
        } else {
            "Exported $totalClips clips"
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Export complete")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun updateNotificationFailed(message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Export failed")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun updateNotificationCancelled() {
        val stoppedAfter = completedClips
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Export cancelled")
            .setContentText(
                "Stopped after $stoppedAfter clip${if (stoppedAfter == 1) "" else "s"}"
            )
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun stopForegroundCompat(detachNotification: Boolean) {
        stopForeground(
            if (detachNotification) Service.STOP_FOREGROUND_DETACH else Service.STOP_FOREGROUND_REMOVE
        )
    }

    private fun createNotificationChannel() {
        val name = "Export"
        val descriptionText = "Shows the progress of the export"
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
            description = descriptionText
        }
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "ExportServiceChannel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "fm.forum.mlv.ExportService"

        const val EXTRA_EXPORT_CLIPS = "export_clips"
        const val EXTRA_OUTPUT_DIRECTORY_URI = "output_directory_uri"
        const val EXTRA_TOTAL_MEMORY = "total_memory"
        const val EXTRA_CPU_CORES = "cpu_cores"
        const val EXTRA_EXPORT_SETTINGS = "export_settings"
    }

    sealed interface ExportStatus {
        object Idle : ExportStatus
        data class Running(val clipIndex: Int, val totalClips: Int, val clipName: String?) :
            ExportStatus

        data class Completed(val totalClips: Int) : ExportStatus
        data class Failed(val reason: String) : ExportStatus
        data class Cancelled(val completedClips: Int) : ExportStatus
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
        if (exportSettings.codec == ExportCodec.CINEMA_DNG || exportSettings.codec == ExportCodec.AUDIO_ONLY) {
            val targetName = tempAudio.name ?: "$baseClipName.wav"
            val moved = moveAudioToTarget(tempAudio, clipOutputDir, targetName)
            if (!moved) {
                tempAudioArtifacts += tempAudio
            }
        } else {
            tempAudioArtifacts += tempAudio
        }
    }

    private fun locateTempAudioFile(baseClipName: String): java.io.File? {
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

    private fun moveAudioToTarget(
        tempAudio: java.io.File,
        destinationDir: DocumentFile,
        targetFileName: String
    ): Boolean {
        return try {
            destinationDir.findFile(targetFileName)?.delete()
            val targetDocument = destinationDir.createFile("audio/wav", targetFileName)
                ?: run {
                    Log.e(TAG, "Failed to create audio DocumentFile for $targetFileName")
                    return false
                }
            contentResolver.openOutputStream(targetDocument.uri, "w")?.use { output ->
                tempAudio.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: run {
                Log.e(TAG, "Failed to open output stream for ${targetDocument.uri}")
                return false
            }
            deleteQuietly(tempAudio)
            true
        } catch (throwable: Throwable) {
            Log.e(TAG, "Failed to move audio file into SAF directory", throwable)
            false
        }
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

    private fun deleteQuietly(file: java.io.File) {
        if (!file.exists()) return
        if (!file.delete()) {
            Log.w(TAG, "Unable to delete temporary audio file: ${file.absolutePath}")
        }
    }
}
