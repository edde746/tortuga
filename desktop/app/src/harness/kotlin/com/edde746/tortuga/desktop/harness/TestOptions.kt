package com.edde746.tortuga.desktop.harness

import com.edde746.tortuga.app.DevLaunch
import com.edde746.tortuga.data.DatabaseFactory
import com.edde746.tortuga.desktop.Cli
import com.edde746.tortuga.desktop.MinimumWindowSize
import java.nio.file.Path

/**
 * The harness launcher's test-only options ([TestMain]), on top of the app's own ([Cli]): the development options
 * ([DevLaunch], read as every platform reads them), with `--dev` a development session instead of the product: a
 * throwaway in-memory database (or one kept in [dataDir]) with one local profile and no account, opened on the app's
 * home screen, or on a dev entry's screen (`--url`, `--dev-open`). The product path never creates a profile on its own.
 * Both take the app's options and its trailing `stremio:` link.
 */
internal data class TestOptions(
    /** The development options: a development session ([DevLaunch.dev]), or the product's start's test hooks. */
    val dev: DevLaunch = DevLaunch.parse(emptyList()),
    /** Sample the app into this journal ([TestReport]). */
    val report: Path? = null,
    /** Quit after this many seconds. */
    val exitAfterSeconds: Double? = null,
    /** Crash the app (an uncaught exception carrying an addon URL) this many seconds after the start. */
    val crashAfterSeconds: Double? = null,
    /** PlayerEngine setting calls to make during the run. */
    val engineSets: List<EngineSet> = emptyList(),
    /**
     * Product: a device of its own, with its database in this directory and its secrets named after it (`--data-dir`).
     * With [dev]: the development session's database in this directory instead of in memory, so the next launch with
     * it continues the session (its profile, addons, library, settings and downloads): `--data-dir`, or `--dev-data
     * <name>`'s directory under the device's own.
     */
    val dataDir: Path? = null,
    /** The content area's size in dp at startup (1280×720 dp otherwise), scaled down to fit the work area. */
    val windowSize: Pair<Int, Int>? = null,
) {
    /** One PlayerEngine setting call: [name] = [value], made [atSeconds] after startup. */
    data class EngineSet(val name: String, val value: String, val atSeconds: Double)

    companion object {
        /** The settings `--engine-set` can call: PlayerEngine.setSpeed, setSubtitleDelay, setAudioDelay, setVolume, setMuted. */
        val ENGINE_SETTINGS = setOf("speed", "sub-delay", "audio-delay", "volume", "mute")

        val USAGE = """
            usage: TestMainKt [test options] [app options] [<stremio: link>]
                   TestMainKt --dev [--dev-addon <manifest url>]... [--dev-addons-only] [--dev-watch-activity]
                              [--dev-profile <name>[:<avatar>[:<pin>]]] [--dev-kids <pin>] [--dev-language <tag>]
                              [--dev-library <type>:<id>]... [--dev-setting <key>=<value>]... [--dev-progress <progress>]...
                              [--dev-trackers <url> [--dev-trackers-connected | --dev-relay <url>]]
                              [--dev-discord <application id>] [--dev-crash-reports <dsn>] [--dev-data <name>]
                              [--url <media url> | (--dev-open <screen>)...]
                              [test options] [app options] [<stremio: link>]
            development (a throwaway in-memory database, or one kept with --dev-data or --data-dir, with one local
            profile, no account or sync):
              --dev         start a development session on the app's home screen; required by the entries below
              --url         play this media URL straight away
              --dev-addon   install this addon into the dev profile, repeatable
              --dev-addons-only
                            the dev profile has the --dev-addon addons alone: no Cinemeta, no OpenSubtitles
              --dev-watch-activity
                            the --dev-addon addons that ask for the user's watch activity (their manifest declares
                            the player or library resource) get it, as when the user agrees in the install dialog;
                            without it they get none
              --dev-profile name the dev profile <name> instead of Developer, with the avatar of that id where
                            given (graphite, slate, silver, ivory, sand, clay, rose, plum, dusk, sea, sage, moss) and
                            locked with the four-digit <pin> where given
              --dev-kids    make the dev profile a kids profile, with a profile named Parent beside it locked with
                            this four-digit <pin> (the PIN a parent check takes)
              --dev-language
                            show the app in this interface language, set as the dev profile's: a tag this build has
                            strings for (shared/i18n/<tag>.i18n.json: ar, de, pt-BR); the launch fails on any other
              --dev-library start with the title <type>:<id> in the library, repeatable, the first given leading it
                            (its meta comes from the installed addons)
              --dev-open    open a screen, repeatable: the first is the start, each next one opens on top of the one
                            before (Back returns to it). <screen> is detail:<type>:<id>, the Detail screen of
                            <type>/<id>; streams:<type>:<id>:<videoId>, the Streams sheet of <videoId> of <type>/<id>
                            (its meta comes from the installed addons); a top-level screen: board, discover, search,
                            library, settings; addons (above Settings); profile, a new profile's editor over the home
                            screen; or restored, alone, the screens the profile's last session left in the kept
                            database. A series episode's <videoId> starts with
                            its <id>: streams:series:tt0944947:tt0944947:1:1 is id tt0944947, video tt0944947:1:1
              --dev-progress
                            start with this watch progress, repeatable, Continue Watching in the order given: <progress>
                            is <type>:<id>:<videoId>:<seconds>:<duration seconds> (the item's meta comes from the
                            installed addons), e.g. movie:tt0111161:tt0111161:600:6000
              --dev-setting start with this device setting, repeatable: <key>=true|false for video.hardwareDecoding,
                            video.hdr, video.matchFrameRate, audio.passthrough, discord.presence and
                            privacy.crashReports, or playback.cacheSizeMegabytes=<MB> (16-4096) or =auto, or the
                            subtitle style: subtitles.sizePercent (50-200), subtitles.textColor and
                            subtitles.outlineColor (white, yellow, green, cyan, gray, black),
                            subtitles.outline (none, thin, thick), subtitles.backgroundPercent (0-100),
                            subtitles.liftPercent (0-50), subtitles.bold and subtitles.styledOverride (true|false), or
                            offline=true to hold the app offline whatever the network (offline mode, downloads), or
                            player.fastTimers=true to count the sleep timer's minutes as 6 s each (5 minutes in 30 s),
                            or the player every play opens in: playback.player=<player id> (tortuga, a found player app:
                            mpv, vlc, mpc-hc, mpc-be, potplayer, celluloid, or custom:dev), and
                            "playback.customPlayer=<name>;<program>;<arguments>" adds the custom player custom:dev
                            (<arguments> may be empty: {url} {title} {start} {subtitle} expand in it);
                            <key>=<value>@<seconds> stores it that many seconds after the start instead
              --dev-trackers offer Trakt only, against this fake tracker server (tools/media/test_trackers.py)
                            instead of the build's tracker registrations
              --dev-trackers-connected
                            with --dev-trackers: offer every tracker service against the fake and connect each to the
                            dev profile at the start, without signing in (Detail shows every score the fake has)
              --dev-relay   with --dev-trackers: MyAnimeList and AniList beside Trakt, not connected, signing in
                            through the tracker sign-in relay of the Tortuga server at this URL (a development stack's)
              --dev-discord offer Discord's Rich Presence as this Discord application (any id: the tests' fake Discord
                            takes any); without it a dev session has none, whatever the build's
              --dev-crash-reports
                            send the session's crash reports to this Sentry DSN, a fake Sentry's
                            (tools/media/fake_sentry.py: http://public@<host>:<port>/1), while privacy.crashReports
                            is on (it is by default); without it the session sends none
              --dev-data    keep the dev session's database (and its downloads) under this name (letters, digits, -
                            and _) in the device's data directory instead of in memory, so the next launch with the
                            same name continues it; not with --data-dir
            test options:
              --report      sample the app's screens, the player and mpv stats into this journal (report.jsonl, one
                            JSON record per line; the latest sample alone in report.last.json beside it)
              --exit-after  quit after this many seconds
              --crash-after crash the app this many seconds after the start: an uncaught exception whose message
                            carries an addon URL with a made-up API key, which no crash report may hold
              --engine-set  repeatable: call a PlayerEngine setter (speed, sub-delay, audio-delay, volume, mute=yes|no)
                            on the first playback's engine at its start, or <seconds> after startup (e.g. speed=1.5@8)
              --data-dir    run as a device of its own: the database in this directory and the secrets under a name
                            derived from it, apart from the device's own (a fresh device, a second instance); with
                            --dev, the dev session's database (and its downloads) there instead of in memory, so the
                            next launch with the same directory continues it (a download picked up again)
              --dev-browser (not with --dev) open sign-in pages by running this program with the page's URL instead of
                            the browser (a .ps1 runs through PowerShell), so a harness completes the provider's login
              --window-size open the window with a content area of <width>x<height> dp (default 1280x720, at least
                            640x480; smaller if the screen's work area is)
              --dev-slow-start
                            (not with --dev) hold the start's account step this many seconds longer, so the splash
                            shows its status line (it names the step once the start has taken half a second)
            app options:
        """.trimIndent() + "\n" + Cli.USAGE.lines().drop(1).joinToString("\n")

        /** The app's options and the test options in [args], in any order; the link last, as for the app. */
        fun parse(args: Array<String>): Pair<Cli, TestOptions> {
            val devArgs = mutableListOf<String>()
            var report: Path? = null
            var exitAfter: Double? = null
            var crashAfter: Double? = null
            val engineSets = mutableListOf<EngineSet>()
            var dataDir: Path? = null
            var windowSize: Pair<Int, Int>? = null
            val cli = Cli.parse(args) testOption@{ a, value ->
                when (a) {
                    "--report" -> report = Path.of(value())
                    "--exit-after" -> exitAfter = value().toDoubleOrNull()?.takeIf { it > 0 } ?: throw Cli.Error("--exit-after needs a positive number")
                    "--crash-after" -> crashAfter = value().toDoubleOrNull()?.takeIf { it > 0 } ?: throw Cli.Error("--crash-after needs a positive number")
                    "--engine-set" -> engineSets += engineSet(value())
                    "--data-dir" -> dataDir = Path.of(value())
                    "--window-size" -> windowSize = windowSize(value())
                    else -> {
                        // The development options go to DevLaunch as every platform's do, with their values.
                        if (!DevLaunch.isOption(a)) return@testOption false
                        devArgs += a
                        if (DevLaunch.takesValue(a)) devArgs += value()
                    }
                }
                true
            }
            val dev = try {
                DevLaunch.parse(devArgs)
            } catch (e: IllegalArgumentException) {
                throw Cli.Error(e.message ?: e.toString())
            }
            if (dev.signInServer != null) throw Cli.Error("--dev-sign-in-server: the desktop's sign-in screen starts on the hosted server, or one the person picks")
            if (dataDir != null && dev.data != null) throw Cli.Error("--data-dir and --dev-data both keep the dev session's database: pass one")
            val devData = dev.data?.let { DatabaseFactory.defaultDirectory().resolve("dev-data").resolve(it) }
            return cli to TestOptions(dev, report, exitAfter, crashAfter, engineSets, dataDir ?: devData, windowSize)
        }

        /** `<width>x<height>` in dp, at least [MinimumWindowSize]. */
        private fun windowSize(spec: String): Pair<Int, Int> {
            val (minWidth, minHeight) = MinimumWindowSize
            val parts = spec.split('x').map { it.toIntOrNull() ?: -1 }
            if (parts.size != 2 || parts[0] < minWidth || parts[1] < minHeight) {
                throw Cli.Error("--window-size needs <width>x<height> in dp, at least ${minWidth}x$minHeight, got '$spec'")
            }
            return parts[0] to parts[1]
        }

        /** `<name>=<value>[@<seconds>]`. */
        private fun engineSet(kv: String): EngineSet {
            val eq = kv.indexOf('=')
            if (eq <= 0) throw Cli.Error("--engine-set needs <name>=<value>, got '$kv'")
            val name = kv.substring(0, eq)
            val spec = kv.substring(eq + 1)
            if (name !in ENGINE_SETTINGS) throw Cli.Error("--engine-set: unknown setting '$name' (one of ${ENGINE_SETTINGS.joinToString()})")
            val value = spec.substringBefore('@')
            val at = if ('@' in spec) spec.substringAfter('@').toDoubleOrNull()?.takeIf { it >= 0 } ?: throw Cli.Error("--engine-set: bad time in '$spec'") else 0.0
            val valid = if (name == "mute") value == "yes" || value == "no" else value.toDoubleOrNull() != null
            if (!valid) throw Cli.Error("--engine-set: bad value for $name: '$value'")
            return EngineSet(name, value, at)
        }
    }
}
