package com.edde746.tortuga.ui.discover

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.discover.DiscoverBody
import com.edde746.tortuga.presentation.discover.DiscoverOption
import com.edde746.tortuga.presentation.discover.DiscoverPicker
import com.edde746.tortuga.presentation.discover.DiscoverUiState
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.test.Test

/**
 * Discover's filter chips on a phone, which every UI words alike: the type (more types than fit as chips) and the
 * catalog show their selection; the genre shows "Genre" while All is picked and the genre once one is, in the primary
 * fill. A screen reader hears each chip's name with its selection ("Genre: All").
 */
@OptIn(ExperimentalTestApi::class)
class DiscoverScreenTest {
    @Test
    fun eachFilterChipShowsOneTextAndIsHeardWithItsName() = runComposeUiTest {
        var genre by mutableStateOf(0)
        setContent {
            TortugaTheme {
                Box(Modifier.size(411.dp, 800.dp)) {
                    AdaptiveLayout(FormFactor.HANDHELD) {
                        DiscoverScreen(
                            state(genre),
                            onSelectType = {},
                            onSelectCatalog = {},
                            onSelectFilter = { _, _ -> },
                            onLoadMore = {},
                            onRetry = {},
                            onOpenItem = {},
                            onItemMenu = { _, _ -> },
                            onOpenDownloads = {},
                        )
                    }
                }
            }
        }
        chip("Movies").assert(hasContentDescription("\u2068Type\u2069: \u2068Movies\u2069")).assert(notSelected)
        chip("Popular").assert(hasContentDescription("\u2068Catalog\u2069: \u2068Popular\u2069")).assert(notSelected)
        chip("Genre").assert(hasContentDescription("\u2068Genre\u2069: \u2068All\u2069")).assert(notSelected)

        genre = 2
        waitForIdle()
        chip("Drama").assert(hasContentDescription("\u2068Genre\u2069: \u2068Drama\u2069")).assertIsSelected()
    }

    /** Discover on Popular movies, its genre [genre] of [genres] (0: All). */
    private fun state(genre: Int) = DiscoverUiState(
        title = "Popular · Movies",
        types = DiscoverPicker("Type", "Movies", isRequired = true, multiSelect = false, options = types.mapIndexed { index, type -> DiscoverOption(type, null, index == 0) }),
        catalogs = DiscoverPicker("Catalog", "Popular", isRequired = true, multiSelect = false, options = listOf(DiscoverOption("Popular", "Cinemeta", true), DiscoverOption("New", "Cinemeta", false))),
        filters = listOf(
            DiscoverPicker("Genre", genres[genre], isRequired = false, multiSelect = false, options = genres.mapIndexed { index, name -> DiscoverOption(name, null, index == genre) }),
        ),
        body = DiscoverBody.Empty("Nothing in this catalog."),
    )

    /** The dropdown chip showing [text]. */
    private fun ComposeUiTest.chip(text: String) = onNode(hasClickAction() and hasText(text) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.DropdownList))

    private val notSelected = SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected)

    private val types = listOf("Movies", "Series", "Channels", "TV channels", "Anime")
    private val genres = listOf("All", "Action", "Drama")
}
