package audio.own.music.library

import audio.own.music.library.db.CatalogueDatabase
import audio.own.music.library.db.CatalogueSearch
import audio.own.music.library.ownaudio.OwnAudioSource
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Offline song search: word prefixes from the search table, one or two letters anywhere. */
@RunWith(RobolectricTestRunner::class)
class CatalogueSearchTest {
    private lateinit var db: CatalogueDatabase

    @Before fun open() {
        db = memoryDatabase()
    }

    @After fun close() = db.close()

    private val library = listOf(
        record("contributor", "band", contributorJson("band", "The Band")),
        record("contributor", "orch", contributorJson("orch", "Česká filharmonie")),
        record("album", "al", albumJson("al", "First Album")),
        record("album", "cl", albumJson("cl", "Symphonies", credits = """[{"contributor_id":"orch","role":"artist","position":0,"join_phrase":""}]""")),
        record("track", "t1", trackJson("t1", "Night Rain")),
        record("track", "t2", trackJson("t2", "Rainbow Road", number = 2)),
        record("track", "t3", trackJson("t3", "Love Me Do", number = 3)),
        record("track", "t4", trackJson("t4", "AC/DC Medley", number = 4)),
        record(
            "track", "t5",
            trackJson("t5", "Dvořák: Symphony No. 9", albumId = "cl", credits = """[{"contributor_id":"orch","role":"artist","position":0,"join_phrase":""}]"""),
        ),
    )

    private var changes: List<String> = emptyList()

    private fun stub(route: (RecordedRequest) -> MockResponse? = { null }) = StubServer { request ->
        route(request) ?: when (request.url.encodedPath) {
            "/api/v2/me" -> json(ME)
            "/api/v2/sync/snapshot" -> ndjson(begin("c0"), *library.toTypedArray(), end("c0"))
            "/api/v2/sync/changes" -> json("""{"changes":[${changes.joinToString()}],"cursor":"c1","has_more":false,"reset":false}""")
            else -> notFound()
        }
    }

    private suspend fun found(src: OwnAudioSource, text: String) = src.searchCatalogue(text).songs.map { it.title }.toSet()

    private fun <T> synced(block: suspend (OwnAudioSource) -> T): T = runBlocking {
        stub().use { stub ->
            val src = OwnAudioSource(SERVER, "me@example.com", stub.client(), db)
            src.syncCatalogue()
            block(src)
        }
    }

    @Test fun `words are found by how they start, not in the middle`() = synced { src ->
        assertEquals(setOf("Night Rain", "Rainbow Road"), found(src, "rain"))
        assertEquals(setOf("Night Rain", "Rainbow Road"), found(src, "RAI"))
        assertEquals("a word's middle is not a word", emptySet<String>(), found(src, "ain"))
    }

    @Test fun `every word typed has to be there, in any of title, artist and album`() = synced { src ->
        assertEquals(setOf("Night Rain"), found(src, "night ra"))
        assertEquals(setOf("Night Rain", "Rainbow Road", "Love Me Do", "AC/DC Medley"), found(src, "the band"))
        assertEquals(setOf("Night Rain"), found(src, "first night"))
        assertEquals(emptySet<String>(), found(src, "night rainbow"))
    }

    @Test fun `case and accents don't matter`() = synced { src ->
        assertEquals(setOf("Dvořák: Symphony No. 9"), found(src, "dvorak"))
        assertEquals(setOf("Dvořák: Symphony No. 9"), found(src, "ČESKÁ symph"))
        assertEquals(setOf("Dvořák: Symphony No. 9"), found(src, "ceska"))
    }

    @Test fun `one or two letters are looked for anywhere`() = synced { src ->
        assertEquals(setOf("Love Me Do"), found(src, "ov"))
        assertEquals(setOf("AC/DC Medley"), found(src, "c/"))
    }

    @Test fun `punctuation and the query syntax typed are only text`() = synced { src ->
        assertEquals(setOf("AC/DC Medley"), found(src, "ac/dc"))
        assertEquals(setOf("Night Rain"), found(src, "\"night\" -rain*"))
        assertEquals(emptySet<String>(), found(src, "NEAR(x) ^^^"))
        assertEquals(emptySet<String>(), found(src, "!!!"))
    }

    @Test fun `the search table follows changes, renames and deletes`() = synced { src ->
        changes = listOf(
            """{"kind":"track","id":"t1","op":"upsert","record":${trackJson("t1", "Morning Sun")}}""",
            """{"kind":"track","id":"t2","op":"delete"}""",
            """{"kind":"track","id":"t9","op":"upsert","record":${trackJson("t9", "Evening Rain", number = 9)}}""",
        )
        src.syncCatalogue()
        assertEquals(setOf("Evening Rain"), found(src, "rain"))
        assertEquals(setOf("Morning Sun"), found(src, "morn"))
        assertEquals(emptySet<String>(), found(src, "rainbow"))
    }

    @Test fun `another server's songs are not this one's`() = runBlocking {
        stub().use { stub ->
            OwnAudioSource(SERVER, "me@example.com", stub.client(), db).syncCatalogue()
            val other = OwnAudioSource("other", "me@example.com", stub.client(), db)
            other.syncCatalogue()
            db.clearServer(SERVER)
            assertEquals(setOf("Night Rain", "Rainbow Road"), found(other, "rain"))
            assertEquals(emptyList<Any>(), db.catalogue().searchSongs(SERVER, "rain", 10))
        }
    }

    @Test fun `what the search table is asked`() {
        assertEquals("night* ra*", CatalogueSearch.match("Night  RA"))
        assertEquals("ac* dc*", CatalogueSearch.match("AC/DC"))
        assertEquals("take*", CatalogueSearch.match("Take 5"))
        assertEquals("dvořák*", CatalogueSearch.match("Dvořák"))
        assertNull(CatalogueSearch.match("ab"))
        assertNull(CatalogueSearch.match(" a "))
        assertNull(CatalogueSearch.match("a b c"))
        assertNull(CatalogueSearch.match("!!!"))
        assertEquals("%50\\%\\_off%", CatalogueSearch.like("50%_off"))
    }
}
