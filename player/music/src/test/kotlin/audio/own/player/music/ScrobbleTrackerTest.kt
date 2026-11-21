package audio.own.player.music

import audio.own.player.music.ScrobbleTracker.Event
import org.junit.Assert.assertEquals
import org.junit.Test

/** Port of music-ios `ScrobbleTrackerTests`. */
class ScrobbleTrackerTest {
    /** A clock the test moves by hand. */
    private class Clock(var timeMs: Long = 1_000_000_000) {
        fun advance(secs: Double) {
            timeMs += (secs * 1000).toLong()
        }
    }

    private val began = 1_000_000_000L
    private val clock = Clock()
    private val events = mutableListOf<Event>()
    private val tracker = ScrobbleTracker(now = { clock.timeMs }, send = { events += it })

    private fun plays() = events.count { it is Event.Played }

    @Test
    fun nowPlayingOnStart() {
        tracker.started("a", 200)
        assertEquals(listOf<Event>(Event.NowPlaying("a")), events)
    }

    @Test
    fun halfCounts() {
        tracker.started("a", 200)
        clock.advance(100.0)
        tracker.stopped("a", isEnd = true)
        assertEquals(listOf(Event.NowPlaying("a"), Event.Played("a", began, 100.0)), events)
    }

    @Test
    fun earlySkipDoesNotCount() {
        tracker.started("a", 200)
        clock.advance(99.0)
        tracker.stopped("a", isEnd = true)
        assertEquals(listOf<Event>(Event.NowPlaying("a")), events)
    }

    @Test
    fun fourMinutesCount() {
        tracker.started("long", 1800)
        clock.advance(240.0)
        tracker.stopped("long", isEnd = true)
        assertEquals(Event.Played("long", began, 240.0), events.last())
    }

    @Test
    fun shortTrackNeverCounts() {
        tracker.started("jingle", 20)
        clock.advance(20.0)
        tracker.stopped("jingle", isEnd = true)
        assertEquals(listOf<Event>(Event.NowPlaying("jingle")), events)
    }

    @Test
    fun pauseDoesNotCount() {
        tracker.started("a", 200)
        clock.advance(60.0)
        tracker.stopped("a", isEnd = false) // pause
        clock.advance(600.0)
        tracker.started("a", 200) // resume: no second now playing
        clock.advance(30.0)
        tracker.stopped("a", isEnd = true)
        assertEquals(listOf<Event>(Event.NowPlaying("a")), events)
    }

    @Test
    fun listeningAddsUpAcrossPause() {
        tracker.started("a", 200)
        clock.advance(60.0)
        tracker.stopped("a", isEnd = false)
        clock.advance(600.0)
        tracker.started("a", 200)
        clock.advance(40.0)
        tracker.stopped("a", isEnd = true)
        assertEquals(2, events.size)
    }

    @Test
    fun countsOnce() {
        tracker.started("a", 200)
        clock.advance(150.0)
        tracker.stopped("a", isEnd = false)
        tracker.started("a", 200)
        clock.advance(50.0)
        tracker.stopped("a", isEnd = true)
        assertEquals(1, plays())
    }

    @Test
    fun nextTrackFinishesPrevious() {
        tracker.started("a", 200)
        clock.advance(120.0)
        // A gapless change can report the next start without a stop in between.
        tracker.started("b", 200)
        assertEquals(listOf(Event.NowPlaying("a"), Event.Played("a", began, 120.0), Event.NowPlaying("b")), events)
    }

    @Test
    fun repeatOneIsANewListen() {
        tracker.started("a", 60)
        clock.advance(60.0)
        tracker.stopped("a", isEnd = true)
        tracker.started("a", 60)
        clock.advance(60.0)
        tracker.stopped("a", isEnd = true)
        assertEquals(2, plays())
    }

    @Test
    fun tickCountsWhilePlaying() {
        tracker.started("a", 200)
        clock.advance(101.0)
        tracker.tick()
        assertEquals(2, events.size)
    }

    @Test
    fun unknownLength() {
        tracker.started("a", null)
        clock.advance(239.0)
        tracker.tick()
        assertEquals(1, events.size)
        clock.advance(1.0)
        tracker.tick()
        assertEquals(2, events.size)
    }
}
