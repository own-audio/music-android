package audio.own.music.library.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

// Browsing reads one page at a time (LIMIT/OFFSET); nothing loads a whole 600k-track table.
// The marks (favourite, rating) are joined on per row through their primary keys.

private const val TRACK_MARKS = """
    EXISTS(SELECT 1 FROM favourite f WHERE f.serverId = t.serverId AND f.kind = 'track' AND f.id = t.id) AS isFavorite,
    (SELECT r.rating FROM rating r WHERE r.serverId = t.serverId AND r.kind = 'track' AND r.id = t.id) AS rating,
    (SELECT al.image FROM album al WHERE al.serverId = t.serverId AND al.id = t.albumId) AS albumImage"""

private const val ALBUM_MARKS = """
    EXISTS(SELECT 1 FROM favourite f WHERE f.serverId = al.serverId AND f.kind = 'album' AND f.id = al.id) AS isFavorite,
    (SELECT r.rating FROM rating r WHERE r.serverId = al.serverId AND r.kind = 'album' AND r.id = al.id) AS rating"""

// `+c.albumId` keeps SQLite on the (serverId, contributorId, role) index: for COUNT(DISTINCT
// albumId) it otherwise picks the primary key's, ordered by album, and reads every credit of
// the server once per artist (minutes for 6,000 artists of a 600k-song library on a phone).
private const val ARTIST_COLUMNS = """a.*,
    (SELECT COUNT(DISTINCT +c.albumId) FROM album_credit c WHERE c.serverId = a.serverId AND c.contributorId = a.id AND c.role = 'artist') AS albumCount,
    EXISTS(SELECT 1 FROM favourite f WHERE f.serverId = a.serverId AND f.kind = 'contributor' AND f.id = a.id) AS isFavorite"""

/**
 * Artists are the contributors credited as one, on an album or a track; authors, narrators and
 * hosts are the same records but not this app's, and a featured guest only is not an artist.
 */
private const val IS_ARTIST = """(
    EXISTS(SELECT 1 FROM album_credit c WHERE c.serverId = a.serverId AND c.contributorId = a.id AND c.role = 'artist')
    OR EXISTS(SELECT 1 FROM track_credit c WHERE c.serverId = a.serverId AND c.contributorId = a.id AND c.role = 'artist'))"""

@Dao
interface CatalogueDao {
    // region Writing (the sync)

    @Upsert suspend fun upsertArtists(rows: List<ArtistRow>)
    @Upsert suspend fun upsertAlbums(rows: List<AlbumRow>)
    @Upsert suspend fun upsertTracks(rows: List<TrackRow>)
    @Upsert suspend fun upsertGenres(rows: List<GenreRow>)
    @Upsert suspend fun upsertTrackGenres(rows: List<TrackGenreRow>)
    @Upsert suspend fun upsertAlbumCredits(rows: List<AlbumCreditRow>)
    @Upsert suspend fun upsertTrackCredits(rows: List<TrackCreditRow>)
    @Upsert suspend fun upsertPlaylists(rows: List<PlaylistRow>)
    @Upsert suspend fun upsertPlaylistEntries(rows: List<PlaylistEntryRow>)
    @Upsert suspend fun upsertFavourites(rows: List<FavouriteRow>)
    @Upsert suspend fun upsertRatings(rows: List<RatingRow>)
    @Upsert suspend fun upsertFeedState(row: FeedStateRow)

    @Query("SELECT * FROM feed_state WHERE serverId = :serverId")
    suspend fun feedState(serverId: String): FeedStateRow?

    @Query("DELETE FROM artist WHERE serverId = :serverId AND id IN (:ids)")
    suspend fun deleteArtists(serverId: String, ids: List<String>)

    @Query("DELETE FROM album WHERE serverId = :serverId AND id IN (:ids)")
    suspend fun deleteAlbums(serverId: String, ids: List<String>)

