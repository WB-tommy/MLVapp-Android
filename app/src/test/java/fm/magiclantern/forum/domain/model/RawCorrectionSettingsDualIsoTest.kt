package fm.magiclantern.forum.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawCorrectionSettingsDualIsoTest {
    @Test
    fun defaultsUseUpstreamAutomaticSentinels() {
        val settings = RawCorrectionSettings()

        assertEquals(DualIsoSettingsContract.MODE_OFF, settings.dualIso)
        assertEquals(DualIsoSettingsContract.PATTERN_AUTO, settings.dualIsoPattern)
        assertEquals(DualIsoSettingsContract.MATCH_ISO, settings.dualIsoMatchMethod)
        assertEquals(DualIsoSettingsContract.EV_AUTO, settings.dualIsoEvCorrection)
        assertEquals(
            DualIsoSettingsContract.BLACK_DELTA_AUTO,
            settings.dualIsoBlackDelta
        )
        assertFalse(settings.dualIsoAliasMap)
        assertTrue(settings.dualIsoFrBlending)
    }

    @Test
    fun legacyPreviewModeMigratesToHq() {
        val normalized = RawCorrectionSettings(
            dualIso = DualIsoSettingsContract.LEGACY_MODE_PREVIEW
        ).normalizedDualIso()

        assertEquals(DualIsoSettingsContract.MODE_HQ, normalized.dualIso)
    }

    @Test
    fun forcedProcessingUsesHistogramMatching() {
        val normalized = RawCorrectionSettings(
            dualIso = DualIsoSettingsContract.MODE_HQ,
            dualIsoForced = true,
            dualIsoMatchMethod = DualIsoSettingsContract.MATCH_ISO
        ).normalizedDualIso()

        assertEquals(
            DualIsoSettingsContract.MATCH_HISTOGRAM,
            normalized.dualIsoMatchMethod
        )
    }

    @Test
    fun invalidValuesAreClampedBeforeNativeApplication() {
        val normalized = RawCorrectionSettings(
            dualIso = 99,
            dualIsoPattern = 99,
            dualIsoMatchMethod = 99,
            dualIsoEvCorrection = Float.NaN,
            dualIsoBlackDelta = 999,
            dualIsoInterpolation = 99,
            dualIsoFrBlending = false
        ).normalizedDualIso()

        assertEquals(DualIsoSettingsContract.MODE_OFF, normalized.dualIso)
        assertEquals(DualIsoSettingsContract.PATTERN_LAST, normalized.dualIsoPattern)
        assertEquals(DualIsoSettingsContract.MATCH_ISO, normalized.dualIsoMatchMethod)
        assertEquals(DualIsoSettingsContract.EV_AUTO, normalized.dualIsoEvCorrection)
        assertEquals(
            DualIsoSettingsContract.BLACK_DELTA_MAX,
            normalized.dualIsoBlackDelta
        )
        assertEquals(1, normalized.dualIsoInterpolation)
        assertTrue(normalized.dualIsoFrBlending)
    }

    @Test
    fun manualCorrectionValuesKeepNativeUnits() {
        val normalized = RawCorrectionSettings(
            dualIsoEvCorrection = -2.345f,
            dualIsoBlackDelta = 37
        ).normalizedDualIso()

        assertEquals(-2.345f, normalized.dualIsoEvCorrection)
        assertEquals(37, normalized.dualIsoBlackDelta)
    }

    @Test
    fun successfulPreviewStateResolvesAutomaticExportValues() {
        val resolved = RawCorrectionSettings(
            dualIso = DualIsoSettingsContract.MODE_HQ
        ).withResolvedDualIsoState(
            floatArrayOf(1f, 3f, 2f, -2.345f, 37f)
        )

        assertEquals(3, resolved.dualIsoPattern)
        assertEquals(DualIsoSettingsContract.MATCH_ISO, resolved.dualIsoMatchMethod)
        assertEquals(-2.345f, resolved.dualIsoEvCorrection)
        assertEquals(37, resolved.dualIsoBlackDelta)
    }

    @Test
    fun everyFramePatternAndFailedPreviewRemainUnresolved() {
        val everyFrame = RawCorrectionSettings(
            dualIso = DualIsoSettingsContract.MODE_HQ,
            dualIsoPattern = DualIsoSettingsContract.PATTERN_AUTO_EVERY_FRAME
        )
        val resolved = everyFrame.withResolvedDualIsoState(
            floatArrayOf(1f, 2f, 1f, -3f, 12f)
        )

        assertEquals(
            DualIsoSettingsContract.PATTERN_AUTO_EVERY_FRAME,
            resolved.dualIsoPattern
        )
        assertEquals(
            everyFrame,
            everyFrame.withResolvedDualIsoState(
                floatArrayOf(0f, 2f, 1f, -3f, 12f)
            )
        )
    }

    @Test
    fun successfulPreviewDoesNotRewriteManualUserControls() {
        val manual = RawCorrectionSettings(
            dualIso = DualIsoSettingsContract.MODE_HQ,
            dualIsoPattern = 2,
            dualIsoMatchMethod = DualIsoSettingsContract.MATCH_ISO,
            dualIsoEvCorrection = -4f,
            dualIsoBlackDelta = 7
        ).normalizedDualIso()

        assertEquals(
            manual,
            manual.withResolvedDualIsoState(
                floatArrayOf(1f, 4f, 2f, -2f, 50f)
            )
        )
    }

    @Test
    fun externalDarkFramePreviewDoesNotBakeAutomaticValuesIntoExport() {
        val resolved = RawCorrectionSettings(
            dualIso = DualIsoSettingsContract.MODE_HQ,
            darkFrameEnabled = 1,
            darkFrameUri = "content://dark-frame",
            dualIsoEvCorrection = DualIsoSettingsContract.EV_AUTO,
            dualIsoBlackDelta = DualIsoSettingsContract.BLACK_DELTA_AUTO
        ).withResolvedDualIsoState(
            floatArrayOf(1f, 3f, 2f, -2.345f, 37f)
        )

        assertEquals(0, resolved.darkFrameEnabled)
        assertEquals(DualIsoSettingsContract.PATTERN_AUTO, resolved.dualIsoPattern)
        assertEquals(DualIsoSettingsContract.EV_AUTO, resolved.dualIsoEvCorrection)
        assertEquals(
            DualIsoSettingsContract.BLACK_DELTA_AUTO,
            resolved.dualIsoBlackDelta
        )
    }

    @Test
    fun externalDarkFrameNormalizationPreservesManualUserValues() {
        val normalized = RawCorrectionSettings(
            dualIso = DualIsoSettingsContract.MODE_HQ,
            darkFrameEnabled = 1,
            dualIsoPattern = 2,
            dualIsoEvCorrection = -4f,
            dualIsoBlackDelta = 7
        ).normalizedForExport()

        assertEquals(0, normalized.darkFrameEnabled)
        assertEquals(2, normalized.dualIsoPattern)
        assertEquals(-4f, normalized.dualIsoEvCorrection)
        assertEquals(7, normalized.dualIsoBlackDelta)
    }

    @Test
    fun forceTransitionsRearmPatternMatchingAndCorrections() {
        val source = RawCorrectionSettings(
            dualIso = DualIsoSettingsContract.MODE_HQ,
            dualIsoPattern = 4,
            dualIsoMatchMethod = DualIsoSettingsContract.MATCH_HISTOGRAM,
            dualIsoEvCorrection = -2f,
            dualIsoBlackDelta = 33
        )

        val forced = source.withDualIsoForced(true)
        assertTrue(forced.dualIsoForced)
        assertEquals(DualIsoSettingsContract.PATTERN_AUTO, forced.dualIsoPattern)
        assertEquals(DualIsoSettingsContract.MATCH_HISTOGRAM, forced.dualIsoMatchMethod)
        assertEquals(DualIsoSettingsContract.EV_AUTO, forced.dualIsoEvCorrection)
        assertEquals(DualIsoSettingsContract.BLACK_DELTA_AUTO, forced.dualIsoBlackDelta)

        val metadataDriven = forced.withDualIsoForced(false)
        assertFalse(metadataDriven.dualIsoForced)
        assertEquals(DualIsoSettingsContract.MATCH_ISO, metadataDriven.dualIsoMatchMethod)
        assertEquals(DualIsoSettingsContract.EV_AUTO, metadataDriven.dualIsoEvCorrection)
        assertEquals(
            DualIsoSettingsContract.BLACK_DELTA_AUTO,
            metadataDriven.dualIsoBlackDelta
        )
    }

    @Test
    fun validDisoMetadataReconcilesLegacyForcedReceipt() {
        val reconciled = RawCorrectionSettings(
            dualIso = DualIsoSettingsContract.MODE_HQ,
            dualIsoForced = true,
            dualIsoPattern = 4,
            dualIsoMatchMethod = DualIsoSettingsContract.MATCH_HISTOGRAM,
            dualIsoEvCorrection = -2f,
            dualIsoBlackDelta = 33
        ).reconciledWithDualIsoMetadata(dualIsoValid = true)

        assertFalse(reconciled.dualIsoForced)
        assertEquals(DualIsoSettingsContract.PATTERN_AUTO, reconciled.dualIsoPattern)
        assertEquals(DualIsoSettingsContract.MATCH_ISO, reconciled.dualIsoMatchMethod)
        assertEquals(DualIsoSettingsContract.EV_AUTO, reconciled.dualIsoEvCorrection)
        assertEquals(
            DualIsoSettingsContract.BLACK_DELTA_AUTO,
            reconciled.dualIsoBlackDelta
        )
    }

    @Test
    fun patternChangeRearmsOnlyForcedOrHistogramCorrections() {
        val histogram = RawCorrectionSettings(
            dualIsoMatchMethod = DualIsoSettingsContract.MATCH_HISTOGRAM,
            dualIsoEvCorrection = -2f,
            dualIsoBlackDelta = 33
        ).withDualIsoPattern(3)
        assertEquals(DualIsoSettingsContract.EV_AUTO, histogram.dualIsoEvCorrection)
        assertEquals(DualIsoSettingsContract.BLACK_DELTA_AUTO, histogram.dualIsoBlackDelta)

        val iso = RawCorrectionSettings(
            dualIsoMatchMethod = DualIsoSettingsContract.MATCH_ISO,
            dualIsoEvCorrection = -2f,
            dualIsoBlackDelta = 33
        ).withDualIsoPattern(3)
        assertEquals(-2f, iso.dualIsoEvCorrection)
        assertEquals(33, iso.dualIsoBlackDelta)
    }

    @Test
    fun snapshotFenceRejectsOlderAsyncCompletion() {
        val fence = DualIsoSnapshotFence()
        val first = fence.beginUpdate()
        val second = fence.beginUpdate()

        fence.completeUpdate(first)
        assertEquals(null, fence.readyToken())

        fence.completeUpdate(second)
        assertEquals(second, fence.readyToken())
        assertTrue(fence.isReady(second))
        assertFalse(fence.isReady(first))
        var commits = 0
        assertFalse(fence.commitIfReady(first) { commits++ })
        assertTrue(fence.commitIfReady(second) { commits++ })
        assertEquals(1, commits)
    }
}
