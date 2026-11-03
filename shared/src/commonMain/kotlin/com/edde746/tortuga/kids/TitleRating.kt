package com.edde746.tortuga.kids

/** What the age database ([AgeRatings]) says of one title, as [KidsPolicy] reads it. */
internal sealed interface TitleRating {
    /** The title's suggested [age] on the scale 0, 6, 12, 16, 18 ([ageStepOf]). */
    data class Rated(val age: Int) : TitleRating

    /** The database has none of the title's ids. */
    data object Unrated : TitleRating

    /** The title has no id the database is keyed by: an addon's own namespace (`yt_id:…`), an unmapped anime entry. */
    data object NoId : TitleRating

    /** The database can't be had (not downloaded yet, offline with no copy, a download that failed) or can't be read. */
    data object Failed : TitleRating
}
