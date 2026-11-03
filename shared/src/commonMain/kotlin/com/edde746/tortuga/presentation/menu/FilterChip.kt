package com.edde746.tortuga.presentation.menu

import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate

/**
 * A filter menu's closed chip, one value for every UI (Discover's type, catalog and filters, Library's type and sort,
 * Addons' type): the chip shows [text] and opens the filter's options, which a TV lists in a panel titled [name].
 *
 * @param name what the menu picks: "Genre", "Type", "Sort by".
 * @param text the chip's text, the same on every screen size: a filter with an All option shows its name while All is
 *   picked and the selection once it narrows what is shown ("Genre", then "Drama"); a choice that always has a value
 *   shows the value ("Movies", "Popular", "Recently watched"). Never "All" alone, which beside the other chips doesn't
 *   say what it is all of, and never the name with the selection, which a phone's row has no room for.
 * @param spoken what screen readers hear, who don't see the row around the chip: the name with the selection
 *   ("Genre: All", "Sort by: Name").
 * @param narrowed the chip narrows what is shown (an option other than All picked): it shows selected.
 */
data class FilterChip(val name: String, val text: String, val spoken: String, val narrowed: Boolean) {
    companion object {
        /** A filter with an All option, [selection] picked; [narrowed] while that isn't All. */
        fun narrowing(name: String, selection: String, narrowed: Boolean): FilterChip =
            FilterChip(name, if (narrowed) selection else name, spoken(name, selection), narrowed)

        /** A choice that always has a value, [selection]: a type, a catalog, an extra the addon requires. */
        fun choice(name: String, selection: String): FilterChip = FilterChip(name, selection, spoken(name, selection), narrowed = false)

        /** "Genre: Drama", each part isolated: an addon names its filters and their options. */
        private fun spoken(name: String, selection: String): String = T.browse.discover.selection(isolate(name), isolate(selection))
    }
}
