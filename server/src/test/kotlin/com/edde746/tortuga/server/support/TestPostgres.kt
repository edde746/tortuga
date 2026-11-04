package com.edde746.tortuga.server.support

import com.edde746.tortuga.server.db.Database
import java.io.StringWriter
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.postgresql.PGConnection
import org.testcontainers.postgresql.PostgreSQLContainer

/** One Postgres container per test JVM (Testcontainers), migrated once; [reset] empties every table between tests. */
object TestPostgres {
    private val container: PostgreSQLContainer by lazy {
        PostgreSQLContainer(System.getProperty("tortuga.test.postgresImage", "postgres:18-alpine")).also { it.start() }
    }

    /** The pool's size: a test that fills the pool knows how many requests do. */
    const val POOL_SIZE = 8

    val database: Database by lazy { connect(POOL_SIZE) }

    /**
     * Another pool of [poolSize] connections on the same database, for a test that may break its pool; through [relay]
     * for one that cuts it off from the database.
     */
    fun connect(poolSize: Int, relay: TcpRelay? = null): Database {
        val url = relay?.let { "jdbc:postgresql://127.0.0.1:${it.localPort}/${container.databaseName}" } ?: container.jdbcUrl
        return Database.connect(url, container.username, container.password, poolSize)
    }

    /** A relay to the database's port, for [connect]. */
    fun relay(): TcpRelay = TcpRelay(container.host, container.firstMappedPort)

    /**
     * A new database of its own in the container, migrated up to [version] only, as a server of that version left its
     * schema: a test writes the rows that server would have, then [Upgrade.connect]s, which migrates the rest.
     */
    fun databaseAt(version: String): Upgrade {
        val name = "upgrade_" + UUID.randomUUID().toString().replace("-", "")
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use {
            it.createStatement().execute("CREATE DATABASE $name")
        }
        val upgrade = Upgrade("jdbc:postgresql://${container.host}:${container.firstMappedPort}/$name")
        Flyway.configure().dataSource(upgrade.url, container.username, container.password)
            .locations("classpath:db/migration").target(version).load().migrate()
        return upgrade
    }

    /** A database from [databaseAt]. */
    class Upgrade(val url: String) {
        /** A connection of its own, committing each statement. */
        fun connection(): Connection = DriverManager.getConnection(url, container.username, container.password)

        /** The server's pool on it: migrated to the newest version first, as at a start. */
        fun connect(): Database = Database.connect(url, container.username, container.password, 2)
    }

    /** Every application table of [db] (not Flyway's history). */
    fun tables(db: Database = database): List<String> = runBlocking {
        db.transaction {
            query(
                """
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history'
                ORDER BY table_name
                """,
            ) { it.getString(1) }
        }
    }

    fun reset() = runBlocking {
        val tables = tables()
        database.transaction { update("TRUNCATE ${tables.joinToString()} CASCADE") }
    }

    /**
     * Every application table of [db] as `COPY … TO STDOUT` writes it (`bytea` as `\x…` hex), keyed by table: the rows
     * of a pg_dump.
     */
    fun dump(db: Database = database): Map<String, String> = runBlocking {
        val tables = tables(db)
        db.transaction {
            val copy = connection.unwrap(PGConnection::class.java).copyAPI
            tables.associateWith { table -> StringWriter().also { copy.copyOut("COPY $table TO STDOUT", it) }.toString() }
        }
    }

    fun count(table: String): Int = runBlocking {
        database.transaction { queryOne("SELECT count(*) FROM $table") { it.getInt(1) }!! }
    }
}
