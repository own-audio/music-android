package audio.own.music.library

import audio.own.kit.ownaudio.OwnAudioJson
import audio.own.music.library.db.CatalogueDatabase
import audio.own.music.library.model.Sharing
import audio.own.music.library.ownaudio.ListenerState
import audio.own.music.library.ownaudio.OwnAudioCatalogueSync
import audio.own.music.library.ownaudio.OwnAudioSource
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Ported from music-ios OwnAudioFeedTests (and OwnAudioPushTests), on the Room catalogue. */
@RunWith(RobolectricTestRunner::class)
class OwnAudioFeedTest {
    private lateinit var db: CatalogueDatabase

    @Before fun open() {
        db = memoryDatabase()
    }

    @After fun close() = db.close()

    /** The iOS test's library, as snapshot lines. */
    private val library = listOf(
        record("contributor", "band", contributorJson("band", "The Band", "https://music.example.com/api/v2/images/o1?e=1&s=x")),
        record("contributor", "guest", contributorJson("guest", "A Guest")),
        record("contributor", "reader", contributorJson("reader", "A Narrator")),
        record("album", "al", albumJson("al", "First Album", image = "https://x")),
        record("genre", "g", """{"id":"g","name":"Rock"}"""),
        record(
            "track", "t1",
            trackJson(
                "t1", "Song One", genres = """["g"]""",
                credits = """[{"contributor_id":"band","role":"artist","position":0,"join_phrase":" feat. "},{"contributor_id":"guest","role":"featured","position":1,"join_phrase":""}]""",
            ),
        ),
        record("favourite.track", "t1", """{"kind":"track","id":"t1","created_at":"2026-10-11T12:00:00Z"}"""),
        record("rating.album", "al", """{"kind":"album","id":"al","rating":4,"updated_at":"2026-10-11T12:00:00Z"}"""),
    )

    private fun snapshotAnswer(cursor: String = "c0", lines: List<String> = library) = ndjson(begin(cursor), *lines.toTypedArray(), end(cursor))

    private fun stub(route: (RecordedRequest) -> MockResponse?) = StubServer { request ->
        route(request) ?: when (request.url.encodedPath) {
            "/api/v2/me" -> json(ME)
            else -> notFound()
        }
    }

    private fun source(stub: StubServer) = OwnAudioSource(SERVER, "me@example.com", stub.client(), db)

    @Test fun `the API's records become the app's songs, albums and artists`() = runBlocking {
        stub { if (it.url.encodedPath == "/api/v2/sync/snapshot") snapshotAnswer() else null }.use { stub ->
            val src = source(stub)
            src.syncCatalogue()
            val (album, songs) = src.album("$SERVER|al")
            val song = songs.single()
            assertEquals("The Band feat. A Guest", song.artist)
            assertEquals("$SERVER|band", song.artistId)
            assertEquals("First Album", song.album)
            assertEquals("Rock", song.genre)
            assertEquals(200_000L, song.durationMs)
            assertEquals("flac", song.suffix)
            assertEquals("no cover of its own: the album's", "$SERVER|al", song.coverArt)
            assertTrue(song.isFavorite)
            assertEquals(Sharing.SHARED, song.sharing)
            assertEquals("The Band", album.artist)
            assertEquals(2001, album.year)
            assertEquals(4, album.rating)
            assertEquals("$SERVER|al", album.coverArt)
            val artists = src.artists()
            assertEquals("a featured guest on a track, and a narrator, are not artists", listOf("$SERVER|band"), artists.map { it.id })
            assertEquals("a picture by id and image, like a cover: a new picture is a new id", "$SERVER|band~o1", artists.single().coverArt)
            assertNull(artists.single().imageUrl)
            assertEquals(1, artists.single().albumCount)
        }
    }

