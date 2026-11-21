package audio.own.music.library

import audio.own.kit.ownaudio.OwnAudioJson
import audio.own.music.library.db.CatalogueDatabase
import audio.own.music.library.model.PlaylistEntry
import audio.own.music.library.ownaudio.OwnAudioSource
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Ported from music-ios OwnAudioAPITests, for what M2 covers. */
@RunWith(RobolectricTestRunner::class)
class OwnAudioSourceTest {
    private lateinit var db: CatalogueDatabase

    @Before fun open() {
        db = memoryDatabase()
    }

    @After fun close() = db.close()

    private val band = contributorJson("band", "The Band")

    private fun stub(offline: Boolean = false, route: (RecordedRequest) -> MockResponse?): Pair<StubServer, OwnAudioSource> {
        val stub = StubServer { request ->
            route(request) ?: when (request.url.encodedPath) {
                "/api/v2/me" -> json(ME)
                else -> notFound()
            }
        }
        return stub to OwnAudioSource(SERVER, "me@example.com", stub.client(isOffline = { offline }), db)
    }

    private suspend fun seed(vararg lines: String) {
        val (stub, src) = stub { if (it.url.encodedPath == "/api/v2/sync/snapshot") ndjson(begin("c0"), *lines, end("c0")) else null }
        stub.use { src.syncCatalogue() }
    }

    @Test fun `no instant mix for own audio, but ratings and every album order`() {
        val (stub, src) = stub { null }
        stub.use {
            assertFalse(Capability.INSTANT_MIX in src.capabilities)
            assertTrue(Capability.RATINGS in src.capabilities)
            assertTrue(AlbumSort.HIGHEST_RATED in src.albumSorts)
            assertThrows(SourceError.Unsupported::class.java) { runBlocking { src.instantMix("$SERVER|t1") } }
        }
    }

    @Test fun `search - songs keep their hearts, an album found by title gets its artist's name from the catalogue`() = runBlocking<Unit> {
        val kevin = "cb0b627f-66d4-4238-b941-0937df345c5e"
        seed(
            record("contributor", kevin, contributorJson(kevin, "Kevin MacLeod")),
            record("favourite.track", "0a5b0f1c-ec29-4830-a9fa-0ac09248e2a5", """{"kind":"track","id":"0a5b0f1c-ec29-4830-a9fa-0ac09248e2a5","created_at":"2026-10-11T12:00:00Z"}"""),
        )
        val (stub, src) = stub { if (it.url.encodedPath == "/api/v2/search") json(answer("search")) else null }
        stub.use {
            src.refreshListener()
            val result = src.search("acid")
            assertTrue(result.songs.first { it.title == "Acid Trumpet" }.isFavorite)
            assertFalse(result.songs.first { it.title == "AcidJazz" }.isFavorite)
            assertEquals("Kevin MacLeod", result.albums.first { it.name == "Classical Sampler" }.artist)
            assertEquals(listOf("Gurdonark", "Gurdonark and Lee Rosevere"), result.artists.map { it.name })
            assertEquals("artists,albums,tracks", stub.requests.last().url.queryParameter("types"))
        }
    }

    @Test fun `offline, search and random songs come from the catalogue, nothing is asked`() = runBlocking<Unit> {
        seed(
            record("contributor", "band", band),
            record("album", "al", albumJson("al", "First Album")),
            record("track", "t1", trackJson("t1", "Song One")),
            record("track", "t2", trackJson("t2", "Another", number = 2)),
        )
        val (stub, src) = stub(offline = true) { null }
        stub.use {
            assertEquals(listOf("Song One"), src.search("SONG").songs.map { it.title })
            assertEquals("by artist name too", 2, src.search("band").songs.size)
            assertEquals(listOf("First Album"), src.search("first").albums.map { it.name })
            assertEquals(2, src.randomSongs(10).size)
            assertTrue(stub.requests.isEmpty())
        }
    }

    @Test fun `a playlist - whose it is by owner, each song with its entry id, kept for offline`() = runBlocking<Unit> {
        seed(
            record("contributor", "band", band),
            record("album", "al", albumJson("al", "First Album")),
            record("track", "t1", trackJson("t1", "Song One")),
            record("playlist", "p1", playlistJson("p1", "Mine")),
            record("playlist", "p2", playlistJson("p2", "Theirs", owner = "other", visibility = "family", tracks = 0)),
        )
        val (stub, src) = stub {
            when (it.url.encodedPath) {
                "/api/v2/playlists/p1" -> json(playlistJson("p1", "Mine"))
                "/api/v2/family/members" -> json("""[{"user_id":"u","display_name":"Me","label":null},{"user_id":"other","display_name":"Anna Nováková","label":"Mum"}]""")
                "/api/v2/playlists/p1/entries" -> json("""{"items":[{"id":"e1","position":1,"added_at":"2026-10-11T12:00:00Z","track":${trackJson("t1", "Song One")}}]}""")
                else -> null
            }
        }
        stub.use {
            val lists = src.playlists()
            assertEquals("the account's own: its username, so the app lets it edit", "me@example.com", lists.first { it.id == "$SERVER|p1" }.owner)
            assertEquals("other", lists.first { it.id == "$SERVER|p2" }.owner)
            assertEquals("shown by the household's name for them, never the id", "Mum", lists.first { it.id == "$SERVER|p2" }.ownerName)
            assertEquals(true, lists.first { it.id == "$SERVER|p2" }.isShared)
            val (record, songs) = src.playlist("$SERVER|p1")
            assertEquals(1, record.songCount)
            assertEquals("e1", songs.single().playlistEntryId)
        }
        val (offline, offlineSource) = stub(offline = true) { null }
        offline.use {
            assertEquals(listOf("e1"), offlineSource.playlist("$SERVER|p1").songs.map { it.playlistEntryId })
        }
    }

