package audio.own.player.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun numbered(count: Int) = (1..count).map { Track(id = "t$it", title = "t$it", durationSecs = 100) }

/** Port of music-ios `PlayQueuePositionTests` (all four suites of that file). */
class PlayQueuePositionTest {
    @Test
    fun position() {
        val queue = PlayQueue(numbered(12), startTrackId = "t4")
        assertEquals(PlayQueue.Position(4, 12), queue.positionDisplay)
    }

    /** What a container-wide progress bar measures against, so it leaves out the current song. */
    @Test
    fun played() {
        assertEquals(listOf("t1", "t2"), ids(PlayQueue(numbered(5), startTrackId = "t3").played))
    }

    @Test
    fun firstTrack() {
        val queue = PlayQueue(numbered(5))
        assertTrue(queue.played.isEmpty())
        assertEquals(1, queue.positionDisplay.current)
    }
}

class PlayQueueShuffleTest {
    /** The number on the player has to be findable among the rows, which shuffle doesn't reorder. */
    @Test
    fun listPosition() {
        val queue = PlayQueue(numbered(5), startTrackId = "t5")
        assertEquals(5, queue.listPositionDisplay?.current)
        queue.setShuffled(true)
        val current = queue.currentTrack!!
        val expected = queue.tracks.indexOfFirst { it.id == current.id } + 1
        assertEquals(expected, queue.listPositionDisplay?.current)
        assertEquals(5, queue.listPositionDisplay?.total)
    }
}

class PlayQueueShuffleMidwayTest {
    /**
     * The bug behind "the progress jumps when I press shuffle": songs nobody had played ended up
     * behind the cursor, counted as heard and dropped from what was still to come.
     */
    @Test
    fun nothingLandsBehind() {
        repeat(50) {
            val queue = PlayQueue(numbered(17))
            queue.setShuffled(true)
            assertTrue("on track 1 nothing has been heard yet", queue.played.isEmpty())
            assertEquals("every other track is still to come", 16, queue.upcoming.size)
            assertEquals("t1", queue.currentTrack?.id)
        }
    }

    @Test
    fun keepsHistory() {
        val queue = PlayQueue(numbered(17))
        queue.advance()
        queue.advance()
        val heard = ids(queue.played)
        queue.setShuffled(true)
        assertEquals(heard, ids(queue.played))
        assertEquals("t3", queue.currentTrack?.id)
        assertEquals(14, queue.upcoming.size)
    }

    @Test
    fun keepsEveryTrack() {
        val queue = PlayQueue(numbered(17))
        queue.advance()
        queue.setShuffled(true)
        val all = ids(queue.played) + queue.currentTrack!!.id + ids(queue.upcoming)
        assertEquals(17, all.toSet().size)
        assertEquals(17, all.size)
    }

    @Test
    fun unshuffle() {
        val queue = PlayQueue(numbered(17), startTrackId = "t5")
        queue.setShuffled(true)
        queue.setShuffled(false)
        assertEquals("t5", queue.currentTrack?.id)
        assertEquals("t6", queue.upcoming.first().id)
    }
}
