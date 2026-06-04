package fm.magiclantern.forum.di

import android.app.ActivityManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class SystemRuntimeInfo(
    val totalMemoryMiB: Long,
    val frameCacheSizeMiB: Long,
    val cpuCores: Int
)

/**
 * Provides system hardware information for ViewModels.
 */
@Singleton
class SystemInfoProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val runtimeInfo: SystemRuntimeInfo by lazy {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val totalMemoryMiB = memoryInfo.totalMem / BYTES_PER_MEBIBYTE
        SystemRuntimeInfo(
            totalMemoryMiB = totalMemoryMiB,
            frameCacheSizeMiB = calculateFrameCacheSizeMiB(totalMemoryMiB),
            cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(DEFAULT_CPU_CORES)
        )
    }

    val totalMemoryMiB: Long
        get() = runtimeInfo.totalMemoryMiB

    val frameCacheSizeMiB: Long
        get() = runtimeInfo.frameCacheSizeMiB

    val cpuCores: Int
        get() = runtimeInfo.cpuCores

    companion object {
        private const val BYTES_PER_MEBIBYTE = 1024L * 1024L
        private const val DEFAULT_CPU_CORES = 4
        private const val MIN_CACHE_SIZE_MIB = 256L
        private const val MAX_CACHE_SIZE_MIB = 1536L

        fun calculateFrameCacheSizeMiB(totalMemoryMiB: Long): Long {
            val calculatedCacheMiB = when {
                totalMemoryMiB < 4_000L -> totalMemoryMiB / 4
                totalMemoryMiB < 8_000L -> totalMemoryMiB / 3
                else -> (totalMemoryMiB - 4_000L) / 2
            }

            return calculatedCacheMiB.coerceIn(MIN_CACHE_SIZE_MIB, MAX_CACHE_SIZE_MIB)
        }
    }
}
