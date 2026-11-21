package audio.own.music.library

import audio.own.kit.jellyfin.ClientInfo
import audio.own.kit.jellyfin.JellyfinClient
import audio.own.music.library.db.CatalogueDatabase
import audio.own.music.library.jellyfin.JellyfinSource
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
 * The Jellyfin source against a real server, skipped unless `JELLYFIN_HOST`, `JELLYFIN_USER` and
 * `JELLYFIN_PASSWORD` are set. Scoped to the first music library, as the app is by default. The
 * favourite and playlist it makes are put back in the same test; no play is counted and no
 * session report is sent (a stop report can count one).
 */
@RunWith(RobolectricTestRunner::class)
class JellyfinSourceLiveTest {
    private val env = System.getenv()
    private val rawHost = env["JELLYFIN_HOST"].orEmpty()
    private val user = env["JELLYFIN_USER"].orEmpty()
    private val password = env["JELLYFIN_PASSWORD"].orEmpty()
    private val info = ClientInfo("own.audio Music tests", "JVM test", "music-android-live-tests", "0", userAgent = "own.audio Music/live-test (JVM)")

    @Volatile private var networkUp = true
    private val http = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            if (!networkUp) throw IOException("network down")
            chain.proceed(chain.request())
        }
        .build()
    private lateinit var db: CatalogueDatabase
    private lateinit var source: JellyfinSource

    @Before fun requireServer() = runBlocking {
        assumeTrue("JELLYFIN_HOST, JELLYFIN_USER and JELLYFIN_PASSWORD not set", rawHost.isNotEmpty() && user.isNotEmpty() && password.isNotEmpty())
        db = memoryDatabase()
        val host = JellyfinClient.normalizeHost(rawHost)!!
        val session = JellyfinClient.signIn(host, user, password, info, http)
        val all = JellyfinSource(SERVER, JellyfinClient(host, session, info, http), db)
        val library = all.musicLibraries().firstOrNull()
        source = JellyfinSource(SERVER, JellyfinClient(host, session, info, http, libraryId = library?.id), db)
    }

    @After fun close() {
        if (::db.isInitialized) db.close()
        // The session stays on the server's device list otherwise.
        if (::source.isInitialized) runBlocking { source.signOut() }
    }

    private suspend fun firstAlbum() = source.albums(AlbumSort.NAME, 10).first { it.songCount > 0 }

    @Test fun `the library reads into the catalogue and opens from it offline`() = runBlocking<Unit> {
        assertEquals(ConnectionStatus.OK, source.checkConnection())
        val result = source.syncCatalogue()!!
        assertTrue("no albums: $result", result.albums > 0)
        assertTrue("no songs: $result", result.songs > 0)
        networkUp = false
        try {
            val album = source.album(source.albums(AlbumSort.NAME, 20).first { it.songCount > 0 }.id)
            assertTrue(album.songs.isNotEmpty())
            assertTrue(source.artists().isNotEmpty())
        } finally {
            networkUp = true
        }
    }

    @Test fun `every album order, genres and an instant mix answer`() = runBlocking<Unit> {
        for (sort in source.albumSorts) source.albums(sort, 5)
        source.genres()
        assertTrue(source.instantMix(firstAlbum().id).isNotEmpty())
    }

    @Test fun `the original and a lower bitrate both answer with audio`() = runBlocking<Unit> {
        val song = source.album(firstAlbum().id).songs.first()
        for (link in listOf(source.playbackLink(song.id, 128), source.playbackLink(song.id))) {
            http.newCall(Request.Builder().url(link.url).header("Range", "bytes=0-4095").build()).execute().use {
                assertTrue("HTTP ${it.code}", it.code == 200 || it.code == 206)
            }
        }
        source.lyrics(song)
    }

    @Test fun `a favourite is made and put back`() = runBlocking<Unit> {
        val song = source.album(firstAlbum().id).songs.first()
        val was = song.isFavorite
        try {
            source.setFavorite(FavoriteKind.SONG, song.id, !was)
            assertEquals(!was, source.song(song.id).isFavorite)
        } finally {
            source.setFavorite(FavoriteKind.SONG, song.id, was)
        }
        assertEquals(was, source.song(song.id).isFavorite)
    }

    @Test fun `a playlist is made, renamed, filled, emptied and deleted`() = runBlocking<Unit> {
        val song = source.album(firstAlbum().id).songs.first()
        val name = "own.audio live test ${UUID.randomUUID()}"
        source.createPlaylist(name, emptyList())
        val made = source.playlists().first { it.name == name }
        try {
            source.renamePlaylist(made.id, "$name renamed")
            source.addToPlaylist(made.id, listOf(song.id))
            val filled = source.playlist(made.id)
            assertEquals(listOf(song.id), filled.songs.map { it.id })
            source.removeFromPlaylist(made.id, filled.songs.map { audio.own.music.library.model.PlaylistEntry(0, it.playlistEntryId) })
            assertTrue(source.playlist(made.id).songs.isEmpty())
            assertTrue(source.playlists().any { it.name == "$name renamed" })
        } finally {
            source.deletePlaylist(made.id)
        }
        assertTrue(source.playlists().none { it.id == made.id })
    }
}
