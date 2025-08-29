package fm.forum.mlvapp.data

import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.ImageBitmap
import java.nio.FloatBuffer
import java.time.LocalDateTime

@Stable
data class PreviewData(
    val cameraName: String,
    val width: Int = 0,
    val height: Int = 0,
    val thumbnail: ByteArray,
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MLVFile

        return (cameraName ?: width) == (other.cameraName ?: other.width)
    }

    override fun hashCode(): Int {
        return (cameraName ?: width).hashCode()
    }
}

@Stable
data class MLVFile(
    val uri: Uri,
    val fileName: String,
    val cameraName: String,
    val width: Int = 0,
    val height: Int = 0,
    val thumbnail: ImageBitmap,

    val lens: String? = null,
    val duration: String? = null,
    val focalLength: String? = null,
    val shutter: String? = null,
    val aperture: String? = null,
    val iso: Int? = null,
    val dualISO: Boolean? = null,
    val bitDepth: Int? = null,
    val dateTime: LocalDateTime? = null,
    val audioInfo: String? = null,
    val size: Long? = null,
    val dataRate: Long? = null,

    val filenum: Int? = null,
    val blockNum: Long? = null,
    val isActive: Boolean? = null,

    // MLV Headers data
    val mlviHeader: MLVIHeader? = null,
    val rawiHeader: RAWIHeader? = null,
    val rawcHeader: RAWCHeader? = null,
    val idntHeader: IDNTHeader? = null,
    val expoHeader: EXPOHeader? = null,
    val lensHeader: LENSHeader? = null,
    val elnsHeader: ELNSHeader? = null,
    val rtciHeader: RTCIHeader? = null,
    val wbalHeader: WBALHeader? = null,
    val waviHeader: WAVIHeader? = null,
    val disoHeader: DISOHeader? = null,
    val infoHeader: INFOHeader? = null,
    val stylHeader: STYLHeader? = null,
    val versHeader: VERSHeader? = null,
    val darkHeader: DARKHeader? = null,
    val vidfHeader: VIDFHeader? = null,
    val audfHeader: AUDFHeader? = null,

    val infoString: String? = "",

    // Core MLV processing objects
    val cameraIdObj: CameraId? = null,

    // Dark frame info
    val darkFrameOffset: Long? = 0L,

    // Black and white level
    val originalBlackLevel: Int = 0,
    val originalWhiteLevel: Int = 0,

    // Video info
    val realFrameRate: Double? = 0.0,
    val frameRate: Double? = 0.0,
    val frames: Int? = null,
    val frameSize: Int? = null,
    val compressionType: Int? = null,
    val videoIndex: List<FrameIndex>? = emptyList(),

    // Audio info
    val audios: Int? = 0,
    val audioSize: Long? = 0L,
    val audioBufferSize: Long? = 0L,
    val audioIndex: List<FrameIndex>? = emptyList(),

    // Version info
    val versBlocks: UInt? = 0u,
    val versIndex: List<FrameIndex>? = emptyList(),

    // Image processing object pointer (it is to be made separately)
    val processingObj: ProcessingObject? = null,
    val llrawprocObj: LLRawprocObject? = null,

    // Lossless raw data bit depth
    val losslessBpp: Int? = 0,

    // CA correction
    val caRed: Float? = 0.0f,
    val caBlue: Float? = 0.0f,

    // CPU cache settings (temporary for development)
    val isCaching: Boolean? = false,
    val cacheThreadCount: Int? = 0,
    val cacheNext: Long? = 0L,
    val stopCaching: Boolean? = false,
    val useAmaze: Boolean? = false,
    val cacheLimitBytes: Long? = 0L,
    val cacheLimitFrames: Long? = 0L,
    val cacheLimitMb: Long? = 0L,
    val cacheStartFrame: Long? = 0L,
    val currentCachedFrameActive: Boolean? = false,
    val currentCachedFrame: Long? = 0L,
    val timesRequested: Int? = 0,
    val cpuCores: Int? = 4,

    // GPU acceleration settings (for future use)
    val useGpuAcceleration: Boolean? = false,
    val debayerOnGpu: Boolean? = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MLVFile

        return (uri ?: fileName) == (other.uri ?: other.fileName)
    }

    override fun hashCode(): Int {
        return (uri ?: fileName).hashCode()
    }
}

// MLV Header data classes
data class MLVIHeader(
    val videoClass: UShort = 0u,
    val sourceFpsNom: UInt = 0u,
    val sourceFpsDenom: UInt = 0u,
    val fileGuid: ULong = 0uL
)

