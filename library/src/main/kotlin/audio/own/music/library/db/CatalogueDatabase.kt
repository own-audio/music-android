package audio.own.music.library.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Every server's catalogue in one database. The schema is exported to `library/schemas`, so each
 * version can be checked and a migration written against the one before.
 *
 * Version 2 added the file format, bit rate and gain columns Subsonic and Jellyfin catalogues
 * fill; adding nullable columns is a migration Room writes itself. Version 3 widened the
 * songs' title index with the searched columns (offline search of a 600k-song library).
 * Version 4 added the songs' full-text table ([TrackFtsRow]) and [DeferredSchemaRow]; its
 * migration fills the search table from the songs already kept.
 */
@Database(
    version = 4,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
    entities = [
        ArtistRow::class, AlbumRow::class, TrackRow::class, GenreRow::class, TrackGenreRow::class,
        AlbumCreditRow::class, TrackCreditRow::class, PlaylistRow::class, PlaylistEntryRow::class,
        FavouriteRow::class, RatingRow::class, FeedStateRow::class, TrackFtsRow::class, DeferredSchemaRow::class,
    ],
)
abstract class CatalogueDatabase : RoomDatabase() {
    abstract fun catalogue(): CatalogueDao

    /** Everything kept for a server, for when it is removed or another account signs in to it. */
    suspend fun clearServer(serverId: String) = withTransaction {
        with(catalogue()) {
            clearArtists(serverId)
            clearAlbums(serverId)
            clearAlbumCredits(serverId)
            clearTracks(serverId)
            clearTrackCredits(serverId)
            clearTrackGenres(serverId)
            clearGenres(serverId)
            clearPlaylists(serverId)
            clearPlaylistEntries(serverId)
            clearFavourites(serverId)
            clearRatings(serverId)
            clearFeedState(serverId)
        }
    }

    companion object {
        const val FILE = "catalogue.db"

        /**
         * In the app's no-backup files: it is rebuilt from the server, so it has no place in a
         * cloud backup, and Android never clears it under storage pressure the way it does a cache.
         */
        fun open(context: Context): CatalogueDatabase = builder(context, context.noBackupFilesDir.resolve(FILE).path).build()

        fun builder(context: Context, path: String): RoomDatabase.Builder<CatalogueDatabase> =
            Room.databaseBuilder(context.applicationContext, CatalogueDatabase::class.java, path).configured()

        /** The migrations, and the indexes a cut-off first snapshot dropped made again on open. */
        fun RoomDatabase.Builder<CatalogueDatabase>.configured(): RoomDatabase.Builder<CatalogueDatabase> = this
            .addMigrations(MIGRATION_3_4)
            .openHelperFactory(DeferredSchema.OpenHelperFactory())
            .addCallback(
                object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        DeferredSchema.restore(db)
                    }
                },
            )

        /** The songs' search table, filled from the songs already kept. Room adds its triggers after. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `track_fts` USING FTS4(`title` TEXT NOT NULL, `artistLine` TEXT, `albumTitle` TEXT, tokenize=unicode61, content=`track`)",
                )
                db.execSQL("CREATE TABLE IF NOT EXISTS `deferred_schema` (`name` TEXT NOT NULL, `sql` TEXT NOT NULL, PRIMARY KEY(`name`))")
                db.execSQL("INSERT INTO track_fts(track_fts) VALUES('rebuild')")
            }
        }
    }
}
