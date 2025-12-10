package fm.magiclantern.forum.export

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fm.magiclantern.forum.nativeInterface.NativeLib
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Comprehensive export test that exports a test clip with ALL codec and option combinations.
 * 
 * This test will create real output files for verification.
 * 
 * SETUP: First push test MLV file to device:
 *   adb push /path/to/M06-1456.MLV /data/local/tmp/test.mlv
 * 
 * Run with:
 * ./gradlew connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=fm.magiclantern.forum.export.ExportAllCodecsTest#exportAllCodecsAndOptions
 * 
 * Output files will be in: /sdcard/Android/media/<package>/export_test_output/
 * Or customize with: -Pandroid.testInstrumentationRunnerArguments.exportPath=/sdcard/Download/MLV_Exports
 * (Ensure app has permissions to write to the custom path)
 * 
 * To see detailed native logs:
 *   adb logcat -s FFmpegHandler,ExportHandler,ExportAllCodecsTest
 */
@RunWith(AndroidJUnit4::class)
class ExportAllCodecsTest {

    companion object {
        private const val TAG = "ExportAllCodecsTest"
        private const val TEST_MLV_PATH = "/data/local/tmp/test.mlv"
    }

    private lateinit var context: Context
    private lateinit var outputDir: File
    private lateinit var testFile: File

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext

        // Check for custom output path argument
        val args = InstrumentationRegistry.getArguments()
        val customPath = args.getString("exportPath")

        if (!customPath.isNullOrEmpty()) {
            outputDir = File(customPath)
            Log.i(TAG, "Using custom output directory: $customPath")
        } else {
            val sharedRoot = getPersistentMediaDir(context)
            // Use app-specific media directory which survives app uninstall on modern Android
            outputDir = File(sharedRoot, "export_test_output")
        }

        if (!outputDir.exists()) {
            val created = outputDir.mkdirs()
            Log.i(TAG, "Output directory created: $created")
        }

        testFile = File(TEST_MLV_PATH)