    @Query("DELETE FROM album_credit WHERE serverId = :serverId AND albumId IN (:albumIds)")
    suspend fun deleteAlbumCredits(serverId: String, albumIds: List<String>)

    @Query("DELETE FROM track WHERE serverId = :serverId AND id IN (:ids)")
    suspend fun deleteTracks(serverId: String, ids: List<String>)

    @Query("DELETE FROM track_credit WHERE serverId = :serverId AND trackId IN (:trackIds)")
    suspend fun deleteTrackCredits(serverId: String, trackIds: List<String>)

    @Query("DELETE FROM track_genre WHERE serverId = :serverId AND trackId IN (:trackIds)")
    suspend fun deleteTrackGenres(serverId: String, trackIds: List<String>)

    @Query("DELETE FROM genre WHERE serverId = :serverId AND id IN (:ids)")
    suspend fun deleteGenres(serverId: String, ids: List<String>)

    @Query("DELETE FROM playlist WHERE serverId = :serverId AND id IN (:ids)")
    suspend fun deletePlaylists(serverId: String, ids: List<String>)

    @Query("DELETE FROM playlist_entry WHERE serverId = :serverId AND playlistId = :playlistId")
    suspend fun deletePlaylistEntries(serverId: String, playlistId: String)

    @Query("DELETE FROM favourite WHERE serverId = :serverId AND kind = :kind AND id = :id")
    suspend fun deleteFavourite(serverId: String, kind: String, id: String)

    @Query("DELETE FROM rating WHERE serverId = :serverId AND kind = :kind AND id = :id")
    suspend fun deleteRating(serverId: String, kind: String, id: String)

    // A finished snapshot drops whatever an older one wrote and this one didn't.
    @Query("DELETE FROM artist WHERE serverId = :serverId AND gen < :gen") suspend fun pruneArtists(serverId: String, gen: Long)
    @Query("DELETE FROM album WHERE serverId = :serverId AND gen < :gen") suspend fun pruneAlbums(serverId: String, gen: Long)
    @Query("DELETE FROM album_credit WHERE serverId = :serverId AND gen < :gen") suspend fun pruneAlbumCredits(serverId: String, gen: Long)
    @Query("DELETE FROM track WHERE serverId = :serverId AND gen < :gen") suspend fun pruneTracks(serverId: String, gen: Long)
    @Query("DELETE FROM track_credit WHERE serverId = :serverId AND gen < :gen") suspend fun pruneTrackCredits(serverId: String, gen: Long)
    @Query("DELETE FROM track_genre WHERE serverId = :serverId AND gen < :gen") suspend fun pruneTrackGenres(serverId: String, gen: Long)
    @Query("DELETE FROM genre WHERE serverId = :serverId AND gen < :gen") suspend fun pruneGenres(serverId: String, gen: Long)
    @Query("DELETE FROM playlist WHERE serverId = :serverId AND gen < :gen") suspend fun prunePlaylists(serverId: String, gen: Long)
    @Query("DELETE FROM favourite WHERE serverId = :serverId AND gen < :gen") suspend fun pruneFavourites(serverId: String, gen: Long)
    @Query("DELETE FROM rating WHERE serverId = :serverId AND gen < :gen") suspend fun pruneRatings(serverId: String, gen: Long)

    @Query("DELETE FROM playlist_entry WHERE serverId = :serverId AND playlistId NOT IN (SELECT id FROM playlist WHERE serverId = :serverId)")
    suspend fun pruneOrphanEntries(serverId: String)

