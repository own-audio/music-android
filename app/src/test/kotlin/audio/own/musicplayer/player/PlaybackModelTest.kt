package audio.own.musicplayer.player

import androidx.media3.common.PlaybackException
import audio.own.core.servers.MobileData
import audio.own.core.servers.NetworkState
import audio.own.music.library.model.Gain
import audio.own.music.library.model.Song
import audio.own.musicplayer.settings.PlaybackSettings
import audio.own.musicplayer.settings.PlaybackSettingsStore
import audio.own.player.core.SleepTimer
import audio.own.player.music.EqPreset
import audio.own.player.music.MusicPlayer
import audio.own.player.music.RepeatMode
import audio.own.player.music.ReplayGainMode
import audio.own.player.music.Track
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Records what the screens asked the player for. */
class FakeEngine : PlayerEngine {
    override val state = MutableStateFlow(MusicPlayer.State())
    override val sleep = MutableStateFlow(SleepTimer.State())
    override val error = MutableStateFlow<PlaybackException?>(null)
    val calls = mutableListOf<String>()
    var queued: List<Track> = emptyList()

    override fun progress() = Progress(positionMs = 1_000, durationMs = 10_000)
    override fun setQueue(tracks: List<Track>, startTrackId: String?, shuffle: Boolean, containerId: String?) {
        queued = tracks
        calls += "queue ${tracks.size} $startTrackId $shuffle $containerId"
        state.value = state.value.copy(currentTrack = tracks.firstOrNull { it.id == startTrackId } ?: tracks.first(), isPlaying = true)
    }
    override fun insertNext(tracks: List<Track>) { calls += "next ${tracks.map { it.id }}" }
    override fun append(tracks: List<Track>) { calls += "append ${tracks.map { it.id }}" }
    override fun play() { calls += "play"; state.value = state.value.copy(isPlaying = true) }
    override fun pause() { calls += "pause"; state.value = state.value.copy(isPlaying = false) }
    override fun next() { calls += "skipNext" }
    override fun previous() { calls += "skipPrevious" }
    override fun seekTo(positionMs: Long) { calls += "seek $positionMs" }
    override fun stop() { calls += "stop"; state.value = MusicPlayer.State() }
    override fun setShuffled(on: Boolean) { calls += "shuffle $on"; state.value = state.value.copy(isShuffled = on) }
    override fun setRepeatMode(mode: RepeatMode) { calls += "repeat $mode"; state.value = state.value.copy(repeatMode = mode) }
    override fun jumpTo(trackId: String) { calls += "jump $trackId" }
    override fun moveUpcoming(from: Int, to: Int) { calls += "move $from $to" }
    override fun removeUpcoming(index: Int) { calls += "remove $index" }
    override fun startSleep(preset: SleepTimer.Preset) { calls += "sleep ${preset.minutes}" }
    override fun sleepAtEndOfTrack() { calls += "sleep end" }
    override fun extendSleep(bySecs: Double) { calls += "extend $bySecs" }
    override fun cancelSleep() { calls += "sleep off" }
    override fun clearError() { error.value = null }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackModelTest {
    private val engine = FakeEngine()
    private val model = PlaybackModel(engine)
    private val songs = (1..3).map { Song("s|t$it", "Song $it", artist = "A", album = "B", durationMs = 61_500, coverArt = "s|c~img") }

    @Test
    fun playHandsTheEngineTracksWithTheirCoversAndGain() {
        model.play(songs.map { it.copy(gain = Gain(track = -3.0, trackPeak = 0.9)) }, startId = "s|t2", containerId = "album:s|a1")
        assertEquals("queue 3 s|t2 false album:s|a1", engine.calls.single())
        val track = engine.queued[1]
        assertEquals(61, track.durationSecs)
        assertEquals("s|c~img", track.artworkId)
        assertEquals(-3.0, track.replayGain?.trackGainDb)
        assertEquals("s|t2", model.state.value.currentTrack?.id)
    }

    @Test
    fun songsWithoutGainCarryNone() {
        assertNull(PlaybackModel.track(songs[0]).replayGain)
        assertNull(PlaybackModel.track(songs[0].copy(gain = Gain())).replayGain)
    }

    @Test
    fun enqueueWithNothingLoadedPlays() {
        model.enqueue(songs.take(1), next = true)
        assertTrue(engine.calls.single().startsWith("queue 1"))
        model.enqueue(songs.drop(1), next = true)
        model.enqueue(songs.drop(2), next = false)
        assertEquals("next [s|t2, s|t3]", engine.calls[1])
        assertEquals("append [s|t3]", engine.calls[2])
    }

    @Test
    fun anEmptyListPlaysNothing() {
        model.play(emptyList())
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun togglePausesAndPlays() {
        model.play(songs)
        model.togglePlayPause()
        model.togglePlayPause()
        assertEquals(listOf("pause", "play"), engine.calls.drop(1))
    }

    @Test
    fun aPlayClearsTheLastError() {
        engine.error.value = PlaybackException("x", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        model.play(songs)
        assertNull(model.error.value)
    }

    @Test
    fun repeatCyclesOffAllOne() {
        model.cycleRepeat(); model.cycleRepeat(); model.cycleRepeat()
        assertEquals(listOf("repeat ALL", "repeat ONE", "repeat OFF"), engine.calls)
    }

    @Test
    fun shuffleToggles() {
        model.toggleShuffle(); model.toggleShuffle()
        assertEquals(listOf("shuffle true", "shuffle false"), engine.calls)
    }

    @Test
    fun plusTenMinutesExtendsBySixHundredSeconds() {
        model.extendSleep()
        assertEquals("extend 600.0", engine.calls.single())
    }
}

class PlaybackSettingsTest {
    private val wifi = NetworkState(connected = true, metered = false, dataSaver = false, signature = "wifi")
    private val cellular = NetworkState(connected = true, metered = true, dataSaver = false, signature = "cell")

    @Test
    fun wifiAlwaysGetsTheOriginal() {
        assertNull(PlaybackSettings(mobileData = MobileData.SAVER).maxBitrateKbps(wifi))
    }

    @Test
    fun saverOnMobileDataCapsAtTheChosenBitrate() {
        assertEquals(192, PlaybackSettings(mobileData = MobileData.SAVER).maxBitrateKbps(cellular))
        assertEquals(320, PlaybackSettings(mobileData = MobileData.SAVER, saverKbps = 320).maxBitrateKbps(cellular))
        assertNull(PlaybackSettings(mobileData = MobileData.ORIGINAL).maxBitrateKbps(cellular))
    }

    @Test
    fun dataSaverKeepsStreamsAt128() {
        val saving = cellular.copy(dataSaver = true)
        assertEquals(128, PlaybackSettings(mobileData = MobileData.ORIGINAL).maxBitrateKbps(saving))
        assertEquals(128, PlaybackSettings(mobileData = MobileData.SAVER, saverKbps = 320).maxBitrateKbps(saving))
    }

    /** Found on the emulator: a fresh install streamed originals on mobile data; the guide and iOS say Saver at 192 kbps. */
    @Test
    fun aFreshInstallUsesSaverOnMobileData() {
        val fresh = PlaybackSettingsStore(PlaybackSettingsStore.Memory()).current
        assertEquals(MobileData.SAVER, fresh.mobileData)
        assertEquals(192, fresh.maxBitrateKbps(cellular))
    }

    @Test
    fun settingsSurviveARelaunch() {
        val memory = PlaybackSettingsStore.Memory()
        val store = PlaybackSettingsStore(memory)
        store.update { it.copy(crossfadeEnabled = true, fadeSecs = 20, replayGain = ReplayGainMode.ALBUM, mobileData = MobileData.DOWNLOADED_ONLY, reportPlays = false) }
        store.equalizer.setEnabled(true)
        store.equalizer.applyPreset(EqPreset.all[1])
        val again = PlaybackSettingsStore(memory)
        assertEquals(12, again.current.fadeSecs)
        assertEquals(12.0, again.current.crossfadeSecs)
        assertEquals(ReplayGainMode.ALBUM, again.current.replayGain)
        assertEquals(MobileData.DOWNLOADED_ONLY, again.current.mobileData)
        assertFalse(again.current.reportPlays)
        assertEquals("Bass Boost", again.equalizer.settings.value.presetName)
        assertTrue(again.equalizer.settings.value.enabled)
    }

    @Test
    fun crossfadeOffMeansGapless() {
        assertNull(PlaybackSettings(crossfadeEnabled = false, fadeSecs = 6).crossfadeSecs)
    }
}

class DeviceCodecsTest {
    @Test
    fun mapsDecodersToServerTypesMp3First() {
        val types = DeviceCodecs.fromMimeTypes(setOf("audio/mpeg", "audio/mp4a-latm", "audio/flac", "audio/opus", "audio/raw"))
        assertEquals("audio/mpeg", types.first())
        assertTrue("audio/flac" in types && "audio/ogg" in types && "audio/wav" in types && "audio/mp4" in types)
        assertFalse("audio/vorbis" in types)
    }

    @Test
    fun theTranscodeOrderIsMp3BeforeAac() {
        assertEquals(listOf("mp3", "aac"), AppLinkResolver.TRANSCODE)
    }
}

/** The full player's keyboard: Space, Ctrl+← and Ctrl+→, Esc. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerKeysTest {
    private val calls = mutableListOf<String>()

    private fun press(code: Int, ctrl: Boolean = false, hasTrack: Boolean = true, hasNext: Boolean = true): Boolean {
        val meta = if (ctrl) android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_CTRL_LEFT_ON else 0
        val event = androidx.compose.ui.input.key.KeyEvent(android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_DOWN, code, 0, meta))
        return playerKey(event, hasTrack, hasNext, { calls += "toggle" }, { calls += "next" }, { calls += "previous" }, { calls += "close" })
    }

    @Test
    fun spaceTogglesCtrlArrowsSkipEscCloses() {
        assertTrue(press(android.view.KeyEvent.KEYCODE_SPACE))
        assertTrue(press(android.view.KeyEvent.KEYCODE_DPAD_RIGHT, ctrl = true))
        assertTrue(press(android.view.KeyEvent.KEYCODE_DPAD_LEFT, ctrl = true))
        assertTrue(press(android.view.KeyEvent.KEYCODE_ESCAPE))
        assertEquals(listOf("toggle", "next", "previous", "close"), calls)
    }

    @Test
    fun plainArrowsMoveFocusAndNothingPlaysWithoutASong() {
        assertFalse("a plain arrow is left to focus", press(android.view.KeyEvent.KEYCODE_DPAD_RIGHT))
        assertFalse(press(android.view.KeyEvent.KEYCODE_SPACE, hasTrack = false))
        assertTrue("Esc closes even an empty player", press(android.view.KeyEvent.KEYCODE_ESCAPE, hasTrack = false))
        assertTrue("on the last song Ctrl+→ is taken, and does nothing", press(android.view.KeyEvent.KEYCODE_DPAD_RIGHT, ctrl = true, hasNext = false))
        assertEquals(listOf("close"), calls)
    }
}
