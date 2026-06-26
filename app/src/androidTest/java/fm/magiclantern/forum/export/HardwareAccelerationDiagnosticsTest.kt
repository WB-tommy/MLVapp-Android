package fm.magiclantern.forum.export

import android.content.Context
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecList
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fm.magiclantern.forum.features.export.model.*
import fm.magiclantern.forum.nativeInterface.NativeLib
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File


/**
 * Test to diagnose hardware acceleration failures.
 *
 * This test helps identify why hardware encoders fail by:
 * 1. Scanning device capabilities
 * 2. Testing each hardware codec option
 * 3. Logging detailed hardware vs software usage
 *
 * Run with:
 *   ./gradlew connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=fm.magiclantern.forum.export.HardwareAccelerationDiagnosticsTest
 *
 * View logs:
 *   adb logcat -s HWAccelDiag,FFmpegPresets,FFmpegUtils,FFmpegHandler
 */
@RunWith(AndroidJUnit4::class)
class HardwareAccelerationDiagnosticsTest {

    companion object {
        private const val TAG = "HWAccelDiag"
    }

    private lateinit var context: Context
    private lateinit var outputDir: File

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        outputDir = File(context.cacheDir, "hw_accel_test")
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        Log.i(TAG, "═══════════════════════════════════════════")
        Log.i(TAG, "  Hardware Acceleration Diagnostics")
        Log.i(TAG, "═══════════════════════════════════════════")
    }

    @Test
    fun testH264HardwareAcceleration() {
        Log.i(TAG, "\n╔══════════════════════════════════════════╗")
        Log.i(TAG, "║  Testing H.264 Hardware Acceleration    ║")
        Log.i(TAG, "╚══════════════════════════════════════════╝\n")

        val result = testExportWithHardwareFlags(
            codec = ExportCodec.H264, name = "H264_HW_Test", extension = ".mp4"
        )
        assertDiagnosticSuccess(result)
    }

    @Test
    fun testH2658BitHardwareAcceleration() {
        Log.i(TAG, "\n╔══════════════════════════════════════════╗")
        Log.i(TAG, "║  Testing H.265 8-bit Hardware Accel     ║")
        Log.i(TAG, "╚══════════════════════════════════════════╝\n")

        val result = testExportWithHardwareFlags(
            codec = ExportCodec.H265,
            name = "H265_8bit_HW_Test",
            extension = ".mp4",
            h265BitDepth = H265BitDepth.BIT_8
        )
        assertDiagnosticSuccess(result)
    }

    @Test
    fun testH26510BitHardwareAcceleration() {
        Log.i(TAG, "\n╔══════════════════════════════════════════╗")
        Log.i(TAG, "║  Testing H.265 10-bit Hardware Accel    ║")
        Log.i(TAG, "╚══════════════════════════════════════════╝\n")

        val result = testExportWithHardwareFlags(
            codec = ExportCodec.H265,
            name = "H265_10bit_HW_Test",
            extension = ".mp4",
            h265BitDepth = H265BitDepth.BIT_10
        )
        assertDiagnosticSuccess(result)
    }

    @Test
    fun testVP9HardwareAcceleration() {
        Log.i(TAG, "\n╔══════════════════════════════════════════╗")
        Log.i(TAG, "║  Testing VP9 Hardware Acceleration      ║")
        Log.i(TAG, "╚══════════════════════════════════════════╝\n")

        val result = testExportWithHardwareFlags(
            codec = ExportCodec.VP9, name = "VP9_HW_Test", extension = ".webm"
        )
        assertDiagnosticSuccess(result)
    }

    @Test
    fun testVulkanHardwareDevice() {
        Log.i(TAG, "\n╔══════════════════════════════════════════╗")
        Log.i(TAG, "║  Testing FFmpeg Vulkan Device           ║")
        Log.i(TAG, "╚══════════════════════════════════════════╝\n")

        val success = NativeLib.testVulkanHardwareDevice()
        if (success) {
            Log.i(TAG, "✓ FFmpeg Vulkan device initialized successfully")
        } else {
            Log.e(TAG, "✗ FFmpeg Vulkan device initialization failed")
        }
        assertTrue("FFmpeg Vulkan device initialization failed", success)
    }

    @Ignore("Qualcomm Adreno currently SIGSEGVs in vkCreateComputePipelines for prores_ks_vulkan.")
    @Test
    fun testVulkanProResHardwareAcceleration() {
        Log.i(TAG, "\n╔══════════════════════════════════════════╗")
        Log.i(TAG, "║  Testing Vulkan ProRes Acceleration     ║")
        Log.i(TAG, "╚══════════════════════════════════════════╝\n")

        if (!NativeLib.testVulkanHardwareDevice()) {
            Log.e(TAG, "Skipping ProRes Vulkan encode test because Vulkan device init failed")
            assertTrue("ProRes Vulkan encode test cannot run because Vulkan device init failed", false)
        }

        val options = createBaseExportOptions(
            codec = ExportCodec.PRORES,
            name = "ProRes_Vulkan_HW_Test"
        ).copy(
            proResProfile = ProResProfile.PRORES_422_HQ,
            forceHardware = true,
            forceSoftware = false
        )

        val startTime = System.currentTimeMillis()
        val success = NativeLib.testVulkanProResEncoding(options)
        val duration = System.currentTimeMillis() - startTime
        if (success) {
            Log.i(TAG, "✓ Vulkan ProRes one-frame encode succeeded in ${duration}ms")
        } else {
            Log.e(TAG, "✗ Vulkan ProRes one-frame encode failed after ${duration}ms")
        }
        assertTrue("Vulkan ProRes one-frame encode failed after ${duration}ms", success)
    }

    @Test
    fun testVulkanHevc10Bit422HardwareAcceleration() {
        Log.i(TAG, "\n╔══════════════════════════════════════════╗")
        Log.i(TAG, "║  Testing Vulkan HEVC 10-bit 4:2:2      ║")
        Log.i(TAG, "╚══════════════════════════════════════════╝\n")

        if (!NativeLib.testVulkanHardwareDevice()) {
            Log.e(TAG, "Skipping HEVC Vulkan encode test because Vulkan device init failed")
            assertTrue("HEVC Vulkan encode test cannot run because Vulkan device init failed", false)
        }

        val options = createBaseExportOptions(
            codec = ExportCodec.H265,
            name = "HEVC_10bit_422_Vulkan_HW_Test",
            h265BitDepth = H265BitDepth.BIT_10
        ).copy(
            forceHardware = true,
            forceSoftware = false
        )

        val startTime = System.currentTimeMillis()
        val success = NativeLib.testVulkanHevc10Bit422Encoding(options)
        val duration = System.currentTimeMillis() - startTime
        if (success) {
            Log.i(TAG, "✓ Vulkan HEVC 10-bit 4:2:2 one-frame encode succeeded in ${duration}ms")
        } else {
            Log.e(TAG, "✗ Vulkan HEVC 10-bit 4:2:2 one-frame encode failed after ${duration}ms")
        }
        assertTrue(
            "Vulkan HEVC 10-bit 4:2:2 one-frame encode failed after ${duration}ms",
            success
        )
    }

    private fun testExportWithHardwareFlags(
        codec: ExportCodec,
        name: String,
        extension: String,
        h265BitDepth: H265BitDepth = H265BitDepth.BIT_10
    ): TestResult {
        // Populate hardware flags
        val baseOptions = createBaseExportOptions(
            codec = codec, name = name, h265BitDepth = h265BitDepth
        )

        return performExport(name, extension, baseOptions)
    }

    private fun assertDiagnosticSuccess(result: TestResult) {
        assertTrue(
            "${result.name} failed after ${result.durationMs}ms: ${result.error}",
            result.success
        )
    }

    private fun createBaseExportOptions(
        codec: ExportCodec, name: String, h265BitDepth: H265BitDepth = H265BitDepth.BIT_10
    ): ExportOptions {
        return ExportOptions(
            codec = codec,
            codecOption = 0,
            cdngVariant = CdngVariant.UNCOMPRESSED,
            cdngNaming = CdngNaming.DEFAULT,
            includeAudio = false,
            enableRawFixes = true,
            frameRateOverrideEnabled = false,
            frameRateValue = 0f,
            sourceFileName = "test_$name.mlv",
            clipUriPath = outputDir.absolutePath,
            audioTempDir = outputDir.absolutePath,
            h264Container = H264Container.MP4,
            h265BitDepth = h265BitDepth,
            h265Container = H265Container.MP4,
            forceHardware = true,
            forceSoftware = false
        )
    }

    private fun performExport(
        name: String, extension: String, options: ExportOptions
    ): TestResult {
        val startTime = System.currentTimeMillis()
        var success = false
        var errorMsg = ""

        try {
            Log.i(TAG, "Starting encoder diagnostic test...")
            // Call the diagnostic function directly
            // This tests encoder initialization without doing full export
            success = NativeLib.testEncoderConfiguration(options)

            if (success) {
                Log.i(TAG, "✓ Encoder initialized successfully!")
            } else {
                errorMsg =
                    "Encoder initialization failed (check logs: adb logcat -s FFmpegUtils)"
                Log.e(TAG, "✗ $errorMsg")
            }
        } catch (e: Exception) {
            success = false
            errorMsg = e.message ?: "Unknown error"
            Log.e(TAG, "✗ Test failed with exception: $errorMsg")
            Log.e(TAG, "Stack trace:", e)
        }

        val duration = System.currentTimeMillis() - startTime

        return TestResult(
            name = name,
            success = success,
            error = if (!success) errorMsg else "",
            durationMs = duration,
            outputSize = 0 // No output file created in this mode
        )
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_000_000 -> String.format("%.2f MB", bytes / 1_000_000.0)
            bytes >= 1_000 -> String.format("%.2f KB", bytes / 1_000.0)
            else -> "$bytes bytes"
        }
    }

    data class TestResult(
        val name: String,
        val success: Boolean,
        val error: String,
        val durationMs: Long,
        val outputSize: Long
    )
}
