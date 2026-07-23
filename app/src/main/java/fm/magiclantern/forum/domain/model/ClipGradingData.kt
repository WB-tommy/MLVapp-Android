package fm.magiclantern.forum.domain.model

import android.os.Parcelable
import androidx.compose.runtime.Stable
import kotlinx.parcelize.Parcelize
import kotlin.math.roundToInt

/**
 * Main container for all clip grading settings
 * Maps to desktop XML schema for cross-platform compatibility
 */
/**
 * Debayer algorithm modes - matches desktop comboBoxDebayer indices
 * and native setDebayerMode() values
 */
enum class DebayerAlgorithm(val displayName: String, val nativeId: Int) {
    NONE("None (monochrome)", 0),
    SIMPLE("Simple", 1),
    BILINEAR("Bilinear", 2),
    LMMSE("LMMSE", 3),
    IGV("IGV", 4),
    AMAZE("AMaZE", 5),
    AHD("AHD", 6),
    RCD("RCD", 7),
    DCB("DCB", 8);
    
    companion object {
        fun fromNativeId(id: Int): DebayerAlgorithm = 
            entries.find { it.nativeId == id } ?: AMAZE
    }
}

@Stable
data class ClipGradingData(
    // Processing Settings
    val debayerMode: DebayerAlgorithm = DebayerAlgorithm.AMAZE,
    
    // Raw Correction Module
    val rawCorrection: RawCorrectionSettings = RawCorrectionSettings(),
    
    // Color Grading Module
    val colorGrading: ColorGradingSettings = ColorGradingSettings(),
    
    // Cut In / Cut Out markers (1-based frame numbers, matching desktop convention)
    // cutIn = 1 means first frame (default), cutOut = 0 means "not set" (use last frame)
    val cutIn: Int = 1,
    val cutOut: Int = 0,
    
    // Advanced Modules (stubs for now)
    val curves: CurvesSettings = CurvesSettings(),
    val hsl: HslSettings = HslSettings(),
    val lut: LutSettings = LutSettings(),
    val effects: EffectsSettings = EffectsSettings()
)

/**
 * Kotlin-side representation of the upstream Dual ISO control contract.
 *
 * Automatic values intentionally use the same out-of-range sentinels as the
 * native implementation so a receipt can distinguish "auto" from a manual
 * correction of zero.
 */
object DualIsoSettingsContract {
    const val MODE_OFF = 0
    const val MODE_HQ = 1
    const val LEGACY_MODE_PREVIEW = 2

    const val PATTERN_AUTO = 0
    const val PATTERN_FIRST = PATTERN_AUTO
    const val PATTERN_LAST = 5
    const val PATTERN_AUTO_EVERY_FRAME = 5

    const val MATCH_ISO = 1
    const val MATCH_HISTOGRAM = 2

    const val EV_AUTO = 1f
    const val EV_MIN = -6f
    const val EV_MAX = 0f
    const val EV_STEP = 0.005f
    const val EV_MANUAL_DEFAULT = -3f

    const val BLACK_DELTA_AUTO = -1
    const val BLACK_DELTA_MIN = 0
    const val BLACK_DELTA_MAX = 100
}

/** Values finalized by one successfully presented Dual ISO preview frame. */
@Stable
data class ResolvedDualIsoValues(
    val pattern: Int,
    val matchMethod: Int,
    val evCorrection: Float,
    val blackDelta: Int
)

/** Validate and decode the native preview snapshot without changing the receipt. */
fun FloatArray?.resolvedDualIsoValues(): ResolvedDualIsoValues? {
    if (this == null || size < 5 || this[0] < 0.5f) return null

    fun finiteInt(index: Int): Int? = this[index]
        .takeIf(Float::isFinite)
        ?.roundToInt()

    val pattern = finiteInt(1)?.takeIf {
        it in DualIsoSettingsContract.PATTERN_FIRST..DualIsoSettingsContract.PATTERN_LAST
    } ?: return null
    val matchMethod = finiteInt(2)?.takeIf {
        it == DualIsoSettingsContract.MATCH_ISO ||
            it == DualIsoSettingsContract.MATCH_HISTOGRAM
    } ?: return null
    val evCorrection = this[3].takeIf {
        it.isFinite() && it in DualIsoSettingsContract.EV_MIN..DualIsoSettingsContract.EV_MAX
    } ?: return null
    val blackDelta = finiteInt(4)?.takeIf {
        it in DualIsoSettingsContract.BLACK_DELTA_MIN..DualIsoSettingsContract.BLACK_DELTA_MAX
    } ?: return null

    return ResolvedDualIsoValues(
        pattern = pattern,
        matchMethod = matchMethod,
        evCorrection = evCorrection,
        blackDelta = blackDelta
    )
}

