package com.edde746.tortuga.data

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import com.edde746.tortuga.collections.CollectionsRepository
import com.edde746.tortuga.data.addon.AddonRepository
import com.edde746.tortuga.data.db.TortugaDatabase
import com.edde746.tortuga.data.downloads.DownloadRepository
import com.edde746.tortuga.data.library.LibraryRepository
import com.edde746.tortuga.data.profile.ProfileRepository
import com.edde746.tortuga.data.settings.DeviceSettingsRepository
import com.edde746.tortuga.data.settings.SettingsRepository
import com.edde746.tortuga.data.streams.StreamHistoryRepository
import com.edde746.tortuga.data.streams.StreamPickRepository
import com.edde746.tortuga.data.sync.HybridClock
import com.edde746.tortuga.data.sync.SyncStateRepository
import com.edde746.tortuga.data.sync.SyncStore
import com.edde746.tortuga.data.tracker.TrackerAccountRepository
import com.edde746.tortuga.data.tracker.TrackerWriteQueue
import com.edde746.tortuga.data.transfer.DataTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock

/**
 * The app's local data: one SQLite database and its repositories. Create one per process in the
 * composition root and [close] it on shutdown.
 *
 * Every local change to synced data (profiles, addon lists, library, stream history, profile settings) stamps a new
 * hybrid logical clock timestamp and marks the record dirty; [sync] is where the sync engine picks those changes up
 * and merges other devices' records.
 *
 * Opening the database (creating or migrating it) happens here, on the calling thread, on desktop and Apple platforms;
 * do it off the main thread. Android's driver opens it at its first statement, on the I/O context ([open]).
 *
 * @param onMigrate called as an older database starts its migration to this version's schema (the start's progress,
 *   [com.edde746.tortuga.app.StartupStep.MIGRATION]).
 */
class LocalData internal constructor(
    factory: DatabaseFactory,
    wallClock: () -> Long,
    io: CoroutineContext,
    maxClockDriftMillis: Long,
    onMigrate: () -> Unit = {},
) : AutoCloseable {
    constructor(factory: DatabaseFactory, onMigrate: () -> Unit = {}) : this(
        factory,
        { Clock.System.now().toEpochMilliseconds() },
        Dispatchers.IO,
        HybridClock.DEFAULT_MAX_DRIFT_MILLIS,
        onMigrate,
    )

    private val driver = factory.createDriver(ReportingSchema(TortugaDatabase.Schema, onMigrate))
    internal val db = Db(TortugaDatabase(driver), io, wallClock, maxClockDriftMillis)

    val profiles: ProfileRepository = ProfileRepository(db)
    val addons: AddonRepository = AddonRepository(db)
    val library: LibraryRepository = LibraryRepository(db)
    val streamHistory: StreamHistoryRepository = StreamHistoryRepository(db)
    val streamPicks: StreamPickRepository = StreamPickRepository(db)
    val settings: SettingsRepository = SettingsRepository(db)
    val collections: CollectionsRepository = CollectionsRepository(settings)
    val deviceSettings: DeviceSettingsRepository = DeviceSettingsRepository(db)
    val trackerAccounts: TrackerAccountRepository = TrackerAccountRepository(db)
    val trackerWriteQueue: TrackerWriteQueue = TrackerWriteQueue(db)
    val syncState: SyncStateRepository = SyncStateRepository(db)
    val sync: SyncStore = SyncStore(db)
    internal val downloads: DownloadRepository = DownloadRepository(db)
    internal val transfer: DataTransfer = DataTransfer(db)

    /**
     * Returns once the database is open: at once where it opened with this object, after Android's driver opened it
     * (creating or migrating it) otherwise. A database that can't open throws here.
     */
    suspend fun open() {
        db.useDriver { driver.executeQuery(null, "SELECT 1", { QueryResult.Value(Unit) }, 0) }
    }

    /**
     * Closes the database once the reads and writes running are done (a cancelled coroutine's read still running on the
     * I/O context among them); one asked for afterwards throws.
     */
    override fun close() = db.close(driver)
}

/** [schema], calling [onMigrate] before it migrates an older database. */
private class ReportingSchema(
    private val schema: SqlSchema<QueryResult.Value<Unit>>,
    private val onMigrate: () -> Unit,
) : SqlSchema<QueryResult.Value<Unit>> by schema {
    override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion): QueryResult.Value<Unit> {
        onMigrate()
        return schema.migrate(driver, oldVersion, newVersion, *callbacks)
    }
}
