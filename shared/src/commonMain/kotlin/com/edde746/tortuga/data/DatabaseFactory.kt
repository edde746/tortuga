package com.edde746.tortuga.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema

/**
 * Where the app's SQLite database lives, and how to open it. Opening creates the database or migrates it to the
 * current schema version through the schema [LocalData] passes (`TortugaDatabase.Schema`, whose migrations are
 * `src/commonMain/sqldelight/migrations`).
 *
 * Each platform constructs it its own way: a file under the platform's app-data location, a caller-chosen location,
 * or a private in-memory database for tests. [LocalData] opens it.
 */
expect class DatabaseFactory {
    internal fun createDriver(schema: SqlSchema<QueryResult.Value<Unit>>): SqlDriver
}

/** File name of the database on every platform. */
internal const val DATABASE_FILE_NAME = "tortuga.db"

/**
 * How long a statement waits for another connection's lock before SQLITE_BUSY, on the drivers that let the app set
 * it (desktop's sqlite-jdbc, 3 s by default; Apple's SQLiter, 5 s). A commit takes longer than those while the disk is
 * saturated (12 s on a Windows PC with a 61 GB file written beside it). Android's framework fixes its own
 * 2.5 s per connection, and its connection pool queues the app's writers, so they don't meet each other's locks.
 */
internal const val BUSY_TIMEOUT_MILLIS = 60_000
