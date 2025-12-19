package fm.magiclantern.forum.export

import android.content.Context
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecList
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fm.magiclantern.forum.features.export.model.*
import fm.magiclantern.forum.nativeInterface.NativeLib
import org.junit.Before
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
 * SETUP:
 *   adb push /path/to/test.mlv /data/local/tmp/test.mlv
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
        private const val TEST_MLV_PATH = "/data/local/tmp/test.mlv"
    }

    private lateinit var context: Context
    private lateinit var outputDir: File
    private lateinit var testFile: File

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        outputDir = File(context.cacheDir, "hw_accel_test")
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }
        testFile = File(TEST_MLV_PATH)

        Log.i(TAG, "═══════════════════════════════════════════")
        Log.i(TAG, "  Hardware Acceleration Diagnostics")
        Log.i(TAG, "═══════════════════════════════════════════")
    }

    @Test
    fun testH264HardwareAcceleration() {
        if (!testFile.exists()) {
            Log.e(TAG, "Test file not found: ${testFile.absolutePath}")
            return
        }

        Log.i(TAG, "\n╔══════════════════════════════════════════╗")
        Log.i(TAG, "║  Testing H.264 Hardware Acceleration    ║")
        Log.i(TAG, "╚══════════════════════════════════════════╝\n")

        // Test with hardware flags enabled
        testExportWithHardwareFlags(
            codec = ExportCodec.H264, name = "H264_HW_Test", extension = ".mp4"
        )
    }

    @Test
    fun testH265HardwareAcceleration() {
        if (!testFile.exists()) {
            Log.e(TAG, "Test file not found: ${testFile.absolutePath}")
            return
        }

        Log.i(TAG, "\n╔══════════════════════════════════════════╗")
        Log.i(TAG, "║  Testing H.265 Hardware Acceleration    ║")
        Log.i(TAG, "╚══════════════════════════════════════════╝\n")

        // Test 8-bit
        Log.i(TAG, "─── Testing 8-bit H.265 ───")
        testExportWithHardwareFlags(
            codec = ExportCodec.H265,
            name = "H265_8bit_HW_Test",
            extension = ".mp4",
            h265BitDepth = H265BitDepth.BIT_8
        )

        // Test 10-bit
        Log.i(TAG, "\n─── Testing 10-bit H.265 ───")
        testExportWithHardwareFlags(
            codec = ExportCodec.H265,
            name = "H265_10bit_HW_Test",
            extension = ".mp4",
            h265BitDepth = H265BitDepth.BIT_10
        )
    }

    @Test
    fun testVP9HardwareAcceleration() {
        if (!testFile.exists()) {
            Log.e(TAG, "Test file not found: ${testFile.absolutePath}")
            return
        }

        Log.i(TAG, "\n╔══════════════════════════════════════════╗")
        Log.i(TAG, "║  Testing VP9 Hardware Acceleration      ║")
        Log.i(TAG, "╚══════════════════════════════════════════╝\n")

        testExportWithHardwareFlags(
            codec = ExportCodec.VP9, name = "VP9_HW_Test", extension = ".webm"
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
            h265Container = H265Container.MP4
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
