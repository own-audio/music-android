package audio.own.musicplayer.downloads

import audio.own.core.downloads.CollectionEntity
import audio.own.core.downloads.DownloadEntity
import audio.own.core.downloads.DownloadsSnapshot
import audio.own.music.library.AlbumSort
import audio.own.music.library.SourceError
import audio.own.music.library.model.Album
import audio.own.music.library.model.Song
import audio.own.musicplayer.library.FakeMusicSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Building the library from downloads, and the source the screens read offline. */
class OfflineLibraryTest {
    private fun song(id: String, album: String, track: Int, artist: String = "Kevin", genre: String = "Jazz") =
        Song("s|$id", "Song $id", album = "Album $album", artist = artist, albumId = "s|$album", artistId = "s|$artist", track = track, genre = genre, durationMs = 60_000)

    private fun entity(song: Song, at: Long, server: String = "s") = DownloadEntity(
        key = song.id, itemId = song.id, fileId = null, serverId = server, title = song.title, extensionHint = null,
        record = recordJson.encodeToString(SongRecord.serializer(), SongRecord.of(song)), state = DownloadEntity.DONE,
        fileName = "x/${song.id}.mp3", bytes = 1000, queuedAt = at, downloadedAt = at,
    )

    private val songs = listOf(song("b2", "b", 2), song("b1", "b", 1), song("a1", "a", 1, artist = "Aaron", genre = "Rock"))
    private var snapshot = DownloadsSnapshot(
        items = mapOf(
            "s|b2" to entity(songs[0], 30), "s|b1" to entity(songs[1], 20), "s|a1" to entity(songs[2], 10),
            "other|x" to entity(Song("other|x", "Elsewhere", albumId = "other|z"), 40, server = "other"),
            "s|queued" to entity(song("queued", "c", 1), 50).copy(state = DownloadEntity.QUEUED),
        ),
        collections = listOf(
            DownloadsSnapshot.CollectionView(
                CollectionEntity("playlist:s|p1", "playlist", "s", "s|p1", "Road Trip", null, null, recordJson.encodeToString(CollectionRecord.serializer(), CollectionRecord("s|p1", "Road Trip")), 0),
                listOf("s|a1", "s|b1", "s|missing"),
            ),
        ),
        loaded = true,
    )
    private val offline = OfflineLibrary({ snapshot }, "s")

    @Test
    fun `songs in album order, only this server's and only downloaded`() {
        assertEquals(listOf("s|a1", "s|b1", "s|b2"), offline.songs().map { it.id })
    }

    @Test
    fun `albums newest download first, by name, and their artists`() {
        assertEquals(listOf("s|b", "s|a"), offline.albumsByDownloadDate().map { it.id })
        assertEquals(listOf("s|a", "s|b"), offline.albums().map { it.id })
        assertEquals(2, offline.albumsByDownloadDate().first().songCount)
        assertEquals(listOf("Aaron", "Kevin"), offline.artists().map { it.name })
        assertEquals(listOf("s|b"), offline.albums("s|Kevin").map { it.id })
        assertEquals(listOf("Jazz", "Rock"), offline.genres().map { it.name })
    }

    @Test
    fun `a downloaded playlist lists what is on the phone in its order`() {
        assertEquals(listOf("Road Trip"), offline.playlists().map { it.name })
        assertEquals(listOf("s|a1", "s|b1"), offline.playlist("s|p1")!!.songs.map { it.id })
    }

    @Test
    fun `search matches title, artist and album`() {
        val found = offline.search("aaron")
        assertEquals(listOf("Aaron"), found.artists.map { it.name })
        assertEquals(listOf("s|a1"), found.songs.map { it.id })
    }

    @Test
    fun `offline with downloads only, the lists are the downloads`() = runTest {
        val server = FakeMusicSource()
        val source = OfflineAwareSource(server, offline, isOffline = { true }, wholeLibrary = { false })
        assertEquals(listOf("s|b", "s|a"), source.albums(AlbumSort.NEWEST, 10, 0).map { it.id })
        assertEquals(listOf("Aaron", "Kevin"), source.artists().map { it.name })
        assertEquals(3, source.songs(100, 0).size)
        assertTrue(source.randomSongs(100, null).all { it.id in setOf("s|a1", "s|b1", "s|b2") })
        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun `offline with the whole library, the catalogue answers`() = runTest {
        val server = FakeMusicSource()
        val source = OfflineAwareSource(server, offline, isOffline = { true }, wholeLibrary = { true })
        assertEquals(10, source.albums(AlbumSort.NAME, 10, 0).size)
        // Shuffle plays only what can play: the downloads.
        assertEquals(3, source.randomSongs(100, null).size)
        assertEquals(listOf("albums NAME 10 0"), server.calls)
    }

    @Test
    fun `an album opens from the catalogue offline, from the downloads when it isn't there`() = runTest {
        val server = FakeMusicSource(albums = listOf(Album("s|b", "Album b", songCount = 3)))
        val source = OfflineAwareSource(server, offline, isOffline = { true }, wholeLibrary = { false })
        assertEquals(3, source.album("s|b").songs.size)
        server.failing = SourceError.Offline()
        assertEquals(listOf("s|b1", "s|b2"), source.album("s|b").songs.map { it.id })
    }

    @Test
    fun `online, everything goes to the server`() = runTest {
        val server = FakeMusicSource()
        val source = OfflineAwareSource(server, offline, isOffline = { false }, wholeLibrary = { false })
        assertEquals(100, source.randomSongs(100, null).size)
        assertEquals(listOf("random 100 null"), server.calls)
    }

    @Test
    fun `a record keeps every field a song shows`() {
        val song = Song("s|t", "T", album = "A", artist = "R", albumId = "s|a", track = 3, discNumber = 2, durationMs = 1, suffix = "flac", rating = 4, chapterCount = 2)
        assertEquals(song, SongRecord.of(song).song())
    }
}
