package com.edde746.tortuga.presentation.detail

import com.edde746.tortuga.model.DeepLink
import com.edde746.tortuga.model.Link
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.net.WebUrl
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import com.edde746.tortuga.trackers.ratings.RatingSource
import com.edde746.tortuga.trackers.ratings.TitleRating
import com.edde746.tortuga.trackers.ratings.TitleRatings
import kotlinx.coroutines.flow.map

internal fun DetailStore.buildHeader(preview: MetaItemPreview, linked: Boolean, sourceFacts: List<DetailFact>): DetailHeader {
    var imdbLink: String? = null
    val addonImdb = TitleRating.of(RatingSource.IMDB, preview.imdbRating, votes = null, origin = null)
    val groups = LinkedHashMap<String, MutableList<DetailLink>>()
    val linksByKind = HashMap<LinkKind, MutableList<DetailLink>>()
    for ((index, link) in preview.links.withIndex()) {
        val name = link.name.trim()
        if (name.isEmpty() || !isRoutable(link.url)) continue
        when (link.category) {
            Link.IMDB -> if (imdbLink == null) imdbLink = LINK_PREFIX + index
            Link.SHARE -> {}
            else -> {
                val detailLink = DetailLink(LINK_PREFIX + index, name)
                groups.getOrPut(groupTitle(link.category)) { mutableListOf() }.add(detailLink)
                linkKind(link.category)?.let { linksByKind.getOrPut(it) { mutableListOf() }.add(detailLink) }
            }
        }
    }
    preview.website?.takeIf(WebUrl::isWeb)?.let { website ->
        groups.getOrPut(T.detail.links.website) { mutableListOf() }.add(DetailLink(WEBSITE_LINK, websiteLabel(website)))
    }
    val runtime = DetailText.runtime(preview.runtime)
    // The info line's content (the addon's release info and runtime text, the genres) isolated, the year not.
    val releaseInfo = preview.releaseInfo?.trim()?.takeIf { it.isNotEmpty() }?.let(::isolate) ?: preview.year?.toString()
    fun names(kind: LinkKind, limit: Int = Int.MAX_VALUE) = linksByKind[kind]?.take(limit)?.joinToString(T.detail.listSeparator) { isolate(it.label) }
    val facts = T.detail.facts
    val links = T.detail.links
    return DetailHeader(
        id = preview.id,
        type = preview.type,
        imdbId = preview.imdbId,
        title = preview.name,
        poster = preview.poster,
        background = preview.background,
        logo = preview.logo,
        posterShape = preview.posterShape,
        infoLine = DetailText.join(listOfNotNull(releaseInfo, DetailText.runtimePart(preview.runtime), names(LinkKind.GENRES, INFO_LINE_GENRES))),
        ratings = TitleRatings.merge(listOfNotNull(addonImdb) + trackerScores).map { DetailRatings.of(it, imdbLink.takeIf { linked }) },
        overview = preview.description?.trim()?.takeIf { it.isNotEmpty() },
        linkGroups = groups.map { (title, links) -> DetailLinkGroup(title, links) },
        factsTitle = facts.title,
        facts = listOfNotNull(
            preview.released?.let { DetailFact(facts.released, DetailText.date(it), linked = false) },
            runtime?.let { DetailFact(facts.runtime, it, linked = false) },
            preview.country?.let { DetailFact(facts.country, it, linked = false) },
            preview.language?.let { DetailFact(facts.language, it, linked = false) },
            preview.awards?.let { DetailFact(facts.awards, it, linked = false) },
            names(LinkKind.CAST)?.let { DetailFact(links.cast, it, linked = true) },
            names(LinkKind.DIRECTORS)?.let { DetailFact(links.directors, it, linked = true) },
            names(LinkKind.WRITERS)?.let { DetailFact(links.writers, it, linked = true) },
            names(LinkKind.GENRES)?.let { DetailFact(links.genres, it, linked = true) },
        ) + sourceFacts,
        castLine = names(LinkKind.CAST, CAST_LINE_NAMES)?.let { T.detail.withCast(it) },
        castAndCrew = listOf(LinkKind.CAST, LinkKind.DIRECTORS, LinkKind.WRITERS).flatMap { linksByKind[it].orEmpty() }.distinctBy { it.label },
    )
}

internal const val LINK_PREFIX = "link:"
internal const val WEBSITE_LINK = "website"

/** The website as its link reads: `hbo.com/the-last-of-us`, without the scheme, `www.` or a closing slash. */
private fun websiteLabel(url: String) = url.substringAfter("://").removePrefix("www.").trimEnd('/')

private fun isRoutable(url: String) = DeepLink.parse(url) != null || WebUrl.isWeb(url)

/** Genres shown in the header's info line. */
private const val INFO_LINE_GENRES = 2

/** Cast names in the header's "With …" line ([DetailHeader.castLine]). */
private const val CAST_LINE_NAMES = 3

private fun linkKind(category: String): LinkKind? = when (category.lowercase()) {
    "genres", "genre" -> LinkKind.GENRES
    "cast", "actor", "actors" -> LinkKind.CAST
    "directors", "director" -> LinkKind.DIRECTORS
    "writers", "writer" -> LinkKind.WRITERS
    else -> null
}

private fun groupTitle(category: String): String = when (linkKind(category)) {
    LinkKind.GENRES -> T.detail.links.genres
    LinkKind.CAST -> T.detail.links.cast
    LinkKind.DIRECTORS -> T.detail.links.directors
    LinkKind.WRITERS -> T.detail.links.writers
    null -> category
}

/** The link categories Detail knows, for its facts and group titles. */
private enum class LinkKind { GENRES, CAST, DIRECTORS, WRITERS }
