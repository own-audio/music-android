package audio.own.kit.jellyfin

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Against a real server, skipped unless `JELLYFIN_HOST`, `JELLYFIN_USER` and `JELLYFIN_PASSWORD`
 * are set. Read-only except for a favorite and a playlist, which are put back as they were; no
 * play is counted.
 */
class LiveServerTest {
    private val env = System.getenv()
    private val rawHost = env["JELLYFIN_HOST"].orEmpty()
    private val user = env["JELLYFIN_USER"].orEmpty()
    private val password = env["JELLYFIN_PASSWORD"].orEmpty()
    private val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
    private val info = ClientInfo("own.audio Music tests", "JVM test", "music-android-live-tests", "0", userAgent = "own.audio Music/live-test (JVM)")
    private val host by lazy { JellyfinClient.normalizeHost(rawHost)!! }

    @Before
    fun requireServer() {
        assumeTrue("JELLYFIN_HOST, JELLYFIN_USER and JELLYFIN_PASSWORD not set", rawHost.isNotEmpty() && user.isNotEmpty() && password.isNotEmpty())
    }

    /** Scoped to the first music library, as the app does by default. */
    private suspend fun client(): JellyfinClient {
        val session = JellyfinClient.signIn(host, user, password, info, http)
        val library = JellyfinClient(host, session, info, http).musicLibraries().firstOrNull()
        return JellyfinClient(host, session, info, http, libraryId = library?.id)
    }

    @Test
    fun `server info and sign-in, a wrong password is refused`() = runBlocking<Unit> {
        assertTrue(JellyfinClient.serverInfo(host, info, http).version.isNotEmpty())
        try {
            JellyfinClient.signIn(host, user, password + "x", info, http)
            fail("a wrong password was accepted")
        } catch (e: JellyfinError.Unauthorized) {
            // expected
        }
        assertEquals(ConnectionStatus.Ok, client().checkSession())
    }

    @Test
    fun `music libraries, artists, every album order, an album with its songs`() = runBlocking<Unit> {
        val api = client()
        assertTrue(api.musicLibraries().isNotEmpty())
        assertTrue(api.artists().isNotEmpty())
        for (sort in AlbumSort.entries) {
            if (sort == AlbumSort.Favorites) continue
            assertTrue("$sort is empty", api.albums(sort, limit = 5).isNotEmpty())
        }
        val album = api.albums(AlbumSort.Name, limit = 1).first()
        val (loaded, songs) = api.album(album.id)
        assertEquals(album.id, loaded.id)
        assertTrue(songs.isNotEmpty())
        assertTrue(songs.all { it.albumId == album.id && (it.duration ?: 0) > 0 })
        album.artistId?.let { artistId -> assertTrue(api.albums(artistId = artistId).any { it.id == album.id }) }
    }

    @Test
    fun `songs page by page, genres, random songs, search, instant mix`() = runBlocking<Unit> {
        val api = client()
        val first = api.songs(limit = 20)
        val second = api.songs(limit = 20, offset = 20)
        assertEquals(20, first.size)
        assertTrue(first.map { it.id }.intersect(second.map { it.id }.toSet()).isEmpty())
        val genre = api.genres().first { (it.songCount ?: 1) > 0 }
        assertTrue(api.albums(genreId = genre.id).isNotEmpty())
        assertEquals(10, api.randomSongs(limit = 10).size)
        val word = first[0].title.split(" ").first()
        assertFalse(api.search(word).isEmpty)
        assertTrue(api.instantMix(first[0].id, limit = 10).isNotEmpty())
    }

    @Test
    fun `a favorite set and cleared again`() = runBlocking<Unit> {
        val api = client()
        val song = api.songs(limit = 1, offset = 7).first()
        try {
            api.setFavorite(song.id, !song.isFavorite)
            assertEquals(!song.isFavorite, api.song(song.id).isFavorite)
        } finally {
            api.setFavorite(song.id, song.isFavorite)
        }
        assertEquals(song.isFavorite, api.song(song.id).isFavorite)
        api.favorites()
    }

