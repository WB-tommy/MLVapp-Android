package fm.magiclantern.forum.features.export

import fm.magiclantern.forum.features.export.model.ExportCodec
import fm.magiclantern.forum.features.export.model.ExportSettings
import fm.magiclantern.forum.features.export.model.SmoothingOption

fun ExportSettings.sanitized(): ExportSettings {
    var sanitized = this

    if (sanitized.frameRate.enabled) {
        sanitized = sanitized.copy(includeAudio = false)
    }

    if (sanitized.codec == ExportCodec.AUDIO_ONLY) {
        sanitized = sanitized.copy(includeAudio = true)
    }

    if (!sanitized.allowsSmoothing) {
        sanitized = sanitized.copy(smoothing = SmoothingOption.OFF)
    }

    if (!sanitized.allowsHdrBlending) {
        sanitized = sanitized.copy(hdrBlending = false)
    }

    if (!sanitized.allowsResize) {
        sanitized = sanitized.copy(
            resize = sanitized.resize.copy(enabled = false)
        )
    }

    if (!sanitized.allowsAudioToggle) {
        sanitized = sanitized.copy(includeAudio = true)
    }

    if (!sanitized.allowsFrameRateOverride) {
        sanitized = sanitized.copy(
            frameRate = sanitized.frameRate.copy(enabled = false)
        )
    }

    if (sanitized.resize.width <= 0 || sanitized.resize.height <= 0) {
        sanitized = sanitized.copy(
            resize = sanitized.resize.copy(
                width = sanitized.resize.width.coerceAtLeast(1),
                height = sanitized.resize.height.coerceAtLeast(1)
            )
        )
    }

    return sanitized
}
