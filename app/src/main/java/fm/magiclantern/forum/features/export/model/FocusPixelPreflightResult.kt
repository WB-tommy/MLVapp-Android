package fm.magiclantern.forum.features.export.model

sealed class FocusPixelPreflightResult {
    object Unchecked : FocusPixelPreflightResult()
    object Ready : FocusPixelPreflightResult()
    data class Missing(
        val requirements: List<FocusPixelExportRequirement>,
        val failedDownloads: List<String> = emptyList()
    ) : FocusPixelPreflightResult()

    data class Skipped(val requirements: List<FocusPixelExportRequirement>) :
        FocusPixelPreflightResult()
}

data class FocusPixelExportRequirement(
    val clipGuid: Long,
    val clipName: String,
    val requiredFile: String
)