    @Test
    fun `a playlist created, filled, renamed, trimmed and deleted`() = runBlocking<Unit> {
        val api = client()
        val songs = api.songs(limit = 3)
        val id = api.createPlaylist("music-android test", listOf(songs[0].id))
        try {
            api.addToPlaylist(id, listOf(songs[1].id, songs[2].id))
            var loaded = api.playlist(id)
            assertEquals(songs.map { it.id }, loaded.second.map { it.id })
            api.removeFromPlaylist(id, listOf(loaded.second[1].playlistItemId!!))
            loaded = api.playlist(id)
            assertEquals(listOf(songs[0].id, songs[2].id), loaded.second.map { it.id })
            // Last: the server writes an edited playlist out in the background, and a rename
            // straight after adding songs can be overwritten by that write.
            delay(1000)
            api.renamePlaylist(id, "music-android test renamed")
            assertEquals("music-android test renamed", api.playlist(id).first.name)
        } finally {
            api.deletePlaylist(id)
        }
        assertFalse(api.playlists().any { it.id == id })
    }

    @Test
    fun `stream urls return audio, and covers load without the token`() = runBlocking<Unit> {
        val api = client()
        val song = api.songs(limit = 1).first()
        http.newCall(Request.Builder().url(api.streamUrl(song.id)).header("Range", "bytes=0-1023").build()).execute().use {
            assertTrue(it.code == 200 || it.code == 206)
            assertTrue(it.header("Content-Type").orEmpty().startsWith("audio/"))
        }
        http.newCall(Request.Builder().url(api.streamUrl(song.id, maxBitrate = 128_000)).build()).execute().use {
            assertEquals("audio/mpeg", it.header("Content-Type"))
            // About a minute of 128 kbps or more; a transcode that failed would be an error page.
            assertTrue(it.body.bytes().size > 100_000)
        }
        http.newCall(Request.Builder().url(api.universalUrl(song.id, maxBitrate = 96_000)).build()).execute().use {
            assertTrue("universal: HTTP ${it.code}", it.isSuccessful)
            assertTrue(it.header("Content-Type").orEmpty().startsWith("audio/"))
            assertTrue(it.body.source().request(64 * 1024))
        }
        val cover = song.coverArt ?: return@runBlocking
        http.newCall(Request.Builder().url(api.imageUrl(cover, 200)).build()).execute().use {
            assertTrue(it.header("Content-Type").orEmpty().startsWith("image/"))
        }
    }

    @Test
    fun `lyrics - none is null, not an error`() = runBlocking<Unit> {
        val api = client()
        api.lyrics(api.songs(limit = 1).first().id)
    }

    @Test
    fun `quick connect - a code approved by a signed-in session gives this device its own session`() = runBlocking<Unit> {
        assumeTrue("Quick Connect is off on this server", JellyfinClient.isQuickConnectEnabled(host, info, http))
        val device = info.copy(deviceId = "music-android-quick-connect-test")
        val started = JellyfinClient.startQuickConnect(host, device, http)
        assertFalse(started.isApproved)
        // What the listener does in another Jellyfin app: approve the code while signed in.
        val approver = client()
        val approve = Request.Builder()
            .url(host.newBuilder().addPathSegments("QuickConnect/Authorize").addQueryParameter("code", started.code).build())
            .post(ByteArray(0).toRequestBody(null))
            .header("Authorization", info.header(approver.session.accessToken))
            .build()
        http.newCall(approve).execute().use { assertEquals(200, it.code) }
        assertTrue(JellyfinClient.checkQuickConnect(host, started.secret, device, http).isApproved)
        val session = JellyfinClient.finishQuickConnect(host, started.secret, device, http)
        val api = JellyfinClient(host, session, device, http)
        assertEquals(ConnectionStatus.Ok, api.checkSession())
        api.signOut()
        assertEquals(ConnectionStatus.Rejected, api.checkSession())
    }

    @Test
    fun `playback reporting is accepted, the session ends cleanly`() = runBlocking<Unit> {
        val api = client()
        val song = api.songs(limit = 1).first()
        api.reportStart(song.id)
        api.reportProgress(song.id, positionSecs = 5.0, isPaused = true)
        api.reportStopped(song.id, positionSecs = 5.0)
        api.signOut()
        assertEquals(ConnectionStatus.Rejected, api.checkSession())
    }

    @Test
    fun `the public info names a local address to try`() = runBlocking<Unit> {
        // Informational: behind Docker it can be a container address.
        println("local address present: ${JellyfinClient.serverInfo(host, info, http).localAddress != null}")
    }
}
