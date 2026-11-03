package com.edde746.tortuga.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.edde746.tortuga.data.db.TortugaDatabase
import com.edde746.tortuga.data.sync.Hlc
import com.edde746.tortuga.data.sync.RecordKey
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Replays every checked-in schema snapshot (`src/commonMain/sqldelight/databases/N.db`, one per released version)
 * through the migrations the app runs, and compares the result with a database created fresh at the current version.
 * A schema change without its migration and new snapshot fails here (and in verifyCommonMainTortugaDatabaseMigration,
 * which `check` runs).
 */
class MigrationTest {
    private val snapshots: Map<Long, File> = File(checkNotNull(System.getProperty("tortuga.schemaDir")) { "Run through Gradle's jvmTest" })
        .listFiles { file -> file.name.matches(Regex("""\d+\.db""")) }.orEmpty()
        .associateBy { it.nameWithoutExtension.toLong() }

    @Test
    fun theNewestSnapshotIsTheCurrentSchema() {
        val current = TortugaDatabase.Schema.version
        assertEquals(current, snapshots.keys.maxOrNull(), "Generate $current.db with generateCommonMainTortugaDatabaseSchema")
        assertEquals(freshSchema(), withCopy(snapshots.getValue(current)) { schemaOf(it) })
    }

    @Test
    fun everyOlderSnapshotMigratesToTheCurrentSchema() {
        val current = TortugaDatabase.Schema.version
        assertEquals((1..current).toSet(), snapshots.keys, "One snapshot per schema version")
        val fresh = freshSchema()
        for ((version, file) in snapshots.toSortedMap()) {
            val migrated = withCopy(file) { driver ->
                TortugaDatabase.Schema.migrate(driver, version, current)
                schemaOf(driver)
            }
            assertEquals(fresh, migrated, "Schema after migrating from version $version")
        }
    }

    @Test
    fun aQueuedDownloadAndItsOwnerSurviveTheUpgradeFromVersion2() {
        withCopy(snapshots.getValue(2)) { driver ->
            driver.execute(
                null,
                """
                INSERT INTO download(id, type, meta_id, video_id, title, season, episode, status, stream_json, location_id,
                    relative_path, total_bytes, downloaded_bytes, priority, added_at, attempts, keep_synced, transferring,
                    artwork_saved, subtitles_saved)
                VALUES (7, 'series', 'tt0903747', 'tt0903747:1:2', 'Breaking Bad', 1, 2, 'QUEUED', '{}', 'internal',
                    'Breaking Bad/S01E02.mkv', 350000, 150000, 5, 1000, 1, 1, 0, 0, 0)
                """.trimIndent(),
                0,
            )
            driver.execute(null, "INSERT INTO download_owner(download_id, profile_id, added_at) VALUES (7, 'p1', 1000)", 0)

            TortugaDatabase.Schema.migrate(driver, 2, TortugaDatabase.Schema.version)

            val downloads = TortugaDatabase(driver).downloadQueries
            downloads.insert(
                type = "movie", metaId = "tt0111161", videoId = "tt0111161", title = "The Shawshank Redemption", year = null,
                episodeTitle = null, season = null, episode = null, poster = null, thumbnail = null, status = "QUEUED",
                failure = null, httpStatus = null, streamJson = "{}", addonUrl = null, addonName = null, locationId = "internal",
                addedAt = 2000, keepSynced = false,
            )
            val queued = downloads.selectQueued().executeAsList()
            assertEquals(listOf("tt0903747:1:2", "tt0111161"), queued.map { it.video_id }, "the older download stays first")
            val kept = queued.first()
            assertEquals(
                listOf<Any?>(7L, "Breaking Bad/S01E02.mkv", 350_000L, 150_000L, 1000L, 1L, true),
                listOf(kept.id, kept.relative_path, kept.total_bytes, kept.downloaded_bytes, kept.added_at, kept.attempts, kept.keep_synced),
            )
            assertEquals(listOf("p1"), downloads.selectOwners(7).executeAsList())
        }
    }

