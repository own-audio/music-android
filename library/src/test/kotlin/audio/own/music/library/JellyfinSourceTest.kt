package audio.own.music.library

import audio.own.music.library.db.CatalogueDatabase
import audio.own.music.library.jellyfin.JellyfinMapper
import audio.own.music.library.jellyfin.JellyfinSource
import audio.own.music.library.model.Gain
import audio.own.music.library.model.PlaylistEntry
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import audio.own.kit.jellyfin.Song as KitSong

/** The Jellyfin source against a stubbed server: mapping (from MappingTests.swift), links, reports, the catalogue. */
@RunWith(RobolectricTestRunner::class)
class JellyfinSourceTest {
    private lateinit var db: CatalogueDatabase
    private val stubs = mutableListOf<KitStub>()

    @Before fun open() {
        db = memoryDatabase()
    }

    @After fun close() {
        stubs.forEach { it.close() }
        db.close()
    }

    private fun stub(answer: (RecordedRequest) -> MockResponse = { library(it) }) = KitStub(answer).also { stubs += it }

    private fun source(stub: KitStub, libraryId: String? = null) = JellyfinSource(SERVER, stub.jellyfin(libraryId), db)

    private suspend inline fun <reified E : SourceError> assertThrows(crossinline block: suspend () -> Unit) {
        try {
            block()
            fail("expected ${E::class.simpleName}")
        } catch (e: SourceError) {
            assertTrue("got $e", e is E)
        }
    }

    private fun RecordedRequest.json() = Json.parseToJsonElement(body!!.utf8()).jsonObject

    @Test fun `measured loudness becomes a track gain, the playlist entry id is kept, no rating`() {
        val song = JellyfinMapper { "$SERVER|$it" }.song(KitSong(id = "s1", title = "One", isFavorite = true, normalizationGain = -4.2, playlistItemId = "e7", duration = 200))
        assertEquals("$SERVER|s1", song.id)
        assertTrue(song.isFavorite)
        assertEquals(Gain(track = -4.2), song.gain)
        assertEquals("e7", song.playlistEntryId)
        assertNull(song.rating)
        assertEquals(200_000L, song.durationMs)
    }

    @Test fun `instant mix, lower bitrates, a library choice and plays counted from reports, no ratings`() = runBlocking {
        val source = source(stub())
        assertEquals(
            setOf(Capability.INSTANT_MIX, Capability.LOWER_BITRATE, Capability.LIBRARY_CHOICE, Capability.COUNTS_PLAYS_FROM_REPORTS),
            source.capabilities,
        )
        assertFalse(AlbumSort.HIGHEST_RATED in source.albumSorts)
        assertThrows<SourceError.Unsupported> { source.albums(AlbumSort.HIGHEST_RATED, 10) }
        assertThrows<SourceError.Unsupported> { source.setRating("$SERVER|s1", 3) }
    }

    @Test fun `the original is the static stream, a lower bitrate the universal endpoint, links never run out`() = runBlocking {
        val source = source(stub())
        val original = source.playbackLink("$SERVER|s1").also { assertNull(it.expiresAt) }.url.toHttpUrl()
        assertEquals("/Audio/s1/stream", original.encodedPath)
        assertEquals("true", original.queryParameter("static"))
        assertEquals("tok", original.queryParameter("ApiKey"))

        val saver = source.playbackLink("$SERVER|s1", maxBitrateKbps = 192).url.toHttpUrl()
        assertEquals("/Audio/s1/universal", saver.encodedPath)
        assertEquals("192000", saver.queryParameter("MaxStreamingBitrate"))
        assertEquals("mp3", saver.queryParameter("TranscodingContainer"))
        assertEquals("/Items/al1/Images/Primary", source.coverLink("$SERVER|al1", 300).toHttpUrl().encodedPath)
    }

