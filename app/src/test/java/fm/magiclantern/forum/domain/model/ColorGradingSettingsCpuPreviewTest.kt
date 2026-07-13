package fm.magiclantern.forum.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorGradingSettingsCpuPreviewTest {
    @Test
    fun requestedProcessingAndProfileControlsAreGpuEligible() {
        val defaults = ColorGradingSettings()
        val supportedReceipts = listOf(
            "desktop defaults" to defaults,
            "exposure" to defaults.copy(exposure = 2f),
            "white balance" to defaults.copy(temperature = 3200, tint = -25),
            "contrast" to defaults.copy(contrast = 50),
            "pivot" to defaults.copy(pivot = 40),
            "clarity" to defaults.copy(clarity = 35),
            "vibrance" to defaults.copy(vibrance = 45),
            "saturation" to defaults.copy(saturation = -30),
            "shadows" to defaults.copy(shadows = 40),
            "highlights" to defaults.copy(highlights = -45),
            "dark strength" to defaults.copy(ds = 80),
            "dark range" to defaults.copy(dr = 20),
            "light strength" to defaults.copy(ls = 70),
            "light range" to defaults.copy(lr = 85),
            "lighten" to defaults.copy(lightening = 60),
            "highlight reconstruction" to defaults.copy(highlightReconstruction = 1),
            "camera matrix and footprint compression" to defaults.copy(camMatrixUsed = 2),
            "EXR" to defaults.copy(exrMode = 1),
            "AgX without camera matrix" to defaults.copy(camMatrixUsed = 0, agx = 1),
            "profile transfer LUT" to defaults.copy(
                profileIndex = 5,
                tonemap = 2,
                transferFunction = "pow(max(x, 0.0), 0.45)",
                gamut = 2,
                gamma = 220
            ),
            "creative adjustments disabled" to defaults.copy(allowCreativeAdjustments = 0),
            "inactive sharpen masking" to defaults.copy(sharpenMasking = 50),
            "inactive chroma blur radius" to defaults.copy(chromaBlur = 3)
        )

        supportedReceipts.forEach { (name, settings) ->
            assertFalse("$name should remain on GPU preview", settings.requiresCpuProcessingPreview())
        }
    }

    @Test
    fun unsupportedSharpenAndChromaControlsRequireCpu() {
        val defaults = ColorGradingSettings()
        val unsupportedReceipts = listOf(
            "sharpen" to defaults.copy(sharpen = 1, sharpenMasking = 50),
            "chroma separation" to defaults.copy(chromaSeparation = 1, chromaBlur = 3)
        )

        unsupportedReceipts.forEach { (name, settings) ->
            assertTrue("$name should use CPU preview", settings.requiresCpuProcessingPreview())
        }
    }

    @Test
    fun defaultFullReceiptAndRawCorrectionChangesRemainGpuEligible() {
        assertFalse(ClipGradingData().requiresCpuProcessingPreview())

        val rawCorrectionReceipt = ClipGradingData(
            rawCorrection = RawCorrectionSettings(
                verticalStripes = 2,
                focusPixels = 1,
                badPixels = 2,
                chromaSmooth = 3,
                dualIso = 1,
                darkFrameEnabled = 1
            )
        )

        assertFalse(rawCorrectionReceipt.requiresCpuProcessingPreview())
    }

    @Test
    fun dualIsoHighlightReconstructionRequiresCpuDemosaicAnalysis() {
        assertTrue(
            ClipGradingData(
                rawCorrection = RawCorrectionSettings(dualIso = 1),
                colorGrading = ColorGradingSettings(highlightReconstruction = 1)
            ).requiresCpuProcessingPreview()
        )
        assertFalse(
            ClipGradingData(
                rawCorrection = RawCorrectionSettings(dualIso = 1)
            ).requiresCpuProcessingPreview()
        )
    }

    @Test
    fun activeExcludedModulesRequireCpu() {
        val cpuOnlyReceipts = listOf(
            "gradation curves" to ClipGradingData(
                curves = CurvesSettings(gradationCurve = "0;0;0.5;0.4;1;1;")
            ),
            "HSL" to ClipGradingData(
                hsl = HslSettings(hueVsSaturation = "0;0;0.5;0.2;1;0;")
            ),
            "LUT" to ClipGradingData(
                lut = LutSettings(enabled = true, name = "look.cube")
            ),
            "median denoiser" to ClipGradingData(
                effects = EffectsSettings(denoiserStrength = 10)
            ),
            "RBF denoiser" to ClipGradingData(
                effects = EffectsSettings(rbfDenoiserLuma = 10)
            ),
            "grain" to ClipGradingData(
                effects = EffectsSettings(grainStrength = 10)
            ),
            "toning" to ClipGradingData(
                effects = EffectsSettings(tone = 120, toningStrength = 25)
            ),
            "filter" to ClipGradingData(
                effects = EffectsSettings(filterEnabled = true, filterIndex = 1)
            ),
            "vignette" to ClipGradingData(
                effects = EffectsSettings(vignetteStrength = -20)
            ),
            "chromatic aberration" to ClipGradingData(
                effects = EffectsSettings(caRed = 2, caBlue = -2, caDesaturate = 10)
            ),
            "gradient" to ClipGradingData(
                effects = EffectsSettings(gradientEnabled = true, gradientExposure = 25)
            )
        )

        cpuOnlyReceipts.forEach { (name, grading) ->
            assertTrue("$name should use CPU preview", grading.requiresCpuProcessingPreview())
        }
    }

    @Test
    fun disabledCreativeAdjustmentsIgnoreCurvesHslAndToning() {
        val grading = ClipGradingData(
            colorGrading = ColorGradingSettings(allowCreativeAdjustments = 0),
            curves = CurvesSettings(gradationCurve = "0;0;0.5;0.4;1;1;"),
            hsl = HslSettings(hueVsHue = "0;0;0.5;0.1;1;0;"),
            effects = EffectsSettings(tone = 120, toningStrength = 50)
        )

        assertFalse(grading.requiresCpuProcessingPreview())
    }

    @Test
    fun inactiveEffectModifiersRemainGpuEligible() {
        val grading = ClipGradingData(
            effects = EffectsSettings(
                denoiserWindow = 5,
                rbfDenoiserRange = 80,
                grainLumaWeight = 100,
                tone = 180,
                filterIndex = 2,
                filterStrength = 50,
                vignetteRadius = 80,
                vignetteShape = 1,
                caRadius = 4,
                gradientExposure = 25,
                gradientContrast = 25,
                gradientStartX = 50,
                gradientStartY = 50,
                gradientLength = 75,
                gradientAngle = 90
            )
        )

        assertFalse(grading.requiresCpuProcessingPreview())
    }

    @Test
    fun disabledCreativeAdjustmentsDoNotHideOtherExcludedModules() {
        val creativeDisabled = ColorGradingSettings(allowCreativeAdjustments = 0)

        assertTrue(
            ClipGradingData(
                colorGrading = creativeDisabled,
                lut = LutSettings(enabled = true, name = "look.cube")
            ).requiresCpuProcessingPreview()
        )
        assertTrue(
            ClipGradingData(
                colorGrading = creativeDisabled,
                effects = EffectsSettings(grainStrength = 10)
            ).requiresCpuProcessingPreview()
        )
    }
}
