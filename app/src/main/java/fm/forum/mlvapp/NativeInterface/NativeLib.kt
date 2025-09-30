package fm.forum.mlvapp.NativeInterface

import java.nio.ByteBuffer

object NativeLib {
    init {
        System.loadLibrary("mlvcore")
    }

    external fun openClipForPreview(
        fd: Int,
        fileName: String,
        memSize: Long,
        cpuCores: Int
    ): fm.forum.mlvapp.data.ClipPreviewData

    external fun openClip(
        fds: IntArray,
        fileName: String,
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

    external fun closeClip(
        handle: Long
    )
}