    // A removed server leaves nothing behind.
    @Query("DELETE FROM artist WHERE serverId = :serverId") suspend fun clearArtists(serverId: String)
    @Query("DELETE FROM album WHERE serverId = :serverId") suspend fun clearAlbums(serverId: String)
    @Query("DELETE FROM album_credit WHERE serverId = :serverId") suspend fun clearAlbumCredits(serverId: String)
    @Query("DELETE FROM track WHERE serverId = :serverId") suspend fun clearTracks(serverId: String)
    @Query("DELETE FROM track_credit WHERE serverId = :serverId") suspend fun clearTrackCredits(serverId: String)
    @Query("DELETE FROM track_genre WHERE serverId = :serverId") suspend fun clearTrackGenres(serverId: String)
    @Query("DELETE FROM genre WHERE serverId = :serverId") suspend fun clearGenres(serverId: String)
    @Query("DELETE FROM playlist WHERE serverId = :serverId") suspend fun clearPlaylists(serverId: String)
    @Query("DELETE FROM playlist_entry WHERE serverId = :serverId") suspend fun clearPlaylistEntries(serverId: String)
    @Query("DELETE FROM favourite WHERE serverId = :serverId") suspend fun clearFavourites(serverId: String)
    @Query("DELETE FROM rating WHERE serverId = :serverId") suspend fun clearRatings(serverId: String)
    @Query("DELETE FROM feed_state WHERE serverId = :serverId") suspend fun clearFeedState(serverId: String)

    // Keeping the copied names right.

    @Query("SELECT id, name FROM artist WHERE serverId = :serverId AND id IN (:ids)")
    suspend fun artistNames(serverId: String, ids: List<String>): List<IdName>

    @Query("SELECT id, title AS name FROM album WHERE serverId = :serverId AND id IN (:ids)")
    suspend fun albumTitles(serverId: String, ids: List<String>): List<IdName>

    @Query("SELECT id, name FROM genre WHERE serverId = :serverId AND id IN (:ids)")
    suspend fun genreNames(serverId: String, ids: List<String>): List<IdName>

    @Query("SELECT DISTINCT albumId FROM album_credit WHERE serverId = :serverId AND contributorId = :contributorId")
    suspend fun albumsCrediting(serverId: String, contributorId: String): List<String>

    @Query("SELECT DISTINCT trackId FROM track_credit WHERE serverId = :serverId AND contributorId = :contributorId")
    suspend fun tracksCrediting(serverId: String, contributorId: String): List<String>

    @Query(
        """SELECT c.ord, c.contributorId, c.role, c.joinPhrase, c.creditedAs, a.name FROM album_credit c
        LEFT JOIN artist a ON a.serverId = c.serverId AND a.id = c.contributorId
        WHERE c.serverId = :serverId AND c.albumId = :albumId ORDER BY c.ord""",
    )
    suspend fun albumCredits(serverId: String, albumId: String): List<NamedCredit>

    @Query(
        """SELECT c.ord, c.contributorId, c.role, c.joinPhrase, c.creditedAs, a.name FROM track_credit c
        LEFT JOIN artist a ON a.serverId = c.serverId AND a.id = c.contributorId
        WHERE c.serverId = :serverId AND c.trackId = :trackId ORDER BY c.ord""",
    )
    suspend fun trackCredits(serverId: String, trackId: String): List<NamedCredit>

    @Query("UPDATE album SET artistLine = :line, artistId = :artistId WHERE serverId = :serverId AND id = :id")
    suspend fun setAlbumLine(serverId: String, id: String, line: String?, artistId: String?)

    @Query("UPDATE track SET artistLine = :line, artistId = :artistId WHERE serverId = :serverId AND id = :id")
    suspend fun setTrackLine(serverId: String, id: String, line: String?, artistId: String?)

    @Query("UPDATE track SET albumTitle = :title WHERE serverId = :serverId AND albumId = :albumId")
    suspend fun setAlbumTitleOnTracks(serverId: String, albumId: String, title: String)

    @Query(
        """UPDATE track SET genreName = :name WHERE serverId = :serverId
        AND id IN (SELECT trackId FROM track_genre WHERE serverId = :serverId AND genreId = :genreId AND ord = 0)""",
    )
    suspend fun setGenreNameOnTracks(serverId: String, genreId: String, name: String)

