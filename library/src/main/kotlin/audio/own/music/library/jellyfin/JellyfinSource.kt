package audio.own.music.library.jellyfin

import audio.own.core.servers.CompositeId
import audio.own.kit.jellyfin.JellyfinClient
import audio.own.kit.jellyfin.JellyfinError
import audio.own.music.library.AlbumSort
import audio.own.music.library.AlbumWithSongs
import audio.own.music.library.Capability
import audio.own.music.library.ConnectionStatus
import audio.own.music.library.FavoriteKind
import audio.own.music.library.PagedCatalogue
import audio.own.music.library.PagedSyncResult
import audio.own.music.library.PlaybackLink
import audio.own.music.library.PlaylistWithSongs
import audio.own.music.library.SourceError
import audio.own.music.library.db.CatalogueDatabase
import audio.own.music.library.model.Album
import audio.own.music.library.model.Artist
import audio.own.music.library.model.FavoritesResult
import audio.own.music.library.model.Gain
import audio.own.music.library.model.Genre
import audio.own.music.library.model.Lyrics
import audio.own.music.library.model.MusicLibraryInfo
import audio.own.music.library.model.Playlist
import audio.own.music.library.model.PlaylistEntry
import audio.own.music.library.model.SearchResult
import audio.own.music.library.model.Song
import audio.own.music.library.paged.OfflineCatalogue
import audio.own.music.library.paged.PagedCatalogueSync
import audio.own.music.library.paged.PagedLibraryReader
import java.time.Clock
import java.time.Instant
import audio.own.kit.jellyfin.Album as KitAlbum
import audio.own.kit.jellyfin.AlbumSort as KitAlbumSort
import audio.own.kit.jellyfin.Artist as KitArtist
import audio.own.kit.jellyfin.ConnectionStatus as KitConnectionStatus
import audio.own.kit.jellyfin.Playlist as KitPlaylist
import audio.own.kit.jellyfin.Song as KitSong

/**
 * A Jellyfin server, limited to one music library when the [client] was built with one (the
 * listener's choice from [musicLibraries]; the app rebuilds the client and syncs again when it
 * changes).
 *
 * Browsing asks the server and falls back to the catalogue on the phone as the Subsonic source
 * does. Jellyfin counts plays itself from the session reports ([reportNowPlaying],
 * [reportProgress], [reportStopped]); [reportPlayed] is for plays it never heard about, made
 * offline. Links carry the session token and don't run out.
 *
 * Ids in and out are composite ("<serverId>|<id>").
 */
