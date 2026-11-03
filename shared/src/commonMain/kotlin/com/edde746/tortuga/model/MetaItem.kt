@file:OptIn(ExperimentalObjCName::class)

package com.edde746.tortuga.model

import com.edde746.tortuga.net.UriComponent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
import kotlin.time.Instant

/**
 * A catalog item (stremio-core `MetaItemPreview`, decoded through its `MetaItemPreviewLegacy`): when `links` is
 * absent, links are built from the legacy `imdbRating`, `genres`, `cast` and `director`; when `trailerStreams` is
 * absent, they are built from the legacy `trailers` of type `Trailer`.
 *
 * Lenient where stremio-core would drop the whole item: an invalid image URL, `released` date or `posterShape` is
 * treated as absent, and a malformed link or trailer is skipped.
 */
@Serializable(with = MetaItemPreviewSerializer::class)
data class MetaItemPreview(
    val id: String,
    val type: String,
    /** Empty when the addon sends none. */
    val name: String = "",
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    /** `descriptionText` in Swift: `description` would clash with `NSObject.description`. */
    @ObjCName("descriptionText")
    val description: String? = null,
    /** Free text such as `2010` or `2011-2019`; numbers are rendered as text. */
    val releaseInfo: String? = null,
    val runtime: String? = null,
    val released: Instant? = null,
    val posterShape: PosterShape = PosterShape.POSTER,
    val links: List<Link> = emptyList(),
    val trailerStreams: List<Stream> = emptyList(),
    val behaviorHints: MetaItemBehaviorHints = MetaItemBehaviorHints(),
    /**
     * `imdb_id`, which Cinemeta and anime addons add (it isn't in stremio-core's type): the IMDb title of an item whose
     * own id is another kind (`kitsu:…`). Kept only when it is an IMDb title id; the trackers read it.
     */
    val imdbId: String? = null,
    /**
     * `country`, `language` and `awards`, free text that Cinemeta and other addons add (they aren't in stremio-core's
     * type): `United States, United Kingdom`, `Won 10 Primetime Emmys`. Detail lists them.
     */
    val country: String? = null,
    val language: String? = null,
    val awards: String? = null,
    /** `website`, the title's official site (the SDK has it, stremio-core doesn't): an absolute URL, else absent. */
    val website: String? = null,
    /**
     * The age ratings the addon sent, in this order, each once: `certification`, `app_extras.certification`,
     * `app_extras.certificationLocal`, `ageRating` (none is in stremio-core's type; text as sent, `TV-MA`, `16`). A kids
     * profile tightens with them, never loosens.
     */
    val certifications: List<String> = emptyList(),
) {
    /**
     * The title is a live channel (stremio-core `is_live`): `behaviorHints.isLive`, or the type `tv`. It plays its own
     * id, whatever videos it lists (a schedule), and has no next episode.
     */
    val isLive: Boolean get() = behaviorHints.isLive || type == "tv"

    /**
     * The release year: the first four-digit year in [releaseInfo] (`2011–2019` is 2011), else [released]'s (UTC).
     * Derived, never written: the one year a download's folder and a watch-together descriptor name.
     */
    val year: Int? = releaseInfo?.let { YEAR.find(it) }?.value?.toInt() ?: released?.toString()?.take(4)?.toIntOrNull()

    /**
     * The addon's IMDb rating: the leading number of the name of the first [Link.IMDB] link ([imdbRatingOf]).
     * Derived, never written.
     */
    val imdbRating: Double? = links.firstOrNull { it.category == Link.IMDB }?.let { imdbRatingOf(it.name) }

    /** [id] parsed; `null` for an addon's own namespace. Derived, never written. */
    internal val stremioId: StremioId? = StremioId.parse(id)
}

private val YEAR = Regex("""\d{4}""")

private val LEADING_NUMBER = Regex("""^\d{1,2}(\.\d+)?""")

/** An IMDb link's rating: the leading number of its name (`9.3`; Cinemeta writes the number alone), else null. */
private fun imdbRatingOf(name: String): Double? = LEADING_NUMBER.find(name.trim())?.value?.toDoubleOrNull()