    @Query("UPDATE track SET image = :image WHERE serverId = :serverId AND id = :id") suspend fun setTrackImage(serverId: String, id: String, image: String?)
    @Query("UPDATE album SET image = :image WHERE serverId = :serverId AND id = :id") suspend fun setAlbumImage(serverId: String, id: String, image: String?)
    @Query("UPDATE artist SET image = :image WHERE serverId = :serverId AND id = :id") suspend fun setArtistImage(serverId: String, id: String, image: String?)
    @Query("UPDATE playlist SET image = :image WHERE serverId = :serverId AND id = :id") suspend fun setPlaylistImage(serverId: String, id: String, image: String?)
    @Query("UPDATE track SET visibility = :visibility WHERE serverId = :serverId AND id IN (:ids)")
    suspend fun setTrackVisibility(serverId: String, ids: List<String>, visibility: String)

    /** An album's tracks that [ownerId] owns, in album order: what "Share My Songs" and a new album cover change. */
    @Query(
        """SELECT id FROM track WHERE serverId = :serverId AND albumId = :albumId AND ownerId = :ownerId
        ORDER BY discNumber IS NULL, discNumber, trackNumber IS NULL, trackNumber, title""",
    )
    suspend fun ownTracksOfAlbum(serverId: String, albumId: String, ownerId: String): List<String>

    // endregion

    // region Browsing

    @Query("SELECT $ARTIST_COLUMNS FROM artist a WHERE a.serverId = :serverId AND $IS_ARTIST ORDER BY a.name LIMIT :limit OFFSET :offset")
    suspend fun artists(serverId: String, limit: Int = Int.MAX_VALUE, offset: Int = 0): List<ArtistView>

    @Query("SELECT $ARTIST_COLUMNS FROM artist a WHERE a.serverId = :serverId AND a.id IN (:ids)")
    suspend fun artistsByIds(serverId: String, ids: List<String>): List<ArtistView>

    // The browsing pages pick their rows first by rowid alone, which an index answers without
    // touching the table, and only then read those rows and their marks: OFFSET otherwise reads
    // every skipped row in full (seconds deep into a 600k-song list). Rows that sort the same
    // keep the order they were written in (rowid), which the indexes carry for free.

    @Query(
        """SELECT al.*, $ALBUM_MARKS FROM album al WHERE al.rowid IN
        (SELECT rowid FROM album WHERE serverId = :serverId ORDER BY title, rowid LIMIT :limit OFFSET :offset)
        ORDER BY al.title, al.rowid""",
    )
    suspend fun albumsByName(serverId: String, limit: Int, offset: Int): List<AlbumView>

    /** By the artist line, a discography's order under each; albums without one come last. */
    suspend fun albumsByArtistName(serverId: String, limit: Int, offset: Int): List<AlbumView> {
        // Two queries rather than ORDER BY artistLine IS NULL, which no index can serve.
        val named = albumsWithArtistLine(serverId, limit, offset)
        if (named.size == limit) return named
        val skipped = maxOf(offset - albumsWithArtistLineCount(serverId), 0)
        return named + albumsWithoutArtistLine(serverId, limit - named.size, skipped)
    }

    @Query(
        """SELECT al.*, $ALBUM_MARKS FROM album al WHERE al.rowid IN
        (SELECT rowid FROM album WHERE serverId = :serverId AND artistLine IS NOT NULL ORDER BY artistLine, year, title, rowid LIMIT :limit OFFSET :offset)
        ORDER BY al.artistLine, al.year, al.title, al.rowid""",
    )
    suspend fun albumsWithArtistLine(serverId: String, limit: Int, offset: Int): List<AlbumView>

    @Query("SELECT COUNT(*) FROM album WHERE serverId = :serverId AND artistLine IS NOT NULL")
    suspend fun albumsWithArtistLineCount(serverId: String): Int