    @Test fun `playlist removal goes by entry id`() = runBlocking<Unit> {
        val (stub, src) = stub {
            when (it.url.encodedPath) {
                "/api/v2/playlists/p1/entries/e2" -> noContent()
                "/api/v2/playlists/p1" -> json(playlistJson("p1", "Mine", tracks = 1))
                else -> null
            }
        }
        stub.use {
            src.removeFromPlaylist("$SERVER|p1", listOf(PlaylistEntry(1, "e2")))
            assertEquals("DELETE /api/v2/playlists/p1/entries/e2", stub.paths().first())
            assertThrows(SourceError.Unsupported::class.java) { runBlocking { src.removeFromPlaylist("$SERVER|p1", listOf(PlaylistEntry(0, null))) } }
        }
    }

    @Test fun `a rating goes to the kind the catalogue knows, unknown, a track first then an album`() = runBlocking<Unit> {
        seed(record("contributor", "band", band), record("album", "al", albumJson("al", "First Album")))
        val (stub, src) = stub {
            when (it.url.encodedPath) {
                "/api/v2/me/ratings/album/al", "/api/v2/me/ratings/album/x" -> noContent()
                "/api/v2/me/ratings/track/x" -> notFound()
                else -> null
            }
        }
        stub.use {
            src.setRating("$SERVER|al", 4)
            assertEquals("PUT /api/v2/me/ratings/album/al", stub.paths().first())
            assertEquals(4, src.album("$SERVER|al").album.rating)
            src.setRating("$SERVER|x", 3)
            assertEquals(listOf("PUT /api/v2/me/ratings/track/x", "PUT /api/v2/me/ratings/album/x"), stub.paths().drop(1))
        }
    }

    @Test fun `a favourite is shown at once`() = runBlocking<Unit> {
        seed(record("contributor", "band", band), record("album", "al", albumJson("al", "First Album")), record("track", "t1", trackJson("t1", "Song One")))
        val (stub, src) = stub { if (it.url.encodedPath == "/api/v2/me/favourites/track/t1") noContent() else null }
        stub.use {
            src.setFavorite(FavoriteKind.SONG, "$SERVER|t1", true)
            assertTrue(src.song("$SERVER|t1").isFavorite)
            assertEquals(listOf("Song One"), src.favorites().songs.map { it.title })
            assertTrue("t1" in src.listener.current.favourites)
        }
    }

