package audio.own.music.library

import audio.own.music.library.db.CatalogueDatabase
import audio.own.music.library.model.Artist
import audio.own.music.library.model.Sharing
import audio.own.music.library.ownaudio.OwnAudioSource
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** M10: sharing with the household and the listener's own pictures. */
@RunWith(RobolectricTestRunner::class)
class HouseholdArtworkTest {
    private lateinit var db: CatalogueDatabase

    @Before fun open() {
        db = memoryDatabase()
    }

    @After fun close() = db.close()

    /** An album with two of the listener's songs and one of someone else's. */
    private val snapshot = ndjson(
        begin("c0"),
        record("contributor", "band", contributorJson("band", "The Band")),
        record("album", "al", albumJson("al", "Shared Album", trackCount = 3)),
        record("track", "t1", trackJson("t1", "Mine One", visibility = "private")),
        record("track", "t2", trackJson("t2", "Mine Two", number = 2, visibility = "private")),
        record("track", "t3", trackJson("t3", "Theirs", number = 3, owner = "other")),
        end("c0"),
    )

    private fun stub(route: (RecordedRequest) -> MockResponse?): Pair<StubServer, OwnAudioSource> {
        val stub = StubServer { request ->
            route(request) ?: when ("${request.method} ${request.url.encodedPath}") {
                "GET /api/v2/me" -> json(ME)
                "GET /api/v2/sync/snapshot" -> snapshot
                else -> notFound()
            }
        }
        return stub to OwnAudioSource(SERVER, "me@example.com", stub.client(), db)
    }

    @Test fun `share my songs - one PUT per own song of the album, others' left alone, shown at once`() = runBlocking<Unit> {
        val (stub, src) = stub {
            if (it.method == "PUT" && it.url.encodedPath.endsWith("/visibility")) json("""{"visibility":"family"}""") else null
        }
        stub.use {
            src.syncCatalogue()
            assertEquals(listOf("$SERVER|t1", "$SERVER|t2"), src.ownSongIds("$SERVER|al"))
            assertEquals(2, src.setAlbumShared("$SERVER|al", true))
            val puts = stub.requests.filter { it.method == "PUT" }
            assertEquals(listOf("/api/v2/tracks/t1/visibility", "/api/v2/tracks/t2/visibility"), puts.map { it.url.encodedPath })
            assertTrue(puts.all { it.text() == """{"visibility":"family"}""" })
            val songs = src.album("$SERVER|al").songs.associate { it.title to it.sharing }
            assertEquals(mapOf("Mine One" to Sharing.SHARED, "Mine Two" to Sharing.SHARED, "Theirs" to Sharing.HOUSEHOLD), songs)
        }
    }

    @Test fun `the household - you first, then admins, then by name - credit null where the server doesn't charge`() = runBlocking<Unit> {
        val (stub, src) = stub {
            when (it.url.encodedPath) {
                "/api/v2/family" -> json("""{"id":"f","name":"Novak","image":null,"me":{"user_id":"u","role":"member"}}""")
                "/api/v2/family/members" -> json(
                    """[{"user_id":"b","display_name":"Bea","role":"member"},{"user_id":"a","display_name":"Zed","role":"admin"},
                    {"user_id":"u","display_name":"Me","role":"member"},{"user_id":"c","display_name":"Cy","role":"member","pending":true}]""",
                )
                "/api/v2/family/billing" -> problem("feature_unavailable", 501)
                "/api/v2/family/invites" -> json(
                    """[{"id":"i1","kind":"link","code":"ABC","max_uses":2,"use_count":1,"expires_at":"2026-12-01T10:00:00+00:00"},
                    {"id":"i2","kind":"link","code":"OLD","max_uses":1,"use_count":1,"expires_at":"2026-12-01T10:00:00Z"}]""",
                )
                else -> null
            }
        }
        stub.use {
            val h = src.household()
            assertEquals(listOf("Me", "Zed", "Bea", "Cy"), h.members.map { it.name })
            assertTrue(h.members.first().isMe && !h.isAdmin && h.members.last().isPending)
            assertNull(src.credit())
            val invite = src.invites().single()
            assertEquals("ABC", invite.code)
            assertEquals(1, invite.usesLeft)
            assertEquals(1796119200L, invite.expiresAt?.epochSecond)
        }
    }

