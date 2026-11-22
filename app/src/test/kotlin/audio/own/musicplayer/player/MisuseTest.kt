package audio.own.musicplayer.player

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.RobolectricUtil
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper.run
import audio.own.core.downloads.DownloadScheduler
import audio.own.core.downloads.Downloads
import audio.own.core.downloads.DownloadsDatabase
import audio.own.music.library.MusicSource
import audio.own.music.library.PlaybackLink
import audio.own.music.library.model.Song
import audio.own.musicplayer.app.AppLog
import audio.own.musicplayer.downloads.DownloadsModel
import audio.own.musicplayer.eventually
import audio.own.musicplayer.library.FakeMusicSource
import audio.own.musicplayer.settings.PlaybackSettings
import audio.own.player.core.ProxyMemory
import audio.own.player.music.MusicMediaItems
import audio.own.player.music.MusicPlayer
import audio.own.player.music.ScrobbleTracker
import audio.own.player.music.lastQueueStore
import java.io.File
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * music-ios's `UITests/MisuseTests.swift` in spirit, on the app's own player model over the real
 * music engine (two ExoPlayer decks on a fake clock, playing test tones from files): things a
 * listener or the system isn't meant to do, done anyway. Nothing here checks a feature; each
 * checks the player is still usable after. The sign-in and search cases are covered by the
 * view-model tests against fake servers.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MisuseTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val closing = mutableListOf<() -> Unit>()
    private val files = HashMap<String, Uri>()
    private val decks = mutableListOf<ExoPlayer>()
    /** The decks' clock: it runs ahead by itself, and a test can move it on by hand. */
    private val clock = FakeClock(true)
    private val scrobbles = java.util.concurrent.CopyOnWriteArrayList<ScrobbleTracker.Event>()

    @After
    fun close() = closing.asReversed().forEach { it() }

    private val music: MusicPlayer by lazy {
        MusicPlayer(
            MusicPlayer.Config(
                newDeck = { TestExoPlayerBuilder(context).setClock(clock).build().also(decks::add) },
                items = MusicMediaItems(localFile = { files[it] }),
                lastQueue = lastQueueStore(File(context.filesDir, "last-queue-${System.nanoTime()}.json")),
                effects = { null },
            ),
        ).also { player ->
            player.listener = object : MusicPlayer.Listener {
                override fun onScrobble(event: ScrobbleTracker.Event) {
                    scrobbles += event
                }
            }
            closing += { player.release() }
        }
    }
    private val model: PlaybackModel by lazy { PlaybackModel(MusicEngine(music, MutableStateFlow(null)) {}) }

    /** The deck that plays: with no crossfade, the first. */
    private val exo: ExoPlayer get() = decks.firstOrNull { it.mediaItemCount > 0 } ?: decks.first()

    private fun songs(count: Int = 5, server: String = "s"): List<Song> = (0 until count).map { index ->
        val id = "$server|t$index"
        val file = File(context.filesDir, "misuse-$index-${System.nanoTime()}.mp3")
        file.writeBytes(javaClass.getResource("/fixtures/tone-8s.mp3")!!.readBytes())
        closing += { file.delete() }
        files[id] = Uri.fromFile(file)
        Song(id, "Song $index", artist = "Artist", album = "Album", durationMs = 8_000)
    }

    private fun idle() = ShadowLooper.idleMainLooper()

    private fun until(condition: () -> Boolean) = RobolectricUtil.runMainLooperUntil({ condition() }, RUN_TIMEOUT_MS, clock)

    /**
     * Found on the emulator: a concert alone in the queue, Next on the player's screen did
     * nothing. The screen's controller drops Next while the session says there is no next item.
     */
    @Test
    fun `next on a concert alone in the queue goes to its next song, whatever the controller thinks`() {
        val concert = songs(1)
        val engine = object : MusicEngine(music, MutableStateFlow(null), {}) {
            override val player: Player = object : androidx.media3.common.ForwardingPlayer(music.player) {
                override fun seekToNext() = Unit
            }
        }
        val model = PlaybackModel(engine)
        model.play(concert, containerId = "album:s|live")
        music.setChapters(concert[0].id, listOf(audio.own.player.music.TrackChapter("Intro", 0.0), audio.own.player.music.TrackChapter("Second", 4.0)))
        run(exo).withTimeoutMs(RUN_TIMEOUT_MS).untilState(Player.STATE_READY)
        idle()
        assertTrue(model.state.value.hasNext)
        model.next()
        idle()
        assertEquals(4_000L, exo.currentPosition)
        assertEquals(1, model.state.value.chapterIndex)
    }

    @Test
    fun `next, previous, play-pause and the scrubber hammered - it still plays after, and next greys out at the end`() {
        val songs = songs()
        model.play(songs, containerId = "album:s|a1")
        repeat(8) { model.next() }
        repeat(8) { model.previous() }
        repeat(9) { model.togglePlayPause() }
        for (fraction in listOf(0.9, 0.1, 0.5, 0.95, 0.0)) model.seekTo((8_000 * fraction).toLong())
        idle()

        if (!model.state.value.isPlaying) model.togglePlayPause()
        run(exo).withTimeoutMs(RUN_TIMEOUT_MS).untilState(Player.STATE_READY)
        val before = exo.currentPosition
        run(exo).withTimeoutMs(RUN_TIMEOUT_MS).untilPosition(exo.currentMediaItemIndex, before + 1_000)
        idle()
        assertTrue("after all that, it plays", model.state.value.isPlaying)
        assertTrue("and the position moves", model.progress().positionMs > before)

        // At the end of the queue Next has nothing to go to; Previous still has.
        repeat(40) { if (model.state.value.hasNext) model.next(); idle() }
        run(exo).withTimeoutMs(RUN_TIMEOUT_MS).untilPendingCommandsAreFullyHandled()
        idle()
        assertEquals("s|t4", model.state.value.currentTrack?.id)
        assertFalse("next greys out on the last song", model.state.value.hasNext)
        assertTrue("previous goes back from the last song", model.state.value.hasPrevious)
    }

    @Test
    fun `stop while the next song loads - nothing starts when the load lands, and the next play works`() {
        val songs = songs()
        model.play(songs)
        model.next()
        model.stop()
        idle()
        assertNull(model.state.value.currentTrack)
        // Give a late load every chance to land.
        repeat(20) { ShadowLooper.idleMainLooper(); shadowOf(exo.playbackLooper).idle() }
        idle()
        assertNull("a load that lands after the stop starts nothing", model.state.value.currentTrack)
        assertFalse(decks.any { it.isPlaying })
        assertEquals(0, decks.sumOf { it.mediaItemCount })

        model.play(songs, startId = "s|t2")
        run(exo).withTimeoutMs(RUN_TIMEOUT_MS).untilState(Player.STATE_READY)
        idle()
        assertEquals("s|t2", model.state.value.currentTrack?.id)
        assertTrue(model.state.value.isPlaying)
    }

    @Test
    fun `seek then stop at once - the seek doesn't bring the song back, and a new queue starts at its top`() {
        val songs = songs()
        model.play(songs)
        run(exo).withTimeoutMs(RUN_TIMEOUT_MS).untilState(Player.STATE_READY)
        model.seekTo(6_500)
        model.stop()
        run(exo).withTimeoutMs(RUN_TIMEOUT_MS).untilPendingCommandsAreFullyHandled()
        idle()
        assertNull(model.state.value.currentTrack)
        assertFalse(exo.isPlaying)

        model.play(songs.take(2))
        run(exo).withTimeoutMs(RUN_TIMEOUT_MS).untilState(Player.STATE_READY)
        idle()
        assertEquals("s|t0", model.state.value.currentTrack?.id)
        assertTrue("from the top, not from the old seek: ${exo.currentPosition}", exo.currentPosition < 3_000)
    }

    @Test
    fun `the server removed mid-song - its song stops, another server's plays on`() {
        model.play(songs(server = "s"))
        run(exo).withTimeoutMs(RUN_TIMEOUT_MS).untilState(Player.STATE_READY)
        model.serverRemoved("other")
        idle()
        assertTrue("someone else's server: nothing changes", model.state.value.isPlaying)

        model.serverRemoved("s")
        idle()
        assertNull("its own: playback stops", model.state.value.currentTrack)
        assertFalse(exo.isPlaying)
        model.serverRemoved("s")
        assertNull("a second time is harmless", model.state.value.currentTrack)
    }

    @Test
    fun `a call takes the audio for 30 s - the song waits where it was and goes on after, a permanent loss pauses`() {
        model.play(songs())
        run(exo).withTimeoutMs(RUN_TIMEOUT_MS).untilPosition(0, 1_000)
        idle()
        val audio = shadowOf(context.getSystemService(AudioManager::class.java))
        val focus = audio.lastAudioFocusRequest
        assertNotNull("playing asks for the audio focus", focus)
        val listener = focus.listener

        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        idle()
        run(exo).withTimeoutMs(RUN_TIMEOUT_MS).untilPendingCommandsAreFullyHandled()
        assertFalse("silent during the call", exo.isPlaying)
        assertTrue("but still meant to play", exo.playWhenReady)
        val during = exo.currentPosition
        // Thirty seconds of the call on the player's clock: the song stays where it was.
        clock.advanceTime(30_000)
        run(exo).withTimeoutMs(RUN_TIMEOUT_MS).untilPendingCommandsAreFullyHandled()
        idle()
        assertTrue("still in the queue", model.state.value.currentTrack != null)
        assertTrue("held at $during, now ${exo.currentPosition}", exo.currentPosition - during < 500)

        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        idle()
        until { exo.isPlaying }
        idle()
        assertTrue("goes on by itself after the call", model.state.value.isPlaying)

        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        idle()
        until { !exo.isPlaying }
        idle()
        assertFalse("another app's music took over: paused", model.state.value.isPlaying)
        model.togglePlayPause()
        until { exo.isPlaying }
        assertTrue("and Play plays again", model.state.value.isPlaying)
    }

    // region The play count

    /**
     * The count's rule (half the song or four minutes heard, nothing under 30 s) on a clock of
     * our own, through what the app sends: each listen is one play in the outbox, whatever the
     * listener does with the song meanwhile.
     */
    @Test
    fun `a song just over 30 s - a seek to the end is no listen, half of it heard is one play, however often it is paused`() = runBlocking {
        var now = 0L
        val sent = java.util.concurrent.CopyOnWriteArrayList<Pair<String, Long>>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { s -> closing += { s.cancel() } }
        val reporter = PlayReporter(sourceOf = { null }, enabled = { true }, scope = scope, recordPlay = { id, _, heard -> sent += id to heard })
        val tracker = ScrobbleTracker(now = { now }) { event ->
            if (event is ScrobbleTracker.Event.Played) reporter.played(event.key, event.startedAtMs, (event.heardSecs * 1000).toLong())
        }

        // 31 s: played 2 s, then seeked to the end and over. Not a listen.
        tracker.started("s|short", 31)
        now += 2_000
        tracker.stopped("s|short", isEnd = false)
        tracker.started("s|short", 31)
        now += 1_000
        tracker.stopped("s|short", isEnd = true)

        // 31 s again, a new listen: 10 s, paused a minute, 6 s more = 16 s heard, past half.
        tracker.started("s|short", 31)
        now += 10_000
        tracker.stopped("s|short", isEnd = false)
        now += 60_000
        tracker.started("s|short", 31)
        now += 6_000
        tracker.tick()
        // Paused and played again, seeked back: still the same listen, counted once.
        repeat(5) {
            tracker.stopped("s|short", isEnd = false)
            now += 1_000
            tracker.started("s|short", 31)
            now += 3_000
            tracker.tick()
        }
        tracker.stopped("s|short", isEnd = true)

        // 29 s heard to the end: under the 30-second floor, never a play.
        tracker.started("s|jingle", 29)
        now += 29_000
        tracker.stopped("s|jingle", isEnd = true)

        eventually(10_000) { sent.isNotEmpty() }
        Thread.sleep(200)
        assertEquals(listOf("s|short"), sent.map { it.first })
        assertTrue("the short one counted once it was half heard: ${sent[0].second}", sent[0].second in 15_500..17_000)

        // A 20-minute song counts at four minutes, not at ten.
        tracker.started("s|long", 1_200)
        now += 239_000
        tracker.tick()
        Thread.sleep(200)
        assertEquals("not before four minutes", 1, sent.size)
        now += 2_000
        tracker.tick()
        eventually(10_000) { sent.size >= 2 }
        assertEquals(listOf("s|short", "s|long"), sent.map { it.first })
        assertTrue(sent[1].second in 240_000..242_000)
    }

    // endregion

    // region Killed mid-download

    /** Serves the tone, slowly when asked, and honours `Range` as a file server does. */
    private class AudioServer(val bytes: ByteArray) : AutoCloseable {
        val server = MockWebServer()
        val requests: MutableList<RecordedRequest> = java.util.concurrent.CopyOnWriteArrayList()
        @Volatile var slow = false

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    val from = request.headers["Range"]?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull() ?: 0L
                    val body = Buffer().write(bytes, from.toInt(), bytes.size - from.toInt())
                    val answer = MockResponse.Builder()
                        .code(if (from > 0) 206 else 200)
                        .addHeader("Content-Type", "audio/mpeg")
                        .addHeader("Accept-Ranges", "bytes")
                        .body(body)
                    if (from > 0) answer.addHeader("Content-Range", "bytes $from-${bytes.size - 1}/${bytes.size}")
                    if (slow) answer.throttleBody(1_024, 100, java.util.concurrent.TimeUnit.MILLISECONDS)
                    return answer.build()
                }
            }
            server.start()
        }

        override fun close() = server.close()
    }

    private class NoScheduler : DownloadScheduler {
        override suspend fun active(): Set<String> = emptySet()
        override fun enqueue(key: String, wifiOnly: Boolean, replace: Boolean) = Unit
        override fun cancel(key: String) = Unit
        override fun cancelAll() = Unit
    }

    private fun downloads(db: DownloadsDatabase, root: File, source: MusicSource, scope: CoroutineScope) = DownloadsModel(
        context = context,
        sourceOf = { source },
        sourceForServer = { source },
        serversLoaded = {},
        bareHttp = OkHttpClient(),
        userAgent = "test",
        proxyMemory = ProxyMemory(),
        settings = { PlaybackSettings(downloadsWifiOnly = false) },
        settingsChanges = MutableStateFlow(PlaybackSettings(downloadsWifiOnly = false)),
        playing = { null },
        log = AppLog(null, echo = false),
        scope = scope,
        db = db,
        root = root,
        scheduler = NoScheduler(),
    )

    @Test
    fun `killed mid-download - the next launch picks up where it got, and keeps the whole song once`() = runBlocking {
        val tone = javaClass.getResource("/fixtures/tone-8s.mp3")!!.readBytes()
        val audio = AudioServer(tone).also { a -> closing += { a.close() } }
        val fake = FakeMusicSource()
        val source = object : MusicSource by fake {
            override suspend fun downloadLink(songId: String) = PlaybackLink(audio.server.url("/song.mp3").toString())
        }
        val root = File(context.filesDir, "misuse-downloads-${System.nanoTime()}").also { dir -> closing += { dir.deleteRecursively() } }
        val db = DownloadsDatabase.inMemory(context).also { d -> closing += { d.close() } }
        val song = Song("s|t1", "Song 1", album = "Album", albumId = "s|a1", durationMs = 8_000)

        val first = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val before = downloads(db, root, source, first)
        before.download(song)
        eventually { song.id in before.state.value.items }

        audio.slow = true
        val running = first.launch { before.downloads.run(song.id, attempt = 0) }
        eventually(20_000) { root.walk().any { it.name.endsWith(".part") && it.length() > 0 } }
        // The process goes: the work is cancelled mid-transfer, nothing is kept as done.
        running.cancelAndJoin()
        first.cancel()
        assertFalse(before.isDownloaded(song.id))
        val partial = root.walk().first { it.name.endsWith(".part") }.length()
        assertTrue("$partial of ${tone.size}", partial in 1 until tone.size)

        audio.slow = false
        val second = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { s -> closing += { s.cancel() } }
        val after = downloads(db, root, source, second)
        after.reconcile()
        assertEquals(Downloads.Outcome.DONE, after.downloads.run(song.id, attempt = 0))
        eventually { after.state.value.isDownloaded(song.id) }
        val file = after.localFile(song.id)?.path?.let(::File)
        assertNotNull(file)
        assertArrayEquals("the whole file, once", tone, file!!.readBytes())
        assertEquals("bytes=$partial-", audio.requests.last().headers["Range"])
        assertFalse("no part file left", root.walk().any { it.name.endsWith(".part") })
    }

    // endregion

    private companion object {
        /** Generous: CI's machines are much slower than a laptop. */
        const val RUN_TIMEOUT_MS = 60_000L
    }
}
