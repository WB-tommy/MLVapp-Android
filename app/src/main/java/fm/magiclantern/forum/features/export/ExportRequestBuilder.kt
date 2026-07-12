package fm.magiclantern.forum.features.export

import fm.magiclantern.forum.features.export.model.ExportDraft
import fm.magiclantern.forum.features.export.model.ExportRequest

object ExportRequestBuilder {
    fun build(
        draft: ExportDraft,
        cacheSizeMiB: Long,
        cpuCores: Int
    ): ExportRequest? {
        val outputDirectory = draft.outputDirectory ?: return null
        if (!draft.canStartExport) return null

        val clips = ExportPayloadBuilder.build(
            clips = draft.selectedClips,
            gradingSnapshot = draft.gradingSnapshot
        )
        if (clips.isEmpty()) return null

        return ExportRequest(
            clips = clips,
            settings = draft.settings,
            outputDirectory = outputDirectory,
            cacheSizeMiB = cacheSizeMiB,
            cpuCores = cpuCores
        )
    }
}
