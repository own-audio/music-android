package audio.own.musicplayer.downloads

import audio.own.core.downloads.DownloadsSnapshot
import audio.own.music.library.AlbumSort
import audio.own.music.library.AlbumWithSongs
import audio.own.music.library.MusicSource
import audio.own.music.library.PlaylistWithSongs
import audio.own.music.library.SourceError
import audio.own.music.library.db.CatalogueSearch
import audio.own.music.library.model.Album
import audio.own.music.library.model.Artist
import audio.own.music.library.model.Genre
import audio.own.music.library.model.Playlist
import audio.own.music.library.model.SearchResult
import audio.own.music.library.model.Song
import audio.own.music.library.ownaudio.OwnAudioSource
import java.text.Collator

/**
 * The library for screens with no server to ask: what is downloaded from one server, as the
 * iOS `OfflineLibrary` builds it. Only that server's, the same scope as the online screens.
 */
class OfflineLibrary(private val snapshot: () -> DownloadsSnapshot, private val serverId: String) {
    private val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }

    private fun downloaded(): List<DownloadedSong> = snapshot().songs(serverId)

    /** In album order: artist, album, disc, track. */
    fun songs(): List<Song> = downloaded().map { it.song }.sortedWith(
        compareBy<Song, String>(collator) { it.artist.orEmpty() }
            .thenBy(collator) { it.album.orEmpty() }
            .thenBy { it.discNumber ?: 1 }
            .thenBy { it.track ?: Int.MAX_VALUE }
            .thenBy(collator) { it.title },
    )

    /** One album per album id among the downloads, newest download first. */
    fun albumsByDownloadDate(): List<Album> {
        val records = snapshot().collections.filter { it.collection.kind == DownloadsModel.ALBUM && it.collection.serverId == serverId }
            .mapNotNull { it.record() }.associateBy { it.id }
        return downloaded().groupBy { it.song.albumId ?: it.song.album.orEmpty() }
            .filterKeys { it.isNotEmpty() }
            .map { (id, items) ->
                val first = items.first().song
                val record = records[first.albumId ?: id]
                val album = Album(
                    id = first.albumId ?: id,
                    name = record?.name ?: first.album.orEmpty(),
                    artist = record?.artist ?: first.artist,
                    artistId = record?.artistId ?: first.artistId,
                    songCount = items.size,
                    durationMs = items.sumOf { it.song.durationMs ?: 0 }.takeIf { it > 0 },
                    coverArt = record?.coverArt ?: first.coverArt ?: first.albumId,
                    year = record?.year,
                    genre = record?.genre ?: first.genre,
                )
                album to (items.maxOfOrNull { it.entity.downloadedAt ?: 0 } ?: 0)
            }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    fun albums(): List<Album> = albumsByDownloadDate().sortedWith(compareBy(collator) { it.name })

    fun albums(sort: AlbumSort): List<Album> = when (sort) {
        AlbumSort.NEWEST, AlbumSort.RECENTLY_PLAYED -> albumsByDownloadDate()
        AlbumSort.ARTIST -> albums().sortedWith(compareBy(collator) { it.artist.orEmpty() })
        AlbumSort.RANDOM -> albums().shuffled()
        else -> albums()
    }

    fun artists(): List<Artist> = albums().groupBy { it.artistId ?: it.artist.orEmpty() }
        .filterKeys { it.isNotEmpty() }
        .mapNotNull { (id, albums) -> albums.first().artist?.let { Artist(id, it, albumCount = albums.size) } }
        .sortedWith(compareBy(collator) { it.name })

    fun albums(artistId: String): List<Album> = albums().filter { it.artistId == artistId || (it.artistId == null && it.artist == artistId) }

    fun genres(): List<Genre> = downloaded().map { it.song }.groupBy { it.genre.orEmpty() }
        .filterKeys { it.isNotEmpty() }
        .map { (name, songs) -> Genre(name, name, albumCount = songs.mapNotNull { it.albumId }.distinct().size, songCount = songs.size) }
        .sortedWith(compareBy(collator) { it.name })

    fun albums(genre: Genre): List<Album> {
        val ids = songs().filter { it.genre.equals(genre.name, ignoreCase = true) || it.genre == genre.id }.mapNotNull { it.albumId }.toSet()
        return albums().filter { it.id in ids }
    }

    fun album(id: String): AlbumWithSongs? {
        val songs = songs().filter { it.albumId == id }
        if (songs.isEmpty()) return null
        val album = albumsByDownloadDate().firstOrNull { it.id == id } ?: return null
        return AlbumWithSongs(album, songs)
    }

    fun playlists(): List<Playlist> = snapshot().collections
        .filter { it.collection.kind == DownloadsModel.PLAYLIST && it.collection.serverId == serverId }
        .map { view -> view.record()?.playlist(view.keys.size) ?: Playlist(view.collection.itemId, view.collection.name, songCount = view.keys.size, coverArt = view.collection.coverId) }
        .sortedWith(compareBy(collator) { it.name })

    fun playlist(id: String): PlaylistWithSongs? {
        val snap = snapshot()
        val view = snap.collection(DownloadsModel.playlistCollection(id)) ?: return null
        val playlist = playlists().firstOrNull { it.id == id } ?: return null
        return PlaylistWithSongs(playlist, snap.songs(view).map { it.song })
    }

    /** Matches in title, artist and album among the downloads, by word starts as the catalogue's search. */
    fun search(text: String): SearchResult {
        fun matches(vararg values: String?) = CatalogueSearch.matches(text, values.toList())
        return SearchResult(
            artists = artists().filter { matches(it.name) },
            albums = albums().filter { matches(it.name, it.artist) },
            songs = songs().filter { matches(it.title, it.artist, it.album) },
        )
    }
}

