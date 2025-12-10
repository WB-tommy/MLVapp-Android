package fm.magiclantern.forum.export

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fm.magiclantern.forum.export.ExportAllCodecsTest.ExportResult
import fm.magiclantern.forum.export.ExportAllCodecsTest.TestFdProvider
import fm.magiclantern.forum.nativeInterface.NativeLib
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Benchmark test for encoders.
 *
 * This test verifies the availability and initialization of specific encoder configurations
 * by forcing either Hardware-Only or Software-Only paths.
 *
 * Run with:
 *   ./gradlew connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=fm.magiclantern.forum.export.EncoderBenchmarkTest
 */
@RunWith(AndroidJUnit4::class)
class EncoderBenchmarkTest {

    companion object {
        private const val TAG = "EncoderBenchmark"
        private const val TEST_MLV_PATH = "/data/local/tmp/test.mlv"
    }

    private lateinit var context: Context
    private lateinit var outputDir: File

    private lateinit var testFile: File

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext

        outputDir = File(context.cacheDir, "benchmark_test")
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        testFile = File(TEST_MLV_PATH)

        Log.i(TAG, "═══════════════════════════════════════════")
        Log.i(TAG, "  Encoder Benchmark Suite")
        Log.i(TAG, "═══════════════════════════════════════════")
    }

    // =========================================================================
    // H.264 Tests
    // =========================================================================

    @Test
    fun benchmarkH264_Hardware() {
        runBenchmark("H264_HW", ExportCodec.H264, forceHardware = true)
    }

    @Test
    fun benchmarkH264_Software() {
        runBenchmark("H264_SW", ExportCodec.H264, forceSoftware = true)
    }

    // =========================================================================
    // H.265 (HEVC) 8-bit Tests
    // =========================================================================

    @Test
    fun benchmarkH265_8bit_Hardware() {
        runBenchmark(
            "H265_8bit_HW",
            ExportCodec.H265,
            h265BitDepth = H265BitDepth.BIT_8,
            forceHardware = true
        )
    }

    @Test
    fun benchmarkH265_8bit_Software() {
        runBenchmark(
            "H265_8bit_SW",
            ExportCodec.H265,
            h265BitDepth = H265BitDepth.BIT_8,
            forceSoftware = true
        )
    }

    // =========================================================================
    // H.265 (HEVC) 10-bit Tests
    // =========================================================================

    @Test
    fun benchmarkH265_10bit_Hardware() {
        // Skip test if device doesn't support 10-bit HEVC hardware encoding
        org.junit.Assume.assumeTrue("Device does not support 10-bit HEVC Hardware Encoding", is10BitHevcSupported())
        
        runBenchmark(
            "H265_10bit_HW",
            ExportCodec.H265,
            h265BitDepth = H265BitDepth.BIT_10,
            forceHardware = true
        )
    }

    @Test
    fun benchmarkH265_10bit_Software() {
        // Skip test if device doesn't support 10-bit HEVC hardware encoding
        org.junit.Assume.assumeTrue("Device does not support 10-bit HEVC Hardware Encoding", is10BitHevcSupported())

        runBenchmark(
            "H265_10bit_SW",
            ExportCodec.H265,
            h265BitDepth = H265BitDepth.BIT_10,
            forceSoftware = true
        )
    }

    // =========================================================================
    // Helper Functions
    // =========================================================================

    private fun is10BitHevcSupported(): Boolean {
        try {
            val list = android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS)
            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                
                try {
                    val caps = info.getCapabilitiesForType("video/hevc")
                    if (caps != null) {
                        for (profile in caps.profileLevels) {
                            // HEVCProfileMain10 = 0x02
                            if (profile.profile == android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10) {
                                return true
                            }
                        }
                    }
                } catch (e: IllegalArgumentException) {
                    // Type not supported by this codec, continue
                    continue
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check 10-bit support: ${e.message}")
        }
        return false
    }

    private fun runBenchmark(
        testName: String,
        codec: ExportCodec,
        h265BitDepth: H265BitDepth = H265BitDepth.BIT_8,
        forceHardware: Boolean = false,
        forceSoftware: Boolean = false
    ) {
        val type = when {
            forceHardware -> "HARDWARE"
            forceSoftware -> "SOFTWARE"
            else -> "DEFAULT"
        }
        Log.i(TAG, "Starting Benchmark: $testName [$type]")

        val options = ExportOptions(
            codec = codec,
            codecOption = 0,
            cdngVariant = CdngVariant.UNCOMPRESSED,
            cdngNaming = CdngNaming.DEFAULT,
            includeAudio = false,
            enableRawFixes = true,
            frameRateOverrideEnabled = false,
            frameRateValue = 0f,
            sourceFileName = "bench_$testName.mlv",
            clipUriPath = outputDir.absolutePath,
            audioTempDir = outputDir.absolutePath,
            h264Container = H264Container.MP4,
            h265BitDepth = h265BitDepth,
            h265Container = H265Container.MP4,
            forceHardware = forceHardware,
            forceSoftware = forceSoftware
        )

        val startTime = System.currentTimeMillis()

        exportWithConfig(testFile, options)

        Log.i(TAG, "$testName takes ${(System.currentTimeMillis() - startTime) / 1000}")
    }

    private fun exportWithConfig(testFile: File, options: ExportOptions): ExportResult {
        val outputName = "test_${options.codec}"
        val tempAudioDir = if (options.codec == ExportCodec.AUDIO_ONLY) {
            File(context.cacheDir, "export_audio_temp").also { it.mkdirs() }
        } else null

        val outputFile = File(outputDir, "$outputName.mp4") // Single file with extension

        // Delete existing output
        if (outputFile.exists()) {
            if (outputFile.isDirectory) {
                outputFile.deleteRecursively()
            } else {
                outputFile.delete()
            }
        }

        // Create output directory for image sequences
        val exportOutputDir = outputDir

        // Open the MLV file
        val fd = ParcelFileDescriptor.open(
            testFile,
            ParcelFileDescriptor.MODE_READ_ONLY
        )

        val clipFd = fd.detachFd()

        // Create file provider for output
        val fileProvider = TestFdProvider(exportOutputDir)

        val startTime = System.currentTimeMillis()

        try {
            NativeLib.exportHandler(
                memSize = 512 * 1024 * 1024L, // 512MB
                cpuCores = Runtime.getRuntime().availableProcessors(),
                clipFds = intArrayOf(clipFd),
                options = options,
                progressListener = ProgressListener { progress ->
                    if (progress % 25 == 0) {
                        Log.d(TAG, "  Progress: $progress%")
                    }
                },
                fileProvider = fileProvider
            )
        } finally {
            // fd is already detached, no need to close
        }

        val duration = System.currentTimeMillis() - startTime

        val outputSize = if (outputFile.exists()) {
            if (outputFile.isDirectory) {
                outputFile.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            } else {
                outputFile.length()
            }
        } else {
            // For single files, check if any file was created in the output dir with the expected name pattern
            val baseName = "test_${options.codec}"
            val matchingFiles =
                outputDir.listFiles()?.filter { it.name.startsWith(baseName) } ?: emptyList()
            matchingFiles.sumOf { it.length() }
        }

        val errorMsg = if (outputSize == 0L) {
            "No output file created. Check native logs with: adb logcat -s FFmpegHandler,ExportHandler"
        } else ""

        return ExportResult(
            configName = options.codec.toString(),
            success = outputSize > 0,
            error = errorMsg,
            outputPath = outputFile.absolutePath,
            outputSize = outputSize,
            durationMs = duration
        )
    }
}
