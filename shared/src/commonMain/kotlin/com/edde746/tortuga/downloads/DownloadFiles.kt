package com.edde746.tortuga.downloads

import com.edde746.tortuga.data.downloads.DownloadRow
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.net.UriComponent
import kotlinx.io.IOException
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path

/**
 * Where a download's files go under its location's directory: `Movies/Title (Year)/Title (Year)
 * [tag].ext`, `TV Shows/Show (Year)/Season 01/S01E02 - Title [tag].ext`, the subtitles in `<video base>_subs/`, the
 * artwork in `artwork/<hash>.<ext>`. The tag (8 hex digits of a hash of `type:metaId:videoId`) keeps two videos with
 * the same names apart. Paths are stored relative to the location, with `/` separators.
 */
internal object DownloadNames {
    const val MOVIES = "Movies"
    const val SHOWS = "TV Shows"
    const val ARTWORK = "artwork"
    const val PART = ".part"

    /** What sits next to a part file while a platform transport works on it (Apple: resume data, a received body). */
    val PART_SIDECARS = listOf(".part.resume", ".part.incoming")

    /** The video's path under its location, with [extension]. */
    fun videoPath(row: DownloadRow, extension: String): String {
        val tag = tag(row.type, row.metaId, row.videoId)
        val name = withYear(clean(row.title).ifEmpty { row.metaId.let(::clean).ifEmpty { "Video" } }, row.year)
        val season = row.season
        val episode = row.episode
        val base = when {
            season != null && episode != null -> {
                val code = "S${pad(season)}E${pad(episode)}"
                val title = row.episodeTitle?.let(::clean).orEmpty()
                "$SHOWS/$name/Season ${pad(season)}/${if (title.isEmpty()) code else "$code - $title"} [$tag]"
            }
            row.type == "movie" || row.videoId == row.metaId -> "$MOVIES/$name/$name [$tag]"
            else -> "$SHOWS/$name/${row.episodeTitle?.let(::clean)?.ifEmpty { null } ?: clean(row.videoId)} [$tag]"
        }
        return "$base.$extension"
    }

    /** The subtitles' directory of the video at [videoPath]. */
    fun subtitlesDirectory(videoPath: String): String = videoPath.substringBeforeLast('.') + "_subs"

    /** Where an image saved from [url] goes. */
    fun artworkPath(url: String): String {
        val segment = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val extension = segment.substringAfterLast('.', "").lowercase().takeIf { it in IMAGE_EXTENSIONS } ?: "jpg"
        return "$ARTWORK/${hex64(fnv64(url))}.$extension"
    }

    /**
     * The file's extension, from its [filename] (the resolve's), else the stream's [hinted] name: only a video
     * container's ([VIDEO_EXTENSIONS]), so an addon or its server can't save a program or script (`exe`, `bat`, `hta`,
     * `js`) at a path it can predict; `mkv` otherwise (mpv tells the container from the content).
     */
    fun extension(filename: String?, hinted: String?): String =
        listOfNotNull(filename, hinted).firstNotNullOfOrNull { name ->
            name.substringAfterLast('.', "").lowercase().takeIf { it in VIDEO_EXTENSIONS }
        } ?: "mkv"

    fun tag(type: String, metaId: String, videoId: String): String = hex32(fnv32("$type:$metaId:$videoId"))

    /** A name safe as one path component on every platform. */
    fun clean(name: String): String {
        val replaced = name.replace(":", " -").replace(INVALID, "").replace(SPACES, " ").trim().trimEnd('.', ' ')
        val short = if (replaced.length > MAX_COMPONENT) replaced.take(MAX_COMPONENT).trimEnd('.', ' ') else replaced
        return if (short.uppercase().substringBefore('.') in RESERVED) "_$short" else short
    }

    private fun withYear(name: String, year: Int?) = if (year != null) "$name ($year)" else name

    private fun pad(number: Int) = number.toString().padStart(2, '0')

    private fun fnv32(text: String): UInt {
        var hash = 0x811c9dc5u
        for (byte in text.encodeToByteArray()) {
            hash = hash xor byte.toUByte().toUInt()
            hash *= 0x01000193u
        }
        return hash
    }

    private fun fnv64(text: String): ULong {
        var hash = 0xcbf29ce484222325uL
        for (byte in text.encodeToByteArray()) {
            hash = hash xor byte.toUByte().toULong()
            hash *= 0x100000001b3uL
        }
        return hash
    }

    private fun hex32(value: UInt) = value.toString(16).padStart(8, '0')

    private fun hex64(value: ULong) = value.toString(16).padStart(16, '0')

