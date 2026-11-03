package com.edde746.tortuga.app

import com.edde746.tortuga.strings.InterfaceLocale
import com.edde746.tortuga.suggest.MockPicks
import com.edde746.tortuga.suggest.MockUser

/**
 * A launch's development options, the same on every platform, as [parse] reads them: the desktop harness launcher's
 * arguments, the Apple apps' launch arguments (`xcrun simctl launch booted com.edde746.tortuga --dev …`, a UI test's
 * `launchArguments`) and Android's `dev` launch extra, a string array of the same arguments
 * (`am start --esa dev --dev,--dev-open,settings`).
 *
 * [dev] starts a development session instead of the product: [AppBootstrap.dev]'s graph on an in-memory database (or
 * the one kept under [data]) with one local profile and no account or sync, which [applyDevSettings] and
 * [startDevSession] set up, on the app's home screen, the [open] screens, or with [url] the player alone. Every other
 * session option needs it. [slowStartMillis], [signInServer] and [browser] test the product's start and sign-in
 * instead, so they refuse it.
 *
 * A platform that can't do an option it is given fails the launch saying so, as [parse] fails on an option it doesn't
 * know: a launch never runs without an option it asked for.
 */
class DevLaunch private constructor(
    /** `--dev`: a development session instead of the product. */
    val dev: Boolean,
    /** `--url <media url>`: the player alone on this URL ([devAppSession]); leaving it leaves the app. */
    val url: String?,
    /** `--dev-addon <manifest url>`, repeatable: addons installed into the dev profile. */
    val addons: List<String>,
    /** `--dev-addons-only`: the dev profile has [addons] alone, in their order: no Cinemeta and no OpenSubtitles. */
    val addonsOnly: Boolean,
    /**
     * `--dev-watch-activity`: those of [addons] whose manifests declare the `player` or `library` resource receive the
     * session's watch activity, as when the user agrees in the install dialog; without it they get none.
     */
    val watchActivity: Boolean,
    /** `--dev-profile <name>[:<avatar>[:<pin>]]`: the dev profile's name, avatar and PIN ([DevProfile]). */
    val profile: DevProfile?,
    /** `--dev-kids <pin>`: the dev profile a kids profile, beside a parent's locked with this PIN ([DevKids]). */
    val kids: DevKids?,
    /** `--dev-language <tag>`: the dev profile's interface language, a tag of [InterfaceLocale.available]. */
    val language: String?,
    /** `--dev-library <type>:<id>`, repeatable: titles in the dev profile's library, the first leading it. */
    val library: List<DevLibraryItem>,
    /** `--dev-open <screen>`, repeatable: the screens to open, each on top of the one before ([DevOpen]). */
    val open: List<DevOpen>,
    /** `--dev-progress <progress>`, repeatable: watch progress, Continue Watching in the order given ([DevProgress]). */
    val progress: List<DevProgress>,
    /** `--dev-watched <video>`, repeatable: videos marked watched, after [progress] is recorded ([DevWatched]). */
    val watched: List<DevWatched>,
    /** `--dev-setting <key>=<value>`, repeatable: this device's settings to start with ([DevSetting]). */
    val settings: List<DevSetting>,
    /**
     * `--dev-trackers <url>`, with `--dev-trackers-connected` or `--dev-relay <url>`: the trackers against this fake
     * tracker server instead of the build's registrations ([DevTrackers]).
     */
    val trackers: DevTrackers?,
    /**
     * `--dev-discord <application id>`: Discord's Rich Presence shows as this Discord application (a test one: the
     * tests' fake Discord takes any); without it the session has none, whatever the build's.
     */
    val discord: String?,
    /**
     * `--dev-crash-reports <dsn>`: the session's crash reports go to this Sentry DSN (a fake Sentry's) while
     * `privacy.crashReports` is on; without it the session sends none, whatever the build's.
     */
    val crashReports: String?,
    /**
     * `--dev-data <name>`: the session's database kept on the device under this name (letters, digits, `-` and `_`),
     * so the next launch with the same name continues the session; in memory without it.
     */
    val data: String?,
    /** `--dev-slow-start <seconds>`, in milliseconds: the product's start holds its account step this much longer. */
    val slowStartMillis: Long,
    /** `--dev-sign-in-server <url>`: the product's sign-in screen starts on this server instead of the hosted one. */
    val signInServer: String?,
    /** `--dev-browser <program>`: the product opens sign-in pages by running this program with the page's URL. */
    val browser: String?,
    /**
     * `--dev-picks <user>`: the dev profile's stream picks on every kind of device, the mocked [user]'s history
     * ([MockPicks.USERS] by name: `phone`, `tv`, `anime`, `noisy`), so stream suggestions have something to go by; it
     * turns them on ([suggestions]).
     */
    val picks: MockUser?,
    /**
     * `--dev-suggestions <true|false>`: the dev profile's stream suggestions switch (Settings → Playback → Streams)
     * on or off; on with [picks] unless given `false`; null leaves it as stored.
     */
    val suggestions: Boolean?,
    /**
     * `--dev-blur-unwatched`: the dev profile's "Blur unwatched episodes" switch (Settings → Playback → Playback) on;
     * without it, left as stored.
     */
    val blurUnwatched: Boolean,
) {
    companion object {
        /**
         * The options in [args], in any order. Arguments that aren't options of this type ([isOption]) are the
         * platform's own, and are skipped.
         *
         * @throws IllegalArgumentException for an unknown `--dev…` option, an option without its value or with one it
         *   doesn't take, or options that don't go together, with the message to show.
         */
        @Throws(IllegalArgumentException::class)
        fun parse(args: List<String>): DevLaunch {
            var dev = false
            var url: String? = null
            val addons = mutableListOf<String>()
            var addonsOnly = false
            var watchActivity = false
            var profile: DevProfile? = null
            var kids: DevKids? = null
            var language: String? = null
            val library = mutableListOf<DevLibraryItem>()
            val open = mutableListOf<DevOpen>()
            val progress = mutableListOf<DevProgress>()
            val watched = mutableListOf<DevWatched>()
            val settings = mutableListOf<DevSetting>()
            var trackersUrl: String? = null
            var trackersConnected = false
            var relay: String? = null
            var discord: String? = null
            var crashReports: String? = null
            var data: String? = null
            var slowStartMillis = 0L
            var signInServer: String? = null
            var browser: String? = null
            var picks: MockUser? = null
            var suggestions: Boolean? = null
            var blurUnwatched = false
            val given = linkedSetOf<String>()
            var i = 0
            while (i < args.size) {
                val name = args[i++]
                if (!isOption(name)) continue
                given += name
                when (name) {
                    DEV -> dev = true
                    ADDONS_ONLY -> addonsOnly = true
                    WATCH_ACTIVITY -> watchActivity = true
                    TRACKERS_CONNECTED -> trackersConnected = true
                    BLUR_UNWATCHED -> blurUnwatched = true
                    else -> {
                        val next = args.getOrNull(i++)
                        fun value(): String = next ?: throw IllegalArgumentException("$name needs a value")
                        when (name) {
                            URL -> url = value()
                            ADDON -> addons += value()
                            PROFILE -> profile = read(name, value()) { DevProfile.parse(it) }
                            KIDS -> kids = read(name, value()) { DevKids.parse(it) }
                            LANGUAGE -> language = read(name, value(), ::interfaceLanguage)
                            LIBRARY -> library += read(name, value()) { DevLibraryItem.parse(it) }
                            OPEN -> open += read(name, value()) { DevOpen.parse(it) }
                            PROGRESS -> progress += read(name, value()) { DevProgress.parse(it) }
                            WATCHED -> watched += read(name, value()) { DevWatched.parse(it) }
                            SETTING -> settings += read(name, value()) { DevSetting.parse(it) }
                            TRACKERS -> trackersUrl = value()
                            RELAY -> relay = value()
                            DISCORD -> discord = read(name, value(), ::discordApplication)
                            CRASH_REPORTS -> crashReports = read(name, value(), ::sentryDsn)
                            DATA -> data = read(name, value(), ::dataName)
                            SLOW_START -> slowStartMillis = read(name, value(), ::seconds)
                            SIGN_IN_SERVER -> signInServer = value()
                            BROWSER -> browser = value()
                            PICKS -> picks = read(name, value()) { user ->
                                MockPicks.USERS.firstOrNull { it.name == user }
                                    ?: throw IllegalArgumentException("needs one of ${MockPicks.USERS.joinToString { it.name }}, got '$user'")
                            }
                            SUGGESTIONS -> suggestions = read(name, value()) {
                                it.toBooleanStrictOrNull() ?: throw IllegalArgumentException("takes true or false, got '$it'")
                            }
                            else -> throw IllegalArgumentException("unknown option $name")
                        }
                    }
                }
            }
            require(!trackersConnected || trackersUrl != null) { "$TRACKERS_CONNECTED needs $TRACKERS" }
            require(relay == null || trackersUrl != null) { "$RELAY needs $TRACKERS" }
            val trackers = trackersUrl?.let { fakeUrl ->
                val fake = read(TRACKERS, fakeUrl) { DevTrackers.parse(it, trackersConnected) }
                relay?.let { read(RELAY, it, fake::withRelay) } ?: fake
            }
            val session = given.filter { it != DEV && it !in PRODUCT }
            require(dev || session.isEmpty()) { "${session.joinToString()} need $DEV" }
            val product = given.filter { it in PRODUCT }
            require(!dev || product.isEmpty()) { "${product.joinToString()} test the product's start, which $DEV replaces: not with $DEV" }
            require(url == null || open.isEmpty()) { "$URL and $OPEN are two different starts: pass one" }
            require(DevOpen.Restored !in open || open.size == 1) {
                "$OPEN restored starts on the stacks the profile's last session left: no other screen goes with it"
            }
            require(kids == null || (profile?.name ?: DEV_PROFILE_NAME) != DEV_PARENT_NAME) {
                "$KIDS: the kids profile can't be named $DEV_PARENT_NAME, the parent's name"
            }
            return DevLaunch(
                dev, url, addons, addonsOnly, watchActivity, profile, kids, language, library, open, progress, watched, settings,
                trackers, discord, crashReports, data, slowStartMillis, signInServer, browser, picks, suggestions ?: picks?.let { true },
                blurUnwatched,
            )
        }

        /**
         * Whether the argument [name] is an option [parse] reads: `--url`, or any argument starting with `--dev`, known
         * or not. A platform's own parser hands these to [parse], with their values ([takesValue]).
         */
        fun isOption(name: String): Boolean = name == URL || name.startsWith(DEV)

        /** Whether the option [name] ([isOption]) takes a value, the argument after it: every option but the switches. */
        fun takesValue(name: String): Boolean = name !in SWITCHES

        /** [value] of the option [name] as [parse] reads it, its refusal's message naming the option. */
        private fun <T> read(name: String, value: String, parse: (String) -> T): T = try {
            parse(value)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("$name ${e.message}", e)
        }

        private fun interfaceLanguage(tag: String): String {
            require(tag in InterfaceLocale.available) {
                "needs an interface language this build has strings for (${InterfaceLocale.available.joinToString()}), got '$tag'"
            }
            return tag
        }

        private fun discordApplication(id: String): String {
            require(id.isNotBlank()) { "needs a Discord application id" }
            return id
        }

        private fun sentryDsn(dsn: String): String {
            require(dsn.startsWith("http://") || dsn.startsWith("https://")) { "needs a Sentry DSN (http://<key>@<host>/<project>), got '$dsn'" }
            return dsn
        }

        private fun dataName(name: String): String {
            require(name.isNotEmpty() && name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }) {
                "needs a name of letters, digits, - and _, got '$name'"
            }
            return name
        }

        private fun seconds(spec: String): Long {
            val seconds = spec.toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() }
                ?: throw IllegalArgumentException("needs a positive number of seconds, got '$spec'")
            return (seconds * 1000).toLong()
        }

        private const val DEV = "--dev"
        private const val URL = "--url"
        private const val ADDON = "--dev-addon"
        private const val ADDONS_ONLY = "--dev-addons-only"
        private const val WATCH_ACTIVITY = "--dev-watch-activity"
        private const val PROFILE = "--dev-profile"
        private const val KIDS = "--dev-kids"
        private const val LANGUAGE = "--dev-language"
        private const val LIBRARY = "--dev-library"
        private const val OPEN = "--dev-open"
        private const val PROGRESS = "--dev-progress"
        private const val WATCHED = "--dev-watched"
        private const val SETTING = "--dev-setting"
        private const val TRACKERS = "--dev-trackers"
        private const val TRACKERS_CONNECTED = "--dev-trackers-connected"
        private const val RELAY = "--dev-relay"
        private const val DISCORD = "--dev-discord"
        private const val CRASH_REPORTS = "--dev-crash-reports"
        private const val DATA = "--dev-data"
        private const val SLOW_START = "--dev-slow-start"
        private const val SIGN_IN_SERVER = "--dev-sign-in-server"
        private const val BROWSER = "--dev-browser"
        private const val PICKS = "--dev-picks"
        private const val SUGGESTIONS = "--dev-suggestions"
        private const val BLUR_UNWATCHED = "--dev-blur-unwatched"

        /** The options that take no value. */
        private val SWITCHES = setOf(DEV, ADDONS_ONLY, WATCH_ACTIVITY, TRACKERS_CONNECTED, BLUR_UNWATCHED)

        /** The options that test the product's start, not a development session's. */
        private val PRODUCT = setOf(SLOW_START, SIGN_IN_SERVER, BROWSER)
    }
}
