package audio.own.musicplayer.library

import audio.own.music.library.AlbumSort
import audio.own.music.library.AlbumWithSongs
import audio.own.music.library.Capability
import audio.own.music.library.ConnectionStatus
import audio.own.music.library.FavoriteKind
import audio.own.music.library.MusicSource
import audio.own.music.library.PlaybackLink
import audio.own.music.library.PlaylistWithSongs
import audio.own.music.library.SourceError
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
import java.time.Instant

/** A server in memory: [albums] pages by offset, songs per album, and a call log. */
class FakeMusicSource(
    val albums: List<Album> = (1..150).map { Album("s|a$it", "Album $it", artist = "Artist ${it % 7}", songCount = 3) },
    val playlists: List<Playlist> = listOf(Playlist("s|p1", "Mix", songCount = 12)),
    /** A song's artist by song id; none when missing. */
    val songArtists: Map<String, String> = emptyMap(),
) : MusicSource {
    val calls = mutableListOf<String>()
    var failing: Exception? = null
    var recentFails = false

    override val serverId = "s"
    override val capabilities = setOf(Capability.RATINGS)
    override val albumSorts = AlbumSort.entries.toList()

    fun songsOf(albumId: String) = (1..3).map { Song("$albumId-t$it", "Song $it", album = albumId, albumId = albumId, artistId = songArtists["$albumId-t$it"], track = it, durationMs = 60_000) }

    private fun check() { failing?.let { throw it } }

    override suspend fun checkConnection() = ConnectionStatus.OK
    override suspend fun artists() = listOf(Artist("s|r1", "Artist 1", albumCount = 2))
    override suspend fun albums(artistId: String) = albums.take(2)
    override suspend fun albums(sort: AlbumSort, limit: Int, offset: Int): List<Album> {
        calls += "albums $sort $limit $offset"
        check()
        if (sort == AlbumSort.RECENTLY_PLAYED && recentFails) throw SourceError.Network()
        return albums.drop(offset).take(limit)
    }
    override suspend fun albums(genre: Genre, limit: Int) = albums.take(limit)
    override suspend fun album(id: String): AlbumWithSongs { check(); return AlbumWithSongs(albums.first { it.id == id }, songsOf(id)) }
    override suspend fun song(id: String) = Song(id, "Song")
    override suspend fun songs(limit: Int, offset: Int) = (offset until minOf(offset + limit, 230)).map { Song("s|t$it", "Song $it") }
    override suspend fun randomSongs(limit: Int, genre: Genre?): List<Song> { calls += "random $limit ${genre?.id}"; check(); return (1..limit).map { Song("s|r$it", "R $it") } }
    override suspend fun genres() = listOf(Genre("g1", "Jazz", 2, 10))
    override suspend fun search(text: String): SearchResult {
        calls += "search $text"
        check()
        return SearchResult(emptyList(), albums.filter { it.name.contains(text) }.take(3), emptyList())
    }
    override suspend fun instantMix(itemId: String): List<Song> = throw SourceError.Unsupported()
    override suspend fun favorites() = FavoritesResult(emptyList(), emptyList(), emptyList())
    override suspend fun setFavorite(kind: FavoriteKind, id: String, isFavorite: Boolean) { calls += "favorite $id $isFavorite" }
    override suspend fun setRating(id: String, rating: Int) { calls += "rate $id $rating" }
    override suspend fun playlists(): List<Playlist> { check(); return playlists }
    override suspend fun playlist(id: String) = PlaylistWithSongs(playlists.first(), emptyList())
    override suspend fun createPlaylist(name: String, songIds: List<String>) {}
    override suspend fun addToPlaylist(id: String, songIds: List<String>) {}
    override suspend fun removeFromPlaylist(id: String, entries: List<PlaylistEntry>) {}
    override suspend fun renamePlaylist(id: String, name: String) {}
    override suspend fun deletePlaylist(id: String) {}
    override suspend fun lyrics(song: Song): List<Lyrics> = emptyList()
    override suspend fun reportNowPlaying(songId: String, positionMs: Long) {}
    override suspend fun reportProgress(songId: String, positionMs: Long, isPaused: Boolean) {}
    override suspend fun reportStopped(songId: String, positionMs: Long) {}
    override suspend fun reportPlayed(songId: String, at: Instant) {}
    override suspend fun playbackLink(songId: String, maxBitrateKbps: Int?) = PlaybackLink("https://example.com/$songId")
    override suspend fun downloadLink(songId: String) = PlaybackLink("https://example.com/$songId")
    override suspend fun coverLink(id: String, size: Int) = "https://example.com/cover/$id"
    override suspend fun musicLibraries(): List<MusicLibraryInfo> = emptyList()
    override suspend fun signOut() {}
}
