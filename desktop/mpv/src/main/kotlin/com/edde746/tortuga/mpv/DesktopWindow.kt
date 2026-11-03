package com.edde746.tortuga.mpv

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.WindowPlacement
import com.edde746.tortuga.mpv.os.WinRect
import com.edde746.tortuga.mpv.os.Win32
import com.edde746.tortuga.mpv.os.X11
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Dialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.HierarchyEvent

/**
 * Per-OS window behaviour for the player window. Call from the AWT event thread.
 *
 * Fullscreen on Windows is borderless, done in Win32: strip the caption and frame styles, then cover the monitor with
 * `SetWindowPos(SWP_FRAMECHANGED)`. Never Compose's `WindowPlacement.Fullscreen` there: in AWT exclusive fullscreen
 * Skiko clears its layer opaque (the video goes black) and leaving it restored the window at the wrong size at 150 %.
 * On Linux (Xwayland) Compose's fullscreen placement is used; with JetBrains Runtime it tracks the window manager.
 */
class DesktopWindow(private val window: ComposeWindow) {
    private var savedStyle = 0L
    private var savedRect: WinRect? = null

    var isFullscreen: Boolean = false
        private set

    fun setFullscreen(fullscreen: Boolean) {
        if (fullscreen == isFullscreen) return
        if (!isWindows) {
            window.placement = if (fullscreen) WindowPlacement.Fullscreen else WindowPlacement.Floating
        } else {
            val frame = window.windowHandle
            if (fullscreen) {
                savedStyle = Win32.style(frame)
                savedRect = Win32.windowRect(frame)
                val frameStyles = Win32.WS_CAPTION or Win32.WS_THICKFRAME or Win32.WS_SYSMENU or Win32.WS_MINIMIZEBOX or Win32.WS_MAXIMIZEBOX
                Win32.setStyle(frame, savedStyle and frameStyles.toLong().inv())
                val m = Win32.monitorRect(frame)
                Win32.setPos(frame, m.left, m.top, m.width, m.height, Win32.SWP_FRAMECHANGED)
            } else {
                Win32.setStyle(frame, savedStyle)
                val r = checkNotNull(savedRect)
                Win32.setPos(frame, r.left, r.top, r.width, r.height, Win32.SWP_FRAMECHANGED)
            }
        }
        isFullscreen = fullscreen
    }

    /**
     * The window's outer rect `[x, y, width, height]` in physical pixels. On Linux, fullscreen windows have no
     * decorations (KWin drops them) while AWT keeps the windowed insets, so the frame is the content area then.
     */
    fun frameRect(): List<Int> = when {
        isWindows -> Win32.windowRect(window.windowHandle).toList()
        isFullscreen -> contentRect() ?: window.bounds.let { listOf(it.x, it.y, it.width, it.height) }
        else -> window.bounds.let { listOf(it.x, it.y, it.width, it.height) }
    }

    /**
     * The content area (what Compose draws, inside the frame's decorations) `[x, y, width, height]` in screen pixels on
     * Linux, where AWT's pixels are physical (the harness's input and the report's probes use them); null on Windows,
     * whose AWT coordinates are scaled (the Windows harness asks Win32 instead).
     */
    fun contentRect(): List<Int>? {
        if (isWindows || !window.isShowing) return null
        val pane = window.contentPane
        val origin = pane.locationOnScreen
        return listOf(origin.x, origin.y, pane.width, pane.height)
    }

    /** The monitor under the window, `[x, y, width, height]` in physical pixels. */
    fun monitorRect(): List<Int> =
        if (isWindows) Win32.monitorRect(window.windowHandle).toList()
        else window.graphicsConfiguration.bounds.let { listOf(it.x, it.y, it.width, it.height) }

    /** Whether the window's system chrome is dark ([useDarkChrome]), read back from the OS. */
    fun hasDarkChrome(): Boolean = hasDarkChrome(window)

