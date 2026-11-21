package audio.own.music.library.paged

import androidx.room.withTransaction
import audio.own.music.library.PagedSyncResult
import audio.own.music.library.db.AlbumCreditRow
import audio.own.music.library.db.AlbumRow
import audio.own.music.library.db.ArtistRow
import audio.own.music.library.db.CatalogueDatabase
import audio.own.music.library.db.FavouriteRow
import audio.own.music.library.db.FeedStateRow
import audio.own.music.library.db.GenreRow
import audio.own.music.library.db.PlaylistRow
import audio.own.music.library.db.RatingRow
import audio.own.music.library.db.TrackCreditRow
import audio.own.music.library.db.TrackGenreRow
import audio.own.music.library.db.TrackRow
import audio.own.music.library.model.Album
import audio.own.music.library.model.Artist
import audio.own.music.library.model.Genre
import audio.own.music.library.model.Playlist
import audio.own.music.library.model.Song
import java.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The library as a server without a change feed lists it, with the server's own ids (not
 * composite) and straight from the server, never from a saved copy of it.
 */
interface PagedLibraryReader {
    suspend fun artists(): List<Artist>
    suspend fun albums(offset: Int, limit: Int): List<Album>

    /** Every song by some stable order; empty on servers that list none without a search term. */
    suspend fun songs(offset: Int, limit: Int): List<Song>
    suspend fun songsOfAlbum(albumId: String): List<Song>
    suspend fun genres(): List<Genre>
    suspend fun playlists(): List<Playlist>
}

/**
 * Keeps a Subsonic or Jellyfin server's catalogue in Room by reading the whole library again:
 * artists, albums and songs page by page, then genres and playlists. The iOS app did the same
 * into one JSON file per server; here each page is written in its own transaction, so a 600k-song
 * library is never in memory at once.
 *
 * - Rows are written in a new generation and the older one is removed only once everything was
 *   read: a sync that fails halfway leaves the previous catalogue as it was, plus what it got to.
 * - Some Subsonic servers list no songs without a search term (`search3` with an empty query);
 *   their songs are read album by album.
 * - At most every [maxAgeMs] unless forced; when it last finished is kept in `feed_state`.
 * - Albums and songs carry the listener's stars and ratings, so favourites and ratings come with
 *   them; the artist credits written here are what the artist queries read.
 *
 * Errors are the kits' own; the sources map them.
 */