/** A full meta item: the preview fields plus [videos]. */
@Serializable(with = MetaItemSerializer::class)
data class MetaItem(
    val preview: MetaItemPreview,
    /**
     * Unique by id (first wins), sorted as stremio-core does: episodes by season (specials, season 0, last) then
     * episode, before videos without series info; those by release date (oldest first when the item has episodes,
     * newest first otherwise), undated last.
     */
    val videos: List<Video> = emptyList(),
    /**
     * The addon that sent this item ([com.edde746.tortuga.addons.AddonAggregator.meta]): the streams its videos carry
     * ([Video.streams]) are that addon's. Null for an item from elsewhere (the one saved with downloads, a
     * watch-together room's); it isn't written with the item.
     */
    val addon: Descriptor? = null,
) {
    val id: String get() = preview.id
    val type: String get() = preview.type
    val name: String get() = preview.name

    /** Seasons in [videos] order, specials (0) included. */
    val seasons: List<Int> get() = videos.mapNotNull { it.seriesInfo?.season }.distinct()

    fun videosInSeason(season: Int): List<Video> = videos.filter { it.seriesInfo?.season == season }

    /** Each video's position in [videos], by id (the first of an id listed twice): built once per item. */
    private val positions: Map<String, Int> by lazy {
        HashMap<String, Int>(videos.size * 2).also { map -> videos.forEachIndexed { index, video -> map.getOrPut(video.id) { index } } }
    }

    /** The number of episodes of each season in [videos] (specials, 0, included): counted once per item. */
    internal val episodesPerSeason: Map<Int, Int> by lazy {
        HashMap<Int, Int>().also { map -> videos.forEach { video -> video.seriesInfo?.let { map[it.season] = (map[it.season] ?: 0) + 1 } } }
    }

    /**
     * The ids of [videos] in the order of Stremio's watched marks (stremio-watched-bitfield): season, then episode,
     * then release date, each missing value first (so specials, season 0, come first here, unlike in [videos]); a
     * stable sort of [videos]. Sorted once per item.
     */
    internal val watchedOrder: List<String> by lazy {
        videos.sortedWith(
            compareBy<Video>(
                { it.seriesInfo?.season?.toLong() ?: Long.MIN_VALUE },
                { it.seriesInfo?.episode?.toLong() ?: Long.MIN_VALUE },
                { it.released?.toEpochMilliseconds() ?: Long.MIN_VALUE },
            ),
        ).map { it.id }
    }

    /** The release dates of [videos], of the first dated video of each id, oldest first: sorted once per item. */
    internal val releases: List<Instant> by lazy {
        val seen = HashSet<String>()
        videos.mapNotNull { video -> video.released?.takeIf { seen.add(video.id) } }.sorted()
    }

    /** The position of [videoId] in [videos] (the first of an id listed twice); -1 when it isn't one of them. */
    internal fun indexOfVideo(videoId: String): Int = positions[videoId] ?: -1

    /** The video [videoId] of [videos] (the first of an id listed twice); null when it isn't one of them. */
    internal fun video(videoId: String): Video? = positions[videoId]?.let(videos::get)

    /**
     * The streams this item carries for its video [videoId] (stremio-core `meta_streams`): the video's own `streams`,
     * else, for a YouTube addon's video (`yt_id:{channel}:{video}`), that video's YouTube stream. None for a video the
     * item doesn't list.
     */
    fun carriedStreams(videoId: String): List<Stream> {
        val video = video(videoId) ?: return emptyList()
        if (video.streams.isNotEmpty()) return video.streams
        val ytId = video.id.takeIf { it.startsWith(YOUTUBE_ID_PREFIX) }?.split(':')?.getOrNull(2)?.takeIf { it.isNotEmpty() }
        return listOfNotNull(ytId?.let { Stream(source = StreamSource.YouTube(it)) })
    }
}

/** stremio-core `YOUTUBE_ADDON_ID_PREFIX`: the YouTube addon's video ids, `yt_id:{channel}:{video}`. */
private const val YOUTUBE_ID_PREFIX = "yt_id:"

/** A video of a meta item: an episode of a series, one video of a channel, or a programme of a live channel. */
@Serializable(with = VideoSerializer::class)
data class Video(
    val id: String,
    /** `title`, or the legacy `name`; empty when neither is set. */
    val title: String = "",
    val released: Instant? = null,
    val overview: String? = null,
    val thumbnail: String? = null,
    /** `streams`, or the legacy single `stream`. */
    val streams: List<Stream> = emptyList(),
    /** Set when both `season` and `episode` are non-negative integers. */
    val seriesInfo: SeriesInfo? = null,
    /** `trailerStreams`, or `trailers` (the SDK's name for them on a video): streams, as the title's trailers are. */
    val trailerStreams: List<Stream> = emptyList(),
    /**
     * Where this video is on IMDb: anime addons' `imdb_id`, `imdbSeason` and `imdbEpisode` (the Kitsu addon's episode
     * 1 of a sequel entry is an IMDb season 2 episode). Set when all three are valid; the trackers read it.
     */
    val imdb: ImdbEpisode? = null,
    /** Set when the video is a scheduled programme of a live channel ([Programme]). */
    val programme: Programme? = null,
)

