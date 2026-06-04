package fm.magiclantern.forum

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
import dagger.hilt.android.AndroidEntryPoint
import fm.magiclantern.forum.di.SystemInfoProvider
import fm.magiclantern.forum.nativeInterface.NativeLib
import fm.magiclantern.forum.ui.theme.MLVappTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var systemInfoProvider: SystemInfoProvider

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
        val systemInfo = systemInfoProvider.runtimeInfo

        setContent {
            MLVappTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val windowSizeClass = calculateWindowSizeClass(this)
                    NavController(
                        windowSizeClass = windowSizeClass,
                        cacheSizeMiB = systemInfo.frameCacheSizeMiB,
                        cpuCores = systemInfo.cpuCores
                    )
                }
            }
        }
    }
}
