package audio.own.music.library

import audio.own.kit.ownaudio.OwnAudioJson
import audio.own.music.library.db.CatalogueDatabase
import audio.own.music.library.ownaudio.OwnAudioSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

/** M5: smart playlists through [SmartPlaylistSource]; the rule's JSON is checked against music-ios's. */
@RunWith(RobolectricTestRunner::class)
class SmartPlaylistsTest {
    private lateinit var db: CatalogueDatabase

    @Before fun open() {
        db = memoryDatabase()
    }

    @After fun close() = db.close()

    private fun stub(signedIn: Boolean = true, route: (RecordedRequest) -> MockResponse?): Pair<StubServer, OwnAudioSource> {
        val stub = StubServer { request ->
            route(request) ?: when (request.url.encodedPath) {
                "/api/v2/me" -> json(ME)
                else -> notFound()
            }
        }
        return stub to OwnAudioSource(SERVER, "me@example.com", stub.client(signedIn = signedIn), db)
    }

    /** The server's own rule, as music-ios OwnAudioClientTests.smartRuleShape has it. */
    private val serverRule =
        """{"arc":"none","filters":{"not_played_days":365,"starred":true},"limit":{"kind":"count","n":50},"sequence":{"max_per_artist":2,"no_consecutive_artist":true},"sort":"weighted-random"}"""

    private fun canonical(text: String) = OwnAudioJson.parseToJsonElement(text)

    @Test fun `a rule round-trips to the same JSON as iOS sends`() {
        val rule = OwnAudioJson.decodeFromString(SmartRule.serializer(), serverRule)
        assertEquals(365, rule.filters.notPlayedDays)
        assertEquals(true, rule.filters.starred)
        assertEquals(audio.own.kit.ownaudio.SmartRule.Limit.Count(50), rule.limit)
        assertEquals(canonical(serverRule), canonical(OwnAudioJson.encodeToString(SmartRule.serializer(), rule)))
        assertEquals("a rule with nothing set is the defaults", SmartRule(), OwnAudioJson.decodeFromString(SmartRule.serializer(), "{}"))

        // iOS's other case: minutes, an arc, a sort, a genre; nothing unset is sent.
        val ios = SmartRule(
            filters = audio.own.kit.ownaudio.SmartRule.Filters(playedByOthersNotMe = true, genres = audio.own.kit.ownaudio.SmartRule.Names(include = listOf("jazz"))),
            limit = audio.own.kit.ownaudio.SmartRule.Limit.Minutes(90),
            arc = audio.own.kit.ownaudio.SmartRule.Arc.WARMUP_SUSTAIN_COOLDOWN,
            sort = audio.own.kit.ownaudio.SmartRule.Sort.LEAST_RECENTLY_PLAYED,
        )
        val sent = OwnAudioJson.encodeToString(SmartRule.serializer(), ios)
        assertEquals(
            canonical(
                """{"filters":{"genres":{"include":["jazz"],"exclude":[]},"played_by_others_not_me":true},"limit":{"kind":"duration","minutes":90},"arc":"warmup-sustain-cooldown","sequence":{"max_per_artist":2,"no_consecutive_artist":true},"sort":"least-recently-played"}""",
            ),
            canonical(sent),
        )
        assertEquals(ios, OwnAudioJson.decodeFromString(SmartRule.serializer(), sent))
    }

    @Test fun `every recorded preset's rule round-trips unchanged`() {
        val presets = OwnAudioJson.parseToJsonElement(answer("smart_presets")) as kotlinx.serialization.json.JsonArray
        for (preset in presets) {
            val rule = preset.jsonObject["rule"] as JsonObject
            val decoded = OwnAudioJson.decodeFromJsonElement(SmartRule.serializer(), rule)
            assertEquals(preset.jsonObject["slug"]?.jsonPrimitive?.content, rule, OwnAudioJson.encodeToJsonElement(SmartRule.serializer(), decoded))
        }
    }

