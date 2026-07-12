package fm.magiclantern.forum.features.export

import fm.magiclantern.forum.domain.model.ClipGradingData
import fm.magiclantern.forum.domain.model.ClipPreview
import fm.magiclantern.forum.domain.model.ColorGradingSettings
import fm.magiclantern.forum.domain.model.DebayerAlgorithm
import fm.magiclantern.forum.domain.model.RawCorrectionSettings
import fm.magiclantern.forum.features.export.model.ExportClipPayload
import fm.magiclantern.forum.utils.sortedByMlvFileRole

object ExportPayloadBuilder {
    fun build(
        clips: List<ClipPreview>,
        gradingSnapshot: Map<Long, ClipGradingData>
    ): List<ExportClipPayload> = buildList {
        for (clip in clips) {
            val pairs = if (clip.fileNames.size == clip.uris.size && clip.fileNames.isNotEmpty()) {
                clip.uris.zip(clip.fileNames)
            } else {
                clip.uris.map { uri -> uri to (uri.lastPathSegment ?: "") }
            }

            val sortedPairs = pairs.sortedByMlvFileRole { (_, fileName) -> fileName }
            val uris = sortedPairs.map { it.first }
            if (uris.isEmpty()) continue

            val primaryFileName = sortedPairs.firstOrNull()?.second
                ?: clip.fileNames.firstOrNull()
                ?: continue
            val grading = gradingSnapshot[clip.guid]

            add(
                ExportClipPayload(
                    displayName = clipDisplayName(clip),
                    primaryFileName = primaryFileName,
                    uris = uris,
                    stretchFactorX = clip.stretchFactorX,
                    stretchFactorY = clip.stretchFactorY,
                    debayerMode = grading?.debayerMode ?: DebayerAlgorithm.AMAZE,
                    rawCorrection = grading?.rawCorrection ?: RawCorrectionSettings(),
                    colorGrading = grading?.colorGrading ?: ColorGradingSettings(),
                    cutIn = grading?.cutIn ?: 1,
                    cutOut = grading?.cutOut ?: 0
                )
            )
        }
    }

    fun clipDisplayName(clip: ClipPreview): String {
        if (clip.displayName.isNotBlank()) return clip.displayName
        val fromFile = clip.fileNames.firstOrNull()?.substringBeforeLast('.', "")
        if (!fromFile.isNullOrBlank()) return fromFile
        return "clip_${clip.guid}"
    }
}
