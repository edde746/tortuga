package com.edde746.tortuga.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/** The desktop database (Windows, Linux), through the SQLite JDBC driver. */
actual class DatabaseFactory private constructor(private val file: Path?, @Suppress("unused") inMemory: Boolean) {
    /** The database at [file]; missing parent directories are created on open. */
    constructor(file: Path) : this(file, inMemory = false)

    internal actual fun createDriver(schema: SqlSchema<QueryResult.Value<Unit>>): SqlDriver {
        val url = if (file == null) {
            "jdbc:sqlite::memory:"
        } else {
            val path = file.toAbsolutePath()
            path.parent?.let { Files.createDirectories(it) }
            "jdbc:sqlite:$path"
        }
        // WAL lets query flows read while a write is in progress. The driver opens a connection per thread; each waits
        // up to BUSY_TIMEOUT_MILLIS for another's lock.
        val properties = Properties().apply {
            setProperty("journal_mode", "WAL")
            setProperty("busy_timeout", BUSY_TIMEOUT_MILLIS.toString())
        }
        return JdbcSqliteDriver(url, properties, schema)
    }

    companion object {
        /** A private in-memory database, gone when [LocalData.close] closes it. */
        fun inMemory(): DatabaseFactory = DatabaseFactory(null, inMemory = true)

        /**
         * The per-user application data location: `%LOCALAPPDATA%\Tortuga` on Windows (the database holds device
         * settings, so it doesn't roam), `$XDG_DATA_HOME/tortuga` (`~/.local/share/tortuga`) on Linux, which a
         * Flatpak maps into its sandbox, and `~/Library/Application Support/Tortuga` on macOS.
         */
        fun defaultDirectory(): Path {
            val os = System.getProperty("os.name").lowercase()
            val home = Path.of(System.getProperty("user.home"))
            return when {
                os.startsWith("windows") -> {
                    val local = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
                    (local ?: home.resolve("AppData").resolve("Local")).resolve("Tortuga")
                }
                os.startsWith("mac") -> home.resolve("Library").resolve("Application Support").resolve("Tortuga")
                else -> {
                    val data = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
                    (data ?: home.resolve(".local").resolve("share")).resolve("tortuga")
                }
            }
        }
    }
}
