package audio.own.player.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Port of music-ios `ChapterNavigationTests`. */
class ChapterNavigationTest {
    // The first songs of a concert: an intro, then two songs.
    private val concert = listOf(
        TrackChapter("Intro", 0.0),
        TrackChapter("The Emptiness Machine", 157.0),
        TrackChapter("Lying From You", 377.0),
    )

    @Test
    fun theChapterPlayingIsTheLastOneStarted() {
        assertEquals(0, ChapterNavigation.index(concert, 0.0))
        assertEquals(0, ChapterNavigation.index(concert, 156.9))
        assertEquals(1, ChapterNavigation.index(concert, 157.0))
        assertEquals(2, ChapterNavigation.index(concert, 5000.0))
        assertNull(ChapterNavigation.index(emptyList(), 10.0))
        // A seek lands a hair before the mark it aimed at; it still counts as that chapter.
        assertEquals(1, ChapterNavigation.index(concert, 156.97))
    }

    @Test
    fun nextGoesToTheNextSongThenLeavesTheTrack() {
        assertEquals(157.0, ChapterNavigation.next(concert, 10.0))
        assertEquals(377.0, ChapterNavigation.next(concert, 200.0))
        assertNull(ChapterNavigation.next(concert, 400.0))
        assertNull(ChapterNavigation.next(emptyList(), 10.0))
    }

    @Test
    fun previousRestartsASongOrGoesBackOne() {
        // Well into a song: back to its start.
        assertEquals(157.0, ChapterNavigation.previous(concert, 200.0, restartThreshold = 3.0))
        // Just after it began: the song before.
        assertEquals(0.0, ChapterNavigation.previous(concert, 158.0, restartThreshold = 3.0))
        // In the first chapter the track's own rule decides.
        assertNull(ChapterNavigation.previous(concert, 60.0, restartThreshold = 3.0))
        assertNull(ChapterNavigation.previous(emptyList(), 60.0, restartThreshold = 3.0))
    }
}
