package fm.magiclantern.forum

import android.app.ActivityManager
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.ui.Modifier
import fm.magiclantern.forum.nativeInterface.NativeLib
import fm.magiclantern.forum.settings.SettingsRepository
import fm.magiclantern.forum.ui.theme.MLVappTheme

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val forcePortrait = resources.getBoolean(R.bool.force_portrait)
        requestedOrientation = if (forcePortrait) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }

        NativeLib.setBaseDir(this.filesDir.absolutePath)
        val settingsRepository = SettingsRepository.getInstance(applicationContext)
        setContent {
            MLVappTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val windowSizeClass = calculateWindowSizeClass(this)
                    val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
                    val memoryInfo = ActivityManager.MemoryInfo()
                    activityManager.getMemoryInfo(memoryInfo)
                    // totalMem is in bytes; JNI expects MiB
                    val totalMemMiB = memoryInfo.totalMem / (1024L * 1024L)

                    val cacheSize =
                        if (totalMemMiB < 7500) totalMemMiB / 3 else (2 * (totalMemMiB - 4000)) / 3

                    val cpuCores = Runtime.getRuntime().availableProcessors()
                    val cores = if (cpuCores > 0) cpuCores else 4

                    NavController(
                        windowSizeClass = windowSizeClass,
                        cacheSize = cacheSize,
                        cores = cores,
                        settingsRepository = settingsRepository
                    )
                }
            }
        }
    }
}
