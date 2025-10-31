package fm.magiclantern.forum.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class DebayerMode(val displayName: String, val nativeId: Int) {
    NONE("None (monochrome)", 0),
    SIMPLE("Simple", 1),
    BILINEAR("Bilinear", 2),
    LMMSE("LMMSE", 3),
    IGV("IGV", 4),
    AHD("AHD", 5),
    RCD("RCD", 6),
    DCB("DCB", 7),
    ALWAYS_AMAZE("AMaZE", 8),
    AUTO("AMaZE Cached", 9)
}

class SettingsRepository private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val dropFrameFlow = MutableStateFlow(prefs.getBoolean(KEY_DROP_FRAME_MODE, true))
    val dropFrameMode: StateFlow<Boolean> = dropFrameFlow.asStateFlow()

    private val debayerModeFlow = MutableStateFlow(
        prefs.getString(KEY_DEBAYER_MODE, DebayerMode.ALWAYS_AMAZE.name)?.let { stored ->
            runCatching { DebayerMode.valueOf(stored) }.getOrDefault(DebayerMode.ALWAYS_AMAZE)
        } ?: DebayerMode.ALWAYS_AMAZE
    )
    val debayerMode: StateFlow<DebayerMode> = debayerModeFlow.asStateFlow()

    private val mutex = Mutex()

    suspend fun setDropFrameMode(enabled: Boolean) {
        mutex.withLock {
            prefs.edit().putBoolean(KEY_DROP_FRAME_MODE, enabled).apply()
            dropFrameFlow.value = enabled
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
        private const val KEY_DEBAYER_MODE = "debayer_mode"

        @Volatile
        private var INSTANCE: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsRepository(context).also { INSTANCE = it }
            }
        }
    }
}
