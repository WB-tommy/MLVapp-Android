package fm.forum.mlvapp.videoPlayer

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import fm.forum.mlvapp.NativeInterface.NativeLib
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

class MlvRenderer(
    private val cpuCores: Int,
    private val viewModel: VideoViewModel
) : GLSurfaceView.Renderer {

    private val tag = "MlvRenderer"

    private val vertexShaderCode = """
        #version 300 es
        layout(location = 0) in vec2 aPos;
        layout(location = 1) in vec2 aTex;
        uniform vec2 uScale; // For aspect ratio correction
        uniform vec2 uStretch; // Applied before scaling to handle anamorphic stretch
        out vec2 vTex;
        void main() {
            vTex = aTex;
            vec2 stretched = aPos * uStretch;
            gl_Position = vec4(stretched * uScale, 0.0, 1.0);
        }
    """.trimIndent()

    private val fragmentShaderCode = """
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

    private var program = 0
    private var textureId = 0
    private var posHandle = 0
    private var texCoordHandle = 0
    private var texUniformHandle = 0
    private var scaleUniformHandle = -1
    private var stretchUniformHandle = -1

    private var viewWidth = 1
    private var viewHeight = 1
    private val scale = floatArrayOf(1f, 1f)
    private val stretch = floatArrayOf(1f, 1f)
    private var lastLoggedStretchX = 1f
    private var lastLoggedStretchY = 1f

    private var textureAllocated = false
    private var frameBuffer: ByteBuffer? = null

    private val quadVertices = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val textureCoords = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)

        program = createProgram(vertexShaderCode, fragmentShaderCode)
        GLES30.glUseProgram(program)

        posHandle = GLES30.glGetAttribLocation(program, "aPos")
        texCoordHandle = GLES30.glGetAttribLocation(program, "aTex")
        texUniformHandle = GLES30.glGetUniformLocation(program, "uTexture")
        scaleUniformHandle = GLES30.glGetUniformLocation(program, "uScale")
        stretchUniformHandle = GLES30.glGetUniformLocation(program, "uStretch")

        vertexBuffer =
            ByteBuffer.allocateDirect(quadVertices.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply { put(quadVertices).position(0) }
        texCoordBuffer =
            ByteBuffer.allocateDirect(textureCoords.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply { put(textureCoords).position(0) }

        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_MIN_FILTER,
            GLES30.GL_NEAREST
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_MAG_FILTER,
            GLES30.GL_NEAREST
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_WRAP_S,
            GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_WRAP_T,
            GLES30.GL_CLAMP_TO_EDGE
        )

        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        checkGlError("onSurfaceCreated")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        this.viewWidth = width
        this.viewHeight = height
        textureAllocated = false
    }

    override fun onDrawFrame(gl: GL10?) {
        val videoWidth = viewModel.width.value
        val videoHeight = viewModel.height.value

        if (videoWidth <= 0 || videoHeight <= 0) {
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }

        if (!textureAllocated) {
            allocateTextureStorage(videoWidth, videoHeight)
        }
        allocateFrameBufferIfNeeded(videoWidth, videoHeight)

        val buf = frameBuffer!!
        buf.position(0)
        val decodeStart = System.nanoTime()
        val ok = NativeLib.fillFrame16(
            viewModel.clipHandle.value,
            viewModel.currentFrame.value,
            cpuCores,
            buf,
            videoWidth,
            videoHeight
        )
        val decodeNs = System.nanoTime() - decodeStart

        val renderStart = System.nanoTime()
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)

        if (ok) {
            buf.position(0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
            GLES30.glTexSubImage2D(
                GLES30.GL_TEXTURE_2D,
                0,
                0,
                0,
                videoWidth,
                videoHeight,
                GLES30.GL_RGB,
                GLES30.GL_FLOAT,
                buf
            )
            checkGlError("glTexSubImage2D")
        }

        // Set uniforms and attributes
        GLES30.glUniform1i(texUniformHandle, 0)
        val processing = viewModel.processingData.value
        val stretchX = sanitizeStretch(processing.stretchFactorX)
        val stretchY = sanitizeStretch(processing.stretchFactorY)

        stretch[0] = stretchX
        stretch[1] = stretchY
        if (stretchUniformHandle >= 0) {
            GLES30.glUniform2fv(stretchUniformHandle, 1, stretch, 0)
        }
        if (abs(stretchX - lastLoggedStretchX) > 0.001f ||
            abs(stretchY - lastLoggedStretchY) > 0.001f) {
            Log.d(tag, "Applying stretch x=$stretchX y=$stretchY")
            lastLoggedStretchX = stretchX
            lastLoggedStretchY = stretchY
        }

        updateScaling(videoWidth, videoHeight, stretchX, stretchY)

        GLES30.glEnableVertexAttribArray(posHandle)
        GLES30.glVertexAttribPointer(posHandle, 2, GLES30.GL_FLOAT, false, 0, vertexBuffer)

        GLES30.glEnableVertexAttribArray(texCoordHandle)
        GLES30.glVertexAttribPointer(texCoordHandle, 2, GLES30.GL_FLOAT, false, 0, texCoordBuffer)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(posHandle)
        GLES30.glDisableVertexAttribArray(texCoordHandle)

        // If we are in a loading state, this frame being drawn means loading is complete.
        if (viewModel.isLoading.value) {
            viewModel.changeLoadingStatus(false)
        }

        if (viewModel.isDrawing.value) {
            viewModel.changeDrawingStatus(false)
        }

        val renderNs = System.nanoTime() - renderStart
        viewModel.reportFrameTiming(decodeNs, renderNs)
    }

    private fun updateScaling(videoWidth: Int, videoHeight: Int, stretchX: Float, stretchY: Float) {
        if (viewWidth > 0 && viewHeight > 0 && videoWidth > 0 && videoHeight > 0) {
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
            if (!scale[0].isFinite() || scale[0] <= 0f) {
                scale[0] = 1f
            }
            if (!scale[1].isFinite() || scale[1] <= 0f) {
                scale[1] = 1f
            }
            if (scaleUniformHandle >= 0) {
                GLES30.glUniform2fv(scaleUniformHandle, 1, scale, 0)
            }
        }
    }

    private fun sanitizeStretch(value: Float): Float {
        return if (value.isFinite() && value > 0f) value else 1f
    }

    private fun allocateFrameBufferIfNeeded(w: Int, h: Int) {
        val needed = w * h * 3 * 4 // 3-channel 32-bit float
        if (frameBuffer?.capacity() != needed) {
            frameBuffer = ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder())
        }
    }

    private fun allocateTextureStorage(w: Int, h: Int) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGB32F,
            w,
            h,
            0,
            GLES30.GL_RGB,
            GLES30.GL_FLOAT,
            null
        )
        checkGlError("glTexImage2D - RGB32F")
        textureAllocated = true
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        return GLES30.glCreateProgram().also {
            GLES30.glAttachShader(it, vertexShader)
            GLES30.glAttachShader(it, fragmentShader)
            GLES30.glLinkProgram(it)
            checkProgramLink(it)
        }
    }

    private fun loadShader(type: Int, code: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, code)
        GLES30.glCompileShader(shader)
        checkShaderCompile(shader, type)
        return shader
    }

    private fun checkGlError(op: String) {
        var error: Int; while (GLES30.glGetError().also { error = it } != GLES30.GL_NO_ERROR) {
            Log.e(tag, "$op: glError 0x${Integer.toHexString(error)}")
        }
    }

    private fun checkShaderCompile(shader: Int, type: Int) {
        val status = IntArray(1); GLES30.glGetShaderiv(
            shader,
            GLES30.GL_COMPILE_STATUS,
            status,
            0
        ); if (status[0] == 0) {
            val typeStr = if (type == GLES30.GL_VERTEX_SHADER) "Vertex" else "Fragment"; Log.e(
                tag,
                "$typeStr Shader Compile Error: ${GLES30.glGetShaderInfoLog(shader)}"
            )
        }
    }

    private fun checkProgramLink(program: Int) {
        val status = IntArray(1); GLES30.glGetProgramiv(
            program,
            GLES30.GL_LINK_STATUS,
            status,
            0
        ); if (status[0] == 0) {
            Log.e(tag, "Program Link Error: ${GLES30.glGetProgramInfoLog(program)}")
        }
    }
}
