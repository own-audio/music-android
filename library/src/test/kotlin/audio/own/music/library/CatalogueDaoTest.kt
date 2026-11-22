package audio.own.music.library

import androidx.room.withTransaction
import audio.own.music.library.db.CatalogueDatabase
import audio.own.music.library.db.FavouriteRow
import audio.own.music.library.db.RatingRow
import audio.own.music.library.ownaudio.CatalogueWriter
import audio.own.music.library.ownaudio.FeedItem
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The browsing queries against a real SQLite (Robolectric), on a small written library. */
@RunWith(RobolectricTestRunner::class)
class CatalogueDaoTest {
    private lateinit var db: CatalogueDatabase
    private val dao get() = db.catalogue()

    @Before fun open() {
        db = memoryDatabase()
    }

    @After fun close() = db.close()

    private fun obj(text: String) = Json.parseToJsonElement(text) as JsonObject

    private suspend fun write(server: String = SERVER, gen: Long = 1, vararg items: Pair<String, String>) {
        val writer = CatalogueWriter(dao, server, gen, changes = false)
        db.withTransaction {
            writer.apply(items.map { (kind, json) -> FeedItem(kind, (obj(json)["id"] as kotlinx.serialization.json.JsonPrimitive).content, obj(json)) })
        }
    }

    private suspend fun library() = write(
        items = arrayOf(
            "contributor" to contributorJson("abba", "ABBA"),
            "contributor" to contributorJson("beatles", "the Beatles"),
            "contributor" to contributorJson("guest", "Guest"),
            "album" to albumJson("arrival", "Arrival", credits = credits("abba"), releaseDate = "1976", createdAt = "2026-01-01T00:00:00Z"),
            "album" to albumJson("abbey", "abbey Road", credits = credits("beatles"), releaseDate = "1969", createdAt = "2026-03-01T00:00:00Z"),
            "album" to albumJson("help", "Help!", credits = credits("beatles"), releaseDate = "1965", createdAt = "2026-02-01T00:00:00Z"),
            "genre" to """{"id":"pop","name":"Pop"}""",
            "genre" to """{"id":"rock","name":"rock"}""",
            "track" to trackJson("t3", "Come Together", albumId = "abbey", credits = credits("beatles"), genres = """["rock"]""", disc = 1, number = 1),
            "track" to trackJson("t5", "Something", albumId = "abbey", credits = credits("beatles"), genres = """["rock","pop"]""", disc = 1, number = 2),
            "track" to trackJson("t6", "Bonus", albumId = "abbey", credits = credits("beatles"), disc = 2, number = 1),
            "track" to trackJson("t7", "Untitled", albumId = "abbey", credits = credits("beatles"), disc = null, number = null),
            "track" to trackJson("t1", "Dancing Queen", albumId = "arrival", credits = credits("abba"), genres = """["pop"]"""),
            "track" to trackJson(
                "t2", "Help!", albumId = "help",
                credits = """[{"contributor_id":"beatles","role":"artist","position":0,"join_phrase":" with "},{"contributor_id":"guest","role":"featured","position":1,"join_phrase":""}]""",
                genres = """["rock"]""",
            ),
        ),
    )

    private fun credits(id: String) = """[{"contributor_id":"$id","role":"artist","position":0,"join_phrase":""}]"""

    @Test fun `albums by name ignore case, by artist then year, newest first, and page`() = runBlocking {
        library()
        assertEquals(listOf("abbey Road", "Arrival", "Help!"), dao.albumsByName(SERVER, 10, 0).map { it.album.title })
        assertEquals(listOf("Arrival"), dao.albumsByName(SERVER, 1, 1).map { it.album.title })
        assertEquals(listOf("Arrival", "Help!", "abbey Road"), dao.albumsByArtistName(SERVER, 10, 0).map { it.album.title })
        assertEquals(listOf("abbey Road", "Help!", "Arrival"), dao.albumsNewest(SERVER, 10, 0).map { it.album.title })
    }

