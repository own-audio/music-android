package audio.own.music.library

import audio.own.music.library.db.CatalogueDatabase
import audio.own.music.library.subsonic.SubsonicSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Reading a whole library into Room page by page (PagedCatalogueSync), through the Subsonic source. */
@RunWith(RobolectricTestRunner::class)
class PagedCatalogueSyncTest {
    private lateinit var db: CatalogueDatabase
    private val dao get() = db.catalogue()
    private val stubs = mutableListOf<KitStub>()
    private var now = Instant.parse("2026-11-21T08:00:00Z")
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
    }

    @Before fun open() {
        db = memoryDatabase()
    }

    @After fun close() {
        stubs.forEach { it.close() }
        db.close()
    }

    private fun source(answers: Map<String, (RecordedRequest) -> MockResponse>): Pair<SubsonicSource, KitStub> {
        val stub = KitStub(subsonicServer(answers)).also { stubs += it }
        return SubsonicSource(SERVER, stub.subsonic(), db, clock) to stub
    }

    @Test fun `artists, albums, songs, genres and playlists land in the catalogue`() = runBlocking {
        val (source, _) = source(SubsonicSourceTest.libraryAnswers())
        val result = source.syncCatalogue()!!
        assertEquals(PagedSyncResult(artists = 1, albums = 2, songs = 2, playlists = 1, readAlbumByAlbum = false), result)
        assertEquals(2, dao.albumCount(SERVER))
        assertEquals(2, dao.trackCount(SERVER))
        // The artist is listed through the credits written with its albums.
        val artist = dao.artists(SERVER).single()
        assertEquals("Band", artist.artist.name)
        assertEquals(2, artist.albumCount)
        assertEquals(listOf("al1"), dao.albumsOfGenre(SERVER, "Rock", 10).map { it.album.id })
        assertEquals(listOf("al1"), dao.favourites(SERVER).map { it.id })
        assertEquals(listOf("p1"), dao.playlists(SERVER).map { it.id })
        assertEquals(now.toEpochMilli(), source.catalogueSyncedAtMs())
    }

    @Test fun `within six hours nothing is read again unless forced`() = runBlocking {
        val (source, stub) = source(SubsonicSourceTest.libraryAnswers())
        assertNotNull(source.syncCatalogue())
        val asked = stub.requests.size
        now = now.plusSeconds(5 * 3600)
        assertNull(source.syncCatalogue())
        assertEquals(asked, stub.requests.size)
        assertNotNull(source.syncCatalogue(force = true))
        now = now.plusSeconds(7 * 3600)
        assertNotNull(source.syncCatalogue())
    }

    @Test fun `a server that lists no songs without a term is read album by album`() = runBlocking {
        val answers = SubsonicSourceTest.libraryAnswers() + mapOf(
            "search3" to { _: RecordedRequest -> subsonicOk(""""searchResult3":{}""") },
            "getAlbum" to { r: RecordedRequest ->
                val id = r.url.queryParameter("id")!!
                subsonicOk(""""album":{"id":"$id","name":"$id","song":[${subsonicSong("$id-s1", "One of $id", albumId = id)}]}""")
            },
        )
        val (source, stub) = source(answers)
        val result = source.syncCatalogue()!!
        assertTrue(result.readAlbumByAlbum)
        assertEquals(2, result.songs)
        assertEquals(listOf("al1", "al2"), stub.requests.filter { it.url.encodedPath == "/rest/getAlbum.view" }.map { it.url.queryParameter("id") })
        assertEquals(listOf("al1-s1"), dao.tracksOfAlbum(SERVER, "al1").map { it.track.id })
    }

    @Test fun `full pages are followed by the next one`() = runBlocking {
        val page = PagedCatalogueSyncTestData.albums(500)
        val answers = SubsonicSourceTest.libraryAnswers() + mapOf(
            "getAlbumList2" to { r: RecordedRequest ->
                val offset = r.url.queryParameter("offset")!!.toInt()
                subsonicOk(""""albumList2":{"album":[${if (offset == 0) page else subsonicAlbum("last", "Last")}]}""")
            },
        )
        val (source, stub) = source(answers)
        assertEquals(501, source.syncCatalogue()!!.albums)
        assertEquals(listOf("0", "500"), stub.requests.filter { it.url.encodedPath == "/rest/getAlbumList2.view" }.map { it.url.queryParameter("offset") })
        assertEquals(501, dao.albumCount(SERVER))
    }

    @Test fun `what the server no longer has goes, but only once a sync has finished`() = runBlocking {
        val answers = SubsonicSourceTest.libraryAnswers().toMutableMap()
        val (source, _) = source(answers)
        source.syncCatalogue()
        val syncedAt = source.catalogueSyncedAtMs()

        // A sync that fails halfway (the songs) leaves the catalogue as it was.
        answers["getAlbumList2"] = { _ -> subsonicOk(""""albumList2":{"album":[${subsonicAlbum("al1", "First")}]}""") }
        answers["search3"] = { _ -> MockResponse.Builder().code(500).build() }
        try {
            source.syncCatalogue(force = true)
            fail("expected the sync to fail")
        } catch (e: SourceError.Http) {
            assertEquals(500, e.status)
        }
        assertEquals(2, dao.albumCount(SERVER))
        assertEquals(syncedAt, source.catalogueSyncedAtMs())

        answers["search3"] = SubsonicSourceTest.libraryAnswers()["search3"]!!
        source.syncCatalogue(force = true)
        assertEquals(listOf("al1"), dao.albumsByName(SERVER, 10, 0).map { it.album.id })
        // al1 is no longer starred on the server.
        assertFalse(dao.favourites(SERVER).any { it.id == "al1" })
    }

    @Test fun `clearing a server leaves nothing of its catalogue`() = runBlocking {
        val (source, _) = source(SubsonicSourceTest.libraryAnswers())
        source.syncCatalogue()
        db.clearServer(SERVER)
        assertEquals(0, dao.trackCount(SERVER))
        assertNull(source.catalogueSyncedAtMs())
    }
}

object PagedCatalogueSyncTestData {
    fun albums(count: Int) = (0 until count).joinToString(",") { subsonicAlbum("a$it", "Album $it") }
}
