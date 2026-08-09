package fm.magiclantern.forum.domain.session

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import fm.magiclantern.forum.domain.model.ClipDetails
import fm.magiclantern.forum.domain.model.ClipGradingData
import fm.magiclantern.forum.domain.model.ClipMetadata
import fm.magiclantern.forum.domain.model.ClipPreview
import fm.magiclantern.forum.domain.model.DebayerAlgorithm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveClipHolderTest {
    @Test
    fun staleReceiptCompletionCannotReleaseNewClipGate() {
        val holder = ActiveClipHolder()
        val first = clip(guid = 11L, handle = 101L)
        val second = clip(guid = 22L, handle = 202L)

        holder.activateClip(first)
        holder.setRequiresCpuProcessingPreview(false)
        assertTrue(holder.cpuProcessingPreviewRequirement.value.required)

        holder.activateClip(second)
        assertFalse(
            holder.completeProcessingReceiptRestore(
                expectedHandle = first.nativeHandle,
                expectedGuid = first.guid,
                required = false
            )
        )
        assertTrue(holder.cpuProcessingPreviewRequirement.value.required)

        assertTrue(
            holder.completeProcessingReceiptRestore(
                expectedHandle = second.nativeHandle,
                expectedGuid = second.guid,
                required = false
            )
        )
        assertFalse(holder.cpuProcessingPreviewRequirement.value.required)
    }

    @Test
    fun sameGuidReplacementPublishesNewHandleAndKeepsGateScoped() {
        val holder = ActiveClipHolder()
        val firstHandle = clip(guid = 33L, handle = 303L)
        val replacement = clip(guid = 33L, handle = 404L)

        holder.activateClip(firstHandle)
        assertTrue(
            holder.completeProcessingReceiptRestore(
                expectedHandle = firstHandle.nativeHandle,
                expectedGuid = firstHandle.guid,
                required = false
            )
        )

        holder.activateClip(replacement)
        assertTrue(holder.activeClip.value?.nativeHandle == replacement.nativeHandle)
        assertTrue(holder.cpuProcessingPreviewRequirement.value.required)
        assertFalse(
            holder.completeProcessingReceiptRestore(
                expectedHandle = firstHandle.nativeHandle,
                expectedGuid = firstHandle.guid,
                required = false
            )
        )
        assertTrue(
            holder.completeProcessingReceiptRestore(
                expectedHandle = replacement.nativeHandle,
                expectedGuid = replacement.guid,
                required = false
            )
        )
    }

    @Test
    fun previewDebayerModeFollowsActiveReceiptAndUpdates() {
        val holder = ActiveClipHolder()
        val active = clip(
            guid = 44L,
            handle = 505L,
            grading = ClipGradingData(debayerMode = DebayerAlgorithm.RCD)
        )

        holder.activateClip(active)
        assertEquals(DebayerAlgorithm.RCD, holder.previewDebayerMode.value)

        holder.setPreviewDebayerMode(DebayerAlgorithm.LMMSE)
        assertEquals(DebayerAlgorithm.LMMSE, holder.previewDebayerMode.value)

        holder.clearActiveClip()
        assertEquals(DebayerAlgorithm.AMAZE, holder.previewDebayerMode.value)
    }

    private fun clip(
        guid: Long,
        handle: Long,
        grading: ClipGradingData = ClipGradingData()
    ): ClipDetails = ClipDetails(
        preview = ClipPreview(
            guid = guid,
            displayName = "clip-$guid",
            uris = emptyList(),
            fileNames = emptyList(),
            thumbnail = testBitmap,
            width = 4,
            height = 4
        ),
        metadata = ClipMetadata(frames = 1, originalWhiteLevel = 16383),
        nativeHandle = handle,
        grading = grading
    )

    private val testBitmap = object : ImageBitmap {
        override val width: Int = 1
        override val height: Int = 1
        override val colorSpace: ColorSpace = ColorSpaces.Srgb
        override val hasAlpha: Boolean = false
        override val config: ImageBitmapConfig = ImageBitmapConfig.Argb8888

        override fun readPixels(
            buffer: IntArray,
            startX: Int,
            startY: Int,
            width: Int,
            height: Int,
            bufferOffset: Int,
            stride: Int
        ) = Unit

        override fun prepareToDraw() = Unit
    }
}
