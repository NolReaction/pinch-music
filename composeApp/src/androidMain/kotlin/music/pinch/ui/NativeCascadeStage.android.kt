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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt

internal actual val supportsPanelMesh: Boolean get() = android.os.Build.VERSION.SDK_INT >= 29

private object CascadeGL {
    init { System.loadLibrary("pinch_stage") }
    external fun create(): Long
    external fun destroy(stage: Long)
    external fun upload(stage: Long, keys: FloatArray, rails: FloatArray)
    external fun draw(stage: Long, width: Int, height: Int, frame: FloatArray)
}

@Composable
internal actual fun NativeCascadeStage(
    modifier: Modifier, course: CascadeGpuCourse, frame: (FloatArray) -> Unit, animate: Boolean,
    onPresented: () -> Unit,
) {
    val context = LocalContext.current
    val presented = remember(course) { mutableStateOf(false) }
    val notifyPresented by rememberUpdatedState {
        presented.value = true
        onPresented()
    }
    val display = remember(context) { CascadeTexture(context) { notifyPresented() } }
    AndroidView(factory = { display }, modifier = modifier, onRelease = { it.release() }, update = {
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            // Vote for fluid motion only while the scene is moving; the system
            // retains control of adaptive refresh and power-saving limits.
            it.requestedFrameRate = if (animate) View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
                else View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
        }
    })
    CascadeFrameClock(display, course, frame, animate || !presented.value)
}

/** A texture participates in normal Compose clipping, opacity and HUD compositing. */
private class CascadeTexture(context: Context, private val onPresented: () -> Unit) :
    TextureView(context), TextureView.SurfaceTextureListener, CascadeDisplay {
    private var worker: CascadeWorker? = null
    private var course: CascadeGpuCourse? = null
    private val lastFrame = FloatArray(CascadeFrameSize)
    private var renderWidth = 0
    private var renderHeight = 0
    private var released = false
    private var swappedCourse: CascadeGpuCourse? = null
    private var textureUpdated = false
    private var presented = false

    init {
        surfaceTextureListener = this
        isOpaque = true
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun present(course: CascadeGpuCourse, frame: FloatArray) {
        if (released) return
        if (this.course !== course) {
            swappedCourse = null
            textureUpdated = false
            presented = false
        }
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
        textureUpdated = false
        swappedCourse = null
        worker = CascadeWorker(surface) { drawn ->
            post {
                if (!released && worker != null && surfaceTexture === surface && course === drawn) {
                    swappedCourse = drawn
                    notifyIfVisible()
                }
            }
        }
        onSurfaceTextureSizeChanged(surface, w, h)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, w: Int, h: Int) {
        if (w <= 0 || h <= 0) {
            renderWidth = 0
            renderHeight = 0
            return
        }
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

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        if (surfaceTexture !== surface) return
        textureUpdated = true
        notifyIfVisible()
    }

    private fun notifyIfVisible() {
        // The GL producer swapped successfully and TextureView latched a buffer on the UI thread.
        if (!presented && !released && textureUpdated && swappedCourse === course && course != null &&
            width > 0 && height > 0 && isShown && windowVisibility == View.VISIBLE && isAttachedToWindow) {
            presented = true
            onPresented()
        }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        val active = worker ?: return true
        worker = null
        textureUpdated = false
        swappedCourse = null
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
private class CascadeWorker(private val texture: SurfaceTexture, private val onSwap: (CascadeGpuCourse) -> Unit) {
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
    private var reported: CascadeGpuCourse? = null

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
        if (egl.render(texture, course, drawing, w, h) && reported !== course) {
            reported = course
            onSwap(course)
        }
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
        // Smooth the thin rails and bevel silhouettes without increasing fragment
        // shading resolution. Some ES2 drivers expose MSAA configs but cannot
        // create a TextureView surface from them, so retry the complete setup.
        for (samples in intArrayOf(4, 2, 0)) {
            val config = chooseConfig(samples) ?: continue
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            if (context == EGL14.EGL_NO_CONTEXT) continue
            surface = EGL14.eglCreateWindowSurface(display, config, texture, intArrayOf(EGL14.EGL_NONE), 0)
            if (surface != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, surface, surface, context)) {
                EGL14.eglSwapInterval(display, 1)
                stage = CascadeGL.create()
                return stage != 0L
            }
            releaseDrawable()
        }
        return false
    }

    private fun chooseConfig(samples: Int): EGLConfig? {
        val attributes = intArrayOf(
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 16,
            EGL14.EGL_SAMPLE_BUFFERS, if (samples > 0) 1 else 0,
            EGL14.EGL_SAMPLES, samples,
            EGL14.EGL_NONE
        )
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attributes, 0, null, 0, 0, count, 0) || count[0] == 0) return null
        val configs = arrayOfNulls<EGLConfig>(count[0])
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, configs.size, count, 0)) return null
        val actual = IntArray(1)
        // EGL_SAMPLES is a minimum, not an exact match. Never select an 8×
        // config for 4×, nor pick the same failed 4× config on the 0× retry.
        return configs.firstOrNull { config ->
            config != null && EGL14.eglGetConfigAttrib(display, config, EGL14.EGL_SAMPLES, actual, 0) && actual[0] == samples
        }
    }

    private fun releaseDrawable() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        surface = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
    }

    fun render(texture: SurfaceTexture, course: CascadeGpuCourse, frame: FloatArray, w: Int, h: Int): Boolean {
        if (course.keys.isEmpty()) return false
        if (stage == 0L && !open(texture)) {
            if (!warned) Log.w("CascadeGL", "Could not initialize GLES stage, EGL error ${EGL14.eglGetError()}")
            warned = true
            close()
            return false
        }
        warned = false
        if (uploaded !== course) {
            CascadeGL.upload(stage, course.keys, course.rails)
            uploaded = course
        }
        // EGL switches its back buffers on swap. Following a resize the current
        // buffer can still have the previous dimensions for one frame.
        if (drawableSize[0] != w || drawableSize[1] != h) {
            EGL14.eglQuerySurface(display, surface, EGL14.EGL_WIDTH, drawableSize, 0)
            EGL14.eglQuerySurface(display, surface, EGL14.EGL_HEIGHT, drawableSize, 1)
        }
        if (drawableSize[0] <= 0 || drawableSize[1] <= 0) return false
        CascadeGL.draw(stage, drawableSize[0], drawableSize[1], frame)
        if (EGL14.eglSwapBuffers(display, surface)) return true
        close()
        return false
    }

    fun close() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (stage != 0L) CascadeGL.destroy(stage)
            releaseDrawable()
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
