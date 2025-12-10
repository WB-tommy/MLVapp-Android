package fm.magiclantern.forum.export

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Strongly-typed export settings model used by the settings UI and the export service.
 * The shape mirrors the desktop application's dialog so future options can map cleanly.
 */
@Parcelize
data class ExportSettings(
    val codec: ExportCodec = ExportCodec.CINEMA_DNG,
    val cdngNaming: CdngNaming = CdngNaming.DEFAULT,
    val cdngVariant: CdngVariant = CdngVariant.UNCOMPRESSED,
    val proResProfile: ProResProfile = ProResProfile.PRORES_422_HQ,
    val proResEncoder: ProResEncoder = ProResEncoder.FFMPEG_KOSTYA,
    // H.264 options
    val h264Quality: H264Quality = H264Quality.HIGH,
    val h264Container: H264Container = H264Container.MOV,
    // H.265 options
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
    // General settings
    val debayerQuality: DebayerQuality = DebayerQuality.AMAZE,
    val smoothing: SmoothingOption = SmoothingOption.OFF,
    val resize: ResizeSettings = ResizeSettings(),
    val frameRate: FrameRateOverride = FrameRateOverride(),
    val hdrBlending: Boolean = false,
    val includeAudio: Boolean = true,
    val antiAliasing: Boolean = false
) : Parcelable {
    val allowsResize: Boolean
        get() = codec.allowsResize

    val requiresRawProcessing: Boolean
        get() = codec.requiresProcessing

    val allowsAudioToggle: Boolean
        get() = codec.allowsAudioToggle

    val allowsFrameRateOverride: Boolean
        get() = codec.allowsFrameRateOverride
}

enum class ExportCodec(
    val displayName: String,
    internal val allowsResize: Boolean,
    internal val requiresProcessing: Boolean,
    internal val allowsAudioToggle: Boolean,
    internal val allowsFrameRateOverride: Boolean
) {
    CINEMA_DNG(
        displayName = "CinemaDNG",
        allowsResize = false,
        requiresProcessing = false,
        allowsAudioToggle = true,
        allowsFrameRateOverride = true
    ),
    PRORES(
        displayName = "ProRes",
        allowsResize = true,
        requiresProcessing = true,
        allowsAudioToggle = true,
        allowsFrameRateOverride = true
    ),
    H264(
        displayName = "H.264",
        allowsResize = true,
        requiresProcessing = true,
        allowsAudioToggle = true,
        allowsFrameRateOverride = true
    ),
    H265(
        displayName = "H.265/HEVC",
        allowsResize = true,
        requiresProcessing = true,
        allowsAudioToggle = true,
        allowsFrameRateOverride = true
    ),
    TIFF(
        displayName = "TIFF (16-bit)",
        allowsResize = true,
        requiresProcessing = true,
        allowsAudioToggle = true,
        allowsFrameRateOverride = false
    ),
    PNG(
        displayName = "PNG",
        allowsResize = true,
        requiresProcessing = true,
        allowsAudioToggle = true,
        allowsFrameRateOverride = false
    ),
    JPEG2000(
        displayName = "JPEG 2000",
        allowsResize = true,
        requiresProcessing = true,
        allowsAudioToggle = true,
        allowsFrameRateOverride = false
    ),
    DNXHR(
        displayName = "DNxHR",
        allowsResize = true,
        requiresProcessing = true,
        allowsAudioToggle = true,
        allowsFrameRateOverride = true
    ),
    DNXHD(
        displayName = "DNxHD",
        allowsResize = true,
        requiresProcessing = true,
        allowsAudioToggle = true,
        allowsFrameRateOverride = true
    ),
    VP9(
        displayName = "VP9/WebM",
        allowsResize = true,
        requiresProcessing = true,
        allowsAudioToggle = true,
        allowsFrameRateOverride = true
    ),
    AUDIO_ONLY(
        displayName = "Audio Only",
        allowsResize = false,
        requiresProcessing = false,
        allowsAudioToggle = false,
        allowsFrameRateOverride = false
    );

    companion object {
        val defaultOrder: List<ExportCodec> = listOf(
            CINEMA_DNG,
            PRORES,
            H264,
            H265,
            DNXHR,
            DNXHD,
            VP9,
            TIFF,
            PNG,
            JPEG2000,
            AUDIO_ONLY
        )
    }
}