    @Query(
        """SELECT al.*, $ALBUM_MARKS FROM album al WHERE al.serverId = :serverId AND al.artistLine IS NULL
        ORDER BY al.year, al.title, al.rowid LIMIT :limit OFFSET :offset""",
    )
    suspend fun albumsWithoutArtistLine(serverId: String, limit: Int, offset: Int): List<AlbumView>

    @Query(
        """SELECT al.*, $ALBUM_MARKS FROM album al WHERE al.rowid IN
        (SELECT rowid FROM album WHERE serverId = :serverId ORDER BY createdAtMs DESC, rowid DESC LIMIT :limit OFFSET :offset)
        ORDER BY al.createdAtMs DESC, al.rowid DESC""",
    )
    suspend fun albumsNewest(serverId: String, limit: Int, offset: Int): List<AlbumView>

    /**
     * A shuffle that stays the same from page to page for one [seed]: a new seed (offset 0) is a
     * new shuffle. ORDER BY random() would repeat and skip albums across pages.
     */
    @Query(
        """SELECT al.*, $ALBUM_MARKS FROM album al WHERE al.serverId = :serverId
        ORDER BY ((al.rowid + :seed) * 2654435761) % 4294967291, al.id LIMIT :limit OFFSET :offset""",
    )
    suspend fun albumsShuffled(serverId: String, seed: Long, limit: Int, offset: Int): List<AlbumView>

    /** Newest marked first, as they were marked. */
    @Query(
        """SELECT al.*, 1 AS isFavorite,
        (SELECT r.rating FROM rating r WHERE r.serverId = al.serverId AND r.kind = 'album' AND r.id = al.id) AS rating
        FROM favourite f JOIN album al ON al.serverId = f.serverId AND al.id = f.id
        WHERE f.serverId = :serverId AND f.kind = 'album' ORDER BY f.createdAtMs DESC LIMIT :limit OFFSET :offset""",
    )
    suspend fun albumsFavourite(serverId: String, limit: Int, offset: Int): List<AlbumView>

    @Query(
        """SELECT al.*, EXISTS(SELECT 1 FROM favourite f WHERE f.serverId = al.serverId AND f.kind = 'album' AND f.id = al.id) AS isFavorite,
        r.rating AS rating
        FROM rating r JOIN album al ON al.serverId = r.serverId AND al.id = r.id
        WHERE r.serverId = :serverId AND r.kind = 'album' ORDER BY r.rating DESC, r.updatedAtMs DESC LIMIT :limit OFFSET :offset""",
    )
    suspend fun albumsHighestRated(serverId: String, limit: Int, offset: Int): List<AlbumView>

    /** Every album crediting the artist as one, oldest first as a discography reads. */
    @Query(
        """SELECT al.*, $ALBUM_MARKS FROM album al WHERE al.serverId = :serverId AND al.id IN
        (SELECT c.albumId FROM album_credit c WHERE c.serverId = :serverId AND c.contributorId = :artistId AND c.role = 'artist')
        ORDER BY al.year IS NULL, al.year, al.title""",
    )
    suspend fun albumsOfArtist(serverId: String, artistId: String): List<AlbumView>

    @Query(
        """SELECT al.*, $ALBUM_MARKS FROM album al WHERE al.serverId = :serverId AND al.id IN
        (SELECT g.albumId FROM track_genre g WHERE g.serverId = :serverId AND g.genreId = :genreId)
        ORDER BY al.title LIMIT :limit""",
    )
    suspend fun albumsOfGenre(serverId: String, genreId: String, limit: Int): List<AlbumView>

    @Query("SELECT al.*, $ALBUM_MARKS FROM album al WHERE al.serverId = :serverId AND al.id IN (:ids)")
    suspend fun albumsByIds(serverId: String, ids: List<String>): List<AlbumView>

    @Query("SELECT al.*, $ALBUM_MARKS FROM album al WHERE al.serverId = :serverId AND al.id = :id")
    suspend fun album(serverId: String, id: String): AlbumView?

