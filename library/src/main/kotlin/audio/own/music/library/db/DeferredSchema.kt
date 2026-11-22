package audio.own.music.library.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

/**
 * A first snapshot of a big library writes hundreds of thousands of rows. Every secondary index
 * and the search table's triggers would be updated row by row, out of order; building them once
 * at the end is several times faster. [defer] drops them and keeps their statements in
 * `deferred_schema`, [restore] makes them again (and fills the search table).
 *
 * Restoring also runs whenever the database opens and before a migration, so a snapshot cut off
 * by the app being killed never leaves the catalogue without its indexes for long, and a
 * migration always finds the schema Room expects.
 */
internal object DeferredSchema {
    /** The tables a snapshot fills; the small ones (artists, genres, playlists) keep theirs. */
    private val TABLES = listOf("track", "track_credit", "track_genre", "album", "album_credit")

    private const val FTS_TRIGGERS = "room_fts_content_sync_track_fts_"

    /** Drops the indexes and triggers, keeping how to make them; in a transaction of its own. */
    fun defer(db: SupportSQLiteDatabase) {
        db.beginTransaction()
        try {
            val found = ArrayList<Triple<String, String, String>>()
            db.query(
                """SELECT type, name, sql FROM sqlite_master WHERE sql IS NOT NULL
                AND tbl_name IN (${TABLES.joinToString { "'$it'" }})
                AND ((type = 'index' AND name LIKE 'index%') OR (type = 'trigger' AND name LIKE '$FTS_TRIGGERS%'))""",
            ).use { c ->
                while (c.moveToNext()) found += Triple(c.getString(0), c.getString(1), c.getString(2))
            }
            for ((type, name, sql) in found) {
                db.execSQL("INSERT OR REPLACE INTO deferred_schema (name, sql) VALUES (?, ?)", arrayOf(name, sql))
                db.execSQL("DROP ${if (type == "index") "INDEX" else "TRIGGER"} IF EXISTS `$name`")
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Makes again what [defer] dropped; true when there was something to make. */
    fun restore(db: SupportSQLiteDatabase): Boolean {
        val kept = ArrayList<Pair<String, String>>()
        try {
            db.query("SELECT name, sql FROM deferred_schema").use { c ->
                while (c.moveToNext()) kept += c.getString(0) to c.getString(1)
            }
        } catch (e: android.database.SQLException) {
            // A version before 4: nothing was ever deferred.
            return false
        }
        if (kept.isEmpty()) return false
        db.beginTransaction()
        try {
            for ((_, sql) in kept) db.execSQL(sql)
            // Songs written while the triggers were away are missing from the search table.
            if (kept.any { it.first.startsWith(FTS_TRIGGERS) }) db.execSQL("INSERT INTO track_fts(track_fts) VALUES('rebuild')")
            db.execSQL("DELETE FROM deferred_schema")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return true
    }

    fun isDeferred(db: SupportSQLiteDatabase): Boolean =
        db.query("SELECT EXISTS(SELECT 1 FROM deferred_schema)").use { it.moveToFirst() && it.getInt(0) == 1 }

    /**
     * Room's open helper, with [restore] before any migration: Room checks each table's indexes
     * after migrating, and a snapshot cut off before an app update would otherwise fail that.
     */
    class OpenHelperFactory(private val inner: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory()) : SupportSQLiteOpenHelper.Factory {
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            val room = configuration.callback
            val callback = object : SupportSQLiteOpenHelper.Callback(room.version) {
                override fun onConfigure(db: SupportSQLiteDatabase) = room.onConfigure(db)
                override fun onCreate(db: SupportSQLiteDatabase) = room.onCreate(db)
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    restore(db)
                    room.onUpgrade(db, oldVersion, newVersion)
                }
                override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = room.onDowngrade(db, oldVersion, newVersion)
                override fun onOpen(db: SupportSQLiteDatabase) = room.onOpen(db)
                override fun onCorruption(db: SupportSQLiteDatabase) = room.onCorruption(db)
            }
            return inner.create(
                SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                    .name(configuration.name)
                    .callback(callback)
                    .noBackupDirectory(configuration.useNoBackupDirectory)
                    .allowDataLossOnRecovery(configuration.allowDataLossOnRecovery)
                    .build(),
            )
        }
    }
}
