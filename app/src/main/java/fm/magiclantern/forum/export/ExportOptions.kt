package fm.magiclantern.forum.export

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Bundle of export-specific options passed directly to the native layer.
 * Keep fields aligned with export_options_t in the JNI bridge.
 */
@Parcelize
data class ExportOptions(
    val codec: ExportCodec,
    val codecOption: Int,
    val cdngVariant: CdngVariant,
    val cdngNaming: CdngNaming,
    val includeAudio: Boolean,
    val enableRawFixes: Boolean,
    val frameRateOverrideEnabled: Boolean,
    val frameRateValue: Float,
    val sourceFileName: String,
    val clipUriPath: String,
    val audioTempDir: String,
    val stretchFactorX: Float = 1.0f,
    val stretchFactorY: Float = 1.0f,
    // ProRes options
    val proResProfile: ProResProfile = ProResProfile.PRORES_422_HQ,
    val proResEncoder: ProResEncoder = ProResEncoder.FFMPEG_KOSTYA,
    // H.264 options
    val h264Quality: H264Quality = H264Quality.HIGH,
    val h264Container: H264Container = H264Container.MOV,
    // H.265/HEVC options
    val h265BitDepth: H265BitDepth = H265BitDepth.BIT_10,
    val h265Quality: H265Quality = H265Quality.HIGH,
    val h265Container: H265Container = H265Container.MOV,
    // PNG options
    val pngBitDepth: PngBitDepth = PngBitDepth.BIT_16,
    // DNxHR options
    val dnxhrProfile: DnxhrProfile = DnxhrProfile.HQ,
    // DNxHD options
    val dnxhdProfile: DnxhdProfile = DnxhdProfile.P1080_10BIT,
    // VP9 options
    val vp9Quality: Vp9Quality = Vp9Quality.GOOD,
    // Processing options
    val debayerQuality: DebayerQuality = DebayerQuality.AMAZE,
    val smoothing: SmoothingOption = SmoothingOption.OFF,
    val resize: ResizeSettings = ResizeSettings(),
    val hdrBlending: Boolean = false,
    val antiAliasing: Boolean = false,
    // Benchmark/Diagnostics options
    val forceHardware: Boolean = false,
    val forceSoftware: Boolean = false
) : Parcelable
