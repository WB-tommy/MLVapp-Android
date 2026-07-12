package fm.magiclantern.forum.features.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import fm.magiclantern.forum.features.export.model.ExportClipPayload
import fm.magiclantern.forum.features.export.model.ExportRequest
import fm.magiclantern.forum.features.export.model.ExportSettings
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

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _status = MutableStateFlow<ExportStatus>(ExportStatus.Idle)
    val status: StateFlow<ExportStatus> = _status.asStateFlow()

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var activeAttempt: ExportAttempt? = null

    @Volatile
    private var totalClips: Int = 0

    @Volatile
    private var completedClips: Int = 0

    private val nativeExportEngine: NativeExportEngine = NativeLibExportEngine

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
            _status.value = ExportStatus.Failed("Export request is missing.")
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        activeAttempt?.let { attempt ->
            attempt.latestStartId = startId
            Log.w(TAG, "Ignoring export start while another export is still finishing.")
            return START_REDELIVER_INTENT
        }

        @Suppress("DEPRECATION")
        val exportRequest = safeIntent.getParcelableExtra<ExportRequest>(EXTRA_EXPORT_REQUEST)
        @Suppress("DEPRECATION")
        val legacyClipPayloads =
            safeIntent.getParcelableArrayListExtra<ExportClipPayload>(EXTRA_EXPORT_CLIPS)
        @Suppress("DEPRECATION")
        val legacyOutputDirectoryUri =
            safeIntent.getParcelableExtra<Uri>(EXTRA_OUTPUT_DIRECTORY_URI)

        val clipPayloads = exportRequest?.clips ?: legacyClipPayloads.orEmpty()
        val outputDirectoryUri = exportRequest?.outputDirectory ?: legacyOutputDirectoryUri

        if (clipPayloads.isEmpty() || outputDirectoryUri == null) {
            _status.value = ExportStatus.Failed("Export request is incomplete.")
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        @Suppress("DEPRECATION")
        val requestSettings = exportRequest?.settings
            ?: safeIntent.getParcelableExtra(EXTRA_EXPORT_SETTINGS)
            ?: ExportSettings()

        val requestCacheSizeMiB = (exportRequest?.cacheSizeMiB
            ?: safeIntent.getLongExtra(EXTRA_CACHE_SIZE_MIB, 0L)).coerceAtLeast(0L)
        val requestCpuCores = (exportRequest?.cpuCores
            ?: safeIntent.getIntExtra(EXTRA_CPU_CORES, 1)).coerceAtLeast(1)

        totalClips = clipPayloads.size
        completedClips = 0
        _progress.value = 0f
        _status.value =
            ExportStatus.Running(clipIndex = 0, totalClips = totalClips, clipName = null)

        try {
            startExportForeground(
                buildProgressNotification(
                    title = "Exporting clips",
                    text = "Preparing export...",
                    progressPercent = 0,
                    indeterminate = true
                )
            )
        } catch (throwable: RuntimeException) {
            val message = "Unable to start export in the foreground: " +
                throwable.exportFailureMessage(default = throwable::class.java.simpleName)
            Log.e(TAG, message, throwable)
            _status.value = ExportStatus.Failed(message)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        val attempt = ExportAttempt(startId)
        activeAttempt = attempt
        val job = serviceScope.launch {
            val executor = createExportExecutor(
                attempt = attempt,
                cacheSizeMiB = requestCacheSizeMiB,
                cpuCores = requestCpuCores,
                settings = requestSettings
            )
            attempt.executor = executor
            try {
                executor.run(clipPayloads, outputDirectoryUri)
                coroutineContext.ensureActive()
                attempt.terminalStatus = ExportStatus.Completed(clipPayloads.size)
            } catch (ex: CancellationException) {
                attempt.terminalStatus = if (attempt.timedOut) {
                    ExportStatus.Failed(FOREGROUND_TIMEOUT_MESSAGE)
                } else {
                    ExportStatus.Cancelled(completedClips)
                }
            } catch (throwable: Throwable) {
                val cancellation = throwable.exportCancellationCause()
                if (cancellation != null) {
                    attempt.terminalStatus = if (attempt.timedOut) {
                        ExportStatus.Failed(FOREGROUND_TIMEOUT_MESSAGE)
                    } else {
                        ExportStatus.Cancelled(completedClips)
                    }
                } else {
                    val message = if (attempt.timedOut) {
                        FOREGROUND_TIMEOUT_MESSAGE
                    } else {
                        throwable.exportFailureMessage(default = "Export failed")
                    }
                    attempt.terminalStatus = ExportStatus.Failed(message)
                }
            } finally {
                executor.cleanup()
                attempt.executor = null
            }
        }
        attempt.job = job
        job.invokeOnCompletion { cause ->
            val terminalStatus = attempt.terminalStatus ?: when {
                attempt.timedOut -> ExportStatus.Failed(FOREGROUND_TIMEOUT_MESSAGE)
                cause is CancellationException -> ExportStatus.Cancelled(completedClips)
                cause != null -> ExportStatus.Failed(
                    cause.exportFailureMessage(default = "Export failed")
                )
                else -> ExportStatus.Failed("Export stopped before producing a result.")
            }
            mainHandler.post {
                finishAttempt(attempt, terminalStatus)
            }
        }

        return START_REDELIVER_INTENT
    }

    private fun createExportExecutor(
        attempt: ExportAttempt,
        cacheSizeMiB: Long,
        cpuCores: Int,
        settings: ExportSettings
    ): ExportExecutor {
        return ExportExecutor(
            context = applicationContext,
            contentResolver = contentResolver,
            filesDir = filesDir,
            cacheDir = cacheDir,
            cacheSizeMiB = cacheSizeMiB,
            cpuCores = cpuCores,
            exportSettings = settings,
            nativeExportEngine = nativeExportEngine,
            callbacks = object : ExportExecutor.Callbacks {
                override fun onClipStarted(index: Int, totalClips: Int, clipName: String) {
                    if (activeAttempt !== attempt) return
                    _status.value = ExportStatus.Running(index, totalClips, clipName)
                    updateNotificationProgress(index, clipName, clipProgress = 0)
                }

                override fun onClipProgress(
                    index: Int,
                    totalClips: Int,
                    clipName: String,
                    clipProgress: Int,
                    overallProgress: Float
                ) {
                    if (activeAttempt !== attempt) return
                    _progress.value = overallProgress
                    updateNotificationProgress(index, clipName, clipProgress)
                }

                override fun onClipCompleted(
                    index: Int,
                    totalClips: Int,
                    clipName: String,
                    overallProgress: Float
                ) {
                    if (activeAttempt !== attempt) return
                    updateNotificationProgress(index, clipName, clipProgress = 100)
                    _progress.value = overallProgress
                    completedClips = index + 1
                }
            }
        )
    }

    private fun finishAttempt(attempt: ExportAttempt, terminalStatus: ExportStatus) {
        if (activeAttempt !== attempt) return

        // Terminal means cleanup and foreground teardown are complete, so a retry is safe.
        stopForegroundCompat(detachNotification = true)
        activeAttempt = null

        when (terminalStatus) {
            is ExportStatus.Completed -> {
                _progress.value = 1f
                _status.value = terminalStatus
                updateNotificationCompleted()
            }

            is ExportStatus.Cancelled -> {
                _status.value = terminalStatus
                updateNotificationCancelled()
            }

            is ExportStatus.Failed -> {
                _status.value = terminalStatus
                updateNotificationFailed(terminalStatus.reason)
            }

            is ExportStatus.Running,
            ExportStatus.Idle -> {
                _status.value = ExportStatus.Failed("Export stopped without a terminal result.")
                updateNotificationFailed("Export stopped without a terminal result.")
            }
        }

        stopSelfResult(attempt.latestStartId)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground service timeout reached: type=$fgsType, startId=$startId")
        val attempt = activeAttempt
        if (attempt != null) {
            attempt.timedOut = true
            attempt.executor?.cancel() ?: nativeExportEngine.cancel()
            attempt.job?.cancel(CancellationException(FOREGROUND_TIMEOUT_MESSAGE))
        }
        // Android only grants a short grace period after this callback.
        stopForegroundCompat(detachNotification = false)
        stopSelf()
    }

    fun cancelExport() {
        val attempt = activeAttempt ?: return
        attempt.executor?.cancel() ?: nativeExportEngine.cancel()
        attempt.job?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        val attempt = activeAttempt
        attempt?.executor?.cancel() ?: nativeExportEngine.cancel()
        attempt?.job?.cancel()
        activeAttempt = null
        serviceJob.cancel()
        _status.value = ExportStatus.Idle
        _progress.value = 0f
        completedClips = 0
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

    private fun startExportForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, exportForegroundServiceType())
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun exportForegroundServiceType(): Int {
        return exportForegroundServiceTypeForSdk(Build.VERSION.SDK_INT)
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
        private const val FOREGROUND_TIMEOUT_MESSAGE =
            "Export stopped because Android's foreground service time limit was reached."

        const val EXTRA_EXPORT_CLIPS = "export_clips"
        const val EXTRA_OUTPUT_DIRECTORY_URI = "output_directory_uri"
        const val EXTRA_CACHE_SIZE_MIB = "cache_size_mib"
        const val EXTRA_CPU_CORES = "cpu_cores"
        const val EXTRA_EXPORT_SETTINGS = "export_settings"
        const val EXTRA_EXPORT_REQUEST = "export_request"
    }

    sealed interface ExportStatus {
        object Idle : ExportStatus
        data class Running(val clipIndex: Int, val totalClips: Int, val clipName: String?) :
            ExportStatus

        data class Completed(val totalClips: Int) : ExportStatus
        data class Failed(val reason: String) : ExportStatus
        data class Cancelled(val completedClips: Int) : ExportStatus
    }

    private class ExportAttempt(startId: Int) {
        @Volatile
        var latestStartId: Int = startId

        @Volatile
        var executor: ExportExecutor? = null

        @Volatile
        var job: Job? = null

        @Volatile
        var terminalStatus: ExportStatus? = null

        @Volatile
        var timedOut: Boolean = false
    }
}

internal fun exportForegroundServiceTypeForSdk(sdkInt: Int): Int {
    return if (sdkInt >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
    } else {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    }
}