    @Test fun `a play counted goes to the API, with how long it was heard and a stable id`() = runBlocking<Unit> {
        val (stub, src) = stub { if (it.url.encodedPath == "/api/v2/me/plays") json("""[{"client_id":"x","status":"ok"}]""") else null }
        stub.use {
            val at = Instant.now().minusSeconds(120)
            src.reportPlayed("$SERVER|t1", at)
            val request = stub.requests.single()
            assertEquals("/api/v2/me/plays", request.url.encodedPath)
            val play = (OwnAudioJson.parseToJsonElement(request.text()) as JsonObject)["plays"]!!.jsonArray.first().jsonObject
            assertEquals("t1", play["id"]!!.jsonPrimitive.content)
            assertTrue(play["listened_ms"]!!.jsonPrimitive.long >= 119_000)
            assertEquals("t1@${at.toEpochMilli()}", play["client_id"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `a stream - playback-info says what the player decodes and the bitrate wanted, the link comes back with its expiry`() = runBlocking<Unit> {
        val (stub, src) = stub {
            if (it.url.encodedPath != "/api/v2/playback-info") return@stub null
            json("""{"decision":"transcode","url":"https://music.example.com/api/v2/stream?i=track:t1&s=x","expires_at":"2030-01-01T00:00:00Z","content_type":"audio/mpeg","bitrate_kbps":128,"reasons":["over_bitrate_limit"]}""")
        }
        stub.use {
            val link = src.playbackLink("$SERVER|t1", maxBitrateKbps = 128)
            assertEquals("https://music.example.com/api/v2/stream?i=track:t1&s=x", link.url)
            assertEquals(Instant.parse("2030-01-01T00:00:00Z"), link.expiresAt)
            assertTrue(link.isTranscoded)
            val body = OwnAudioJson.parseToJsonElement(stub.requests.single().text()) as JsonObject
            val capabilities = body["capabilities"] as JsonObject
            assertEquals("track", body["kind"]!!.jsonPrimitive.content)
            assertEquals("t1", body["id"]!!.jsonPrimitive.content)
            assertEquals(128L, capabilities["max_bitrate_kbps"]!!.jsonPrimitive.long)
            val direct = (capabilities["direct"] as JsonArray).map { (it as JsonPrimitive).content }
            assertTrue("audio/flac" in direct)
            assertTrue("ExoPlayer streams Ogg, unlike Core Audio", "audio/ogg" in direct)
            assertEquals(listOf("mp3", "aac"), (capabilities["transcode"] as JsonArray).map { (it as JsonPrimitive).content })
        }
    }

    @Test fun `a cover - the record's signed link at the nearest size made, an unknown id is read again, once`() = runBlocking<Unit> {
        val future = Instant.now().epochSecond + 7 * 86_400
        val link = "https://music.example.com/api/v2/images/o9?e=$future&s=sig"
        val (stub, src) = stub {
            when (it.url.encodedPath) {
                "/api/v2/albums/t9" -> notFound()
                "/api/v2/tracks/t9" -> json(trackJson("t9", "Nine", image = link))
                else -> null
            }
        }
        stub.use {
            assertEquals("$link&w=512", src.coverLink("$SERVER|t9~o9", 300))
            assertEquals(listOf("GET /api/v2/albums/t9", "GET /api/v2/tracks/t9"), stub.paths())
        }
    }

    @Test fun `a cover known to the catalogue needs no request, unless its link is about to run out`() = runBlocking<Unit> {
        val soon = Instant.now().epochSecond + 60
        val later = Instant.now().epochSecond + 86_400
        seed(
            record("contributor", "band", band),
            record("album", "fresh", albumJson("fresh", "Fresh", image = "https://h/api/v2/images/o1?e=$later&s=s")),
            record("album", "stale", albumJson("stale", "Stale", image = "https://h/api/v2/images/o2?e=$soon&s=s")),
        )
        val (stub, src) = stub {
            if (it.url.encodedPath == "/api/v2/albums/stale") json(albumJson("stale", "Stale", image = "https://h/api/v2/images/o2?e=$later&s=new")) else null
        }
        stub.use {
            assertEquals("https://h/api/v2/images/o1?e=$later&s=s&w=128", src.coverLink("$SERVER|fresh~o1", 100))
            assertTrue(stub.requests.isEmpty())
            assertEquals("https://h/api/v2/images/o2?e=$later&s=new&w=64", src.coverLink("$SERVER|stale~o2", 64))
            assertEquals("written back: no second request", "https://h/api/v2/images/o2?e=$later&s=new&w=64", src.coverLink("$SERVER|stale~o2", 64))
            assertEquals(1, stub.requests.size)
        }
    }

    @Test fun `recently played - the server's order, the albums from the catalogue with their counts`() = runBlocking<Unit> {
        seed(
            record("contributor", "band", band),
            record("album", "a1", albumJson("a1", "One")),
            record("album", "a2", albumJson("a2", "Two")),
        )
        val (stub, src) = stub {
            when (it.url.encodedPath) {
                "/api/v2/me/played" -> json(
                    """{"items":[{"kind":"album","id":"a2","play_count":7,"last_played_at":"2026-10-11T12:00:00Z"},
                    {"kind":"album","id":"gone","play_count":1,"last_played_at":"2026-10-11T12:00:00Z"},
                    {"kind":"album","id":"a1","play_count":3,"last_played_at":"2026-10-11T11:00:00Z"}]}""",
                )
                else -> null
            }
        }
        stub.use {
            val albums = src.albums(AlbumSort.MOST_PLAYED, limit = 10)
            assertEquals(listOf("Two", "One"), albums.map { it.name })
            assertEquals(listOf(7, 3), albums.map { it.playCount })
            assertEquals("most", stub.requests.first().url.queryParameter("order"))
        }
    }

    @Test fun `with the sign-in refused, the connection check says so and nothing is synced`() = runBlocking<Unit> {
        val (stub, _) = stub { null }
        stub.use {
            val src = OwnAudioSource(SERVER, "me@example.com", stub.client(signedIn = false), db)
            assertEquals(ConnectionStatus.REJECTED, src.checkConnection())
            assertThrows(SourceError.Unsupported::class.java) { runBlocking { src.syncCatalogue() } }
            assertTrue(stub.requests.isEmpty())
        }
    }

    @Test fun `an id of another server is not this one's`() {
        val (stub, src) = stub { null }
        stub.use {
            assertEquals("t1", src.apiId("$SERVER|t1"))
            assertThrows(SourceError.NotFound::class.java) { src.apiId("other|t1") }
        }
    }
}
