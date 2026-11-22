package audio.own.musicplayer.platform

import audio.own.music.library.MusicSource
import audio.own.music.library.PlaylistWithSongs
import audio.own.music.library.SourceError
import audio.own.music.library.model.Album
import audio.own.music.library.model.Playlist
import audio.own.music.library.model.SearchResult
import audio.own.music.library.model.Song
import audio.own.musicplayer.R
import audio.own.musicplayer.app.UiText
import audio.own.musicplayer.library.FakeMusicSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The widget's links, the shortcut and the assistant: what each plays, and what it says when it can't. */
class PlayRequestsTest {
    private class Played(val songs: List<Song>, val startId: String?, val containerId: String?)

    private val played = mutableListOf<Played>()
    private var hasTrack = false
    private var resumed = 0
    private var offline = false
    private var downloaded = setOf<String>()
    private var source: MusicSource = object : MusicSource by FakeMusicSource(
        albums = listOf(Album("s|kob", "Kind of Blue", artist = "Miles Davis", songCount = 3), Album("s|bt", "Blue Train", artist = "John Coltrane")),
        playlists = listOf(Playlist("s|road", "Road Trip"), Playlist("s|gym", "Gym")),
    ) {
        override suspend fun playlist(id: String) = PlaylistWithSongs(Playlist(id, id), listOf(Song("$id-1", "First"), Song("$id-2", "Second")))
    }

    private val requests = PlayRequests(
        serversLoaded = {},
        activeServerId = { "s" },
        source = { source },
        player = object : PlayRequests.Player {
            override val hasTrack get() = this@PlayRequestsTest.hasTrack
            override fun resume() { resumed++ }
            override fun play(songs: List<Song>, startId: String?, containerId: String?) {
                played += Played(songs, startId, containerId)
                this@PlayRequestsTest.hasTrack = true
            }
        },
        isOffline = { offline },
        isDownloaded = { it in downloaded },
    )

    @Test
    fun `a widget's album link plays the album from the top`() = runTest {
        assertNull(requests.open(MusicLinks.Link.Play(MusicLinks.Kind.ALBUM, "s|kob")))
        assertEquals(listOf("s|kob-t1", "s|kob-t2", "s|kob-t3"), played.single().songs.map { it.id })
        assertEquals("album:s|kob", played.single().containerId)
        assertNull(requests.open(MusicLinks.Link.Play(MusicLinks.Kind.PLAYLIST, "s|road")))
        assertEquals("playlist:s|road", played.last().containerId)
    }

    @Test
    fun `another server's item says so and plays nothing`() = runTest {
        assertEquals(UiText(R.string.link_other_server), requests.open(MusicLinks.Link.Play(MusicLinks.Kind.ALBUM, "other|kob")))
        assertEquals(UiText(R.string.link_other_server), requests.open(MusicLinks.Link.Play(MusicLinks.Kind.ALBUM, "no-server-part")))
        assertTrue(played.isEmpty())
    }

    @Test
    fun `continue goes on with what is loaded, and says when nothing is`() = runTest {
        assertEquals(UiText(R.string.nothing_to_continue), requests.open(MusicLinks.Link.Resume))
        hasTrack = true
        assertNull(requests.playFromSearch("continue listening"))
        assertEquals(1, resumed)
    }

    @Test
    fun `voice finds my playlist, the album, or a bare name`() = runTest {
        assertNull(requests.playFromSearch("play my playlist road trip"))
        assertEquals("playlist:s|road", played.last().containerId)
        assertNull(requests.playFromSearch("Přehraj album Kind of Blue"))
        assertEquals("album:s|kob", played.last().containerId)
        assertNull(requests.playFromSearch("Gym"))
        assertEquals("playlist:s|gym", played.last().containerId)
        assertEquals(UiText.of(R.string.voice_not_found, "Symphony No 5"), requests.playFromSearch("play the album Symphony No. 5"))
    }

    @Test
    fun `a song is the last resort for a name`() = runTest {
        val songs = listOf(Song("s|x", "Something Else"), Song("s|y", "Yesterday"))
        source = object : MusicSource by FakeMusicSource(playlists = emptyList()) {
            override suspend fun search(text: String) = SearchResult(emptyList(), emptyList(), songs)
        }
        assertNull(requests.playFromSearch("yesterday"))
        assertEquals("s|y", played.single().startId)
    }

    @Test
    fun `offline only the downloaded songs play, and none at all is said`() = runTest {
        offline = true
        assertEquals(UiText(R.string.offline_none_downloaded), requests.open(MusicLinks.Link.Play(MusicLinks.Kind.ALBUM, "s|kob")))
        downloaded = setOf("s|kob-t2")
        assertNull(requests.open(MusicLinks.Link.Play(MusicLinks.Kind.ALBUM, "s|kob")))
        assertEquals(listOf("s|kob-t2"), played.single().songs.map { it.id })
    }

    @Test
    fun `a failing server's reason is what is said`() = runTest {
        source = FakeMusicSource().apply { failing = SourceError.Network() }
        assertEquals(UiText(audio.own.core.strings.R.string.error_network), requests.open(MusicLinks.Link.Play(MusicLinks.Kind.ALBUM, "s|a1")))
    }
}
