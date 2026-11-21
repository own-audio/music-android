package audio.own.player.music

import org.junit.Assert.assertEquals
import org.junit.Test

/** Port of music-ios `PlayQueueEditingTests`. */
class PlayQueueEditingTest {
    private fun queue(start: String = "a") = PlayQueue(listOf("a", "b", "c", "d").map { track(it) }, startTrackId = start)

    @Test
    fun playNext() {
        val q = queue("b")
        q.insertNext(listOf(track("x"), track("y")))
        assertEquals("b", q.currentTrack?.id)
        assertEquals(listOf("x", "y", "c", "d"), ids(q.upcoming))
        assertEquals(listOf("a"), ids(q.played))
        assertEquals(listOf("a", "b", "x", "y", "c", "d"), ids(q.tracks))
    }

    @Test
    fun append() {
        val q = queue()
        q.append(listOf(track("x")))
        assertEquals(listOf("b", "c", "d", "x"), ids(q.upcoming))
    }

    @Test
    fun noDuplicates() {
        val q = queue()
        q.insertNext(listOf(track("d"), track("a")))
        assertEquals("a", q.currentTrack?.id)
        assertEquals(listOf("d", "b", "c"), ids(q.upcoming))
        q.append(listOf(track("d")))
        assertEquals(listOf("b", "c", "d"), ids(q.upcoming))
    }

    @Test
    fun playedTrackMoves() {
        val q = queue("c")
        q.insertNext(listOf(track("a")))
        assertEquals(listOf("b"), ids(q.played))
        assertEquals("c", q.currentTrack?.id)
        assertEquals(listOf("a", "d"), ids(q.upcoming))
    }

    @Test
    fun moveAndRemove() {
        val q = queue()
        q.moveUpcoming(setOf(2), destination = 0)
        assertEquals(listOf("d", "b", "c"), ids(q.upcoming))
        q.moveUpcoming(setOf(0), destination = 3)
        assertEquals(listOf("b", "c", "d"), ids(q.upcoming))
        q.removeUpcoming(setOf(1))
        assertEquals(listOf("b", "d"), ids(q.upcoming))
        assertEquals("a", q.currentTrack?.id)
        assertEquals("b", q.advance()?.id)
        assertEquals("d", q.advance()?.id)
    }

    @Test
    fun shuffled() {
        val q = queue()
        q.setShuffled(true)
        q.insertNext(listOf(track("x")))
        assertEquals("x", q.nextTrack?.id)
        assertEquals(4, q.upcoming.size)
        q.setShuffled(false)
        assertEquals("a", q.currentTrack?.id)
        assertEquals(setOf("x", "b", "c", "d"), ids(q.upcoming).toSet())
        assertEquals("a", ids(q.tracks).first())
    }
}