data class RAWIHeader(
    val xRes: UShort = 0u,
    val yRes: UShort = 0u,
    val bitDepth: UShort = 0u,
    val blackLevel: UShort = 0u,
    val whiteLevel: UShort = 0u
)

data class RAWCHeader(
    val sensorResX: UShort = 0u,
    val sensorResY: UShort = 0u,
    val sensorCrop: UShort = 0u,
    val binningX: UByte = 0u,
    val skippingX: UByte = 0u,
    val binningY: UByte = 0u,
    val skippingY: UByte = 0u,
    val offsetX: Short = 0,
    val offsetY: Short = 0
)

data class IDNTHeader(
    val cameraName: String = "",
    val cameraSerial: String = "",
    val cameraModel: UInt = 0u
)

data class EXPOHeader(
    val isoMode: UInt = 0u,
    val isoValue: UInt = 0u,
    val isoAnalog: UInt = 0u,
    val digitalGain: UInt = 0u,
    val shutterValue: ULong = 0uL
)

data class LENSHeader(
    val focalLength: UShort = 0u,
    val focalDist: UShort = 0u,
    val aperture: UShort = 0u,
    val stabilizerMode: UByte = 0u,
    val autofocusMode: UByte = 0u,
    val flags: UInt = 0u,
    val lensId: UInt = 0u,
    val lensName: String = "",
    val lensSerial: String = ""
)

data class ELNSHeader(
    val focalLengthMin: UShort = 0u,
    val focalLengthMax: UShort = 0u,
    val apertureMin: UShort = 0u,
    val apertureMax: UShort = 0u,
    val version: UInt = 0u,
    val extenderInfo: UByte = 0u,
    val capabilities: UByte = 0u,
    val chipped: UByte = 0u,
    val lensName: String = ""
)

data class RTCIHeader(
    val tm_sec: UShort = 0u,
    val tm_min: UShort = 0u,
    val tm_hour: UShort = 0u,
    val tm_mday: UShort = 0u,
    val tm_mon: UShort = 0u,
    val tm_year: UShort = 0u,
    val tm_wday: UShort = 0u,
    val tm_yday: UShort = 0u,
    val tm_isdst: UShort = 0u,
    val tm_gmtoff: UShort = 0u,
    val timestamp: ULong = 0uL
)

data class WBALHeader(
    val wbMode: UInt = 0u,
    val kelvin: UInt = 0u,
    val wbGain_R: UInt = 0u,
    val wbGain_G: UInt = 0u,
    val wbGain_B: UInt = 0u,
    val wbs_Gm: UInt = 0u,
    val wbs_Ba: UInt = 0u
)

data class WAVIHeader(
    val format: UShort = 0u,
    val channels: UShort = 0u,
    val samplingRate: UInt = 0u,
    val bytesPerSecond: UInt = 0u,
    val blockAlign: UShort = 0u,
    val bitsPerSample: UShort = 0u
)

data class DISOHeader(
    val dualMode: UInt = 0u,
    val isoValue: UInt = 0u
)

data class INFOHeader(
    val infoString: String = ""
)

data class STYLHeader(
    val picStyleId: UInt = 0u,
    val contrast: Int = 0,
    val sharpness: Int = 0,
    val saturation: Int = 0,
    val colorTone: Int = 0
)

data class VERSHeader(
    val version: String = ""
)

data class DARKHeader(
    val samplesAveraged: UInt = 0u,
    val cameraModel: UInt = 0u,
    val xRes: UShort = 0u,
    val yRes: UShort = 0u,
    val rawWidth: UInt = 0u,
    val rawHeight: UInt = 0u,
    val bitsPerPixel: UInt = 0u,
    val blackLevel: UInt = 0u,
    val whiteLevel: UInt = 0u,
    val sourceFpsNom: UInt = 0u,
    val sourceFpsDenom: UInt = 0u,
    val isoMode: UInt = 0u,
    val isoValue: UInt = 0u,
    val isoAnalog: UInt = 0u,
    val digitalGain: UInt = 0u,
    val shutterValue: ULong = 0uL,
    val binningX: UByte = 0u,
    val skippingX: UByte = 0u,
    val binningY: UByte = 0u,
    val skippingY: UByte = 0u
)

data class VIDFHeader(
    val blockSize: UInt = 0u,
    val frameNumber: UInt = 0u,
    val cropPosX: UShort = 0u,
    val cropPosY: UShort = 0u,
    val panPosX: UShort = 0u,
    val panPosY: UShort = 0u,
    val frameSpace: UInt = 0u
)

