package audio.own.player.music

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper.run
import androidx.test.core.app.ApplicationProvider
import audio.own.player.core.PlaybackLinkRequest
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper

/**
 * The music engine on two real ExoPlayer decks (fake renderers) playing test tones from files.
 * Songs "t0"…"t4" are 8 s each unless a test says otherwise; a song without a file gets a
 * placeholder address that nothing resolves, which is how offline and failing songs are made.
 */
@RunWith(RobolectricTestRunner::class)
class MusicPlayerTest {
    @get:Rule val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val files = HashMap<String, Uri>()
    private var offline = false
    private val effects = mutableListOf<Pair<EqualizerSettings, Double>>()
    private val lastQueueFile by lazy { File(folder.root, "last-queue.json") }
    private val events = mutableListOf<String>()
    private var activeChanges = mutableListOf<Player>()

    private val player: MusicPlayer by lazy {
        MusicPlayer(
            MusicPlayer.Config(
                newDeck = { TestExoPlayerBuilder(context).build() },
                items = MusicMediaItems(localFile = { files[it] }),
                isFullyCached = { false },
                isOffline = { offline },
                lastQueue = lastQueueStore(lastQueueFile),
                effects = { _ -> FakeEffects() },
            ),
        ).also {
            it.listener = object : MusicPlayer.Listener {
                override fun onActivePlayerChanged(player: Player) {
                    activeChanges += player
                }

                override fun onScrobble(event: ScrobbleTracker.Event) {
                    events += event.toString()
                }

                override fun onPlaybackError(error: PlaybackException, stopped: Boolean) {
                    events += "error stopped=$stopped"
                }
            }
        }
    }

    private inner class FakeEffects : DeckEffects {
        override fun apply(eq: EqualizerSettings, boostDb: Double) {
            effects += eq to boostDb
        }

        override fun release() = Unit
    }

    private fun fixture(name: String, id: String): Uri {
        val file = File(folder.root, "$id-$name")
        file.writeBytes(javaClass.getResource("/fixtures/$name")!!.readBytes())
        return Uri.fromFile(file).also { files[id] = it }
    }

    private fun songs(count: Int = 5, fixture: String = "tone-8s.mp3"): List<Track> = (0 until count).map {
        fixture(fixture, "t$it")
        Track(id = "t$it", title = "Song $it", durationSecs = 8)
    }

    private val exo: ExoPlayer get() = player.activeExo

    private fun ids(p: ExoPlayer = exo) = (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId }

    private fun idle() = ShadowLooper.idleMainLooper()

    @After
    fun tearDown() = player.release()