class JellyfinSource(
    override val serverId: String,
    val client: JellyfinClient,
    db: CatalogueDatabase,
    private val clock: Clock = Clock.systemUTC(),
) : PagedCatalogue {
    private val mapper = JellyfinMapper { CompositeId.make(serverId, it) }
    private val offline = OfflineCatalogue(db, serverId, realEntryIds = true, clock = clock)
    private val sync = PagedCatalogueSync(db, serverId, Reader(), PagedCatalogueSync.MAX_AGE_MS, clock)

    override val capabilities: Set<Capability> =
        setOf(Capability.INSTANT_MIX, Capability.LOWER_BITRATE, Capability.LIBRARY_CHOICE, Capability.COUNTS_PLAYS_FROM_REPORTS)

    /** No ratings in Jellyfin, so no "highest rated". */
    override val albumSorts: List<AlbumSort> = AlbumSort.entries - AlbumSort.HIGHEST_RATED
    override val catalogueMaxAgeMs: Long = PagedCatalogueSync.MAX_AGE_MS

    private fun apiId(id: String): String {
        val (server, item) = CompositeId.split(id) ?: return id
        if (server != serverId) throw SourceError.NotFound()
        return item
    }

    override suspend fun checkConnection(): ConnectionStatus = when (client.checkSession()) {
        KitConnectionStatus.Ok -> ConnectionStatus.OK
        KitConnectionStatus.Rejected -> ConnectionStatus.REJECTED
        KitConnectionStatus.Unreachable -> ConnectionStatus.UNREACHABLE
    }

    // region Browsing

    override suspend fun artists(): List<Artist> = orOffline({ client.artists().map(mapper::artist) }, { offline.artists() })

    override suspend fun albums(artistId: String): List<Album> {
        val raw = apiId(artistId)
        return orOffline({ client.albums(artistId = raw).map(mapper::album) }, { offline.albumsOfArtist(raw) })
    }

    override suspend fun albums(sort: AlbumSort, limit: Int, offset: Int): List<Album> {
        val kitSort = when (sort) {
            AlbumSort.NAME -> KitAlbumSort.Name
            AlbumSort.ARTIST -> KitAlbumSort.Artist
            AlbumSort.NEWEST -> KitAlbumSort.Newest
            AlbumSort.RECENTLY_PLAYED -> KitAlbumSort.RecentlyPlayed
            AlbumSort.MOST_PLAYED -> KitAlbumSort.MostPlayed
            AlbumSort.RANDOM -> KitAlbumSort.Random
            AlbumSort.FAVORITES -> KitAlbumSort.Favorites
            AlbumSort.HIGHEST_RATED -> throw SourceError.Unsupported()
        }
        return orOffline({ client.albums(kitSort, limit = limit, offset = offset).map(mapper::album) }, { offline.albums(sort, limit, offset) })
    }

    override suspend fun albums(genre: Genre, limit: Int): List<Album> {
        val raw = apiId(genre.id)
        return orOffline({ client.albums(genreId = raw, limit = limit).map(mapper::album) }, { offline.albumsOfGenre(raw, limit) })
    }

    override suspend fun album(id: String): AlbumWithSongs {
        val raw = apiId(id)
        return orOffline(
            {
                val (album, songs) = client.album(raw)
                AlbumWithSongs(mapper.album(album), songs.map(mapper::song))
            },
            { offline.album(raw) },
        )
    }

    override suspend fun song(id: String): Song {
        val raw = apiId(id)
        return orOffline({ mapper.song(client.song(raw)) }, { offline.song(raw) })
    }

    override suspend fun songs(limit: Int, offset: Int): List<Song> =
        orOffline({ client.songs(limit = limit, offset = offset).map(mapper::song) }, { offline.songs(limit, offset) })

    override suspend fun randomSongs(limit: Int, genre: Genre?): List<Song> {
        val raw = genre?.let { apiId(it.id) }
        return orOffline({ client.randomSongs(limit = limit, genreId = raw).map(mapper::song) }, { offline.randomSongs(limit, raw) })
    }

    override suspend fun genres(): List<Genre> = orOffline(
        { client.genres().map { Genre(mapper.id(it.id), it.name, it.albumCount, it.songCount) } },
        { offline.genres() },
    )

    override suspend fun search(text: String): SearchResult {
        val query = text.trim()
        if (query.isEmpty()) return SearchResult(emptyList(), emptyList(), emptyList())
        return orOffline(
            {
                val found = client.search(query)
                SearchResult(found.artists.map(mapper::artist), found.albums.map(mapper::album), found.songs.map(mapper::song))
            },
            { offline.search(query, SEARCH_LIMIT) },
        )
    }

    /** Songs like a song, album or artist, picked by the server; never from the catalogue. */
    override suspend fun instantMix(itemId: String): List<Song> = api { client.instantMix(apiId(itemId)).map(mapper::song) }

    // endregion

    // region Favourites

    override suspend fun favorites(): FavoritesResult = orOffline(
        {
            val found = client.favorites()
            FavoritesResult(found.artists.map(mapper::artist), found.albums.map(mapper::album), found.songs.map(mapper::song))
        },
        { offline.favorites() },
    )

    /** Jellyfin treats songs, albums and artists alike. */
    override suspend fun setFavorite(kind: FavoriteKind, id: String, isFavorite: Boolean) {
        val raw = apiId(id)
        api { client.setFavorite(raw, isFavorite) }
        offline.setFavorite(kind, raw, isFavorite)
    }

    override suspend fun setRating(id: String, rating: Int) = throw SourceError.Unsupported()

    // endregion

    // region Playlists

    override suspend fun playlists(): List<Playlist> = orOffline({ client.playlists().map(mapper::playlist) }, { offline.playlists() })

    override suspend fun playlist(id: String): PlaylistWithSongs {
        val raw = apiId(id)
        return orOffline(
            {
                val (playlist, songs) = client.playlist(raw)
                PlaylistWithSongs(mapper.playlist(playlist), songs.map(mapper::song)).also {
                    runCatching { offline.keepPlaylist(it.playlist, it.songs) }
                }
            },
            { offline.playlist(raw) },
        )
    }

    override suspend fun createPlaylist(name: String, songIds: List<String>) {
        val created = api { client.createPlaylist(name, songIds.map(::apiId)) }
        offline.addPlaylist(Playlist(mapper.id(created), name, songCount = songIds.size))
    }

    override suspend fun addToPlaylist(id: String, songIds: List<String>) {
        api { client.addToPlaylist(apiId(id), songIds.map(::apiId)) }
    }

    /** By each entry's own id; entries without one can't be removed. */
    override suspend fun removeFromPlaylist(id: String, entries: List<PlaylistEntry>) {
        val entryIds = entries.mapNotNull { it.entryId }
        if (entryIds.isEmpty()) return
        api { client.removeFromPlaylist(apiId(id), entryIds) }
    }

    override suspend fun renamePlaylist(id: String, name: String) {
        val raw = apiId(id)
        api { client.renamePlaylist(raw, name) }
        offline.renamePlaylist(raw, name)
    }

    override suspend fun deletePlaylist(id: String) {
        val raw = apiId(id)
        api { client.deletePlaylist(raw) }
        offline.deletePlaylist(raw)
    }

    // endregion

    override suspend fun lyrics(song: Song): List<Lyrics> = api {
        val found = client.lyrics(apiId(song.id)) ?: return@api emptyList()
        listOf(Lyrics(found.lines.map { Lyrics.Line(it.startMs?.toLong(), it.text) }, found.isSynced))
    }

    // region Playback reporting

    /** Shows the song on the server's dashboard as playing here; the play is counted from these reports. */
    override suspend fun reportNowPlaying(songId: String, positionMs: Long) {
        api { client.reportStart(apiId(songId), positionMs / 1000.0) }
    }

    /** Without one now and then (the app sends one every 15 s while playing) the server takes the session for ended. */
    override suspend fun reportProgress(songId: String, positionMs: Long, isPaused: Boolean) {
        api { client.reportProgress(apiId(songId), positionMs / 1000.0, isPaused) }
    }

    override suspend fun reportStopped(songId: String, positionMs: Long) {
        api { client.reportStopped(apiId(songId), positionMs / 1000.0) }
    }

    /** Only for a play the server didn't follow through the reports (made offline). */
    override suspend fun reportPlayed(songId: String, at: Instant) {
        api { client.markPlayed(apiId(songId), at) }
    }

    // endregion

    // region Links

    /**
     * The file as it is; with [maxBitrateKbps], Jellyfin's universal endpoint, which sends the
     * original when its format plays here and fits the rate, and an MP3 at that rate otherwise.
     */
    override suspend fun playbackLink(songId: String, maxBitrateKbps: Int?): PlaybackLink {
        val raw = apiId(songId)
        if (maxBitrateKbps == null) return PlaybackLink(client.streamUrl(raw).toString())
        return PlaybackLink(client.universalUrl(raw, maxBitrateKbps * 1000).toString(), bitrateKbps = maxBitrateKbps)
    }

    override suspend fun downloadLink(songId: String): PlaybackLink = PlaybackLink(client.streamUrl(apiId(songId)).toString())

    override suspend fun coverLink(id: String, size: Int): String = client.imageUrl(apiId(id), size).toString()

    // endregion

    /**
     * The server's music libraries, for the listener to pick one or all. Their ids are the
     * server's own, not composite: they configure the client (`libraryId`) rather than name an item.
     */
    override suspend fun musicLibraries(): List<MusicLibraryInfo> =
        api { client.musicLibraries().map { MusicLibraryInfo(it.id, it.name) } }

    /** Ends the session on the server, so it leaves the dashboard's device list. */
    override suspend fun signOut() = client.signOut()

    // region Catalogue

    override suspend fun syncCatalogue(force: Boolean): PagedSyncResult? = api { sync.sync(force) }

    override suspend fun catalogueSyncedAtMs(): Long? = sync.syncedAtMs()

    private inner class Reader : PagedLibraryReader {
        private val raw = JellyfinMapper { it }

        override suspend fun artists() = client.artists().map(raw::artist)
        override suspend fun albums(offset: Int, limit: Int) = client.albums(KitAlbumSort.Name, limit = limit, offset = offset).map(raw::album)
        override suspend fun songs(offset: Int, limit: Int) = client.songs(limit = limit, offset = offset).map(raw::song)
        override suspend fun songsOfAlbum(albumId: String) = client.album(albumId).second.map(raw::song)
        override suspend fun genres() = client.genres().map { Genre(it.id, it.name, it.albumCount, it.songCount) }
        override suspend fun playlists() = client.playlists().map(raw::playlist)
    }

    // endregion

    private suspend fun <T> orOffline(online: suspend () -> T, fallback: suspend () -> T): T = try {
        online()
    } catch (e: JellyfinError) {
        if ((e is JellyfinError.Offline || e is JellyfinError.Network) && offline.isAvailable()) fallback() else throw sourceError(e)
    }

    private suspend fun <T> api(work: suspend () -> T): T = try {
        work()
    } catch (e: JellyfinError) {
        throw sourceError(e)
    }

    companion object {
        const val SEARCH_LIMIT = 20

        fun sourceError(e: JellyfinError): SourceError = when (e) {
            JellyfinError.Unauthorized -> SourceError.SignInRejected(e)
            JellyfinError.Forbidden -> SourceError.NotAllowed(e)
            JellyfinError.NotFound -> SourceError.NotFound(e)
            is JellyfinError.Http -> SourceError.Http(e.code)
            JellyfinError.Decoding -> SourceError.Unreadable(e)
            JellyfinError.Network -> SourceError.Network(e)
            JellyfinError.Offline -> SourceError.Offline()
        }
    }
}