    @Test fun `changes - upserts and deletes applied, other kinds left out, the cursor moved on`() = runBlocking {
        val changes = """{"changes":[
            {"kind":"track","id":"t1","op":"upsert","record":${trackJson("t1", "Song One (Live)", contentType = "audio/mpeg")}},
            {"kind":"favourite.track","id":"t1","op":"delete"},
            {"kind":"book","id":"b1","op":"upsert","record":{"id":"b1"}}],"cursor":"c1","has_more":false,"reset":false}"""
        stub {
            when (it.url.encodedPath) {
                "/api/v2/sync/snapshot" -> snapshotAnswer()
                "/api/v2/sync/changes" -> json(changes)
                else -> null
            }
        }.use { stub ->
            val src = source(stub)
            assertTrue(src.syncCatalogue().fromSnapshot)
            val result = src.syncCatalogue()
            assertFalse(result.fromSnapshot)
            assertEquals("c1", result.cursor)
            assertEquals("c1", db.catalogue().feedState(SERVER)?.cursor)
            val song = src.song("$SERVER|t1")
            assertEquals("Song One (Live)", song.title)
            assertFalse(song.isFavorite)
            assertEquals("mp3", song.suffix)
            assertEquals("the changes page asked from the snapshot's cursor", "c0", stub.requests.last().url.queryParameter("cursor"))
        }
    }

    @Test fun `an expired cursor means a fresh snapshot, which replaces what was kept`() = runBlocking {
        var snapshots = 0
        stub {
            when (it.url.encodedPath) {
                "/api/v2/sync/snapshot" -> if (snapshots++ == 0) snapshotAnswer() else snapshotAnswer("s1", listOf(record("genre", "g2", """{"id":"g2","name":"Jazz"}""")))
                "/api/v2/sync/changes" -> problem("sync_cursor_expired", 410)
                else -> null
            }
        }.use { stub ->
            val src = source(stub)
            src.syncCatalogue()
            val result = src.syncCatalogue()
            assertTrue(result.fromSnapshot)
            assertEquals("s1", result.cursor)
            assertEquals(listOf("Jazz"), src.genres().map { it.name })
            assertEquals("a snapshot replaces what was kept", 0, db.catalogue().trackCount(SERVER))
            assertTrue(src.favorites().isEmpty)
        }
    }

    @Test fun `reset and invalid_cursor also start over`() = runBlocking {
        for (answer in listOf(json("""{"changes":[],"cursor":"x","has_more":false,"reset":true}"""), problem("invalid_cursor", 400))) {
            var snapshots = 0
            stub {
                when (it.url.encodedPath) {
                    "/api/v2/sync/snapshot" -> {
                        snapshots++
                        snapshotAnswer()
                    }
                    "/api/v2/sync/changes" -> answer
                    else -> null
                }
            }.use { stub ->
                db.clearServer(SERVER)
                val src = source(stub)
                src.syncCatalogue()
                assertTrue(src.syncCatalogue().fromSnapshot)
                assertEquals(2, snapshots)
                assertEquals(1, db.catalogue().trackCount(SERVER))
            }
        }
    }

    @Test fun `a cut snapshot resumes after the last record with the same cursor`() = runBlocking {
        stub {
            if (it.url.encodedPath != "/api/v2/sync/snapshot") return@stub null
            if (it.url.queryParameter("after") == null) {
                // No end line: the connection broke after the album.
                json(listOf(begin("c0")).plus(library.take(4)).joinToString("\n") + "\n", type = "application/x-ndjson")
            } else {
                ndjson(*library.drop(4).toTypedArray(), end("c0"))
            }
        }.use { stub ->
            val src = source(stub)
            val result = src.syncCatalogue()
            assertEquals("c0", result.cursor)
            val resume = stub.requests.last { it.url.encodedPath == "/api/v2/sync/snapshot" }
            assertEquals("album:al", resume.url.queryParameter("after"))
            assertEquals("c0", resume.url.queryParameter("cursor"))
            assertEquals("The Band feat. A Guest", src.song("$SERVER|t1").artist)
            assertEquals("contributor,album,genre,track,playlist,favourite,rating", resume.url.queryParameter("kinds"))
        }
    }