    @Test fun `by artist, albums with no artist come last, also across a page's edge`() = runBlocking {
        library()
        write(gen = 1, items = arrayOf("album" to albumJson("loose", "Loose Ends", credits = "[]", releaseDate = null)))
        val whole = dao.albumsByArtistName(SERVER, 10, 0).map { it.album.title }
        assertEquals(listOf("Arrival", "Help!", "abbey Road", "Loose Ends"), whole)
        assertEquals(whole, (0 until 4).flatMap { dao.albumsByArtistName(SERVER, 1, it) }.map { it.album.title })
        assertEquals(listOf("abbey Road", "Loose Ends"), dao.albumsByArtistName(SERVER, 2, 2).map { it.album.title })
        assertEquals(listOf("Loose Ends"), dao.albumsByArtistName(SERVER, 5, 3).map { it.album.title })
        assertEquals(emptyList<String>(), dao.albumsByArtistName(SERVER, 5, 4).map { it.album.title })
    }

    @Test fun `songs by title page without gaps or repeats`() = runBlocking {
        library()
        val whole = dao.tracksByTitle(SERVER, 50, 0).map { it.track.id }
        assertEquals(6, whole.size)
        assertEquals(whole, (0 until 6).flatMap { dao.tracksByTitle(SERVER, 1, it) }.map { it.track.id })
    }

    @Test fun `a shuffle stays the same across pages for one seed`() = runBlocking {
        library()
        val whole = dao.albumsShuffled(SERVER, 42, 10, 0).map { it.album.id }
        val paged = (0 until 3).flatMap { dao.albumsShuffled(SERVER, 42, 1, it) }.map { it.album.id }
        assertEquals(whole, paged)
        assertEquals(3, whole.toSet().size)
    }

    @Test fun `an album's tracks in disc and track order, unnumbered last`() = runBlocking {
        library()
        assertEquals(listOf("Come Together", "Something", "Bonus", "Untitled"), dao.tracksOfAlbum(SERVER, "abbey").map { it.track.title })
        val track = dao.track(SERVER, "t2")!!.track
        assertEquals("Help!", track.albumTitle)
        assertEquals("the Beatles with Guest", track.artistLine)
        assertEquals("rock", track.genreName)
    }

    @Test fun `artists with their album counts, a featured guest only is not one`() = runBlocking {
        library()
        val artists = dao.artists(SERVER)
        assertEquals(listOf("ABBA", "the Beatles"), artists.map { it.artist.name })
        assertEquals(listOf(1, 2), artists.map { it.albumCount })
        assertEquals(listOf("Help!", "abbey Road"), dao.albumsOfArtist(SERVER, "beatles").map { it.album.title })
    }

    // Found on a real library: over half of its artists had songs only on compilations, and
    // their pages were empty, with Play and Shuffle off and "0 albums" in the list.
    @Test fun `an artist with songs only on someone else's album has that album`() = runBlocking {
        library()
        write(
            gen = 1,
            items = arrayOf(
                "contributor" to contributorJson("various", "Various Artists"),
                "contributor" to contributorJson("maniacs", "10,000 Maniacs"),
                "album" to albumJson("hits", "Hits", credits = credits("various"), releaseDate = "1995"),
                "track" to trackJson("h1", "Because the Night", albumId = "hits", credits = credits("maniacs")),
                "track" to trackJson("h2", "Dancing Queen (Live)", albumId = "hits", credits = credits("abba")),
            ),
        )
        val counts = dao.artists(SERVER).associate { it.artist.name to it.albumCount }
        assertEquals(1, counts["10,000 Maniacs"])
        assertEquals(2, counts["ABBA"])
        assertEquals(1, counts["Various Artists"])
        assertEquals(listOf("Hits"), dao.albumsOfArtist(SERVER, "maniacs").map { it.album.title })
        assertEquals(listOf("Arrival", "Hits"), dao.albumsOfArtist(SERVER, "abba").map { it.album.title })
        // A featured guest only still has no albums of their own.
        assertEquals(emptyList<String>(), dao.albumsOfArtist(SERVER, "guest").map { it.album.title })
    }

