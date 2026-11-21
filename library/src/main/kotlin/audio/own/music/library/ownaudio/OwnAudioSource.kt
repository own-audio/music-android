package audio.own.music.library.ownaudio

import androidx.room.withTransaction
import audio.own.core.servers.CompositeId
import audio.own.kit.ownaudio.ItemKind
import audio.own.kit.ownaudio.OfflineChange
import audio.own.kit.ownaudio.OwnAudioClient
import audio.own.kit.ownaudio.OwnAudioException
import audio.own.kit.ownaudio.OwnAudioImages
import audio.own.kit.ownaudio.Page
import audio.own.kit.ownaudio.Play
import audio.own.kit.ownaudio.PlaybackCapabilities
import audio.own.kit.ownaudio.addToPlaylist
import audio.own.kit.ownaudio.album
import audio.own.kit.ownaudio.albums
import audio.own.kit.ownaudio.contributor
import audio.own.kit.ownaudio.createPlaylist
import audio.own.kit.ownaudio.deletePlaylist
import audio.own.kit.ownaudio.discover
import audio.own.kit.ownaudio.events
import audio.own.kit.ownaudio.httpStatus
import audio.own.kit.ownaudio.lyrics
import audio.own.kit.ownaudio.me
import audio.own.kit.ownaudio.members
import audio.own.kit.ownaudio.playbackInfo
import audio.own.kit.ownaudio.played
import audio.own.kit.ownaudio.playlist
import audio.own.kit.ownaudio.playlistEntries
import audio.own.kit.ownaudio.progress
import audio.own.kit.ownaudio.push
import audio.own.kit.ownaudio.randomTracks
import audio.own.kit.ownaudio.removeFromPlaylist
import audio.own.kit.ownaudio.renamePlaylist
import audio.own.kit.ownaudio.reportPlays
import audio.own.kit.ownaudio.saveProgress
import audio.own.kit.ownaudio.search
import audio.own.kit.ownaudio.setFavourite
import audio.own.kit.ownaudio.setRating
import audio.own.kit.ownaudio.setTrackChapters
import audio.own.kit.ownaudio.signOut
import audio.own.kit.ownaudio.track
import audio.own.kit.ownaudio.trackChapters
import audio.own.kit.ownaudio.tracks
import audio.own.music.library.AlbumSort
import audio.own.music.library.AlbumWithSongs
import audio.own.music.library.BatchedOfflineEdits
import audio.own.music.library.Capability
import audio.own.music.library.CatalogueSyncResult
import audio.own.music.library.ChangeFeedCatalogue
import audio.own.music.library.ConcertSource
import audio.own.music.library.ConnectionStatus
import audio.own.music.library.FavoriteKind
import audio.own.music.library.IdentifySource
import audio.own.music.library.LiveLibraryUpdates
import audio.own.music.library.MusicSource
import audio.own.music.library.OfflineEdit
import audio.own.music.library.PlaybackLink
import audio.own.music.library.PlaylistWithSongs
import audio.own.music.library.SourceError
import audio.own.music.library.db.CatalogueDatabase
import audio.own.music.library.db.FavouriteRow
import audio.own.music.library.db.PlaylistEntryRow
import audio.own.music.library.db.PlaylistRow
import audio.own.music.library.db.RatingRow
import audio.own.music.library.model.Album
import audio.own.music.library.model.Artist
import audio.own.music.library.model.FavoritesResult
import audio.own.music.library.model.Genre
import audio.own.music.library.model.Lyrics
import audio.own.music.library.model.MusicLibraryInfo
import audio.own.music.library.model.Playlist
import audio.own.music.library.model.PlaylistEntry
import audio.own.music.library.model.SearchResult
import audio.own.music.library.model.Song
import audio.own.music.library.model.SongChapter
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import audio.own.kit.ownaudio.Playlist as ApiPlaylist
import audio.own.kit.ownaudio.Track as ApiTrack

