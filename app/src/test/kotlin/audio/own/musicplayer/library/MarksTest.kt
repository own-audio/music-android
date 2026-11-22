package audio.own.musicplayer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Found against a server with a big library: an album made a favourite here, then removed from it
 * on another device, still showed as a favourite after the change had synced, even on reopening.
 */
class MarksTest {
    @Test
    fun aFavouriteTheServerTookFollowsTheServerAfterwards() {
        // Set here, taken by the server, then removed elsewhere: the feed says no.
        assertFalse(Marks.favourite(changedHere = true, taken = true, serverSays = false, fallback = true))
        // Removed here, taken, then set again elsewhere.
        assertTrue(Marks.favourite(changedHere = false, taken = true, serverSays = true, fallback = false))
    }

    @Test
    fun aChangeNotYetTakenStillWins() {
        // Waiting in the outbox offline, or the request still on its way.
        assertTrue(Marks.favourite(changedHere = true, taken = false, serverSays = false, fallback = false))
        assertFalse(Marks.favourite(changedHere = false, taken = false, serverSays = true, fallback = true))
    }

    @Test
    fun otherServersKeepTheChangeMadeHere() {
        assertTrue(Marks.favourite(changedHere = true, taken = true, serverSays = null, fallback = false))
        assertFalse(Marks.favourite(changedHere = null, taken = false, serverSays = null, fallback = false))
    }

    @Test
    fun untouchedFollowsTheServerOrTheModel() {
        assertTrue(Marks.favourite(changedHere = null, taken = false, serverSays = true, fallback = false))
        assertTrue(Marks.favourite(changedHere = null, taken = false, serverSays = false, fallback = true))
    }

    @Test
    fun ratingsTheSame() {
        assertEquals(null, Marks.rating(changedHere = 4, taken = true, serverSays = 0, fallback = 4))
        assertEquals(2, Marks.rating(changedHere = 4, taken = true, serverSays = 2, fallback = null))
        assertEquals(4, Marks.rating(changedHere = 4, taken = false, serverSays = 0, fallback = null))
        assertEquals(null, Marks.rating(changedHere = 0, taken = false, serverSays = 3, fallback = 3))
        assertEquals(3, Marks.rating(changedHere = null, taken = false, serverSays = 0, fallback = 3))
    }
}