    @Test fun `genres with counts, and a genre's albums through any of a track's genres`() = runBlocking {
        library()
        val genres = dao.genres(SERVER).associateBy { it.name }
        assertEquals(2, genres.getValue("Pop").songCount)
        assertEquals(2, genres.getValue("Pop").albumCount)
        assertEquals(3, genres.getValue("rock").songCount)
        assertEquals(listOf("abbey Road", "Arrival"), dao.albumsOfGenre(SERVER, "pop", 10).map { it.album.title })
        assertEquals("pop", dao.genreIdNamed(SERVER, "POP"))
    }

    @Test fun `search by title, artist or album, ignoring case`() = runBlocking {
        library()
        assertEquals(listOf("Dancing Queen"), dao.searchTracks(SERVER, "%queen%", 10).map { it.track.title })
        assertEquals(5, dao.searchTracks(SERVER, "%BEATLES%", 10).size)
        assertEquals(4, dao.searchTracks(SERVER, "%abbey%", 10).size)
        assertEquals(listOf("abbey Road", "Help!"), dao.searchAlbums(SERVER, "%beatles%", 10).map { it.album.title })
        assertEquals(listOf("ABBA"), dao.searchArtists(SERVER, "%ab%", 10).map { it.artist.name })
        assertTrue("an underscore is a character, not a wildcard", dao.searchTracks(SERVER, "%\\_%", 10).isEmpty())
    }

    @Test fun `favourites newest first and ratings highest first, joined onto the rows`() = runBlocking {
        library()
        dao.upsertFavourites(listOf(FavouriteRow(SERVER, "album", "help", 1, 1), FavouriteRow(SERVER, "album", "arrival", 2, 1), FavouriteRow(SERVER, "track", "t1", 3, 1)))
        dao.upsertRatings(listOf(RatingRow(SERVER, "album", "abbey", 5, 1, 1), RatingRow(SERVER, "album", "help", 3, 1, 1), RatingRow(SERVER, "track", "t1", 4, 1, 1)))
        assertEquals(listOf("Arrival", "Help!"), dao.albumsFavourite(SERVER, 10, 0).map { it.album.title })
        assertEquals(listOf("abbey Road", "Help!"), dao.albumsHighestRated(SERVER, 10, 0).map { it.album.title })
        val help = dao.album(SERVER, "help")!!
        assertTrue(help.isFavorite)
        assertEquals(3, help.rating)
        val queen = dao.track(SERVER, "t1")!!
        assertTrue(queen.isFavorite)
        assertEquals(4, queen.rating)
        assertEquals(listOf("t1"), dao.favouriteTracks(SERVER).map { it.track.id })
        assertNull(dao.album(SERVER, "arrival")!!.rating)
    }

    @Test fun `servers don't mix, and an older generation is pruned`() = runBlocking {
        library()
        write("other", 1, "contributor" to contributorJson("abba", "ABBA"), "album" to albumJson("arrival", "Arrival (other server)", credits = credits("abba")))
        assertEquals(listOf("Arrival (other server)"), dao.albumsByName("other", 10, 0).map { it.album.title })
        assertEquals(3, dao.albumCount(SERVER))
        write(SERVER, 2, "contributor" to contributorJson("abba", "ABBA"), "album" to albumJson("arrival", "Arrival", credits = credits("abba")))
        dao.pruneAlbums(SERVER, 2)
        dao.pruneTracks(SERVER, 2)
        assertEquals(listOf("Arrival"), dao.albumsByName(SERVER, 10, 0).map { it.album.title })
        assertEquals(0, dao.trackCount(SERVER))
        assertEquals(1, dao.albumCount("other"))
    }

    @Test fun `what an id is, and its picture`() = runBlocking {
        library()
        assertEquals("album", dao.kindOf(SERVER, "abbey"))
        assertEquals("track", dao.kindOf(SERVER, "t1"))
        assertEquals("contributor", dao.kindOf(SERVER, "abba"))
        assertNull(dao.kindOf(SERVER, "nothing"))
        dao.setAlbumImage(SERVER, "abbey", "https://h/api/v2/images/x")
        assertEquals("https://h/api/v2/images/x", dao.imageOf(SERVER, "abbey"))
        assertEquals("a track without a picture shows its album's", "https://h/api/v2/images/x", dao.track(SERVER, "t3")!!.albumImage)
    }
}
