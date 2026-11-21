package audio.own.music.library

import audio.own.kit.subsonic.SubsonicError
import audio.own.music.library.db.CatalogueDatabase
import audio.own.music.library.model.Gain
import audio.own.music.library.model.PlaylistEntry
import audio.own.music.library.subsonic.SubsonicMapper
import audio.own.music.library.subsonic.SubsonicSource
import java.time.Instant
import kotlinx.coroutines.runBlocking
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
import audio.own.kit.subsonic.Album as KitAlbum
import audio.own.kit.subsonic.ReplayGain as KitReplayGain
import audio.own.kit.subsonic.Song as KitSong

/** The Subsonic source against a stubbed server: the mapping (ported from MappingTests.swift), the requests, the fallback. */
@RunWith(RobolectricTestRunner::class)
class SubsonicSourceTest {
    private lateinit var db: CatalogueDatabase
    private val stubs = mutableListOf<KitStub>()

    @Before fun open() {
        db = memoryDatabase()
    }

    @After fun close() {
        stubs.forEach { it.close() }
        db.close()
    }

    private fun stub(answers: Map<String, (RecordedRequest) -> MockResponse>) = KitStub(subsonicServer(answers)).also { stubs += it }

    private fun source(stub: KitStub) = SubsonicSource(SERVER, stub.subsonic(), db)

    private val composite = SubsonicMapper { "$SERVER|$it" }

    private suspend inline fun <reified E : SourceError> assertThrows(crossinline block: suspend () -> Unit) {
        try {
            block()
            fail("expected ${E::class.simpleName}")
        } catch (e: SourceError) {
            assertTrue("got $e", e is E)
        }
    }

    // Mapping, as MappingTests.swift

    @Test fun `a star is a favorite, a song without a cover takes the album's, ReplayGain carries over`() {
        val song = composite.song(
            KitSong(id = "s1", title = "One", albumId = "al1", starred = "2026-01-01T00:00:00Z", userRating = 4, bitRate = 320, replayGain = KitReplayGain(trackGain = -6.5, trackPeak = 0.9)),
        )
        assertEquals("$SERVER|s1", song.id)
        assertTrue(song.isFavorite)
        assertEquals(4, song.rating)
        assertEquals("$SERVER|al1", song.coverArt)
        assertEquals(Gain(track = -6.5, trackPeak = 0.9), song.gain)
        assertNull(song.playlistEntryId)
        // Subsonic counts kilobits; the models bits.
        assertEquals(320_000, song.bitRate)
    }

    @Test fun `an empty ReplayGain is no gain, an album without a cover id uses its own id`() {
        val song = composite.song(KitSong(id = "s1", title = "One", replayGain = KitReplayGain()))
        assertNull(song.gain)
        assertFalse(song.isFavorite)
        val album = composite.album(KitAlbum(id = "al1", name = "A", songCount = 3, duration = 61))
        assertEquals("$SERVER|al1", album.coverArt)
        assertEquals(61_000L, album.durationMs)
    }

    @Test fun `errors become the shared ones`() {
        assertTrue(SubsonicSource.sourceError(SubsonicError.WrongCredentials) is SourceError.SignInRejected)
        assertEquals("Not here", (SubsonicSource.sourceError(SubsonicError.Server(10, "Not here")) as SourceError.Server).text)
        // 70 is Subsonic's "not found" (the iOS app showed it as a server message).
        assertTrue(SubsonicSource.sourceError(SubsonicError.Server(70, "Not found")) is SourceError.NotFound)
        assertTrue(SubsonicSource.sourceError(SubsonicError.NotAuthorized) is SourceError.NotAllowed)
        assertTrue(SubsonicSource.sourceError(SubsonicError.Offline) is SourceError.Offline)
        val token = SubsonicSource.sourceError(SubsonicError.TokenAuthUnsupported)
        assertEquals("This server doesn't accept Subsonic sign-in with a token", (token as SourceError.Server).text)
    }

    @Test fun `ratings and lower bitrates, every album order, no instant mix`() = runBlocking {
        val source = source(stub(emptyMap()))
        assertEquals(setOf(Capability.RATINGS, Capability.LOWER_BITRATE), source.capabilities)
        assertTrue(AlbumSort.HIGHEST_RATED in source.albumSorts)
        assertEquals(AlbumSort.entries.size, source.albumSorts.size)
        assertThrows<SourceError.Unsupported> { source.instantMix("$SERVER|s1") }
        assertTrue(source.musicLibraries().isEmpty())
    }

    // Requests

    @Test fun `each album order asks for its list type`() = runBlocking {
        val stub = stub(mapOf("getAlbumList2" to { _ -> subsonicOk(""""albumList2":{"album":[${subsonicAlbum("al1", "First")}]}""") }))
        val source = source(stub)
        val expected = mapOf(
            AlbumSort.NAME to "alphabeticalByName", AlbumSort.ARTIST to "alphabeticalByArtist", AlbumSort.NEWEST to "newest",
            AlbumSort.RECENTLY_PLAYED to "recent", AlbumSort.MOST_PLAYED to "frequent", AlbumSort.RANDOM to "random",
            AlbumSort.FAVORITES to "starred", AlbumSort.HIGHEST_RATED to "highest",
        )
        for ((sort, type) in expected) {
            val albums = source.albums(sort, limit = 30, offset = 60)
            assertEquals("$SERVER|al1", albums.single().id)
            val url = stub.last("getAlbumList2").url
            assertEquals(type, url.queryParameter("type"))
            assertEquals("30", url.queryParameter("size"))
            assertEquals("60", url.queryParameter("offset"))
        }
    }