    @Test fun `saved ones and presets - composite ids, whose they are`() = runBlocking<Unit> {
        val owner = "fd616c92-7af6-4d94-b17b-316079c65c3a"
        val (stub, src) = stub {
            when (it.url.encodedPath) {
                "/api/v2/me" -> json(ME.replace("\"id\":\"u\"", "\"id\":\"$owner\""))
                "/api/v2/smart-playlists" -> json(answer("smart_playlists"))
                "/api/v2/smart-playlists/presets" -> json(answer("smart_presets"))
                else -> null
            }
        }
        stub.use {
            val saved = src.smartPlaylists().single()
            assertEquals("$SERVER|3112c934-c176-4524-8850-a3c1761249b6", saved.id)
            assertEquals("Jazz station", saved.name)
            assertTrue(saved.isMine)
            assertFalse(saved.isShared)
            assertFalse(saved.isFrozen)
            assertEquals(listOf("Jazz"), saved.rule.filters.genres?.include)
            val presets = src.smartPresets()
            assertEquals("forgotten-favourites", presets.first().slug)
        }
    }

    @Test fun `an unsaved rule is tried with its songs' names, and says how many fit`() = runBlocking<Unit> {
        val (stub, src) = stub {
            when (it.url.encodedPath) {
                "/api/v2/smart-playlists/resolve" -> json(
                    """{"items":[${trackJson("t1", "Song One")}],"duration_ms":200000,"candidates":340,
                    "included":{"contributors":[${contributorJson("band", "The Band")}],"albums":[${albumJson("al", "First Album")}]}}""",
                )
                else -> null
            }
        }
        stub.use {
            val rule = OwnAudioJson.decodeFromString(SmartRule.serializer(), serverRule)
            val result = src.resolve(rule)
            assertEquals(340, result.candidates)
            assertEquals(200_000L, result.durationMs)
            assertEquals("$SERVER|t1", result.songs.single().id)
            assertEquals("The Band", result.songs.single().artist)
            val sent = stub.requests.single { it.url.encodedPath.endsWith("/resolve") }
            assertEquals("POST", sent.method)
            assertEquals("albums,contributors,genres", sent.url.queryParameter("include"))
            assertEquals(canonical("""{"rule":$serverRule}"""), canonical(sent.text()))
        }
    }

    @Test fun `save, edit, resolve a saved one, freeze, delete`() = runBlocking<Unit> {
        val record = """{"id":"s1","name":"Calm","rule":$serverRule,"mode":"dynamic","visibility":"family","owner_id":"u","created_at":"2026-10-11T12:00:00Z","updated_at":"2026-10-11T12:00:00Z"}"""
        val (stub, src) = stub {
            when ("${it.method} ${it.url.encodedPath}") {
                "POST /api/v2/smart-playlists", "PATCH /api/v2/smart-playlists/s1" -> json(record)
                "POST /api/v2/smart-playlists/s1/resolve" -> json("""{"items":[],"duration_ms":0,"candidates":0}""")
                "POST /api/v2/smart-playlists/s1/freeze" -> json(playlistJson("p9", "Calm, today"))
                "DELETE /api/v2/smart-playlists/s1" -> noContent()
                else -> null
            }
        }
        stub.use {
            val rule = OwnAudioJson.decodeFromString(SmartRule.serializer(), serverRule)
            val saved = src.saveSmartPlaylist("Calm", rule, shared = true)
            assertEquals("$SERVER|s1", saved.id)
            assertTrue(saved.isShared && saved.isMine)
            val create = stub.requests.single { it.method == "POST" && it.url.encodedPath == "/api/v2/smart-playlists" }
            assertEquals(canonical("""{"name":"Calm","rule":$serverRule,"visibility":"family"}"""), canonical(create.text()))

            src.updateSmartPlaylist(saved.id, "Calm", rule, shared = false)
            val patch = stub.requests.single { it.method == "PATCH" }
            assertEquals("private", (canonical(patch.text()) as JsonObject)["visibility"]?.jsonPrimitive?.content)

            assertEquals(0, src.resolveSmartPlaylist(saved.id).candidates)

            src.freezeSmartPlaylist(saved.id, "  Calm, today ")
            assertEquals(canonical("""{"name":"Calm, today"}"""), canonical(stub.requests.single { it.url.encodedPath.endsWith("/freeze") }.text()))
            assertEquals("the frozen playlist is in Playlists at once", listOf("Calm, today"), src.playlists().map { it.name })

            src.deleteSmartPlaylist(saved.id)
            assertTrue("DELETE /api/v2/smart-playlists/s1" in stub.paths())
        }
    }

    @Test fun `signed out, there are no smart playlists`() {
        val (stub, src) = stub(signedIn = false) { null }
        stub.use {
            assertThrows(SourceError.Unsupported::class.java) { runBlocking { src.smartPlaylists() } }
            assertTrue(stub.requests.isEmpty())
        }
    }
}
