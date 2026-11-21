package audio.own.music.library.subsonic

import audio.own.core.servers.CompositeId
import audio.own.kit.subsonic.AlbumListType
import audio.own.kit.subsonic.OpenSubsonicExtension
import audio.own.kit.subsonic.SubsonicClient
import audio.own.kit.subsonic.SubsonicError
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import audio.own.kit.subsonic.Album as KitAlbum
import audio.own.kit.subsonic.Artist as KitArtist
import audio.own.kit.subsonic.Playlist as KitPlaylist
import audio.own.kit.subsonic.Song as KitSong

/**
 * A Subsonic or OpenSubsonic server (Navidrome, Gonic, Airsonic, LMS, …).
 *
 * Browsing asks the server; when it can't be reached the kit answers from its saved answers, and
 * past those this falls back to the catalogue on the phone, which [syncCatalogue] reads whole.
 * Stream, download and cover links are built on the phone (the sign-in is a token in the query),
 * so they never run out.
 *
 * Ids in and out are composite ("<serverId>|<id>").
 */
class SubsonicSource(
    override val serverId: String,
    val client: SubsonicClient,
    db: CatalogueDatabase,
    private val clock: Clock = Clock.systemUTC(),
) : PagedCatalogue {
    private val mapper = SubsonicMapper { CompositeId.make(serverId, it) }
    private val offline = OfflineCatalogue(db, serverId, realEntryIds = false, clock = clock)
    private val sync = PagedCatalogueSync(db, serverId, Reader(), PagedCatalogueSync.MAX_AGE_MS, clock)
    private val extensionsLock = Mutex()
    private var extensions: Set<String>? = null

    /**
     * Navidrome, Gonic, Airsonic and own.audio transcode on request; a server that doesn't
     * ignores the parameters and sends the original, so asking is always safe.
     */
    override val capabilities: Set<Capability> = setOf(Capability.RATINGS, Capability.LOWER_BITRATE)
    override val albumSorts: List<AlbumSort> = AlbumSort.entries.toList()
    override val catalogueMaxAgeMs: Long = PagedCatalogueSync.MAX_AGE_MS

    private fun apiId(id: String): String {
        val (server, item) = CompositeId.split(id) ?: return id
        if (server != serverId) throw SourceError.NotFound()
        return item
    }

    override suspend fun checkConnection(): ConnectionStatus = when (client.checkConnection()) {
        audio.own.kit.subsonic.ConnectionStatus.Ok -> ConnectionStatus.OK
        // A server that refuses token sign-in won't take this password however often it is typed.
        audio.own.kit.subsonic.ConnectionStatus.Rejected, audio.own.kit.subsonic.ConnectionStatus.Unsupported -> ConnectionStatus.REJECTED
        audio.own.kit.subsonic.ConnectionStatus.Unreachable -> ConnectionStatus.UNREACHABLE
    }

    // region Browsing

    override suspend fun artists(): List<Artist> = orOffline({ client.artists().map(mapper::artist) }, { offline.artists() })

    override suspend fun albums(artistId: String): List<Album> {
        val raw = apiId(artistId)
        return orOffline({ client.artist(raw).second.map(mapper::album) }, { offline.albumsOfArtist(raw) })
    }

    override suspend fun albums(sort: AlbumSort, limit: Int, offset: Int): List<Album> = orOffline(
        { client.albumList(listType(sort), size = limit, offset = offset).map(mapper::album) },
        { offline.albums(sort, limit, offset) },
    )

    /** Subsonic filters by the genre's name, which is also its id here. */
    override suspend fun albums(genre: Genre, limit: Int): List<Album> {
        val name = genreName(genre)
        return orOffline({ client.albums(genre = name, size = limit).map(mapper::album) }, { offline.albumsOfGenre(name, limit) })
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
        orOffline({ client.songs(size = limit, offset = offset).map(mapper::song) }, { offline.songs(limit, offset) })

    override suspend fun randomSongs(limit: Int, genre: Genre?): List<Song> {
        val name = genre?.let(::genreName)
        return orOffline({ client.randomSongs(size = limit, genre = name).map(mapper::song) }, { offline.randomSongs(limit, name) })
    }

    override suspend fun genres(): List<Genre> = orOffline(
        { client.genres().map { Genre(mapper.id(it.value), it.value, it.albumCount, it.songCount) } },
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

    override suspend fun instantMix(itemId: String): List<Song> = throw SourceError.Unsupported()

    // endregion

    // region Favourites and ratings

    override suspend fun favorites(): FavoritesResult = orOffline(
        {
            val starred = client.starred()
            FavoritesResult(starred.artists.map(mapper::artist), starred.albums.map(mapper::album), starred.songs.map(mapper::song))
        },
        { offline.favorites() },
    )

    override suspend fun setFavorite(kind: FavoriteKind, id: String, isFavorite: Boolean) {
        val ids = listOf(apiId(id))
        api {
            when (kind) {
                FavoriteKind.SONG -> if (isFavorite) client.star(songIds = ids) else client.unstar(songIds = ids)
                FavoriteKind.ALBUM -> if (isFavorite) client.star(albumIds = ids) else client.unstar(albumIds = ids)
                FavoriteKind.ARTIST -> if (isFavorite) client.star(artistIds = ids) else client.unstar(artistIds = ids)
            }
        }
        offline.setFavorite(kind, ids.first(), isFavorite)
    }

    override suspend fun setRating(id: String, rating: Int) {
        val raw = apiId(id)
        api { client.setRating(raw, rating) }
        offline.setRating(raw, rating)
    }

    // endregion

    // region Playlists

    override suspend fun playlists(): List<Playlist> = orOffline({ client.playlists().map(mapper::playlist) }, { offline.playlists() })

    /** Online, its songs are kept in the catalogue too, for opening it offline. */
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
        offline.addPlaylist(mapper.playlist(created))
    }

    override suspend fun addToPlaylist(id: String, songIds: List<String>) {
        api { client.updatePlaylist(apiId(id), songIdsToAdd = songIds.map(::apiId)) }
    }

    /** By position; the server shifts the rest, so callers reload afterwards. */
    override suspend fun removeFromPlaylist(id: String, entries: List<PlaylistEntry>) {
        if (entries.isEmpty()) return
        api { client.updatePlaylist(apiId(id), songIndicesToRemove = entries.map { it.index }) }
    }

    override suspend fun renamePlaylist(id: String, name: String) {
        val raw = apiId(id)
        api { client.updatePlaylist(raw, name = name) }
        offline.renamePlaylist(raw, name)
    }

    override suspend fun deletePlaylist(id: String) {
        val raw = apiId(id)
        api { client.deletePlaylist(raw) }
        offline.deletePlaylist(raw)
    }

    // endregion

    /**
     * The OpenSubsonic `songLyrics` extension where the server has it (synced, several
     * languages), else classic `getLyrics` by artist and title.
     */
    override suspend fun lyrics(song: Song): List<Lyrics> {
        val structured = OpenSubsonicExtension.SONG_LYRICS in extensions()
        val asked = KitSong(id = apiId(song.id), title = song.title, album = song.album, artist = song.artist)
        return api {
            client.lyrics(asked, structured).map { l ->
                Lyrics(l.lines.map { Lyrics.Line(it.startMs?.toLong(), it.text) }, l.isSynced, l.language, l.offsetMs.toLong())
            }
        }
    }

    // region Playback reporting

    /** "Now playing" (`submission=false`), which counts nothing. */
    override suspend fun reportNowPlaying(songId: String, positionMs: Long) {
        api { client.scrobble(apiId(songId), submission = false) }
    }

    // Subsonic has no progress or stop reports.
    override suspend fun reportProgress(songId: String, positionMs: Long, isPaused: Boolean) {}
    override suspend fun reportStopped(songId: String, positionMs: Long) {}

    /** A counted play, with when listening began, so a play sent late lands at the right time. */
    override suspend fun reportPlayed(songId: String, at: Instant) {
        api { client.scrobble(apiId(songId), submission = true, time = at) }
    }

    // endregion

    // region Links

    /**
     * The original, or with [maxBitrateKbps] an MP3 the server makes at that rate. MP3 because
     * every server's transcoder makes it and the player decodes it as it arrives.
     */
    override suspend fun playbackLink(songId: String, maxBitrateKbps: Int?): PlaybackLink {
        val url = client.streamUrl(apiId(songId), maxBitRate = maxBitrateKbps, format = "mp3")
        return PlaybackLink(url.toString(), bitrateKbps = maxBitrateKbps)
    }

    /** `stream` with no parameters is the original file; `download` needs a permission many accounts lack. */
    override suspend fun downloadLink(songId: String): PlaybackLink = PlaybackLink(client.streamUrl(apiId(songId)).toString())

    override suspend fun coverLink(id: String, size: Int): String = client.coverUrl(apiId(id), size).toString()

    // endregion

    override suspend fun musicLibraries(): List<MusicLibraryInfo> = emptyList()

    /** Subsonic has no session to end. */
    override suspend fun signOut() {}

    // region Catalogue

    override suspend fun syncCatalogue(force: Boolean): PagedSyncResult? = api { sync.sync(force) }

    override suspend fun catalogueSyncedAtMs(): Long? = sync.syncedAtMs()

    /** Straight from the server, with the server's own ids, for the catalogue sync. */
    private inner class Reader : PagedLibraryReader {
        private val raw = SubsonicMapper { it }

        override suspend fun artists() = client.artists().map(raw::artist)
        override suspend fun albums(offset: Int, limit: Int) =
            client.albumList(AlbumListType.AlphabeticalByName, size = limit, offset = offset).map(raw::album)
        override suspend fun songs(offset: Int, limit: Int) = client.songs(size = limit, offset = offset).map(raw::song)
        override suspend fun songsOfAlbum(albumId: String) = client.album(albumId).second.map(raw::song)
        override suspend fun genres() = client.genres().map { Genre(it.value, it.value, it.albumCount, it.songCount) }
        override suspend fun playlists() = client.playlists().map(raw::playlist)
    }

    // endregion

    // region Helpers

    private suspend fun extensions(): Set<String> = extensionsLock.withLock {
        extensions ?: client.openSubsonicExtensions().also { extensions = it }
    }

    private fun genreName(genre: Genre): String = CompositeId.split(genre.id)?.second ?: genre.name

    /**
     * The server's answer. Where it can't be reached and the kit has no saved answer either, the
     * catalogue's, once one has been read; otherwise the error.
     */
    private suspend fun <T> orOffline(online: suspend () -> T, fallback: suspend () -> T): T = try {
        online()
    } catch (e: SubsonicError) {
        if ((e is SubsonicError.Offline || e is SubsonicError.Network) && offline.isAvailable()) fallback() else throw sourceError(e)
    }

    private suspend fun <T> api(work: suspend () -> T): T = try {
        work()
    } catch (e: SubsonicError) {
        throw sourceError(e)
    }

    // endregion

    companion object {
        const val SEARCH_LIMIT = 40

        fun listType(sort: AlbumSort): AlbumListType = when (sort) {
            AlbumSort.NAME -> AlbumListType.AlphabeticalByName
            AlbumSort.ARTIST -> AlbumListType.AlphabeticalByArtist
            AlbumSort.NEWEST -> AlbumListType.Newest
            AlbumSort.RECENTLY_PLAYED -> AlbumListType.Recent
            AlbumSort.MOST_PLAYED -> AlbumListType.Frequent
            AlbumSort.RANDOM -> AlbumListType.Random
            AlbumSort.FAVORITES -> AlbumListType.Starred
            AlbumSort.HIGHEST_RATED -> AlbumListType.Highest
        }

        fun sourceError(e: SubsonicError): SourceError = when (e) {
            SubsonicError.WrongCredentials -> SourceError.SignInRejected(e)
            // Not a wrong password, so not "sign in again": the kit's words say what is wrong.
            SubsonicError.TokenAuthUnsupported -> SourceError.Server(e.message.orEmpty())
            SubsonicError.NotAuthorized -> SourceError.NotAllowed(e)
            is SubsonicError.Http -> if (e.code == 404) SourceError.NotFound(e) else SourceError.Http(e.code)
            // 70 is Subsonic's "not found".
            is SubsonicError.Server -> when {
                e.code == 70 -> SourceError.NotFound(e)
                e.serverMessage.isEmpty() -> SourceError.Http(200)
                else -> SourceError.Server(e.serverMessage)
            }
            SubsonicError.Decoding -> SourceError.Unreadable(e)
            SubsonicError.Network -> SourceError.Network(e)
            SubsonicError.Offline -> SourceError.Offline()
        }
    }
}

/** The kit's models as the app's, with ids made by [id] (composite, or the server's own for the catalogue). */
class SubsonicMapper(val id: (String) -> String) {
    fun artist(a: KitArtist) = Artist(
        id = id(a.id),
        name = a.name,
        albumCount = a.albumCount,
        imageUrl = a.artistImageUrl,
        isFavorite = a.starred != null,
    )

    /** An album without a cover id of its own is drawn by its id, which servers take for one. */
    fun album(a: KitAlbum) = Album(
        id = id(a.id),
        name = a.name,
        artist = a.artist,
        artistId = a.artistId?.let(id),
        songCount = a.songCount,
        durationMs = a.duration?.let { it * 1000L },
        coverArt = id(a.coverArt ?: a.id),
        year = a.year,
        isFavorite = a.starred != null,
        rating = a.userRating?.takeIf { it > 0 },
        playCount = a.playCount,
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
        // Some servers give songs no cover id of their own; the album's is the right fallback.
        coverArt = (s.coverArt ?: s.albumId)?.let(id),
        suffix = s.suffix,
        // Subsonic counts kilobits.
        bitRate = s.bitRate?.let { it * 1000 },
        isFavorite = s.starred != null,
        rating = s.userRating?.takeIf { it > 0 },
        playCount = s.playCount,
        gain = s.replayGain?.takeUnless { it.isEmpty }?.let { Gain(it.trackGain, it.albumGain, it.trackPeak, it.albumPeak) },
    )

    fun playlist(p: KitPlaylist) = Playlist(
        id = id(p.id),
        name = p.name,
        comment = p.comment,
        owner = p.owner,
        songCount = p.songCount,
        durationMs = p.duration?.takeIf { it > 0 }?.let { it * 1000L },
        coverArt = p.coverArt?.let(id),
    )
}