/**
 * Raw correction settings (from desktop lines 51-70)
 */
@Parcelize
@Stable
data class RawCorrectionSettings(
    val enabled: Boolean = true,              // rawFixesEnabled
    val verticalStripes: Int = 0,             // 0=Off, 1=Normal, 2=Force
    val focusPixels: Int = 0,                 // 0=Off, 1=On, 2=CropRec
    val fpiMethod: Int = 0,                   // Focus pixel interpolation method
    val badPixels: Int = 0,                   // 0=Off, 1=Auto, 2=Force, 3=Map
    val bpsMethod: Int = 0,                   // Bad pixel search method
    val bpiMethod: Int = 0,                   // Bad pixel interpolation method
    val chromaSmooth: Int = 0,                // 0=Off, 2=2x2, 3=3x3, 5=5x5
    val patternNoise: Int = 0,                // Fix pattern noise (0, 1)
    val deflickerTarget: Int = 0,             // Deflicker value
    val dualIso: Int = 0,                     // 0=Off, 1=HQ; legacy 2 migrates to HQ
    val dualIsoForced: Boolean = false,       // Override ISO detection
    val dualIsoPattern: Int = DualIsoSettingsContract.PATTERN_AUTO,
    val dualIsoMatchMethod: Int = DualIsoSettingsContract.MATCH_ISO,
    val dualIsoEvCorrection: Float = DualIsoSettingsContract.EV_AUTO,
    val dualIsoBlackDelta: Int = DualIsoSettingsContract.BLACK_DELTA_AUTO,
    val dualIsoInterpolation: Int = 0,        // 0=Amaze, 1=Mean23
    val dualIsoAliasMap: Boolean = false,     // Upstream default: off
    val dualIsoFrBlending: Boolean = true,    // Required for safe full-res output
    // Legacy field names: these are general RAW processing levels, not DISO delta.
    val dualIsoWhite: Int = 65013,
    val dualIsoBlack: Int = 4096,
    val darkFrameFileName: String = "No file selected",  // Dark frame file path
    val darkFrameUri: String = "",            // Persistable SAF URI for handle restore
    val darkFrameEnabled: Int = 0             // 0=Off, 1=Ext, 2=Int
) : Parcelable

/** Canonicalize legacy/corrupt receipt values before applying them to native state. */
fun RawCorrectionSettings.normalizedDualIso(): RawCorrectionSettings {
    val normalizedMode = when (dualIso) {
        DualIsoSettingsContract.MODE_HQ,
        DualIsoSettingsContract.LEGACY_MODE_PREVIEW -> DualIsoSettingsContract.MODE_HQ
        else -> DualIsoSettingsContract.MODE_OFF
    }
    val normalizedMatchMethod = if (dualIsoForced) {
        DualIsoSettingsContract.MATCH_HISTOGRAM
    } else if (dualIsoMatchMethod == DualIsoSettingsContract.MATCH_HISTOGRAM) {
        DualIsoSettingsContract.MATCH_HISTOGRAM
    } else {
        DualIsoSettingsContract.MATCH_ISO
    }
    val normalizedEv = if (dualIsoEvCorrection == DualIsoSettingsContract.EV_AUTO) {
        DualIsoSettingsContract.EV_AUTO
    } else {
        dualIsoEvCorrection
            .takeIf(Float::isFinite)
            ?.coerceIn(DualIsoSettingsContract.EV_MIN, DualIsoSettingsContract.EV_MAX)
            ?: DualIsoSettingsContract.EV_AUTO
    }
    val normalizedBlackDelta = if (
        dualIsoBlackDelta == DualIsoSettingsContract.BLACK_DELTA_AUTO
    ) {
        DualIsoSettingsContract.BLACK_DELTA_AUTO
    } else {
        dualIsoBlackDelta.coerceIn(
            DualIsoSettingsContract.BLACK_DELTA_MIN,
            DualIsoSettingsContract.BLACK_DELTA_MAX
        )
    }

    return copy(
        dualIso = normalizedMode,
        dualIsoPattern = dualIsoPattern.coerceIn(
            DualIsoSettingsContract.PATTERN_FIRST,
            DualIsoSettingsContract.PATTERN_LAST
        ),
        dualIsoMatchMethod = normalizedMatchMethod,
        dualIsoEvCorrection = normalizedEv,
        dualIsoBlackDelta = normalizedBlackDelta,
        dualIsoInterpolation = dualIsoInterpolation.coerceIn(0, 1),
        dualIsoFrBlending = true
    )
}

