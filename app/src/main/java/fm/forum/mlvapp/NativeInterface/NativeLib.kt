package fm.forum.mlvapp.NativeInterface

class NativeLib {
    init {
        System.loadLibrary("mlvcore")
    }

    // TODO: Implement native functions in JNI layer
    // external fun initMlvClip(path: String): Long
    // external fun getFrame(clipHandle: Long, frameIndex: Int): ByteArray
    // external fun getFrameCount(clipHandle: Long): Int
    // external fun getThumbnail(clipHandle: Long, width: Int, height: Int): ByteArray?
    // external fun getMLVInfo(path: String): String // Returns JSON with resolution, duration, etc.
    // external fun cleanup(clipHandle: Long)
}