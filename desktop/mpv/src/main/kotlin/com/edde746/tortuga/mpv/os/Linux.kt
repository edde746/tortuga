package com.edde746.tortuga.mpv.os

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/** libc: mpv_create refuses to run under a non-C LC_NUMERIC, and the JVM applies the user's locale. */
internal object Posix {
    // glibc's value; the desktop build only targets glibc Linux (macOS uses the native Swift player).
    private const val LC_NUMERIC_GLIBC = 1
    private val setlocale by lazy { linker.defaultLookup().fn("setlocale", PTR, I32, PTR) }

    fun useCNumericLocale() = Arena.ofConfined().use { a ->
        setlocale.call(LC_NUMERIC_GLIBC, a.allocateFrom("C"))
        Unit
    }
}

/** The libX11 calls Linux needs: the desktop scale for Compose, the windows' hints. */
internal object X11 {
    private const val PROP_MODE_REPLACE = 0
    private const val ANY_PROPERTY_TYPE = 0L
    private val lib = SymbolLookup.libraryLookup("libX11.so.6", Arena.global())
    private val xOpenDisplay = lib.fn("XOpenDisplay", PTR, PTR)
    private val xCloseDisplay = lib.fn("XCloseDisplay", I32, PTR)
    private val xResourceManagerString = lib.fn("XResourceManagerString", PTR, PTR)
    private val xInternAtom = lib.fn("XInternAtom", I64, PTR, PTR, I32)
    private val xChangeProperty = lib.fn("XChangeProperty", I32, PTR, I64, I64, I64, I32, I32, PTR, I32)
    private val xGetWindowProperty = lib.fn("XGetWindowProperty", I32, PTR, I64, I64, I64, I64, I32, I64, PTR, PTR, PTR, PTR, PTR)
    private val xFree = lib.fn("XFree", I32, PTR)

    /**
     * `Xft.dpi` / 96 from the RESOURCE_MANAGER string (KDE sets 163 at 1.7×), or null when unset or no display is
     * reachable. AWT only scales by integers on X11, so Compose applies this itself. Opens its own short-lived
     * connection; no XInitThreads, which would have to precede AWT's own Xlib use (libX11 ≥ 1.8 locks by default).
     */
    fun xftScale(): Double? {
        val dpy = xOpenDisplay.call(MemorySegment.NULL) as MemorySegment
        if (dpy.address() == 0L) return null
        try {
            val resources = (xResourceManagerString.call(dpy) as MemorySegment).utf8() ?: return null
            val dpi = Regex("""(?m)^Xft\.dpi:\s*([0-9.]+)""").find(resources)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
            return dpi / 96.0
        } finally {
            xCloseDisplay.call(dpy)
        }
    }

    /** Sets [window]'s property [name] to the text [value] (8-bit, of the type [type]) over the connection [display]. */
    fun setText(display: Long, window: Long, name: String, type: String, value: String) = Arena.ofConfined().use { a ->
        val bytes = value.toByteArray()
        xChangeProperty.call(
            ptr(display), window, atom(a, display, name), atom(a, display, type), 8, PROP_MODE_REPLACE,
            a.allocateFrom(ValueLayout.JAVA_BYTE, *bytes), bytes.size,
        )
    }

    /** [window]'s property [name] as text, read over the connection [display]; null when it has none. */
    fun text(display: Long, window: Long, name: String): String? = Arena.ofConfined().use { a ->
        val type = a.allocate(ValueLayout.JAVA_LONG)
        val format = a.allocate(ValueLayout.JAVA_INT)
        val count = a.allocate(ValueLayout.JAVA_LONG)
        val after = a.allocate(ValueLayout.JAVA_LONG)
        val data = a.allocate(ValueLayout.ADDRESS)
        val status = xGetWindowProperty.call(
            ptr(display), window, atom(a, display, name), 0L, 1024L, 0, ANY_PROPERTY_TYPE, type, format, count, after, data,
        ) as Int
        check(status == 0) { "could not read the property $name of window $window (XGetWindowProperty: $status)" }
        val bytes = data.get(ValueLayout.ADDRESS, 0)
        if (bytes.address() == 0L) return null
        try {
            if (type.get(ValueLayout.JAVA_LONG, 0) == 0L) null
            else String(bytes.reinterpret(count.get(ValueLayout.JAVA_LONG, 0)).toArray(ValueLayout.JAVA_BYTE))
        } finally {
            xFree.call(bytes)
        }
    }

    private fun atom(a: Arena, display: Long, name: String): Long = xInternAtom.call(ptr(display), a.allocateFrom(name), 0) as Long
}

