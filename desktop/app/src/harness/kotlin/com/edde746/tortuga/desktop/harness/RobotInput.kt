package com.edde746.tortuga.desktop.harness

import com.edde746.tortuga.mpv.DesktopWindow
import java.awt.MouseInfo
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.system.exitProcess

/**
 * Harness only (Linux test runs): real mouse and keyboard input into the Xwayland
 * session, one command per line on stdin, each answered `ok` or `error <reason>` on stdout once done.
 *
 *     java -Dsun.java2d.uiScale=1 @harness.args com.edde746.tortuga.desktop.harness.RobotInputKt
 *
 * Why a JVM program: KWin passes emulated X11 input (XTEST through Xwayland's EI) only from the executables named in
 * `kwinrc` `[Xwayland] XwaylandEisNoPromptApps`, which is `java` for test runs; anything
 * else gets a permission prompt. Screen coordinates are X11 root pixels, which are physical pixels there (Xwayland apps
 * scale themselves), the same pixels the app's report uses.
 *
 * The pointer moves through XTEST as the buttons do, so KWin's pointer moves and Xwayland's follows it. Robot's own
 * mouseMove is an XWarpPointer, which moves only Xwayland's pointer (KWin follows a warp only while the app hides the
 * cursor): KWin's stays behind, and when KWin sends its position between a click's press and release, the release
 * lands there and the click is lost (about one run in two).
 *
 * Commands (keys are AWT `KeyEvent.VK_*` names without the prefix; a chord joins them with `+`):
 * - `move X Y`
 * - `click X Y [left|right|middle]`, `double X Y`
 * - `press X Y` / `release X Y` (the left button, for a drag)
 * - `wheel X Y NOTCHES [KEY]` (positive scrolls down, as AWT counts), with KEY held, e.g. `SHIFT` (sideways)
 * - `key NAME[+NAME...]`, e.g. `key ENTER`, `key CONTROL+A`, `key ALT+F4`
 * - `xkey KEYSYM`: one key by X keysym name, for keys Robot can't send (`xkey Menu`)
 * - `type TEXT`: US-layout key presses for printable ASCII (the test host's layout)
 */
fun main() {
    val robot = Robot().apply { autoDelay = 0 }
    for (line in generateSequence(::readLine)) {
        if (line.isBlank()) continue
        val reply = try {
            run(robot, line)
            "ok"
        } catch (e: Exception) {
            "error ${e.message ?: e::class.simpleName}"
        }
        println(reply)
        System.out.flush()
    }
    exitProcess(0)
}

private fun run(robot: Robot, line: String) {
    val command = line.substringBefore(' ')
    val rest = line.substringAfter(' ', "")
    val args = rest.split(' ').filter { it.isNotEmpty() }
    fun int(i: Int) = args.getOrNull(i)?.toIntOrNull() ?: throw IllegalArgumentException("'$command' needs a number at ${i + 1}")
    when (command) {
        "move" -> moveTo(int(0), int(1))
        "click" -> {
            moveTo(int(0), int(1))
            click(robot, button(args.getOrNull(2) ?: "left"))
        }
        "double" -> {
            moveTo(int(0), int(1))
            click(robot, InputEvent.BUTTON1_DOWN_MASK)
            Thread.sleep(80)
            click(robot, InputEvent.BUTTON1_DOWN_MASK)
        }
        "press" -> {
            moveTo(int(0), int(1))
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        }
        "release" -> {
            moveTo(int(0), int(1))
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        }
        "wheel" -> {
            moveTo(int(0), int(1))
            val held = args.getOrNull(3)?.let(::keyCode)
            if (held == null) {
                robot.mouseWheel(int(2))
            } else {
                robot.keyPress(held)
                try {
                    Thread.sleep(60)
                    robot.mouseWheel(int(2))
                    Thread.sleep(60)
                } finally {
                    robot.keyRelease(held)
                }
            }
        }
        "key" -> chord(robot, rest.trim().split('+').map(::keyCode))
        "xkey" -> XTest.key(rest.trim())
        "type" -> rest.forEach { typeChar(robot, it) }
        else -> throw IllegalArgumentException("unknown command '$command'")
    }
}

private fun button(name: String) = when (name) {
    "left" -> InputEvent.BUTTON1_DOWN_MASK
    "middle" -> InputEvent.BUTTON2_DOWN_MASK
    "right" -> InputEvent.BUTTON3_DOWN_MASK
    else -> throw IllegalArgumentException("unknown button '$name'")
}

private fun click(robot: Robot, button: Int) {
    robot.mousePress(button)
    Thread.sleep(40)
    robot.mouseRelease(button)
}

/**
 * What java.awt.Robot doesn't send through XTEST: keys its keysym table lacks (no VK_CONTEXT_MENU: the key arrives as
 * keycode 0), by X keysym name (`Menu`), and pointer motion (moveTo). From this same `java` process, so KWin takes it.
 */
