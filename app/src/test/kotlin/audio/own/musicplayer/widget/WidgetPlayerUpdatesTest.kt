package audio.own.musicplayer.widget

import androidx.media3.common.Player
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The player widgets are redrawn on a change and once a minute while playing, never while paused. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetPlayerUpdatesTest {
    @Test
    fun `redrawn on play, pause and another song, and once a minute only while playing`() = runTest {
        val playing = MutableStateFlow<PlayingKey?>(null)
        var redraws = 0
        val updater = WidgetUpdater(
            store = WidgetStore(Files.createTempDirectory("widget").toFile()),
            track = MutableStateFlow(null),
            activeServerId = MutableStateFlow(null),
            source = { null },
            isOffline = { true },
            jpeg = { null },
            redraw = {},
            playing = playing,
            redrawPlayers = { redraws++ },
        )
        updater.start(backgroundScope)
        runCurrent()
        advanceTimeBy(1_000)
        assertEquals("drawn once at the start", 1, redraws)

        playing.value = PlayingKey("s|1", isPlaying = true)
        advanceTimeBy(1_000)
        assertEquals(2, redraws)
        advanceTimeBy(60_000)
        assertEquals("a minute later, for the progress", 3, redraws)
        advanceTimeBy(60_000)
        assertEquals(4, redraws)

        playing.value = PlayingKey("s|1", isPlaying = false)
        advanceTimeBy(1_000)
        assertEquals(5, redraws)
        advanceTimeBy(10 * 60_000)
        assertEquals("nothing while paused", 5, redraws)

        playing.value = PlayingKey("s|2", isPlaying = true)
        playing.value = PlayingKey("s|3", isPlaying = true)
        advanceTimeBy(1_000)
        assertEquals("skipping through songs draws once", 6, redraws)
    }

    @Test
    fun `each command does what its button says to the session's player`() {
        val calls = mutableListOf<String>()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            calls += method.name
            null
        } as Player
        WidgetActions.Command.PAUSE.sendTo(player)
        WidgetActions.Command.PREVIOUS.sendTo(player)
        WidgetActions.Command.NEXT.sendTo(player)
        assertEquals(listOf("pause", "seekToPrevious", "seekToNext"), calls)
    }
}