data class AUDFHeader(
    val blockSize: UInt = 0u,
    val frameNumber: UInt = 0u,
    val frameSpace: UInt = 0u
)

// GPU-optimized data classes for OpenGL/Shader video acceleration
@Stable
data class MLVGpuFrame(
    val frameIndex: Int,
    val timestamp: Long,
    val rawData: ByteArray? = null,
    val textureId: Int = 0,
    val isTextureReady: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    val bayerPattern: BayerPattern = BayerPattern.RGGB
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MLVGpuFrame

        return frameIndex == other.frameIndex && timestamp == other.timestamp
    }

    override fun hashCode(): Int {
        var result = frameIndex
        result = 31 * result + timestamp.hashCode()
        return result
    }
}

@Stable
data class MLVGpuRenderer(
    val shaderId: Int = 0,
    val vertexBufferId: Int = 0,
    val fragmentBufferId: Int = 0,
    val framebufferId: Int = 0,
    val textureIds: IntArray = intArrayOf(),
    val vertexBuffer: FloatBuffer? = null,
    val isInitialized: Boolean = false,
    val viewport: Viewport = Viewport()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MLVGpuRenderer

        return shaderId == other.shaderId
    }

    override fun hashCode(): Int {
        return shaderId
    }
}

@Stable
data class Viewport(
    val x: Int = 0,
    val y: Int = 0,
    val width: Int = 0,
    val height: Int = 0
)

@Stable
data class MLVProcessingParams(
    val exposure: Float = 0.0f,
    val temperature: Int = 5600,
    val tint: Float = 0.0f,
    val contrast: Float = 0.0f,
    val highlights: Float = 0.0f,
    val shadows: Float = 0.0f,
    val saturation: Float = 0.0f,
    val gamma: Float = 1.0f,
    val whiteBalance: WhiteBalance = WhiteBalance(),
    val colorMatrix: FloatArray = floatArrayOf(
        1.0f, 0.0f, 0.0f,
        0.0f, 1.0f, 0.0f,
        0.0f, 0.0f, 1.0f
    ),
    val enableCA: Boolean = false,
    val caRed: Float = 0.0f,
    val caBlue: Float = 0.0f
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MLVProcessingParams

        return exposure == other.exposure &&
                temperature == other.temperature &&
                tint == other.tint
    }

    override fun hashCode(): Int {
        var result = exposure.hashCode()
        result = 31 * result + temperature
        result = 31 * result + tint.hashCode()
        return result
    }
}

@Stable
data class WhiteBalance(
    val multiplierR: Float = 1.0f,
    val multiplierG: Float = 1.0f,
    val multiplierB: Float = 1.0f
)

enum class BayerPattern {
    RGGB, BGGR, GRBG, GBRG
}

enum class ViewMode {
    LIST, GRID
}

enum class DebayerAlgorithm {
    BILINEAR, MALVAR, AMaZE, LMMSE
}

// Core MLV processing data classes
@Stable
data class CameraId(
    val cameraModel: UInt = 0u,
    val cameraNames: List<String> = listOf("", "", ""), // UNIQ, LOC1, LOC2
    val colorMatrix1: IntArray = IntArray(18),
    val colorMatrix2: IntArray = IntArray(18),
    val forwardMatrix1: IntArray = IntArray(18),
    val forwardMatrix2: IntArray = IntArray(18),
    val focalResolutionX: IntArray = IntArray(2),
    val focalResolutionY: IntArray = IntArray(2),
    val focalUnit: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CameraId
        return cameraModel == other.cameraModel
    }

    override fun hashCode(): Int {
        return cameraModel.hashCode()
    }
}

@Stable
data class FrameIndex(
    val frameType: UShort = 0u,    // VIDF = 1, AUDF = 2, VERS = 3
    val chunkNum: UShort = 0u,     // MLV chunk number
    val frameNumber: UInt = 0u,    // Unique frame number
    val frameSize: UInt = 0u,      // Size of frame data
    val frameOffset: ULong = 0uL,  // Offset to start of frame data
    val frameTime: ULong = 0uL,    // Time in microseconds from recording start
    val blockOffset: ULong = 0uL   // Offset to start of block header
)

