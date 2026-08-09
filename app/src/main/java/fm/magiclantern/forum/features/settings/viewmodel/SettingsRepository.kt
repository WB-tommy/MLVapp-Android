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

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val dropFrameFlow = MutableStateFlow(prefs.getBoolean(KEY_DROP_FRAME_MODE, true))
    val dropFrameMode: StateFlow<Boolean> = dropFrameFlow.asStateFlow()

    private val experimentalRawGpuPreviewFlow = MutableStateFlow(
        prefs.getBoolean(KEY_EXPERIMENTAL_RAW_GPU_PREVIEW, false)
    )
    val experimentalRawGpuPreview: StateFlow<Boolean> =
        experimentalRawGpuPreviewFlow.asStateFlow()

    private val experimentalMcrawParallelDecoderFlow = MutableStateFlow(
        prefs.getBoolean(KEY_EXPERIMENTAL_MCRAW_PARALLEL_DECODER, true)
    )
    val experimentalMcrawParallelDecoder: StateFlow<Boolean> =
        experimentalMcrawParallelDecoderFlow.asStateFlow()

    private val mutex = Mutex()

    suspend fun setDropFrameMode(enabled: Boolean) {
        mutex.withLock {
            prefs.edit().putBoolean(KEY_DROP_FRAME_MODE, enabled).apply()
            dropFrameFlow.value = enabled
        }
    }

    suspend fun setExperimentalRawGpuPreview(enabled: Boolean) {
        mutex.withLock {
            prefs.edit().putBoolean(KEY_EXPERIMENTAL_RAW_GPU_PREVIEW, enabled).apply()
            experimentalRawGpuPreviewFlow.value = enabled
        }
    }

    suspend fun setExperimentalMcrawParallelDecoder(enabled: Boolean) {
        mutex.withLock {
            prefs.edit().putBoolean(KEY_EXPERIMENTAL_MCRAW_PARALLEL_DECODER, enabled).apply()
            experimentalMcrawParallelDecoderFlow.value = enabled
        }
    }

    companion object {
        private const val PREFS_NAME = "mlvapp_settings"
        private const val KEY_DROP_FRAME_MODE = "drop_frame_mode"
        // Preserve the prototype's original key so installed test builds keep
        // the user's opt-in choice while the experiment expands to classic MLV.
        private const val KEY_EXPERIMENTAL_RAW_GPU_PREVIEW =
            "experimental_mcraw_gpu_preview"
        private const val KEY_EXPERIMENTAL_MCRAW_PARALLEL_DECODER =
            "experimental_mcraw_parallel_decoder"
    }
}
