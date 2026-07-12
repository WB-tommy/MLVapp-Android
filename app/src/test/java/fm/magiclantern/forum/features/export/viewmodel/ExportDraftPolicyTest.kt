package fm.magiclantern.forum.features.export.viewmodel

import fm.magiclantern.forum.features.export.ExportService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportDraftPolicyTest {

    @Test
    fun `cancelled and failed attempts retain retry draft`() {
        assertFalse(
            shouldClearExportDraft(ExportService.ExportStatus.Cancelled(completedClips = 0))
        )
        assertFalse(shouldClearExportDraft(ExportService.ExportStatus.Failed("failed")))
    }

    @Test
    fun `completed attempt clears retry draft`() {
        assertTrue(shouldClearExportDraft(ExportService.ExportStatus.Completed(totalClips = 1)))
    }
}
