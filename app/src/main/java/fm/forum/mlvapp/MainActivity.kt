package fm.forum.mlvapp

import android.app.ActivityManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import fm.forum.mlvapp.ui.theme.MLVappTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MLVappTheme {
                val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
                val memoryInfo = ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memoryInfo)
                // totalMem is in bytes; JNI expects MiB
                val totalMemMiB = memoryInfo.totalMem / (1024L * 1024L)

                val cacheSize =
                    if (totalMemMiB < 7500) totalMemMiB / 3 else (2 * (totalMemMiB - 4000)) / 3

                val cpuCores = Runtime.getRuntime().availableProcessors()
                val cores = if (cpuCores > 0) cpuCores else 4

                MainScreen(cacheSize, cores)
            }
        }
    }
}
