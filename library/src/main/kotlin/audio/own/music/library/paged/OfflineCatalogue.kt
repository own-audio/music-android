package audio.own.music.library.paged

import androidx.room.withTransaction
import audio.own.core.servers.CompositeId
import audio.own.music.library.AlbumSort
import audio.own.music.library.AlbumWithSongs
import audio.own.music.library.FavoriteKind
import audio.own.music.library.PlaylistWithSongs
import audio.own.music.library.SourceError
import audio.own.music.library.db.AlbumView
import audio.own.music.library.db.ArtistView
import audio.own.music.library.db.CatalogueDatabase
import audio.own.music.library.db.FavouriteRow
import audio.own.music.library.db.PlaylistEntryRow
import audio.own.music.library.db.PlaylistRow
import audio.own.music.library.db.RatingRow
import audio.own.music.library.db.TrackView
import audio.own.music.library.model.Album
import audio.own.music.library.model.Artist
import audio.own.music.library.model.FavoritesResult
import audio.own.music.library.model.Gain
import audio.own.music.library.model.Genre
import audio.own.music.library.model.Playlist
import audio.own.music.library.model.SearchResult
import audio.own.music.library.model.Song
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * A Subsonic or Jellyfin server's catalogue on the phone, read back as the app's models when the
 * server can't be reached, and kept in step with what the listener changes here (favourites,
 * ratings, playlists) until the next sync reads it all again.
 *
 * @param realEntryIds whether playlist entries have ids of their own (Jellyfin); Subsonic removes
 *   by position, so its entries are kept by position and handed out without an id.
 */
