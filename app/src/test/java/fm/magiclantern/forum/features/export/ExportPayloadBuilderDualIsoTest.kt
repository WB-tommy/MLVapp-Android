package fm.magiclantern.forum.features.export

import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import fm.magiclantern.forum.domain.model.ClipGradingData
import fm.magiclantern.forum.domain.model.ClipPreview
import fm.magiclantern.forum.domain.model.DualIsoSettingsContract
import fm.magiclantern.forum.domain.model.RawCorrectionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportPayloadBuilderDualIsoTest {

    @Test
    fun `legacy preview receipt is normalized before export`() {
        val grading = ClipGradingData(
            rawCorrection = RawCorrectionSettings(
                dualIso = DualIsoSettingsContract.LEGACY_MODE_PREVIEW,
                dualIsoForced = true,
                dualIsoPattern = 99,
                dualIsoMatchMethod = DualIsoSettingsContract.MATCH_ISO,
                dualIsoEvCorrection = Float.NaN,
                dualIsoBlackDelta = 200,
                dualIsoInterpolation = 99,
                dualIsoAliasMap = true,
                dualIsoFrBlending = false
            )
        )

        val raw = ExportPayloadBuilder.build(
            clips = listOf(clip(guid = 7L)),
            gradingSnapshot = mapOf(7L to grading)
        ).single().rawCorrection

        assertEquals(DualIsoSettingsContract.MODE_HQ, raw.dualIso)
        assertEquals(DualIsoSettingsContract.PATTERN_LAST, raw.dualIsoPattern)
        assertEquals(DualIsoSettingsContract.MATCH_HISTOGRAM, raw.dualIsoMatchMethod)
        assertEquals(DualIsoSettingsContract.EV_AUTO, raw.dualIsoEvCorrection, 0f)
        assertEquals(DualIsoSettingsContract.BLACK_DELTA_MAX, raw.dualIsoBlackDelta)
        assertEquals(1, raw.dualIsoInterpolation)
        assertTrue(raw.dualIsoAliasMap)
        assertTrue(raw.dualIsoFrBlending)
    }

    @Test
    fun `clip without receipt uses upstream Dual ISO defaults`() {
        val raw = ExportPayloadBuilder.build(
            clips = listOf(clip(guid = 11L)),
            gradingSnapshot = emptyMap()
        ).single().rawCorrection

        assertEquals(DualIsoSettingsContract.MODE_OFF, raw.dualIso)
        assertEquals(DualIsoSettingsContract.PATTERN_AUTO, raw.dualIsoPattern)
        assertEquals(DualIsoSettingsContract.MATCH_ISO, raw.dualIsoMatchMethod)
        assertEquals(DualIsoSettingsContract.EV_AUTO, raw.dualIsoEvCorrection, 0f)
        assertEquals(DualIsoSettingsContract.BLACK_DELTA_AUTO, raw.dualIsoBlackDelta)
        assertFalse(raw.dualIsoAliasMap)
        assertTrue(raw.dualIsoFrBlending)
    }

    @Test
    fun `never opened dual ISO clip uses preview metadata for export`() {
        val raw = ExportPayloadBuilder.build(
            clips = listOf(
                clip(
                    guid = 12L,
                    dualIsoValid = true,
                    dualIsoAutoEnabled = true,
                    originalBlackLevel = 2048,
                    originalWhiteLevel = 15000
                )
            ),
            gradingSnapshot = emptyMap()
        ).single().rawCorrection

        assertEquals(DualIsoSettingsContract.MODE_HQ, raw.dualIso)
        assertEquals(2048, raw.dualIsoBlack)
        assertEquals(15000, raw.dualIsoWhite)
    }

    @Test
    fun `valid equal ISO metadata does not auto enable processing`() {
        val raw = ExportPayloadBuilder.build(
            clips = listOf(
                clip(
                    guid = 13L,
                    dualIsoValid = true,
                    dualIsoAutoEnabled = false,
                    originalBlackLevel = 1024,
                    originalWhiteLevel = 14000
                )
            ),
            gradingSnapshot = emptyMap()
        ).single().rawCorrection

        assertEquals(DualIsoSettingsContract.MODE_OFF, raw.dualIso)
        assertEquals(1024, raw.dualIsoBlack)
        assertEquals(14000, raw.dualIsoWhite)
    }

    @Test
    fun `external dark frame is not advertised to fresh export handle`() {
        val grading = ClipGradingData(
            rawCorrection = RawCorrectionSettings(
                dualIso = DualIsoSettingsContract.MODE_HQ,
                darkFrameEnabled = 1,
                darkFrameUri = "content://dark-frame"
            )
        )

        val raw = ExportPayloadBuilder.build(
            clips = listOf(clip(guid = 14L)),
            gradingSnapshot = mapOf(14L to grading)
        ).single().rawCorrection

        assertEquals(0, raw.darkFrameEnabled)
        assertEquals(DualIsoSettingsContract.EV_AUTO, raw.dualIsoEvCorrection)
        assertEquals(DualIsoSettingsContract.BLACK_DELTA_AUTO, raw.dualIsoBlackDelta)
    }

    private fun clip(
        guid: Long,
        dualIsoValid: Boolean = false,
        dualIsoAutoEnabled: Boolean = false,
        originalBlackLevel: Int = 4096,
        originalWhiteLevel: Int = 65013
    ) = ClipPreview(
        guid = guid,
        displayName = "clip-$guid",
        uris = listOf(Uri.EMPTY),
        fileNames = listOf("clip-$guid.MLV"),
        thumbnail = testBitmap,
        width = 4,
        height = 4,
        dualIsoValid = dualIsoValid,
        dualIsoAutoEnabled = dualIsoAutoEnabled,
        originalBlackLevel = originalBlackLevel,
        originalWhiteLevel = originalWhiteLevel
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
