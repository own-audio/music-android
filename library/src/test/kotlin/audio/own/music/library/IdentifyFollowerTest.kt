package audio.own.music.library

import audio.own.kit.ownaudio.IdentifyProgress
import audio.own.kit.ownaudio.LiveEvent
import audio.own.music.library.identify.IdentifyEvents
import audio.own.music.library.identify.IdentifyFollower
import audio.own.music.library.identify.IdentifySignal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The timing rules of music-ios IdentifyLive.swift, on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class IdentifyFollowerTest {
    private fun progress(run: String = "r1") = IdentifySignal.Progress(IdentifyProgress(run, "running", 1, 2))

    private class Following(val signals: Channel<IdentifySignal>, val reloads: MutableList<Long>)

    private fun TestScope.follow(runActive: () -> Boolean, matching: (IdentifyProgress) -> Boolean = { true }): Following {
        val signals = Channel<IdentifySignal>(Channel.UNLIMITED)
        val reloads = mutableListOf<Long>()
        backgroundScope.launch {
            IdentifyFollower { testScheduler.currentTime }.follow(
                signals.consumeAsFlow(),
                matching = matching,
                runActive = runActive,
                reload = { reloads += testScheduler.currentTime },
            )
        }
        runCurrent()
        return Following(signals, reloads)
    }

    @Test fun `a burst of events reloads at once and once more when the 2 s are up, not per event`() = runTest {
        val f = follow(runActive = { false })
        f.signals.send(IdentifySignal.Open)
        repeat(5) { f.signals.send(progress()) }
        runCurrent()
        assertEquals(listOf(0L), f.reloads)
        advanceTimeBy(1_999)
        assertEquals(listOf(0L), f.reloads)
        advanceTimeBy(2)
        assertEquals("one trailing reload for the four held back", listOf(0L, 2_000L), f.reloads)
        advanceTimeBy(60_000)
        assertEquals(listOf(0L, 2_000L), f.reloads)
    }

    @Test fun `events of another run are heard but don't reload`() = runTest {
        val f = follow(runActive = { false }, matching = { it.runId == "r1" })
        f.signals.send(IdentifySignal.Open)
        f.signals.send(progress("r2"))
        runCurrent()
        assertTrue(f.reloads.isEmpty())
    }

    @Test fun `an open stream is not polled until silent for 30 s - a closed one is polled every 10 s`() = runTest {
        var active = true
        val f = follow(runActive = { active })
        f.signals.send(IdentifySignal.Open)
        runCurrent()
        advanceTimeBy(29_999)
        assertTrue("open and recently heard: no poll", f.reloads.isEmpty())
        advanceTimeBy(2)
        assertEquals("the 30 s tick finds it silent", listOf(30_000L), f.reloads)

        f.signals.send(progress())
        runCurrent()
        assertEquals("a poll counts as a reload: the event waits out the 2 s", listOf(30_000L), f.reloads)
        advanceTimeBy(2_000)
        assertEquals(listOf(30_000L, 32_000L), f.reloads)

        f.signals.send(IdentifySignal.Closed)
        runCurrent()
        advanceTimeBy(8_001)
        assertEquals("closed: the next tick polls", 40_000L, f.reloads.last())
        advanceTimeBy(10_000)
        assertEquals(50_000L, f.reloads.last())

        active = false
        val before = f.reloads.size
        advanceTimeBy(60_000)
        assertEquals("no active run, no polling", before, f.reloads.size)
    }

    @Test fun `retry pauses double from 2 s to a minute, and start over after a connection opened`() {
        assertEquals(2_000L to 4_000L, IdentifyEvents.retry(2_000, opened = false))
        assertEquals(32_000L to 60_000L, IdentifyEvents.retry(32_000, opened = false))
        assertEquals(60_000L to 60_000L, IdentifyEvents.retry(60_000, opened = false))
        assertEquals(2_000L to 4_000L, IdentifyEvents.retry(60_000, opened = true))
    }

    @Test fun `the stream reconnects 2, 4, 8 … 60 s apart while it fails, 2 s after one that opened`() = runTest {
        val tries = mutableListOf<Long>()
        var attempt = 0
        val signals = IdentifyEvents.signals { onOpen ->
            flow<LiveEvent> {
                tries += testScheduler.currentTime
                attempt++
                if (attempt == 8) {
                    onOpen()
                    emit(LiveEvent("identify", "1", """{"run_id":"r1","status":"running","groups_done":1,"groups_total":3}"""))
                    emit(LiveEvent("library", "2", "{}"))
                }
                throw java.io.IOException("gone")
            }
        }
        val seen = signals.take(7 + 3 + 1).toList()
        assertEquals(listOf(0L, 2_000L, 6_000L, 14_000L, 30_000L, 62_000L, 122_000L, 182_000L, 184_000L), tries)
        assertTrue(IdentifySignal.Open in seen)
        assertEquals(1, seen.count { it is IdentifySignal.Progress })
        assertFalse("library events are not identify's", seen.any { it is IdentifySignal.Progress && it.progress.runId != "r1" })
    }

    @Test fun `when to poll`() {
        assertFalse(IdentifyFollower.shouldPoll(runActive = false, streamOpen = false, silentForMs = 100_000))
        assertTrue(IdentifyFollower.shouldPoll(runActive = true, streamOpen = false, silentForMs = 0))
        assertFalse(IdentifyFollower.shouldPoll(runActive = true, streamOpen = true, silentForMs = 29_999))
        assertTrue(IdentifyFollower.shouldPoll(runActive = true, streamOpen = true, silentForMs = 30_000))
    }
}