private object XTest {
    private val linker = Linker.nativeLinker()
    private val arena = Arena.global()
    private val x11 = SymbolLookup.libraryLookup("libX11.so.6", arena)
    private val xtst = SymbolLookup.libraryLookup("libXtst.so.6", arena)
    private fun fn(lookup: SymbolLookup, name: String, descriptor: FunctionDescriptor) =
        linker.downcallHandle(lookup.find(name).orElseThrow(), descriptor)
    private val openDisplay = fn(x11, "XOpenDisplay", FunctionDescriptor.of(ADDRESS, ADDRESS))
    private val stringToKeysym = fn(x11, "XStringToKeysym", FunctionDescriptor.of(JAVA_LONG, ADDRESS))
    private val keysymToKeycode = fn(x11, "XKeysymToKeycode", FunctionDescriptor.of(JAVA_BYTE, ADDRESS, JAVA_LONG))
    private val flush = fn(x11, "XFlush", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    private val fakeKey = fn(xtst, "XTestFakeKeyEvent", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_LONG))
    private val fakeMotion = fn(xtst, "XTestFakeMotionEvent", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG))
    private val display: MemorySegment by lazy {
        (openDisplay.invokeExact(MemorySegment.NULL) as MemorySegment).also { check(it.address() != 0L) { "XOpenDisplay failed" } }
    }

    fun key(keysymName: String) {
        val keysym = stringToKeysym.invokeExact(arena.allocateFrom(keysymName)) as Long
        require(keysym != 0L) { "unknown X keysym '$keysymName'" }
        val keycode = (keysymToKeycode.invokeExact(display, keysym) as Byte).toInt() and 0xff
        require(keycode != 0) { "no keycode for X keysym '$keysymName'" }
        for (down in intArrayOf(1, 0)) {
            fakeKey.invokeExact(display, keycode, down, 0L) as Int
            flush.invokeExact(display) as Int
            Thread.sleep(40)
        }
    }

    /** An absolute motion to [x], [y] on the current screen. */
    fun motion(x: Int, y: Int) {
        fakeMotion.invokeExact(display, -1, x, y, 0L) as Int
        flush.invokeExact(display) as Int
    }
}

/**
 * Moves KWin's pointer (XTEST through EI, in KWin's logical pixels: root pixels over Xwayland's scale, which KWin
 * gives X clients as `Xft.dpi`) and waits up to a second for Xwayland's pointer, which KWin's motion moves, to get
 * there. Over the app's windows it does within milliseconds (the first motion also waits for Xwayland's EI
 * connection); over what KWin draws itself (a title bar) it doesn't follow, and the wait runs out.
 */
private fun moveTo(x: Int, y: Int) {
    val scale = DesktopWindow.uiScale
    XTest.motion((x / scale).roundToInt(), (y / scale).roundToInt())
    val slack = ceil(scale).toInt()
    val until = System.nanoTime() + 1_000_000_000
    while (System.nanoTime() < until) {
        val at = MouseInfo.getPointerInfo()?.location
        if (at != null && abs(at.x - x) <= slack && abs(at.y - y) <= slack) return
        Thread.sleep(10)
    }
}

private fun keyCode(name: String): Int =
    runCatching { KeyEvent::class.java.getField("VK_${name.uppercase()}").getInt(null) }
        .getOrElse { throw IllegalArgumentException("unknown key '$name'") }

/** Presses [codes] in order and releases them in reverse. */
private fun chord(robot: Robot, codes: List<Int>) {
    codes.forEach { robot.keyPress(it); Thread.sleep(30) }
    codes.asReversed().forEach { robot.keyRelease(it); Thread.sleep(30) }
}

private fun typeChar(robot: Robot, c: Char) {
    val (code, shift) = US_KEYS[c] ?: when (c) {
        in 'a'..'z' -> (KeyEvent.VK_A + (c - 'a')) to false
        in 'A'..'Z' -> (KeyEvent.VK_A + (c - 'A')) to true
        in '0'..'9' -> (KeyEvent.VK_0 + (c - '0')) to false
        else -> throw IllegalArgumentException("can't type '$c'")
    }
    chord(robot, if (shift) listOf(KeyEvent.VK_SHIFT, code) else listOf(code))
    Thread.sleep(12)
}

/** Printable ASCII other than letters and digits on a US layout: the key and whether it needs Shift. */
private val US_KEYS: Map<Char, Pair<Int, Boolean>> = buildMap {
    fun plain(chars: String, vararg codes: Int) = chars.forEachIndexed { i, c -> put(c, codes[i] to false) }
    fun shifted(chars: String, vararg codes: Int) = chars.forEachIndexed { i, c -> put(c, codes[i] to true) }
    plain(
        " -=[]\\;',./`",
        KeyEvent.VK_SPACE, KeyEvent.VK_MINUS, KeyEvent.VK_EQUALS, KeyEvent.VK_OPEN_BRACKET, KeyEvent.VK_CLOSE_BRACKET,
        KeyEvent.VK_BACK_SLASH, KeyEvent.VK_SEMICOLON, KeyEvent.VK_QUOTE, KeyEvent.VK_COMMA, KeyEvent.VK_PERIOD,
        KeyEvent.VK_SLASH, KeyEvent.VK_BACK_QUOTE,
    )
    shifted(
        "_+{}|:\"<>?~!@#$%^&*()",
        KeyEvent.VK_MINUS, KeyEvent.VK_EQUALS, KeyEvent.VK_OPEN_BRACKET, KeyEvent.VK_CLOSE_BRACKET, KeyEvent.VK_BACK_SLASH,
        KeyEvent.VK_SEMICOLON, KeyEvent.VK_QUOTE, KeyEvent.VK_COMMA, KeyEvent.VK_PERIOD, KeyEvent.VK_SLASH,
        KeyEvent.VK_BACK_QUOTE, KeyEvent.VK_1, KeyEvent.VK_2, KeyEvent.VK_3, KeyEvent.VK_4, KeyEvent.VK_5, KeyEvent.VK_6,
        KeyEvent.VK_7, KeyEvent.VK_8, KeyEvent.VK_9, KeyEvent.VK_0,
    )
}
