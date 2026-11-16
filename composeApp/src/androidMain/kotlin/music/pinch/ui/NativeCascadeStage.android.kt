package music.pinch.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.TextureView
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt

private object CascadeGL {
    init { System.loadLibrary("pinch_stage") }
    external fun create(): Long
    external fun destroy(stage: Long)
    external fun upload(stage: Long, keys: FloatArray, rails: FloatArray, lamps: FloatArray)
    external fun draw(stage: Long, width: Int, height: Int, frame: FloatArray)
}

@Composable
internal actual fun NativeCascadeStage(
    modifier: Modifier, course: CascadeGpuCourse, frame: (FloatArray) -> Unit, animate: Boolean
) {
    val context = LocalContext.current
    val display = remember(context) { CascadeTexture(context) }
    AndroidView(factory = { display }, modifier = modifier, onRelease = { it.release() })
    CascadeFrameClock(display, course, frame, animate)
}

/** A texture participates in normal Compose clipping, opacity and HUD compositing. */
private class CascadeTexture(context: Context) : TextureView(context), TextureView.SurfaceTextureListener, CascadeDisplay {
    private var worker: CascadeWorker? = null
    private var course: CascadeGpuCourse? = null
    private val lastFrame = FloatArray(CascadeFrameSize)
    private var renderWidth = 0
    private var renderHeight = 0
    private var released = false

    init {
        surfaceTextureListener = this
        isOpaque = true
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun present(course: CascadeGpuCourse, frame: FloatArray) {
        this.course = course
        frame.copyInto(lastFrame)
        redraw()
    }

    private fun redraw() {
        val installed = course ?: return
        if (!released && windowVisibility == View.VISIBLE && renderWidth > 0 && renderHeight > 0) {
            worker?.submit(installed, lastFrame, renderWidth, renderHeight)
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, w: Int, h: Int) {
        if (released) return
        worker = CascadeWorker(surface)
        onSurfaceTextureSizeChanged(surface, w, h)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, w: Int, h: Int) {
        // Keep a phone's high-density display from multiplying fragment shading cost.
        val scale = CascadeRaster.scale(w.toDouble(), h.toDouble(), 1.0)
        renderWidth = (w * scale).roundToInt().coerceAtLeast(1)
        renderHeight = (h * scale).roundToInt().coerceAtLeast(1)
        surface.setDefaultBufferSize(renderWidth, renderHeight)
        redraw()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) redraw()
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        val active = worker ?: return true
        worker = null
        active.stop()
        // The worker releases its SurfaceTexture after finishing the in-flight swap.
        return false
    }

    fun release() {
        released = true
        course = null
        // Detaching the AndroidView invokes onSurfaceTextureDestroyed. Until then the
        // TextureView still owns the consumer end; stop submitting work immediately.
    }
}

/** At most one frame waits for the GL thread, even if a swap misses a display refresh. */
private class CascadeWorker(private val texture: SurfaceTexture) {
    private val thread = HandlerThread("Cascade GLES").apply { start() }
    private val handler = Handler(thread.looper)
    private val lock = Any()
    private val pending = FloatArray(CascadeFrameSize)
    private val drawing = FloatArray(CascadeFrameSize)
    private var width = 1
    private var height = 1
    private var latestCourse: CascadeGpuCourse? = null
    private var queued = false
    private var stopped = false
    private val egl = CascadeEgl()

    fun submit(course: CascadeGpuCourse, values: FloatArray, w: Int, h: Int) {
        synchronized(lock) {
            if (stopped) return
            latestCourse = course
            values.copyInto(pending)
            width = w
            height = h
            if (!queued) {
                queued = true
                handler.post(::render)
            }
        }
    }

    private fun render() {
        val course: CascadeGpuCourse
        val w: Int
        val h: Int
        synchronized(lock) {
            queued = false
            if (stopped) return
            course = latestCourse ?: return
            w = width
            h = height
            pending.copyInto(drawing)
        }
        egl.render(texture, course, drawing, w, h)
    }

    fun stop() {
        synchronized(lock) { stopped = true }
        handler.post {
            egl.close()
            texture.release()
            thread.quitSafely()
        }
    }
}

/** All methods execute on this texture's private GL thread. */
private class CascadeEgl {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var stage = 0L
    private var uploaded: CascadeGpuCourse? = null
    private val drawableSize = IntArray(2)
    private var warned = false

    private fun open(texture: SurfaceTexture): Boolean {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, version, 0, version, 1)) return false
        val attributes = intArrayOf(
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 16, EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) return false
        val config = configs[0] ?: return false
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        if (context == EGL14.EGL_NO_CONTEXT) return false
        surface = EGL14.eglCreateWindowSurface(display, config, texture, intArrayOf(EGL14.EGL_NONE), 0)
        if (surface == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(display, surface, surface, context)) return false
        stage = CascadeGL.create()
        return stage != 0L
    }

    fun render(texture: SurfaceTexture, course: CascadeGpuCourse, frame: FloatArray, w: Int, h: Int) {
        if (stage == 0L && !open(texture)) {
            if (!warned) Log.w("CascadeGL", "Could not initialize GLES stage, EGL error ${EGL14.eglGetError()}")
            warned = true
            close()
            return
        }
        warned = false
        if (uploaded !== course) {
            CascadeGL.upload(stage, course.keys, course.rails, course.lamps)
            uploaded = course
        }
        // EGL switches its back buffers on swap. Following a resize the current
        // buffer can still have the previous dimensions for one frame.
        if (drawableSize[0] != w || drawableSize[1] != h) {
            EGL14.eglQuerySurface(display, surface, EGL14.EGL_WIDTH, drawableSize, 0)
            EGL14.eglQuerySurface(display, surface, EGL14.EGL_HEIGHT, drawableSize, 1)
        }
        CascadeGL.draw(stage, drawableSize[0], drawableSize[1], frame)
        if (!EGL14.eglSwapBuffers(display, surface)) close()
    }

    fun close() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (stage != 0L) CascadeGL.destroy(stage)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            EGL14.eglReleaseThread()
        }
        stage = 0L
        uploaded = null
        drawableSize.fill(0)
        surface = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
    }
}
