package audio.own.musicplayer.player

import audio.own.music.library.Capability
import audio.own.music.library.MusicSource
import audio.own.music.library.SourceError
import audio.own.musicplayer.library.FakeMusicSource
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayReporterTest {
    private class Reporting(override val capabilities: Set<Capability>, val down: Boolean = false) : MusicSource by FakeMusicSource() {
        val log = mutableListOf<String>()
        override suspend fun reportNowPlaying(songId: String, positionMs: Long) {
            if (down) throw SourceError.Network()
            log += "start $songId $positionMs"
        }
        override suspend fun reportProgress(songId: String, positionMs: Long, isPaused: Boolean) { log += "progress $songId $positionMs" }
        override suspend fun reportStopped(songId: String, positionMs: Long) { log += "stopped $songId $positionMs" }
        override suspend fun reportPlayed(songId: String, at: Instant) { log += "played $songId" }
    }

    private fun TestScope.reporter(source: MusicSource, outbox: MutableList<String>) = PlayReporter(
        sourceOf = { source },
        enabled = { true },
        scope = this,
        recordPlay = { id, _, _ -> outbox += id },
    )

    @Test
    fun jellyfinHearsStartProgressAndStopButNoPlayItSawLive() = runTest(StandardTestDispatcher()) {
        val jellyfin = Reporting(setOf(Capability.COUNTS_PLAYS_FROM_REPORTS))
        val outbox = mutableListOf<String>()
        val r = reporter(jellyfin, outbox)
        r.started("j|1")
        advanceUntilIdle()
        r.progress("j|1", 15_000, isPlaying = true)
        r.progress("j|1", 20_000, isPlaying = false)
        r.progress("j|1", 20_000, isPlaying = false)
        r.resumed("j|1", 20_000)
        r.played("j|1", 0, 120_000)
        r.started("j|2")
        r.ended()
        advanceUntilIdle()
        assertEquals(
            listOf("start j|1 0", "progress j|1 15000", "stopped j|1 20000", "start j|1 20000", "stopped j|1 20000", "start j|2 0", "stopped j|2 0"),
            jellyfin.log,
        )
        assertTrue(outbox.isEmpty())
    }

    @Test
    fun aJellyfinPlayItNeverHeardStartGoesToTheOutbox() = runTest(StandardTestDispatcher()) {
        val offline = Reporting(setOf(Capability.COUNTS_PLAYS_FROM_REPORTS), down = true)
        val outbox = mutableListOf<String>()
        val r = reporter(offline, outbox)
        r.started("j|1")
        advanceUntilIdle()
        r.played("j|1", 0, 120_000)
        advanceUntilIdle()
        assertEquals(listOf("j|1"), outbox)
    }

    @Test
    fun subsonicGetsEveryPlay() = runTest(StandardTestDispatcher()) {
        val subsonic = Reporting(setOf(Capability.RATINGS))
        val outbox = mutableListOf<String>()
        val r = reporter(subsonic, outbox)
        r.started("n|1")
        advanceUntilIdle()
        r.played("n|1", 0, 120_000)
        advanceUntilIdle()
        assertEquals(listOf("n|1"), outbox)
    }

    @Test
    fun nothingIsSentWithReportPlaysOff() = runTest(StandardTestDispatcher()) {
        val source = Reporting(setOf(Capability.RATINGS))
        val outbox = mutableListOf<String>()
        val r = PlayReporter({ source }, { false }, this, { id, _, _ -> outbox += id })
        r.started("n|1")
        r.progress("n|1", 1, true)
        r.played("n|1", 0, 1)
        advanceUntilIdle()
        assertTrue(source.log.isEmpty())
        assertTrue(outbox.isEmpty())
    }
}