/**
 * GL entry points resolved through glXGetProcAddressARB (context-independent under GLX), for the render-API video
 * host. Every call must run on Skiko's render thread with its GL context current.
 */
internal object Gl {
    const val TEXTURE_2D = 0x0DE1
    const val RGBA8 = 0x8058
    private const val RGBA = 0x1908
    private const val UNSIGNED_BYTE = 0x1401
    private const val TEXTURE_MIN_FILTER = 0x2801
    private const val TEXTURE_MAG_FILTER = 0x2800
    private const val LINEAR = 0x2601
    private const val FRAMEBUFFER = 0x8D40
    private const val COLOR_ATTACHMENT0 = 0x8CE0
    private const val FRAMEBUFFER_COMPLETE = 0x8CD5
    private const val FRAMEBUFFER_BINDING = 0x8CA6

    private val libGL = SymbolLookup.libraryLookup("libGL.so.1", Arena.global())
    private val getProc = libGL.fn("glXGetProcAddressARB", PTR, PTR)

    fun procAddress(name: MemorySegment): MemorySegment = getProc.call(name) as MemorySegment

    private fun fn(name: String, ret: ValueLayout?, vararg args: ValueLayout): MethodHandle {
        val address = Arena.ofConfined().use { a -> procAddress(a.allocateFrom(name)) }
        check(address.address() != 0L) { "GL function $name not found" }
        val desc = if (ret == null) FunctionDescriptor.ofVoid(*args) else FunctionDescriptor.of(ret, *args)
        return linker.downcallHandle(address, desc)
    }

    private val genTextures by lazy { fn("glGenTextures", null, I32, PTR) }
    private val bindTexture by lazy { fn("glBindTexture", null, I32, I32) }
    private val texImage2D by lazy { fn("glTexImage2D", null, I32, I32, I32, I32, I32, I32, I32, I32, PTR) }
    private val texParameteri by lazy { fn("glTexParameteri", null, I32, I32, I32) }
    private val genFramebuffers by lazy { fn("glGenFramebuffers", null, I32, PTR) }
    private val bindFramebuffer by lazy { fn("glBindFramebuffer", null, I32, I32) }
    private val framebufferTexture2D by lazy { fn("glFramebufferTexture2D", null, I32, I32, I32, I32, I32) }
    private val checkFramebufferStatus by lazy { fn("glCheckFramebufferStatus", I32, I32) }
    private val deleteFramebuffers by lazy { fn("glDeleteFramebuffers", null, I32, PTR) }
    private val getIntegerv by lazy { fn("glGetIntegerv", null, I32, PTR) }

    private fun gen(h: MethodHandle): Int = Arena.ofConfined().use { a ->
        val out = a.allocate(ValueLayout.JAVA_INT)
        h.call(1, out)
        out.get(ValueLayout.JAVA_INT, 0)
    }

    fun newTexture(w: Int, h: Int): Int {
        val t = gen(genTextures)
        bindTexture.call(TEXTURE_2D, t)
        texParameteri.call(TEXTURE_2D, TEXTURE_MIN_FILTER, LINEAR)
        texParameteri.call(TEXTURE_2D, TEXTURE_MAG_FILTER, LINEAR)
        texImage2D.call(TEXTURE_2D, 0, RGBA8, w, h, 0, RGBA, UNSIGNED_BYTE, MemorySegment.NULL)
        bindTexture.call(TEXTURE_2D, 0)
        return t
    }

    fun newFramebuffer(texture: Int): Int {
        val f = gen(genFramebuffers)
        bindFramebuffer.call(FRAMEBUFFER, f)
        framebufferTexture2D.call(FRAMEBUFFER, COLOR_ATTACHMENT0, TEXTURE_2D, texture, 0)
        val status = checkFramebufferStatus.call(FRAMEBUFFER) as Int
        check(status == FRAMEBUFFER_COMPLETE) { "framebuffer incomplete: 0x%x".format(status) }
        return f
    }

    fun deleteFramebuffer(f: Int) = Arena.ofConfined().use { a ->
        val ids = a.allocate(ValueLayout.JAVA_INT)
        ids.set(ValueLayout.JAVA_INT, 0, f)
        deleteFramebuffers.call(1, ids)
        Unit
    }

    fun bindFramebuffer(f: Int) { bindFramebuffer.call(FRAMEBUFFER, f) }

    fun currentFramebuffer(): Int = Arena.ofConfined().use { a ->
        val out = a.allocate(ValueLayout.JAVA_INT)
        getIntegerv.call(FRAMEBUFFER_BINDING, out)
        out.get(ValueLayout.JAVA_INT, 0)
    }
}