/**
 * Normalize settings for a fresh export decoder. External dark-frame data is
 * owned by the preview handle and is not available to the export handle, so it
 * must not be advertised as active there. Automatic Dual ISO sentinels remain
 * automatic and will be resolved again by the export decoder.
 */
fun RawCorrectionSettings.normalizedForExport(): RawCorrectionSettings {
    val normalized = normalizedDualIso()
    return if (normalized.darkFrameEnabled == 1) {
        normalized.copy(darkFrameEnabled = 0)
    } else {
        normalized.copy(
            darkFrameEnabled = if (normalized.darkFrameEnabled == 2) 2 else 0
        )
    }
}

/** Apply the upstream force transition and re-arm all dependent auto values. */
fun RawCorrectionSettings.withDualIsoForced(isForced: Boolean): RawCorrectionSettings =
    copy(
        dualIsoForced = isForced,
        dualIsoPattern = DualIsoSettingsContract.PATTERN_AUTO,
        dualIsoMatchMethod = if (isForced) {
            DualIsoSettingsContract.MATCH_HISTOGRAM
        } else {
            DualIsoSettingsContract.MATCH_ISO
        },
        dualIsoEvCorrection = DualIsoSettingsContract.EV_AUTO,
        dualIsoBlackDelta = DualIsoSettingsContract.BLACK_DELTA_AUTO
    ).normalizedDualIso()

/** Force applies only to legacy clips without a valid DISO metadata block. */
fun RawCorrectionSettings.reconciledWithDualIsoMetadata(
    dualIsoValid: Boolean
): RawCorrectionSettings {
    val normalized = normalizedDualIso()
    return if (dualIsoValid && normalized.dualIsoForced) {
        normalized.withDualIsoForced(false)
    } else {
        normalized
    }
}

/**
 * Upstream re-runs histogram/forced matching when its row pattern changes.
 * ISO matching keeps explicit correction values because they are independent
 * of the detected line arrangement.
 */
fun RawCorrectionSettings.withDualIsoPattern(pattern: Int): RawCorrectionSettings {
    val normalized = normalizedDualIso()
    val rearmCorrections = normalized.dualIsoForced ||
        normalized.dualIsoMatchMethod == DualIsoSettingsContract.MATCH_HISTOGRAM
    return normalized.copy(
        dualIsoPattern = pattern,
        dualIsoEvCorrection = if (rearmCorrections) {
            DualIsoSettingsContract.EV_AUTO
        } else {
            normalized.dualIsoEvCorrection
        },
        dualIsoBlackDelta = if (rearmCorrections) {
            DualIsoSettingsContract.BLACK_DELTA_AUTO
        } else {
            normalized.dualIsoBlackDelta
        }
    ).normalizedDualIso()
}

/**
 * Resolve auto values from the last successfully rendered preview frame for
 * export without changing the live UI/native receipt.
 */
