package audio.own.music.library

import audio.own.music.library.model.SongChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Ported from music-ios UnitTests/ChapterTimeTests.swift, plus the editor's checks (ConcertSheets.swift). */
class ChapterTimeTest {
    @Test fun `times as people write them`() {
        assertEquals("9:26", ChapterTime.format(566_000))
        assertEquals("1:02:03", ChapterTime.format(3_723_900))
        assertEquals("0:00", ChapterTime.format(-5_000))
        assertEquals(566_000L, ChapterTime.parse("9:26"))
        assertEquals(3_723_000L, ChapterTime.parse(" 1:02:03 "))
        assertEquals(75_000L, ChapterTime.parse("75"))
        assertEquals(3_660_000L, ChapterTime.parse("61:00"))
    }

    @Test fun `not times`() {
        for (text in listOf("", "abc", "1:75", "1::2", "1:2:3:4", "-1:00")) assertNull(text, ChapterTime.parse(text))
    }

    @Test fun `the editor's rows - sorted, named, never two at once`() {
        val ok = ChapterDraft.check(listOf(ChapterDraft.Row(" Encore ", "1:00:00"), ChapterDraft.Row("Opener", "0:00")))
        assertEquals(ChapterDraft.Outcome.Ready(listOf(SongChapter("Opener", 0), SongChapter("Encore", 3_600_000))), ok)
        assertEquals(ChapterDraft.Outcome.NotATime("9:75"), ChapterDraft.check(listOf(ChapterDraft.Row("A", "9:75"))))
        assertEquals(ChapterDraft.Outcome.Unnamed, ChapterDraft.check(listOf(ChapterDraft.Row(" ", "1:00"))))
        assertEquals(ChapterDraft.Outcome.SameStart, ChapterDraft.check(listOf(ChapterDraft.Row("A", "1:00"), ChapterDraft.Row("B", "60"))))
    }
}
