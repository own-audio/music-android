package audio.own.music.library

import audio.own.music.library.db.CatalogueDatabase
import audio.own.music.library.identify.IdentifyRunReview
import audio.own.music.library.identify.IdentifyRuns
import audio.own.music.library.identify.IdentifyStart
import audio.own.music.library.identify.IdentifyTab
import audio.own.music.library.ownaudio.OwnAudioSource
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** M11: identify, without the screens (music-ios App/OwnAudioMusic/Identify). */
@RunWith(RobolectricTestRunner::class)
class IdentifyRunsTest {
    private lateinit var db: CatalogueDatabase

    @Before fun open() {
        db = memoryDatabase()
    }

    @After fun close() = db.close()

    private fun stub(route: (RecordedRequest) -> MockResponse?): Pair<StubServer, OwnAudioSource> {
        val stub = StubServer { request -> route(request) ?: if (request.url.encodedPath == "/api/v2/me") json(ME) else notFound() }
        return stub to OwnAudioSource(SERVER, "me@example.com", stub.client(), db)
    }

    private fun detailed(code: String, status: Int, detail: String) = json(
        """{"type":"urn:own-audio:problem:$code","title":"x","status":$status,"code":"$code","detail":"$detail"}""",
        status,
        "application/problem+json",
    )

    private fun run(status: String = "running") =
        """{"id":"r1","status":"$status","scope":{"kind":"library","label":"Whole library"},"tracks":3,"groups_total":2,"groups_done":1,"counts":{"sure":1,"review":2},"created_at":"2026-10-12T05:01:38Z"}"""

    private fun proposal(id: String, group: String, track: Int?, disc: Int? = 1, decision: String = "pending", reasons: String = "[]", match: Boolean = true) =
        """{"id":"$id","group":{"id":"$group","kind":"album","label":"Album $group"},
        "track":{"id":"t$id","title":"Song $id","artist":"Band","album":"Album $group","disc_number":${disc ?: "null"},"track_number":${track ?: "null"},"duration_secs":200},
        "class":"review","reasons":$reasons,
        "proposal":${if (match) """{"mb_recording_id":"m$id","title":"Song $id","album":"Album $group","mb_release_id":"rel$group","score":90}""" else "null"},
        "decision":"$decision"}"""

    @Test fun `offered with the module on a new enough v2, charges with billing, asked once`() = runBlocking<Unit> {
        var discovery = answer("discovery")
        val (stub, src) = stub { if (it.url.encodedPath == "/.well-known/own-audio") json(discovery) else null }
        stub.use {
            assertEquals(IdentifyAvailability(charges = false), src.identifyAvailability())
            discovery = discovery.replace("\"identify_library\": true", "\"identify_library\": false")
            assertEquals("remembered", IdentifyAvailability(charges = false), src.identifyAvailability())
            assertEquals(1, stub.requests.count { it.url.encodedPath == "/.well-known/own-audio" })
        }
        val (stub2, other) = stub {
            if (it.url.encodedPath == "/.well-known/own-audio") {
                json(answer("discovery").replace("\"identify_library\": true", "\"identify_library\": false").replace("\"music_identify\": true", "\"music_identify\": false"))
            } else {
                null
            }
        }
        stub2.use { assertNull(other.identifyAvailability()) }
        val (stub3, billed) = stub {
            if (it.url.encodedPath == "/.well-known/own-audio") json(answer("discovery").replace("\"billing\": false", "\"billing\": true")) else null
        }
        stub3.use { assertEquals(IdentifyAvailability(charges = true), billed.identifyAvailability()) }
    }

