package audio.own.music.library

import androidx.room.withTransaction
import audio.own.kit.ownaudio.SnapshotLine
import audio.own.music.library.db.CatalogueDatabase
import audio.own.music.library.ownaudio.CatalogueWriter
import audio.own.music.library.ownaudio.FeedItem
import audio.own.music.library.ownaudio.OwnAudioCatalogueSync
import kotlin.system.measureNanoTime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * How the catalogue holds up with a big library: 100k generated tracks written the way a
 * snapshot writes them (NDJSON lines parsed by the kit, 500 per transaction), then the browsing
 * queries timed. The numbers are printed, not asserted: this runs on a shared JVM with
 * Robolectric's SQLite, so they are a guide. The plan's 600k target is measured on a device.
 */
@RunWith(RobolectricTestRunner::class)
class CataloguePerfTest {
    private lateinit var db: CatalogueDatabase

    @Before fun open() {
        db = memoryDatabase()
    }

    @After fun close() = db.close()

    @Test fun `100k tracks - insert, then album pages and the other browsing queries`() = runBlocking {
        val tracks = 100_000
        val perAlbum = 10
        val albums = tracks / perAlbum
        val artists = 2_000
        val genres = 50
        val dao = db.catalogue()

        val lines = sequence {
            for (a in 0 until artists) yield(record("contributor", "c$a", contributorJson("c$a", "Artist ${name(a)}")))
            for (a in 0 until albums) {
                val artist = a % artists
                yield(
                    record(
                        "album", "al$a",
                        albumJson(
                            "al$a", "Album ${name(a)}", credits = """[{"contributor_id":"c$artist","role":"artist","position":0,"join_phrase":""}]""",
                            releaseDate = "${1960 + a % 60}-01-01", image = "https://h/api/v2/images/o$a?e=9999999999&s=x",
                            createdAt = "2026-%02d-%02dT00:00:00Z".format(1 + a % 12, 1 + a % 28), trackCount = perAlbum,
                        ),
                    ),
                )
            }
            for (g in 0 until genres) yield(record("genre", "g$g", """{"id":"g$g","name":"Genre $g"}"""))
            for (t in 0 until tracks) {
                val album = t / perAlbum
                yield(
                    record(
                        "track", "t$t",
                        trackJson(
                            "t$t", "Track ${name(t)}", albumId = "al$album", number = 1 + t % perAlbum,
                            credits = """[{"contributor_id":"c${album % artists}","role":"artist","position":0,"join_phrase":""}]""",
                            genres = """["g${t % genres}"]""",
                        ),
                    ),
                )
            }
        }

        val writer = CatalogueWriter(dao, SERVER, 1, changes = false)
        val batch = ArrayList<FeedItem>(OwnAudioCatalogueSync.BATCH)
        suspend fun flush() {
            val items = batch.toList()
            batch.clear()
            db.withTransaction { writer.apply(items) }
        }
        val insertNs = measureNanoTime {
            for (text in lines) {
                val line = SnapshotLine.parse(text) as SnapshotLine.Record
                batch += FeedItem(line.kind, line.id, line.record)
                if (batch.size >= OwnAudioCatalogueSync.BATCH) flush()
            }
            flush()
        }
        assertEquals(tracks, dao.trackCount(SERVER))
        assertEquals(albums, dao.albumCount(SERVER))

        val results = linkedMapOf<String, Double>()
        suspend fun time(name: String, runs: Int = 5, query: suspend () -> Any) {
            query() // warm the statement cache and SQLite's pages
            val ns = (0 until runs).map { measureNanoTime { query() } }.sorted()
            results[name] = ns[ns.size / 2] / 1e6
        }
        time("albums by name, first page of 50") { dao.albumsByName(SERVER, 50, 0) }
        time("albums by name, page at offset 5000") { dao.albumsByName(SERVER, 50, 5_000) }
        time("albums by artist, first page") { dao.albumsByArtistName(SERVER, 50, 0) }
        time("albums newest, first page") { dao.albumsNewest(SERVER, 50, 0) }
        time("albums shuffled, first page") { dao.albumsShuffled(SERVER, 7, 50, 0) }
        time("one album's tracks") { dao.tracksOfAlbum(SERVER, "al4321") }
        time("songs by title, first page of 50") { dao.tracksByTitle(SERVER, 50, 0) }
        time("artists, first page of 50") { dao.artists(SERVER, 50, 0) }
        time("albums of one artist") { dao.albumsOfArtist(SERVER, "c17") }
        time("search tracks (LIKE, no match: full scan)", runs = 3) { dao.searchTracks(SERVER, "%track 12%", 20) }
        time("genres with counts", runs = 1) { dao.genres(SERVER) }

        val report = buildString {
            appendLine("Catalogue with $tracks tracks, $albums albums, $artists artists (Robolectric SQLite on the JVM):")
            appendLine("  insert: %.1f s (%.0f records/s)".format(insertNs / 1e9, (artists + albums + genres + tracks) / (insertNs / 1e9)))
            for ((name, ms) in results) appendLine("  %-40s %8.2f ms".format(name, ms))
        }
        println(report)
        assertEquals(50, dao.albumsByName(SERVER, 50, 0).size)
        assertEquals(perAlbum, dao.tracksOfAlbum(SERVER, "al4321").size)
    }

    /** Names that don't sort in insertion order, so the indexes do the work. */
    private fun name(i: Int): String = Integer.toHexString(i * 2_654_435_761L.toInt()).padStart(8, '0') + " $i"
}