    @Test fun `session reports go to Playing, Progress and Stopped, a late play to UserPlayedItems`() = runBlocking {
        val stub = stub { MockResponse.Builder().code(204).build() }
        val source = source(stub)
        source.reportNowPlaying("$SERVER|s1", 0)
        source.reportProgress("$SERVER|s1", 15_000, isPaused = true)
        source.reportStopped("$SERVER|s1", 31_500)
        assertEquals(listOf("/Sessions/Playing", "/Sessions/Playing/Progress", "/Sessions/Playing/Stopped"), stub.requests.map { it.url.encodedPath })
        val progress = stub.requests[1].json()
        assertEquals("s1", progress["ItemId"]!!.jsonPrimitive.content)
        assertEquals(150_000_000L, progress["PositionTicks"]!!.jsonPrimitive.long)
        assertTrue(progress["IsPaused"]!!.jsonPrimitive.boolean)
        assertEquals(315_000_000L, stub.requests[2].json()["PositionTicks"]!!.jsonPrimitive.long)

        source.reportPlayed("$SERVER|s1", Instant.parse("2026-11-20T10:00:00.500Z"))
        val played = stub.requests.last()
        assertEquals("POST", played.method)
        assertEquals("/UserPlayedItems/s1", played.url.encodedPath)
        assertEquals("2026-11-20T10:00:00Z", played.url.queryParameter("datePlayed"))
    }

    @Test fun `music libraries are the server's music views, by their own ids`() = runBlocking {
        val stub = stub {
            json("""{"Items":[{"Id":"lib1","Name":"Music","CollectionType":"music"},{"Id":"lib2","Name":"Films","CollectionType":"movies"}]}""")
        }
        val libraries = source(stub).musicLibraries()
        assertEquals(listOf("lib1" to "Music"), libraries.map { it.id to it.name })
        assertEquals("/UserViews", stub.requests.single().url.encodedPath)
    }

    @Test fun `a chosen library scopes the listings`() = runBlocking {
        val stub = stub()
        source(stub, libraryId = "lib1").artists()
        assertEquals("lib1", stub.requests.single().url.queryParameter("ParentId"))
    }

    @Test fun `instant mix asks the server for songs like the item`() = runBlocking {
        val stub = stub { json("""{"Items":[${song("s9", "Nine")}]}""") }
        val mix = source(stub).instantMix("$SERVER|al1")
        assertEquals(listOf("$SERVER|s9"), mix.map { it.id })
        assertEquals("/Items/al1/InstantMix", stub.requests.single().url.encodedPath)
    }

    @Test fun `playlist entries are removed by their own ids, entries without one not at all`() = runBlocking {
        val stub = stub { MockResponse.Builder().code(204).build() }
        val source = source(stub)
        source.removeFromPlaylist("$SERVER|p1", listOf(PlaylistEntry(0, null)))
        assertTrue(stub.requests.isEmpty())
        source.removeFromPlaylist("$SERVER|p1", listOf(PlaylistEntry(0, "e1"), PlaylistEntry(3, "e4")))
        val request = stub.requests.single()
        assertEquals("DELETE", request.method)
        assertEquals("e1,e4", request.url.queryParameter("entryIds"))
    }

    @Test fun `favourites are one call for every kind`() = runBlocking {
        val stub = stub { json("{}") }
        val source = source(stub)
        source.setFavorite(FavoriteKind.ARTIST, "$SERVER|ar1", true)
        source.setFavorite(FavoriteKind.SONG, "$SERVER|s1", false)
        assertEquals(listOf("POST /UserFavoriteItems/ar1", "DELETE /UserFavoriteItems/s1"), stub.requests.map { "${it.method} ${it.url.encodedPath}" })
    }

