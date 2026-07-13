package fm.magiclantern.forum.features.settings.viewmodel

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsRepositoryTest {
    private lateinit var context: Context
    private var hadOriginalGpuValue = false
    private var originalGpuValue = false
    private var hadOriginalParallelValue = false
    private var originalParallelValue = false

    @Before
    fun removeExperimentalSetting() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = settingsPreferences()
        hadOriginalGpuValue = preferences.contains(KEY_EXPERIMENTAL_RAW_GPU_PREVIEW)
        originalGpuValue = preferences.getBoolean(KEY_EXPERIMENTAL_RAW_GPU_PREVIEW, false)
        hadOriginalParallelValue = preferences.contains(KEY_EXPERIMENTAL_MCRAW_PARALLEL_DECODER)
        originalParallelValue = preferences.getBoolean(KEY_EXPERIMENTAL_MCRAW_PARALLEL_DECODER, false)
        preferences.edit()
            .remove(KEY_EXPERIMENTAL_RAW_GPU_PREVIEW)
            .remove(KEY_EXPERIMENTAL_MCRAW_PARALLEL_DECODER)
            .commit()
    }

    @After
    fun restoreExperimentalSetting() {
        settingsPreferences().edit().apply {
            if (hadOriginalGpuValue) {
                putBoolean(KEY_EXPERIMENTAL_RAW_GPU_PREVIEW, originalGpuValue)
            } else {
                remove(KEY_EXPERIMENTAL_RAW_GPU_PREVIEW)
            }
            if (hadOriginalParallelValue) {
                putBoolean(KEY_EXPERIMENTAL_MCRAW_PARALLEL_DECODER, originalParallelValue)
            } else {
                remove(KEY_EXPERIMENTAL_MCRAW_PARALLEL_DECODER)
            }
        }.commit()
    }

    @Test
    fun experimentalRawGpuPreviewDefaultsOffAndPersistsChanges() = runBlocking {
        val repository = SettingsRepository(context)
        assertFalse(repository.experimentalRawGpuPreview.value)

        repository.setExperimentalRawGpuPreview(true)
        assertTrue(repository.experimentalRawGpuPreview.value)

        val restoredRepository = SettingsRepository(context)
        assertTrue(restoredRepository.experimentalRawGpuPreview.value)
    }

    @Test
    fun experimentalMcrawParallelDecoderDefaultsOffAndPersistsIndependently() = runBlocking {
        val repository = SettingsRepository(context)
        assertFalse(repository.experimentalMcrawParallelDecoder.value)

        repository.setExperimentalRawGpuPreview(true)
        assertFalse(repository.experimentalMcrawParallelDecoder.value)

        repository.setExperimentalMcrawParallelDecoder(true)
        repository.setExperimentalRawGpuPreview(false)

        val restoredRepository = SettingsRepository(context)
        assertTrue(restoredRepository.experimentalMcrawParallelDecoder.value)
        assertFalse(restoredRepository.experimentalRawGpuPreview.value)
    }

    private fun settingsPreferences() =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private companion object {
        const val PREFS_NAME = "mlvapp_settings"
        const val KEY_EXPERIMENTAL_RAW_GPU_PREVIEW = "experimental_mcraw_gpu_preview"
        const val KEY_EXPERIMENTAL_MCRAW_PARALLEL_DECODER =
            "experimental_mcraw_parallel_decoder"
    }
}
