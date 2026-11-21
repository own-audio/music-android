package audio.own.music.library

import audio.own.kit.subsonic.SubsonicClient
import audio.own.music.library.db.CatalogueDatabase
import audio.own.music.library.subsonic.SubsonicSource
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Subsonic source against a real server, skipped unless `SUBSONIC_HOST`, `SUBSONIC_USER` and
 * `SUBSONIC_PASSWORD` are set. Every star, rating and playlist it makes is put back as it was in
 * the same test; no play is counted and nothing is reported as playing.
 */
@RunWith(RobolectricTestRunner::class)
class SubsonicSourceLiveTest {
    private val env = System.getenv()
    private val host = env["SUBSONIC_HOST"].orEmpty()
    private val user = env["SUBSONIC_USER"].orEmpty()
    private val password = env["SUBSONIC_PASSWORD"].orEmpty()

    @Volatile private var networkUp = true
    private val http = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            if (!networkUp) throw IOException("network down")
            chain.proceed(chain.request())
        }
        .build()
    private lateinit var db: CatalogueDatabase
    private lateinit var source: SubsonicSource

    @Before fun requireServer() {
        assumeTrue("SUBSONIC_HOST, SUBSONIC_USER and SUBSONIC_PASSWORD not set", host.isNotEmpty() && user.isNotEmpty() && password.isNotEmpty())
        db = memoryDatabase()
        source = SubsonicSource(SERVER, SubsonicClient(SubsonicClient.normalizeHost(host)!!, user, password, http, "own.audio Music/live-test (JVM)"), db)
    }

    @After fun close() {
        if (::db.isInitialized) db.close()
    }

    private suspend fun firstSong() = source.albums(AlbumSort.NAME, 10).first { it.songCount > 0 }.let { source.album(it.id).songs.first() }

    @Test fun `the whole library reads into the catalogue and opens from it offline`() = runBlocking<Unit> {
        assertEquals(ConnectionStatus.OK, source.checkConnection())
        val result = source.syncCatalogue()!!
        assertTrue("no albums: $result", result.albums > 0)
        assertTrue("no songs: $result", result.songs > 0)
        networkUp = false
        try {
            val albums = source.albums(AlbumSort.NAME, 20)
            assertTrue(albums.isNotEmpty())
            val album = source.album(albums.first { it.songCount > 0 }.id)
            assertTrue(album.songs.isNotEmpty())
            assertTrue(source.artists().isNotEmpty())
        } finally {
            networkUp = true
        }
    }

    @Test fun `every album order answers`() = runBlocking<Unit> {
        for (sort in source.albumSorts) source.albums(sort, 5)
        assertTrue(source.genres().isNotEmpty() || source.songs(5).isNotEmpty())
    }

    @Test fun `a lower bitrate stream and the original both answer with audio`() = runBlocking<Unit> {
        val song = firstSong()
        for (link in listOf(source.playbackLink(song.id, 128), source.playbackLink(song.id))) {
            http.newCall(Request.Builder().url(link.url).header("Range", "bytes=0-4095").build()).execute().use {
                assertTrue("HTTP ${it.code}", it.code == 200 || it.code == 206)
                assertTrue("type ${it.header("Content-Type")}", it.header("Content-Type").orEmpty().startsWith("audio/") || it.header("Content-Type") == "application/octet-stream")
            }
        }
        source.lyrics(song)
    }

    @Test fun `a star and a rating are made and put back`() = runBlocking<Unit> {
        val song = firstSong()
        val wasFavorite = song.isFavorite
        val rating = song.rating ?: 0
        try {
            source.setFavorite(FavoriteKind.SONG, song.id, !wasFavorite)
            assertEquals(!wasFavorite, source.favorites().songs.any { it.id == song.id })
            source.setRating(song.id, if (rating == 5) 4 else 5)
            assertEquals(if (rating == 5) 4 else 5, source.song(song.id).rating)
        } finally {
            source.setFavorite(FavoriteKind.SONG, song.id, wasFavorite)
            source.setRating(song.id, rating)
        }
        assertEquals(wasFavorite, source.song(song.id).isFavorite)
        assertEquals(rating, source.song(song.id).rating ?: 0)
    }

    @Test fun `a playlist is made, renamed, filled, emptied and deleted`() = runBlocking<Unit> {
        val song = firstSong()
        val name = "own.audio live test ${UUID.randomUUID()}"
        source.createPlaylist(name, emptyList())
        val made = source.playlists().first { it.name == name }
        try {
            source.renamePlaylist(made.id, "$name renamed")
            source.addToPlaylist(made.id, listOf(song.id))
            val filled = source.playlist(made.id)
            assertEquals("$name renamed", filled.playlist.name)
            assertEquals(listOf(song.id), filled.songs.map { it.id })
            source.removeFromPlaylist(made.id, listOf(audio.own.music.library.model.PlaylistEntry(0, null)))
            assertTrue(source.playlist(made.id).songs.isEmpty())
        } finally {
            source.deletePlaylist(made.id)
        }
        assertTrue(source.playlists().none { it.id == made.id })
    }
}
