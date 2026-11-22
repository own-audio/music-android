package audio.own.music.library

import audio.own.music.library.db.CatalogueDatabase
import audio.own.music.library.db.DeferredSchema
import audio.own.music.library.ownaudio.OwnAudioCatalogueSync
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * A first snapshot writes without the big tables' indexes and the search table's triggers and
 * makes them at its end. Whatever happens on the way, the catalogue keeps them afterwards, a cut
 * snapshot resumes with `after=`, and a failed one leaves the catalogue there was.
 */
@RunWith(RobolectricTestRunner::class)
class FirstSnapshotTest {
    private lateinit var db: CatalogueDatabase

    @Before fun open() {
        db = memoryDatabase()
    }

    @After fun close() = db.close()

    private val heads = listOf(
        record("contributor", "band", contributorJson("band", "The Band")),
        record("album", "al", albumJson("al", "First Album", trackCount = 6)),
        record("genre", "g", """{"id":"g","name":"Rock"}"""),
    )

    private fun tracks(vararg titles: String) = titles.mapIndexed { i, title -> record("track", "t$i", trackJson("t$i", title, genres = """["g"]""", number = i + 1)) }

    private val library = heads + tracks("Night Rain", "Rainbow Road", "Love Me Do", "Golden Sun", "Silver Moon", "Black Sky")

    private fun stub(route: (RecordedRequest) -> MockResponse?) = StubServer { request ->
        route(request) ?: when (request.url.encodedPath) {
            "/api/v2/me" -> json(ME)
            else -> notFound()
        }
    }

    private fun cut(lines: List<String>) = json(lines.joinToString("\n", postfix = "\n"), type = "application/x-ndjson")

    private fun names(type: String, table: String): Set<String> =
        db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type = '$type' AND tbl_name = '$table'").use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }

    private fun assertSchemaWhole() {
        assertTrue(names("index", "track").containsAll(listOf("index_track_serverId_title_artistLine_albumTitle", "index_track_serverId_albumId_discNumber_trackNumber")))
        assertTrue("index_album_serverId_title" in names("index", "album"))
        assertTrue("index_track_credit_serverId_contributorId_role" in names("index", "track_credit"))
        assertEquals(4, names("trigger", "track").count { it.startsWith("room_fts_content_sync_track_fts_") })
        assertTrue(db.openHelper.readableDatabase.query("SELECT 1 FROM deferred_schema").use { it.count == 0 })
    }

    private suspend fun titlesFound(text: String) = db.catalogue().searchSongs(SERVER, text, 50).map { it.track.title }.toSet()

    @Test fun `a first snapshot ends with its indexes and the search table filled`() = runBlocking {
        stub { if (it.url.encodedPath == "/api/v2/sync/snapshot") ndjson(begin("c0"), *library.toTypedArray(), end("c0")) else null }.use { stub ->
            OwnAudioCatalogueSync(db, SERVER, stub.client()).sync()
        }
        assertSchemaWhole()
        assertEquals(6, db.catalogue().trackCount(SERVER))
        assertEquals(setOf("Night Rain", "Rainbow Road"), titlesFound("rain"))
        assertEquals("The Band", db.catalogue().track(SERVER, "t0")!!.track.artistLine)
        assertEquals(listOf("Rock"), db.catalogue().genres(SERVER).filter { it.songCount == 6 }.map { it.name })
    }

    @Test fun `a cut first snapshot that then fails keeps what came, gets its indexes back and resumes with after`() = runBlocking {
        var attempt = 0
        stub {
            if (it.url.encodedPath != "/api/v2/sync/snapshot") return@stub null
            when (attempt++) {
                // No end line: the connection broke after the third song.
                0 -> cut(listOf(begin("c0")) + library.take(6))
                // The resume fails outright: the sync gives up for now.
                1 -> problem("unavailable", 503)
                else -> ndjson(*library.drop(6).toTypedArray(), end("c0"))
            }
        }.use { stub ->
            val sync = OwnAudioCatalogueSync(db, SERVER, stub.client())
            try {
                sync.sync()
                fail("expected the sync to fail")
            } catch (expected: Exception) {
                // the 503
            }
            assertNull(sync.progress.value)
            assertSchemaWhole()
            assertEquals("the songs that came are kept", 3, db.catalogue().trackCount(SERVER))
            assertEquals("track:t2", db.catalogue().feedState(SERVER)?.snapshotAfter)
            assertEquals(setOf("Night Rain", "Rainbow Road"), titlesFound("rain"))

            val result = sync.sync()
            assertTrue(result.fromSnapshot)
            assertEquals("c0", result.cursor)
            val resume = synchronized(stub.requests) { stub.requests.toList() }.last { it.url.encodedPath == "/api/v2/sync/snapshot" }
            assertEquals("track:t2", resume.url.queryParameter("after"))
            assertEquals("c0", resume.url.queryParameter("cursor"))
            assertEquals(6, db.catalogue().trackCount(SERVER))
            assertSchemaWhole()
            assertEquals(setOf("Black Sky"), titlesFound("sky"))
        }
    }

