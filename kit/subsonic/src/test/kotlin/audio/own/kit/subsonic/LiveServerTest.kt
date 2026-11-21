package audio.own.kit.subsonic

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Runs against a real server. Stubbed tests only prove we parse JSON we wrote ourselves; the real
 * risk with a third-party protocol is whether actual servers send what the models expect.
 *
 * Skipped unless configured:
 * ```
 * SUBSONIC_HOST=https://music.example.com SUBSONIC_USER=someone SUBSONIC_PASSWORD=… \
 *   ./gradlew :kit:subsonic:test --tests '*LiveServerTest*'
 * ```
 * Writes only a star, a rating and a playlist, and puts each back as it was. No play is counted.
 */
class LiveServerTest {
    private val env = System.getenv()
    private val host = env["SUBSONIC_HOST"].orEmpty()
    private val user = env["SUBSONIC_USER"].orEmpty()
    private val password = env["SUBSONIC_PASSWORD"].orEmpty()
    private val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()

    @Before
    fun requireServer() {
        assumeTrue("SUBSONIC_HOST, SUBSONIC_USER and SUBSONIC_PASSWORD not set", host.isNotEmpty() && user.isNotEmpty() && password.isNotEmpty())
    }

    private fun client(passwordSuffix: String = "") = SubsonicClient(
        SubsonicClient.normalizeHost(host)!!, user, password + passwordSuffix, http, "own.audio Music/live-test (JVM)",
    )

    private suspend fun firstAlbumWithSongs(api: SubsonicClient) = api.albumList(size = 10).first { it.songCount > 0 }

    @Test
    fun `the token and salt sign-in is accepted`() = runBlocking<Unit> {
        assertEquals(ConnectionStatus.Ok, client().checkConnection())
    }

    @Test
    fun `a wrong password is rejected, not reported as unreachable`() = runBlocking<Unit> {
        assertEquals(ConnectionStatus.Rejected, client(passwordSuffix = "-wrong").checkConnection())
    }

    @Test
    fun `artists decode`() = runBlocking<Unit> {
        val first = client().artists().first()
        assertTrue(first.id.isNotEmpty() && first.name.isNotEmpty())
    }

    @Test
    fun `albums decode despite the many fields the models ignore`() = runBlocking<Unit> {
        assertTrue(client().albumList(size = 10).first().songCount > 0)
    }

    @Test
    fun `every album list type answers`() = runBlocking<Unit> {
        val api = client()
        for (type in AlbumListType.entries) api.albumList(type, size = 5)
    }

    @Test
    fun `an album's songs decode`() = runBlocking<Unit> {
        val api = client()
        val target = firstAlbumWithSongs(api)
        val (album, songs) = api.album(target.id)
        assertEquals(target.id, album.id)
        assertEquals(target.songCount, songs.size)
    }

    /** Reads only the first kilobytes: enough to tell audio from an error page. */
    @Test
    fun `a stream url returns audio, not an error page`() = runBlocking<Unit> {
        val api = client()
        val song = api.album(firstAlbumWithSongs(api).id).second.first()
        for (url in listOf(api.streamUrl(song.id), api.streamUrl(song.id, maxBitRate = 128), api.downloadUrl(song.id))) {
            val request = Request.Builder().url(url).header("Range", "bytes=0-65535").build()
            http.newCall(request).execute().use { response ->
                assertTrue("HTTP ${response.code} for ${url.encodedPath}", response.code == 200 || response.code == 206)
                assertTrue("not a known audio container at ${url.encodedPath}", looksLikeAudio(response.body.source().peek().readByteArray(12)))
            }
        }
    }

    @Test
    fun `genres, a genre's albums and songs, a page of all songs, and random songs`() = runBlocking<Unit> {
        val api = client()
        val genre = api.genres().first { (it.albumCount ?: 0) > 0 }
        assertTrue(api.albums(genre = genre.value, size = 5).isNotEmpty())
        assertTrue(api.songs(genre = genre.value, count = 5).isNotEmpty())
        assertTrue(api.songs(size = 20).isNotEmpty())
        assertTrue(api.randomSongs(size = 10).isNotEmpty())
    }

