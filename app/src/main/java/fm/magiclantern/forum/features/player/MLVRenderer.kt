package fm.magiclantern.forum.features.player

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import fm.magiclantern.forum.features.player.viewmodel.PlayerViewModel
import fm.magiclantern.forum.nativeInterface.NativeLib
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

/**
 * Preview renderer with two deliberately separate paths:
 *
 *  * Standard: native code returns fully processed RGB16 and GLES presents it.
 *  * Experimental RAW: native code only decodes Bayer uint16. GLES applies
 *    levels, Bayer-domain WB, bilinear demosaic, the colour matrix, and tone LUT.
 *
 * The experimental path never affects export and falls back to the standard path
 * when native decode or any required GLES resource is unavailable. For classic
 * MLV it deliberately bypasses low-level RAW corrections such as Dual ISO.
 */
class MlvRenderer(
    private val cpuCores: Int,
    private val viewModel: PlayerViewModel
) : GLSurfaceView.Renderer {

    private val tag = "MlvRenderer"

    private val presentationVertexShader = """
        #version 300 es
        layout(location = 0) in vec2 aPos;
        layout(location = 1) in vec2 aTex;
        uniform vec2 uScale;
        uniform vec2 uStretch;
        out vec2 vTex;
        void main() {
            vTex = aTex;
            vec2 stretched = aPos * uStretch;
            gl_Position = vec4(stretched * uScale, 0.0, 1.0);
        }
    """.trimIndent()

    private val standardFragmentShader = """
        #version 300 es
        precision highp float;
        uniform sampler2D uTexture;
        uniform int uVideoWidth;
        in vec2 vTex;
        out vec4 fragColor;
        void main() {
            // Three RG8 texels hold the little-endian bytes of one RGB16 pixel.
            float texW = float(uVideoWidth * 3);
            float base = floor(vTex.x * float(uVideoWidth)) * 3.0;
            vec2 rb = texture(uTexture, vec2((base + 0.5) / texW, vTex.y)).rg;
            vec2 gb = texture(uTexture, vec2((base + 1.5) / texW, vTex.y)).rg;
            vec2 bb = texture(uTexture, vec2((base + 2.5) / texW, vTex.y)).rg;
            float r = (rb.y * 65280.0 + rb.x * 255.0) / 65535.0;
            float g = (gb.y * 65280.0 + gb.x * 255.0) / 65535.0;
            float b = (bb.y * 65280.0 + bb.x * 255.0) / 65535.0;
            fragColor = vec4(r, g, b, 1.0);
        }
    """.trimIndent()

    private val gpuProcessVertexShader = """
        #version 300 es
        layout(location = 0) in vec2 aPos;
        layout(location = 1) in vec2 aTex;
        out vec2 vTex;
        void main() {
            vTex = aTex;
            gl_Position = vec4(aPos, 0.0, 1.0);
        }
    """.trimIndent()

    private val gpuProcessFragmentShader = """
        #version 300 es
        precision highp float;
        precision highp int;

        uniform highp usampler2D uRawTexture;
        uniform highp usampler2D uToneTexture;
        uniform vec2 uLevels;
        uniform vec3 uCfaGains;
        uniform int uCfaPattern;
        uniform vec3 uColorRow0;
        uniform vec3 uColorRow1;
        uniform vec3 uColorRow2;
        uniform int uAgxEnabled;

        out vec4 fragColor;

        // CFA enum supplied by JNI: 0=RGGB, 1=GBRG, 2=BGGR, 3=GRBG.
        ivec2 cfaOffset() {
            if (uCfaPattern == 1) return ivec2(0, 1);
            if (uCfaPattern == 2) return ivec2(1, 1);
            if (uCfaPattern == 3) return ivec2(1, 0);
            return ivec2(0, 0);
        }

        ivec2 cfaPhase(ivec2 p) {
            ivec2 q = p + cfaOffset();
            return ivec2(q.x % 2, q.y % 2);
        }

        // 0=red, 1=green, 2=blue.
        int cfaChannel(ivec2 p) {
            ivec2 phase = cfaPhase(p);
            if (phase.x == 0 && phase.y == 0) return 0;
            if (phase.x == 1 && phase.y == 1) return 2;
            return 1;
        }

        // Reflect an out-of-bounds neighbour through the centre pixel. This
        // repeats a sample of the expected CFA colour instead of clamping to a
        // different colour at the image edge.
        ivec2 neighbour(ivec2 centre, ivec2 delta) {
            ivec2 size = textureSize(uRawTexture, 0);
            ivec2 q = centre + delta;
            if (q.x < 0 || q.x >= size.x) q.x = centre.x - delta.x;
            if (q.y < 0 || q.y >= size.y) q.y = centre.y - delta.y;
            return clamp(q, ivec2(0), size - ivec2(1));
        }

        float balancedSample(ivec2 centre, ivec2 delta) {
            ivec2 p = neighbour(centre, delta);
            float code = float(texelFetch(uRawTexture, p, 0).r);
            float range = max(uLevels.y - uLevels.x, 1.0);
            float linearRaw = clamp((code - uLevels.x) / range, 0.0, 1.0);
            return linearRaw * uCfaGains[cfaChannel(p)];
        }

        vec3 bilinearDemosaic(ivec2 p) {
            float c = balancedSample(p, ivec2(0, 0));
            float n = balancedSample(p, ivec2(0, -1));
            float s = balancedSample(p, ivec2(0, 1));
            float w = balancedSample(p, ivec2(-1, 0));
            float e = balancedSample(p, ivec2(1, 0));
            float nw = balancedSample(p, ivec2(-1, -1));
            float ne = balancedSample(p, ivec2(1, -1));
            float sw = balancedSample(p, ivec2(-1, 1));
            float se = balancedSample(p, ivec2(1, 1));

            int channel = cfaChannel(p);
            if (channel == 0) {
                return vec3(c, (n + s + w + e) * 0.25,
                            (nw + ne + sw + se) * 0.25);
            }
            if (channel == 2) {
                return vec3((nw + ne + sw + se) * 0.25,
                            (n + s + w + e) * 0.25, c);
            }

            ivec2 phase = cfaPhase(p);
            if (phase.y == 0) {
                // Green on a red row.
                return vec3((w + e) * 0.5, c, (n + s) * 0.5);
            }
            // Green on a blue row.
            return vec3((n + s) * 0.5, c, (w + e) * 0.5);
        }

        float tone(float value) {
            uint index = uint(floor(clamp(value, 0.0, 1.0) * 65535.0 + 0.5));
            ivec2 lutPos = ivec2(int(index & 255u), int(index >> 8u));
            return float(texelFetch(uToneTexture, lutPos, 0).r) / 65535.0;
        }

        vec3 agxCompress(vec3 value) {
            value = max(value, vec3(0.0));
            return vec3(
                dot(vec3(0.84247906, 0.07843360, 0.07922375), value),
                dot(vec3(0.04232824, 0.87846864, 0.07916613), value),
                dot(vec3(0.04237565, 0.07843360, 0.87914297), value)
            );
        }

        vec3 agxExpand(vec3 value) {
            return vec3(
                dot(vec3(1.19687901, -0.09802088, -0.09902975), value),
                dot(vec3(-0.05289685, 1.15190313, -0.09896118), value),
                dot(vec3(-0.05297163, -0.09804345, 1.15107368), value)
            );
        }

        void main() {
            // Pass one runs at source resolution. gl_FragCoord maps one-to-one
            // to the original buffer rows; presentation performs the only flip.
            ivec2 p = ivec2(gl_FragCoord.xy);
            vec3 cameraRgb = bilinearDemosaic(p);
            vec3 outputRgb = vec3(
                dot(uColorRow0, cameraRgb),
                dot(uColorRow1, cameraRgb),
                dot(uColorRow2, cameraRgb)
            );
            if (uAgxEnabled != 0) outputRgb = agxCompress(outputRgb);
            outputRgb = vec3(tone(outputRgb.r), tone(outputRgb.g), tone(outputRgb.b));
            if (uAgxEnabled != 0) outputRgb = agxExpand(outputRgb);
            fragColor = vec4(clamp(outputRgb, 0.0, 1.0), 1.0);
        }
    """.trimIndent()

    private val gpuDisplayFragmentShader = """
        #version 300 es
        precision highp float;
        uniform sampler2D uTexture;
        in vec2 vTex;
        out vec4 fragColor;
        void main() {
            fragColor = texture(uTexture, vTex);
        }
    """.trimIndent()

    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var texCoordBuffer: FloatBuffer

    private var standardProgram = 0
    private var gpuProcessProgram = 0
    private var gpuDisplayProgram = 0

    private var standardTextureUniform = -1
    private var standardWidthUniform = -1
    private var standardScaleUniform = -1
    private var standardStretchUniform = -1
    private var processRawUniform = -1
    private var processToneUniform = -1
    private var processLevelsUniform = -1
    private var processGainsUniform = -1
    private var processCfaUniform = -1
    private var processColorRow0Uniform = -1
    private var processColorRow1Uniform = -1
    private var processColorRow2Uniform = -1
    private var processAgxUniform = -1
    private var displayTextureUniform = -1
    private var displayScaleUniform = -1
    private var displayStretchUniform = -1

    private var standardTexture = 0
    private var rawTexture = 0
    private var toneTexture = 0
    private var processedTexture = 0
    private var processedFramebuffer = 0

    private var standardTextureWidth = 0
    private var standardTextureHeight = 0
    private var gpuTextureWidth = 0
    private var gpuTextureHeight = 0
    private var maxTextureSize = 0

    private var viewWidth = 1
    private var viewHeight = 1
    private val scale = floatArrayOf(1f, 1f)
    private val stretch = floatArrayOf(1f, 1f)
    private var lastLoggedStretchX = 1f
    private var lastLoggedStretchY = 1f

    private var bayerBuffer: ByteBuffer? = null
    private val gpuStateBuffer: ByteBuffer = ByteBuffer.allocateDirect(GPU_STATE_BYTES)
        .order(ByteOrder.nativeOrder())
    private val toneLutBuffer: ByteBuffer = ByteBuffer.allocateDirect(TONE_LUT_BYTES)
        .order(ByteOrder.nativeOrder())
    private val gpuState = FloatArray(GPU_STATE_FLOATS)
    private var loadedGpuStateVersion = Long.MIN_VALUE
    private var loadedGpuStateHandle = 0L
    private var gpuStateFailureVersion = Long.MIN_VALUE
    private var gpuStateFailureHandle = 0L
    private var gpuHardFailure = false
    private var gpuFailureLogged = false
    private var gpuPathLoggedBackend = DECODER_BACKEND_UNSET
    private var processedGpuFrameHandle = 0L
    private var cacheRestoreRequestedHandle = 0L
    private var gpuStateFailureCount = 0
    private var gpuTimingFrames = 0
    private var gpuTimingDecodeNs = 0L
    private var gpuTimingSubmissionNs = 0L
    private var gpuTimingDecoderBackend = DECODER_BACKEND_UNSET

    private val quadVertices = floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f)
    private val textureCoords = floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        resetContextState()

        vertexBuffer = ByteBuffer.allocateDirect(quadVertices.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(quadVertices).position(0) }
        texCoordBuffer = ByteBuffer.allocateDirect(textureCoords.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(textureCoords).position(0) }

        standardProgram = createProgram(presentationVertexShader, standardFragmentShader)
        gpuProcessProgram = createProgram(gpuProcessVertexShader, gpuProcessFragmentShader)
        gpuDisplayProgram = createProgram(presentationVertexShader, gpuDisplayFragmentShader)
        cacheUniformLocations()

        standardTexture = generateTexture()
        rawTexture = generateTexture()
        toneTexture = generateTexture()
        processedTexture = generateTexture()
        processedFramebuffer = generateFramebuffer()

        val maxSize = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxSize, 0)
        maxTextureSize = maxSize[0]
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)

        if (standardProgram == 0) {
            Log.e(tag, "Standard preview shader could not be created")
        }
        if (gpuProcessProgram == 0 || gpuDisplayProgram == 0 ||
            rawTexture == 0 || toneTexture == 0 || processedTexture == 0 ||
            processedFramebuffer == 0
        ) {
            latchGpuFailure("required GLES program or object creation failed")
        }
        checkGlError("onSurfaceCreated")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width.coerceAtLeast(1)
        viewHeight = height.coerceAtLeast(1)
        GLES30.glViewport(0, 0, viewWidth, viewHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        val clipHandle = viewModel.clipHandle.value
        val videoWidth = viewModel.width.value
        val videoHeight = viewModel.height.value

        if (clipHandle == 0L || videoWidth <= 0 || videoHeight <= 0) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, viewWidth, viewHeight)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }

        val frameStart = System.nanoTime()
        var decodeNs = 0L
        var rendered = false
        var usedGpu = false
        var gpuDecoderBackend = DECODER_BACKEND_UNSET
        val wantsGpu = viewModel.experimentalRawGpuPreview.value
        if (!wantsGpu) {
            // The ViewModel restores normal caching when the experiment is
            // disabled. Clear the per-handle notification marker so a later
            // context/clip attempt can report its own hard failure.
            cacheRestoreRequestedHandle = 0L
        }

        if (wantsGpu && !gpuHardFailure) {
            val gpuResult = drawGpuFrame(clipHandle, videoWidth, videoHeight)
            decodeNs += gpuResult.decodeNs
            rendered = gpuResult.rendered
            usedGpu = rendered && gpuResult.freshFrame
            gpuDecoderBackend = gpuResult.decoderBackend
        }

        if (!rendered) {
            val standardResult = drawStandardFrame(clipHandle, videoWidth, videoHeight)
            decodeNs += standardResult.decodeNs
            rendered = standardResult.rendered
        }

        if (wantsGpu && gpuHardFailure && cacheRestoreRequestedHandle != clipHandle) {
            // Request restoration only after the same-frame CPU draw. Otherwise
            // the async cache transition can win render_mutex and make that
            // immediate fallback miss its frame.
            cacheRestoreRequestedHandle = clipHandle
            viewModel.reportRawGpuHardFailure(clipHandle)
        }

        if (!rendered) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, viewWidth, viewHeight)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        }

        if (rendered && viewModel.isLoading.value) {
            viewModel.changeLoadingStatus(false)
        }
        // Sequential playback advances from this handshake. Always release it,
        // including transient JNI lock failures, so playback cannot deadlock.
        if (viewModel.isDrawing.value) {
            viewModel.changeDrawingStatus(false)
        }

        val totalNs = System.nanoTime() - frameStart
        val submissionNs = (totalNs - decodeNs).coerceAtLeast(0L)
        viewModel.reportFrameTiming(decodeNs, submissionNs)
        if (usedGpu) recordGpuTiming(decodeNs, submissionNs, gpuDecoderBackend)
    }

    /** Called by the fullscreen host. GLES resources belong to the EGL context. */
    fun onSurfaceDestroyed() {
        bayerBuffer = null
        loadedGpuStateVersion = Long.MIN_VALUE
        loadedGpuStateHandle = 0L
        processedGpuFrameHandle = 0L
    }

    private fun drawStandardFrame(handle: Long, width: Int, height: Int): DrawResult {
        if (standardProgram == 0 || standardTexture == 0) return DrawResult(false, 0L)
        if (!ensureStandardTexture(width, height)) return DrawResult(false, 0L)

        val buffer = viewModel.getOrAllocateFrameBuffer(width, height)
            ?: return DrawResult(false, 0L)
        buffer.position(0)
        val decodeStart = System.nanoTime()
        val decoded = NativeLib.fillFrame16(
            handle,
            viewModel.currentFrame.value,
            cpuCores,
            buffer,
            width,
            height
        )
        val decodeNs = System.nanoTime() - decodeStart

        if (decoded) {
            buffer.position(0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, standardTexture)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            GLES30.glTexSubImage2D(
                GLES30.GL_TEXTURE_2D,
                0,
                0,
                0,
                width * 3,
                height,
                GLES30.GL_RG,
                GLES30.GL_UNSIGNED_BYTE,
                buffer
            )
            if (!checkGlError("standard glTexSubImage2D")) {
                return DrawResult(false, decodeNs)
            }
        }

        // Preserve the previous frame on a transient try-lock failure, matching
        // the old renderer's behaviour.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewWidth, viewHeight)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(standardProgram)
        setPresentationUniforms(standardProgram, width, height)
        GLES30.glUniform1i(standardWidthUniform, width)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, standardTexture)
        GLES30.glUniform1i(standardTextureUniform, 0)
        drawQuad()
        return DrawResult(checkGlError("standard draw"), decodeNs)
    }

    private fun drawGpuFrame(handle: Long, width: Int, height: Int): DrawResult {
        if (!canAllocateGpuFrame(width, height)) return DrawResult(false, 0L)
        if (!ensureGpuTextures(width, height)) return DrawResult(false, 0L)
        if (!refreshGpuStateIfNeeded(handle)) return DrawResult(false, 0L)

        val buffer = getOrAllocateBayerBuffer(width, height) ?: return DrawResult(false, 0L)
        buffer.position(0)
        val requestedDecoderBackend = if (
            viewModel.isMcraw.value && viewModel.experimentalMcrawParallelDecoder.value
        ) {
            DECODER_BACKEND_ROW_PARALLEL
        } else {
            DECODER_BACKEND_CURRENT
        }
        val decodeStart = System.nanoTime()
        val decoderBackend = NativeLib.fillRawBayer16(
            handle,
            viewModel.currentFrame.value,
            buffer,
            requestedDecoderBackend,
            cpuCores
        )
        val decodeNs = System.nanoTime() - decodeStart
        if (decoderBackend == RAW_GPU_DECODE_TRANSIENT) {
            if (processedGpuFrameHandle != handle) return DrawResult(false, decodeNs)
            val preserved = drawProcessedTexture(width, height)
            if (!preserved) latchGpuFailure("preserved RAW frame display failed")
            return DrawResult(preserved, decodeNs)
        }
        if (decoderBackend < RAW_GPU_DECODE_TRANSIENT) {
            latchGpuFailure("native RAW Bayer decode failed ($decoderBackend)")
            return DrawResult(false, decodeNs)
        }

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rawTexture)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 2)
        buffer.position(0)
        GLES30.glTexSubImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            0,
            0,
            width,
            height,
            GLES30.GL_RED_INTEGER,
            GLES30.GL_UNSIGNED_SHORT,
            buffer
        )
        if (!checkGlError("RAW Bayer upload")) {
            latchGpuFailure("Bayer texture upload failed")
            return DrawResult(false, decodeNs, decoderBackend)
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, processedFramebuffer)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glUseProgram(gpuProcessProgram)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rawTexture)
        GLES30.glUniform1i(processRawUniform, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, toneTexture)
        GLES30.glUniform1i(processToneUniform, 1)

        GLES30.glUniform2f(
            processLevelsUniform,
            gpuState[PARAM_BLACK],
            gpuState[PARAM_WHITE]
        )
        GLES30.glUniform3f(
            processGainsUniform,
            gpuState[PARAM_GAIN_R],
            gpuState[PARAM_GAIN_G],
            gpuState[PARAM_GAIN_B]
        )
        GLES30.glUniform1i(
            processCfaUniform,
            gpuState[PARAM_CFA].toInt()
        )
        setColorMatrixUniforms()
        GLES30.glUniform1i(
            processAgxUniform,
            if ((gpuState[PARAM_FLAGS].toInt() and FLAG_AGX) != 0) 1 else 0
        )
        drawQuad()

        if (!checkGlError("RAW process pass")) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            latchGpuFailure("Bayer processing pass failed")
            return DrawResult(false, decodeNs, decoderBackend)
        }

        val displayed = drawProcessedTexture(width, height)
        if (!displayed) {
            latchGpuFailure("processed RAW frame display failed")
            return DrawResult(false, decodeNs, decoderBackend)
        }
        processedGpuFrameHandle = handle
        viewModel.reportRawGpuSuccess(handle)
        if (displayed && gpuPathLoggedBackend != decoderBackend) {
            gpuPathLoggedBackend = decoderBackend
            val format = if (viewModel.isMcraw.value) {
                "MCRAW"
            } else {
                "MLV (RAW corrections bypassed)"
            }
            Log.i(
                tag,
                "Experimental RAW GPU preview active for $format (${width}x$height, " +
                    "decoder=${decoderBackendName(decoderBackend)})"
            )
        }
        return DrawResult(displayed, decodeNs, decoderBackend, freshFrame = displayed)
    }

    private fun drawProcessedTexture(width: Int, height: Int): Boolean {
        if (gpuTextureWidth != width || gpuTextureHeight != height || gpuDisplayProgram == 0) {
            return false
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewWidth, viewHeight)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(gpuDisplayProgram)
        setPresentationUniforms(gpuDisplayProgram, width, height)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, processedTexture)
        GLES30.glUniform1i(displayTextureUniform, 0)
        drawQuad()
        return checkGlError("RAW display pass")
    }

    private fun refreshGpuStateIfNeeded(handle: Long): Boolean {
        val version = viewModel.processingVersion.value
        if (loadedGpuStateHandle == handle && loadedGpuStateVersion == version) return true

        if (gpuStateFailureHandle != handle || gpuStateFailureVersion != version) {
            gpuStateFailureHandle = handle
            gpuStateFailureVersion = version
            gpuStateFailureCount = 0
        }

        gpuStateBuffer.position(0)
        toneLutBuffer.position(0)
        if (!NativeLib.fillRawGpuPreviewState(handle, gpuStateBuffer, toneLutBuffer)) {
            recordGpuStateFailure("Unable to snapshot RAW GPU processing state; will retry")
            return false
        }

        gpuStateBuffer.position(0)
        gpuStateBuffer.asFloatBuffer().apply {
            position(0)
            get(gpuState)
        }
        if (!validateGpuState()) {
            recordGpuStateFailure("Native RAW GPU state contains invalid values; will retry")
            return false
        }

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, toneTexture)
        configureIntegerTexture()
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 2)
        toneLutBuffer.position(0)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_R16UI,
            TONE_LUT_SIDE,
            TONE_LUT_SIDE,
            0,
            GLES30.GL_RED_INTEGER,
            GLES30.GL_UNSIGNED_SHORT,
            toneLutBuffer
        )
        if (!checkGlError("tone LUT upload")) {
            latchGpuFailure("tone LUT upload failed")
            return false
        }

        loadedGpuStateHandle = handle
        loadedGpuStateVersion = version
        gpuStateFailureCount = 0
        return true
    }

    private fun recordGpuStateFailure(message: String) {
        gpuStateFailureCount++
        if (gpuStateFailureCount == 1) Log.w(tag, message)
        if (gpuStateFailureCount >= GPU_STATE_FAILURE_LIMIT) {
            latchGpuFailure("native RAW GPU state remained unavailable or invalid")
        }
    }

    private fun validateGpuState(): Boolean {
        if (!gpuState[PARAM_BLACK].isFinite() || !gpuState[PARAM_WHITE].isFinite() ||
            gpuState[PARAM_WHITE] <= gpuState[PARAM_BLACK]
        ) return false
        for (index in PARAM_GAIN_R..PARAM_GAIN_B) {
            if (!gpuState[index].isFinite() || gpuState[index] <= 0f) return false
        }
        val cfa = gpuState[PARAM_CFA].toInt()
        if (cfa !in CFA_RGGB..CFA_GRBG) return false
        for (index in PARAM_MATRIX_START until PARAM_MATRIX_START + 9) {
            if (!gpuState[index].isFinite()) return false
        }
        return true
    }

    private fun setColorMatrixUniforms() {
        GLES30.glUniform3f(
            processColorRow0Uniform,
            gpuState[PARAM_MATRIX_START],
            gpuState[PARAM_MATRIX_START + 1],
            gpuState[PARAM_MATRIX_START + 2]
        )
        GLES30.glUniform3f(
            processColorRow1Uniform,
            gpuState[PARAM_MATRIX_START + 3],
            gpuState[PARAM_MATRIX_START + 4],
            gpuState[PARAM_MATRIX_START + 5]
        )
        GLES30.glUniform3f(
            processColorRow2Uniform,
            gpuState[PARAM_MATRIX_START + 6],
            gpuState[PARAM_MATRIX_START + 7],
            gpuState[PARAM_MATRIX_START + 8]
        )
    }

    private fun ensureStandardTexture(width: Int, height: Int): Boolean {
        if (standardTextureWidth == width && standardTextureHeight == height) return true
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, standardTexture)
        configureNormalizedTexture(GLES30.GL_NEAREST)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RG8,
            width * 3,
            height,
            0,
            GLES30.GL_RG,
            GLES30.GL_UNSIGNED_BYTE,
            null
        )
        if (!checkGlError("standard texture allocation")) return false
        standardTextureWidth = width
        standardTextureHeight = height
        return true
    }

    private fun ensureGpuTextures(width: Int, height: Int): Boolean {
        if (gpuTextureWidth == width && gpuTextureHeight == height) return true

        processedGpuFrameHandle = 0L

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rawTexture)
        configureIntegerTexture()
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_R16UI,
            width,
            height,
            0,
            GLES30.GL_RED_INTEGER,
            GLES30.GL_UNSIGNED_SHORT,
            null
        )
        if (!checkGlError("Bayer texture allocation")) {
            latchGpuFailure("Bayer texture allocation failed")
            return false
        }

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, processedTexture)
        configureNormalizedTexture(GLES30.GL_LINEAR)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA8,
            width,
            height,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            null
        )
        if (!checkGlError("processed texture allocation")) {
            latchGpuFailure("processed texture allocation failed")
            return false
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, processedFramebuffer)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            processedTexture,
            0
        )
        val framebufferStatus = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (framebufferStatus != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            latchGpuFailure("framebuffer incomplete: 0x${Integer.toHexString(framebufferStatus)}")
            return false
        }

        gpuTextureWidth = width
        gpuTextureHeight = height
        loadedGpuStateVersion = Long.MIN_VALUE
        return true
    }

    private fun canAllocateGpuFrame(width: Int, height: Int): Boolean {
        if (width < 3 || height < 3 || maxTextureSize <= 0 ||
            width > maxTextureSize || height > maxTextureSize
        ) {
            latchGpuFailure("source dimensions exceed GPU prototype limits")
            return false
        }
        return true
    }

    private fun getOrAllocateBayerBuffer(width: Int, height: Int): ByteBuffer? {
        val bytes = width.toLong() * height.toLong() * Short.SIZE_BYTES
        if (bytes <= 0L || bytes > Int.MAX_VALUE) return null
        val existing = bayerBuffer
        if (existing == null || existing.capacity() != bytes.toInt()) {
            bayerBuffer = try {
                ByteBuffer.allocateDirect(bytes.toInt()).order(ByteOrder.nativeOrder())
            } catch (oom: OutOfMemoryError) {
                Log.e(tag, "Unable to allocate RAW Bayer staging buffer", oom)
                latchGpuFailure("Bayer staging allocation failed")
                null
            }
        }
        return bayerBuffer
    }

    private fun configureIntegerTexture() {
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    private fun configureNormalizedTexture(filter: Int) {
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    private fun setPresentationUniforms(program: Int, videoWidth: Int, videoHeight: Int) {
        val processing = viewModel.processingData.value
        val stretchX = sanitizeStretch(processing.stretchFactorX)
        val stretchY = sanitizeStretch(processing.stretchFactorY)
        stretch[0] = stretchX
        stretch[1] = stretchY

        if (abs(stretchX - lastLoggedStretchX) > 0.001f ||
            abs(stretchY - lastLoggedStretchY) > 0.001f
        ) {
            lastLoggedStretchX = stretchX
            lastLoggedStretchY = stretchY
        }

        updateScaling(videoWidth, videoHeight, stretchX, stretchY)
        val scaleUniform = if (program == standardProgram) standardScaleUniform else displayScaleUniform
        val stretchUniform =
            if (program == standardProgram) standardStretchUniform else displayStretchUniform
        GLES30.glUniform2fv(scaleUniform, 1, scale, 0)
        GLES30.glUniform2fv(stretchUniform, 1, stretch, 0)
    }

    private fun updateScaling(videoWidth: Int, videoHeight: Int, stretchX: Float, stretchY: Float) {
        val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()
        val adjustedVideoAspect = (videoWidth.toFloat() * stretchX) /
            (videoHeight.toFloat() * stretchY)
        if (viewAspect > adjustedVideoAspect) {
            scale[0] = adjustedVideoAspect / viewAspect
            scale[1] = 1f
        } else {
            scale[0] = 1f
            scale[1] = viewAspect / adjustedVideoAspect
        }
        scale[0] /= stretchX
        scale[1] /= stretchY
        if (!scale[0].isFinite() || scale[0] <= 0f) scale[0] = 1f
        if (!scale[1].isFinite() || scale[1] <= 0f) scale[1] = 1f
    }

    private fun sanitizeStretch(value: Float): Float =
        if (value.isFinite() && value > 0f) value else 1f

    private fun drawQuad() {
        vertexBuffer.position(0)
        texCoordBuffer.position(0)
        GLES30.glEnableVertexAttribArray(POSITION_ATTRIBUTE)
        GLES30.glVertexAttribPointer(
            POSITION_ATTRIBUTE,
            2,
            GLES30.GL_FLOAT,
            false,
            0,
            vertexBuffer
        )
        GLES30.glEnableVertexAttribArray(TEXCOORD_ATTRIBUTE)
        GLES30.glVertexAttribPointer(
            TEXCOORD_ATTRIBUTE,
            2,
            GLES30.GL_FLOAT,
            false,
            0,
            texCoordBuffer
        )
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_FAN, 0, 4)
        GLES30.glDisableVertexAttribArray(POSITION_ATTRIBUTE)
        GLES30.glDisableVertexAttribArray(TEXCOORD_ATTRIBUTE)
    }

    private fun cacheUniformLocations() {
        if (standardProgram != 0) {
            standardTextureUniform = GLES30.glGetUniformLocation(standardProgram, "uTexture")
            standardWidthUniform = GLES30.glGetUniformLocation(standardProgram, "uVideoWidth")
            standardScaleUniform = GLES30.glGetUniformLocation(standardProgram, "uScale")
            standardStretchUniform = GLES30.glGetUniformLocation(standardProgram, "uStretch")
        }
        if (gpuProcessProgram != 0) {
            processRawUniform = GLES30.glGetUniformLocation(gpuProcessProgram, "uRawTexture")
            processToneUniform = GLES30.glGetUniformLocation(gpuProcessProgram, "uToneTexture")
            processLevelsUniform = GLES30.glGetUniformLocation(gpuProcessProgram, "uLevels")
            processGainsUniform = GLES30.glGetUniformLocation(gpuProcessProgram, "uCfaGains")
            processCfaUniform = GLES30.glGetUniformLocation(gpuProcessProgram, "uCfaPattern")
            processColorRow0Uniform = GLES30.glGetUniformLocation(gpuProcessProgram, "uColorRow0")
            processColorRow1Uniform = GLES30.glGetUniformLocation(gpuProcessProgram, "uColorRow1")
            processColorRow2Uniform = GLES30.glGetUniformLocation(gpuProcessProgram, "uColorRow2")
            processAgxUniform = GLES30.glGetUniformLocation(gpuProcessProgram, "uAgxEnabled")
        }
        if (gpuDisplayProgram != 0) {
            displayTextureUniform = GLES30.glGetUniformLocation(gpuDisplayProgram, "uTexture")
            displayScaleUniform = GLES30.glGetUniformLocation(gpuDisplayProgram, "uScale")
            displayStretchUniform = GLES30.glGetUniformLocation(gpuDisplayProgram, "uStretch")
        }
    }

    private fun generateTexture(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        return ids[0]
    }

    private fun generateFramebuffer(): Int {
        val ids = IntArray(1)
        GLES30.glGenFramebuffers(1, ids, 0)
        return ids[0]
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        if (vertexShader == 0) return 0
        val fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        if (fragmentShader == 0) {
            GLES30.glDeleteShader(vertexShader)
            return 0
        }

        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)
        GLES30.glDeleteShader(vertexShader)
        GLES30.glDeleteShader(fragmentShader)

        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(tag, "Program link error: ${GLES30.glGetProgramInfoLog(program)}")
            GLES30.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val label = if (type == GLES30.GL_VERTEX_SHADER) "Vertex" else "Fragment"
            Log.e(tag, "$label shader compile error: ${GLES30.glGetShaderInfoLog(shader)}")
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun checkGlError(operation: String): Boolean {
        var success = true
        var error = GLES30.glGetError()
        while (error != GLES30.GL_NO_ERROR) {
            success = false
            Log.e(tag, "$operation: glError 0x${Integer.toHexString(error)}")
            error = GLES30.glGetError()
        }
        return success
    }

    private fun latchGpuFailure(reason: String) {
        gpuHardFailure = true
        if (!gpuFailureLogged) {
            gpuFailureLogged = true
            Log.e(tag, "Experimental RAW GPU preview disabled for this EGL context: $reason")
        }
    }

    private fun recordGpuTiming(
        decodeNs: Long,
        submissionNs: Long,
        decoderBackend: Int
    ) {
        if (gpuTimingDecoderBackend != decoderBackend) {
            gpuTimingFrames = 0
            gpuTimingDecodeNs = 0L
            gpuTimingSubmissionNs = 0L
            gpuTimingDecoderBackend = decoderBackend
        }
        gpuTimingFrames++
        gpuTimingDecodeNs += decodeNs
        gpuTimingSubmissionNs += submissionNs
        if (gpuTimingFrames >= GPU_TIMING_LOG_WINDOW) {
            val decodeUs = gpuTimingDecodeNs / gpuTimingFrames / 1_000L
            val submissionUs = gpuTimingSubmissionNs / gpuTimingFrames / 1_000L
            Log.i(
                tag,
                "RAW GPU preview averages: decoder=${decoderBackendName(decoderBackend)}, " +
                    "decode=${decodeUs}us, " +
                    "upload/GL-submit=${submissionUs}us (GPU execution is asynchronous)"
            )
            gpuTimingFrames = 0
            gpuTimingDecodeNs = 0L
            gpuTimingSubmissionNs = 0L
        }
    }

    private fun resetContextState() {
        standardProgram = 0
        gpuProcessProgram = 0
        gpuDisplayProgram = 0
        standardTexture = 0
        rawTexture = 0
        toneTexture = 0
        processedTexture = 0
        processedFramebuffer = 0
        standardTextureWidth = 0
        standardTextureHeight = 0
        gpuTextureWidth = 0
        gpuTextureHeight = 0
        loadedGpuStateVersion = Long.MIN_VALUE
        loadedGpuStateHandle = 0L
        gpuStateFailureVersion = Long.MIN_VALUE
        gpuStateFailureHandle = 0L
        gpuHardFailure = false
        gpuFailureLogged = false
        gpuPathLoggedBackend = DECODER_BACKEND_UNSET
        processedGpuFrameHandle = 0L
        cacheRestoreRequestedHandle = 0L
        gpuStateFailureCount = 0
        gpuTimingFrames = 0
        gpuTimingDecodeNs = 0L
        gpuTimingSubmissionNs = 0L
        gpuTimingDecoderBackend = DECODER_BACKEND_UNSET
    }

    private fun decoderBackendName(backend: Int): String = when (backend) {
        DECODER_BACKEND_CURRENT -> "current"
        DECODER_BACKEND_ROW_PARALLEL -> "MotionCam row-parallel"
        DECODER_BACKEND_CLASSIC_MLV -> "classic MLV built-in"
        else -> "unknown($backend)"
    }

    private data class DrawResult(
        val rendered: Boolean,
        val decodeNs: Long,
        val decoderBackend: Int = DECODER_BACKEND_UNSET,
        val freshFrame: Boolean = false
    )

    private companion object {
        const val POSITION_ATTRIBUTE = 0
        const val TEXCOORD_ATTRIBUTE = 1

        const val GPU_STATE_FLOATS = 16
        const val GPU_STATE_BYTES = GPU_STATE_FLOATS * Float.SIZE_BYTES
        const val TONE_LUT_SIDE = 256
        const val TONE_LUT_BYTES = 65_536 * Short.SIZE_BYTES
        const val GPU_TIMING_LOG_WINDOW = 120
        const val GPU_STATE_FAILURE_LIMIT = 3

        const val DECODER_BACKEND_UNSET = -1
        const val DECODER_BACKEND_CURRENT = 0
        const val DECODER_BACKEND_ROW_PARALLEL = 1
        const val DECODER_BACKEND_CLASSIC_MLV = 2
        const val RAW_GPU_DECODE_TRANSIENT = -1

        const val PARAM_BLACK = 0
        const val PARAM_WHITE = 1
        const val PARAM_GAIN_R = 2
        const val PARAM_GAIN_G = 3
        const val PARAM_GAIN_B = 4
        const val PARAM_CFA = 5
        const val PARAM_MATRIX_START = 6
        const val PARAM_FLAGS = 15

        const val FLAG_AGX = 1

        const val CFA_RGGB = 0
        const val CFA_GRBG = 3
    }
}