    @Test fun `start - a quote first where it charges, a folder scope, 402 as PaymentRequired`() = runBlocking<Unit> {
        var short = false
        val (stub, src) = stub {
            when ("${it.method} ${it.url.encodedPath}") {
                "POST /api/v2/identify/quote" -> json("""{"tracks":12,"charge_micro":12000}""")
                "POST /api/v2/identify/runs" -> if (short) detailed("payment_required", 402, "not enough credit") else json(run("queued"), 202)
                "GET /api/v2/identify/runs" -> json("""{"items":[${run("queued")}]}""")
                "GET /api/v2/library/folders" -> json(answer("library_folders"))
                else -> null
            }
        }
        stub.use {
            val runs: IdentifyRuns = src.identifyRuns(IdentifyAvailability(charges = true))
            runs.loadFolders()
            assertEquals(listOf("/music", "/private-music"), runs.folders.value.map { it.path })
            val music = runs.folders.value.first()
            assertEquals("/music", music.path)
            val scope = runs.scope(music, "Whole library")
            val start = runs.begin(scope)
            assertTrue(start is IdentifyStart.Confirm)
            assertEquals(12_000L, (start as IdentifyStart.Confirm).quote.chargeMicro)
            assertEquals("""{"scope":{"id":"${music.id}","kind":"folder","label":"/music"}}""", stub.requests.single { it.url.encodedPath.endsWith("/quote") }.text())
            assertTrue("nothing started before the price is accepted", stub.requests.none { it.method == "POST" && it.url.encodedPath == "/api/v2/identify/runs" })

            val started = runs.start(start.scope)
            assertEquals("r1", started.id)
            assertTrue(runs.hasActiveRun)

            short = true
            assertThrows(SourceError.PaymentRequired::class.java) { runBlocking { runs.start(runs.scope(null, "Whole library")) } }
        }
    }

    @Test fun `free servers start at once, an album scope by its api id`() = runBlocking<Unit> {
        val (stub, src) = stub {
            when ("${it.method} ${it.url.encodedPath}") {
                "POST /api/v2/identify/runs" -> json(run("queued"), 202)
                "GET /api/v2/identify/runs" -> json("""{"items":[]}""")
                else -> null
            }
        }
        stub.use {
            val start = src.identifyRuns(IdentifyAvailability(charges = false)).begin(src.albumScope("$SERVER|al", "First Album"))
            assertTrue(start is IdentifyStart.Started)
            assertTrue(stub.requests.none { it.url.encodedPath.endsWith("/quote") })
            assertEquals("""{"scope":{"id":"al","kind":"album","label":"First Album"}}""", stub.requests.first().text())
        }
    }

    @Test fun `a run's proposals by album, songs by disc and number - Done shows only decided ones`() = runBlocking<Unit> {
        val (stub, src) = stub {
            when (it.url.encodedPath) {
                "/api/v2/identify/runs/r1" -> json(run())
                "/api/v2/identify/runs/r1/proposals" -> when (it.url.queryParameter("cursor")) {
                    null -> json("""{"items":[${proposal("3", "B", 1)},${proposal("2", "A", 2)},${proposal("1", "A", 1, disc = 2)}],"next":"c2"}""")
                    else -> json("""{"items":[${proposal("4", "A", null)},${proposal("5", "A", 1, decision = "accepted")}]}""")
                }
                else -> null
            }
        }
        stub.use {
            val review: IdentifyRunReview = src.identifyRun("r1")
            review.reload()
            val first = stub.requests.first { it.url.encodedPath.endsWith("/proposals") }
            assertEquals("review", first.url.queryParameter("class"))
            assertEquals("pending", first.url.queryParameter("decision"))
            val groups = review.state.value.groups
            assertEquals(listOf("B", "A"), groups.map { it.group.id })
            assertEquals("disc 1 first, unnumbered last, then disc 2", listOf("5", "2", "4", "1"), groups[1].proposals.map { it.id })
            assertEquals("Album A", groups[1].albumMatch?.album)

            review.show(IdentifyTab.DONE)
            assertEquals(listOf("5"), review.state.value.proposals.map { it.id })
            val last = stub.requests.last { it.url.encodedPath.endsWith("/proposals") }
            assertNull(last.url.queryParameter("class"))
        }
    }