    /** In disc and track order; a track without numbers after those with them. */
    @Query(
        """SELECT t.*, $TRACK_MARKS FROM track t WHERE t.serverId = :serverId AND t.albumId = :albumId
        ORDER BY t.discNumber IS NULL, t.discNumber, t.trackNumber IS NULL, t.trackNumber, t.title""",
    )
    suspend fun tracksOfAlbum(serverId: String, albumId: String): List<TrackView>

    @Query(
        """SELECT t.*, $TRACK_MARKS FROM track t WHERE t.rowid IN
        (SELECT rowid FROM track WHERE serverId = :serverId ORDER BY title, rowid LIMIT :limit OFFSET :offset)
        ORDER BY t.title, t.rowid""",
    )
    suspend fun tracksByTitle(serverId: String, limit: Int, offset: Int): List<TrackView>

    @Query("SELECT t.*, $TRACK_MARKS FROM track t WHERE t.serverId = :serverId AND t.id = :id")
    suspend fun track(serverId: String, id: String): TrackView?

    @Query("SELECT t.*, $TRACK_MARKS FROM track t WHERE t.serverId = :serverId AND t.id IN (:ids)")
    suspend fun tracksByIds(serverId: String, ids: List<String>): List<TrackView>

    @Query("SELECT t.*, $TRACK_MARKS FROM track t WHERE t.serverId = :serverId ORDER BY random() LIMIT :limit")
    suspend fun randomTracks(serverId: String, limit: Int): List<TrackView>

    @Query(
        """SELECT t.*, $TRACK_MARKS FROM track t WHERE t.serverId = :serverId
        AND t.id IN (SELECT trackId FROM track_genre WHERE serverId = :serverId AND genreId = :genreId)
        ORDER BY random() LIMIT :limit""",
    )
    suspend fun randomTracksOfGenre(serverId: String, genreId: String, limit: Int): List<TrackView>

    /** Every genre with its counts (the API gives none), from track_genre's covering index alone. */
    @Query(
        """SELECT g.id, g.name,
        (SELECT COUNT(*) FROM track_genre tg WHERE tg.serverId = g.serverId AND tg.genreId = g.id) AS songCount,
        (SELECT COUNT(DISTINCT tg.albumId) FROM track_genre tg WHERE tg.serverId = g.serverId AND tg.genreId = g.id) AS albumCount
        FROM genre g WHERE g.serverId = :serverId ORDER BY g.name""",
    )
    suspend fun genres(serverId: String): List<GenreView>

    @Query("SELECT id FROM genre WHERE serverId = :serverId AND name = :name LIMIT 1")
    suspend fun genreIdNamed(serverId: String, name: String): String?

    // Search: [pattern] is "%text%" with % and _ escaped by '\'.

    /**
     * A LIKE with a leading % reads every song; the inner query reads only the (serverId,
     * title, artistLine, albumTitle) index, a fraction of the table's size, and the rows found
     * are read after.
     */
    @Query(
        """SELECT t.*, $TRACK_MARKS FROM track t WHERE t.rowid IN
        (SELECT rowid FROM track WHERE serverId = :serverId
        AND (title LIKE :pattern ESCAPE '\' OR artistLine LIKE :pattern ESCAPE '\' OR albumTitle LIKE :pattern ESCAPE '\')
        ORDER BY title LIMIT :limit)
        ORDER BY t.title""",
    )
    suspend fun searchTracks(serverId: String, pattern: String, limit: Int): List<TrackView>

    @Query(
        """SELECT al.*, $ALBUM_MARKS FROM album al WHERE al.serverId = :serverId
        AND (al.title LIKE :pattern ESCAPE '\' OR al.artistLine LIKE :pattern ESCAPE '\') ORDER BY al.title LIMIT :limit""",
    )
    suspend fun searchAlbums(serverId: String, pattern: String, limit: Int): List<AlbumView>

