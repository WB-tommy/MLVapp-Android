package fm.magiclantern.forum.features.export

import fm.magiclantern.forum.data.repository.FocusPixelRequirement
import fm.magiclantern.forum.domain.model.ClipPreview
import fm.magiclantern.forum.features.export.model.FocusPixelExportRequirement
import fm.magiclantern.forum.features.export.model.FocusPixelPreflightResult

class FocusPixelPreflightCoordinator(
    private val findMissing: (List<ClipPreview>) -> List<FocusPixelRequirement>,
    private val downloadMap: suspend (String) -> Boolean
) {
    fun check(clips: List<ClipPreview>): FocusPixelPreflightResult {
        val missing = findMissing(clips).toExportRequirements(clips)
        return if (missing.isEmpty()) {
            FocusPixelPreflightResult.Ready
        } else {
            FocusPixelPreflightResult.Missing(missing)
        }
    }

    suspend fun downloadMissing(
        clips: List<ClipPreview>,
        requirements: List<FocusPixelExportRequirement>
    ): FocusPixelPreflightResult {
        val failedDownloads = requirements
            .map { it.requiredFile }
            .distinct()
            .filter { fileName ->
                !runCatching { downloadMap(fileName) }.getOrDefault(false)
            }

        return when (val result = check(clips)) {
            is FocusPixelPreflightResult.Missing -> {
                result.copy(failedDownloads = failedDownloads)
            }
            else -> result
        }
    }

    private fun List<FocusPixelRequirement>.toExportRequirements(
        clips: List<ClipPreview>
    ): List<FocusPixelExportRequirement> {
        return mapNotNull { requirement ->
            val clip = clips.firstOrNull { it.guid == requirement.clipGuid }
                ?: return@mapNotNull null
            FocusPixelExportRequirement(
                clipGuid = clip.guid,
                clipName = ExportPayloadBuilder.clipDisplayName(clip),
                requiredFile = requirement.requiredFile
            )
        }
    }
}