fun RawCorrectionSettings.withResolvedDualIsoState(
    snapshot: FloatArray?
): RawCorrectionSettings {
    val normalized = normalizedForExport()
    if (normalized.dualIso != DualIsoSettingsContract.MODE_HQ) return normalized

    // The preview result includes subtraction unavailable to the fresh export
    // handle. Re-run every automatic value after normalizing DF_EXT to Off.
    if (darkFrameEnabled == 1) return normalized

    val resolved = snapshot.resolvedDualIsoValues() ?: return normalized

    val resolvedPattern = if (
        normalized.dualIsoPattern == DualIsoSettingsContract.PATTERN_AUTO
    ) {
        resolved.pattern
    } else {
        normalized.dualIsoPattern
    }

    return normalized.copy(
        dualIsoPattern = resolvedPattern,
        dualIsoEvCorrection = if (
            normalized.dualIsoEvCorrection == DualIsoSettingsContract.EV_AUTO
        ) {
            resolved.evCorrection
        } else {
            normalized.dualIsoEvCorrection
        },
        dualIsoBlackDelta = if (
            normalized.dualIsoBlackDelta == DualIsoSettingsContract.BLACK_DELTA_AUTO
        ) {
            resolved.blackDelta
        } else {
            normalized.dualIsoBlackDelta
        }
    ).normalizedForExport()
}

/**
 * Color grading settings (from desktop lines 4-50)
 */
@Parcelize
@Stable
data class ColorGradingSettings(
    // Basic adjustments
    val exposure: Float = 0f,                 // Exposure stops (-4.0 to 4.0)
    val contrast: Int = 0,                    // Contrast (-100 to 100)
    val pivot: Int = 75,                      // Contrast pivot (0-100)
    val temperature: Int = 6500,              // White balance kelvin (2000-10000)
    val tint: Int = 0,                        // Tint (-100 to 100)
    val saturation: Int = 0,                  // Saturation (-100 to 100)
    val vibrance: Int = 0,                    // Vibrance (-100 to 100)
    val clarity: Int = 0,                     // Clarity (-100 to 100)
    
    // Shadows/Highlights
    val shadows: Int = 0,                     // Shadows (-100 to 100)
    val highlights: Int = 0,                  // Highlights (-100 to 100)
    val ds: Int = 20,                         // Dark strength (0-100)
    val dr: Int = 70,                         // Dark range (0-100)
    val ls: Int = 0,                          // Light strength (0-100)
    val lr: Int = 50,                         // Light range (0-100)
    val lightening: Int = 0,                  // Lighten slider (0-100; native 0.0-0.6)
    
    // Processing options
    val sharpen: Int = 0,                     // Sharpen (0-100)
    val sharpenMasking: Int = 0,              // Sharpen masking (0-100)
    val chromaBlur: Int = 0,                  // Chroma blur radius
    val highlightReconstruction: Int = 0,     // Highlight reconstruction (0-1)
    val camMatrixUsed: Int = 1,               // Use camera matrix (0-2)
    val chromaSeparation: Int = 0,            // Chroma separation (0-1)
    
    // Profile preset selection (0 = "Select Preset...", 1-13 = actual presets)
    val profileIndex: Int = 0,
    
    // Tone mapping
    val tonemap: Int = 1,                     // Tonemap function (0-2)
    val transferFunction: String = "(x < 0.0) ? 0 : pow(x / (1.0 + x), 1/3.15)",
    val gamut: Int = 0,                       // Color gamut (0-2)
    val gamma: Int = 315,                     // Gamma power (multiplied by 100)
    val allowCreativeAdjustments: Int = 1,    // Allow creative adjustments with log
    
    // Advanced options
    val exrMode: Int = 0,                     // EXR mode (0-1)
    val agx: Int = 1                          // AgX mode (0-1)
) : Parcelable

/** Whether this Processing/Profile receipt requires the complete CPU preview path. */
fun ColorGradingSettings.requiresCpuProcessingPreview(): Boolean =
    sharpen != 0 ||
        chromaSeparation != 0

/**
 * Gradation curves settings (stub - complex data structure)
 */
@Stable
data class CurvesSettings(
    val gradationCurve: String = "1e-05;1e-05;1;1;?1e-05;1e-05;1;1;?1e-05;1e-05;1;1;?1e-05;1e-05;1;1;"
)

/**
 * HSL adjustments settings (from desktop lines 18-21)
 */
