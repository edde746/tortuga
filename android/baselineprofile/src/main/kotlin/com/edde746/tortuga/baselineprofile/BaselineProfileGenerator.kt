package com.edde746.tortuga.baselineprofile

import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiObject2
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The journeys most sessions take on a TV, by D-pad, whose code the Baseline Profile has ART compile ahead of time:
 * cold start to Home, Home's rows and its shortcut chips, Discover's grid, a movie's Detail → Streams → the player, a
 * series' Detail (its seasons and episodes) → Streams → the player. Each iteration starts cold. Playback itself is not
 * a journey: each ends once the player's video surface is up and its engine has had [PLAYER_MS] to load.
 *
 * The content: a development session (the harness build's `dev` launch extras, android/app/src/debug TestLaunches.kt)
 * with Cinemeta's catalogs (the device online) and the test addon (tools/media/test_addon.py --all-movies --title
 * series:tt0903747:Breaking Bad: every movie and Breaking Bad's episodes have its streams, named "Tortuga Test 2160p" and
 * "Tortuga Test 1080p") on the device's 127.0.0.1:8766 and its media on 127.0.0.1:8765, both over `adb reverse`:
 * the device test harness sets them up.
 *
 * Screens are told apart by focus, not by text the app translates: a card is tall (a poster taller than wide, an
 * episode's 16:9 card wider), a button or a chip short, and a series' season chips sit at the top of the screen once
 * Detail's page has moved down to them.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    /** The product's cold start (signed out: the sign-in screen) and a session's to Home's first row; the startup profile. */
    @Test
    fun startup() = rule.collect(PACKAGE, maxIterations = ITERATIONS, stableIterations = STABLE, includeInStartupProfile = true) {
        coldStart(dev = false)
        awaitFocus("the sign-in screen") { true }
        coldStart()
        awaitFocus("Home's first card") { isCard(it) }
    }

    /**
     * Home's rows, down and along: their catalogs and posters load as they come into view. Then back up to the first
     * shelf and on up to the shortcut chips above it (Up lands on the first chip), along them, and Down to the first
     * shelf's first card.
     */
    @Test
    fun home() = rule.collect(PACKAGE, maxIterations = ITERATIONS, stableIterations = STABLE) {
        coldStart()
        awaitFocus("Home's first card") { isCard(it) }
        repeat(4) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        repeat(6) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        repeat(4) { press(KeyEvent.KEYCODE_DPAD_UP) }
        press(KeyEvent.KEYCODE_DPAD_UP)
        awaitFocus("Home's shortcut chips") { isButton(it) }
        repeat(2) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitFocus("the first shelf's first card") { isCard(it) }
    }

    /** Discover's movie grid down and along (the next page loads), then its Series tab and that grid. */
    @Test
    fun discover() = rule.collect(PACKAGE, maxIterations = ITERATIONS, stableIterations = STABLE) {
        coldStart(open = "discover")
        awaitFocus("Discover's first card") { isCard(it) }
        repeat(5) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        repeat(3) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        // Back to the first column (not past it, into the rail), then up to the tabs: Movies over that column, Series
        // beside it.
        pressUntil("the grid's first column", KeyEvent.KEYCODE_DPAD_LEFT) { it.left < device.displayWidth / 6 }
        pressUntil("Discover's tabs", KeyEvent.KEYCODE_DPAD_UP) { isButton(it) }
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        pressUntil("the series grid", KeyEvent.KEYCODE_DPAD_DOWN) { isCard(it) }
        repeat(3) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        repeat(2) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
    }

    /** Home's first card → its Detail (a remote lands on Play) → the Streams sheet → the player. */
    @Test
    fun movie() = rule.collect(PACKAGE, maxIterations = ITERATIONS, stableIterations = STABLE) {
        coldStart()
        awaitFocus("Home's first card") { isCard(it) }
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocus("Detail's Play") { isButton(it) }
        streamsToPlayer()
    }

    /**
     * Breaking Bad's Detail → its season chips → the next season (a chip selects its season as focus lands on it) →
     * that season's episode row → an episode along it → the Streams sheet → the player.
     */
    @Test
    fun series() = rule.collect(PACKAGE, maxIterations = ITERATIONS, stableIterations = STABLE) {
        coldStart(open = "detail:series:$SERIES")
        awaitFocus("the series' Play") { isButton(it) }
        // Down from Play to the selected season's chip, the page moving up to the seasons.
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitFocus("the season chips") { isChip(it) }
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitFocus("the next season's chip") { isChip(it) }
        // Down into its episode row, on its first episode, then along it.
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitFocus("that season's episodes") { isEpisode(it) }
        repeat(2) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitStreams()
        playSecondStream()
    }

    /** From Detail's focused Play: the Streams sheet, then the player. */
    private fun MacrobenchmarkScope.streamsToPlayer() {
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitStreams()
        playSecondStream()
    }

    private fun MacrobenchmarkScope.awaitStreams() {
        waitFor("the test addon's streams") { device.hasObject(By.pkg(PACKAGE).textContains(STREAM)) }
    }

    /** The 1080p stream (H.264, light on the emulator) played: the player's surface up, its engine loading. */
    private fun MacrobenchmarkScope.playSecondStream() {
        pressUntilReached("the $STREAM stream", KeyEvent.KEYCODE_DPAD_DOWN) { STREAM in focusedText() }
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("the player's video surface") { device.hasObject(By.pkg(PACKAGE).clazz("android.view.SurfaceView")) }
        SystemClock.sleep(PLAYER_MS)
    }
}