/** The kit's models as the app's, with ids made by [id] (composite, or the server's own for the catalogue). */
class JellyfinMapper(val id: (String) -> String) {
    fun artist(a: KitArtist) = Artist(id = id(a.id), name = a.name, albumCount = a.albumCount, coverArt = a.imageId?.let(id), isFavorite = a.isFavorite)

    fun album(a: KitAlbum) = Album(
        id = id(a.id),
        name = a.name,
        artist = a.artist,
        artistId = a.artistId?.let(id),
        songCount = a.songCount,
        durationMs = a.duration?.let { it * 1000L },
        coverArt = a.coverArt?.let(id),
        year = a.year,
        genre = a.genre,
        isFavorite = a.isFavorite,
        playCount = a.playCount,
        dateAdded = a.dateAdded,
    )

    fun song(s: KitSong) = Song(
        id = id(s.id),
        title = s.title,
        album = s.album,
        artist = s.artist,
        albumId = s.albumId?.let(id),
        artistId = s.artistId?.let(id),
        track = s.track,
        discNumber = s.discNumber,
        durationMs = s.duration?.let { it * 1000L },
        genre = s.genre,
        coverArt = s.coverArt?.let(id),
        suffix = s.suffix,
        bitRate = s.bitRate,
        isFavorite = s.isFavorite,
        playCount = s.playCount,
        // Jellyfin measures loudness per track; there is no album value or peak.
        gain = s.normalizationGain?.let { Gain(track = it) },
        playlistEntryId = s.playlistItemId,
    )

    fun playlist(p: KitPlaylist) = Playlist(
        id = id(p.id),
        name = p.name,
        songCount = p.songCount,
        durationMs = p.duration?.let { it * 1000L },
        coverArt = p.coverArt?.let(id),
    )
}