class OfflineCatalogue(
    private val db: CatalogueDatabase,
    private val serverId: String,
    private val realEntryIds: Boolean,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val dao = db.catalogue()
    private val shuffleSeed = AtomicLong(clock.millis() % 1_000_003)

    private fun id(raw: String) = CompositeId.make(serverId, raw)

    /** Whether there is anything to fall back on: a sync has finished at least once. */
    suspend fun isAvailable(): Boolean = dao.feedState(serverId)?.syncedAtMs != null

    // region Reading

    suspend fun artists(): List<Artist> = dao.artists(serverId).map(::artist)

    suspend fun albumsOfArtist(artistId: String): List<Album> = dao.albumsOfArtist(serverId, artistId).map(::album)

    /** The orders the catalogue can give; plays are only on the server. */
    suspend fun albums(sort: AlbumSort, limit: Int, offset: Int): List<Album> = when (sort) {
        AlbumSort.NAME -> dao.albumsByName(serverId, limit, offset)
        AlbumSort.ARTIST -> dao.albumsByArtistName(serverId, limit, offset)
        AlbumSort.NEWEST -> dao.albumsNewest(serverId, limit, offset)
        AlbumSort.RANDOM -> {
            if (offset == 0) shuffleSeed.set(clock.millis() % 1_000_003)
            dao.albumsShuffled(serverId, shuffleSeed.get(), limit, offset)
        }
        AlbumSort.FAVORITES -> dao.albumsFavourite(serverId, limit, offset)
        AlbumSort.HIGHEST_RATED -> dao.albumsHighestRated(serverId, limit, offset)
        AlbumSort.RECENTLY_PLAYED, AlbumSort.MOST_PLAYED -> throw SourceError.Offline()
    }.map(::album)

    suspend fun albumsOfGenre(genreId: String, limit: Int): List<Album> = dao.albumsOfGenre(serverId, genreId, limit).map(::album)

    suspend fun album(albumId: String): AlbumWithSongs {
        val row = dao.album(serverId, albumId) ?: throw SourceError.Offline()
        return AlbumWithSongs(album(row), dao.tracksOfAlbum(serverId, albumId).map { song(it) })
    }

    suspend fun song(songId: String): Song = dao.track(serverId, songId)?.let { song(it) } ?: throw SourceError.Offline()

    suspend fun songs(limit: Int, offset: Int): List<Song> = dao.tracksByTitle(serverId, limit, offset).map { song(it) }

    suspend fun randomSongs(limit: Int, genreId: String?): List<Song> =
        (if (genreId == null) dao.randomTracks(serverId, limit) else dao.randomTracksOfGenre(serverId, genreId, limit)).map { song(it) }

    suspend fun genres(): List<Genre> = dao.genres(serverId).map { Genre(id(it.id), it.name, it.albumCount, it.songCount) }

    suspend fun search(text: String, limit: Int): SearchResult {
        val pattern = audio.own.music.library.db.CatalogueSearch.like(text)
        return SearchResult(
            artists = dao.searchArtists(serverId, pattern, limit).map(::artist),
            albums = dao.searchAlbums(serverId, pattern, limit).map(::album),
            songs = dao.searchSongs(serverId, text, limit).map { song(it) },
        )
    }

    suspend fun favorites(): FavoritesResult = FavoritesResult(
        artists = dao.favouriteArtists(serverId).map(::artist),
        albums = dao.albumsFavourite(serverId, Int.MAX_VALUE, 0).map(::album),
        songs = dao.favouriteTracks(serverId).map { song(it) },
    )

    suspend fun playlists(): List<Playlist> = dao.playlists(serverId).map(::playlist)

    /** The entries kept when the playlist was last opened online. */
    suspend fun playlist(playlistId: String): PlaylistWithSongs {
        val row = dao.playlist(serverId, playlistId) ?: throw SourceError.Offline()
        val songs = dao.playlistTracks(serverId, playlistId).map { song(TrackView(it.track, it.isFavorite, it.rating, it.albumImage), entryId(it.entryId)) }
        return PlaylistWithSongs(playlist(row), songs)
    }

    // endregion

    // region Keeping it in step

    /** A playlist as just read from the server, its entries kept for opening it offline. */
    suspend fun keepPlaylist(playlist: Playlist, songs: List<Song>) {
        val raw = raw(playlist.id)
        db.withTransaction {
            val gen = generation()
            dao.upsertPlaylists(listOf(playlistRow(playlist, gen)))
            dao.deletePlaylistEntries(serverId, raw)
            dao.upsertPlaylistEntries(
                songs.mapIndexed { i, s -> PlaylistEntryRow(serverId, raw, s.playlistEntryId?.takeIf { realEntryIds } ?: "#$i", i, raw(s.id)) },
            )
        }
    }

    suspend fun renamePlaylist(playlistId: String, name: String) {
        val row = dao.playlist(serverId, playlistId) ?: return
        dao.upsertPlaylists(listOf(row.copy(name = name)))
    }

    suspend fun deletePlaylist(playlistId: String) = db.withTransaction {
        dao.deletePlaylists(serverId, listOf(playlistId))
        dao.deletePlaylistEntries(serverId, playlistId)
    }

    suspend fun addPlaylist(playlist: Playlist) {
        dao.upsertPlaylists(listOf(playlistRow(playlist, generation())))
    }

    suspend fun setFavorite(kind: FavoriteKind, rawId: String, on: Boolean) {
        val wire = when (kind) {
            FavoriteKind.SONG -> "track"
            FavoriteKind.ALBUM -> "album"
            FavoriteKind.ARTIST -> "contributor"
        }
        if (on) dao.upsertFavourites(listOf(FavouriteRow(serverId, wire, rawId, clock.millis(), generation()))) else dao.deleteFavourite(serverId, wire, rawId)
    }

    /** By what the catalogue knows the id as; an id it doesn't know waits for the next sync. */
    suspend fun setRating(rawId: String, rating: Int) {
        val kind = dao.kindOf(serverId, rawId)?.takeIf { it == "track" || it == "album" } ?: return
        if (rating <= 0) dao.deleteRating(serverId, kind, rawId) else dao.upsertRatings(listOf(RatingRow(serverId, kind, rawId, rating, clock.millis(), generation())))
    }

    // endregion

    // region Rows to models

    private fun raw(composite: String) = CompositeId.split(composite)?.second ?: composite

    private suspend fun generation() = dao.feedState(serverId)?.generation ?: 0

    private fun entryId(stored: String): String? = stored.takeIf { realEntryIds && !it.startsWith("#") }

    private fun artist(v: ArtistView): Artist = with(v.artist) {
        // A Navidrome artist picture is a ready link; any other image is a cover id.
        val isLink = image?.startsWith("http://") == true || image?.startsWith("https://") == true
        Artist(
            id = id(id),
            name = name,
            albumCount = v.albumCount,
            coverArt = image?.takeUnless { isLink }?.let(::id),
            imageUrl = image?.takeIf { isLink },
            isFavorite = v.isFavorite,
        )
    }

    private fun album(v: AlbumView): Album = with(v.album) {
        Album(
            id = id(id),
            name = title,
            artist = artistLine,
            artistId = artistId?.let(::id),
            songCount = trackCount,
            durationMs = durationMs.takeIf { it > 0 },
            coverArt = image?.let(::id),
            year = year,
            isFavorite = v.isFavorite,
            rating = v.rating,
            dateAdded = createdAtMs.takeIf { it > 0 }?.let(Instant::ofEpochMilli),
        )
    }

    private fun song(v: TrackView, entryId: String? = null): Song = with(v.track) {
        val gain = Gain(trackGain, albumGain, trackPeak, albumPeak).takeUnless { it.isEmpty }
        Song(
            id = id(id),
            title = title,
            album = albumTitle,
            artist = artistLine,
            albumId = albumId?.let(::id),
            artistId = artistId?.let(::id),
            track = trackNumber,
            discNumber = discNumber,
            durationMs = durationMs,
            genre = genreName,
            coverArt = image?.let(::id),
            suffix = suffix,
            bitRate = bitRate,
            isFavorite = v.isFavorite,
            rating = v.rating,
            gain = gain,
            playlistEntryId = entryId,
        )
    }

    private fun playlist(p: PlaylistRow) = Playlist(
        id = id(p.id),
        name = p.name,
        comment = p.description,
        owner = p.ownerId.ifEmpty { null },
        songCount = p.trackCount,
        durationMs = p.durationMs.takeIf { it > 0 },
        coverArt = p.image?.let(::id),
    )

    private fun playlistRow(p: Playlist, gen: Long) = PlaylistRow(
        serverId = serverId,
        id = raw(p.id),
        name = p.name,
        description = p.comment,
        visibility = "",
        ownerId = p.owner.orEmpty(),
        trackCount = p.songCount,
        durationMs = p.durationMs ?: 0,
        image = p.coverArt?.let(::raw),
        generated = false,
        updatedAtMs = 0,
        gen = gen,
    )

    // endregion
}