    private const val MAX_COMPONENT = 80
    private val INVALID = Regex("""[\\/*?"<>|\u0000-\u001F]""")
    private val SPACES = Regex("""\s+""")
    private val VIDEO_EXTENSIONS = setOf(
        "mkv", "mp4", "m4v", "webm", "avi", "mov", "ts", "m2ts", "mts", "wmv", "flv", "mpg", "mpeg", "ogv", "3gp",
    )
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "avif")
    private val RESERVED = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }
}

/**
 * The downloads' file operations over [fileSystem], under the locations' directories. None throws: a failure is
 * logged (never with a path) and reported by the return value.
 */
internal class DownloadFiles(private val fileSystem: FileSystem) {
    fun size(path: Path): Long? = try {
        fileSystem.metadataOrNull(path)?.takeIf { it.isRegularFile }?.size
    } catch (_: IOException) {
        null
    }

    fun exists(path: Path): Boolean = try {
        fileSystem.exists(path)
    } catch (_: IOException) {
        false
    }

    /** Creates [directory] and its parents; false when that fails (the location went away, no permission). */
    fun createDirectories(directory: Path): Boolean = try {
        fileSystem.createDirectories(directory)
        true
    } catch (e: IOException) {
        Log.w(TAG) { "can't create a directory (${e::class.simpleName})" }
        false
    }

    fun delete(path: Path) {
        try {
            fileSystem.delete(path, mustExist = false)
        } catch (e: IOException) {
            Log.w(TAG) { "can't delete a file (${e::class.simpleName})" }
        }
    }

    /** Deletes [path] and, for a directory, everything under it. */
    fun deleteRecursively(path: Path) {
        val metadata = try {
            fileSystem.metadataOrNull(path)
        } catch (_: IOException) {
            null
        } ?: return
        if (metadata.isDirectory) list(path).forEach(::deleteRecursively)
        delete(path)
    }

    /** The part file of the video at [video], with what a platform transport keeps next to it. */
    fun deletePart(video: Path) {
        delete(Path("$video${DownloadNames.PART}"))
        DownloadNames.PART_SIDECARS.forEach { delete(Path("$video$it")) }
    }

    /** A new file at [path] (replacing one there), to write; throws on I/O failure. */
    fun sink(path: Path): Sink = fileSystem.sink(path).buffered()

    /** Moves [from] onto [to], replacing it; false when that fails. */
    fun move(from: Path, to: Path): Boolean = try {
        fileSystem.atomicMove(from, to)
        true
    } catch (e: IOException) {
        Log.w(TAG) { "can't move a file into place (${e::class.simpleName})" }
        false
    }

    fun list(directory: Path): List<Path> = try {
        if (fileSystem.metadataOrNull(directory)?.isDirectory == true) fileSystem.list(directory).toList() else emptyList()
    } catch (_: IOException) {
        emptyList()
    }

    /** Deletes the empty directories from [directory] up to, not including, [root]. */
    fun deleteEmptyParents(directory: Path?, root: Path) {
        var current = directory
        val top = root.toString()
        while (current != null && current.toString().length > top.length && current.toString().startsWith(top)) {
            if (exists(current)) {
                if (list(current).isNotEmpty()) return
                delete(current)
            }
            current = current.parent
        }
    }

    /** Every file under [directory] whose name ends with one of [suffixes], at any depth. */
    fun findFiles(directory: Path, suffixes: List<String>): List<Path> = list(directory).flatMap { child ->
        val metadata = try {
            fileSystem.metadataOrNull(child)
        } catch (_: IOException) {
            null
        }
        when {
            metadata == null -> emptyList()
            metadata.isDirectory -> findFiles(child, suffixes)
            suffixes.any { child.name.endsWith(it) } -> listOf(child)
            else -> emptyList()
        }
    }

    /** The total size of the files under [directory], at any depth. */
    fun totalSize(directory: Path): Long = list(directory).sumOf { child ->
        val metadata = try {
            fileSystem.metadataOrNull(child)
        } catch (_: IOException) {
            null
        }
        when {
            metadata == null -> 0L
            metadata.isDirectory -> totalSize(child)
            else -> metadata.size.coerceAtLeast(0L)
        }
    }

    private companion object {
        const val TAG = "downloads"
    }
}

/** A path under [directory] given with `/` separators. */
internal fun child(directory: String, relativePath: String): Path = Path(directory, *relativePath.split('/').toTypedArray())

/**
 * A `file://` URL for the absolute [path] (Coil and Nuke load them): each segment percent-encoded, Windows' drive
 * letter and backslashes as a file URL has them.
 */
internal fun fileUrl(path: String): String {
    val segments = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
    val encoded = segments.mapIndexed { index, segment ->
        if (index == 0 && DRIVE.matches(segment)) segment else UriComponent.encode(segment)
    }
    return "file:///" + encoded.joinToString("/")
}

private val DRIVE = Regex("""[A-Za-z]:""")
