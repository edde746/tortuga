package com.edde746.tortuga.trackers.ids

import com.edde746.tortuga.kids.gunzip
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlin.time.Clock

/** Where the resolver finds the anime mapping datasets; tests give it indexes directly. */
internal interface AnimeMappingSource {
    /** Fribb's mapping, or `null` when it can't be had (offline with no copy). */
    suspend fun fribb(): FribbIndex?

    /** The Anime-Lists mapping, or `null` when it can't be had. */
    suspend fun animeLists(): AnimeListsIndex?
}

/** Both mappings, as one anime-mappings file holds them. */
internal class AnimeMappingIndexes(val fribb: FribbIndex, val animeLists: AnimeListsIndex)

/**
 * The anime mappings: anime-mappings' packed list (`anime-mappings.bin.gz`, about 0.65 MB, from the latest release of
 * https://github.com/edde746/anime-mappings, which rebuilds it daily from two community datasets):
 *
 * - Fribb's `anime-list-mini.json` (~40 000 rows; https://github.com/Fribb/anime-lists): which AniDB, Kitsu,
 *   MyAnimeList, AniList and Simkl entry is which IMDb/TMDB/TVDB title and season ([FribbIndex]).
 * - Anime-Lists' `anime-list.xml` (~11 000 entries; https://github.com/Anime-Lists/anime-lists): where each AniDB
 *   entry's episodes lie on TVDB's and TMDB's seasons ([AnimeListsIndex]).
 *
 * The file is kept gzipped in [directory] (a cache location: it can be downloaded again) and re-checked daily by the
 * latest release's tag ([CachedDataset]). [updateDiskCopy] brings that copy up to date each time the app comes to
 * the foreground on every device; the indexes load into memory only when the resolver first needs anime ids, which it does only while an
 * anime tracker is active, or when [load] asks (an anime tracker connected on this device). Both indexes are also kept
 * as a snapshot of their arrays, which loads in a fraction of the time the packed list takes to decode on a TV stick.
 *
 * @param directory the platform's cache directory for the file, created on first download.
 * @param scope where loads and daily checks run, so a lookup that is cancelled doesn't waste a download.
 */
class AnimeMappings internal constructor(
    http: HttpClient,
    directory: Path,
    scope: CoroutineScope,
    clock: Clock,
    fileSystem: FileSystem,
    ioDispatcher: CoroutineDispatcher,
    parseDispatcher: CoroutineDispatcher,
) {
    constructor(http: HttpClient, directory: String, scope: CoroutineScope) :
        this(http, Path(directory), scope, Clock.System, SystemFileSystem, Dispatchers.IO, Dispatchers.Default)

    private val dataset = CachedDataset(
        label = "anime-mappings",
        fileName = ASSET_NAME,
        repository = REPOSITORY,
        asset = ASSET_NAME,
        accept = "application/gzip",
        http = http,
        directory = directory,
        fileSystem = fileSystem,
        clock = clock,
        scope = scope,
        ioDispatcher = ioDispatcher,
        parseDispatcher = parseDispatcher,
        open = { path -> read(gunzip(fileSystem.source(path).buffered().use { it.readByteArray() })) },
        isEmpty = { it.fribb.isEmpty || it.animeLists.isEmpty },
        summary = { "${it.fribb.summary}; ${it.animeLists.summary}" },
        // The packed list is about 0.65 MB.
        maxBytes = 8L * 1024 * 1024,
        // Decoding the packed list takes about 0.4 s on a TV stick; the indexes' arrays load in a fifth of that.
        snapshot = PackedForm(write = ::writeSnapshot, read = ::readSnapshot),
    )

    /** The datasets as the resolver and [AnimeEntryTitles] read them. */
    internal val source: AnimeMappingSource = object : AnimeMappingSource {
        override suspend fun fribb(): FribbIndex? = dataset.get()?.fribb
        override suspend fun animeLists(): AnimeListsIndex? = dataset.get()?.animeLists
    }

    /** Brings the disk copy up to date without loading it ([CachedDataset.updateDiskCopy]). */
    internal suspend fun updateDiskCopy() = dataset.updateDiskCopy()

    /** Loads the indexes (downloading the file first when there is no copy), so the first lookup doesn't wait. */
    internal suspend fun load() {
        dataset.get()
    }

    companion object {
        /** anime-mappings' repository, whose releases publish the file. */
        const val REPOSITORY: String = "https://github.com/edde746/anime-mappings"
        const val ASSET_NAME: String = "anime-mappings.bin.gz"

        /** Both indexes of the packed list in [bytes] (unpacked); throws [IllegalArgumentException] for anything else. */
        internal fun read(bytes: ByteArray): AnimeMappingIndexes {
            val tables = PackedTables.read(bytes)
            return AnimeMappingIndexes(FribbIndex.from(tables.table("fribb")), AnimeListsIndex.from(tables))
        }

        /** The Fribb index's arrays after their length, then the Anime-Lists index's. */
        private fun writeSnapshot(indexes: AnimeMappingIndexes, sink: Sink) {
            val fribb = Buffer().also { indexes.fribb.pack(it) }
            sink.writeLong(fribb.size)
            sink.write(fribb, fribb.size)
            indexes.animeLists.pack(sink)
        }

        private fun readSnapshot(source: Source): AnimeMappingIndexes {
            val size = source.readLong()
            require(size in 0..Int.MAX_VALUE) { "not a snapshot of the anime mappings" }
            val fribb = FribbIndex.unpack(Buffer().also { source.readTo(it, size) })
            return AnimeMappingIndexes(fribb, AnimeListsIndex.unpack(source))
        }
    }
}