/**
 * An own.audio server through its own API, `/api/v2` only.
 *
 * Browsing reads the catalogue on the phone, which [syncCatalogue] keeps from the change feed:
 * artists, albums in every order the catalogue can give, songs, genres with counts, favourites.
 * What the feed doesn't carry goes to the server, and to the catalogue when the server can't be
 * reached: Recently and Most Played (`/me/played`), random songs, search, a playlist's entries,
 * lyrics. Streams, downloads and covers are links the server signs per item.
 *
 * Ids in and out are composite ("<serverId>|<id>"); [apiId] strips the prefix.
 */
class OwnAudioSource(
    override val serverId: String,
    /** The account's email; a playlist of one's own names it as owner. */
    val username: String,
    val client: OwnAudioClient,
    private val db: CatalogueDatabase,
    val listener: ListenerState = ListenerState(),
    private val clock: Clock = Clock.systemUTC(),
) : MusicSource, ChangeFeedCatalogue, BatchedOfflineEdits, LiveLibraryUpdates, ConcertSource, IdentifySource {
    private val dao = db.catalogue()
    private val feed = OwnAudioCatalogueSync(db, serverId, client, clock)

    /** The shuffle Random reads pages of; a new one at offset 0. */
    private val shuffleSeed = AtomicLong(clock.millis() % 1_000_003)
    private val memberNames = AtomicReference<Map<String, String>?>(null)

    /** No instant mix: the server has nothing to pick similar songs from. */
    override val capabilities: Set<Capability> = setOf(Capability.RATINGS, Capability.LOWER_BITRATE)
    override val albumSorts: List<AlbumSort> = AlbumSort.entries.toList()
    override val identifyClient: OwnAudioClient get() = client

    override fun apiId(id: String): String {
        val (server, item) = CompositeId.split(id) ?: return id
        if (server != serverId) throw SourceError.NotFound()
        return item
    }

    private fun id(raw: String) = CompositeId.make(serverId, raw)

    private suspend fun models() = CatalogueModels(serverId, userId())

    override suspend fun checkConnection(): ConnectionStatus {
        if (client.tokens?.current == null) return ConnectionStatus.REJECTED
        return try {
            client.me()
            ConnectionStatus.OK
        } catch (e: OwnAudioException) {
            when {
                e is OwnAudioException.SignedOut || e is OwnAudioException.SignInRefused || e.httpStatus == 401 -> ConnectionStatus.REJECTED
                else -> ConnectionStatus.UNREACHABLE
            }
        }
    }

    // region Browsing, from the catalogue

    override suspend fun artists(): List<Artist> {
        val m = models()
        return dao.artists(serverId).map(m::artist)
    }

    override suspend fun albums(artistId: String): List<Album> {
        val m = models()
        return dao.albumsOfArtist(serverId, apiId(artistId)).map { m.album(it) }
    }

    override suspend fun albums(sort: AlbumSort, limit: Int, offset: Int): List<Album> {
        val m = models()
        return when (sort) {
            AlbumSort.NAME -> dao.albumsByName(serverId, limit, offset).map { m.album(it) }
            AlbumSort.ARTIST -> dao.albumsByArtistName(serverId, limit, offset).map { m.album(it) }
            AlbumSort.NEWEST -> dao.albumsNewest(serverId, limit, offset).map { m.album(it) }
            AlbumSort.RANDOM -> {
                if (offset == 0) shuffleSeed.set(clock.millis() % 1_000_003)
                dao.albumsShuffled(serverId, shuffleSeed.get(), limit, offset).map { m.album(it) }
            }
            AlbumSort.FAVORITES -> dao.albumsFavourite(serverId, limit, offset).map { m.album(it) }
            AlbumSort.HIGHEST_RATED -> dao.albumsHighestRated(serverId, limit, offset).map { m.album(it) }
            // The feed has no plays: the server's list, the albums from the catalogue.
            AlbumSort.RECENTLY_PLAYED, AlbumSort.MOST_PLAYED -> api {
                val order = if (sort == AlbumSort.MOST_PLAYED) "most" else "recent"
                val played = client.played(ItemKind.ALBUM, order = order, limit = minOf(offset + limit, PAGE_MAX)).items
                val wanted = played.drop(offset).take(limit)
                albumsById(wanted.map { it.id }, wanted.associate { it.id to it.playCount.toInt() })
            }
        }
    }

    override suspend fun albums(genre: Genre, limit: Int): List<Album> {
        val genreId = genreId(genre) ?: return emptyList()
        val m = models()
        return dao.albumsOfGenre(serverId, genreId, limit).map { m.album(it) }
    }

    override suspend fun album(id: String): AlbumWithSongs {
        val raw = apiId(id)
        val m = models()
        val row = dao.album(serverId, raw)
        if (row != null) return AlbumWithSongs(m.album(row), dao.tracksOfAlbum(serverId, raw).map { m.song(it) })
        // Not in the catalogue yet (added since the last sync, or no sync yet): from the server.
        return api {
            val answer = client.album(raw, include = listOf("contributors"))
            val tracks = allPages { cursor -> client.tracks(albumId = raw, include = TRACK_INCLUDES, cursor = cursor, limit = PAGE_MAX) }
            val mapper = mapper(answer.included).also { it.albums[answer.record.id] = answer.record }
            AlbumWithSongs(mapper.album(answer.record), tracks)
        }
    }

    override suspend fun song(id: String): Song {
        val raw = apiId(id)
        dao.track(serverId, raw)?.let { return models().song(it) }
        return api {
            val answer = client.track(raw, include = TRACK_INCLUDES)
            mapper(answer.included).song(answer.track)
        }
    }

    override suspend fun songs(limit: Int, offset: Int): List<Song> {
        val m = models()
        return dao.tracksByTitle(serverId, limit, offset).map { m.song(it) }
    }

    /** A new draw from the server each time; offline, from the catalogue. */
    override suspend fun randomSongs(limit: Int, genre: Genre?): List<Song> {
        val genreId = if (genre != null) genreId(genre) ?: return emptyList() else null
        return orOffline(
            online = {
                val page = client.randomTracks(genreId = genreId, limit = limit.coerceIn(1, PAGE_MAX), include = TRACK_INCLUDES)
                val mapper = mapper(page.included)
                page.items.map { mapper.song(it) }
            },
            offline = {
                val m = models()
                (if (genreId == null) dao.randomTracks(serverId, limit) else dao.randomTracksOfGenre(serverId, genreId, limit)).map { m.song(it) }
            },
        )
    }

    override suspend fun genres(): List<Genre> {
        val m = models()
        return dao.genres(serverId).map(m::genre)
    }

    /** The server's search (it matches more than titles); offline, titles and names in the catalogue. */
    override suspend fun search(text: String): SearchResult {
        val query = text.trim()
        if (query.isEmpty()) return SearchResult(emptyList(), emptyList(), emptyList())
        return orOffline(
            online = {
                val answer = client.search(query, types = listOf("artists", "albums", "tracks"), limit = SEARCH_LIMIT, include = TRACK_INCLUDES)
                val mapper = mapper(answer.included)
                for (artist in answer.artists) mapper.contributors[artist.id] = artist
                // An album found by its title brings no names with it: take them from the catalogue.
                val missing = answer.albums.flatMap { a -> a.credits.map { it.contributorId } }.filter { it !in mapper.contributors }.distinct()
                val known = missing.chunked(CatalogueWriter.CHUNK).flatMap { dao.artistNames(serverId, it) }.associate { it.id to it.name }
                val albums = answer.albums.map { a ->
                    mapper.album(a).let { album ->
                        if (album.artist != null && album.artist.isNotEmpty()) album
                        else album.copy(artist = RecordMapper.creditLine(a.credits, RecordMapper.ALBUM_ROLES) { mapper.contributors[it]?.name ?: known[it] })
                    }
                }
                SearchResult(answer.artists.map { mapper.artist(it) }, albums, answer.tracks.map { mapper.song(it) })
            },
            offline = { searchCatalogue(query) },
        )
    }

    suspend fun searchCatalogue(text: String): SearchResult {
        val pattern = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val m = models()
        return SearchResult(
            artists = dao.searchArtists(serverId, pattern, SEARCH_LIMIT).map(m::artist),
            albums = dao.searchAlbums(serverId, pattern, SEARCH_LIMIT).map { m.album(it) },
            songs = dao.searchTracks(serverId, pattern, SEARCH_LIMIT).map { m.song(it) },
        )
    }

    override suspend fun instantMix(itemId: String): List<Song> = throw SourceError.Unsupported()

    // endregion

    // region Favourites and ratings

    /** From the feed, newest marked first. */
    override suspend fun favorites(): FavoritesResult {
        val m = models()
        return FavoritesResult(
            artists = dao.favouriteArtists(serverId).map(m::artist),
            albums = dao.albumsFavourite(serverId, Int.MAX_VALUE, 0).map { m.album(it) },
            songs = dao.favouriteTracks(serverId).map { m.song(it) },
        )
    }

    override suspend fun setFavorite(kind: FavoriteKind, id: String, isFavorite: Boolean) {
        val raw = apiId(id)
        val itemKind = itemKind(kind)
        api { client.setFavourite(itemKind, raw, isFavorite) }
        // Shown at once; the feed brings the same back at the next sync.
        if (isFavorite) {
            dao.upsertFavourites(listOf(FavouriteRow(serverId, itemKind.wire, raw, clock.millis(), currentGeneration())))
        } else {
            dao.deleteFavourite(serverId, itemKind.wire, raw)
        }
        listener.setFavourite(raw, isFavorite)
    }

    /**
     * The API rates a track or an album by kind, and the app has only the id: the kind is known
     * from the catalogue, and otherwise tried, a track first.
     */
    override suspend fun setRating(id: String, rating: Int) {
        val raw = apiId(id)
        val kind = api {
            when (dao.kindOf(serverId, raw)) {
                "album" -> ItemKind.ALBUM.also { client.setRating(it, raw, rating) }
                "track" -> ItemKind.TRACK.also { client.setRating(it, raw, rating) }
                else -> try {
                    ItemKind.TRACK.also { client.setRating(it, raw, rating) }
                } catch (e: OwnAudioException) {
                    if (e.httpStatus != 404) throw e
                    ItemKind.ALBUM.also { client.setRating(it, raw, rating) }
                }
            }
        }
        if (rating == 0) {
            dao.deleteRating(serverId, kind.wire, raw)
        } else {
            dao.upsertRatings(listOf(RatingRow(serverId, kind.wire, raw, rating, clock.millis(), currentGeneration())))
        }
        listener.setRating(raw, rating)
    }

    // endregion

    // region Playlists

    /** From the feed; edits made here are written through at once. */
    override suspend fun playlists(): List<Playlist> {
        val m = models()
        val names = memberNames()
        return dao.playlists(serverId).map { m.playlist(it, username, names) }
    }

    /** Its entries from the server, kept for offline; offline, the entries kept last time. */
    override suspend fun playlist(id: String): PlaylistWithSongs {
        val raw = apiId(id)
        return orOffline(
            online = {
                val record = client.playlist(raw)
                val songs = ArrayList<Song>()
                val entries = ArrayList<PlaylistEntryRow>()
                var cursor: String? = null
                do {
                    val page = client.playlistEntries(raw, include = TRACK_INCLUDES, cursor = cursor, limit = PAGE_MAX)
                    val mapper = mapper(page.included)
                    for (e in page.items) {
                        songs += mapper.song(e.track, entryId = e.id)
                        entries += PlaylistEntryRow(serverId, raw, e.id, entries.size, e.track.id)
                    }
                    cursor = page.next
                } while (cursor != null)
                db.withTransaction {
                    dao.upsertPlaylists(listOf(playlistRow(record)))
                    dao.deletePlaylistEntries(serverId, raw)
                    dao.upsertPlaylistEntries(entries)
                }
                PlaylistWithSongs(mapper(null).playlist(record, username, memberNames()), songs)
            },
            offline = {
                val row = dao.playlist(serverId, raw) ?: throw SourceError.Offline()
                val m = models()
                PlaylistWithSongs(m.playlist(row, username, memberNames()), dao.playlistTracks(serverId, raw).map(m::song))
            },
        )
    }

    override suspend fun createPlaylist(name: String, songIds: List<String>) {
        val created = api { client.createPlaylist(name, trackIds = songIds.map(::apiId)) }
        dao.upsertPlaylists(listOf(playlistRow(created)))
    }

    override suspend fun addToPlaylist(id: String, songIds: List<String>) {
        val updated = api { client.addToPlaylist(apiId(id), songIds.map(::apiId)) }
        dao.upsertPlaylists(listOf(playlistRow(updated)))
    }

    /** By the entries' own ids: a song can be in a playlist twice. */
    override suspend fun removeFromPlaylist(id: String, entries: List<PlaylistEntry>) {
        val raw = apiId(id)
        val entryIds = entries.mapNotNull { it.entryId }
        if (entryIds.size != entries.size) throw SourceError.Unsupported()
        api { for (entryId in entryIds) client.removeFromPlaylist(raw, entryId) }
        orNull { client.playlist(raw) }?.let { dao.upsertPlaylists(listOf(playlistRow(it))) }
    }

    override suspend fun renamePlaylist(id: String, name: String) {
        val updated = api { client.renamePlaylist(apiId(id), name) }
        dao.upsertPlaylists(listOf(playlistRow(updated)))
    }

    override suspend fun deletePlaylist(id: String) {
        val raw = apiId(id)
        api { client.deletePlaylist(raw) }
        db.withTransaction {
            dao.deletePlaylists(serverId, listOf(raw))
            dao.deletePlaylistEntries(serverId, raw)
        }
    }

    // endregion

    override suspend fun lyrics(song: Song): List<Lyrics> = api {
        val answer = client.lyrics(apiId(song.id))
        if (answer.text == null || answer.lines.isEmpty()) {
            emptyList()
        } else {
            listOf(Lyrics(answer.lines.map { Lyrics.Line(it.startMs, it.text) }, isSynced = answer.synced))
        }
    }

    // region Playback reporting

    // v2 has no "now playing": the server learns from plays and positions.
    override suspend fun reportNowPlaying(songId: String, positionMs: Long) {}
    override suspend fun reportProgress(songId: String, positionMs: Long, isPaused: Boolean) {}
    override suspend fun reportStopped(songId: String, positionMs: Long) {}

    /** A play counted now, heard from [at]. Its id is stable, so a retry counts once. */
    override suspend fun reportPlayed(songId: String, at: Instant) {
        val raw = apiId(songId)
        val now = clock.instant()
        val started = minOf(at, now)
        val play = Play(
            clientId = "$raw@${started.toEpochMilli()}",
            kind = ItemKind.TRACK,
            id = raw,
            startedAt = started,
            endedAt = now,
            listenedMs = now.toEpochMilli() - started.toEpochMilli(),
        )
        val refused = api { client.reportPlays(listOf(play)) }.firstOrNull { it.status != "ok" && it.status != "duplicate" }
        if (refused != null) throw SourceError.Server(refused.status)
    }

    // endregion

    // region Links

    /**
     * A link signed for this song, asked for each time a song starts; the player resolves it.
     * The server sends the file when the player decodes its format and makes MP3 (then AAC)
     * otherwise, the order the iOS app asks for.
     */
    override suspend fun playbackLink(songId: String, maxBitrateKbps: Int?): PlaybackLink = api {
        val info = client.playbackInfo("track", apiId(songId), PlaybackCapabilities(direct = PLAYABLE, transcode = listOf("mp3", "aac"), maxBitrateKbps = maxBitrateKbps))
        PlaybackLink(info.url, info.expiresAt, info.contentType, info.bitrateKbps, isTranscoded = info.decision == "transcode")
    }

    /** The original, whatever its format: a download is kept as the server has it. */
    override suspend fun downloadLink(songId: String): PlaybackLink = api {
        val info = client.playbackInfo("track", apiId(songId), PlaybackCapabilities(direct = emptyList(), transcode = emptyList()))
        PlaybackLink(info.url, info.expiresAt, info.contentType, info.bitrateKbps, isTranscoded = info.decision == "transcode")
    }

    /**
     * The record's image link at the nearest size the server makes. Image links are signed and
     * run out after a week or two: one that runs out within the hour is read again from the
     * record and written back to the catalogue.
     */
    override suspend fun coverLink(id: String, size: Int): String {
        val (server, raw) = RecordMapper.recordOfCover(id)
        if (server != null && server != serverId) throw SourceError.NotFound()
        val known = dao.imageOf(serverId, raw)
        val link = known?.takeIf { isFresh(it) } ?: freshImage(raw) ?: throw SourceError.NotFound()
        return OwnAudioImages.sized(link, size)
    }

    private fun isFresh(link: String): Boolean {
        val expires = link.toHttpUrlOrNull()?.queryParameter("e")?.toLongOrNull() ?: return true
        return expires - clock.instant().epochSecond > 3600
    }

    /** Reads the record again: by its kind in the catalogue, else album, track, playlist, contributor. */
    private suspend fun freshImage(raw: String): String? {
        val kinds = dao.kindOf(serverId, raw)?.let { listOf(it) } ?: listOf("album", "track", "playlist", "contributor")
        for (kind in kinds) {
            val image = try {
                api {
                    when (kind) {
                        "album" -> client.album(raw, include = emptyList()).record.image
                        "track" -> client.track(raw, include = emptyList()).track.image
                        "playlist" -> client.playlist(raw).image
                        else -> client.contributor(raw).image
                    }
                }
            } catch (_: SourceError.NotFound) {
                continue
            }
            when (kind) {
                "album" -> dao.setAlbumImage(serverId, raw, image)
                "track" -> dao.setTrackImage(serverId, raw, image)
                "playlist" -> dao.setPlaylistImage(serverId, raw, image)
                else -> dao.setArtistImage(serverId, raw, image)
            }
            return image
        }
        return null
    }

    // endregion

    override suspend fun musicLibraries(): List<MusicLibraryInfo> = emptyList()

    override suspend fun signOut() = client.signOut()

    // region Catalogue, offline edits, live updates

    override suspend fun syncCatalogue(): CatalogueSyncResult {
        if (client.tokens?.current == null) throw SourceError.Unsupported()
        val result = api { feed.sync() }
        refreshListener()
        return result
    }

    /** The marks from the catalogue, for answers that come straight from the server. */
    suspend fun refreshListener() {
        val state = dao.feedState(serverId)
        listener.update(ListenerState.Values.fromRows(dao.favourites(serverId), dao.ratings(serverId), state?.userId))
    }

    override suspend fun push(edits: List<OfflineEdit>) {
        if (client.tokens?.current == null) throw SourceError.Unsupported()
        val changes = edits.map(::change)
        api { for (batch in changes.chunked(PAGE_MAX)) client.push(batch) }
    }

    fun change(edit: OfflineEdit): OfflineChange {
        val id = apiId(edit.itemId)
        return when (val c = edit.change) {
            is OfflineEdit.Change.Favorite -> OfflineChange.favourite(itemKind(c.kind), id, c.on, edit.madeAt, edit.clientId)
            is OfflineEdit.Change.Rating -> OfflineChange.rating(itemKind(c.kind), id, c.rating, edit.madeAt, edit.clientId)
            is OfflineEdit.Change.Played -> OfflineChange.play(
                Play(
                    clientId = edit.clientId,
                    kind = ItemKind.TRACK,
                    id = id,
                    startedAt = c.startedAt,
                    endedAt = maxOf(edit.madeAt, c.startedAt),
                    listenedMs = maxOf(c.heardMs, 0),
                ),
            )
        }
    }

    override fun libraryChanges(): Flow<Unit> = client.events()
        .filter { it.type == "library" }
        .map { }
        .catch { e -> throw if (e is OwnAudioException) sourceError(e) else e }

    // endregion

    // region Concerts

    override suspend fun supportsConcerts(): Boolean = orNull { client.discover().has("track_chapters") } == true

    /** Newest first: a concert is more often something just added than something looked up. */
    override suspend fun concerts(): List<Album> = api {
        val out = ArrayList<Album>()
        var cursor: String? = null
        do {
            val page = client.albums(kind = "live", sort = "added", include = listOf("contributors"), cursor = cursor, limit = PAGE_MAX)
            val mapper = mapper(page.included)
            page.items.mapTo(out) { mapper.album(it) }
            cursor = page.next
        } while (cursor != null)
        out
    }

    override suspend fun chapters(songId: String): List<SongChapter> =
        api { client.trackChapters(apiId(songId)).map { SongChapter(it.title, it.startMs) } }

    override suspend fun saveChapters(songId: String, chapters: List<SongChapter>): List<SongChapter> = api {
        client.setTrackChapters(apiId(songId), chapters.sortedBy { it.startMs }.map { it.title to it.startMs })
            .map { SongChapter(it.title, it.startMs) }
    }

    override suspend fun savedPosition(songId: String): Long? = api {
        client.progress(ItemKind.TRACK, apiId(songId))?.takeUnless { it.finished }?.positionMs
    }

    override suspend fun savePosition(songId: String, positionMs: Long) {
        api { client.saveProgress(ItemKind.TRACK, apiId(songId), positionMs) }
    }

    // endregion

    // region Helpers

    private suspend fun userId(): String? = listener.current.userId ?: dao.feedState(serverId)?.userId?.also(listener::setUserId)

    private suspend fun currentGeneration(): Long = dao.feedState(serverId)?.generation ?: 0

    private suspend fun mapper(included: audio.own.kit.ownaudio.Included?): RecordMapper {
        if (listener.current.userId == null) userId()
        return RecordMapper(serverId, included, listener.current)
    }

    private suspend fun allPages(page: suspend (String?) -> Page<ApiTrack>): List<Song> {
        val out = ArrayList<Song>()
        var cursor: String? = null
        do {
            val p = page(cursor)
            val mapper = mapper(p.included)
            p.items.mapTo(out) { mapper.song(it) }
            cursor = p.next
        } while (cursor != null)
        return out
    }

    /** Albums by id in that order, from the catalogue, else from the server; gone ones left out. */
    private suspend fun albumsById(ids: List<String>, playCounts: Map<String, Int>): List<Album> {
        val m = models()
        val known = ids.chunked(CatalogueWriter.CHUNK).flatMap { dao.albumsByIds(serverId, it) }.associateBy { it.album.id }
        return ids.mapNotNull { raw ->
            known[raw]?.let { m.album(it, playCounts[raw]) } ?: try {
                val answer = client.album(raw, include = listOf("contributors"))
                mapper(answer.included).album(answer.record, playCounts[raw])
            } catch (e: OwnAudioException) {
                if (e.httpStatus == 404) null else throw e
            }
        }
    }

    /** The API's id for a genre known by id or by name. */
    private suspend fun genreId(genre: Genre): String? {
        val raw = runCatching { apiId(genre.id) }.getOrNull()
        if (raw != null && dao.genreNames(serverId, listOf(raw)).isNotEmpty()) return raw
        return dao.genreIdNamed(serverId, genre.name)
    }

    /** Household members' names by user id; empty when they can't be had (no owner is shown then). */
    private suspend fun memberNames(): Map<String, String> {
        memberNames.get()?.let { return it }
        val names = orNull { client.members().associate { it.userId to it.name } } ?: return emptyMap()
        memberNames.set(names)
        return names
    }

    private suspend fun playlistRow(p: ApiPlaylist) = PlaylistRow(
        serverId, p.id, p.name, p.description, p.visibility, p.ownerId, p.trackCount.toInt(), p.durationMs, p.image, p.generated,
        p.updatedAt.toEpochMilli(), currentGeneration(),
    )

    /** The server's answer; the catalogue's when offline or the server can't be reached. */
    private suspend fun <T> orOffline(online: suspend () -> T, offline: suspend () -> T): T = try {
        online()
    } catch (e: OwnAudioException) {
        when (e) {
            is OwnAudioException.Offline, is OwnAudioException.Network -> offline()
            else -> throw sourceError(e)
        }
    }

    private suspend fun <T> orNull(work: suspend () -> T): T? = try {
        work()
    } catch (_: OwnAudioException) {
        null
    }

    private suspend fun <T> api(work: suspend () -> T): T = try {
        work()
    } catch (e: OwnAudioException) {
        throw sourceError(e)
    }

    // endregion

    companion object {
        /** What a track answer brings with it, so songs have their album, artists and genre names. */
        val TRACK_INCLUDES = listOf("albums", "contributors", "genres")

        /** The most the server sends in one page. */
        const val PAGE_MAX = 500
        const val SEARCH_LIMIT = 20

        /**
         * What ExoPlayer plays as it streams. Unlike iOS, Ogg, Opus and Vorbis are among them
         * (Core Audio can't stream those); AIFF is not (Media3 has no AIFF extractor), so the
         * server converts it.
         */
        val PLAYABLE = listOf(
            "audio/mpeg", "audio/mp3", "audio/mp4", "audio/x-m4a", "audio/m4a", "audio/aac", "audio/aacp",
            "audio/flac", "audio/x-flac", "audio/wav", "audio/x-wav", "audio/wave",
            "audio/ogg", "audio/opus", "audio/vorbis",
        )

        fun itemKind(kind: FavoriteKind): ItemKind = when (kind) {
            FavoriteKind.SONG -> ItemKind.TRACK
            FavoriteKind.ALBUM -> ItemKind.ALBUM
            FavoriteKind.ARTIST -> ItemKind.CONTRIBUTOR
        }

        fun sourceError(e: OwnAudioException): SourceError = when (e) {
            is OwnAudioException.SignedOut, is OwnAudioException.SignInRefused -> SourceError.SignInRejected(e)
            is OwnAudioException.Problem -> when (e.status) {
                401 -> SourceError.SignInRejected(e)
                403 -> SourceError.NotAllowed(e)
                404 -> SourceError.NotFound(e)
                else -> SourceError.Server(e.detail ?: e.code)
            }
            is OwnAudioException.Http -> if (e.status == 404) SourceError.NotFound(e) else SourceError.Http(e.status)
            is OwnAudioException.Decoding -> SourceError.Unreadable(e)
            is OwnAudioException.Network, is OwnAudioException.SnapshotTruncated -> SourceError.Network(e)
            is OwnAudioException.Offline -> SourceError.Offline()
            is OwnAudioException.TooOld -> SourceError.Server(e.message ?: "too old")
        }
    }
}