@Stable
data class HslSettings(
    val hueVsHue: String = "0;0;1;0;",
    val hueVsSaturation: String = "0;0;1;0;",
    val hueVsLuminance: String = "0;0;1;0;",
    val lumaVsSaturation: String = "0;0;1;0;"
)

/**
 * LUT settings (from desktop lines 74-76)
 */
@Stable
data class LutSettings(
    val enabled: Boolean = false,             // lutEnabled
    val name: String = "",                    // lutName
    val strength: Int = 100                   // lutStrength (0-100)
)

/**
 * Effects settings (grain, vignette, CA, gradient)
 */
@Stable
data class EffectsSettings(
    // Denoiser
    val denoiserStrength: Int = 0,            // 0-100
    val denoiserWindow: Int = 3,              // Window size
    val rbfDenoiserLuma: Int = 0,             // RBF denoiser luma
    val rbfDenoiserChroma: Int = 0,           // RBF denoiser chroma
    val rbfDenoiserRange: Int = 40,           // RBF denoiser range
    
    // Grain
    val grainStrength: Int = 0,               // 0-100
    val grainLumaWeight: Int = 0,             // 0-100
    
    // Toning
    val tone: Int = 0,                        // Tone (hue)
    val toningStrength: Int = 0,              // Toning strength (0-100)
    
    // Filter/LUT
    val filterEnabled: Boolean = false,       // Filter on/off
    val filterIndex: Int = 0,                 // Filter index
    val filterStrength: Int = 100,            // Filter strength (0-100)
    
    // Vignette
    val vignetteStrength: Int = 0,            // Vignette strength (-100 to 100)
    val vignetteRadius: Int = 20,             // Vignette radius (0-100)
    val vignetteShape: Int = 0,               // Vignette shape (0-1)
    
    // Chromatic Aberration
    val caRed: Int = 0,                       // CA red shift
    val caBlue: Int = 0,                      // CA blue shift
    val caDesaturate: Int = 0,                // CA desaturate (0-100)
    val caRadius: Int = 1,                    // CA radius
    
    // Gradient
    val gradientEnabled: Boolean = false,     // Gradient on/off
    val gradientExposure: Int = 0,            // Gradient exposure
    val gradientContrast: Int = 0,            // Gradient contrast
    val gradientStartX: Int = 0,              // Gradient start X (0-100)
    val gradientStartY: Int = 0,              // Gradient start Y (0-100)
    val gradientLength: Int = 1,              // Gradient length (0-100)
    val gradientAngle: Int = 0                // Gradient angle (0-360)
)

/** Whether any active receipt stage is still absent from experimental RAW GPU preview. */
fun ClipGradingData.requiresCpuProcessingPreview(): Boolean {
    if (colorGrading.requiresCpuProcessingPreview()) return true
    if (colorGrading.highlightReconstruction != 0 && rawCorrection.dualIso != 0) {
        // Desktop derives a per-frame green peak after CPU demosaic for this
        // combination; the corrected-Bayer GPU path cannot reproduce it yet.
        return true
    }

    val creativeAdjustmentsEnabled = colorGrading.allowCreativeAdjustments != 0
    if (creativeAdjustmentsEnabled &&
        (curves != CurvesSettings() || hsl != HslSettings())
    ) {
        return true
    }
    if (lut.enabled) return true

    // Other RAW corrections run on CPU before upload and remain GPU eligible.
    return effects.hasActiveCpuOnlyEffect(creativeAdjustmentsEnabled)
}

private fun EffectsSettings.hasActiveCpuOnlyEffect(
    creativeAdjustmentsEnabled: Boolean
): Boolean =
    denoiserStrength != 0 ||
        rbfDenoiserLuma != 0 ||
        rbfDenoiserChroma != 0 ||
        grainStrength != 0 ||
        (creativeAdjustmentsEnabled && toningStrength != 0) ||
        filterEnabled ||
        vignetteStrength != 0 ||
        caRed != 0 ||
        caBlue != 0 ||
        caDesaturate != 0 ||
        (gradientEnabled && (gradientExposure != 0 || gradientContrast != 0))
