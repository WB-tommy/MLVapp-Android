package fm.magiclantern.forum.features.export.ui

import fm.magiclantern.forum.features.export.ExportService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportProgressUiModelTest {

    @Test
    fun `running export cancels without navigating away`() {
        val model = exportProgressUiModel(
            ExportService.ExportStatus.Running(
                clipIndex = 0,
                totalClips = 2,
                clipName = "A001"
            )
        )

        assertEquals(ExportProgressAction.CANCEL, model.action)
        assertTrue(model.showProgressBar)
        assertEquals("Exporting A001 (1/2)", model.message)
    }

    @Test
    fun `cancelled export closes back to location`() {
        val model = exportProgressUiModel(ExportService.ExportStatus.Cancelled(completedClips = 0))

        assertEquals(ExportProgressAction.CLOSE_TO_LOCATION, model.action)
        assertFalse(model.showProgressBar)
        assertEquals("Export cancelled.", model.message)
    }

    @Test
    fun `failed export closes back to location`() {
        val model = exportProgressUiModel(ExportService.ExportStatus.Failed("encoder failed"))

        assertEquals(ExportProgressAction.CLOSE_TO_LOCATION, model.action)
        assertEquals("Export failed: encoder failed", model.message)
    }

    @Test
    fun `successful export closes to home`() {
        val model = exportProgressUiModel(ExportService.ExportStatus.Completed(totalClips = 1))

        assertEquals(ExportProgressAction.CLOSE_TO_HOME, model.action)
        assertEquals("Export completed successfully.", model.message)
    }
}
