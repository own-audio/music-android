package audio.own.kit.jellyfin

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class JellyfinClientTest {
    private val info = ClientInfo("own.audio Music", "Pixel", "dev-1", "1.0", userAgent = "own.audio Music/1.0 (JVM)")
    private val session = Session(userId = "u1", userName = "anna", accessToken = "tok", serverId = "s1")
    private val stubs = mutableListOf<Stub>()

    @After
    fun tearDown() = stubs.forEach { it.close() }

    private fun stub(handler: (RecordedRequest) -> MockResponse) = Stub(handler).also { stubs += it }

    private fun stubBody(body: String, code: Int = 200) = stub { Stub.ok(body, code) }

    private fun client(stub: Stub, libraryId: String? = null, cache: ResponseCache? = null, offline: () -> Boolean = { false }) =
        JellyfinClient(stub.host, session, info, stub.http, libraryId = libraryId, cache = cache, isOffline = offline)

    private suspend inline fun <reified E : JellyfinError> assertThrows(block: () -> Unit) {
        try {
            block()
            fail("expected ${E::class.simpleName}")
        } catch (e: JellyfinError) {
            assertTrue("got $e", e is E)
        }
    }

    // Addresses and headers

    @Test
    fun `a bare host gets http and port 8096, https and explicit ports are kept`() {
        assertEquals("http://nas.local:8096/", JellyfinClient.normalizeHost("nas.local").toString())
        assertEquals("http://nas.local:9000/", JellyfinClient.normalizeHost("nas.local:9000").toString())
        assertEquals("http://nas.local/", JellyfinClient.normalizeHost("nas.local:80").toString())
        assertEquals("https://jelly.example.com/", JellyfinClient.normalizeHost("https://jelly.example.com/").toString())
        assertEquals("https://example.com/jellyfin", JellyfinClient.normalizeHost("https://example.com/jellyfin").toString())
        assertEquals("https://example.com/jellyfin", JellyfinClient.normalizeHost("https://example.com/jellyfin/").toString())
        assertEquals("http://[fd00::1]:8096/", JellyfinClient.normalizeHost("[fd00::1]").toString())
        assertNull(JellyfinClient.normalizeHost("  "))
    }

    @Test
    fun `without a scheme, https comes first, then http on 8096, then plain http`() {
        assertEquals(
            listOf("https://demo.jellyfin.org/stable", "http://demo.jellyfin.org:8096/stable", "http://demo.jellyfin.org/stable"),
            JellyfinClient.candidateHosts("demo.jellyfin.org/stable").map { it.toString() },
        )
        assertEquals(
            listOf("https://nas.local:9000/", "http://nas.local:9000/"),
            JellyfinClient.candidateHosts("nas.local:9000").map { it.toString() },
        )
        assertEquals(listOf("http://nas.local/"), JellyfinClient.candidateHosts("http://nas.local").map { it.toString() })
    }

    @Test
    fun `every request carries the MediaBrowser header with the token, and the user agent`() = runTest {
        val stub = stubBody("""{"Items":[]}""")
        client(stub).playlists()
        val request = stub.requests.first()
        assertEquals(
            """MediaBrowser Client="own.audio Music", Device="Pixel", DeviceId="dev-1", Version="1.0", Token="tok"""",
            request.headers["Authorization"],
        )
        assertEquals("own.audio Music/1.0 (JVM)", request.headers["User-Agent"])
    }

    @Test
    fun `quotes are dropped from header values`() {
        assertEquals(
            """MediaBrowser Client="a", Device="Anna's Pixel 9", DeviceId="d", Version="1"""",
            ClientInfo("a", "Anna's \"Pixel\" 9", "d", "1").header(token = null),
        )
    }

    @Test
    fun `sign-in posts the name and password and returns the session`() = runTest {
        val stub = stubBody("""{"User":{"Id":"u1","Name":"anna"},"AccessToken":"tok","ServerId":"s1"}""")
        val result = JellyfinClient.signIn(stub.host, "anna", "pw", info, stub.http)
        assertEquals(session, result)
        val request = stub.requests.first()
        assertEquals("POST", request.method)
        assertEquals("/Users/AuthenticateByName", request.url.encodedPath)
        assertFalse(request.headers["Authorization"]!!.contains("Token="))
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("anna", body["Username"]!!.jsonPrimitive.content)
        assertEquals("pw", body["Pw"]!!.jsonPrimitive.content)
    }

    @Test
    fun `server info reads the name, version and local address`() = runTest {
        val stub = stubBody(
            """{"LocalAddress":"http://192.168.1.20:8096","ServerName":"Home","Version":"10.11.0","ProductName":"Jellyfin Server","Id":"abc","StartupWizardCompleted":true}""",
        )
        val info = JellyfinClient.serverInfo(stub.host, info, stub.http)
        assertEquals(ServerInfo("abc", "Home", "10.11.0", "http://192.168.1.20:8096"), info)
        assertEquals("/System/Info/Public", stub.requests.first().url.encodedPath)
        assertNull(stub.requests.first().headers["Authorization"])
    }

    @Test
    fun `a server under a sub-path keeps it`() = runTest {
        val stub = stubBody("""{"Id":"abc"}""")
        JellyfinClient.serverInfo(stub.url("/jellyfin"), info, stub.http)
        assertEquals("/jellyfin/System/Info/Public", stub.requests.first().url.encodedPath)
    }

    @Test
    fun `401 is unauthorized, 404 not found, a transport failure network`() = runTest {
        val unauthorized = stubBody("", code = 401)
        assertThrows<JellyfinError.Unauthorized> { client(unauthorized).playlists() }
        val missing = stubBody("", code = 404)
        assertThrows<JellyfinError.NotFound> { client(missing).song("x") }
        val down = stubBody("{}").apply { networkUp = false }
        assertThrows<JellyfinError.Network> { client(down).playlists() }
        assertEquals(ConnectionStatus.Rejected, client(unauthorized).checkSession())
        assertEquals(ConnectionStatus.Unreachable, client(down).checkSession())
        assertEquals(ConnectionStatus.Ok, client(stubBody("""{"Id":"u1","Name":"anna"}""")).checkSession())
    }

    // Quick Connect

    @Test
    fun `quick connect - enabled, initiate, poll, authenticate`() = runTest {
        val stub = stub { request ->
            when (request.url.encodedPath) {
                "/QuickConnect/Enabled" -> Stub.ok("true")
                "/QuickConnect/Initiate" -> Stub.ok("""{"Authenticated":false,"Secret":"sec","Code":"123456","DeviceId":"dev-1"}""")
                "/QuickConnect/Connect" -> Stub.ok("""{"Authenticated":true,"Secret":"sec","Code":"123456"}""")
                "/Users/AuthenticateWithQuickConnect" -> Stub.ok("""{"User":{"Id":"u1","Name":"anna"},"AccessToken":"tok","ServerId":"s1"}""")
                else -> Stub.ok("", code = 404)
            }
        }
        assertTrue(JellyfinClient.isQuickConnectEnabled(stub.host, info, stub.http))
        val started = JellyfinClient.startQuickConnect(stub.host, info, stub.http)
        assertEquals(QuickConnect("sec", "123456", isApproved = false), started)
        assertTrue(JellyfinClient.checkQuickConnect(stub.host, "sec", info, stub.http).isApproved)
        assertEquals(session, JellyfinClient.finishQuickConnect(stub.host, "sec", info, stub.http))

        val (enabled, initiate, connect, finish) = stub.requests.toList()
        assertEquals("GET", enabled.method)
        assertEquals("POST", initiate.method)
        assertTrue(initiate.headers["Authorization"]!!.startsWith("MediaBrowser Client="))
        assertEquals("sec", connect.url.queryParameter("secret"))
        assertEquals("POST", finish.method)
        assertEquals("sec", Json.parseToJsonElement(finish.body!!.utf8()).jsonObject["Secret"]!!.jsonPrimitive.content)
    }

    @Test
    fun `quick connect switched off, or no answer, is not enabled`() = runTest {
        assertFalse(JellyfinClient.isQuickConnectEnabled(stubBody("false").host, info, OkHttpClient()))
        val missing = stubBody("", code = 404)
        assertFalse(JellyfinClient.isQuickConnectEnabled(missing.host, info, missing.http))
    }

    // Decoding

    @Test
    fun `a song - ticks to seconds, disc 0 is no disc, the album's cover when it has none`() = runTest {
        val stub = stubBody(
            """
            {"Items":[{"Id":"s1","Name":"One","Type":"Audio","Album":"Opera","AlbumId":"al1",
            "AlbumPrimaryImageTag":"t","ImageTags":{},"RunTimeTicks":2135000000,"IndexNumber":3,
            "ParentIndexNumber":0,"Genres":["Rock"],"ArtistItems":[{"Name":"Queen","Id":"ar1"}],
            "UserData":{"IsFavorite":true,"PlayCount":4},"Container":"flac",
            "MediaSources":[{"Container":"flac","Bitrate":900000}],"NormalizationGain":-6.5,
            "HasLyrics":true,"DateCreated":"2025-02-22T21:32:03.1234567Z"}]}
            """,
        )
        val song = client(stub).songs().first()
        assertEquals(214, song.duration)
        assertNull(song.discNumber)
        assertEquals(3, song.track)
        assertEquals("al1", song.coverArt)
        assertEquals("Queen", song.artist)
        assertEquals("ar1", song.artistId)
        assertTrue(song.isFavorite)
        assertEquals(4, song.playCount)
        assertEquals("flac", song.suffix)
        assertEquals(900000, song.bitRate)
        assertEquals(-6.5, song.normalizationGain!!, 0.0)
        assertTrue(song.hasLyrics)
    }

    @Test
    fun `an album - its own cover, album artist, child count as song count, date added`() = runTest {
        val stub = stubBody(
            """
            {"Items":[{"Id":"al1","Name":"72 Seasons","Type":"MusicAlbum","AlbumArtist":"Metallica",
            "AlbumArtists":[{"Name":"Metallica","Id":"ar1"}],"ImageTags":{"Primary":"x"},
            "ChildCount":12,"ProductionYear":2023,"RunTimeTicks":46307466660,
            "DateCreated":"2025-02-22T21:32:03.0000000Z"}]}
            """,
        )
        val album = client(stub).albums(AlbumSort.Newest).first()
        assertEquals("al1", album.coverArt)
        assertEquals("Metallica", album.artist)
        assertEquals("ar1", album.artistId)
        assertEquals(12, album.songCount)
        assertEquals(2023, album.year)
        assertEquals(4631, album.duration)
        assertEquals(Instant.parse("2025-02-22T21:32:03Z"), album.dateAdded)
        val url = stub.requests.first().url
        assertEquals("DateCreated,SortName", url.queryParameter("SortBy"))
        assertEquals("Descending", url.queryParameter("SortOrder"))
    }

    @Test
    fun `names made of spaces count as no name`() = runTest {
        val stub = stubBody("""{"Items":[{"Id":"ar1","Name":"   ","ImageTags":{"Primary":"x"},"ChildCount":2}]}""")
        val artist = client(stub).artists().first()
        assertEquals("", artist.name)
        assertEquals("ar1", artist.imageId)
        assertEquals(2, artist.albumCount)
    }

    @Test
    fun `seven fractional digits, none, and no zone all parse`() {
        assertEquals(Instant.parse("2025-02-22T21:32:03Z"), JellyfinDateSerializer.parseDate("2025-02-22T21:32:03.0000000Z"))
        assertEquals(Instant.parse("2025-02-22T21:32:03Z"), JellyfinDateSerializer.parseDate("2025-02-22T21:32:03Z"))
        assertEquals(Instant.parse("2025-02-22T21:32:03Z"), JellyfinDateSerializer.parseDate("2025-02-22T21:32:03"))
        val fraction = JellyfinDateSerializer.parseDate("2025-04-30T17:19:59.3360864Z")!!.toEpochMilli() -
            Instant.parse("2025-04-30T17:19:59Z").toEpochMilli()
        assertTrue(fraction >= 330)
        assertNull(JellyfinDateSerializer.parseDate("yesterday"))
    }

    @Test
    fun `synced lyrics - ticks to milliseconds, 404 means none`() = runTest {
        val stub = stub { request ->
            if (request.url.encodedPath.contains("none")) {
                Stub.ok("", code = 404)
            } else {
                Stub.ok("""{"Lyrics":[{"Text":"Hello","Start":15000000},{"Text":"World","Start":32000000}]}""")
            }
        }
        val lyrics = client(stub).lyrics("s1")!!
        assertTrue(lyrics.isSynced)
        assertEquals(listOf(1500, 3200), lyrics.lines.map { it.startMs })
        assertEquals(0, lyrics.lineIndex(2.0))
        assertEquals("/Audio/s1/Lyrics", stub.requests.first().url.encodedPath)
        assertNull(client(stub).lyrics("none"))
    }

    // Requests

    @Test
    fun `a library choice limits listings to it`() = runTest {
        val stub = stubBody("""{"Items":[]}""")
        client(stub, libraryId = "lib").albums(AlbumSort.Name)
        assertEquals("lib", stub.requests.first().url.queryParameter("ParentId"))
    }

    @Test
    fun `music libraries are the user views of type music`() = runTest {
        val stub = stubBody(
            """{"Items":[{"Id":"l1","Name":"Music","CollectionType":"music"},{"Id":"l2","Name":"Films","CollectionType":"movies"}]}""",
        )
        assertEquals(listOf(JellyfinLibrary("l1", "Music")), client(stub).musicLibraries())
        assertEquals("/UserViews", stub.requests.first().url.encodedPath)
        assertEquals("u1", stub.requests.first().url.queryParameter("userId"))
    }

    @Test
    fun `recently played albums come from recently played songs, in order, once each`() = runTest {
        val stub = stub { request ->
            if (request.url.queryParameter("Ids") == null) {
                Stub.ok("""{"Items":[{"Id":"s1","AlbumId":"b"},{"Id":"s2","AlbumId":"a"},{"Id":"s3","AlbumId":"b"}]}""")
            } else {
                Stub.ok("""{"Items":[{"Id":"a","Name":"A"},{"Id":"b","Name":"B"}]}""")
            }
        }
        assertEquals(listOf("b", "a"), client(stub).albums(AlbumSort.RecentlyPlayed, limit = 10).map { it.id })
        assertEquals("b,a", stub.requests[1].url.queryParameter("Ids"))
        assertEquals("DatePlayed", stub.requests[0].url.queryParameter("SortBy"))
    }

    @Test
    fun `instant mix asks the server for songs like the item`() = runTest {
        val stub = stubBody("""{"Items":[{"Id":"s9","Name":"Nine"}]}""")
        assertEquals(listOf("s9"), client(stub).instantMix("al1", limit = 25).map { it.id })
        val url = stub.requests.first().url
        assertEquals("/Items/al1/InstantMix", url.encodedPath)
        assertEquals("25", url.queryParameter("Limit"))
    }

    @Test
    fun `search asks once per kind`() = runTest {
        val stub = stubBody("""{"Items":[{"Id":"x","Name":"X"}]}""")
        val result = client(stub).search("queen")
        assertEquals(1, result.artists.size)
        assertEquals(3, stub.requests.size)
        assertTrue(stub.requests.all { it.url.queryParameter("SearchTerm") == "queen" })
    }

    @Test
    fun `removing from a playlist goes by entry id, favorites post and delete`() = runTest {
        val stub = stubBody("", code = 204)
        val api = client(stub)
        api.removeFromPlaylist("p1", listOf("e1", "e2"))
        api.setFavorite("s1", true)
        api.setFavorite("s1", false)
        val log = stub.requests
        assertEquals("DELETE", log[0].method)
        assertEquals("/Playlists/p1/Items", log[0].url.encodedPath)
        assertEquals("e1,e2", log[0].url.queryParameter("entryIds"))
        assertEquals("POST", log[1].method)
        assertEquals("/UserFavoriteItems/s1", log[1].url.encodedPath)
        assertEquals("DELETE", log[2].method)
    }

    @Test
    fun `creating, filling and renaming a playlist`() = runTest {
        val stub = stub { request ->
            if (request.url.encodedPath == "/Playlists") Stub.ok("""{"Id":"p9"}""") else Stub.ok("", code = 204)
        }
        val api = client(stub)
        assertEquals("p9", api.createPlaylist("Mix", listOf("s1", "s2")))
        api.addToPlaylist("p9", listOf("s3", "s4"))
        api.renamePlaylist("p9", "Mix 2")
        api.deletePlaylist("p9")
        val created = Json.parseToJsonElement(stub.requests[0].body!!.utf8()).jsonObject
        assertEquals("Mix", created["Name"]!!.jsonPrimitive.content)
        assertEquals("Audio", created["MediaType"]!!.jsonPrimitive.content)
        assertEquals("u1", created["UserId"]!!.jsonPrimitive.content)
        assertEquals("s3,s4", stub.requests[1].url.queryParameter("ids"))
        assertEquals("/Playlists/p9", stub.requests[2].url.encodedPath)
        assertEquals("DELETE", stub.requests[3].method)
        assertEquals("/Items/p9", stub.requests[3].url.encodedPath)
    }

    @Test
    fun `playlists - imported ones (media type Unknown) are listed, video ones are not`() = runTest {
        val stub = stubBody(
            """
            {"Items":[{"Id":"p1","Name":"From m3u","ChildCount":17,"MediaType":"Unknown"},
            {"Id":"p2","Name":"Made here","ChildCount":3,"MediaType":"Audio"},
            {"Id":"p3","Name":"Films","ChildCount":2,"MediaType":"Video"}]}
            """,
        )
        assertEquals(listOf("From m3u", "Made here"), client(stub).playlists().map { it.name })
        assertNull(stub.requests.first().url.queryParameter("MediaTypes"))
    }

    @Test
    fun `playback reports carry the position in ticks`() = runTest {
        val stub = stubBody("", code = 204)
        val api = client(stub)
        api.reportStart("s1")
        api.reportProgress("s1", positionSecs = 12.5, isPaused = true)
        api.reportStopped("s1", positionSecs = 30.0)
        api.markPlayed("s1", at = Instant.parse("2026-01-02T03:04:05.678Z"))
        api.signOut()
        val paths = stub.requests.map { it.url.encodedPath }
        assertEquals(
            listOf("/Sessions/Playing", "/Sessions/Playing/Progress", "/Sessions/Playing/Stopped", "/UserPlayedItems/s1", "/Sessions/Logout"),
            paths,
        )
        val progress = Json.parseToJsonElement(stub.requests[1].body!!.utf8()).jsonObject
        assertEquals(125_000_000L, progress["PositionTicks"]!!.jsonPrimitive.long)
        assertTrue(progress["IsPaused"]!!.jsonPrimitive.boolean)
        assertEquals("s1", progress["ItemId"]!!.jsonPrimitive.content)
        assertEquals("2026-01-02T03:04:05Z", stub.requests[3].url.queryParameter("datePlayed"))
        assertTrue(stub.requests.all { it.method == "POST" })
    }

    @Test
    fun `sign out never throws`() = runTest {
        client(stubBody("{}").apply { networkUp = false }).signOut()
    }

    // URLs

    private val plain = JellyfinClient("http://music.example.com:8096".toHttpUrl(), session, info, OkHttpClient())

    @Test
    fun `the stream url is the original file, with the token for the player`() {
        val url = plain.streamUrl("s1")
        assertEquals("/Audio/s1/stream", url.encodedPath)
        assertEquals("true", url.queryParameter("static"))
        assertEquals("tok", url.queryParameter("ApiKey"))
        val smaller = plain.streamUrl("s1", maxBitrate = 128_000)
        assertEquals("/Audio/s1/stream.mp3", smaller.encodedPath)
        assertEquals("128000", smaller.queryParameter("audioBitRate"))
        assertEquals("false", smaller.queryParameter("static"))
    }

    @Test
    fun `the universal url names the device, the limit and what plays without a transcode`() {
        val url = plain.universalUrl("s1", maxBitrate = 192_000)
        assertEquals("/Audio/s1/universal", url.encodedPath)
        assertEquals("192000", url.queryParameter("MaxStreamingBitrate"))
        assertEquals("dev-1", url.queryParameter("DeviceId"))
        assertEquals("u1", url.queryParameter("UserId"))
        assertEquals("mp3", url.queryParameter("TranscodingContainer"))
        assertTrue(url.queryParameter("Container")!!.split(",").contains("flac"))
        assertEquals("tok", url.queryParameter("ApiKey"))
    }

    @Test
    fun `image urls need no token`() {
        val url = plain.imageUrl("al1", 300)
        assertEquals("/Items/al1/Images/Primary", url.encodedPath)
        assertEquals("300", url.queryParameter("fillWidth"))
        assertNull(url.queryParameter("ApiKey"))
    }

    // Offline

    @Test
    fun `a saved answer comes back offline and when the request fails, writes are refused offline`() = runTest {
        val stub = stubBody("""{"Items":[{"Id":"p1","Name":"Mix","ChildCount":3}]}""")
        var offline = false
        val api = client(stub, cache = MemoryCache(), offline = { offline })
        api.playlists()
        stub.networkUp = false
        assertEquals(listOf("Mix"), api.playlists().map { it.name })
        offline = true
        assertEquals(listOf("Mix"), api.playlists().map { it.name })
        assertThrows<JellyfinError.Offline> { api.setFavorite("s1", true) }
        assertThrows<JellyfinError.Offline> { api.genres() }
        assertEquals(1, stub.requests.size)
    }

    @Test
    fun `the cache key has no token`() {
        val key = plain.cacheKey("Items", mapOf("b" to "2", "a" to "1"))
        assertFalse(key.contains("tok"))
        assertTrue(key.endsWith("Items?a=1&b=2"))
    }
}

class LyricsTest {
    @Test
    fun `the line to highlight follows the time`() {
        val lyrics = Lyrics(listOf(Lyrics.Line(1000, "a"), Lyrics.Line(5000, "b")), isSynced = true)
        assertNull(lyrics.lineIndex(0.5))
        assertEquals(0, lyrics.lineIndex(4.9))
        assertEquals(1, lyrics.lineIndex(60.0))
        assertNull(Lyrics(listOf(Lyrics.Line(null, "x")), isSynced = false).lineIndex(1.0))
    }
}