    @Query("""SELECT $ARTIST_COLUMNS FROM artist a WHERE a.serverId = :serverId AND a.name LIKE :pattern ESCAPE '\' AND $IS_ARTIST ORDER BY a.name LIMIT :limit""")
    suspend fun searchArtists(serverId: String, pattern: String, limit: Int): List<ArtistView>

    // Favourites, newest marked first.

    @Query(
        """SELECT t.*, $TRACK_MARKS FROM favourite fav JOIN track t ON t.serverId = fav.serverId AND t.id = fav.id
        WHERE fav.serverId = :serverId AND fav.kind = 'track' ORDER BY fav.createdAtMs DESC""",
    )
    suspend fun favouriteTracks(serverId: String): List<TrackView>

    @Query(
        """SELECT $ARTIST_COLUMNS FROM favourite fav JOIN artist a ON a.serverId = fav.serverId AND a.id = fav.id
        WHERE fav.serverId = :serverId AND fav.kind = 'contributor' ORDER BY fav.createdAtMs DESC""",
    )
    suspend fun favouriteArtists(serverId: String): List<ArtistView>

    @Query("SELECT * FROM favourite WHERE serverId = :serverId")
    suspend fun favourites(serverId: String): List<FavouriteRow>

    @Query("SELECT * FROM rating WHERE serverId = :serverId")
    suspend fun ratings(serverId: String): List<RatingRow>

    // Playlists.

    @Query("SELECT * FROM playlist WHERE serverId = :serverId ORDER BY name")
    suspend fun playlists(serverId: String): List<PlaylistRow>

    @Query("SELECT * FROM playlist WHERE serverId = :serverId AND id = :id")
    suspend fun playlist(serverId: String, id: String): PlaylistRow?

    @Query(
        """SELECT t.*, e.entryId AS entryId, $TRACK_MARKS FROM playlist_entry e
        JOIN track t ON t.serverId = e.serverId AND t.id = e.trackId
        WHERE e.serverId = :serverId AND e.playlistId = :playlistId ORDER BY e.position""",
    )
    suspend fun playlistTracks(serverId: String, playlistId: String): List<PlaylistTrackView>

    @Query("SELECT * FROM playlist_entry WHERE serverId = :serverId AND playlistId = :playlistId ORDER BY position")
    suspend fun playlistEntryRows(serverId: String, playlistId: String): List<PlaylistEntryRow>

    // What an id is, and its picture link.

    @Query(
        """SELECT 'track' FROM track WHERE serverId = :serverId AND id = :id
        UNION ALL SELECT 'album' FROM album WHERE serverId = :serverId AND id = :id
        UNION ALL SELECT 'contributor' FROM artist WHERE serverId = :serverId AND id = :id
        UNION ALL SELECT 'playlist' FROM playlist WHERE serverId = :serverId AND id = :id LIMIT 1""",
    )
    suspend fun kindOf(serverId: String, id: String): String?

    @Query(
        """SELECT image FROM track WHERE serverId = :serverId AND id = :id
        UNION ALL SELECT image FROM album WHERE serverId = :serverId AND id = :id
        UNION ALL SELECT image FROM artist WHERE serverId = :serverId AND id = :id
        UNION ALL SELECT image FROM playlist WHERE serverId = :serverId AND id = :id LIMIT 1""",
    )
    suspend fun imageOf(serverId: String, id: String): String?

    // "Library on This Device".

    @Query("SELECT COUNT(*) FROM track WHERE serverId = :serverId") suspend fun trackCount(serverId: String): Int
    @Query("SELECT COUNT(*) FROM album WHERE serverId = :serverId") suspend fun albumCount(serverId: String): Int

    // endregion
}

data class IdName(val id: String, val name: String)

data class PlaylistTrackView(
    @androidx.room.Embedded val track: TrackRow,
    val entryId: String,
    val isFavorite: Boolean,
    val rating: Int?,
    val albumImage: String?,
)