    @Test fun `accept all takes pending matches but not another artist's - each action reloads`() = runBlocking<Unit> {
        val (stub, src) = stub {
            when ("${it.method} ${it.url.encodedPath}") {
                "GET /api/v2/identify/runs/r1" -> json(run())
                "GET /api/v2/identify/runs/r1/proposals" -> json(
                    """{"items":[${proposal("1", "A", 1)},${proposal("2", "A", 2, reasons = "[\"artist_differs\"]")},${proposal("3", "A", 3, match = false)},${proposal("4", "A", 4, decision = "rejected")}]}""",
                )
                "POST /api/v2/identify/proposals/1/accept" -> json(proposal("1", "A", 1, decision = "accepted"))
                "POST /api/v2/identify/runs/r1/accept" -> json("""{"accepted":7}""")
                else -> null
            }
        }
        stub.use {
            val review = src.identifyRun("r1")
            review.reload()
            val group = review.state.value.groups.single()
            assertEquals(listOf("1"), group.acceptable.map { it.id })
            assertEquals(1, review.acceptAll(group))
            assertEquals(listOf("POST /api/v2/identify/proposals/1/accept"), stub.paths().filter { it.startsWith("POST") })
            assertEquals("GET /api/v2/identify/runs/r1", stub.paths()[stub.paths().indexOf("POST /api/v2/identify/proposals/1/accept") + 1])

            assertEquals(7, review.acceptSure())
            assertEquals("""{"class":"sure"}""", stub.requests.single { it.url.encodedPath == "/api/v2/identify/runs/r1/accept" }.text())
        }
    }

    @Test fun `undo of a song never identified is false (409), other recording by a pasted link, a deleted run is gone`() = runBlocking<Unit> {
        var deleted = false
        val (stub, src) = stub {
            when ("${it.method} ${it.url.encodedPath}") {
                "GET /api/v2/identify/runs/r1" -> if (deleted) notFound() else json(run("ready"))
                "GET /api/v2/identify/runs/r1/proposals" -> json("""{"items":[${proposal("1", "A", 1, decision = "accepted")}]}""")
                "DELETE /api/v2/tracks/t1/identity" -> detailed("not_identified", 409, "never identified")
                "DELETE /api/v2/tracks/t9/identity" -> json(trackJson("t9", "Nine"))
                "PUT /api/v2/identify/proposals/1/recording" -> json(proposal("1", "A", 1, decision = "accepted"))
                "DELETE /api/v2/identify/runs/r1" -> noContent().also { deleted = true }
                else -> null
            }
        }
        stub.use {
            val review = src.identifyRun("r1")
            review.reload()
            val p = review.state.value.proposals.single()
            assertFalse(review.undo(p))
            assertFalse(src.undoIdentify("$SERVER|t1"))
            assertTrue(src.undoIdentify("$SERVER|t9"))

            review.chooseRecording(p, "  https://musicbrainz.org/recording/0f0e4e1c-9a8b-4e6f-8f5a-1b2c3d4e5f60 ")
            assertEquals(
                """{"accept":true,"recording":"https://musicbrainz.org/recording/0f0e4e1c-9a8b-4e6f-8f5a-1b2c3d4e5f60"}""",
                stub.requests.single { it.url.encodedPath.endsWith("/recording") }.text(),
            )

            review.delete()
            assertTrue(review.state.value.isGone)
        }
    }

    @Test fun `a song's recording search - nothing on its album, so again without it`() = runBlocking<Unit> {
        val (stub, src) = stub {
            if (it.url.encodedPath == "/api/v2/tracks/t1/identity/search") {
                if (it.text().contains("\"album\"")) {
                    json("""{"items":[]}""")
                } else {
                    json("""{"items":[{"mb_recording_id":"m1","title":"Song","artist":"Band","duration_ms":203000,"score":100}]}""")
                }
            } else {
                null
            }
        }
        stub.use {
            val found = src.searchRecordings("$SERVER|t1", " Song ", "Band", "Wrong Album")
            assertEquals("Wrong Album", found.widenedFrom)
            assertEquals("m1", found.candidates.single().mbRecordingId)
            val bodies = stub.requests.map { it.text() }
            assertEquals(listOf("""{"album":"Wrong Album","artist":"Band","title":"Song"}""", """{"artist":"Band","title":"Song"}"""), bodies)
            assertTrue(found.candidates.single().lengthFits(200_000))
            assertFalse(found.candidates.single().lengthFits(190_000))
        }
    }
}
