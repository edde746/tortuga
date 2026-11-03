package com.edde746.tortuga.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

/**
 * The Android database, in the app's private databases directory ([Context.getDatabasePath]). Android's backup and
 * device-to-device transfer carry it, addon links and their keys included (`res/xml/backup_rules.xml`).
 */
actual class DatabaseFactory private constructor(context: Context, private val name: String?) {
    private val context: Context = context.applicationContext ?: context

    constructor(context: Context) : this(context, DATABASE_FILE_NAME)

    internal actual fun createDriver(schema: SqlSchema<QueryResult.Value<Unit>>): SqlDriver = AndroidSqliteDriver(
        schema = schema,
        context = context,
        name = name,
        callback = object : AndroidSqliteDriver.Callback(schema) {
            // WAL lets query flows read while a write is in progress. (In-memory databases stay in memory mode.)
            override fun onConfigure(db: SupportSQLiteDatabase) {
                if (name != null) db.enableWriteAheadLogging()
            }
        },
    )

    companion object {
        /** A private in-memory database, gone when [LocalData.close] closes it. */
        fun inMemory(context: Context): DatabaseFactory = DatabaseFactory(context, null)

        /**
         * A database of its own named [name] in the same directory, apart from the app's (a debug build's kept
         * development session); the app's data going (a reset, an uninstall) takes it along.
         */
        fun named(context: Context, name: String): DatabaseFactory {
            require(name != DATABASE_FILE_NAME) { "$name is the app's own database" }
            return DatabaseFactory(context, name)
        }
    }
}