/**
 * A video's slot in a live channel's schedule (stremio-core `VideoEpgInfo`, the addon SDK's `docs/epg.md`): a video
 * with both `startTime` and `endTime`, the end strictly after the start, is a programme; any other video isn't one
 * and no guide shows it. The rest are the SDK's optional programme fields, read leniently: a malformed one is absent.
 * A programme never plays by its own id: its channel does.
 */
data class Programme(
    val start: Instant,
    val end: Instant,
    /** Free text, `45 min`. */
    val runtime: String? = null,
    /** Free text, the original air year: `2026`. */
    val releaseInfo: String? = null,
    val genres: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val links: List<Link> = emptyList(),
    val ratings: List<Rating> = emptyList(),
) {
    /** On air at [now]: from [start] (included) to [end] (excluded). */
    fun isOn(now: Instant): Boolean = start <= now && now < end

    /** A content rating, `PG` in the `TVPG` system. */
    data class Rating(val value: String, val system: String? = null, val icon: String? = null)
}

@Serializable
data class SeriesInfo(val season: Int, val episode: Int)

/** An episode on IMDb: [id] is the series' title id (`tt5626028`). */
@Serializable
data class ImdbEpisode(val id: String, val season: Int, val episode: Int)

/**
 * A detail link. [category] is free text, trimmed; Cinemeta uses `imdb`, `Genres`, `Cast`, `Directors`, `Writers`,
 * `share`. A category with a fixed meaning is read in any case (`IMDb`) and kept as its constant ([IMDB], [SHARE]);
 * the others keep the addon's case, which Detail shows as a group's title.
 */
@Serializable
data class Link(val name: String, val category: String, val url: String) {
    companion object {
        /** The category of the title's IMDb page, its name the rating (stremio-core's legacy `imdbRating`). */
        const val IMDB = "imdb"

        /** The category of the title's share link. */
        const val SHARE = "share"
    }
}

enum class PosterShape { POSTER, SQUARE, LANDSCAPE }

/** `behaviorHints` of a meta item. Keys this client doesn't know are kept in [other]. */
data class MetaItemBehaviorHints(
    val isLive: Boolean = false,
    val defaultVideoId: String? = null,
    val featuredVideoId: String? = null,
    val hasScheduledVideos: Boolean = false,
    val other: Map<String, JsonElement> = emptyMap(),
)

/** stremio-core `CINEMETA_URL`: the base of the genre links built for legacy items. */
private const val CINEMETA_MANIFEST = "https://v3-cinemeta.strem.io/manifest.json"

internal object MetaItemPreviewSerializer : JsonTreeSerializer<MetaItemPreview>("MetaItemPreview") {
    override fun read(element: JsonElement): MetaItemPreview = read(element.asObject("meta item"), DropCounter())