    @Test fun `a failed changes page leaves the catalogue and the cursor as they were`() = runBlocking {
        var page = 0
        stub {
            when (it.url.encodedPath) {
                "/api/v2/sync/snapshot" -> snapshotAnswer()
                "/api/v2/sync/changes" -> when (page++) {
                    0 -> json("""{"changes":[{"kind":"track","id":"t1","op":"delete"}],"cursor":"c1","has_more":true,"reset":false}""")
                    else -> json("not json at all")
                }
                else -> null
            }
        }.use { stub ->
            val src = source(stub)
            src.syncCatalogue()
            assertThrows(SourceError.Unreadable::class.java) { runBlocking { src.syncCatalogue() } }
            assertEquals("the first page is committed with its cursor", "c1", db.catalogue().feedState(SERVER)?.cursor)
            assertEquals(0, db.catalogue().trackCount(SERVER))
            assertEquals("the album is still there", 1, db.catalogue().albumCount(SERVER))
        }
    }

    @Test fun `deletes remove records and their marks, a renamed contributor renames its lines`() = runBlocking {
        val changes = """{"changes":[
            {"kind":"contributor","id":"band","op":"upsert","record":${contributorJson("band", "The New Band")}},
            {"kind":"rating.album","id":"al","op":"delete"},
            {"kind":"genre","id":"g","op":"upsert","record":{"id":"g","name":"Rock & Roll"}},
            {"kind":"album","id":"al","op":"upsert","record":${albumJson("al", "First Album (Remaster)")}}],"cursor":"c1","has_more":false,"reset":false}"""
        stub {
            when (it.url.encodedPath) {
                "/api/v2/sync/snapshot" -> snapshotAnswer()
                "/api/v2/sync/changes" -> json(changes)
                else -> null
            }
        }.use { stub ->
            val src = source(stub)
            src.syncCatalogue()
            src.syncCatalogue()
            val (album, songs) = src.album("$SERVER|al")
            assertEquals("The New Band", album.artist)
            assertNull(album.rating)
            assertEquals("The New Band feat. A Guest", songs.single().artist)
            assertEquals("First Album (Remaster)", songs.single().album)
            assertEquals("Rock & Roll", songs.single().genre)
        }
    }

    @Test fun `favourites and ratings come from the change feed`() {
        fun obj(text: String) = OwnAudioJson.parseToJsonElement(text) as JsonObject
        val values = ListenerState.Values.fromFeed(
            mapOf(
                "favourite.track" to mapOf("t1" to obj("""{"kind":"track","id":"t1"}""")),
                "favourite.contributor" to mapOf("band" to obj("""{"kind":"contributor","id":"band"}""")),
                "rating.album" to mapOf("al" to obj("""{"kind":"album","id":"al","rating":4}""")),
                "track" to mapOf("t2" to obj("""{"id":"t2"}""")),
            ),
        )
        val state = ListenerState()
        state.update(values)
        assertEquals(setOf("t1", "band"), state.current.favourites)
        assertEquals(mapOf("al" to 4), state.current.ratings)
    }

    @Test fun `after a sync, answers from the server know the marks and whose a song is`() = runBlocking {
        stub { if (it.url.encodedPath == "/api/v2/sync/snapshot") snapshotAnswer() else null }.use { stub ->
            val src = source(stub)
            src.syncCatalogue()
            assertEquals(setOf("t1"), src.listener.current.favourites)
            assertEquals(mapOf("al" to 4), src.listener.current.ratings)
            assertEquals("u", src.listener.current.userId)
        }
    }

    @Test fun `two syncs at once run one after the other`() = runBlocking {
        stub {
            when (it.url.encodedPath) {
                "/api/v2/sync/snapshot" -> snapshotAnswer()
                "/api/v2/sync/changes" -> json("""{"changes":[],"cursor":"c1","has_more":false,"reset":false}""")
                else -> null
            }
        }.use { stub ->
            val sync = OwnAudioCatalogueSync(db, SERVER, stub.client())
            val results = listOf(async { sync.sync() }, async { sync.sync() }).map { it.await() }
            assertEquals(listOf(true, false), results.map { it.fromSnapshot })
            assertEquals(1, stub.requests.count { it.url.encodedPath == "/api/v2/sync/snapshot" })
        }
    }