@Stable
data class ProcessingObject(
    // Core processing settings
    val exrMode: Boolean = false,
    val agx: Boolean = false,
    val filterOn: Boolean = false,
    val lutOn: Boolean = false,

    // White balance
    val wbFindActive: Boolean = false,
    val kelvin: Double = 5600.0,
    val wbTint: Double = 0.0,
    val wbMultipliers: DoubleArray = doubleArrayOf(1.0, 1.0, 1.0),

    // Levels
    val blackLevel: Float = 0.0f,
    val whiteLevel: Int = 65535,

    // Basic adjustments
    val exposureStops: Double = 0.0,
    val saturation: Double = 1.0,
    val vibrance: Double = 1.0,
    val contrast: Double = 0.0,
    val pivot: Double = 0.5,
    val gammaPower: Double = 1.0,
    val lighten: Double = 0.0,

    // Shadow/Highlight
    val highlights: Double = 0.0,
    val shadows: Double = 0.0,

    // Clarity & Sharpening
    val clarity: Double = 0.0,
    val sharpen: Double = 0.0,
    val sharpenBias: Double = 0.0,
    val shMasking: UByte = 0u,

    // Color correction
    val highlightHue: Double = 0.0,
    val midtoneHue: Double = 0.0,
    val shadowHue: Double = 0.0,
    val highlightSat: Double = 1.0,
    val midtoneSat: Double = 1.0,
    val shadowSat: Double = 1.0,

    // Toning
    val toningDry: Float = 1.0f,
    val toningWet: FloatArray = floatArrayOf(0.0f, 0.0f, 0.0f),

    // Camera matrix
    val cameraMatrix: DoubleArray = DoubleArray(9) { if (it % 4 == 0) 1.0 else 0.0 },
    val useCameraMatrix: Boolean = true,

    // Advanced
    val highlightReconstruction: Boolean = false,
    val transformation: UByte = 0u,
    val colourGamut: UByte = 0u,
    val tonemapFunction: UByte = 0u,
    val colourSpaceTag: UByte = 0u,

    // Denoising
    val denoiserWindow: UByte = 0u,
    val denoiserStrength: UByte = 0u,
    val rbfDenoiserLuma: UByte = 0u,
    val rbfDenoiserChroma: UByte = 0u,
    val rbfDenoiserRange: UByte = 0u,

    // Effects
    val grainStrength: UByte = 0u,
    val grainLumaWeight: UByte = 0u,
    val vignetteStrength: Byte = 0,

    // CA correction
    val caDesaturate: UByte = 0u,
    val caRadius: UByte = 0u
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ProcessingObject
        return kelvin == other.kelvin && exposureStops == other.exposureStops
    }

    override fun hashCode(): Int {
        var result = kelvin.hashCode()
        result = 31 * result + exposureStops.hashCode()
        return result
    }
}

@Stable
data class LLRawprocObject(
    // Fix flags
    val fixRaw: Boolean = true,
    val verticalStripes: Int = 1,        // 0=off, 1=fix, 2=compute per frame
    val computeStripes: Boolean = false,
    val focusPixels: Int = 1,            // 0=off, 1=fix, 2=generate map
    val fpiMethod: Int = 0,              // Focus pixel interpolation: 0=mlvfs, 1=raw2dng
    val badPixels: Int = 1,              // 0=off, 1=fix, 2=force search per frame
    val bpsMethod: Int = 0,              // Bad pixel search: 0=normal, 1=aggressive
    val bpiMethod: Int = 0,              // Bad pixel interpolation: 0=mlvfs, 1=raw2dng
    val chromaSmooth: Int = 0,           // 0=off, 2=cs2x2, 3=cs3x3, 5=cs5x5
    val patternNoise: Boolean = false,
    val deflickerTarget: Int = 0,

    // Dual ISO
    val disoValidity: Int = 0,           // 0=not valid, 1=forced, 2=valid
    val dualIso: Int = 0,                // 0=off, 1=full 20bit, 2=preview
    val disoAveraging: Int = 0,          // 0=amaze-edge, 1=mean23
    val disoAliasMap: Boolean = false,
    val disoFrBlending: Boolean = false,

    // Dark frame
    val darkFrame: Int = 0,              // 0=off, 1=external, 2=internal
    val darkFrameFilename: String = "",

    // cDNG settings
    val dngBitDepth: Int = 14,
    val dngBlackLevel: Int = 0,
    val dngWhiteLevel: Int = 65535,

    // Map statuses
    val fpmStatus: Int = 0,              // Focus pixel map: 0=not loaded, 1=loaded, 2=not exist
    val bpmStatus: Int = 0               // Bad pixel map: 0=not loaded, 1=loaded, 2=not exist, 3=none found
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as LLRawprocObject
        return fixRaw == other.fixRaw && dualIso == other.dualIso
    }

    override fun hashCode(): Int {
        var result = fixRaw.hashCode()
        result = 31 * result + dualIso
        return result
    }
}