    @Test fun `a lower bitrate asks for an MP3 at that rate, the original asks for nothing, links never run out`() = runBlocking {
        val source = source(stub(emptyMap()))
        val saver = source.playbackLink("$SERVER|s1", maxBitrateKbps = 192)
        val url = saver.url.toHttpUrl()
        assertEquals("/rest/stream.view", url.encodedPath)
        assertEquals("s1", url.queryParameter("id"))
        assertEquals("192", url.queryParameter("maxBitRate"))
        assertEquals("mp3", url.queryParameter("format"))
        assertNull(saver.expiresAt)

        val original = source.playbackLink("$SERVER|s1").url.toHttpUrl()
        assertNull(original.queryParameter("maxBitRate"))
        assertNull(original.queryParameter("format"))
        assertNull(source.downloadLink("$SERVER|s1").url.toHttpUrl().queryParameter("format"))
        assertEquals("al1", source.coverLink("$SERVER|al1", 300).toHttpUrl().queryParameter("id"))
    }

    @Test fun `now playing is a scrobble that counts nothing, a play is a submission with its start time`() = runBlocking {
        val stub = stub(mapOf("scrobble" to { _ -> subsonicOk() }))
        val source = source(stub)
        source.reportNowPlaying("$SERVER|s1", 0)
        assertEquals("false", stub.last("scrobble").url.queryParameter("submission"))
        // Progress and stop have nothing to go to.
        source.reportProgress("$SERVER|s1", 15_000, isPaused = false)
        source.reportStopped("$SERVER|s1", 20_000)
        assertEquals(1, stub.requests.size)

        val at = Instant.parse("2026-11-20T10:00:00Z")
        source.reportPlayed("$SERVER|s1", at)
        val played = stub.last("scrobble").url
        assertEquals("true", played.queryParameter("submission"))
        assertEquals(at.toEpochMilli().toString(), played.queryParameter("time"))
        assertEquals("s1", played.queryParameter("id"))
    }

    @Test fun `stars go by kind, ratings straight, playlist entries by position`() = runBlocking {
        val stub = stub(mapOf("star" to { _ -> subsonicOk() }, "unstar" to { _ -> subsonicOk() }, "setRating" to { _ -> subsonicOk() }, "updatePlaylist" to { _ -> subsonicOk() }))
        val source = source(stub)
        source.setFavorite(FavoriteKind.ALBUM, "$SERVER|al1", true)
        assertEquals("al1", stub.last("star").url.queryParameter("albumId"))
        source.setFavorite(FavoriteKind.ARTIST, "$SERVER|ar1", false)
        assertEquals("ar1", stub.last("unstar").url.queryParameter("artistId"))
        source.setFavorite(FavoriteKind.SONG, "$SERVER|s1", true)
        assertEquals("s1", stub.last("star").url.queryParameter("id"))
        source.setRating("$SERVER|s1", 4)
        assertEquals("4", stub.last("setRating").url.queryParameter("rating"))

        source.removeFromPlaylist("$SERVER|p1", listOf(PlaylistEntry(0, null), PlaylistEntry(2, null)))
        val removed = stub.last("updatePlaylist").url
        assertEquals("p1", removed.queryParameter("playlistId"))
        assertEquals(listOf("0", "2"), removed.queryParameterValues("songIndexToRemove"))
        source.addToPlaylist("$SERVER|p1", listOf("$SERVER|s1", "$SERVER|s2"))
        assertEquals(listOf("s1", "s2"), stub.last("updatePlaylist").url.queryParameterValues("songIdToAdd"))
    }

    @Test fun `lyrics through songLyrics where the server has it, asked once`() = runBlocking {
        val stub = stub(
            mapOf(
                "getOpenSubsonicExtensions" to { _ -> subsonicOk(""""openSubsonicExtensions":[{"name":"songLyrics","versions":[1]}]""") },
                "getLyricsBySongId" to { _ ->
                    subsonicOk(""""lyricsList":{"structuredLyrics":[{"lang":"eng","synced":true,"offset":-100,"line":[{"start":0,"value":"Hello"},{"start":2000,"value":"World"}]}]}""")
                },
            ),
        )
        val source = source(stub)
        val song = composite.song(KitSong(id = "s1", title = "One", artist = "Band"))
        val lyrics = source.lyrics(song).single()
        assertTrue(lyrics.isSynced)
        assertEquals("eng", lyrics.language)
        assertEquals(-100L, lyrics.offsetMs)
        assertEquals(2000L, lyrics.lines[1].startMs)
        source.lyrics(song)
        assertEquals(1, stub.actions().count { it == "getOpenSubsonicExtensions" })
        assertEquals("s1", stub.last("getLyricsBySongId").url.queryParameter("id"))
    }

