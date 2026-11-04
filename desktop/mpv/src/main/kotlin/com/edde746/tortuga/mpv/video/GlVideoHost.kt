package com.edde746.tortuga.mpv.video

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import com.edde746.tortuga.mpv.core.MpvCore
import com.edde746.tortuga.mpv.ffi.MpvFfi
import com.edde746.tortuga.mpv.ffi.mpv_opengl_fbo
import com.edde746.tortuga.mpv.ffi.mpv_opengl_init_params
import com.edde746.tortuga.mpv.ffi.mpv_render_param
import com.edde746.tortuga.mpv.ffi.mpv_render_update_fn
import com.edde746.tortuga.mpv.os.Gl
import com.edde746.tortuga.playback.HardwareDecoder
import org.jetbrains.skia.BackendTexture
import org.jetbrains.skia.Canvas as SkiaCanvas
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skiko.SkiaLayer
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

/**
 * Linux: `vo=libmpv`. mpv's OpenGL render context lives on Skiko's own GLX context and renders each frame into
 * an FBO texture that Skia adopts as an [Image], drawn under the Compose UI in the same window. Everything touching GL
 * or the render context runs in [draw], i.e. on Skiko's render thread with its context current; the mpv thread only
 * requests creation and teardown. The render context is freed there too, before mpv_terminate_destroy:
 * [awaitUnbound] holds the core's teardown until a draw of the window has freed it, however long the window doesn't
 * draw (minimized, hidden). A window disposed first never draws again, so its context can't be freed: the context and
 * the core are then left alive, never freed later against a destroyed core.
 *
 * Needs JetBrains Runtime on Linux (AWT under Temurin misses KWin-driven resizes) and OpenGL rendering in Skiko, which
 * is its Linux default.
 */
internal class GlVideoHost : VideoHost {
    private val frameTick = mutableLongStateOf(0L)
    @Volatile private var window: ComposeWindow? = null
    @Volatile private var layer: SkiaLayer? = null

    // Handed from the mpv thread to the render thread.
    @Volatile private var pendingBind: Pair<MpvCore, (String?) -> Unit>? = null
    @Volatile private var unbindRequested = false
    /** Taken once: by [release] before it frees the GL side, or by [windowGone] when no draw will ever come. */
    private val settled = AtomicBoolean(false)
    @Volatile private var freed = false
    private val unbound = CountDownLatch(1)
    private val windowGone = object : WindowAdapter() {
        override fun windowClosed(e: WindowEvent) {
            e.window.removeWindowListener(this)
            if (settled.compareAndSet(false, true)) unbound.countDown()
        }
    }

    // Render-thread state.
    private var direct: DirectContext? = null
    private var renderContext: MemorySegment = MemorySegment.NULL
    private var arena: Arena? = null
    private var fbo = 0
    private var image: Image? = null
    private var texW = 0
    private var texH = 0
    @Volatile private var renderError: String? = null
    private var contextWaits = 0
    /** The bound engine's `onVideoReady`, kept while bound: a later failure reaches the playback too, once. */
    private var onVideo: ((String?) -> Unit)? = null

    override fun attach(window: ComposeWindow, onReady: () -> Unit): Boolean {
        if (layer != null) return true
        // The SkiaLayer is the HardwareLayer's parent when it is not found by class (the spike needed both paths).
        val skiaLayer = (findComponent(window, SkiaLayer::class.java.name) ?: findComponent(window, "org.jetbrains.skiko.HardwareLayer")?.parent)
            as? SkiaLayer ?: return false
        this.window = window
        layer = skiaLayer
        window.addWindowListener(windowGone)
        onReady()
        return true
    }

    override val hardwareDecoder = HardwareDecoder.LINUX

    override fun options(): Map<String, String> = linkedMapOf("vo" to "libmpv")

    override fun bind(core: MpvCore, onVideoReady: (error: String?) -> Unit) {
        // vo=libmpv has no display to time against; tell it the monitor's refresh rate.
        window?.graphicsConfiguration?.device?.displayMode?.refreshRate?.takeIf { it > 0 }?.let {
            core.setString("display-fps-override", it.toString())
        }
        pendingBind = core to onVideoReady
        requestRedraw()
    }

    override fun unbind(core: MpvCore) = requestRelease()