/**
 * The app under test: the nonMinifiedHarness build's application ID (com.edde746.tortuga.dev, a test build's), which
 * the androidx.baselineprofile plugin passes as this instrumentation argument.
 */
private val PACKAGE: String =
    checkNotNull(InstrumentationRegistry.getArguments().getString("androidx.benchmark.targetPackageName")) {
        "no androidx.benchmark.targetPackageName argument: run the journeys through :app-android:generateBaselineProfile"
    }
private const val ADDON = "http://127.0.0.1:8766/manifest.json"
private const val SERIES = "tt0903747"
private const val STREAM = "1080p"
private const val ITERATIONS = 5
private const val STABLE = 2
private const val TIMEOUT_MS = 30_000L
private const val MOVE_MS = 2_000L
private const val PLAYER_MS = 3_000L
private const val MAX_PRESSES = 10

/** Home, the app killed, then the launch: a dev session (opening [open]) unless [dev] is false (the product's). */
private fun MacrobenchmarkScope.coldStart(dev: Boolean = true, open: String? = null) {
    pressHome()
    killProcess()
    // A key press first: until one, Android is in touch mode, where nothing is focused.
    device.pressDPadDown()
    startActivityAndWait { intent ->
        if (dev) {
            // The development options, as every platform takes them (DevLaunch).
            val options = listOf("--dev", "--dev-addon", ADDON) + (open?.let { listOf("--dev-open", it) } ?: emptyList())
            intent.putExtra("dev", options.toTypedArray())
            intent.putExtra("mpv", "ao=null")
        }
    }
}

/** Presses [keyCode], then waits (up to [MOVE_MS]) for focus to move: a scrolled list or a loading screen moves it late. */
private fun MacrobenchmarkScope.press(keyCode: Int) {
    val before = focusedBounds() to focusedText()
    device.pressKeyCode(keyCode)
    val deadline = SystemClock.uptimeMillis() + MOVE_MS
    while ((focusedBounds() to focusedText()) == before && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
}

/** A card (a poster, an episode's): taller than an eighth of the screen. */
private fun MacrobenchmarkScope.isCard(bounds: Rect) = bounds.height() > device.displayHeight / 8

/** A button, tab or chip: no taller than an eighth of the screen. */
private fun MacrobenchmarkScope.isButton(bounds: Rect) = !isCard(bounds)

/**
 * A season chip on a series' Detail: a button in the screen's top quarter, where the page moves the seasons once they
 * have focus (the action row stays under the title block, further down).
 */
private fun MacrobenchmarkScope.isChip(bounds: Rect) = isButton(bounds) && bounds.top < device.displayHeight / 4

/** An episode's card in a series' episode row: a card in 16:9, wider than tall (a poster is taller than wide). */
private fun MacrobenchmarkScope.isEpisode(bounds: Rect) = isCard(bounds) && bounds.width() > bounds.height() * 3 / 2

private fun MacrobenchmarkScope.awaitFocus(what: String, focus: MacrobenchmarkScope.(Rect) -> Boolean) {
    waitFor(what) { focusedBounds()?.let { focus(it) } == true }
}

/** Presses [keyCode] until the focused element's bounds pass [focus]. */
private fun MacrobenchmarkScope.pressUntil(what: String, keyCode: Int, focus: MacrobenchmarkScope.(Rect) -> Boolean) {
    pressUntilReached(what, keyCode) { focusedBounds()?.let { focus(it) } == true }
}

/** Presses [keyCode] until [reached]. */
private fun MacrobenchmarkScope.pressUntilReached(what: String, keyCode: Int, reached: () -> Boolean) {
    var presses = 0
    while (!reached()) {
        check(presses++ < MAX_PRESSES) { "focus never reached $what (at ${focusedBounds()}: '${focusedText()}')" }
        press(keyCode)
    }
}

private fun waitFor(what: String, condition: () -> Boolean) {
    val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
    while (!condition()) {
        check(SystemClock.uptimeMillis() < deadline) { "no $what within ${TIMEOUT_MS / 1000} s" }
        SystemClock.sleep(100)
    }
}

/**
 * The focused element, read fresh: Compose's focus moves don't reach this process's accessibility cache, which would
 * keep answering with the element focused when it was first read (UiAutomation.clearCache, API 34).
 */
private fun MacrobenchmarkScope.focused(): UiObject2? {
    InstrumentationRegistry.getInstrumentation().getUiAutomation(Configurator.getInstance().uiAutomationFlags).clearCache()
    return device.findObject(By.pkg(PACKAGE).focused(true))
}

/** The focused element's bounds, or null when nothing is focused or it changed while read. */
private fun MacrobenchmarkScope.focusedBounds(): Rect? = try {
    focused()?.visibleBounds
} catch (e: StaleObjectException) {
    null
}

/** The focused element's text and its descendants', or "" when nothing is focused or it changed while read. */
private fun MacrobenchmarkScope.focusedText(): String = try {
    focused()?.let { node -> buildList<String> { collectText(node, this) }.joinToString(" ") }.orEmpty()
} catch (e: StaleObjectException) {
    ""
}

private fun collectText(node: UiObject2, into: MutableList<String>) {
    node.text?.let(into::add)
    node.contentDescription?.let(into::add)
    node.children.forEach { collectText(it, into) }
}