    @Test fun `a failed fresh snapshot leaves the old catalogue whole and searchable`() = runBlocking {
        var snapshots = 0
        stub {
            when (it.url.encodedPath) {
                "/api/v2/sync/snapshot" -> when (snapshots++) {
                    0 -> ndjson(begin("c0"), *library.toTypedArray(), end("c0"))
                    // The fresh one breaks after a song, then fails to resume.
                    1 -> cut(listOf(begin("c1")) + heads + tracks("Renamed Song"))
                    2 -> problem("unavailable", 503)
                    else -> ndjson(begin("c2"), *(heads + tracks("Only Song")).toTypedArray(), end("c2"))
                }
                "/api/v2/sync/changes" -> problem("sync_cursor_expired", 410)
                else -> null
            }
        }.use { stub ->
            val sync = OwnAudioCatalogueSync(db, SERVER, stub.client())
            sync.sync()
            try {
                sync.sync()
                fail("expected the sync to fail")
            } catch (expected: Exception) {
                // the 503
            }
            assertSchemaWhole()
            assertEquals("nothing of the old catalogue is gone yet", 6, db.catalogue().trackCount(SERVER))
            assertEquals(setOf("Golden Sun"), titlesFound("golden"))
            assertEquals(setOf("Renamed Song"), titlesFound("renamed"))

            sync.sync()
            assertEquals(listOf("Only Song"), db.catalogue().tracksByTitle(SERVER, 50, 0).map { it.track.title })
            assertEquals(emptySet<String>(), titlesFound("golden"))
            assertSchemaWhole()
        }
    }

    @Test fun `after the first snapshot, a song changed to fewer credits keeps none of the old ones`() = runBlocking {
        val guest = record("contributor", "guest", contributorJson("guest", "A Guest"))
        val duet = record(
            "track", "t0",
            trackJson(
                "t0", "Duet", genres = """["g"]""",
                credits = """[{"contributor_id":"band","role":"artist","position":0,"join_phrase":" feat. "},{"contributor_id":"guest","role":"featured","position":1,"join_phrase":""}]""",
            ),
        )
        stub {
            when (it.url.encodedPath) {
                "/api/v2/sync/snapshot" -> ndjson(begin("c0"), guest, *heads.toTypedArray(), duet, end("c0"))
                "/api/v2/sync/changes" -> json("""{"changes":[{"kind":"track","id":"t0","op":"upsert","record":${trackJson("t0", "Solo")}}],"cursor":"c1","has_more":false,"reset":false}""")
                else -> null
            }
        }.use { stub ->
            val sync = OwnAudioCatalogueSync(db, SERVER, stub.client())
            sync.sync()
            assertEquals(listOf("band", "guest"), db.catalogue().trackCredits(SERVER, "t0").map { it.contributorId })
            sync.sync()
            assertEquals(listOf("band"), db.catalogue().trackCredits(SERVER, "t0").map { it.contributorId })
            assertEquals("The Band", db.catalogue().track(SERVER, "t0")!!.track.artistLine)
            assertEquals(emptyList<String>(), db.catalogue().genres(SERVER).filter { it.songCount > 0 }.map { it.name })
        }
    }

    @Test fun `the songs read so far are told while a snapshot runs`() = runBlocking {
        val resumeAsked = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        var attempt = 0
        stub {
            if (it.url.encodedPath != "/api/v2/sync/snapshot") return@stub null
            if (attempt++ == 0) {
                cut(listOf(begin("c0")) + library.take(5))
            } else {
                resumeAsked.countDown()
                letGo.await(30, TimeUnit.SECONDS)
                ndjson(*library.drop(5).toTypedArray(), end("c0"))
            }
        }.use { stub ->
            val sync = OwnAudioCatalogueSync(db, SERVER, stub.client())
            val running = async(kotlinx.coroutines.Dispatchers.IO) { sync.sync() }
            assertTrue(resumeAsked.await(30, TimeUnit.SECONDS))
            assertEquals(2, sync.progress.value)
            letGo.countDown()
            running.await()
            assertNull(sync.progress.value)
        }
    }

    @Test fun `indexes a killed snapshot dropped come back when the catalogue opens`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val file = context.getDatabasePath("deferred-test.db").also { it.parentFile?.mkdirs(); it.delete() }
        var opened = CatalogueDatabase.builder(context, file.path).allowMainThreadQueries().build()
        try {
            stub { if (it.url.encodedPath == "/api/v2/sync/snapshot") ndjson(begin("c0"), *library.toTypedArray(), end("c0")) else null }.use { stub ->
                OwnAudioCatalogueSync(opened, SERVER, stub.client()).sync()
            }
            // As a snapshot leaves it when the app is killed halfway.
            DeferredSchema.defer(opened.openHelper.writableDatabase)
            opened.openHelper.writableDatabase.execSQL("DELETE FROM track_fts")
            assertTrue(DeferredSchema.isDeferred(opened.openHelper.writableDatabase))
            opened.close()

            opened = CatalogueDatabase.builder(context, file.path).allowMainThreadQueries().build()
            db.close()
            db = opened
            assertEquals(setOf("Night Rain", "Rainbow Road"), titlesFound("rain"))
            assertSchemaWhole()
        } finally {
            opened.close()
            file.delete()
        }
    }
}
