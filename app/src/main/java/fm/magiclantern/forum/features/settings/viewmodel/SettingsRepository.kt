package fm.magiclantern.forum.features.settings.viewmodel

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

enum class DebayerMode(val displayName: String, val nativeId: Int) {
    NONE("None (monochrome)", 0),
    SIMPLE("Simple", 1),
    BILINEAR("Bilinear", 2),
    LMMSE("LMMSE", 3),
    IGV("IGV", 4),
    AMAZE("AMaZE", 5),
    AHD("AHD", 6),
    RCD("RCD", 7),
    DCB("DCB", 8),
}

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val dropFrameFlow = MutableStateFlow(prefs.getBoolean(KEY_DROP_FRAME_MODE, true))
    val dropFrameMode: StateFlow<Boolean> = dropFrameFlow.asStateFlow()

    private val experimentalMcrawGpuPreviewFlow = MutableStateFlow(
        prefs.getBoolean(KEY_EXPERIMENTAL_MCRAW_GPU_PREVIEW, false)
    )
    val experimentalMcrawGpuPreview: StateFlow<Boolean> =
        experimentalMcrawGpuPreviewFlow.asStateFlow()

    private val experimentalMcrawParallelDecoderFlow = MutableStateFlow(
        prefs.getBoolean(KEY_EXPERIMENTAL_MCRAW_PARALLEL_DECODER, false)
    )
    val experimentalMcrawParallelDecoder: StateFlow<Boolean> =
        experimentalMcrawParallelDecoderFlow.asStateFlow()

    private val debayerModeFlow = MutableStateFlow(
        prefs.getString(KEY_DEBAYER_MODE, DebayerMode.AMAZE.name)?.let { stored ->
            runCatching { DebayerMode.valueOf(stored) }.getOrDefault(DebayerMode.AMAZE)
        } ?: DebayerMode.AMAZE
    )
    val debayerMode: StateFlow<DebayerMode> = debayerModeFlow.asStateFlow()

    private val mutex = Mutex()

    suspend fun setDropFrameMode(enabled: Boolean) {
        mutex.withLock {
            prefs.edit().putBoolean(KEY_DROP_FRAME_MODE, enabled).apply()
            dropFrameFlow.value = enabled
        }
    }

    suspend fun setExperimentalMcrawGpuPreview(enabled: Boolean) {
        mutex.withLock {
            prefs.edit().putBoolean(KEY_EXPERIMENTAL_MCRAW_GPU_PREVIEW, enabled).apply()
            experimentalMcrawGpuPreviewFlow.value = enabled
        }
    }

    suspend fun setExperimentalMcrawParallelDecoder(enabled: Boolean) {
        mutex.withLock {
            prefs.edit().putBoolean(KEY_EXPERIMENTAL_MCRAW_PARALLEL_DECODER, enabled).apply()
            experimentalMcrawParallelDecoderFlow.value = enabled
        }
    }

    suspend fun setDebayerMode(mode: DebayerMode) {
        mutex.withLock {
            prefs.edit().putString(KEY_DEBAYER_MODE, mode.name).apply()
            debayerModeFlow.value = mode
        }
    }

    companion object {
        private const val PREFS_NAME = "mlvapp_settings"
        private const val KEY_DROP_FRAME_MODE = "drop_frame_mode"
        private const val KEY_EXPERIMENTAL_MCRAW_GPU_PREVIEW =
            "experimental_mcraw_gpu_preview"
        private const val KEY_EXPERIMENTAL_MCRAW_PARALLEL_DECODER =
            "experimental_mcraw_parallel_decoder"
        private const val KEY_DEBAYER_MODE = "debayer_mode"
    }
}
