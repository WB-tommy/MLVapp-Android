package fm.forum.mlvapp.NativeInterface

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
    ): fm.forum.mlvapp.data.ClipPreviewData

    external fun openClip(
        fds: IntArray,
        clipPath: String,
        memSize: Long,
        cpuCores: Int
    ): fm.forum.mlvapp.data.ClipMetaData

    external fun fillFrame16(
        handle: Long,
        frameIndex: Int,
        cores: Int,
        dst: ByteBuffer,  // direct buffer
        width: Int,
        height: Int
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

    external fun  readAudioBuffer(
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
}
