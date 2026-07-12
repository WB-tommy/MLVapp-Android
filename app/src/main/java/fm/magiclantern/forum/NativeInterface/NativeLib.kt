package fm.magiclantern.forum.nativeInterface

import fm.magiclantern.forum.features.export.model.ClipExportData
import fm.magiclantern.forum.features.export.model.ExportOptions
import java.nio.ByteBuffer

object NativeLib {
    init {
        System.loadLibrary("mlvcore")
    }

    external fun openClipForPreview(
        fd: Int,
        clipPath: String,
        memSize: Long,
        cpuCores: Int
    ): fm.magiclantern.forum.data.ClipPreviewData

    external fun probeMlvGuid(
        fd: Int,
        clipPath: String
    ): Long

    external fun openClip(
        fds: IntArray,
        clipPath: String,
        memSize: Long,
        cpuCores: Int
    ): fm.magiclantern.forum.data.ClipMetaData

    external fun fillFrame16(
        handle: Long,
        frameIndex: Int,
        cores: Int,
        dst: ByteBuffer,  // direct buffer
        width: Int,
        height: Int
    ): Boolean

    /**
     * Decodes one MCRAW frame into its native-scale, original-CFA Bayer plane.
     * [dst] must be a direct buffer with at least width * height * 2 bytes.
     */
    external fun fillMcrawBayer16(
        handle: Long,
        frameIndex: Int,
        dst: ByteBuffer,
        decoderBackend: Int,
        decoderThreads: Int
    ): Int

    /**
     * Copies the immutable inputs for one GPU-preview render-state snapshot.
     * Both buffers must be direct and use native byte order. [params] holds
     * 16 Float values (64 bytes); [toneLut] holds 65,536 unsigned 16-bit
     * entries (131,072 bytes). Parameter layout: native-scale black and white
     * at 0..1, R/G/B Bayer gains at 2..4, CFA enum at 5 (RGGB/GBRG/BGGR/GRBG
     * = 0/1/2/3), row-major 3x3 color matrix at 6..14, and flags at 15
     * (bit 0 enables the AgX matrix sandwich).
     */
    external fun fillMcrawGpuPreviewState(
        handle: Long,
        params: ByteBuffer,
        toneLut: ByteBuffer
    ): Boolean

    /** Enables or disables the CPU RGB frame cache for MCRAW benchmarking. */
    external fun setMcrawGpuPreviewCaching(
        handle: Long,
        enabled: Boolean
    ): Boolean

    external fun getVideoFrameTimestamps(
        handle: Long
    ): LongArray?

    external fun getAudioBufferSize(
        handle: Long
    ): Long

    external fun getAudioBytesPerSample(
        handle: Long
    ): Int

    external fun readAudioBuffer(
        handle: Long,
        offsetBytes: Long,
        byteCount: Int,
        dst: ByteBuffer
    ): Int

    external fun closeClip(
        handle: Long
    )

    external fun getFpmName(
        handle: Long
    ): String

    external fun checkCameraModel(
        handle: Long
    ): Int

    external fun setBaseDir(
        path: String
    )

    external fun refreshFocusPixelMap(
        handle: Long
    )

    external fun cancelExport()

    external fun prepareExport()

    external fun setFocusPixelMode(
        handle: Long,
        mode: Int
    )

    external fun setFixRawMode(
        handle: Long,
        enabled: Boolean
    )

    external fun setDebayerMode(
        handle: Long,
        mode: Int
    )

    external fun exportHandler(
        memSize: Long,
        cpuCores: Int,
        clipFds: IntArray,
        options: ExportOptions,
        progressListener: Any,
        fileProvider: Any?
    )

    /**
     * Batch export handler - processes multiple clips with shared encoder context.
     * More efficient than calling exportHandler for each clip when exporting
     * a queue of clips with the same codec settings.
     */
    external fun exportBatchHandler(
        memSize: Long,
        cpuCores: Int,
        clips: Array<ClipExportData>,
        options: ExportOptions,
        progressListener: Any,
        fileProvider: Any?
    )

    external fun testEncoderConfiguration(
        options: ExportOptions
    ): Boolean

    external fun testVulkanHardwareDevice(): Boolean

    external fun testVulkanProResEncoding(
        options: ExportOptions
    ): Boolean

    external fun testVulkanHevc10Bit422Encoding(
        options: ExportOptions
    ): Boolean
}