    override fun awaitUnbound(): Boolean {
        unbound.await()
        if (!freed) System.getLogger(javaClass.name).log(System.Logger.Level.ERROR, "the window closed before mpv's render context was freed: the context and the mpv core are left alive")
        return freed
    }

    override fun close() = requestRelease()

    /**
     * Frees the GL side on the next draw: the video's own, or the window root's ([GlRenderQueue]) once the player has
     * left the screen and the video is no longer drawn.
     */
    private fun requestRelease() {
        unbindRequested = true
        requestRedraw()
        GlRenderQueue.post(::release)
    }

    /** On Skiko's render thread (a draw), with its GL context current. */
    private fun release() {
        if (!settled.compareAndSet(false, true)) return
        try {
            dispose()
        } finally {
            freed = renderContext.address() == 0L
            window?.removeWindowListener(windowGone)
            unbound.countDown()
        }
    }

    @Composable
    override fun Content(modifier: Modifier) {
        Canvas(modifier.fillMaxSize().background(Color.Black)) {
            frameTick.longValue // read in the draw scope: each mpv frame invalidates only this draw
            drawIntoCanvas { draw(it.skiaCanvas, size.width.toInt(), size.height.toInt()) }
        }
    }

    private fun requestRedraw() = SwingUtilities.invokeLater { frameTick.longValue++ }

    private fun draw(canvas: SkiaCanvas, w: Int, h: Int) {
        if (unbindRequested) {
            release()
            return
        }
        val bind = pendingBind
        // A host that failed can't show video: the playback waiting for it hears why instead of waiting forever.
        if (renderError != null) {
            if (bind != null) {
                pendingBind = null
                bind.second(renderError)
            }
            return
        }
        // Compose may draw the video before the host is attached to the window's layer: nothing to render yet.
        val l = layer
        if (w <= 0 || h <= 0 || l == null) return
        try {
            // Skiko records Compose's first frame before its redrawer creates the GL context: until that exists, wait
            // for the next frame.
            val dc = direct ?: skiaDirectContext(l)?.also { direct = it } ?: run {
                check(++contextWaits <= CONTEXT_WAIT_FRAMES) { "Skiko has no DirectContext after $CONTEXT_WAIT_FRAMES frames (render API ${l.renderApi})" }
                requestRedraw()
                return
            }
            if (bind != null) {
                pendingBind = null
                onVideo = bind.second
                create(bind.first)
                bind.second(null)
            }
            if (renderContext.address() == 0L) return
            val previous = Gl.currentFramebuffer()
            try {
                if (w != texW || h != texH) reallocate(dc, w, h)
                MpvFfi.mpv_render_context_update(renderContext)
                render(w, h)
            } finally {
                Gl.bindFramebuffer(previous)
                dc.resetGLAll()
            }
            canvas.drawImageRect(checkNotNull(image), Rect.makeWH(texW.toFloat(), texH.toFloat()), Rect.makeWH(w.toFloat(), h.toFloat()), SamplingMode.LINEAR, null, true)
        } catch (t: Throwable) {
            // No draw renders again: the playback fails with why, whether or not the video was ready.
            val error = t.toString()
            renderError = error
            System.getLogger(javaClass.name).log(System.Logger.Level.ERROR, "mpv's video rendering failed", t)
            val report = onVideo ?: pendingBind?.second
            onVideo = null
            pendingBind = null
            report?.invoke(error)
        }
    }

    private fun create(core: MpvCore) {
        val a = Arena.ofShared().also { arena = it }
        val getProcAddress = mpv_opengl_init_params.get_proc_address.allocate({ _, name -> Gl.procAddress(name) }, a)
        val init = mpv_opengl_init_params.allocate(a)
        mpv_opengl_init_params.get_proc_address(init, getProcAddress)
        mpv_opengl_init_params.get_proc_address_ctx(init, MemorySegment.NULL)
        val params = mpv_render_param.allocateArray(3, a)
        param(params, 0, MpvFfi.MPV_RENDER_PARAM_API_TYPE(), MpvFfi.MPV_RENDER_API_TYPE_OPENGL())
        param(params, 1, MpvFfi.MPV_RENDER_PARAM_OPENGL_INIT_PARAMS(), init)
        param(params, 2, MpvFfi.MPV_RENDER_PARAM_INVALID(), MemorySegment.NULL)
        val out = a.allocate(ValueLayout.ADDRESS)
        val rc = MpvFfi.mpv_render_context_create(out, core.handle, params)
        check(rc >= 0) { "mpv_render_context_create: ${MpvCore.errorString(rc)}" }
        renderContext = out.get(ValueLayout.ADDRESS, 0)
        // Called on mpv's threads; must not throw (an exception in an upcall aborts the JVM).
        val onUpdate = mpv_render_update_fn.allocate({ _ -> requestRedraw() }, a)
        MpvFfi.mpv_render_context_set_update_callback(renderContext, onUpdate, MemorySegment.NULL)
    }