    @Test
    fun `search finds an album by its name`() = runBlocking<Unit> {
        val api = client()
        val album = firstAlbumWithSongs(api)
        assertTrue(api.search(album.name).albums.any { it.id == album.id })
    }

    @Test
    fun `the server lists its OpenSubsonic extensions`() = runBlocking<Unit> {
        // Informational: a classic server has none, Navidrome has several.
        println("extensions: ${client().openSubsonicExtensions().sorted()}")
    }

    @Test
    fun `lyrics can be asked for`() = runBlocking<Unit> {
        val api = client()
        val useExtension = OpenSubsonicExtension.SONG_LYRICS in api.openSubsonicExtensions()
        val song = api.album(firstAlbumWithSongs(api).id).second.first()
        api.lyrics(song, useExtension)
    }

    @Test
    fun `star and rate a song, then restore it`() = runBlocking<Unit> {
        val api = client()
        val original = api.album(firstAlbumWithSongs(api).id).second.first()
        try {
            if (original.starred == null) {
                api.star(songIds = listOf(original.id))
                assertNotNull(api.song(original.id).starred)
                assertTrue(api.starred().songs.any { it.id == original.id })
            } else {
                api.unstar(songIds = listOf(original.id))
                assertNull(api.song(original.id).starred)
            }
            val newRating = if ((original.userRating ?: 0) == 3) 4 else 3
            api.setRating(original.id, newRating)
            assertEquals(newRating, api.song(original.id).userRating)
        } finally {
            if (original.starred == null) api.unstar(songIds = listOf(original.id)) else api.star(songIds = listOf(original.id))
            api.setRating(original.id, original.userRating ?: 0)
        }
        val after = api.song(original.id)
        assertEquals(original.starred == null, after.starred == null)
        assertEquals(original.userRating ?: 0, after.userRating ?: 0)
    }

    /** Only "now playing": a counted play would change the library's play counts. */
    @Test
    fun `now playing is accepted`() = runBlocking<Unit> {
        val api = client()
        val song = api.album(firstAlbumWithSongs(api).id).second.first()
        api.scrobble(song.id, submission = false)
    }

    /** Cleans up even on failure, and the name makes a leftover from a crashed run easy to spot. */
    @Test
    fun `playlist create, add, remove, delete`() = runBlocking<Unit> {
        val api = client()
        val song = api.album(firstAlbumWithSongs(api).id).second.first()
        val created = api.createPlaylist("music-android test ${UUID.randomUUID().toString().take(8)}")
        try {
            api.updatePlaylist(created.id, songIdsToAdd = listOf(song.id))
            assertEquals(listOf(song.id), api.playlist(created.id).second.map { it.id })
            api.updatePlaylist(created.id, songIndicesToRemove = listOf(0))
            assertTrue(api.playlist(created.id).second.isEmpty())
        } finally {
            api.deletePlaylist(created.id)
        }
        assertFalse(api.playlists().any { it.id == created.id })
    }

    @Test
    fun `form post works where the server offers it`() = runBlocking<Unit> {
        val plain = client()
        assumeTrue(OpenSubsonicExtension.FORM_POST in plain.openSubsonicExtensions())
        val api = plain.withFormPost(true)
        assertEquals(ConnectionStatus.Ok, api.checkConnection())
        assertTrue(api.artists().isNotEmpty())
    }

    private fun looksLikeAudio(magic: ByteArray): Boolean {
        fun startsWith(text: String) = magic.size >= text.length && String(magic, 0, text.length, Charsets.ISO_8859_1) == text
        return startsWith("fLaC") || startsWith("OggS") || startsWith("ID3") || startsWith("RIFF") ||
            (magic.size >= 2 && magic[0] == 0xFF.toByte() && (magic[1].toInt() and 0xE0) == 0xE0) ||
            (magic.size >= 8 && String(magic, 4, 4, Charsets.ISO_8859_1) == "ftyp")
    }
}