/**
 * The source the library screens read: the server's, except offline. Offline with only the
 * downloads shown, every list is the downloads; with Whole Library When Offline, the catalogue
 * on the phone (the songs not downloaded are greyed out by the rows). An album opens from the
 * catalogue either way, so it lists all its songs.
 */
class OfflineAwareSource(
    val delegate: MusicSource,
    private val offline: OfflineLibrary,
    private val isOffline: () -> Boolean,
    private val wholeLibrary: () -> Boolean,
) : MusicSource by delegate {
    private val downloadsOnly: Boolean get() = isOffline() && !wholeLibrary()

    override suspend fun artists(): List<Artist> = if (downloadsOnly) offline.artists() else delegate.artists()

    override suspend fun albums(artistId: String): List<Album> = if (downloadsOnly) offline.albums(artistId) else delegate.albums(artistId)

    override suspend fun albums(sort: AlbumSort, limit: Int, offset: Int): List<Album> =
        if (downloadsOnly) offline.albums(sort).drop(offset).take(limit) else delegate.albums(sort, limit, offset)

    override suspend fun albums(genre: Genre, limit: Int): List<Album> = if (downloadsOnly) offline.albums(genre).take(limit) else delegate.albums(genre, limit)

    override suspend fun album(id: String): AlbumWithSongs {
        if (!isOffline()) return delegate.album(id)
        return try {
            delegate.album(id)
        } catch (e: SourceError) {
            offline.album(id) ?: throw e
        }
    }

    override suspend fun songs(limit: Int, offset: Int): List<Song> = if (downloadsOnly) offline.songs().drop(offset).take(limit) else delegate.songs(limit, offset)

    /** Offline only downloaded songs play, so a shuffle draws from them in either mode. */
    override suspend fun randomSongs(limit: Int, genre: Genre?): List<Song> {
        if (!isOffline()) return delegate.randomSongs(limit, genre)
        val songs = offline.songs().filter { genre == null || it.genre.equals(genre.name, ignoreCase = true) }
        if (songs.isEmpty()) throw SourceError.Offline()
        return songs.shuffled().take(limit)
    }

    override suspend fun genres(): List<Genre> = if (downloadsOnly) offline.genres() else delegate.genres()

    override suspend fun search(text: String): SearchResult = when {
        downloadsOnly -> offline.search(text)
        isOffline() && delegate is OwnAudioSource -> delegate.searchCatalogue(text)
        else -> delegate.search(text)
    }

    override suspend fun playlists(): List<Playlist> = if (downloadsOnly) offline.playlists() else delegate.playlists()

    override suspend fun playlist(id: String): PlaylistWithSongs {
        if (!isOffline()) return delegate.playlist(id)
        return offline.playlist(id) ?: delegate.playlist(id)
    }
}
