package fm.forum.mlvapp.data

class ClipMetaData(
    val nativeHandle: Long,
    // Metadata (mirrors desktop MainWindow.cpp updateMetadata inputs)
    val cameraName: String,
    val lens: String,
    val frames: Int,
    val fps: Float,
    val focalLengthMm: Int,
    val shutterUs: Int,
    val apertureHundredths: Int,
    val iso: Int,
    val dualIsoValue: Int,
    val dualIsoValid: Boolean,
    val losslessBpp: Int,
    val compression: String,
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val min: Int,
    val sec: Int,
    val hasAudio: Boolean,
    val audioChannels: Int,
    val audioSampleRate: Int,
    val isMcraw: Boolean,
) {
}
