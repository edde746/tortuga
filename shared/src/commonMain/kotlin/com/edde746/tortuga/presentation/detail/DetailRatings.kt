package com.edde746.tortuga.presentation.detail

import com.edde746.tortuga.presentation.settings.serviceName
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.trackers.UserScore
import com.edde746.tortuga.trackers.UserScoreState
import com.edde746.tortuga.trackers.ratings.RatingSource
import com.edde746.tortuga.trackers.ratings.TitleRating
import kotlin.math.roundToLong

/**
 * The mark drawn before a score: the rating sites' and trackers' marks, and Metacritic's "M" roundel and
 * Letterboxd's three dots from Wikimedia Commons. Rotten Tomatoes has two of each: fresh or rotten for the critics,
 * upright or spilled for the audience, by the 60 % line the Tomatometer draws.
 */
enum class RatingLogo { IMDB, TMDB, RT_FRESH, RT_ROTTEN, RT_UPRIGHT, RT_SPILLED, METACRITIC, LETTERBOXD, TRAKT, SIMKL, MYANIMELIST, ANILIST }

/** The ratings row's text, every score written the way its own site writes it. */
internal object DetailRatings {
    /** Rotten Tomatoes' line between fresh and rotten (and upright and spilled). */
    private const val FRESH = 60.0

    /** The lines whose score is the user's on the tracker, or will be: read, being saved, saved or queued. */
    private val GIVEN = setOf(UserScoreState.READ, UserScoreState.SAVING, UserScoreState.SAVED, UserScoreState.QUEUED)

    fun of(rating: TitleRating, imdbLink: String?): DetailRating {
        val name = name(rating.source)
        val text = text(rating)
        val percent = when (rating.source) {
            RatingSource.ROTTEN_TOMATOES_CRITICS, RatingSource.ROTTEN_TOMATOES_AUDIENCE, RatingSource.TMDB,
            RatingSource.TRAKT, RatingSource.ANILIST,
            -> true
            else -> false
        }
        return DetailRating(
            source = rating.source,
            logo = logo(rating),
            label = if (percent) {
                T.detail.ratings.percentLabel(source = name, value = text.removeSuffix("%"))
            } else {
                T.detail.ratings.label(source = name, value = text)
            },
            text = text,
            linkId = imdbLink.takeIf { rating.source == RatingSource.IMDB },
        )
    }

    /** The user's own score of the title ([DetailUserRating]) from each tracker's line. */
    fun user(scores: List<UserScore>): DetailUserRating {
        val given = scores.filter { it.state in GIVEN }.mapNotNull { it.score }.distinct().sorted()
        val text = when (given.size) {
            0 -> null
            1 -> given.single().toString()
            else -> T.detail.userRating.range(given.first().toString(), given.last().toString())
        }
        return DetailUserRating(
            text = text,
            label = text?.let { T.detail.userRating.label(it) } ?: T.detail.userRating.rateLabel,
            score = given.singleOrNull(),
            trackers = scores.sortedBy { it.service.ordinal }.map(::trackerLine),
        )
    }

    private fun trackerLine(line: UserScore): DetailTrackerScore {
        val text = T.detail.userRating
        val status = when (line.state) {
            UserScoreState.READING -> text.checking
            UserScoreState.READ -> line.score?.toString() ?: text.notRated
            UserScoreState.UNREADABLE -> text.unreadable
            UserScoreState.SAVING -> text.saving
            UserScoreState.SAVED -> if (line.score == null) text.removed else text.saved
            UserScoreState.QUEUED -> text.queued
            UserScoreState.FAILED -> text.failed
        }
        return DetailTrackerScore(
            service = line.service,
            name = serviceName(line.service),
            status = status,
            busy = line.state == UserScoreState.READING || line.state == UserScoreState.SAVING,
            attention = line.state == UserScoreState.UNREADABLE || line.state == UserScoreState.QUEUED || line.state == UserScoreState.FAILED,
        )
    }

    /**
     * IMDb and Simkl with one decimal (`8.2`), MyAnimeList with two as it shows them (`8.42`), Letterboxd's stars with
     * one (`4.6`), the Metascore whole (`82`), and the scores out of 100 as percentages (`88%`): Rotten Tomatoes,
     * TMDB, Trakt and AniList.
     */
    fun text(rating: TitleRating): String = when (rating.source) {
        RatingSource.IMDB, RatingSource.SIMKL, RatingSource.LETTERBOXD -> fixed(rating.value, 1)
        RatingSource.MYANIMELIST -> fixed(rating.value, 2)
        RatingSource.METACRITIC -> fixed(rating.value, 0)
        RatingSource.ROTTEN_TOMATOES_CRITICS, RatingSource.ROTTEN_TOMATOES_AUDIENCE, RatingSource.TMDB,
        RatingSource.TRAKT, RatingSource.ANILIST,
        -> fixed(rating.value, 0) + "%"
    }

    /** Every source's mark; Rotten Tomatoes' by the score. */
    fun logo(rating: TitleRating): RatingLogo = when (rating.source) {
        RatingSource.ROTTEN_TOMATOES_CRITICS -> if (rating.value >= FRESH) RatingLogo.RT_FRESH else RatingLogo.RT_ROTTEN
        RatingSource.ROTTEN_TOMATOES_AUDIENCE -> if (rating.value >= FRESH) RatingLogo.RT_UPRIGHT else RatingLogo.RT_SPILLED
        RatingSource.IMDB -> RatingLogo.IMDB
        RatingSource.TMDB -> RatingLogo.TMDB
        RatingSource.METACRITIC -> RatingLogo.METACRITIC
        RatingSource.LETTERBOXD -> RatingLogo.LETTERBOXD
        RatingSource.TRAKT -> RatingLogo.TRAKT
        RatingSource.SIMKL -> RatingLogo.SIMKL
        RatingSource.MYANIMELIST -> RatingLogo.MYANIMELIST
        RatingSource.ANILIST -> RatingLogo.ANILIST
    }

    fun name(source: RatingSource): String = with(T.detail.ratings.source) {
        when (source) {
            RatingSource.ROTTEN_TOMATOES_CRITICS -> rottenTomatoesCritics
            RatingSource.ROTTEN_TOMATOES_AUDIENCE -> rottenTomatoesAudience
            RatingSource.IMDB -> imdb
            RatingSource.TMDB -> tmdb
            RatingSource.METACRITIC -> metacritic
            RatingSource.LETTERBOXD -> letterboxd
            RatingSource.TRAKT -> trakt
            RatingSource.SIMKL -> simkl
            RatingSource.MYANIMELIST -> myAnimeList
            RatingSource.ANILIST -> aniList
        }
    }

    /** [value] with [decimals] decimals, rounded half up, with a full stop as the addons write it. */
    private fun fixed(value: Double, decimals: Int): String {
        var factor = 1L
        repeat(decimals) { factor *= 10 }
        val scaled = (value * factor).roundToLong()
        if (decimals == 0) return scaled.toString()
        val fraction = (scaled % factor).toString().padStart(decimals, '0')
        return "${scaled / factor}.$fraction"
    }
}
