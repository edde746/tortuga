package com.edde746.tortuga.presentation.navigation

import com.edde746.tortuga.data.settings.DeviceSetting
import com.edde746.tortuga.data.settings.SAVED_NAVIGATION_KEY_PREFIX
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable

/**
 * A profile's back stacks as this device keeps them for the profile's next session
 * ([SavedNavigation]): the [shown] destination and each destination's stack, bottom
 * to top, up to the first screen that is never brought back. The player, a sheet and Watch together (its room ends
 * with the session) stay out with whatever lies over them, so the next session opens on the screen under them. Detail
 * comes back on the season it showed, without what it was opened to do (play a video, open its streams): its title
 * loads again as it does when it opens, and shows its error when it no longer resolves.
 *
 * Plain names, so a build reads what another build saved: a [version] other than [VERSION] is dropped whole, and a
 * destination or a route this build doesn't know is dropped with whatever lies over it ([restore]).
 */
@Serializable
data class SavedStacks(
    @SerialName("version") val version: Int,
    @SerialName("shown") val shown: String,
    @SerialName("stacks") val stacks: Map<String, List<SavedRoute>>,
) {
    /**
     * The stacks to start on; null for none (another [version]). The shown destination is [shown], or Home when this
     * build doesn't know it; each destination's stack has the destination's route at its bottom and ends before the
     * first route this build doesn't know or the app doesn't [offer] (a screen it no longer has), or one already on
     * a stack (two routes saved apart can restore as one, without what they were opened to do): a screen is on one
     * stack at a time, once, the shown destination's first.
     */
    fun restore(offer: (Route) -> Boolean): NavigationStacks? {
        if (version != VERSION) return null
        val shownDestination = destination(shown) ?: TopLevelDestination.BOARD
        val placed = HashSet<Route>()
        val restored = LinkedHashMap<TopLevelDestination, List<Route>>()
        for ((name, saved) in stacks.entries.sortedByDescending { destination(it.key) == shownDestination }) {
            val destination = destination(name) ?: continue
            val stack = mutableListOf(destination.route)
            for ((index, entry) in saved.withIndex()) {
                val route = entry.route() ?: break
                if (index == 0 && route == destination.route) continue
                if (TopLevelDestination.of(route) != null || route in placed || route in stack || !offer(route)) break
                stack += route
            }
            placed += stack
            restored[destination] = stack
        }
        restored.getOrPut(shownDestination) { listOf(shownDestination.route) }
        return NavigationStacks(shownDestination, restored)
    }

    companion object {
        /** The format this build writes and reads. */
        const val VERSION = 1

        /** [stacks] as the profile's next session restores them. */
        fun of(stacks: NavigationStacks): SavedStacks = SavedStacks(
            VERSION,
            stacks.shown.name,
            stacks.stacks.entries.associate { (destination, stack) ->
                destination.name to stack.asSequence().map(SavedRoute::of).takeWhile { it != null }.filterNotNull().toList()
            },
        )

        private fun destination(name: String): TopLevelDestination? = TopLevelDestination.entries.firstOrNull { it.name == name }
    }
}

/**
 * A route as [SavedStacks] keeps it: its [kind] and, for Detail, the title's [type] and [id] and the [video] whose
 * season it showed.
 */
@Serializable
data class SavedRoute(
    @SerialName("route") val kind: String,
    @SerialName("type") val type: String? = null,
    @SerialName("id") val id: String? = null,
    @SerialName("video") val video: String? = null,
) {
    /** The route; null for a kind this build doesn't know. */
    internal fun route(): Route? = when (kind) {
        BOARD -> Route.Board
        DISCOVER -> Route.Discover
        SEARCH -> Route.Search
        LIBRARY -> Route.Library
        SETTINGS -> Route.Settings
        DETAIL -> if (type != null && id != null) Route.Detail(type, id, shownVideoId = video) else null
        KEEP_SYNCED -> Route.KeepSynced
        GUIDE -> Route.Guide
        CALENDAR -> Route.Calendar
        else -> null
    }

    companion object {
        private const val BOARD = "board"
        private const val DISCOVER = "discover"
        private const val SEARCH = "search"
        private const val LIBRARY = "library"
        private const val SETTINGS = "settings"
        private const val DETAIL = "detail"
        private const val KEEP_SYNCED = "keepSynced"
        private const val GUIDE = "guide"
        private const val CALENDAR = "calendar"

        /**
         * [route] as kept; null for a screen never brought back: the player, a sheet, Watch together, a collection's
         * folder.
         */
        internal fun of(route: Route): SavedRoute? = when (route) {
            Route.Board -> SavedRoute(BOARD)
            Route.Discover -> SavedRoute(DISCOVER)
            Route.Search -> SavedRoute(SEARCH)
            Route.Library -> SavedRoute(LIBRARY)
            Route.Settings -> SavedRoute(SETTINGS)
            is Route.Detail -> SavedRoute(DETAIL, route.type, route.id, route.videoId ?: route.shownVideoId)
            Route.KeepSynced -> SavedRoute(KEEP_SYNCED)
            Route.Guide -> SavedRoute(GUIDE)
            Route.Calendar -> SavedRoute(CALENDAR)
            is Route.Streams, Route.Player, Route.WatchTogether, is Route.CollectionFolder -> null
        }
    }
}

/**
 * One profile's back stacks on this device ([SavedStacks]), as its last session left them: the profile's next session
 * starts on them, once its PIN is in. A device setting, so it never syncs; keyed by profile; null until the profile's
 * first session. Forgotten with the profile and when the account's data leaves the device
 * ([com.edde746.tortuga.data.settings.forgetSavedNavigation], [com.edde746.tortuga.data.settings.forgetAllSavedNavigation]).
 */
class SavedNavigation(profileId: String) :
    DeviceSetting<SavedStacks?>(SAVED_NAVIGATION_KEY_PREFIX + profileId, null, SavedStacks.serializer().nullable)
