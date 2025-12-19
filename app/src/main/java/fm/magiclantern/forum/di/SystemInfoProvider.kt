package fm.magiclantern.forum.di

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides system hardware information for ViewModels.
 */
@Singleton
class SystemInfoProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val totalMemoryMiB: Long by lazy {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        memoryInfo.totalMem / (1024 * 1024)
    }

    val cpuCores: Int by lazy {
        Runtime.getRuntime().availableProcessors()
    }
}
