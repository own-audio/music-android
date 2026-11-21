package audio.own.player.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun track(id: String, secs: Int? = 200) = Track(id = id, title = "Track $id", artist = "Artist", album = "Album", durationSecs = secs)

internal fun ids(tracks: List<Track>) = tracks.map { it.id }

/** Port of music-ios `PlayQueueTests`. */
class PlayQueueTest {
    private val threeTracks = listOf(track("t1"), track("t2"), track("t3"))

    @Test
    fun startsAtFirstTrack() {
        val queue = PlayQueue(threeTracks)
        assertEquals("t1", queue.currentTrack?.id)
        assertEquals("t2", queue.nextTrack?.id)
        assertNull(queue.previousTrack)
    }

    @Test
    fun startsAtGivenTrack() {
        val queue = PlayQueue(threeTracks, startTrackId = "t2")
        assertEquals("t2", queue.currentTrack?.id)
        assertEquals("t3", queue.nextTrack?.id)
        assertEquals("t1", queue.previousTrack?.id)
    }

    @Test
    fun unknownStartTrackIdFallsBackToFirst() {
        assertEquals("t1", PlayQueue(threeTracks, startTrackId = "does-not-exist").currentTrack?.id)
    }

    @Test
    fun advanceMovesForwardThenExhausts() {
        val queue = PlayQueue(threeTracks)
        assertEquals("t2", queue.advance()?.id)
        assertEquals("t3", queue.advance()?.id)
        assertNull(queue.advance())
        assertNull(queue.currentTrack)
    }

    @Test
    fun goToPreviousStopsAtStart() {
        assertEquals("t1", PlayQueue(threeTracks).goToPrevious()?.id)
    }

    @Test
    fun jumpToMovesDirectly() {
        val queue = PlayQueue(threeTracks)
        assertEquals("t3", queue.jumpTo("t3")?.id)
        assertNull(queue.jumpTo("does-not-exist"))
        assertEquals("a failed jump must not move the queue", "t3", queue.currentTrack?.id)
    }

    @Test
    fun shufflePreservesCurrentTrack() {
        val queue = PlayQueue(threeTracks, startTrackId = "t2")
        queue.setShuffled(true)
        assertTrue(queue.isShuffled)
        assertEquals("shuffling must not change what's currently playing", "t2", queue.currentTrack?.id)
    }

    @Test
    fun shuffleFromStartDoesNotPinTheFirstTrack() {
        // Ten songs shuffled many times: if the start were pinned, every run would begin at t1.
        val tracks = (1..10).map { Track(id = "t$it", title = "Track $it", durationSecs = 100) }
        val starts = HashSet<String>()
        repeat(50) {
            val queue = PlayQueue(tracks)
            queue.shuffleFromStart()
            assertTrue(queue.isShuffled)
            starts += queue.currentTrack?.id ?: ""
        }
        assertTrue("shuffle always began with the same track: $starts", starts.size > 1)
    }

    @Test
    fun unshuffleRestoresOriginalOrder() {
        val queue = PlayQueue(threeTracks)
        queue.setShuffled(true)
        queue.setShuffled(false)
        assertFalse(queue.isShuffled)
        assertEquals("t1", queue.currentTrack?.id)
        assertEquals("t2", queue.nextTrack?.id)
    }

    @Test
    fun trackAfterCurrentOffMatchesNextTrack() {
        val queue = PlayQueue(threeTracks, startTrackId = "t3")
        assertNull(queue.trackAfterCurrent(RepeatMode.OFF))
        queue.jumpTo("t1")
        assertEquals("t2", queue.trackAfterCurrent(RepeatMode.OFF)?.id)
    }

    @Test
    fun trackAfterCurrentOneIsCurrentTrack() {
        assertEquals("t2", PlayQueue(threeTracks, startTrackId = "t2").trackAfterCurrent(RepeatMode.ONE)?.id)
    }

    @Test
    fun trackAfterCurrentAllWrapsAtTheEnd() {
        val queue = PlayQueue(threeTracks)
        assertEquals("mid-queue, ALL behaves exactly like OFF", "t2", queue.trackAfterCurrent(RepeatMode.ALL)?.id)
        queue.jumpTo("t3")
        assertEquals("at the last track, ALL wraps instead of returning null", "t1", queue.trackAfterCurrent(RepeatMode.ALL)?.id)
    }

    @Test
    fun advanceOffMatchesPlainAdvance() {
        val queue = PlayQueue(threeTracks, startTrackId = "t3")
        assertNull(queue.advance(RepeatMode.OFF))
        assertNull("exhausting the queue under OFF still stops", queue.currentTrack)
    }

    @Test
    fun advanceOneReplaysCurrentTrack() {
        val queue = PlayQueue(threeTracks, startTrackId = "t2")
        assertEquals("t2", queue.advance(RepeatMode.ONE)?.id)
        assertEquals("position must not move under ONE", "t2", queue.currentTrack?.id)
        assertEquals("t2", queue.advance(RepeatMode.ONE)?.id)
    }

    @Test
    fun advanceAllWrapsInsteadOfExhausting() {
        val queue = PlayQueue(threeTracks, startTrackId = "t3")
        assertEquals("t1", queue.advance(RepeatMode.ALL)?.id)
        assertEquals("t1", queue.currentTrack?.id)
        assertEquals("wrapping lands at a real position", "t2", queue.nextTrack?.id)
    }

    @Test
    fun advanceAllMatchesPlainAdvanceMidQueue() {
        val queue = PlayQueue(threeTracks)
        assertEquals("t2", queue.advance(RepeatMode.ALL)?.id)
        assertEquals("t2", queue.currentTrack?.id)
    }

    /** Not in the Swift suite: the repeat button's cycle and the container ids. */
    @Test
    fun repeatCyclesOffAllOne() {
        assertEquals(RepeatMode.ALL, RepeatMode.OFF.next())
        assertEquals(RepeatMode.ONE, RepeatMode.ALL.next())
        assertEquals(RepeatMode.OFF, RepeatMode.ONE.next())
    }

    @Test
    fun containersAreScopedByServer() {
        assertEquals("srv-1|album:42", PlayContainer.scoped("srv-1", PlayContainer.album("42")))
        assertEquals("playlist:p", PlayContainer.playlist("p"))
        assertEquals("mix:m", PlayContainer.mix("m"))
    }
}
