package fm.magiclantern.forum.features.export.model

import android.net.Uri
import fm.magiclantern.forum.domain.model.ClipGradingData
import fm.magiclantern.forum.domain.model.ClipPreview

data class ExportDraft(
    val selectedClips: List<ClipPreview> = emptyList(),
    val fpmPreflight: FocusPixelPreflightResult = FocusPixelPreflightResult.Unchecked,
    val settings: ExportSettings = ExportSettings(),
    val gradingSnapshot: Map<Long, ClipGradingData> = emptyMap(),
    val outputDirectory: Uri? = null
) {
    val canConfigureSettings: Boolean
        get() = selectedClips.isNotEmpty() &&
            (fpmPreflight is FocusPixelPreflightResult.Ready ||
                fpmPreflight is FocusPixelPreflightResult.Skipped)

    val canStartExport: Boolean
        get() = canConfigureSettings && outputDirectory != null
}