    companion object {
        /**
         * The factor Compose must apply on top of AWT's own scaling: `Xft.dpi` / 96 on Linux, where AWT stays at 1× under
         * Xwayland; 1 on Windows, where AWT already scales per monitor.
         */
        val uiScale: Float by lazy {
            if (System.getProperty("os.name").startsWith("Linux")) (X11.xftScale() ?: 1.0).toFloat() else 1f
        }

        /** Kernel/GDI/USER handle counts on Windows (the libmpv handle leak check); empty elsewhere. */
        fun processHandleCounts(): Map<String, Int> =
            if (System.getProperty("os.name").startsWith("Windows")) Win32.handleCounts() else emptyMap()

        /**
         * Draws the system's chrome (title bar and frame) of every decorated window the process opens from now on dark,
         * whatever the OS's light or dark mode, as Tortuga's one theme is: the main window, the file
         * choosers' dialogs, any later one. Each window gets it as its native window is made, before it first shows: on
         * Windows DWM's dark frame, on Linux (Xwayland) the hints window managers draw their decorations from: KWin's
         * colour scheme for the window (`_KDE_NET_WM_COLOR_SCHEME`, Breeze Dark's) and GTK's dark variant
         * (`_GTK_THEME_VARIANT`, Mutter's). Call once, before the first window. Reaching the
         * native windows takes `--add-exports` of `java.desktop/sun.awt` and of `sun.awt.windows` or `sun.awt.X11`
         * (desktop/app/build.gradle.kts).
         */
        fun useDarkChrome() {
            Toolkit.getDefaultToolkit().addAWTEventListener({ event ->
                val e = event as HierarchyEvent
                val window = e.changed as? Window
                val madeNative = e.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() != 0L && window?.isDisplayable == true
                if (window != null && e.component === window && madeNative && window.isDecorated) {
                    if (isWindows) {
                        Win32.useDarkFrame(Peers.hwnd(window))
                    } else {
                        Peers.withX11(window) { display, xWindow ->
                            X11.setText(display, xWindow, KDE_COLOR_SCHEME, "STRING", BREEZE_DARK)
                            X11.setText(display, xWindow, GTK_THEME_VARIANT, "UTF8_STRING", "dark")
                        }
                    }
                }
            }, AWTEvent.HIERARCHY_EVENT_MASK)
        }

        /** Whether [window]'s system chrome is dark ([useDarkChrome]), read back from the OS: DWM's frame, or both X hints. */
        fun hasDarkChrome(window: Window): Boolean =
            if (isWindows) {
                Win32.hasDarkFrame(Peers.hwnd(window))
            } else {
                Peers.withX11(window) { display, xWindow ->
                    X11.text(display, xWindow, KDE_COLOR_SCHEME) == BREEZE_DARK && X11.text(display, xWindow, GTK_THEME_VARIANT) == "dark"
                }
            }

        /** [window]'s X window on Linux (Xwayland): the parent a desktop portal's dialog is shown over. */
        fun xWindow(window: Window): Long = Peers.withX11(window) { _, xWindow -> xWindow }

        private const val KDE_COLOR_SCHEME = "_KDE_NET_WM_COLOR_SCHEME"
        private const val GTK_THEME_VARIANT = "_GTK_THEME_VARIANT"
        /** KWin reads the file itself: a path on the host (a Flatpak's own /usr is not), where KDE installs Breeze's schemes. */
        private const val BREEZE_DARK = "/usr/share/color-schemes/BreezeDark.colors"
        private val isWindows = System.getProperty("os.name").startsWith("Windows")

        private val Window.isDecorated get() = (this as? Frame)?.isUndecorated == false || (this as? Dialog)?.isUndecorated == false
    }
}

/**
 * The native windows behind AWT's peers, through the JDK's internal API (exported to the app by the JVM flags
 * [DesktopWindow.useDarkChrome] names): a window's HWND on Windows; on Linux its X window and AWT's own X connection,
 * used under AWT's lock so that requests about a window follow AWT's own creation of it.
 */
private object Peers {
    private val accessor = Class.forName("sun.awt.AWTAccessor").getMethod("getComponentAccessor").invoke(null)
    private val getPeer = Class.forName("sun.awt.AWTAccessor\$ComponentAccessor").getMethod("getPeer", Component::class.java)

    private fun peer(window: Window): Any = checkNotNull(getPeer.invoke(accessor, window)) { "window ${window.name} has no native window" }

    private val getHWnd by lazy { Class.forName("sun.awt.windows.WComponentPeer").getMethod("getHWnd") }

    fun hwnd(window: Window): Long = getHWnd.invoke(peer(window)) as Long

    private val getXWindow by lazy { Class.forName("sun.awt.X11.XBaseWindow").getMethod("getWindow") }
    private val getDisplay by lazy { Class.forName("sun.awt.X11.XToolkit").getMethod("getDisplay") }
    private val awtLock by lazy { Class.forName("sun.awt.SunToolkit").getMethod("awtLock") }
    private val awtUnlock by lazy { Class.forName("sun.awt.SunToolkit").getMethod("awtUnlock") }

    fun <T> withX11(window: Window, use: (display: Long, xWindow: Long) -> T): T {
        val xWindow = getXWindow.invoke(peer(window)) as Long
        awtLock.invoke(null)
        try {
            return use(getDisplay.invoke(null) as Long, xWindow)
        } finally {
            awtUnlock.invoke(null)
        }
    }
}