    // region Offline edits and live updates (OwnAudioPushTests)

    @Test fun `edits become the API's changes - kinds mapped, a play with its length`() {
        StubServer { notFound() }.use { stub ->
            val src = source(stub)
            val at = Instant.ofEpochSecond(1_791_000_000)
            val rating = src.change(OfflineEdit("c1", "$SERVER|al", OfflineEdit.Change.Rating(FavoriteKind.ALBUM, 4), at))
            assertEquals("rate", rating.op)
            assertEquals("album", rating.kind.wire)
            assertEquals("al", rating.id)
            assertEquals(4, rating.rating)
            assertEquals(at, rating.at)
            val artist = src.change(OfflineEdit("c2", "$SERVER|ar", OfflineEdit.Change.Favorite(FavoriteKind.ARTIST, false), at))
            assertEquals("unfavourite", artist.op)
            assertEquals("contributor", artist.kind.wire)
            val play = src.change(OfflineEdit("c3", "$SERVER|t1", OfflineEdit.Change.Played(at, 120_500), at.plusSeconds(121)))
            assertEquals("play", play.op)
            assertEquals("track", play.kind.wire)
            assertEquals(120_500L, play.listenedMs)
            assertEquals(at, play.startedAt)
            assertEquals(at.plusSeconds(121), play.endedAt)
            assertEquals("c3", play.clientId)
        }
    }

    @Test fun `more than 500 go in several requests`() = runBlocking {
        StubServer { json("[]") }.use { stub ->
            val edits = (0 until 1001).map { OfflineEdit("c$it", "$SERVER|t$it", OfflineEdit.Change.Favorite(FavoriteKind.SONG, true), Instant.now()) }
            source(stub).push(edits)
            assertEquals(3, stub.requests.size)
        }
    }

    @Test fun `each edit gets what the server said of it`() = runBlocking {
        StubServer { json("""[{"client_id":"a","status":"ok"},{"client_id":"b","status":"not_found"},{"client_id":"c","status":"duplicate"}]""") }.use { stub ->
            val edits = listOf("a", "b", "c", "d").map { OfflineEdit(it, "$SERVER|t$it", OfflineEdit.Change.Favorite(FavoriteKind.SONG, true), Instant.now()) }
            val results = source(stub).push(edits)
            assertEquals(listOf(true, false, true, true), results.map { it.accepted })
            assertEquals("not_found", results[1].code)
            assertEquals(listOf("a", "b", "c", "d"), results.map { it.clientId })
        }
    }

    @Test fun `a server that isn't signed in takes them one at a time`() = runBlocking {
        StubServer { json("[]", 500) }.use { stub ->
            val src = OwnAudioSource(SERVER, "me@example.com", stub.client(signedIn = false), db)
            assertThrows(SourceError.Unsupported::class.java) {
                runBlocking { src.push(listOf(OfflineEdit("c", "$SERVER|t", OfflineEdit.Change.Favorite(FavoriteKind.SONG, true), Instant.now()))) }
            }
            assertTrue(stub.requests.isEmpty())
        }
    }

    @Test fun `only library events are passed on`() = runBlocking {
        StubServer {
            json("event: library\nid: 1\ndata: {}\n\nevent: other\ndata: x\n\nevent: library\nid: 2\ndata: {}\n\n", type = "text/event-stream")
        }.use { stub ->
            assertEquals(2, source(stub).libraryChanges().toList().size)
        }
    }

    // endregion

    @Test fun `removing a server leaves nothing of it`() = runBlocking {
        stub { if (it.url.encodedPath == "/api/v2/sync/snapshot") snapshotAnswer() else null }.use { stub ->
            source(stub).syncCatalogue()
            db.clearServer(SERVER)
            assertEquals(0, db.catalogue().trackCount(SERVER))
            assertNull(db.catalogue().feedState(SERVER))
            assertNotNull(db.catalogue())
        }
    }
}
