package audio.own.music.library

import audio.own.music.library.db.CatalogueDatabase
import audio.own.music.library.ownaudio.OwnAudioSource
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** M5: what is done to a playlist beyond create, add, remove and rename. */
@RunWith(RobolectricTestRunner::class)
class PlaylistEditsTest {
    private lateinit var db: CatalogueDatabase

    @Before fun open() {
        db = memoryDatabase()
    }

    @After fun close() = db.close()

    private val entries =
        """{"items":[
        {"id":"e1","position":1,"added_at":"2026-10-11T12:00:00Z","track":${trackJson("t1", "One")}},
        {"id":"e2","position":2,"added_at":"2026-10-11T12:00:00Z","track":${trackJson("t2", "Two", number = 2)}},
        {"id":"e3","position":3,"added_at":"2026-10-11T12:00:00Z","track":${trackJson("t1", "One")}}]}"""

    private fun stub(offline: () -> Boolean = { false }, route: (RecordedRequest) -> MockResponse?): Pair<StubServer, OwnAudioSource> {
        val stub = StubServer { request ->
            route(request) ?: when ("${request.method} ${request.url.encodedPath}") {
                "GET /api/v2/me" -> json(ME)
                "GET /api/v2/family/members" -> json("[]")
                "GET /api/v2/playlists/p1" -> json(playlistJson("p1", "Mine", tracks = 3))
                "GET /api/v2/playlists/p1/entries" -> json(entries)
                "GET /api/v2/sync/snapshot" -> ndjson(
                    begin("c0"),
                    record("contributor", "band", contributorJson("band", "The Band")),
                    record("album", "al", albumJson("al", "First Album")),
                    record("track", "t1", trackJson("t1", "One")),
                    record("track", "t2", trackJson("t2", "Two", number = 2)),
                    end("c0"),
                )
                else -> notFound()
            }
        }
        return stub to OwnAudioSource(SERVER, "me@example.com", stub.client(isOffline = offline), db)
    }

    @Test fun `reorder sends every entry id in the new order, and the kept copy follows`() = runBlocking<Unit> {
        var offline = false
        val (stub, src) = stub({ offline }) { if (it.url.encodedPath == "/api/v2/playlists/p1/entries/order") noContent() else null }
        stub.use {
            src.syncCatalogue()
            val before = src.playlist("$SERVER|p1").songs
            assertEquals("a song twice is two entries", listOf("e1", "e2", "e3"), before.map { it.playlistEntryId })

            src.reorderPlaylist("$SERVER|p1", listOf("e3", "e1", "e2"))
            val sent = stub.requests.single { it.url.encodedPath.endsWith("/order") }
            assertEquals("PUT", sent.method)
            assertEquals("""{"entry_ids":["e3","e1","e2"]}""", sent.text())

            offline = true
            assertEquals(listOf("e3", "e1", "e2"), src.playlist("$SERVER|p1").songs.map { it.playlistEntryId })
        }
    }

    @Test fun `reorder refuses an entry twice or none, before asking`() {
        val (stub, src) = stub { null }
        stub.use {
            assertThrows(IllegalArgumentException::class.java) { runBlocking { src.reorderPlaylist("$SERVER|p1", listOf("e1", "e1")) } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { src.reorderPlaylist("$SERVER|p1", emptyList()) } }
            assertTrue(stub.requests.isEmpty())
        }
    }

    @Test fun `description - set, and a blank one clears it`() = runBlocking<Unit> {
        val (stub, src) = stub { if (it.method == "PATCH") json(playlistJson("p1", "Mine")) else null }
        stub.use {
            src.setPlaylistDescription("$SERVER|p1", " For the car ")
            src.setPlaylistDescription("$SERVER|p1", null)
            assertEquals(listOf("""{"description":"For the car"}""", """{"description":""}"""), stub.requests.filter { it.method == "PATCH" }.map { it.text() })
        }
    }

    @Test fun `share a playlist with the family - PATCH its visibility, shown at once`() = runBlocking<Unit> {
        val (stub, src) = stub {
            if (it.method == "PATCH") json(playlistJson("p1", "Mine", visibility = "family")) else null
        }
        stub.use {
            src.setShared("$SERVER|p1", true)
            val sent = stub.requests.single { it.method == "PATCH" }
            assertEquals("/api/v2/playlists/p1", sent.url.encodedPath)
            assertEquals("""{"visibility":"family"}""", sent.text())
            assertEquals(true, src.playlists().single().isShared)
        }
    }
}