enum class CdngNaming(val displayName: String) {
    DEFAULT("Default Naming Scheme"),
    DAVINCI_RESOLVE("DaVinci Resolve Naming Scheme")
}

enum class CdngVariant(val displayName: String, val nativeId: Int) {
    UNCOMPRESSED("Uncompressed", 0),
    LOSSLESS("Lossless", 1),
    FAST("Fast", 2)
}

enum class ProResProfile(val displayName: String) {
    PRORES_422_PROXY("422 Proxy"),
    PRORES_422_LT("422 LT"),
    PRORES_422_STANDARD("422"),
    PRORES_422_HQ("422 HQ"),
    PRORES_4444("4444"),
    PRORES_4444_XQ("4444 XQ")
}

enum class ProResEncoder(val displayName: String) {
    FFMPEG_KOSTYA("ffmpeg Kostya"),
    FFMPEG_ANATOLYI("ffmpeg Anatolyi")
}

// H.264 Options
enum class H264Quality(val displayName: String) {
    HIGH("High Quality (CRF 14)"),
    MEDIUM("Medium Quality (CRF 24)")
}

enum class H264Container(val displayName: String) {
    MOV("QuickTime (.mov)"),
    MP4("MPEG-4 (.mp4)"),
    MKV("Matroska (.mkv)")
}

// H.265/HEVC Options
enum class H265BitDepth(val displayName: String) {
    BIT_8("8-bit"),
    BIT_10("10-bit"),
    BIT_12("12-bit 4:4:4")
}

enum class H265Quality(val displayName: String) {
    HIGH("High Quality (CRF 18)"),
    MEDIUM("Medium Quality (CRF 24)")
}

enum class H265Container(val displayName: String) {
    MOV("QuickTime (.mov)"),
    MP4("MPEG-4 (.mp4)"),
    MKV("Matroska (.mkv)")
}

// PNG Options
enum class PngBitDepth(val displayName: String) {
    BIT_16("16-bit"),
    BIT_8("8-bit")
}

// DNxHR Options
enum class DnxhrProfile(val displayName: String) {
    LB("LB - Low Bandwidth"),
    SQ("SQ - Standard Quality"),
    HQ("HQ - High Quality"),
    HQX("HQX - High Quality 10-bit"),
    P444("444 - 4:4:4 10-bit")
}

// DNxHD Options (matches desktop presets)
enum class DnxhdProfile(val displayName: String) {
    P1080_10BIT("1080p 10-bit"),
    P1080_8BIT("1080p 8-bit"),
    P720_10BIT("720p 10-bit"),
    P720_8BIT("720p 8-bit")
}

// VP9 Options
enum class Vp9Quality(val displayName: String) {
    GOOD("Good Quality (CRF 18)"),
    LOSSLESS("Lossless")
}

enum class DebayerQuality(val displayName: String) {
    AMAZE("AMaZE"),
    LMMSE("LMMSE"),
    VNG_4("VNG4")
}

enum class SmoothingOption(val displayName: String) {
    OFF("Off"),
    ONE_PASS("1-Pass"),
    THREE_PASS("3-Pass"),
    THREE_PASS_USM("3-Pass USM"),
    THREE_PASS_USM_BB("3-Pass USM + Black Border")
}

enum class ScalingAlgorithm(val displayName: String) {
    BICUBIC("Bicubic"),
    BILINEAR("Bilinear"),
    SINC("Sinc"),
    LANCZOS("Lanczos"),
    BSPLINE("B-Spline")
}

@Parcelize
data class ResizeSettings(
    val enabled: Boolean = false,
    val width: Int = 1920,
    val height: Int = 1080,
    val lockAspectRatio: Boolean = true,
    val algorithm: ScalingAlgorithm = ScalingAlgorithm.LANCZOS
) : Parcelable

@Parcelize
data class FrameRateOverride(
    val enabled: Boolean = false,
    val value: Float = FrameRatePreset.FPS_23976.value
) : Parcelable

enum class FrameRatePreset(val displayLabel: String, val value: Float) {
    FPS_23976("23.976", 23.976f),
    FPS_24("24.0", 24f),
    FPS_25("25.0", 25f),
    FPS_2997("29.97", 29.97f),
    FPS_30("30.0", 30f),
    FPS_50("50.0", 50f),
    FPS_5994("59.94", 59.94f),
    FPS_60("60.0", 60f)
}
