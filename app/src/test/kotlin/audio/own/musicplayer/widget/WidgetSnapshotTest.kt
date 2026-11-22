package audio.own.musicplayer.widget

import audio.own.music.library.model.Album
import audio.own.musicplayer.eventually
import audio.own.musicplayer.library.FakeMusicSource
import audio.own.musicplayer.platform.MusicLinks
import audio.own.player.music.Track
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the Recently Played widget is given, and that it is written only when it changes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetSnapshotTest {
    private val dir: File = Files.createTempDirectory("widget").toFile()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private val track = Track("s|t1", "So What", artist = "Miles Davis", album = "Kind of Blue", artworkId = "s|kob")
    private val albums = listOf(
        WidgetSnapshot.album(Album("s|kob", "Kind of Blue", artist = "Miles Davis")),
        WidgetSnapshot.album(Album("s|bt", "Blue Train", artist = "John Coltrane", coverArt = "s|bt-cover")),
        WidgetSnapshot.album(Album("s|ls", "A Love Supreme", artist = "John Coltrane")),
        WidgetSnapshot.album(Album("s|mx", "Maiden Voyage", artist = "Herbie Hancock")),
    )

    @Test
    fun `where you left off first, then the albums played last without the one playing, three at most`() {
        val picked = WidgetSnapshot.pick(track, albums, "s").map { it.entry }
        assertEquals(listOf("So What", "Blue Train", "A Love Supreme"), picked.map { it.title })
        assertEquals(WidgetSnapshot.Kind.RESUME, picked[0].kind)
        assertEquals("Miles Davis", picked[0].subtitle)
        assertEquals(MusicLinks.resume(), picked[0].link)
        assertEquals(MusicLinks.Link.Play(MusicLinks.Kind.ALBUM, "s|bt"), MusicLinks.parse(picked[1].link))
        assertEquals(WidgetSnapshot.coverName("s|bt-cover"), picked[1].cover)
    }

    @Test
    fun `nothing played yet, or another server's albums, give nothing of theirs`() {
        assertEquals(listOf("Kind of Blue", "Blue Train", "A Love Supreme"), WidgetSnapshot.pick(null, albums, "s").map { it.entry.title })
        assertEquals(emptyList<String>(), WidgetSnapshot.pick(null, albums, "other").map { it.entry.title })
    }

    @Test
    fun `the file is written only when the entries change, with its covers and without stale ones`() = runTest {
        val store = WidgetStore(dir)
        val asked = mutableListOf<String>()
        val jpeg: suspend (String) -> ByteArray? = { id -> asked += id; if (id == "s|ls") null else byteArrayOf(1, 2, 3) }
        val picked = WidgetSnapshot.pick(track, albums, "s")
        assertTrue(store.save(picked, 1, jpeg))
        assertEquals(listOf("s|kob", "s|bt-cover", "s|ls"), asked)
        val saved = store.load()!!.entries
        assertTrue(store.cover(saved[0].cover!!).exists())
        assertNull("a cover that couldn't be made is left out", saved[2].cover)
        assertFalse("the same entries again write nothing", store.save(picked, 2, jpeg))
        assertEquals("a cover on the device isn't made again", 1, asked.count { it == "s|kob" })
        assertTrue(store.save(WidgetSnapshot.pick(null, albums.drop(2), "s"), 3, jpeg))
        assertFalse("the song's cover went with its entry", store.cover(saved[0].cover!!).exists())
    }

    @Test
    fun `the updater follows the player and the server's albums, and redraws only on a change`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val playing = MutableStateFlow<Track?>(null)
        val server = MutableStateFlow<String?>(null)
        val redraws = AtomicInteger()
        val source = FakeMusicSource(albums = albums.map { Album(it.entry.id, it.entry.title, artist = it.entry.subtitle) })
        val store = WidgetStore(dir)
        val updater = WidgetUpdater(
            store = store,
            track = playing,
            activeServerId = server,
            source = { source },
            isOffline = { false },
            jpeg = { byteArrayOf(9) },
            redraw = { redraws.incrementAndGet() },
            debounceMs = 50,
        )
        try {
            updater.start(scope)
            server.value = "s"
            eventually(20_000) { store.load()?.entries?.size == 3 }
            assertTrue(source.calls.any { it.startsWith("albums RECENTLY_PLAYED ${WidgetSnapshot.ALBUMS_ASKED}") })
            playing.value = track
            eventually(20_000) { store.load()?.entries?.firstOrNull()?.kind == WidgetSnapshot.Kind.RESUME }
            val drawn = redraws.get()
            playing.value = track.copy(title = "So What (take 2)")
            Thread.sleep(300)
            assertEquals("a new title of the same song changes nothing the widget shows", drawn, redraws.get())
        } finally {
            scope.cancel()
        }
    }
}