        Log.i(TAG, "Test MLV file: ${testFile.absolutePath}")
        Log.i(TAG, "Output directory: ${outputDir.absolutePath}")
        Log.i(TAG, "Output dir exists: ${outputDir.exists()}, writable: ${outputDir.canWrite()}")
    }

    private fun getPersistentMediaDir(context: Context): File {
        val externalFiles = context.getExternalFilesDir(null)
        val androidDir = externalFiles?.parentFile?.parentFile?.parentFile
        val mediaDir = androidDir?.let { File(it, "media/${context.packageName}") }
        val target = mediaDir ?: File("/sdcard/Android/media/${context.packageName}")
        if (!target.exists()) {
            val created = target.mkdirs()
            Log.i(TAG, "Media directory created at ${target.absolutePath}: $created")
        }
        return target
    }

    private fun moveAudioTempToOutput(
        tempDir: File,
        baseName: String,
        targetDir: File
    ): File? {
        val wav = tempDir.listFiles()?.firstOrNull { file ->
            file.isFile &&
                    file.extension.equals("wav", ignoreCase = true) &&
                    (file.nameWithoutExtension == baseName || file.name.startsWith("${baseName}_"))
        } ?: return null

        if (!targetDir.exists()) targetDir.mkdirs()
        val target = File(targetDir, "$baseName.wav")
        wav.copyTo(target, overwrite = true)
        wav.delete()
        return target
    }

    /**
     * Generate all export configurations to test
     */
    private fun generateAllExportConfigurations(): List<ExportTestConfig> {
        return mutableListOf<ExportTestConfig>().apply {
            addAll(generateCinemaDNGConfigs())
            addAll(generateProResConfigs())
            addAll(generateH264Configs())
            addAll(generateH265Configs())
            addAll(generateDNxHRConfigs())
            addAll(generateVP9Configs())
            addAll(generateTiffConfigs())
            addAll(generatePngConfigs())
            addAll(generateJpeg2000Configs())
            addAll(generateAudioOnlyConfigs())
        }
    }

    private fun generateCinemaDNGConfigs(): List<ExportTestConfig> {
        val configs = mutableListOf<ExportTestConfig>()
        CdngVariant.entries.forEach { variant ->
            configs.add(
                ExportTestConfig(
                    name = "CDNG_${variant.name}",
                    settings = ExportSettings(
                        codec = ExportCodec.CINEMA_DNG,
                        cdngVariant = variant
                    ),
                    extension = "" // Creates folder with DNG files
                )
            )
        }
        return configs
    }

    private fun generateProResConfigs(): List<ExportTestConfig> {
        val configs = mutableListOf<ExportTestConfig>()
        ProResProfile.entries.forEach { profile ->
            ProResEncoder.entries.forEach { encoder ->
                // Skip prores_aw for 4444 profiles (not supported)
                if (encoder == ProResEncoder.FFMPEG_ANATOLYI &&
                    (profile == ProResProfile.PRORES_4444 || profile == ProResProfile.PRORES_4444_XQ)) {
                    return@forEach
                }
                configs.add(
                    ExportTestConfig(
                        name = "ProRes_${profile.name}_${encoder.name}",
                        settings = ExportSettings(
                            codec = ExportCodec.PRORES,
                            proResProfile = profile,
                            proResEncoder = encoder
                        ),
                        extension = ".mov"
                    )
                )
            }
        }
        return configs
    }

    private fun generateH264Configs(): List<ExportTestConfig> {
        val configs = mutableListOf<ExportTestConfig>()
        H264Quality.entries.forEach { quality ->
            H264Container.entries.forEach { container ->
                val ext = when (container) {
                    H264Container.MOV -> ".mov"
                    H264Container.MP4 -> ".mp4"
                    H264Container.MKV -> ".mkv"
                }
                configs.add(
                    ExportTestConfig(
                        name = "H264_${quality.name}_${container.name}",
                        settings = ExportSettings(
                            codec = ExportCodec.H264,
                            h264Quality = quality,
                            h264Container = container
                        ),
                        extension = ext
                    )
                )
            }
        }
        return configs
    }

    private fun generateH265Configs(): List<ExportTestConfig> {
        val configs = mutableListOf<ExportTestConfig>()
        H265BitDepth.entries.forEach { bitDepth ->
            H265Quality.entries.forEach { quality ->
                H265Container.entries.forEach { container ->
                    val ext = when (container) {
                        H265Container.MOV -> ".mov"
                        H265Container.MP4 -> ".mp4"
                        H265Container.MKV -> ".mkv"
                    }
                    configs.add(
                        ExportTestConfig(
                            name = "H265_${bitDepth.name}_${quality.name}_${container.name}",
                            settings = ExportSettings(
                                codec = ExportCodec.H265,
                                h265BitDepth = bitDepth,
                                h265Quality = quality,
                                h265Container = container
                            ),
                            extension = ext
                        )
                    )
                }
            }
        }
        return configs
    }

    private fun generateDNxHRConfigs(): List<ExportTestConfig> {
        val configs = mutableListOf<ExportTestConfig>()
        DnxhrProfile.entries.forEach { profile ->
            configs.add(
                ExportTestConfig(
                    name = "DNxHR_${profile.name}",
                    settings = ExportSettings(
                        codec = ExportCodec.DNXHR,
                        dnxhrProfile = profile
                    ),
                    extension = ".mov"
                )
            )
        }
        return configs
    }

    private fun generateDNxHDConfigs(): List<ExportTestConfig> {
        val configs = mutableListOf<ExportTestConfig>()
        DnxhdProfile.entries.forEach { profile ->
            configs.add(
                ExportTestConfig(
                    name = "DNxHR_${profile.name}",
                    settings = ExportSettings(
                        codec = ExportCodec.DNXHR,
                        dnxhdProfile = profile
                    ),
                    extension = ".mov"
                )
            )
        }
        return configs
    }

    private fun generateVP9Configs(): List<ExportTestConfig> {
        val configs = mutableListOf<ExportTestConfig>()
        Vp9Quality.entries.forEach { quality ->
            configs.add(
                ExportTestConfig(
                    name = "VP9_${quality.name}",
                    settings = ExportSettings(
                        codec = ExportCodec.VP9,
                        vp9Quality = quality
                    ),
                    extension = ".webm"
                )
            )
        }
        return configs
    }

    private fun generateTiffConfigs(): List<ExportTestConfig> {
        return listOf(
            ExportTestConfig(
                name = "TIFF_16bit",
                settings = ExportSettings(codec = ExportCodec.TIFF),
                extension = "" // Creates folder with TIFF files
            )
        )
    }

    private fun generatePngConfigs(): List<ExportTestConfig> {
        val configs = mutableListOf<ExportTestConfig>()
        PngBitDepth.entries.forEach { bitDepth ->
            configs.add(
                ExportTestConfig(
                    name = "PNG_${bitDepth.name}",
                    settings = ExportSettings(
                        codec = ExportCodec.PNG,
                        pngBitDepth = bitDepth
                    ),
                    extension = "" // Creates folder with PNG files
                )
            )
        }
        return configs
    }

    private fun generateJpeg2000Configs(): List<ExportTestConfig> {
        return listOf(
            ExportTestConfig(
                name = "JPEG2000",
                settings = ExportSettings(codec = ExportCodec.JPEG2000),
                extension = "" // Creates folder with JP2 files
            )
        )
    }

    private fun generateAudioOnlyConfigs(): List<ExportTestConfig> {
        return listOf(
            ExportTestConfig(
                name = "AudioOnly",
                settings = ExportSettings(codec = ExportCodec.AUDIO_ONLY),
                extension = ".wav"
            )
        )
    }

    @Test
    fun listAllExportConfigurations() {
        Log.i(TAG, "Checking test file: ${testFile.absolutePath}")
        Log.i(TAG, "File exists: ${testFile.exists()}, readable: ${testFile.canRead()}, size: ${testFile.length()}")

        if (!testFile.exists()) {
            Log.e(TAG, "Test MLV file not found at: ${testFile.absolutePath}")
            throw AssertionError("Test MLV file not found at: ${testFile.absolutePath}")
        }

        val configs = generateAllExportConfigurations()

        Log.i(TAG, "")
        Log.i(TAG, "╔════════════════════════════════════════════════════════════╗")
        Log.i(TAG, "║         All Export Configurations (${configs.size} total)              ║")
        Log.i(TAG, "╚════════════════════════════════════════════════════════════╝")
        Log.i(TAG, "")

        var index = 1
        ExportCodec.entries.forEach { codec ->
            val codecConfigs = configs.filter { it.settings.codec == codec }
            Log.i(TAG, "── ${codec.displayName} (${codecConfigs.size} configurations) ──")
            codecConfigs.forEach { config ->
                Log.i(TAG, "  ${index++}. ${config.name}")
            }
            Log.i(TAG, "")
        }

        Log.i(TAG, "Total configurations: ${configs.size}")
    }

    @Test
    fun exportCDNG() {
        val configs = generateCinemaDNGConfigs()
        configs.forEachIndexed { index, config ->
            Log.i(TAG, "────────────────────────────────────────")
            Log.i(TAG, "[${index + 1}/${configs.size}] Exporting: ${config.name}")
            Log.i(TAG, "  Codec: ${config.settings.codec.displayName}")

            val result = try {
                exportWithConfig(testFile, config)
            } catch (e: Exception) {
                Log.e(TAG, "  ✗ FAILED: ${e.message}", e)
                Log.e(TAG, "  Stack trace: ${e.stackTraceToString()}")
                ExportResult(config.name, false, e.message ?: "Unknown error", 0)
            }

            if (result.success) Log.d(TAG, "O Success: ${config.name}")

            if (result.success) {
                Log.i(TAG, "  ✓ SUCCESS - Output: ${result.outputPath}")
                Log.i(TAG, "  Size: ${formatFileSize(result.outputSize)}")
            } else {
                Log.e(TAG, "  ✗ FAILED: ${result.error}")
            }
        }
    }

    @Test
    fun exportH265() {
        val configs = generateH265Configs()
        configs.forEachIndexed { index, config ->
            Log.i(TAG, "────────────────────────────────────────")
            Log.i(TAG, "[${index + 1}/${configs.size}] Exporting: ${config.name}")
            Log.i(TAG, "  Codec: ${config.settings.codec.displayName}")

            val result = try {
                exportWithConfig(testFile, config)
            } catch (e: Exception) {
                Log.e(TAG, "  ✗ FAILED: ${e.message}", e)
                Log.e(TAG, "  Stack trace: ${e.stackTraceToString()}")
                ExportResult(config.name, false, e.message ?: "Unknown error", 0)
            }

            if (result.success) Log.d(TAG, "O Success: ${config.name}")

            if (result.success) {
                Log.i(TAG, "  ✓ SUCCESS - Output: ${result.outputPath}")
                Log.i(TAG, "  Size: ${formatFileSize(result.outputSize)}")
            } else {
                Log.e(TAG, "  ✗ FAILED: ${result.error}")
            }
        }
    }

    @Test
    fun exportPNG() {
        val configs = generatePngConfigs()
        configs.forEachIndexed { index, config ->
            Log.i(TAG, "────────────────────────────────────────")
            Log.i(TAG, "[${index + 1}/${configs.size}] Exporting: ${config.name}")
            Log.i(TAG, "  Codec: ${config.settings.codec.displayName}")

            val result = try {
                exportWithConfig(testFile, config)
            } catch (e: Exception) {
                Log.e(TAG, "  ✗ FAILED: ${e.message}", e)
                Log.e(TAG, "  Stack trace: ${e.stackTraceToString()}")
                ExportResult(config.name, false, e.message ?: "Unknown error", 0)
            }

            if (result.success) Log.d(TAG, "O Success: ${config.name}")

            if (result.success) {
                Log.i(TAG, "  ✓ SUCCESS - Output: ${result.outputPath}")
                Log.i(TAG, "  Size: ${formatFileSize(result.outputSize)}")
            } else {
                Log.e(TAG, "  ✗ FAILED: ${result.error}")
            }
        }
    }

    @Test
    fun exportAudioOnly() {
        val configs = generateAudioOnlyConfigs()
        configs.forEachIndexed { index, config ->
            Log.i(TAG, "────────────────────────────────────────")
            Log.i(TAG, "[${index + 1}/${configs.size}] Exporting: ${config.name}")
            Log.i(TAG, "  Codec: ${config.settings.codec.displayName}")

            val result = try {
                exportWithConfig(testFile, config)
            } catch (e: Exception) {
                Log.e(TAG, "  ✗ FAILED: ${e.message}", e)
                Log.e(TAG, "  Stack trace: ${e.stackTraceToString()}")
                ExportResult(config.name, false, e.message ?: "Unknown error", 0)
            }

            if (result.success) Log.d(TAG, "O Success: ${config.name}")

            if (result.success) {
                Log.i(TAG, "  ✓ SUCCESS - Output: ${result.outputPath}")
                Log.i(TAG, "  Size: ${formatFileSize(result.outputSize)}")
            } else {
                Log.e(TAG, "  ✗ FAILED: ${result.error}")
            }
        }
    }

    @Test
    fun exportAllCodecsAndOptions() {
        val configs = generateAllExportConfigurations()
        val results = mutableListOf<ExportResult>()

        Log.i(TAG, "")
        Log.i(TAG, "╔════════════════════════════════════════════════════════════╗")
        Log.i(TAG, "║              Starting Export Tests                         ║")
        Log.i(TAG, "║              ${configs.size} configurations to test                    ║")
        Log.i(TAG, "╚════════════════════════════════════════════════════════════╝")
        Log.i(TAG, "")

        configs.forEachIndexed { index, config ->
            Log.i(TAG, "────────────────────────────────────────")
            Log.i(TAG, "[${index + 1}/${configs.size}] Exporting: ${config.name}")
            Log.i(TAG, "  Codec: ${config.settings.codec.displayName}")

            val result = try {
                exportWithConfig(testFile, config)
            } catch (e: Exception) {
                Log.e(TAG, "  ✗ FAILED: ${e.message}", e)
                Log.e(TAG, "  Stack trace: ${e.stackTraceToString()}")
                ExportResult(config.name, false, e.message ?: "Unknown error", 0)
            }

            if (result.success) Log.d(TAG, "O Success: ${config.name}")
            results.add(result)

            if (result.success) {
                Log.i(TAG, "  ✓ SUCCESS - Output: ${result.outputPath}")
                Log.i(TAG, "  Size: ${formatFileSize(result.outputSize)}")
            } else {
                Log.e(TAG, "  ✗ FAILED: ${result.error}")
            }
        }
    }

    private fun exportWithConfig(testFile: File, config: ExportTestConfig): ExportResult {
        val outputName = "test_${config.name}"
        val tempAudioDir = if (config.settings.codec == ExportCodec.AUDIO_ONLY) {
            File(context.cacheDir, "export_audio_temp").also { it.mkdirs() }
        } else null
        
        // For image sequences, create a subdirectory
        // For video files, output directly with extension
        val isImageSequence = config.settings.codec in listOf(
            ExportCodec.CINEMA_DNG,
            ExportCodec.TIFF,
            ExportCodec.PNG,
            ExportCodec.JPEG2000
        )
        
        var outputFile = if (isImageSequence) {
            File(outputDir, outputName) // Directory for image sequences
        } else {
            File(outputDir, outputName + config.extension) // Single file with extension
        }
        
        // Delete existing output
        if (outputFile.exists()) {
            if (outputFile.isDirectory) {
                outputFile.deleteRecursively()
            } else {
                outputFile.delete()
            }
        }
        
        // Create output directory for image sequences
        val exportOutputDir = if (isImageSequence) {
            outputFile.mkdirs()
            outputFile
        } else {
            outputDir
        }

        // Open the MLV file
        val fd = ParcelFileDescriptor.open(
            testFile,
            ParcelFileDescriptor.MODE_READ_ONLY
        )

        val clipFd = fd.detachFd()
        
        // Create export options - use unique source name for each config so output files don't overwrite
        val uniqueSourceName = "test_${config.name}.mlv"
        val exportOptions = config.settings.toTestExportOptions(
            sourceFileName = uniqueSourceName,
            outputPath = exportOutputDir.absolutePath,
            audioTempDir = tempAudioDir?.absolutePath ?: exportOutputDir.absolutePath
        )
        
        // Create file provider for output
        val fileProvider = TestFdProvider(exportOutputDir)

        val startTime = System.currentTimeMillis()
        
        try {
            NativeLib.exportHandler(
                memSize = 512 * 1024 * 1024L, // 512MB
                cpuCores = Runtime.getRuntime().availableProcessors(),
                clipFds = intArrayOf(clipFd),
                options = exportOptions,
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
        
        // Check output - list all files in output directory for debugging
        Log.d(TAG, "  Checking output files in: ${exportOutputDir.absolutePath}")
        val filesInDir = exportOutputDir.listFiles() ?: emptyArray()
        Log.d(TAG, "  Files found: ${filesInDir.size}")
        filesInDir.forEach { f ->
            Log.d(TAG, "    - ${f.name} (${f.length()} bytes)")
        }
        
        // Check output
        // For audio-only we first write to a temp cache directory, then move the WAV to the output dir
        if (config.settings.codec == ExportCodec.AUDIO_ONLY && tempAudioDir != null) {
            if (config.settings.codec == ExportCodec.AUDIO_ONLY) {
                moveAudioTempToOutput(tempAudioDir, outputName, outputDir)?.let { moved ->
                    outputFile = moved
                }
            }
            tempAudioDir.deleteRecursively()
        }

        val outputSize = if (outputFile.exists()) {
            if (outputFile.isDirectory) {
                outputFile.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            } else {
                outputFile.length()
            }
        } else {
            // For single files, check if any file was created in the output dir with the expected name pattern
            val baseName = "test_${config.name}"
            val matchingFiles = outputDir.listFiles()?.filter { it.name.startsWith(baseName) } ?: emptyList()
            Log.d(TAG, "  Looking for files starting with '$baseName': found ${matchingFiles.size}")
            matchingFiles.sumOf { it.length() }
        }
        
        val errorMsg = if (outputSize == 0L) {
            "No output file created. Check native logs with: adb logcat -s FFmpegHandler,ExportHandler"
        } else ""

        return ExportResult(
            configName = config.name,
            success = outputSize > 0,
            error = errorMsg,
            outputPath = outputFile.absolutePath,
            outputSize = outputSize,
            durationMs = duration
        )
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1_000_000_000 -> String.format("%.2f GB", bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> String.format("%.2f MB", bytes / 1_000_000.0)
            bytes >= 1_000 -> String.format("%.2f KB", bytes / 1_000.0)
            else -> "$bytes bytes"
        }
    }

    // Helper extension to convert ExportSettings to ExportOptions for testing
    private fun ExportSettings.toTestExportOptions(
        sourceFileName: String,
        outputPath: String,
        audioTempDir: String
    ): ExportOptions {
        val codecOption = when (codec) {
            ExportCodec.CINEMA_DNG -> cdngVariant.nativeId
            else -> 0
        }

        return ExportOptions(
            codec = codec,
            codecOption = codecOption,
            cdngVariant = cdngVariant,
            cdngNaming = cdngNaming,
            includeAudio = includeAudio,
            enableRawFixes = true,
            frameRateOverrideEnabled = frameRate.enabled,
            frameRateValue = frameRate.value,
            sourceFileName = sourceFileName,
            clipUriPath = outputPath,
            audioTempDir = audioTempDir,
            stretchFactorX = 1.0f,
            stretchFactorY = 1.0f,
            proResProfile = proResProfile,
            proResEncoder = proResEncoder,
            h264Quality = h264Quality,
            h264Container = h264Container,
            h265BitDepth = h265BitDepth,
            h265Quality = h265Quality,
            h265Container = h265Container,
            pngBitDepth = pngBitDepth,
            dnxhrProfile = dnxhrProfile,
            vp9Quality = vp9Quality,
            debayerQuality = debayerQuality,
            smoothing = smoothing,
            resize = resize,
            hdrBlending = hdrBlending,
            antiAliasing = antiAliasing
        )
    }

    data class ExportTestConfig(
        val name: String,
        val settings: ExportSettings,
        val extension: String
    )

    data class ExportResult(
        val configName: String,
        val success: Boolean,
        val error: String = "",
        val outputSize: Long = 0,
        val outputPath: String = "",
        val durationMs: Long = 0
    )

    /**
     * Simple file-based provider for tests (no SAF needed)
     */
    class TestFdProvider(private val outputDir: File) {
        
        fun openFrameFd(frameIndex: Int, relativeName: String): Int {
            return createFile(relativeName)
        }

        fun openContainerFd(relativeName: String): Int {
            return createFile(relativeName)
        }

        fun openAudioFd(relativeName: String): Int {
            return createFile(relativeName)
        }

        private fun createFile(relativeName: String): Int {
            val file = File(outputDir, relativeName)
            file.parentFile?.mkdirs()
            if (!file.exists()) {
                file.createNewFile()
            }
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
            return pfd.detachFd()
        }
    }
}