    @Test fun `the catalogue keeps Jellyfin's genre ids and opens offline`() = runBlocking {
        val stub = stub()
        val source = source(stub)
        val result = source.syncCatalogue()!!
        assertEquals(2, result.albums)
        assertEquals(2, result.songs)
        assertFalse(result.readAlbumByAlbum)

        stub.networkUp = false
        val genre = source.genres().single()
        assertEquals("$SERVER|g-rock", genre.id)
        assertEquals(listOf("First"), source.albums(genre, 10).map { it.name })
        val album = source.album("$SERVER|al1")
        assertEquals(Gain(track = -4.0), album.songs.single().gain)
        assertEquals("$SERVER|al1", album.songs.single().coverArt)
        assertEquals(listOf("Band"), source.artists().map { it.name })
        assertEquals(listOf("$SERVER|s2"), source.favorites().songs.map { it.id })
    }

    @Test fun `a playlist opened online keeps its entry ids offline`() = runBlocking {
        val stub = stub()
        val source = source(stub)
        source.syncCatalogue()
        source.playlist("$SERVER|p1")
        stub.networkUp = false
        val offline = source.playlist("$SERVER|p1")
        assertEquals(listOf("e1", "e2"), offline.songs.map { it.playlistEntryId })
        assertEquals("Mix", offline.playlist.name)
    }

    companion object {
        fun song(id: String, title: String, albumId: String = "al1", genre: String = "Rock", favourite: Boolean = false, extra: String = "") =
            """{"Id":"$id","Name":"$title","Type":"Audio","Album":"First","AlbumId":"$albumId","AlbumPrimaryImageTag":"t","ArtistItems":[{"Name":"Band","Id":"ar1"}],"RunTimeTicks":2000000000,"IndexNumber":1,"ParentIndexNumber":1,"Genres":["$genre"],"Container":"flac","UserData":{"IsFavorite":$favourite}$extra}"""

        fun album(id: String, name: String, extra: String = "") =
            """{"Id":"$id","Name":"$name","Type":"MusicAlbum","AlbumArtist":"Band","AlbumArtists":[{"Name":"Band","Id":"ar1"}],"ChildCount":1,"ProductionYear":2001,"ImageTags":{"Primary":"t"},"DateCreated":"2026-10-01T12:00:00.0000000Z"$extra}"""

        /** A small library: two albums, two songs (the second a favourite), a genre and a playlist. */
        fun library(r: RecordedRequest): MockResponse {
            val path = r.url.encodedPath
            val offset = r.url.queryParameter("StartIndex")?.toInt() ?: 0
            fun items(vararg items: String) = json("""{"Items":[${if (offset > 0) "" else items.joinToString(",")}]}""")
            return when {
                path == "/Artists/AlbumArtists" -> json("""{"Items":[{"Id":"ar1","Name":"Band","Type":"MusicArtist","ImageTags":{"Primary":"t"}}]}""")
                path == "/MusicGenres" -> json("""{"Items":[{"Id":"g-rock","Name":"Rock","SongCount":2}]}""")
                path == "/Playlists/p1/Items" -> json(
                    """{"Items":[${song("s1", "One", extra = ""","PlaylistItemId":"e1"""")},${song("s2", "Two", albumId = "al2", extra = ""","PlaylistItemId":"e2"""")}]}""",
                )
                path == "/Items/p1" -> json("""{"Id":"p1","Name":"Mix","Type":"Playlist","ChildCount":2}""")
                path == "/Items/al1" -> json(album("al1", "First"))
                path == "/Items" -> when (r.url.queryParameter("IncludeItemTypes")) {
                    "MusicAlbum" -> items(album("al1", "First"), album("al2", "Second"))
                    "Playlist" -> items("""{"Id":"p1","Name":"Mix","Type":"Playlist","ChildCount":2,"MediaType":"Audio"}""")
                    else -> if (r.url.queryParameter("ParentId") == "al1") {
                        json("""{"Items":[${song("s1", "One", extra = ""","NormalizationGain":-4.0""")}]}""")
                    } else {
                        items(
                            song("s1", "One", extra = ""","NormalizationGain":-4.0"""),
                            song("s2", "Two", albumId = "al2", genre = "Jazz", favourite = true),
                        )
                    }
                }
                else -> MockResponse.Builder().code(404).build()
            }
        }
    }
}