    @Test
    fun `queue edits change what comes next and never the current item`() {
        val tracks = songs()
        player.setQueue(tracks, startTrackId = "t1")
        advance(exo).untilPosition(1, 1_000)
        var transitions = 0
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                transitions += 1
            }
        })

        player.insertNext(listOf(tracks[4]))
        player.append(listOf(Track("t5", "Song 5")))
        player.removeUpcoming(listOf(1))
        player.moveUpcoming(listOf(0), 3)
        run(exo).withTimeoutMs(RUN_TIMEOUT_MS).untilPendingCommandsAreFullyHandled()

        assertEquals(0, transitions)
        assertEquals("t1", exo.currentMediaItem?.mediaId)
        assertTrue(exo.isPlaying)
        assertTrue(exo.currentPosition >= 1_000)
        val state = player.state.value
        assertEquals(listOf("t0", "t1") + state.upcoming.map { it.id }, ids())
        // Play Next t4, add t5, drop t2, drag t4 to the end.
        assertEquals(listOf("t3", "t5", "t4"), state.upcoming.map { it.id })
    }

    @Test
    fun `shuffle keeps the current song and turning it off restores the list order`() {
        player.setQueue(songs(), startTrackId = "t2")
        run(exo).withTimeoutMs(RUN_TIMEOUT_MS).untilState(Player.STATE_READY)
        player.setShuffled(true)
        assertEquals("t2", exo.currentMediaItem?.mediaId)
        assertEquals(listOf("t0", "t1", "t2"), ids().take(3))
        assertTrue(exo.shuffleModeEnabled)
        assertEquals(player.state.value.upcoming.map { it.id }, ids().drop(3))
        player.setShuffled(false)
        assertEquals(listOf("t0", "t1", "t2", "t3", "t4"), ids())
        assertEquals("t2", exo.currentMediaItem?.mediaId)
    }

    @Test
    fun `previous restarts a song after 3 s and goes back one before`() {
        player.setQueue(songs(), startTrackId = "t1")
        advance(exo).untilPosition(1, 4_000)
        player.player.seekToPrevious()
        assertEquals(1, exo.currentMediaItemIndex)
        assertTrue(exo.currentPosition < 1_000)
        player.player.seekToPrevious()
        assertEquals(0, exo.currentMediaItemIndex)
    }

    @Test
    fun `next and previous move between a concert's chapters`() {
        player.setQueue(songs(2))
        player.setChapters("t0", listOf(TrackChapter("Intro", 0.0), TrackChapter("Song A", 2.0), TrackChapter("Song B", 5.0)))
        advance(exo).untilPosition(0, 500)
        player.player.seekToNext()
        assertEquals(0, exo.currentMediaItemIndex)
        assertEquals(2_000, exo.currentPosition)
        player.player.seekToNext()
        assertEquals(5_000, exo.currentPosition)
        player.player.seekToPrevious()
        assertEquals(2_000, exo.currentPosition)
        assertEquals(1, player.state.value.chapterIndex)
        player.player.seekTo(5_500)
        player.player.seekToNext()
        assertEquals(1, exo.currentMediaItemIndex)
    }

    @Test
    fun `sleep at end of track stops there instead of starting the next song`() {
        player.setQueue(songs(3, "test-tone.mp3"))
        player.sleepTimer.startUntilEndOfItem()
        idle()
        assertTrue(exo.pauseAtEndOfMediaItems)
        run(exo).withTimeoutMs(RUN_TIMEOUT_MS).untilPlayWhenReadyIs(false)
        run(exo).withTimeoutMs(RUN_TIMEOUT_MS).untilPendingCommandsAreFullyHandled()
        assertFalse(exo.isPlaying)
        assertEquals(1, exo.currentMediaItemIndex)
        assertEquals(0, exo.currentPosition)
        assertFalse(player.sleepTimer.stopsAtEndOfItem)
        idle()
        assertFalse(exo.pauseAtEndOfMediaItems)
    }

    @Test
    fun `offline, songs with no local file are skipped`() {
        val tracks = songs(4)
        files.remove("t1")
        files.remove("t2")
        offline = true
        player.setQueue(tracks, startTrackId = "t1")
        assertEquals("t3", exo.currentMediaItem?.mediaId)
    }

    @Test
    fun `three load failures in a row stop playback`() {
        val tracks = songs(5)
        files.clear()
        player.setQueue(tracks)
        repeat(3) { run(exo).ignoringNonFatalErrors().untilPendingCommandsAreFullyHandled(); runCatching { run(exo).withTimeoutMs(RUN_TIMEOUT_MS).untilPlayerError() } }
        assertEquals(listOf("error stopped=false", "error stopped=false", "error stopped=true"), events.filter { it.startsWith("error") })
        assertEquals(2, exo.currentMediaItemIndex)
    }

    @Test
    fun `ReplayGain is the volume of each song, and a boost goes to the effects`() {
        val tracks = songs(2).let {
            listOf(
                it[0].copy(replayGain = TrackGain(trackGainDb = -6.0)),
                it[1].copy(replayGain = TrackGain(trackGainDb = 3.0)),
            )
        }
        player.setQueue(tracks)
        run(exo).withTimeoutMs(RUN_TIMEOUT_MS).untilState(Player.STATE_READY)
        assertEquals(0.501f, exo.volume, 0.01f)
        assertEquals(-6.0, player.state.value.replayGainDb, 0.01)

        player.player.seekToNext()
        run(exo).withTimeoutMs(RUN_TIMEOUT_MS).untilPendingCommandsAreFullyHandled()
        assertEquals(1f, exo.volume)
        assertEquals(3.0, effects.last().second, 0.01)

        player.setReplayGainMode(ReplayGainMode.OFF)
        assertEquals(0.0, effects.last().second, 0.0)
    }

    @Test
    fun `ReplayGain from the file's tags when the server has none`() {
        fixture("tone-replaygain.mp3", "rg")
        player.setQueue(listOf(Track("rg", "Tagged")))
        run(exo).withTimeoutMs(RUN_TIMEOUT_MS).untilState(Player.STATE_READY)
        run(exo).withTimeoutMs(RUN_TIMEOUT_MS).untilPendingCommandsAreFullyHandled()
        assertEquals(-6.0, player.state.value.replayGainDb, 0.01)
        assertEquals(0.501f, exo.volume, 0.01f)
        assertEquals("MP3", player.state.value.format?.codec)
    }

    @Test
    fun `no crossfade while sleeping at end of track or into a song not on the phone`() {
        val tracks = songs(3)
        player.setCrossfade(2.0)
        player.setQueue(tracks)
        run(exo).withTimeoutMs(RUN_TIMEOUT_MS).untilState(Player.STATE_READY)

        player.sleepTimer.startUntilEndOfItem()
        assertFalse(player.beginCrossfade())
        player.sleepTimer.cancel()

        files.remove("t1")
        player.refreshUpcoming()
        assertFalse(player.beginCrossfade())
    }

    @Test
    fun `a crossfade ramps the decks, and a skip mid-fade restores the volume`() {
        player.setCrossfade(2.0)
        player.setQueue(songs(3))
        advance(exo).untilPosition(0, 1_000)
        val outgoing = exo
        val sessionPlayer = player.player

        assertTrue(player.beginCrossfade())
        val incoming = exo
        assertNotSame(outgoing, incoming)
        assertSame(player.player, activeChanges.single())
        assertNotSame(sessionPlayer, player.player)
        assertEquals("t1", player.state.value.currentTrack?.id)
        assertEquals(listOf("t0"), ids(outgoing))
        assertEquals(listOf("t0", "t1", "t2"), ids(incoming))
        assertTrue(player.state.value.isCrossfading)

        ShadowLooper.idleMainLooper(1_000, TimeUnit.MILLISECONDS)
        assertTrue("incoming ${incoming.volume}", incoming.volume in 0.3f..0.7f)
        assertTrue("outgoing ${outgoing.volume}", outgoing.volume in 0.3f..0.7f)

        player.player.seekToNext()
        assertFalse(player.state.value.isCrossfading)
        assertEquals(1f, incoming.volume)
        assertEquals(0, outgoing.mediaItemCount)
        assertEquals("t2", incoming.currentMediaItem?.mediaId)
    }

    @Test
    fun `a full crossfade ends with one deck at full volume`() {
        player.setCrossfade(1.0)
        player.setQueue(songs(2))
        advance(exo).untilPosition(0, 500)
        val outgoing = exo
        assertTrue(player.beginCrossfade())
        ShadowLooper.idleMainLooper(2_000, TimeUnit.MILLISECONDS)
        assertFalse(player.state.value.isCrossfading)
        assertEquals(1f, exo.volume)
        assertEquals(Player.STATE_IDLE, outgoing.playbackState)
    }

    @Test
    fun `the last queue is saved, restored paused without focus, and cleared on stop`() {
        val store = lastQueueStore(lastQueueFile)
        player.setQueue(songs(3), startTrackId = "t1", containerId = "s|album:1")
        advance(exo).untilPosition(1, 1_000)
        player.player.pause()
        val saved = store.read()!!
        assertEquals("t1", saved.currentTrackId)
        assertTrue("saved at ${saved.positionMs}", saved.positionMs >= 900)
        assertEquals("s|album:1", saved.containerId)
        player.stop()
        assertNull(store.read())

        store.write(saved)
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // The first player asked for focus when it played; restoring must not ask again.
        val focusBefore = shadowOf(audio).lastAudioFocusRequest
        val again = MusicPlayer(MusicPlayer.Config(newDeck = { TestExoPlayerBuilder(context).build() }, items = MusicMediaItems(localFile = { files[it] }), lastQueue = store, effects = { null }))
        assertTrue(again.restoreLastQueue())
        assertEquals("t1", again.activeExo.currentMediaItem?.mediaId)
        assertEquals(Player.STATE_IDLE, again.activeExo.playbackState)
        assertSame(focusBefore, shadowOf(audio).lastAudioFocusRequest)
        assertEquals(1, again.resumptionItems()?.startIndex)
        again.release()
    }

    @Test
    fun `controllers edit the queue through its rules`() {
        player.setQueue(songs(4))
        run(exo).withTimeoutMs(RUN_TIMEOUT_MS).untilState(Player.STATE_READY)
        val session = player.player
        session.addMediaItem(1, MediaItem.Builder().setMediaId("t3").build())
        assertEquals(listOf("t0", "t3", "t1", "t2"), ids())
        session.removeMediaItem(2)
        assertEquals(listOf("t0", "t3", "t2"), ids())
        session.shuffleModeEnabled = true
        assertTrue(player.state.value.isShuffled)
        assertEquals("t0", exo.currentMediaItem?.mediaId)
    }

    @Test
    fun `a play is reported as now playing when a song starts`() {
        player.setQueue(songs(1))
        run(exo).withTimeoutMs(RUN_TIMEOUT_MS).untilPendingCommandsAreFullyHandled()
        advance(exo).untilPosition(0, 500)
        assertEquals(ScrobbleTracker.Event.NowPlaying("t0").toString(), events.first())
    }

    @Test
    fun `offline playability counts a fully cached stream`() {
        var cached = false
        val p = MusicPlayer(
            MusicPlayer.Config(
                newDeck = { TestExoPlayerBuilder(context).build() },
                items = MusicMediaItems(localFile = { files[it] }),
                isFullyCached = { request: PlaybackLinkRequest -> cached && request.mediaId == "t1" },
                isOffline = { true },
                effects = { null },
            ),
        )
        val tracks = songs(3)
        files.remove("t1")
        cached = true
        p.setQueue(tracks, startTrackId = "t1", play = false)
        assertEquals("t1", p.activeExo.currentMediaItem?.mediaId)
        p.release()
    }
}

/** Generous: CI's machines are much slower than a laptop, and a test player that stalls fails anyway. */
private const val RUN_TIMEOUT_MS = 60_000L
