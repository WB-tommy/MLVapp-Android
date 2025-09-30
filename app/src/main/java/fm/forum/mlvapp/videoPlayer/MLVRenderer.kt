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
        out vec2 vTex;
        void main() {
            vTex = aTex;
            gl_Position = vec4(aPos * uScale, 0.0, 1.0);
        }
    """.trimIndent()

    // Fragment shader for 3-channel RGB data
    private val fragmentShaderCode = """
        #version 300 es
        precision highp float;
        precision highp usampler2D;
        uniform usampler2D uTexture;
        in vec2 vTex;
        out vec4 fragColor;
        void main() {
            uvec3 rgb = texture(uTexture, vTex).rgb;
            fragColor = vec4(vec3(rgb) / 65535.0, 1.0);
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

    private var viewWidth = 1
    private var viewHeight = 1
    private val scale = floatArrayOf(1f, 1f)

    private var textureAllocated = false
    private var frameBuffer: ByteBuffer? = null

    private val quadVertices = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val textureCoords = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        Log.d(tag, "onSurfaceCreated")
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)

        program = createProgram(vertexShaderCode, fragmentShaderCode)
        GLES30.glUseProgram(program)

        posHandle = GLES30.glGetAttribLocation(program, "aPos")
        texCoordHandle = GLES30.glGetAttribLocation(program, "aTex")
        texUniformHandle = GLES30.glGetUniformLocation(program, "uTexture")
        scaleUniformHandle = GLES30.glGetUniformLocation(program, "uScale")

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
        Log.d(tag, "onSurfaceChanged: ${width}x${height}")
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
        val ok = NativeLib.fillFrame16(
            viewModel.clipHandle.value,
            viewModel.currentFrame.value,
            cpuCores,
            buf,
            videoWidth,
            videoHeight
        )

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
                GLES30.GL_RGB_INTEGER,
                GLES30.GL_UNSIGNED_SHORT,
                buf
            )
            checkGlError("glTexSubImage2D")
        }

        // Set uniforms and attributes
        GLES30.glUniform1i(texUniformHandle, 0)
        updateScaling(videoWidth, videoHeight)

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
    }

    private fun updateScaling(videoWidth: Int, videoHeight: Int) {
        if (viewWidth > 0 && viewHeight > 0 && videoWidth > 0 && videoHeight > 0) {
            val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()
            val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
            if (viewAspect > videoAspect) {
                scale[0] = videoAspect / viewAspect
                scale[1] = 1f
            } else {
                scale[0] = 1f
                scale[1] = viewAspect / videoAspect
            }
            GLES30.glUniform2fv(scaleUniformHandle, 1, scale, 0)
        }
    }

    private fun allocateFrameBufferIfNeeded(w: Int, h: Int) {
        val needed = w * h * 3 * 2 // 3-channel 16-bit
        if (frameBuffer?.capacity() != needed) {
            frameBuffer = ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder())
        }
    }

    private fun allocateTextureStorage(w: Int, h: Int) {
        Log.d(tag, "Allocating RGB texture storage: ${w}x${h}")
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGB16UI,
            w,
            h,
            0,
            GLES30.GL_RGB_INTEGER,
            GLES30.GL_UNSIGNED_SHORT,
            null
        )
        checkGlError("glTexImage2D - RGB16UI")
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
