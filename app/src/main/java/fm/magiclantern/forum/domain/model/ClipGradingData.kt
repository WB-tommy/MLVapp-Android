package fm.magiclantern.forum.domain.model

import android.os.Parcelable
import androidx.compose.runtime.Stable
import kotlinx.parcelize.Parcelize

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
    
    // Advanced Modules (stubs for now)
    val curves: CurvesSettings = CurvesSettings(),
    val hsl: HslSettings = HslSettings(),
    val lut: LutSettings = LutSettings(),
    val effects: EffectsSettings = EffectsSettings()
)

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
    val dualIso: Int = 0,                     // 0=Off, 1=On, 2=Preview
    val dualIsoForced: Boolean = false,       // Override ISO detection
    val dualIsoInterpolation: Int = 0,        // 0=Amaze, 1=Mean23
    val dualIsoAliasMap: Boolean = true,      // Alias map on/off
    val dualIsoFrBlending: Boolean = true,    // Fullres blending on/off
    val dualIsoWhite: Int = 65013,            // Dual ISO white level
    val dualIsoBlack: Int = 4096,             // Dual ISO black level
    val darkFrameFileName: String = "No file selected",  // Dark frame file path
    val darkFrameEnabled: Int = 0             // 0=Off, 1=Ext, 2=Int
) : Parcelable

/**
 * Color grading settings (from desktop lines 4-50)
 */
@Stable
data class ColorGradingSettings(
    // Basic adjustments
    val exposure: Int = 0,                    // Exposure stops (-200 to 200)
    val contrast: Int = 0,                    // Contrast (-100 to 100)
    val pivot: Int = 75,                      // Contrast pivot (0-100)
    val temperature: Int = 6500,              // White balance kelvin (2500-10000)
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
    val lightening: Int = 0,                  // Lightening (0-60)
    
    // Processing options
    val sharpen: Int = 0,                     // Sharpen (0-100)
    val sharpenMasking: Int = 0,              // Sharpen masking (0-100)
    val chromaBlur: Int = 0,                  // Chroma blur radius
    val highlightReconstruction: Int = 0,     // Highlight reconstruction (0-1)
    val camMatrixUsed: Int = 0,               // Use camera matrix (0-1)
    val chromaSeparation: Int = 0,            // Chroma separation (0-1)
    
    // Tone mapping
    val tonemap: Int = 1,                     // Tonemap function (0-2)
    val transferFunction: String = "(x < 0.0) ? 0 : pow(x / (1.0 + x), 1/3.15)",
    val gamut: Int = 0,                       // Color gamut (0-2)
    val gamma: Int = 315,                     // Gamma power (multiplied by 100)
    val allowCreativeAdjustments: Int = 1,    // Allow creative adjustments with log
    
    // Advanced options
    val exrMode: Int = 0,                     // EXR mode (0-1)
    val agx: Int = 1                          // AgX mode (0-1)
)

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