class PagedCatalogueSync(
    private val db: CatalogueDatabase,
    private val serverId: String,
    private val reader: PagedLibraryReader,
    private val maxAgeMs: Long = MAX_AGE_MS,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val dao = db.catalogue()
    private val running = Mutex()

    suspend fun syncedAtMs(): Long? = dao.feedState(serverId)?.syncedAtMs

    /** One sync at a time per server; a second caller waits and then finds the catalogue fresh. */
    suspend fun sync(force: Boolean = false): PagedSyncResult? = running.withLock {
        val before = dao.feedState(serverId)
        val last = before?.syncedAtMs
        if (!force && last != null && clock.millis() - last < maxAgeMs) return@withLock null
        val gen = (before?.generation ?: 0) + 1
        val rows = Rows(serverId, gen, clock.millis())

        val artists = reader.artists()
        db.withTransaction {
            dao.upsertArtists(artists.map(rows::artist))
            dao.upsertFavourites(artists.filter { it.isFavorite }.map { rows.favourite("contributor", it.id) })
        }

        // Genres first, so each song's genre name finds the server's genre id (Jellyfin's
        // differ from the name; Subsonic's are the name).
        val genres = reader.genres()
        db.withTransaction { dao.upsertGenres(genres.map { GenreRow(serverId, it.id, it.name, gen) }) }
        val genreIds = genres.associate { it.name.lowercase() to it.id }

        var albumCount = 0
        val albumIds = ArrayList<String>()
        while (true) {
            val page = reader.albums(albumCount, PAGE)
            db.withTransaction { writeAlbums(rows, page) }
            page.mapTo(albumIds) { it.id }
            albumCount += page.size
            if (page.size < PAGE) break
        }

        var songCount = 0
        while (true) {
            val page = reader.songs(songCount, PAGE)
            db.withTransaction { writeSongs(rows, page, genreIds) }
            songCount += page.size
            if (page.size < PAGE) break
        }
        val albumByAlbum = songCount == 0 && albumIds.isNotEmpty()
        if (albumByAlbum) {
            val buffer = ArrayList<Song>()
            for (albumId in albumIds) {
                buffer += reader.songsOfAlbum(albumId)
                if (buffer.size >= PAGE) {
                    val batch = buffer.toList()
                    buffer.clear()
                    db.withTransaction { writeSongs(rows, batch, genreIds) }
                    songCount += batch.size
                }
            }
            val rest = buffer.toList()
            db.withTransaction { writeSongs(rows, rest, genreIds) }
            songCount += rest.size
        }

        val playlists = reader.playlists()
        db.withTransaction {
            dao.upsertPlaylists(playlists.map(rows::playlist))
            prune(gen)
            dao.upsertFeedState(FeedStateRow(serverId, cursor = null, syncedAtMs = clock.millis(), userId = before?.userId, generation = gen, snapshotCursor = null, snapshotAfter = null))
        }
        PagedSyncResult(artists.size, albumCount, songCount, playlists.size, albumByAlbum)
    }

    private suspend fun writeAlbums(rows: Rows, albums: List<Album>) {
        dao.upsertAlbums(albums.map(rows::album))
        dao.deleteAlbumCredits(serverId, albums.map { it.id })
        dao.upsertAlbumCredits(albums.mapNotNull(rows::albumCredit))
        dao.upsertFavourites(albums.filter { it.isFavorite }.map { rows.favourite("album", it.id) })
        dao.upsertRatings(albums.mapNotNull { a -> a.rating?.takeIf { it > 0 }?.let { rows.rating("album", a.id, it) } })
    }

    private suspend fun writeSongs(rows: Rows, songs: List<Song>, genreIds: Map<String, String>) {
        if (songs.isEmpty()) return
        val ids = songs.map { it.id }
        dao.deleteTrackCredits(serverId, ids)
        dao.deleteTrackGenres(serverId, ids)
        dao.upsertTracks(songs.map(rows::track))
        dao.upsertTrackCredits(songs.mapNotNull(rows::trackCredit))
        dao.upsertTrackGenres(
            songs.mapNotNull { s ->
                val name = s.genre?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                TrackGenreRow(serverId, s.id, genreIds[name.lowercase()] ?: name, 0, s.albumId, rows.gen)
            },
        )
        dao.upsertFavourites(songs.filter { it.isFavorite }.map { rows.favourite("track", it.id) })
        dao.upsertRatings(songs.mapNotNull { s -> s.rating?.takeIf { it > 0 }?.let { rows.rating("track", s.id, it) } })
    }

    private suspend fun prune(gen: Long) = with(dao) {
        pruneArtists(serverId, gen)
        pruneAlbums(serverId, gen)
        pruneAlbumCredits(serverId, gen)
        pruneTracks(serverId, gen)
        pruneTrackCredits(serverId, gen)
        pruneTrackGenres(serverId, gen)
        pruneGenres(serverId, gen)
        prunePlaylists(serverId, gen)
        pruneFavourites(serverId, gen)
        pruneRatings(serverId, gen)
        pruneOrphanEntries(serverId)
    }

    /** Models with the server's own ids as catalogue rows of one generation. */
    internal class Rows(private val serverId: String, val gen: Long, startMs: Long) {
        // Marks carry no time on these servers: they count down from the sync's start, so lists
        // of the newest marked first follow the server's order, after anything marked here since.
        private var markClock = startMs

        fun artist(a: Artist) = ArtistRow(serverId, a.id, a.name, sortName = null, image = a.coverArt ?: a.imageUrl, gen = gen)

        fun album(a: Album) = AlbumRow(
            serverId = serverId,
            id = a.id,
            title = a.name,
            artistLine = a.artist,
            artistId = a.artistId,
            year = a.year,
            releaseDate = null,
            releaseGroupId = null,
            isCompilation = false,
            trackCount = a.songCount,
            durationMs = a.durationMs ?: 0,
            discCount = 1,
            image = a.coverArt,
            createdAtMs = a.dateAdded?.toEpochMilli() ?: 0,
            gen = gen,
        )

        fun albumCredit(a: Album) = a.artistId?.let { AlbumCreditRow(serverId, a.id, 0, it, "artist", "", null, gen) }

        fun track(s: Song) = TrackRow(
            serverId = serverId,
            id = s.id,
            title = s.title,
            albumId = s.albumId,
            albumTitle = s.album,
            artistLine = s.artist,
            artistId = s.artistId,
            discNumber = s.discNumber,
            trackNumber = s.track,
            durationMs = s.durationMs,
            genreName = s.genre,
            contentType = null,
            sizeBytes = null,
            image = s.coverArt,
            // No households on these servers: nobody's songs are anybody else's.
            ownerId = "",
            visibility = "",
            chapterCount = 0,
            createdAtMs = 0,
            updatedAtMs = 0,
            gen = gen,
            suffix = s.suffix,
            bitRate = s.bitRate,
            trackGain = s.gain?.track,
            albumGain = s.gain?.album,
            trackPeak = s.gain?.trackPeak,
            albumPeak = s.gain?.albumPeak,
        )

        fun trackCredit(s: Song) = s.artistId?.let { TrackCreditRow(serverId, s.id, 0, it, "artist", "", null, gen) }

        fun favourite(kind: String, id: String) = FavouriteRow(serverId, kind, id, markClock--, gen)

        fun rating(kind: String, id: String, rating: Int) = RatingRow(serverId, kind, id, rating, markClock--, gen)

        fun playlist(p: Playlist) = PlaylistRow(
            serverId = serverId,
            id = p.id,
            name = p.name,
            description = p.comment,
            visibility = "",
            ownerId = p.owner.orEmpty(),
            trackCount = p.songCount,
            durationMs = p.durationMs ?: 0,
            image = p.coverArt,
            generated = false,
            updatedAtMs = 0,
            gen = gen,
        )
    }

    companion object {
        /** Subsonic's largest page, and few enough rows per transaction. */
        const val PAGE = 500

        /** As the iOS app: a library read whole is refreshed four times a day at most. */
        const val MAX_AGE_MS = 6 * 60 * 60 * 1000L
    }
}
