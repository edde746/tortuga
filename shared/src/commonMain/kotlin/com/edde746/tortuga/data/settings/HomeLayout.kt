package com.edde746.tortuga.data.settings

import kotlinx.serialization.Serializable

/**
 * How a profile arranged Home's rows ([ProfileSetting.HomeRows]): [order] lists row keys in the profile's order,
 * [hidden] the rows left off Home. A row's key names where it comes from, the same on every launch and device (an
 * addon catalog's: the addon's transport URL with the catalog's type, id and extra; a tracker list's: the service and
 * the list's id; Continue Watching's: [CONTINUE_WATCHING], which can only be hidden: it stays first). The empty
 * layout is Home's default: every row, in the default order.
 *
 * The rules are pure: [arrange] puts the rows Home has now in the profile's order, [reordered], [withHidden] and
 * [pruned] make the layout to store after a change.
 */
@Serializable
data class HomeLayout(val order: List<String> = emptyList(), val hidden: List<String> = emptyList()) {
    fun isHidden(key: String): Boolean = key in hidden

    /**
     * [rows] (unique keys, in Home's default order) in the profile's order, hidden rows included. Rows [order] lists
     * keep its order; a row it doesn't list (a newly installed addon's, a newly connected tracker's) takes its default
     * place among them: at the end when no listed row follows it by default, at the start when none precedes it, and
     * otherwise right after the row before it.
     */
    fun arrange(rows: List<String>): List<String> {
        val present = rows.toHashSet()
        val result = order.filter { it in present }.distinct().toMutableList()
        val placed = result.toHashSet()
        val lastListed = rows.indexOfLast { it in placed }
        rows.forEachIndexed { index, key ->
            if (key in placed) return@forEachIndexed
            val at = if (index > lastListed) {
                result.size
            } else {
                rows.subList(0, index).lastOrNull { it in placed }?.let { result.indexOf(it) + 1 } ?: 0
            }
            result.add(at, key)
            placed += key
        }
        return result
    }

    /**
     * The layout with [arranged] (every row Home has now, in the profile's new order) as its order. A key of the
     * current order that isn't among them stays when [kept] says its row may come back (a tracker list this device
     * doesn't read), right after the key it followed; the others are dropped.
     */
    fun reordered(arranged: List<String>, kept: (String) -> Boolean): HomeLayout {
        val result = arranged.distinct().toMutableList()
        val placed = result.toHashSet()
        var previous: String? = null
        for (key in order.distinct()) {
            if (key !in placed && kept(key)) {
                result.add(previous?.let { result.indexOf(it) + 1 } ?: 0, key)
                placed += key
            }
            if (key in placed) previous = key
        }
        return copy(order = result)
    }

    /** The layout with the row [key] hidden or shown. */
    fun withHidden(key: String, hidden: Boolean): HomeLayout =
        copy(hidden = if (hidden) (this.hidden + key).distinct() else this.hidden - key)

    /**
     * The layout without the keys of rows that are gone (an addon removed, a catalog its addon no longer has): those
     * not in [present] that [kept] doesn't keep. Continue Watching's is always kept.
     */
    fun pruned(present: Set<String>, kept: (String) -> Boolean): HomeLayout {
        fun stays(key: String) = key == CONTINUE_WATCHING || key in present || kept(key)
        return HomeLayout(order.filter(::stays).distinct(), hidden.filter(::stays).distinct())
    }

    companion object {
        /** Continue Watching's key: hidden or not, never in [order]. */
        const val CONTINUE_WATCHING: String = "continueWatching"
    }
}
