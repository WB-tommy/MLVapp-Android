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
        cpuCores: Int,
        useParallelMcrawDecoder: Boolean
    ): fm.magiclantern.forum.data.ClipPreviewData

    external fun probeMlvGuid(
        fd: Int,
        clipPath: String
    ): Long

    external fun openClip(
        fds: IntArray,
        clipPath: String,
        memSize: Long,
        cpuCores: Int,
        useParallelMcrawDecoder: Boolean
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
     * Decodes one classic MLV or MCRAW frame, applies the configured low-level
     * CPU RAW corrections, and returns a canonical, full-scale 16-bit Bayer
     * plane for GPU preview processing. User-visible levels, white balance,
     * demosaic, profile, and grading are not applied here.
     *
     * [dst] must be direct with at least width * height * 2 bytes. [frameInfo]
     * must be a native-order direct buffer of four Floats: full-scale black,
     * full-scale white, CFA enum, and representation bit depth. It is committed
     * only when [dst] contains a complete corrected frame.
     * Returns -1 for transient lock contention, -2 for an unsupported or hard
     * decode failure, or the backend used: 0=current MCRAW, 1=row-parallel
     * MCRAW, 2=classic MLV built-in decode.
     */
    external fun fillCorrectedRawBayer16(
        handle: Long,
        frameIndex: Int,
        dst: ByteBuffer,
        frameInfo: ByteBuffer,
        decoderBackend: Int,
        decoderThreads: Int
    ): Int

    /**
     * Copies the immutable inputs for one GPU-preview render-state snapshot.
     * Both buffers must be direct and use native byte order. [params] holds
     * 32 Float values (128 bytes). [toneLut] holds 65,536 interleaved RG
     * unsigned 16-bit pixels (262,144 bytes): R is the gamma/transfer LUT and
     * G is the post-gamma contrast-curve LUT.
     *
     * Parameter layout: full-scale 16-bit black and white at 0..1, R/G/B Bayer
     * gains at 2..4, CFA enum at 5 (RGGB/GBRG/BGGR/GRBG = 0/1/2/3), row-major
     * 3x3 color matrix at 6..14, flags at 15, contrast/pivot/clarity at 16..18,
     * shadows/highlights at 19..20, vibrance/saturation at 21..22, normalized
     * highest green and its tolerance at 23..24, color gamut at 25, and zeroed
     * reserved values at 26..31. Flag bits are AgX (0), requires CPU (1),
     * creative adjustments allowed (2), camera matrix enabled (3), EXR mode
     * (4), and highlight reconstruction (5).
     */
    external fun fillRawGpuPreviewState(
        handle: Long,
        params: ByteBuffer,
        toneLut: ByteBuffer
    ): Boolean

    /** Enables or disables the CPU RGB frame cache for RAW GPU preview mode. */
    external fun setRawGpuPreviewCaching(
        handle: Long,
        enabled: Boolean
    ): Boolean

    /** Selects the shared MCRAW type-7 decoder for this clip's CPU/GPU paths. */
    external fun setMcrawParallelDecoder(
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