    @Test fun `credit in the currency's units`() = runBlocking<Unit> {
        val (stub, src) = stub {
            if (it.url.encodedPath == "/api/v2/family/billing") {
                json("""{"balance_micro":5200000,"available_micro":5000000,"estimated_monthly_cost_micro":150000,"days_remaining":1000,"depleted":false,"pricing":{"currency":"USD","price_per_gb_month_micro":50000}}""")
            } else {
                null
            }
        }
        stub.use {
            val credit = src.credit()!!
            assertEquals(0, BigDecimal("5").compareTo(credit.balance))
            assertEquals(0, BigDecimal("0.15").compareTo(credit.monthlyCost))
            assertEquals(1000, credit.daysLeft)
        }
    }

    @Test fun `an album cover - one upload of the exact size, PUT with exactly its headers, then named by each own song`() = runBlocking<Unit> {
        lateinit var storage: String
        val (stub, src) = stub {
            when ("${it.method} ${it.url.encodedPath}") {
                "POST /api/v2/uploads" -> json(
                    """{"upload_id":"up1","url":"$storage","method":"PUT","headers":{"Content-Type":"image/jpeg","x-amz-checksum-sha256":"abc="},"expires_at":"2026-12-01T10:00:00Z"}""",
                    201,
                )
                "PUT /storage/key" -> MockResponse.Builder().code(200).build()
                "PUT /api/v2/tracks/t1/image" -> json(trackJson("t1", "Mine One", image = "https://img/new1"))
                "PUT /api/v2/tracks/t2/image" -> json(trackJson("t2", "Mine Two", number = 2, image = "https://img/new2"))
                "GET /api/v2/albums/al" -> json(albumJson("al", "Shared Album", image = "https://img/album"))
                else -> null
            }
        }
        storage = stub.server.url("/storage/key").toString()
        stub.use {
            src.syncCatalogue()
            val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
            src.setCover(src.ownSongIds("$SERVER|al"), "$SERVER|al", bytes, "image/jpeg")

            val create = stub.requests.single { it.url.encodedPath == "/api/v2/uploads" }
            assertEquals("""{"content_type":"image/jpeg","filename":"cover.jpg","kind":"track_cover","size_bytes":4}""", create.text())

            val put = stub.requests.single { it.url.encodedPath == "/storage/key" }
            assertEquals("PUT", put.method)
            assertEquals("image/jpeg", put.headers["Content-Type"])
            assertEquals("abc=", put.headers["x-amz-checksum-sha256"])
            assertNull("storage is signed for itself: no token goes there", put.headers["Authorization"])
            assertArrayEquals(bytes, put.body!!.toByteArray())

            val named = stub.requests.filter { it.url.encodedPath.endsWith("/image") }
            assertEquals(listOf("/api/v2/tracks/t1/image", "/api/v2/tracks/t2/image"), named.map { it.url.encodedPath })
            assertTrue(named.all { it.text() == """{"upload_id":"up1"}""" })
            assertEquals("one upload for the whole album", 1, stub.requests.count { it.url.encodedPath == "/api/v2/uploads" })
            assertEquals("https://img/album", db.catalogue().album(SERVER, "al")?.album?.image)
        }
    }

    @Test fun `an artist's picture goes by its id, a playlist's cover by the playlist`() = runBlocking<Unit> {
        lateinit var storage: String
        val (stub, src) = stub {
            when ("${it.method} ${it.url.encodedPath}") {
                "POST /api/v2/uploads" -> json("""{"upload_id":"up2","url":"$storage","method":"PUT","headers":{"Content-Type":"image/png"},"expires_at":"2026-12-01T10:00:00Z"}""", 201)
                "PUT /storage/key" -> MockResponse.Builder().code(200).build()
                "PUT /api/v2/contributors/band/image" -> json(contributorJson("band", "The Band", image = "https://img/band"))
                "PUT /api/v2/playlists/p1/image" -> json(playlistJson("p1", "Mine"))
                else -> null
            }
        }
        storage = stub.server.url("/storage/key").toString()
        stub.use {
            src.setPicture(Artist("$SERVER|band", "The Band"), byteArrayOf(1, 2, 3), "image/png")
            src.setCover("$SERVER|p1", byteArrayOf(1, 2, 3), "image/png")
            assertTrue(stub.requests.first().text().contains("\"filename\":\"cover.png\""))
            assertTrue("PUT /api/v2/contributors/band/image" in stub.paths())
            assertTrue("PUT /api/v2/playlists/p1/image" in stub.paths())
            assertEquals(listOf("Mine"), src.playlists().map { it.name })
        }
    }
}
