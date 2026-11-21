package audio.own.kit.subsonic

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class SubsonicClientTest {
    private val stubs = mutableListOf<Stub>()

    @After
    fun tearDown() = stubs.forEach { it.close() }

    private fun stub(handler: (mockwebserver3.RecordedRequest) -> MockResponse) = Stub(handler).also { stubs += it }

    private fun stubBody(body: String) = stub { Stub.ok(body) }

    private fun client(stub: Stub, username: String = "u", password: String = "p", formPost: Boolean = false) =
        SubsonicClient(stub.host, username, password, stub.http, USER_AGENT, useFormPost = formPost)

    private suspend inline fun <reified E : SubsonicError> assertThrows(expected: E? = null, block: () -> Unit) {
        try {
            block()
            fail("expected ${E::class.simpleName}")
        } catch (e: SubsonicError) {
            assertTrue("got $e", e is E)
            if (expected != null) assertEquals(expected, e)
        }
    }

    // normalizeHost

    @Test
    fun `a bare host defaults to http`() {
        assertEquals("http://music.example.com/", SubsonicClient.normalizeHost("music.example.com").toString())
    }

    @Test
    fun `an explicit https is kept`() {
        assertEquals("https://music.example.com/", SubsonicClient.normalizeHost("https://music.example.com").toString())
    }

    @Test
    fun `a port is kept`() {
        assertEquals("http://192.168.1.10:4533/", SubsonicClient.normalizeHost("192.168.1.10:4533").toString())
    }

    @Test
    fun `empty or whitespace-only input is rejected`() {
        assertNull(SubsonicClient.normalizeHost("   "))
        assertNull(SubsonicClient.normalizeHost(""))
    }

    // Auth

    @Test
    fun `every request carries u t s v c f and t is md5 of password and salt`() = runTest {
        val stub = stubBody(envelope())
        client(stub, username = "alice", password = "secret").checkConnection()
        val q = stub.firstQuery()
        assertEquals(listOf("alice"), q["u"])
        assertEquals(listOf("1.16.1"), q["v"])
        assertEquals(listOf("own.audio Music"), q["c"])
        assertEquals(listOf("json"), q["f"])
        assertNull(q["p"])
        val salt = q.getValue("s").single()!!
        assertEquals(SubsonicClient.md5Hex("secret$salt"), q.getValue("t").single())
        assertEquals("098f6bcd4621d373cade4e832627b4f6", SubsonicClient.md5Hex("test"))
    }

    @Test
    fun `the user agent is sent`() = runTest {
        val stub = stubBody(envelope())
        client(stub).checkConnection()
        assertEquals(USER_AGENT, stub.requests.first().headers["User-Agent"])
    }

    @Test
    fun `two requests use two different salts`() = runTest {
        val stub = stubBody(envelope())
        val api = client(stub)
        api.checkConnection()
        api.checkConnection()
        val salts = stub.requests.map { it.url.queryParameter("s") }
        assertEquals(2, salts.size)
        assertNotEquals(salts[0], salts[1])
    }

    // Browsing

    @Test
    fun `artists flattens the index groups`() = runTest {
        val stub = stubBody(
            envelope(
                """
                "artists":{"ignoredArticles":"","index":[
                    {"name":"A","artist":[{"id":"ar-2","name":"ABBA","albumCount":3}]},
                    {"name":"Q","artist":[{"id":"ar-1","name":"Queen","albumCount":19}]}
                ]}
                """,
            ),
        )
        val artists = client(stub).artists()
        assertEquals(listOf("ABBA", "Queen"), artists.map { it.name })
        assertEquals(19, artists.last().albumCount)
    }

    @Test
    fun `an empty library (no index key) decodes as no artists`() = runTest {
        val stub = stubBody(envelope(""""artists":{"ignoredArticles":""}"""))
        assertTrue(client(stub).artists().isEmpty())
    }

    @Test
    fun `album decodes its songs and its own cover and year`() = runTest {
        val stub = stubBody(
            envelope(
                """
                "album":{"id":"al-1","name":"A Night At The Opera","artist":"Queen","artistId":"ar-1","coverArt":"al-1","year":1975,"song":[
                    {"id":"so-1","title":"Death On Two Legs","track":1,"discNumber":1,"duration":221,"genre":"Rock","suffix":"flac"},
                    {"id":"so-2","title":"Bohemian Rhapsody","track":11,"duration":354}
                ]}
                """,
            ),
        )
        val (album, songs) = client(stub).album("al-1")
        assertEquals("A Night At The Opera", album.name)
        assertEquals("al-1", album.coverArt)
        assertEquals(1975, album.year)
        assertEquals(2, album.songCount)
        assertEquals(listOf("so-1", "so-2"), songs.map { it.id })
        assertEquals("flac", songs.first().suffix)
        assertNull(songs.last().genre)
    }

    @Test
    fun `artist returns its albums`() = runTest {
        val stub = stubBody(
            envelope(""""artist":{"id":"ar-1","name":"Queen","artistImageUrl":"https://x/a?size=600","album":[{"id":"al-1","name":"A","songCount":3}]}"""),
        )
        val (artist, albums) = client(stub).artist("ar-1")
        assertEquals("Queen", artist.name)
        assertEquals(1, artist.albumCount)
        assertEquals(listOf("al-1"), albums.map { it.id })
        assertEquals(listOf("ar-1"), stub.firstQuery()["id"])
    }

    @Test
    fun `albumList sends the list type`() = runTest {
        val stub = stubBody(envelope(""""albumList2":{}"""))
        val albums = client(stub).albumList(AlbumListType.Newest, size = 20)
        assertTrue(albums.isEmpty())
        val q = stub.firstQuery()
        assertEquals(listOf("newest"), q["type"])
        assertEquals(listOf("20"), q["size"])
    }

    @Test
    fun `every album list type has its wire name`() {
        assertEquals(
            listOf("alphabeticalByArtist", "alphabeticalByName", "newest", "recent", "frequent", "random", "starred", "highest"),
            AlbumListType.entries.map { it.wireName },
        )
    }

    @Test
    fun `albums by year send the range`() = runTest {
        val stub = stubBody(envelope(""""albumList2":{}"""))
        client(stub).albums(fromYear = 1970, toYear = 1979)
        val q = stub.firstQuery()
        assertEquals(listOf("byYear"), q["type"])
        assertEquals(listOf("1970"), q["fromYear"])
        assertEquals(listOf("1979"), q["toYear"])
    }

    @Test
    fun `allAlbums keeps paging until a short page`() = runTest {
        val stub = stub { request ->
            val offset = request.url.queryParameter("offset") ?: "0"
            val count = if (offset == "0") 500 else 3
            val albums = (0 until count).joinToString(",") { """{"id":"al-$offset-$it","name":"x","songCount":1}""" }
            Stub.ok(envelope(""""albumList2":{"album":[$albums]}"""))
        }
        assertEquals(503, client(stub).allAlbums().size)
        assertEquals(2, stub.requests.size)
    }

    @Test
    fun `search splits artists, albums and songs`() = runTest {
        val stub = stubBody(
            envelope(""""searchResult3":{"artist":[{"id":"ar-1","name":"Queen","albumCount":1}],"song":[{"id":"so-1","title":"Bohemian Rhapsody"}]}"""),
        )
        val result = client(stub).search("queen")
        assertEquals(1, result.artists.size)
        assertTrue(result.albums.isEmpty())
        assertEquals("Bohemian Rhapsody", result.songs.first().title)
        assertEquals(listOf("queen"), stub.firstQuery()["query"])
    }

    // Playlists

    @Test
    fun `playlists decodes the flat array`() = runTest {
        val stub = stubBody(
            envelope(
                """
                "playlists":{"playlist":[
                    {"id":"pl-1","name":"Road Trip","songCount":12,"duration":0,"owner":"alice"},
                    {"id":"pl-2","name":"Focus","comment":"Deep work","songCount":40,"duration":9600,"coverArt":"pl-2"}
                ]}
                """,
            ),
        )
        val playlists = client(stub).playlists()
        assertEquals(listOf("pl-1", "pl-2"), playlists.map { it.id })
        assertEquals("alice", playlists.first().owner)
        assertEquals("Deep work", playlists.last().comment)
    }

    @Test
    fun `playlists is empty when the server sends no playlist key`() = runTest {
        assertTrue(client(stubBody(envelope(""""playlists":{}"""))).playlists().isEmpty())
    }

    @Test
    fun `playlist decodes its entries as songs`() = runTest {
        val stub = stubBody(
            envelope(
                """
                "playlist":{"id":"pl-1","name":"Road Trip","owner":"alice","songCount":2,"duration":0,"entry":[
                    {"id":"so-1","title":"Death On Two Legs"},{"id":"so-2","title":"Bohemian Rhapsody"}
                ]}
                """,
            ),
        )
        val (playlist, songs) = client(stub).playlist("pl-1")
        assertEquals("Road Trip", playlist.name)
        assertEquals(listOf("so-1", "so-2"), songs.map { it.id })
    }

    @Test
    fun `createPlaylist sends the name and every songId`() = runTest {
        val stub = stubBody(envelope(""""playlist":{"id":"pl-9","name":"New Mix","songCount":2}"""))
        val created = client(stub).createPlaylist("New Mix", listOf("so-1", "so-2"))
        assertEquals("pl-9", created.id)
        val q = stub.firstQuery()
        assertEquals(listOf("New Mix"), q["name"])
        assertEquals(listOf("so-1", "so-2"), q["songId"])
    }

    @Test
    fun `updatePlaylist sends only what is being changed`() = runTest {
        val stub = stubBody(envelope())
        client(stub).updatePlaylist("pl-1", songIdsToAdd = listOf("so-3"))
        val q = stub.firstQuery()
        assertEquals(listOf("pl-1"), q["playlistId"])
        assertEquals(listOf("so-3"), q["songIdToAdd"])
        assertFalse("songIndexToRemove" in q)
        assertFalse("name" in q)
        assertFalse("comment" in q)
    }

    @Test
    fun `updatePlaylist sends songIndexToRemove`() = runTest {
        val stub = stubBody(envelope())
        client(stub).updatePlaylist("pl-1", songIndicesToRemove = listOf(2))
        assertEquals(listOf("2"), stub.firstQuery()["songIndexToRemove"])
    }

    @Test
    fun `deletePlaylist sends the id`() = runTest {
        val stub = stubBody(envelope())
        client(stub).deletePlaylist("pl-1")
        assertEquals(listOf("pl-1"), stub.firstQuery()["id"])
        assertEquals("/rest/deletePlaylist.view", stub.requests.first().url.encodedPath)
    }

    // Favorites, ratings, scrobbling

    @Test
    fun `star sends songs as id, albums as albumId, artists as artistId`() = runTest {
        val stub = stubBody(envelope())
        client(stub).star(songIds = listOf("so-1"), albumIds = listOf("al-1"), artistIds = listOf("ar-1"))
        val q = stub.firstQuery()
        assertEquals("/rest/star.view", stub.requests.first().url.encodedPath)
        assertEquals(listOf("so-1"), q["id"])
        assertEquals(listOf("al-1"), q["albumId"])
        assertEquals(listOf("ar-1"), q["artistId"])
    }

    @Test
    fun `unstar uses its own endpoint`() = runTest {
        val stub = stubBody(envelope())
        client(stub).unstar(songIds = listOf("so-1"))
        assertEquals("/rest/unstar.view", stub.requests.first().url.encodedPath)
    }

    @Test
    fun `a rating is clamped to 0 to 5`() = runTest {
        val stub = stubBody(envelope())
        client(stub).setRating("so-1", 9)
        assertEquals(listOf("5"), stub.firstQuery()["rating"])
    }

    @Test
    fun `scrobble sends submission and the start time in milliseconds`() = runTest {
        val stub = stubBody(envelope())
        client(stub).scrobble("so-1", submission = true, time = Instant.ofEpochSecond(1_700_000_000))
        val q = stub.firstQuery()
        assertEquals(listOf("true"), q["submission"])
        assertEquals(listOf("1700000000000"), q["time"])
    }

    @Test
    fun `starred decodes songs, albums and artists`() = runTest {
        val stub = stubBody(
            envelope(
                """"starred2":{"song":[{"id":"so-1","title":"A","starred":"2026-01-01T00:00:00Z","userRating":4}],"album":[{"id":"al-1","name":"B","songCount":3,"starred":"2026-01-01T00:00:00Z"}]}""",
            ),
        )
        val starred = client(stub).starred()
        assertNotNull(starred.songs.first().starred)
        assertEquals(4, starred.songs.first().userRating)
        assertEquals(1, starred.albums.size)
        assertTrue(starred.artists.isEmpty())
    }

    @Test
    fun `extensions are read by name, a classic server's error means none`() = runTest {
        val open = stubBody(
            envelope(""""openSubsonicExtensions":[{"name":"songLyrics","versions":[1,2]},{"name":"transcodeOffset","versions":[1]}]"""),
        )
        val classic = stubBody(envelope(""""error":{"code":0,"message":"unknown"}""", status = "failed"))
        assertEquals(setOf("songLyrics", "transcodeOffset"), client(open).openSubsonicExtensions())
        assertTrue(client(classic).openSubsonicExtensions().isEmpty())
    }

    // Genres, all songs, random

    @Test
    fun `genres decode and sort by name`() = runTest {
        val stub = stubBody(
            envelope(""""genres":{"genre":[{"value":"Rock","albumCount":3,"songCount":30},{"value":"ambient","albumCount":1,"songCount":4}]}"""),
        )
        val genres = client(stub).genres()
        assertEquals(listOf("ambient", "Rock"), genres.map { it.value })
        assertEquals(30, genres.last().songCount)
    }

    @Test
    fun `albums by genre send type byGenre and the genre`() = runTest {
        val stub = stubBody(envelope(""""albumList2":{}"""))
        client(stub).albums(genre = "Rock & Roll")
        val q = stub.firstQuery()
        assertEquals(listOf("byGenre"), q["type"])
        assertEquals(listOf("Rock & Roll"), q["genre"])
    }

    @Test
    fun `songs by genre page with count and offset`() = runTest {
        val stub = stubBody(envelope(""""songsByGenre":{"song":[{"id":"1","title":"a"}]}"""))
        val songs = client(stub).songs(genre = "Jazz", count = 50, offset = 50)
        assertEquals(1, songs.size)
        assertEquals("/rest/getSongsByGenre.view", stub.requests.first().url.encodedPath)
        val q = stub.firstQuery()
        assertEquals(listOf("Jazz"), q["genre"])
        assertEquals(listOf("50"), q["count"])
        assertEquals(listOf("50"), q["offset"])
    }

    @Test
    fun `all songs is an empty search3 asking for songs only, paged`() = runTest {
        val stub = stubBody(envelope(""""searchResult3":{"song":[{"id":"1","title":"a"}]}"""))
        assertEquals(1, client(stub).songs(size = 50, offset = 100).size)
        val q = stub.firstQuery()
        assertEquals(listOf(""), q["query"])
        assertEquals(listOf("100"), q["songOffset"])
        assertEquals(listOf("0"), q["albumCount"])
    }

    @Test
    fun `random songs decode`() = runTest {
        val stub = stubBody(envelope(""""randomSongs":{"song":[{"id":"1","title":"a"},{"id":"2","title":"b"}]}"""))
        assertEquals(2, client(stub).randomSongs(size = 2).size)
        assertEquals(listOf("2"), stub.firstQuery()["size"])
    }

    // Lyrics and ReplayGain

    @Test
    fun `structured lyrics decode with their timing`() = runTest {
        val stub = stubBody(
            envelope(""""lyricsList":{"structuredLyrics":[{"lang":"eng","synced":true,"offset":-100,"line":[{"start":1000,"value":"One"},{"start":5000,"value":"Two"}]}]}"""),
        )
        val first = client(stub).lyrics(Song(id = "so-1", title = "T"), useSongLyricsExtension = true).first()
        assertTrue(first.isSynced)
        assertEquals(-100, first.offsetMs)
        assertEquals("eng", first.language)
        assertEquals(listOf("One", "Two"), first.lines.map { it.text })
        assertEquals("/rest/getLyricsBySongId.view", stub.requests.first().url.encodedPath)
    }

    @Test
    fun `an empty lyrics list means none, not an error`() = runTest {
        val stub = stubBody(envelope(""""lyricsList":{}"""))
        assertTrue(client(stub).lyrics(Song(id = "so-1", title = "T"), useSongLyricsExtension = true).isEmpty())
    }

    @Test
    fun `without the extension, classic getLyrics by artist and title gives plain lines`() = runTest {
        val stub = stubBody(envelope(""""lyrics":{"artist":"A","title":"T","value":"Line one\nLine two\n"}"""))
        val lyrics = client(stub).lyrics(Song(id = "so-1", title = "T", artist = "A"), useSongLyricsExtension = false)
        assertFalse(lyrics.first().isSynced)
        assertEquals(listOf("Line one", "Line two"), lyrics.first().lines.map { it.text })
        val q = stub.firstQuery()
        assertEquals(listOf("A"), q["artist"])
        assertEquals(listOf("T"), q["title"])
    }

    @Test
    fun `classic lyrics with an empty value are none`() = runTest {
        val stub = stubBody(envelope(""""lyrics":{"artist":"A","title":"T","value":""}"""))
        assertTrue(client(stub).lyrics(Song(id = "so-1", title = "T", artist = "A"), useSongLyricsExtension = false).isEmpty())
    }

    @Test
    fun `replayGain decodes, and an empty object is empty`() = runTest {
        val stub = stubBody(
            envelope(
                """
                "album":{"id":"al","name":"A","song":[
                    {"id":"1","title":"a","replayGain":{"trackGain":-6.5,"albumGain":-5,"trackPeak":0.9,"albumPeak":0.95}},
                    {"id":"2","title":"b","replayGain":{}}
                ]}
                """,
            ),
        )
        val songs = client(stub).album("al").second
        assertEquals(-6.5, songs[0].replayGain!!.trackGain!!, 0.0)
        assertEquals(0.95, songs[0].replayGain!!.albumPeak!!, 0.0)
        assertTrue(songs[1].replayGain!!.isEmpty)
    }

    // Errors (HTTP 200 with status "failed")

    @Test
    fun `a failed write surfaces the server's code and message`() = runTest {
        val stub = stubBody(envelope(""""error":{"code":70,"message":"Playlist not found"}""", status = "failed"))
        assertThrows(SubsonicError.Server(70, "Playlist not found")) {
            client(stub).updatePlaylist("gone", songIdsToAdd = listOf("so-1"))
        }
    }

    @Test
    fun `code 40 is wrong credentials, not a decoding failure`() = runTest {
        val stub = stubBody(envelope(""""error":{"code":40,"message":"Wrong username or password"}""", status = "failed"))
        assertThrows(SubsonicError.WrongCredentials) { client(stub, password = "wrong").artists() }
    }

    @Test
    fun `codes 41 and 42 mean token sign-in is not accepted`() = runTest {
        for (code in listOf(41, 42)) {
            val stub = stubBody(envelope(""""error":{"code":$code,"message":"Token authentication not supported"}""", status = "failed"))
            assertThrows(SubsonicError.TokenAuthUnsupported) { client(stub).artists() }
            assertEquals(ConnectionStatus.Unsupported, client(stub).checkConnection())
        }
    }

    @Test
    fun `Ampache's 40 means token sign-in is not accepted`() = runTest {
        val stub = stubBody(
            """{"subsonic-response":{"status":"failed","version":"1.16.1","type":"ampache","serverVersion":"8.2.2","openSubsonic":true,"error":{"code":40,"message":"Wrong username or password."}}}""",
        )
        assertEquals(ConnectionStatus.Unsupported, client(stub).checkConnection())
        assertThrows(SubsonicError.TokenAuthUnsupported) { client(stub).artists() }
    }

    @Test
    fun `the server names itself even when the sign-in fails`() = runTest {
        val stub = stubBody(
            """{"subsonic-response":{"status":"failed","version":"1.16.1","type":"navidrome","serverVersion":"0.64.2","openSubsonic":true,"error":{"code":40,"message":"x"}}}""",
        )
        assertEquals(ServerIdentity("navidrome", "0.64.2", true), client(stub).serverIdentity())
        assertEquals(ServerIdentity(), client(stubBody(envelope())).serverIdentity())
    }

    @Test
    fun `code 50 is not authorized`() = runTest {
        val stub = stubBody(envelope(""""error":{"code":50,"message":"no"}""", status = "failed"))
        assertThrows(SubsonicError.NotAuthorized) { client(stub).deletePlaylist("pl-1") }
    }

    @Test
    fun `an HTTP error status is reported as such`() = runTest {
        val stub = stub { Stub.ok("", code = 502) }
        assertThrows(SubsonicError.Http(502)) { client(stub).artists() }
    }

    @Test
    fun `a non-Subsonic body (a proxy's HTML page) is a decoding error`() = runTest {
        val stub = stubBody("<html></html>")
        assertThrows(SubsonicError.Decoding) { client(stub).artists() }
    }

    @Test
    fun `checkConnection - rejected, ok, unreachable`() = runTest {
        val rejected = stubBody(envelope(""""error":{"code":40,"message":"x"}""", status = "failed"))
        val ok = stubBody(envelope())
        val down = stubBody(envelope()).apply { networkUp = false }
        assertEquals(ConnectionStatus.Rejected, client(rejected).checkConnection())
        assertEquals(ConnectionStatus.Ok, client(ok).checkConnection())
        assertEquals(ConnectionStatus.Unreachable, client(down).checkConnection())
        assertEquals("/rest/ping.view", ok.requests.first().url.encodedPath)
    }

    @Test
    fun `form post puts the sign-in and parameters in the body, not the url`() = runTest {
        val stub = stubBody(envelope(""""playlist":{"id":"pl-9","name":"New Mix","songCount":2}"""))
        client(stub, formPost = true).createPlaylist("New Mix", listOf("so-1", "so-2"))
        val request = stub.requests.first()
        assertEquals("POST", request.method)
        assertEquals("/rest/createPlaylist.view", request.url.encodedPath)
        assertNull(request.url.query)
        val body = request.body!!.utf8()
        assertTrue(body, body.contains("songId=so-1&songId=so-2"))
        assertTrue(body, body.contains("u=u&t="))
        assertTrue(body, body.contains("name=New+Mix"))
    }

    // URLs

    private val host = "http://music.example.com".toHttpUrl()

    private fun offlineClient(username: String = "alice", password: String = "secret") =
        SubsonicClient(host, username, password, OkHttpClient(), USER_AGENT)

    @Test
    fun `streamUrl builds rest stream view with the id and auth`() {
        val api = offlineClient()
        val url = api.streamUrl("so-1")
        assertEquals("/rest/stream.view", url.encodedPath)
        assertEquals("so-1", url.queryParameter("id"))
        assertEquals("alice", url.queryParameter("u"))
        assertNull(url.queryParameter("maxBitRate"))
        val smaller = api.streamUrl("so-1", maxBitRate = 192)
        assertEquals("192", smaller.queryParameter("maxBitRate"))
        assertEquals("mp3", smaller.queryParameter("format"))
        assertNull(smaller.queryParameter("timeOffset"))
        assertEquals("30", api.streamUrl("so-1", maxBitRate = 192, timeOffsetSecs = 30).queryParameter("timeOffset"))
    }

    @Test
    fun `downloadUrl asks for the original`() {
        val url = offlineClient().downloadUrl("so-1")
        assertEquals("/rest/download.view", url.encodedPath)
        assertEquals("so-1", url.queryParameter("id"))
        assertNotNull(url.queryParameter("t"))
    }

    @Test
    fun `a server under a sub-path keeps it`() {
        val api = SubsonicClient("https://example.com/navidrome".toHttpUrl(), "u", "p", OkHttpClient(), USER_AGENT)
        assertEquals("/navidrome/rest/stream.view", api.streamUrl("1").encodedPath)
        val slash = SubsonicClient("https://example.com/navidrome/".toHttpUrl(), "u", "p", OkHttpClient(), USER_AGENT)
        assertEquals("/navidrome/rest/stream.view", slash.streamUrl("1").encodedPath)
    }

    @Test
    fun `coverUrl adds size only when asked`() {
        val api = offlineClient()
        assertNull(api.coverUrl("al-1").queryParameter("size"))
        assertEquals("300", api.coverUrl("al-1", size = 300).queryParameter("size"))
        assertEquals("/rest/getCoverArt.view", api.coverUrl("al-1").encodedPath)
    }

    companion object {
        const val USER_AGENT = "own.audio Music/test (JVM)"

        fun envelope(payload: String = "", status: String = "ok"): String {
            val extra = if (payload.isBlank()) "" else ",${payload.trim()}"
            return """{"subsonic-response":{"status":"$status","version":"1.16.1"$extra}}"""
        }
    }
}

class ModelTest {
    @Test
    fun `the current synced line follows the time, offset included`() {
        val lyrics = Lyrics(
            lines = listOf(Lyrics.Line(1000, "a"), Lyrics.Line(5000, "b"), Lyrics.Line(10000, "c")),
            isSynced = true,
            offsetMs = 500,
        )
        assertNull(lyrics.lineIndex(0.5))
        assertEquals(0, lyrics.lineIndex(1.6))
        assertEquals(0, lyrics.lineIndex(5.4))
        assertEquals(1, lyrics.lineIndex(5.5))
        assertEquals(2, lyrics.lineIndex(99.0))
        assertNull(Lyrics(listOf(Lyrics.Line(null, "x")), isSynced = false).lineIndex(3.0))
    }

    @Test
    fun `an image url without a size parameter is left alone`() {
        assertEquals("https://x.example.com/a.jpg", Artist.displaySizedImageUrl("https://x.example.com/a.jpg"))
    }

    @Test
    fun `a sized image url is rewritten to the display size`() {
        assertEquals(
            "https://m.example.com/share/img/abc?size=320",
            Artist.displaySizedImageUrl("https://m.example.com/share/img/abc?size=600"),
        )
    }
}