    @Test fun `without the extension, classic lyrics by artist and title`() = runBlocking {
        val stub = stub(mapOf("getLyrics" to { _ -> subsonicOk(""""lyrics":{"value":"One line\nTwo"}""") }))
        val lyrics = source(stub).lyrics(composite.song(KitSong(id = "s1", title = "One", artist = "Band"))).single()
        assertFalse(lyrics.isSynced)
        assertEquals(listOf("One line", "Two"), lyrics.lines.map { it.text })
        assertEquals("Band", stub.last("getLyrics").url.queryParameter("artist"))
    }

    @Test fun `an id of another server is not found`() = runBlocking {
        val source = source(stub(emptyMap()))
        assertThrows<SourceError.NotFound> { source.song("other|s1") }
    }

    // Falling back to the catalogue

    @Test fun `unreachable before any sync is a network error, after one the catalogue answers`() = runBlocking {
        val stub = stub(libraryAnswers())
        val source = source(stub)
        stub.networkUp = false
        assertThrows<SourceError.Network> { source.albums(AlbumSort.NAME, 50) }

        stub.networkUp = true
        assertEquals(2, source.syncCatalogue()!!.songs)
        stub.networkUp = false
        assertEquals(listOf("First", "Second"), source.albums(AlbumSort.NAME, 50).map { it.name })
        val album = source.album("$SERVER|al1")
        assertEquals("$SERVER|s1", album.songs.first().id)
        assertEquals(Gain(track = -3.0, album = -2.0), album.songs.first().gain)
        assertEquals("flac", album.songs.first().suffix)
        assertEquals(900_000, album.songs.first().bitRate)
        assertEquals(listOf("Band"), source.artists().map { it.name })
        assertEquals(listOf("$SERVER|al1"), source.favorites().albums.map { it.id })
        assertEquals(listOf("Rock"), source.genres().map { it.name })
        assertEquals(1, source.albums(source.genres().single(), 10).size)
        assertEquals(listOf("One"), source.search("one").songs.map { it.title })
        // Plays are only on the server.
        assertThrows<SourceError.Offline> { source.albums(AlbumSort.MOST_PLAYED, 50) }
    }

    @Test fun `a playlist opened online opens offline, its entries by position`() = runBlocking {
        val stub = stub(libraryAnswers())
        val source = source(stub)
        source.syncCatalogue()
        val online = source.playlist("$SERVER|p1")
        assertEquals(2, online.songs.size)
        stub.networkUp = false
        val offline = source.playlist("$SERVER|p1")
        assertEquals("Mix", offline.playlist.name)
        assertEquals(listOf("$SERVER|s2", "$SERVER|s1"), offline.songs.map { it.id })
        assertTrue(offline.songs.all { it.playlistEntryId == null })
    }

    @Test fun `a star made here shows in the catalogue before the next sync`() = runBlocking {
        val answers = libraryAnswers() + mapOf("star" to { _: RecordedRequest -> subsonicOk() })
        val stub = stub(answers)
        val source = source(stub)
        source.syncCatalogue()
        source.setFavorite(FavoriteKind.SONG, "$SERVER|s2", true)
        stub.networkUp = false
        assertEquals(listOf("$SERVER|s2"), source.favorites().songs.map { it.id })
    }

    companion object {
        fun libraryAnswers(): Map<String, (RecordedRequest) -> MockResponse> = mapOf(
            "getArtists" to { _ -> subsonicOk(""""artists":{"index":[{"name":"B","artist":[{"id":"ar1","name":"Band","albumCount":2}]}]}""") },
            "getGenres" to { _ -> subsonicOk(""""genres":{"genre":[{"value":"Rock","songCount":2,"albumCount":1}]}""") },
            "getAlbumList2" to { r ->
                val offset = r.url.queryParameter("offset")?.toInt() ?: 0
                val albums = if (offset > 0) "" else subsonicAlbum("al1", "First", extra = ""","starred":"2026-01-01T00:00:00Z"""") + "," + subsonicAlbum("al2", "Second")
                subsonicOk(""""albumList2":{"album":[$albums]}""")
            },
            "search3" to { r ->
                val offset = r.url.queryParameter("songOffset")?.toInt() ?: 0
                val songs = if (offset > 0) "" else subsonicSong("s1", "One", extra = ""","replayGain":{"trackGain":-3.0,"albumGain":-2.0}""") + "," + subsonicSong("s2", "Two")
                subsonicOk(""""searchResult3":{"song":[$songs]}""")
            },
            "getPlaylists" to { _ -> subsonicOk(""""playlists":{"playlist":[{"id":"p1","name":"Mix","owner":"anna","songCount":2,"duration":400}]}""") },
            "getPlaylist" to { _ ->
                subsonicOk(""""playlist":{"id":"p1","name":"Mix","owner":"anna","songCount":2,"entry":[${subsonicSong("s2", "Two")},${subsonicSong("s1", "One")}]}""")
            },
        )
    }
}
