package fm.magiclantern.forum.features.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewTapMapperTest {
    @Test
    fun mapsCenterOfMatchingAspectSurface() {
        assertEquals(
            SourcePixel(960, 540),
            mapPreviewTapToSource(800f, 450f, 1600, 900, 1920, 1080, 1f, 1f)
        )
    }

    @Test
    fun rejectsHorizontalLetterboxAndMapsImageCenter() {
        assertNull(mapPreviewTapToSource(500f, 100f, 1000, 1000, 1600, 900, 1f, 1f))
        assertEquals(
            SourcePixel(800, 450),
            mapPreviewTapToSource(500f, 500f, 1000, 1000, 1600, 900, 1f, 1f)
        )
    }

    @Test
    fun rejectsVerticalPillarbox() {
        assertNull(mapPreviewTapToSource(100f, 500f, 2000, 1000, 400, 300, 1f, 1f))
    }

    @Test
    fun includesStretchInFittedContentRectangle() {
        assertNull(mapPreviewTapToSource(50f, 10f, 100, 100, 100, 100, 2f, 1f))
        assertEquals(
            SourcePixel(50, 50),
            mapPreviewTapToSource(50f, 50f, 100, 100, 100, 100, 2f, 1f)
        )
    }
}
