package audio.own.music.library.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction

/**
 * Every server's catalogue in one database. The schema is exported to `library/schemas`, so each
 * version can be checked and a migration written against the one before; until 1.0 ships there
 * is only version 1.
 */
@Database(
    version = 1,
    exportSchema = true,
    entities = [
        ArtistRow::class, AlbumRow::class, TrackRow::class, GenreRow::class, TrackGenreRow::class,
        AlbumCreditRow::class, TrackCreditRow::class, PlaylistRow::class, PlaylistEntryRow::class,
        FavouriteRow::class, RatingRow::class, FeedStateRow::class,
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
        fun open(context: Context): CatalogueDatabase = Room.databaseBuilder(
            context.applicationContext,
            CatalogueDatabase::class.java,
            context.noBackupFilesDir.resolve(FILE).path,
        ).build()
    }
}