    @Test
    fun keepSyncedRulesTakeTheirSeriesNameFromItsLatestDownloadInTheUpgradeFromVersion4() {
        withCopy(snapshots.getValue(4)) { driver ->
            for (sql in listOf(
                """
                INSERT INTO download(id, type, meta_id, video_id, title, season, episode, poster, status, location_id,
                    downloaded_bytes, added_at, attempts, keep_synced, transferring, artwork_saved, subtitles_saved)
                VALUES (1, 'series', 'tt0903747', 'tt0903747:1:1', 'Breaking Bad (old)', 1, 1, 'https://img.example/old.jpg',
                    'COMPLETED', 'internal', 0, 1000, 0, 1, 0, 0, 0),
                    (2, 'series', 'tt0903747', 'tt0903747:1:2', 'Breaking Bad', 1, 2, 'https://img.example/bb.jpg',
                    'QUEUED', 'internal', 0, 2000, 0, 1, 0, 0, 0)
                """,
                "INSERT INTO download_sync_rule(profile_id, meta_id, type, episodes, last_run) VALUES ('p1', 'tt0903747', 'series', 3, 500)",
                "INSERT INTO download_sync_rule(profile_id, meta_id, type, episodes, last_run) VALUES ('p1', 'tt0141842', 'series', NULL, NULL)",
            )) {
                driver.execute(null, sql.trimIndent(), 0)
            }

            TortugaDatabase.Schema.migrate(driver, 4, TortugaDatabase.Schema.version)

            val rules = TortugaDatabase(driver).downloadQueries.selectAllRules().executeAsList()
            assertEquals(
                listOf(
                    listOf<Any?>("tt0141842", null, null, null, false, null),
                    listOf<Any?>("tt0903747", 3L, 500L, "Breaking Bad", false, "https://img.example/bb.jpg"),
                ),
                rules.map { listOf(it.meta_id, it.episodes, it.last_run, it.title, it.paused, it.poster) },
            )
        }
    }

    @Test
    fun anAddonInstalledBeforeTheSwitchIsOnWithoutTheWatchActivityAgreementAfterTheUpgradeFromVersion5() {
        withCopy(snapshots.getValue(5)) { driver ->
            driver.execute(
                null,
                "INSERT INTO addon(profile_id, transport_url, manifest_json, position) VALUES ('p1', 'https://a.example/manifest.json', '{}', 0)",
                0,
            )

            TortugaDatabase.Schema.migrate(driver, 5, TortugaDatabase.Schema.version)

            // Read by name, not through the generated queries, so a schema without the columns fails here, not to compile.
            val stored = query(driver, "SELECT transport_url, enabled, watch_activity FROM addon WHERE profile_id = 'p1'") {
                listOf<Any?>(it.getString(0), it.getLong(1), it.getLong(2))
            }
            assertEquals(listOf(listOf<Any?>("https://a.example/manifest.json", 1L, 0L)), stored, "on, and asked for the agreement")
        }
    }

    @Test
    fun aStreamPickBecomesAChangeToPushAndThePullStartsOverInTheUpgradeFromVersion9() {
        withCopy(snapshots.getValue(9)) { driver ->
            for (sql in listOf(
                "INSERT INTO hlc_state(id, node_id, last_hlc) VALUES (1, '0123456789abcdef', '018b00000000-0005-0123456789abcdef')",
                "INSERT INTO sync_state(account_id, pull_cursor, updated_at) VALUES ('account', 1234, 1000)",
                """
                INSERT INTO stream_pick(pick_id, profile_id, meta_id, video_id, runtime_seconds, picked_at, device_class,
                    metered, hdr_display, dolby_vision_display, hardware_codecs, source, candidates_json, chosen, first_row,
                    outcome, outcome_at)
                VALUES ('k/1', 'p1', 'tt0111161', 'tt0111161', 8520, 1790000000000, 'tv', 0, 1, 0, 'HEVC', 'list',
                    '[{"r":"UHD_2160"},{"r":"FHD_1080"}]', 1, 0, 'good', 1790000120000),
                    ('k2', 'p1', 'tt0111161', 'tt0111161', NULL, 1790000300000, 'tv', 0, 1, 0, '', 'player', '[{}]', 0, 4,
                    NULL, NULL)
                """,
            )) {
                driver.execute(null, sql.trimIndent(), 0)
            }

            TortugaDatabase.Schema.migrate(driver, 9, TortugaDatabase.Schema.version)

            val picks = query(driver, "SELECT record_id, hlc, server_version, dirty, deleted, chosen FROM stream_pick ORDER BY picked_at") {
                listOf<Any?>(it.getString(0), it.getString(1), it.getLong(2), it.getLong(3), it.getLong(4), it.getLong(5))
            }
            val first = Hlc(1_790_000_120_000, 0, "0123456789abcdef")
            val second = Hlc(1_790_000_300_000, 0, "0123456789abcdef")
            assertEquals(
                listOf(
                    listOf<Any?>(RecordKey.StreamPick("p1", "k/1").recordId(), first.toString(), null, 1L, 0L, 1L),
                    listOf<Any?>(RecordKey.StreamPick("p1", "k2").recordId(), second.toString(), null, 1L, 0L, 0L),
                ),
                picks,
                "stamped at the outcome when later than the pick, waiting to be pushed",
            )
            assertEquals(listOf(second.toString()), query(driver, "SELECT last_hlc FROM hlc_state") { it.getString(0) }, "the clock goes on after them")
            assertEquals(listOf<Any?>("account", 0L), query(driver, "SELECT account_id, pull_cursor FROM sync_state") { listOf<Any?>(it.getString(0), it.getLong(1)) }.single())
        }
    }

