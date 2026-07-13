package fm.magiclantern.forum.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorGradingSettingsCpuPreviewTest {
    @Test
    fun desktopDefaultsRequireCpuForFootprintAndDarkCurve() {
        assertTrue(ColorGradingSettings().requiresCpuProcessingPreview())
    }

    @Test
    fun neutralGpuSupportedSettingsDoNotRequireCpu() {
        val settings = ColorGradingSettings(
            camMatrixUsed = 0,
            ds = 0,
            agx = 0
        )

        assertFalse(settings.requiresCpuProcessingPreview())
    }

    @Test
    fun disabledCreativeAdjustmentsIgnoreStoredCreativeValues() {
        val settings = ColorGradingSettings(
            camMatrixUsed = 0,
            allowCreativeAdjustments = 0,
            agx = 0
        )

        assertFalse(settings.requiresCpuProcessingPreview())
    }

    @Test
    fun highlightReconstructionAlwaysRequiresCpu() {
        val settings = ColorGradingSettings(
            camMatrixUsed = 0,
            ds = 0,
            agx = 0,
            highlightReconstruction = 1
        )

        assertTrue(settings.requiresCpuProcessingPreview())
    }

    @Test
    fun agxWithoutCameraMatrixRequiresCpuExpandOnlyPath() {
        val settings = ColorGradingSettings(
            camMatrixUsed = 0,
            ds = 0,
            agx = 1
        )

        assertTrue(settings.requiresCpuProcessingPreview())
    }
}