    fun read(o: JsonObject, drops: DropCounter): MetaItemPreview {
        val id = o.reqString("id")
        val type = o.reqString("type")
        return MetaItemPreview(
            id = id,
            type = type,
            name = o.optString("name").orEmpty(),
            poster = o.optUrlLenient("poster"),
            background = o.optUrlLenient("background"),
            logo = o.optUrlLenient("logo"),
            description = o.optString("description"),
            releaseInfo = o.optNumberAsString("releaseInfo"),
            runtime = o.optNumberAsString("runtime"),
            released = o.releasedLenient(allowMillis = true),
            posterShape = when ((o["posterShape"] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
                "square" -> PosterShape.SQUARE
                "landscape" -> PosterShape.LANDSCAPE
                else -> PosterShape.POSTER
            },
            links = when (val links = o["links"]) {
                null, JsonNull -> legacyLinks(o, id, type)
                else -> decodeEach(links, drops, ::readLink)
            },
            trailerStreams = when (val trailerStreams = o["trailerStreams"]) {
                null, JsonNull -> legacyTrailers(o["trailers"])
                else -> decodeEach(trailerStreams, drops) { StreamSerializer.read(it, drops) }
            },
            behaviorHints = o["behaviorHints"]?.takeUnless { it is JsonNull }?.let(::readHints) ?: MetaItemBehaviorHints(),
            imdbId = o.imdbTitleId("imdb_id"),
            country = o.optText("country"),
            language = o.optText("language"),
            awards = o.optText("awards"),
            website = o.optUrlLenient("website"),
            certifications = certificationsOf(o),
        )
    }

    /** A non-blank string at [key], trimmed; anything else (absent, a number, a list) as absent. */
    private fun JsonObject.optText(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

    /** [MetaItemPreview.certifications] from where addons put them; a number counts as its text. */
    private fun certificationsOf(o: JsonObject): List<String> {
        val extras = o["app_extras"] as? JsonObject
        return listOf(o["certification"], extras?.get("certification"), extras?.get("certificationLocal"), o["ageRating"])
            .mapNotNull { (it as? JsonPrimitive)?.takeUnless { p -> p is JsonNull }?.content?.trim()?.takeIf { text -> text.isNotEmpty() } }
            .distinct()
    }

    internal fun readLink(element: JsonElement): Link {
        val o = element.asObject("link")
        val category = o.reqString("category").trim()
        val known = listOf(Link.IMDB, Link.SHARE).firstOrNull { it.equals(category, ignoreCase = true) }
        return Link(name = o.reqString("name"), category = known ?: category, url = o.reqUrl("url"))
    }

    /**
     * `imdbRating` → an `imdb` link, `genres` → `Genres` links into Cinemeta's `top` catalog, `cast` and `director`
     * (the SDK's deprecated name lists, which stremio-core drops) → `Cast` and `Directors` links that search for the
     * name, as Cinemeta's own links do.
     */
    private fun legacyLinks(o: JsonObject, id: String, type: String): List<Link> {
        val imdb = runCatching { o.optNumberAsString("imdbRating") }.getOrNull()?.let {
            Link(name = it, category = Link.IMDB, url = "https://imdb.com/title/" + UriComponent.encode(id))
        }
        val genres = o.names("genres").map { genre ->
            Link(
                name = genre,
                category = "Genres",
                url = DeepLink.Discover(CINEMETA_MANIFEST, type, "top", listOf(ExtraValue(Extras.GENRE, genre))).toUrl(),
            )
        }
        fun people(key: String, category: String) = o.names(key).map { Link(name = it, category = category, url = DeepLink.Search(it).toUrl()) }
        return listOfNotNull(imdb) + genres + people("cast", "Cast") + people("director", "Directors")
    }

    /** The non-blank strings of the list at [key], trimmed; anything else in it, or at [key], is left out. */
    private fun JsonObject.names(key: String): List<String> = (this[key] as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()?.takeIf { name -> name.isNotEmpty() } }

    /** Legacy `trailers`: `{source, type}` entries of type `Trailer` (or YouTube streams) become YouTube streams. */
    private fun legacyTrailers(element: JsonElement?): List<Stream> = (element as? JsonArray).orEmpty().mapNotNull { item ->
        val t = item as? JsonObject ?: return@mapNotNull null
        val source = (t["source"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val kind = (t["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        when {
            source != null && kind != null -> source.takeIf { kind == "Trailer" }
            else -> (t["ytId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        }?.let { Stream(source = StreamSource.YouTube(it)) }
    }

    private fun readHints(element: JsonElement): MetaItemBehaviorHints {
        val o = element.asObject("behaviorHints")
        return MetaItemBehaviorHints(
            isLive = o.optBoolean("isLive"),
            defaultVideoId = o.optString("defaultVideoId"),
            featuredVideoId = o.optString("featuredVideoId"),
            hasScheduledVideos = o.optBoolean("hasScheduledVideos"),
            other = o.filterKeys { it !in KNOWN_HINTS },
        )
    }

    private val KNOWN_HINTS = setOf("isLive", "defaultVideoId", "featuredVideoId", "hasScheduledVideos")

    override fun write(value: MetaItemPreview): JsonElement = buildJsonObject { writeFields(value) }

    fun JsonObjectBuilder.writeFields(value: MetaItemPreview) {
        put("id", value.id)
        put("type", value.type)
        put("name", value.name)
        putIfNotNull("poster", value.poster)
        putIfNotNull("background", value.background)
        putIfNotNull("logo", value.logo)
        putIfNotNull("description", value.description)
        putIfNotNull("releaseInfo", value.releaseInfo)
        putIfNotNull("runtime", value.runtime)
        putIfNotNull("released", value.released?.toString())
        put("posterShape", value.posterShape.name.lowercase())
        put("links", JsonArray(value.links.map { AddonJson.encodeToJsonElement(Link.serializer(), it) }))
        put("trailerStreams", JsonArray(value.trailerStreams.map(StreamSerializer::write)))
        putIfNotNull("imdb_id", value.imdbId)
        putIfNotNull("country", value.country)
        putIfNotNull("language", value.language)
        putIfNotNull("awards", value.awards)
        putIfNotNull("website", value.website)
        // Back where the reader looks, in its order, so the list reads back the same.
        value.certifications.getOrNull(0)?.let { put("certification", it) }
        if (value.certifications.size > 1) {
            put("app_extras", buildJsonObject {
                put("certification", value.certifications[1])
                value.certifications.getOrNull(2)?.let { put("certificationLocal", it) }
            })
        }
        value.certifications.getOrNull(3)?.let { put("ageRating", it) }
        val hints = value.behaviorHints
        if (hints != MetaItemBehaviorHints()) {
            put("behaviorHints", buildJsonObject {
                if (hints.isLive) put("isLive", true)
                putIfNotNull("defaultVideoId", hints.defaultVideoId)
                putIfNotNull("featuredVideoId", hints.featuredVideoId)
                if (hints.hasScheduledVideos) put("hasScheduledVideos", true)
                hints.other.forEach { (k, v) -> put(k, v) }
            })
        }
    }
}

internal object MetaItemSerializer : JsonTreeSerializer<MetaItem>("MetaItem") {
    override fun read(element: JsonElement): MetaItem = read(element, DropCounter())

    fun read(element: JsonElement, drops: DropCounter): MetaItem {
        val o = element.asObject("meta item")
        val preview = MetaItemPreviewSerializer.read(o, drops)
        val videos = decodeEach(o["videos"], drops) { VideoSerializer.read(it, drops) }.distinctBy { it.id }
        return MetaItem(preview, sortVideos(videos))
    }

    /** stremio-core `VideoSortedVecAdapter`; a stable sort, so equal videos keep the addon's order. */
    private fun sortVideos(videos: List<Video>): List<Video> {
        val isSeries = videos.any { it.seriesInfo != null }
        return videos.sortedWith { a, b ->
            val sa = a.seriesInfo
            val sb = b.seriesInfo
            when {
                sa != null && sb != null -> {
                    val seasonA = if (sa.season == 0) Int.MAX_VALUE else sa.season
                    val seasonB = if (sb.season == 0) Int.MAX_VALUE else sb.season
                    compareValues(seasonA, seasonB).takeIf { it != 0 } ?: compareValues(sa.episode, sb.episode)
                }
                sa != null -> -1
                sb != null -> 1
                a.released != null && b.released != null ->
                    if (isSeries) a.released.compareTo(b.released) else b.released.compareTo(a.released)
                a.released != null -> -1
                b.released != null -> 1
                else -> 0
            }
        }
    }

    override fun write(value: MetaItem): JsonElement = buildJsonObject {
        with(MetaItemPreviewSerializer) { writeFields(value.preview) }
        put("videos", JsonArray(value.videos.map(VideoSerializer::write)))
    }
}

internal object VideoSerializer : JsonTreeSerializer<Video>("Video") {
    override fun read(element: JsonElement): Video = read(element, DropCounter())

    fun read(element: JsonElement, drops: DropCounter): Video {
        val o = element.asObject("video")
        val season = o.uint("season")
        val episode = o.uint("episode")
        val streams = when (val s = o["streams"] ?: o["stream"]) {
            null, JsonNull -> emptyList()
            is JsonArray -> decodeEach(s, drops) { StreamSerializer.read(it, drops) }
            else -> listOf(StreamSerializer.read(s, drops))
        }
        return Video(
            id = o.reqString("id"),
            title = o.optString("title") ?: o.optString("name").orEmpty(),
            released = o.releasedLenient(allowMillis = false),
            overview = o.optString("overview"),
            thumbnail = o.optString("thumbnail"),
            streams = streams,
            seriesInfo = if (season != null && episode != null) SeriesInfo(season, episode) else null,
            trailerStreams = decodeEach(o["trailerStreams"]?.takeUnless { it is JsonNull } ?: o["trailers"], drops) { StreamSerializer.read(it, drops) },
            imdb = o.imdbTitleId("imdb_id")?.let { id ->
                val imdbSeason = o.uint("imdbSeason")
                val imdbEpisode = o.uint("imdbEpisode")
                if (imdbSeason != null && imdbEpisode != null) ImdbEpisode(id, imdbSeason, imdbEpisode) else null
            },
            programme = readProgramme(o),
        )
    }

    /** The video's [Programme]: both times valid and the end after the start; null otherwise. */
    private fun readProgramme(o: JsonObject): Programme? {
        val start = o.instantLenient("startTime") ?: return null
        val end = o.instantLenient("endTime")?.takeIf { it > start } ?: return null
        val ignored = DropCounter()
        return Programme(
            start = start,
            end = end,
            runtime = o.textLenient("runtime"),
            releaseInfo = o.textLenient("releaseInfo"),
            genres = o.namesLenient("genres"),
            cast = o.namesLenient("cast"),
            directors = o.namesLenient("directors"),
            links = (o["links"] as? JsonArray)?.let { links -> decodeEach(links, ignored, MetaItemPreviewSerializer::readLink) }.orEmpty(),
            ratings = (o["ratings"] as? JsonArray)?.let { ratings -> decodeEach(ratings, ignored, ::readRating) }.orEmpty(),
        )
    }

    private fun readRating(element: JsonElement): Programme.Rating {
        val o = element.asObject("rating")
        return Programme.Rating(value = o.reqString("value"), system = o.textLenient("system"), icon = o.optUrlLenient("icon"))
    }

    private fun JsonObject.instantLenient(key: String): Instant? =
        textLenient(key)?.let { text ->
            try {
                Instant.parse(text)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

    /** A non-blank string, trimmed; anything else is absent. */
    private fun JsonObject.textLenient(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

    /** The non-blank strings of a list; anything else in it, or a value that isn't a list, is left out. */
    private fun JsonObject.namesLenient(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { item ->
            (item as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
        }

    private fun JsonObject.uint(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it in 0..UInt.MAX_VALUE.toLong() }
            ?.let { if (it > Int.MAX_VALUE) null else it.toInt() }

    override fun write(value: Video): JsonElement = buildJsonObject {
        put("id", value.id)
        put("title", value.title)
        putIfNotNull("released", value.released?.toString())
        putIfNotNull("overview", value.overview)
        putIfNotNull("thumbnail", value.thumbnail)
        if (value.streams.isNotEmpty()) put("streams", JsonArray(value.streams.map(StreamSerializer::write)))
        value.seriesInfo?.let {
            put("season", it.season)
            put("episode", it.episode)
        }
        if (value.trailerStreams.isNotEmpty()) put("trailerStreams", JsonArray(value.trailerStreams.map(StreamSerializer::write)))
        value.imdb?.let {
            put("imdb_id", it.id)
            put("imdbSeason", it.season)
            put("imdbEpisode", it.episode)
        }
        value.programme?.let { programme ->
            put("startTime", programme.start.toString())
            put("endTime", programme.end.toString())
            putIfNotNull("runtime", programme.runtime)
            putIfNotNull("releaseInfo", programme.releaseInfo)
            if (programme.genres.isNotEmpty()) putStrings("genres", programme.genres)
            if (programme.cast.isNotEmpty()) putStrings("cast", programme.cast)
            if (programme.directors.isNotEmpty()) putStrings("directors", programme.directors)
            if (programme.links.isNotEmpty()) put("links", JsonArray(programme.links.map { AddonJson.encodeToJsonElement(Link.serializer(), it) }))
            if (programme.ratings.isNotEmpty()) {
                put("ratings", JsonArray(programme.ratings.map { rating ->
                    buildJsonObject {
                        put("value", rating.value)
                        putIfNotNull("system", rating.system)
                        putIfNotNull("icon", rating.icon)
                    }
                }))
            }
        }
    }
}

/** An RFC 3339 date string, or (for catalog items) epoch milliseconds; anything unparseable is null. */
private fun JsonObject.releasedLenient(allowMillis: Boolean): Instant? {
    val e = this["released"] as? JsonPrimitive ?: return null
    return if (e.isString) {
        try {
            Instant.parse(e.content)
        } catch (_: IllegalArgumentException) {
            null
        }
    } else if (allowMillis) {
        e.longOrNull?.let { Instant.fromEpochMilliseconds(it) }
    } else {
        null
    }
}

/** An IMDb title id (`tt0944947`) under [key]; any other value is ignored, since these fields are addon extras. */
private fun JsonObject.imdbTitleId(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { ImdbId.parse(it) != null }
