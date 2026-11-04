package com.edde746.tortuga.kids

import com.edde746.tortuga.trackers.ids.CachedDataset
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlin.time.Clock

/** agerating's id tables, by their names in its small database and its packed list. */
internal enum class AgeIdTable(val table: String) {
    /** IMDb title ids as numbers, without `tt` and leading zeros. */
    IMDB("imdb"),
    TMDB_MOVIE("tmdb_movie"),
    TMDB_TV("tmdb_tv"),
    TVDB_SERIES("tvdb_series"),
    TVDB_MOVIE("tvdb_movie"),
}

/** Ages by id, as one copy of the age database answers them. */
internal fun interface AgeLookup {
    /** The age the database gives [id] in [table]; null when it hasn't the id. */
    fun age(table: AgeIdTable, id: Long): Int?
}

/** The age database as [AgeRatings] reads it; tests give it ages directly. */
internal interface AgeDatabase {
    /** [block] on the database, or null when it can't be had (not downloaded, offline with no copy) or read. */
    suspend fun <R : Any> read(block: (AgeLookup) -> R): R?

    /** Loads the database into memory (downloading it first when there is no copy), so the first lookup doesn't wait. */
    fun prefetch()
}

/**
 * agerating's small database (https://github.com/edde746/agerating, the agerating License 1.1, used with its
 * licensor's permission) as its packed list, `agerating-ids.bin.gz`: the IMDb, TMDB and TVDB ids of about 600,000
 * films and series, each with its suggested age on the FSK scale (0, 6, 12, 16, 18), the official FSK rating where
 * Wikidata has one, else a language model's estimate. The list doesn't say which, so every age it gives is shown as a
 * suggestion ("Suggested 12+"), never as a rating board's.
 *
 * Downloaded from the latest GitHub release of [repository] (resolved to its tag first; the file and its
 * `SHA256SUMS` from that tag), checked, kept gzipped in [directory] (a cache location: it can be downloaded again;
 * gzip's CRC checks the copy at each load), unpacked in memory into an [AgeIndex] off the main thread, and re-checked
 * daily: downloaded again only once a newer release's tag is the latest ([CachedDataset]). [updateDiskCopy] brings
 * the copy up to date each time the app comes to the foreground on every device; it loads into memory only when a kids profile looks a title
 * up or [prefetch] asks (a kids profile on this device).
 */
internal class AgeRatingDataset(
    http: HttpClient,
    directory: Path,
    private val scope: CoroutineScope,
    clock: Clock = Clock.System,
    fileSystem: FileSystem = SystemFileSystem,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    repository: String = REPOSITORY,
) : AgeDatabase {
    private val dataset = CachedDataset(
        label = "agerating",
        fileName = ASSET_NAME,
        repository = repository,
        asset = ASSET_NAME,
        accept = "application/gzip",
        http = http,
        directory = directory,
        fileSystem = fileSystem,
        clock = clock,
        scope = scope,
        ioDispatcher = ioDispatcher,
        parseDispatcher = ioDispatcher,
        open = { path -> AgeIndex.read(gunzip(fileSystem.source(path).buffered().use { it.readByteArray() })) },
        isEmpty = AgeIndex::isEmpty,
        summary = AgeIndex::summary,
        // The packed list is about 2 MB.
        maxBytes = 16L * 1024 * 1024,
    )

    override suspend fun <R : Any> read(block: (AgeLookup) -> R): R? = dataset.get()?.let(block)

    override fun prefetch() {
        scope.launch { dataset.get() }
    }

    /** Brings the disk copy up to date without loading it ([CachedDataset.updateDiskCopy]). */
    suspend fun updateDiskCopy() = dataset.updateDiskCopy()

    companion object {
        /** agerating's repository, whose releases publish the packed list. */
        const val REPOSITORY = "https://github.com/edde746/agerating"
        const val ASSET_NAME = "agerating-ids.bin.gz"
    }
}

/**
 * The single gzip member [packed] unpacked, into an array of the size its trailer gives. Throws when [packed] isn't
 * gzip, is damaged or cut short, holds more than one member, or unpacks to more than [MAX_UNPACKED_BYTES].
 */
internal fun gunzip(packed: ByteArray): ByteArray {
    require(packed.size >= GZIP_MIN_BYTES) { "not gzip: ${packed.size} bytes" }
    val n = packed.size
    // ISIZE, the trailer's last four bytes: the unpacked size modulo 2^32.
    val size = (packed[n - 4].toLong() and 0xFF) or ((packed[n - 3].toLong() and 0xFF) shl 8) or
        ((packed[n - 2].toLong() and 0xFF) shl 16) or ((packed[n - 1].toLong() and 0xFF) shl 24)
    require(size <= MAX_UNPACKED_BYTES) { "gzip trailer gives $size bytes, more than $MAX_UNPACKED_BYTES" }
    return ByteArray(size.toInt()).also { inflateGzip(packed, it) }
}

/**
 * Inflates the gzip member [packed] into [into], which it must fill exactly and end there. Throws when [packed] isn't
 * gzip, is damaged, or holds more or fewer bytes than [into] takes.
 */
internal expect fun inflateGzip(packed: ByteArray, into: ByteArray)

/** A gzip member's header (10 bytes) and trailer (8). */
private const val GZIP_MIN_BYTES = 18

/** Far above the packed list's 2 MB: a bound on what a damaged trailer can make this allocate. */
private const val MAX_UNPACKED_BYTES = 64L * 1024 * 1024