    @Test
    fun aDeviceWhoseClockNeverRanGetsOneForItsStreamPicksInTheUpgradeFromVersion9() {
        withCopy(snapshots.getValue(9)) { driver ->
            driver.execute(
                null,
                """
                INSERT INTO stream_pick(pick_id, profile_id, meta_id, video_id, runtime_seconds, picked_at, device_class,
                    metered, hdr_display, dolby_vision_display, hardware_codecs, source, candidates_json, chosen, first_row,
                    outcome, outcome_at)
                VALUES ('k1', 'p1', 'tt0111161', 'tt0111161', NULL, 1790000000000, 'handheld', 1, 0, 0, '', 'list', '[{}]', 0, 0,
                    NULL, NULL)
                """.trimIndent(),
                0,
            )

            TortugaDatabase.Schema.migrate(driver, 9, TortugaDatabase.Schema.version)

            val (node, last) = query(driver, "SELECT node_id, last_hlc FROM hlc_state") { it.getString(0)!! to it.getString(1)!! }.single()
            val hlc = Hlc.parse(query(driver, "SELECT hlc FROM stream_pick") { it.getString(0)!! }.single())
            assertEquals(Hlc(1_790_000_000_000, 0, node), hlc)
            assertEquals(hlc, Hlc.parse(last))
        }
    }

    @Test
    fun schemaDescriptionSeesEveryTable() {
        val tables = freshSchema().keys.filter { it.startsWith("table ") }.map { it.removePrefix("table ") }.toSet()
        assertTrue(
            tables.containsAll(
                setOf(
                    "profile", "addon", "addon_list", "library_item", "tracker_account", "tracker_write_queue",
                    "stream_history", "setting", "device_setting", "sync_state", "hlc_state",
                    "download", "download_owner", "download_meta", "download_subtitle", "download_sync_rule",
                ),
            ),
            "Tables: $tables",
        )
    }

    private fun freshSchema(): Map<String, List<String>> {
        val driver = JdbcSqliteDriver("jdbc:sqlite::memory:")
        return try {
            TortugaDatabase.Schema.create(driver)
            schemaOf(driver)
        } finally {
            driver.close()
        }
    }

    private fun <T> withCopy(snapshot: File, block: (SqlDriver) -> T): T {
        val copy = Files.createTempFile("tortuga-migration", ".db").toFile()
        snapshot.copyTo(copy, overwrite = true)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${copy.absolutePath}")
        return try {
            block(driver)
        } finally {
            driver.close()
            copy.delete()
        }
    }

    /**
     * Tables with their columns (name, type, not null, default, primary key position) and indexes with their
     * columns, uniqueness and partial-index condition. Column order is left out: migrations append columns, and the
     * generated queries name every column.
     */
    private fun schemaOf(driver: SqlDriver): Map<String, List<String>> {
        val objects = query(driver, "SELECT type, name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'") {
            listOf(it.getString(0)!!, it.getString(1)!!, it.getString(2).orEmpty())
        }
        return objects.associate { (type, name, sql) ->
            val details = when (type) {
                "table" -> query(driver, "PRAGMA table_info('$name')") {
                    "${it.getString(1)} ${it.getString(2)} notnull=${it.getLong(3)} default=${it.getString(4)} pk=${it.getLong(5)}"
                }.sorted()
                "index" -> query(driver, "PRAGMA index_info('$name')") { "${it.getLong(0)}:${it.getString(2)}" } +
                    "where=${sql.substringAfter(" WHERE ", "").trim()}" +
                    "unique=${sql.trimStart().startsWith("CREATE UNIQUE", ignoreCase = true)}"
                else -> listOf(sql)
            }
            "$type $name" to details
        }
    }

    private fun <T> query(driver: SqlDriver, sql: String, row: (app.cash.sqldelight.db.SqlCursor) -> T): List<T> =
        driver.executeQuery(null, sql, { cursor ->
            val rows = mutableListOf<T>()
            while (cursor.next().value) rows += row(cursor)
            QueryResult.Value(rows)
        }, 0).value
}
