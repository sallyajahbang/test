package com.sally.glues

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max

private const val VERTEX_SHADER = """
attribute vec2 aPos;
attribute vec2 aTex;
uniform mat4 uTexMatrix;
uniform vec2 uScale;
uniform vec2 uOffset;
varying vec2 vTex;
void main() {
    gl_Position = vec4(aPos * uScale + uOffset, 0.0, 1.0);
    vTex = (uTexMatrix * vec4(aTex, 0.0, 1.0)).xy;
}
"""

private const val FRAGMENT_SHADER = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vTex;
uniform samplerExternalOES sTexture;
void main() {
    gl_FragColor = texture2D(sTexture, vTex);
}
"""

/**
 * Render thread: EGL window di atas surface wallpaper, MediaPlayer menulis ke SurfaceTexture (OES),
 * dan shader menggambar video dengan skala cover x skala user + offset.
 * Rumus ini sama dengan preview di editor, sehingga hasil wallpaper sesuai yang terlihat.
 */
class GlueRenderer(
    private val context: Context,
    private val outputSurface: Surface,
    @Volatile private var width: Int,
    @Volatile private var height: Int,
    private var config: GlueConfig
) : HandlerThread("GlueRenderer") {

    private val handler: Handler by lazy { Handler(looper) }

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private var player: MediaPlayer? = null
    private var prepared = false
    private var visible = true
    private var released = false

    private var videoW = 1f
    private var videoH = 1f

    private var program = 0
    private var texId = 0
    private var locPos = 0
    private var locTex = 0
    private var locScale = 0
    private var locOffset = 0
    private var locTexMatrix = 0

    private val texMatrix = FloatArray(16)
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(4 * 16)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            // x, y, u, v untuk triangle strip
            put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
        }

    override fun onLooperPrepared() {
        initEgl()
        initGl()
        initPlayer()
    }

    fun resize(w: Int, h: Int) {
        width = w
        height = h
        handler.post { if (prepared) drawFrame() }
    }

    fun setVisible(v: Boolean) {
        handler.post {
            visible = v
            if (prepared) {
                if (v) player?.start() else player?.pause()
            }
        }
    }

    fun applyConfig(c: GlueConfig) {
        handler.post {
            val videoChanged = c.videoUri != config.videoUri
            config = c
            if (videoChanged) {
                player?.release()
                player = null
                prepared = false
                initPlayer()
            } else {
                applyVolume()
            }
            if (prepared) drawFrame()
        }
    }

    fun release() {
        handler.post {
            released = true
            player?.release()
            player = null
            surfaceTexture?.release()
            inputSurface?.release()
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
            quitSafely()
        }
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, num, 0)

        eglContext = EGL14.eglCreateContext(
            eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, configs[0], outputSurface,
            intArrayOf(EGL14.EGL_NONE), 0
        )
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    private fun initGl() {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)

        locPos = GLES20.glGetAttribLocation(program, "aPos")
        locTex = GLES20.glGetAttribLocation(program, "aTex")
        locScale = GLES20.glGetUniformLocation(program, "uScale")
        locOffset = GLES20.glGetUniformLocation(program, "uOffset")
        locTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(texId)
        st.setOnFrameAvailableListener({ drawFrame() }, handler)
        surfaceTexture = st
        inputSurface = Surface(st)
    }

    private fun initPlayer() {
        val uri = config.videoUri ?: return
        val mp = MediaPlayer()
        try {
            mp.setDataSource(context, Uri.parse(uri))
            inputSurface?.let { mp.setSurface(it) }
            mp.isLooping = true
            mp.setOnPreparedListener { p ->
                videoW = max(1, p.videoWidth).toFloat()
                videoH = max(1, p.videoHeight).toFloat()
                prepared = true
                applyVolume()
                if (visible) p.start()
                drawFrame()
            }
            mp.prepareAsync()
            player = mp
        } catch (e: Exception) {
            mp.release()
        }
    }

    private fun applyVolume() {
        val v = if (config.muted) 0f else 1f
        runCatching { player?.setVolume(v, v) }
    }

    private fun drawFrame() {
        if (released || !prepared) return
        val st = surfaceTexture ?: return
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        st.updateTexImage()
        st.getTransformMatrix(texMatrix)

        val sw = w.toFloat()
        val sh = h.toFloat()
        val cover = max(sw / videoW, sh / videoH)
        val s = config.scale

        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        GLES20.glUniform2f(locScale, cover * videoW / sw * s, cover * videoH / sh * s)
        GLES20.glUniform2f(locOffset, 2f * config.offsetX, -2f * config.offsetY)
        GLES20.glUniformMatrix4fv(locTexMatrix, 1, false, texMatrix, 0)

        quad.position(0)
        GLES20.glVertexAttribPointer(locPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(locPos)
        quad.position(2)
        GLES20.glVertexAttribPointer(locTex, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(locTex)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private fun compileShader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        return s
    }
}