    private fun reallocate(dc: DirectContext, w: Int, h: Int) {
        image?.close() // Skia owns, and deletes, the adopted texture
        image = null
        if (fbo != 0) Gl.deleteFramebuffer(fbo)
        fbo = 0
        val texture = Gl.newTexture(w, h)
        fbo = Gl.newFramebuffer(texture)
        Gl.bindFramebuffer(0)
        dc.resetGLAll()
        // FLIP_Y=0 makes mpv write rows top-down, so the image origin is top-left.
        image = Image.adoptTextureFrom(dc, BackendTexture.makeGL(w, h, false, texture, Gl.TEXTURE_2D, Gl.RGBA8), SurfaceOrigin.TOP_LEFT, ColorType.RGBA_8888)
        texW = w
        texH = h
    }

    private fun render(w: Int, h: Int) = Arena.ofConfined().use { a ->
        val target = mpv_opengl_fbo.allocate(a)
        mpv_opengl_fbo.fbo(target, fbo)
        mpv_opengl_fbo.w(target, w)
        mpv_opengl_fbo.h(target, h)
        mpv_opengl_fbo.internal_format(target, Gl.RGBA8)
        val zero = a.allocateFrom(ValueLayout.JAVA_INT, 0)
        val depth = a.allocateFrom(ValueLayout.JAVA_INT, 8)
        val params = mpv_render_param.allocateArray(5, a)
        param(params, 0, MpvFfi.MPV_RENDER_PARAM_OPENGL_FBO(), target)
        param(params, 1, MpvFfi.MPV_RENDER_PARAM_FLIP_Y(), zero)
        param(params, 2, MpvFfi.MPV_RENDER_PARAM_DEPTH(), depth)
        param(params, 3, MpvFfi.MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME(), zero)
        param(params, 4, MpvFfi.MPV_RENDER_PARAM_INVALID(), MemorySegment.NULL)
        MpvFfi.mpv_render_context_render(renderContext, params)
    }

    private fun dispose() {
        if (renderContext.address() != 0L) {
            MpvFfi.mpv_render_context_set_update_callback(renderContext, MemorySegment.NULL, MemorySegment.NULL)
            MpvFfi.mpv_render_context_free(renderContext)
            renderContext = MemorySegment.NULL
        }
        image?.close()
        image = null
        if (fbo != 0) {
            Gl.deleteFramebuffer(fbo)
            fbo = 0
        }
        direct?.resetGLAll()
        arena?.close()
        arena = null
    }

    private fun param(params: MemorySegment, i: Long, type: Int, data: MemorySegment) {
        val p = mpv_render_param.asSlice(params, i)
        mpv_render_param.type(p, type)
        mpv_render_param.data(p, data)
    }

    private companion object {
        /** Frames to wait for Skiko's GL context before the video fails. */
        const val CONTEXT_WAIT_FRAMES = 120
    }
}

/**
 * The DirectContext Skiko renders [layer] with: `SkiaLayer.redrawerManager.redrawer.contextHandler.context` (Skiko
 * 0.150's LinuxOpenGLRedrawer and OpenGLContextHandler), null until its redrawer has created it. Not public API: pin
 * CMP/Skiko and keep the Linux UI tests as a release gate.
 */
private fun skiaDirectContext(layer: SkiaLayer): DirectContext? {
    fun field(o: Any?, name: String): Any? {
        if (o == null) return null
        var c: Class<*>? = o.javaClass
        while (c != null) {
            val f = c.declaredFields.firstOrNull { it.name == name }
            if (f != null) return f.apply { isAccessible = true }.get(o)
            c = c.superclass
        }
        error("Skiko internals changed: no field '$name' in ${o.javaClass.name}")
    }
    val redrawer = field(field(layer, "redrawerManager"), "redrawer") ?: return null
    return field(field(redrawer, "contextHandler"), "context") as DirectContext?
}
