package fm.magiclantern.forum.features.player.ui

internal data class SourcePixel(val x: Int, val y: Int)

/** Maps a tap through the same fit/stretch rectangle used by [fm.magiclantern.forum.features.player.MlvRenderer]. */
internal fun mapPreviewTapToSource(
    tapX: Float,
    tapY: Float,
    surfaceWidth: Int,
    surfaceHeight: Int,
    sourceWidth: Int,
    sourceHeight: Int,
    stretchX: Float,
    stretchY: Float
): SourcePixel? {
    if (!tapX.isFinite() || !tapY.isFinite() || surfaceWidth <= 0 || surfaceHeight <= 0 ||
        sourceWidth <= 0 || sourceHeight <= 0
    ) {
        return null
    }

    val safeStretchX = stretchX.takeIf { it.isFinite() && it > 0f } ?: 1f
    val safeStretchY = stretchY.takeIf { it.isFinite() && it > 0f } ?: 1f
    val surfaceAspect = surfaceWidth.toFloat() / surfaceHeight.toFloat()
    val adjustedSourceAspect = (sourceWidth.toFloat() * safeStretchX) /
        (sourceHeight.toFloat() * safeStretchY)

    val contentWidth: Float
    val contentHeight: Float
    if (surfaceAspect > adjustedSourceAspect) {
        contentHeight = surfaceHeight.toFloat()
        contentWidth = contentHeight * adjustedSourceAspect
    } else {
        contentWidth = surfaceWidth.toFloat()
        contentHeight = contentWidth / adjustedSourceAspect
    }

    val left = (surfaceWidth - contentWidth) / 2f
    val top = (surfaceHeight - contentHeight) / 2f
    if (tapX < left || tapX >= left + contentWidth ||
        tapY < top || tapY >= top + contentHeight
    ) {
        return null
    }

    val sourceX = (((tapX - left) / contentWidth) * sourceWidth).toInt()
        .coerceIn(0, sourceWidth - 1)
    val sourceY = (((tapY - top) / contentHeight) * sourceHeight).toInt()
        .coerceIn(0, sourceHeight - 1)
    return SourcePixel(sourceX, sourceY)
}
