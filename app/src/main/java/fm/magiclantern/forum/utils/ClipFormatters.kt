package fm.magiclantern.forum.utils

import java.util.Locale

internal fun formatDuration(frames: Int, fps: Float): String {
    if (fps <= 0f || frames <= 0) return "-"
    val totalSeconds = (frames / fps).toInt()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
}

internal fun formatShutter(shutterUs: Int, fps: Float): String {
    if (shutterUs <= 0) return "-"
    val shutterSpeed = 1_000_000.0 / shutterUs.toDouble()
    val shutterAngle = if (fps > 0f) (fps * 360.0 / shutterSpeed) else 0.0
    val denom = shutterSpeed.toInt().coerceAtLeast(1)
    return String.format(Locale.US, "1/%d s,  %.0f deg,  %d µs", denom, shutterAngle, shutterUs)
}
