package audio.own.musicplayer.player

import audio.own.music.library.ConcertSource
import audio.own.music.library.MusicSource
import audio.own.music.library.SourceError
import audio.own.music.library.model.Album
import audio.own.music.library.model.Sharing
import audio.own.music.library.model.Song
import audio.own.music.library.model.SongChapter
import audio.own.musicplayer.R
import audio.own.musicplayer.app.UiText
import audio.own.musicplayer.eventually
import audio.own.musicplayer.library.FakeMusicSource
import audio.own.musicplayer.mainThread
import audio.own.player.music.TrackChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConcertsTest {
    private class Concerts(
        val songs: Map<String, Song>,
        var saved: Long? = null,
        var refuseSave: Boolean = false,
    ) : MusicSource by FakeMusicSource(), ConcertSource {
        val log = mutableListOf<String>()
        override suspend fun song(id: String) = songs[id] ?: Song(id, "Song")
        override suspend fun supportsConcerts() = true
        override suspend fun concerts(): List<Album> = emptyList()
        override suspend fun chapters(songId: String) = listOf(SongChapter("Intro", 0), SongChapter("Second", 90_000))
        override suspend fun saveChapters(songId: String, chapters: List<SongChapter>): List<SongChapter> {
            if (refuseSave) throw SourceError.NotAllowed()
            log += "save $songId ${chapters.joinToString { "${it.title}@${it.startMs}" }}"
            return chapters
        }
        override suspend fun savedPosition(songId: String) = saved
        override suspend fun savePosition(songId: String, positionMs: Long) {
            log += "position $songId $positionMs"
        }
    }

    private val concert = Song("s|c1", "Live at Home", durationMs = 3_600_000, chapterCount = 2, sharing = Sharing.MINE)
    private val othersConcert = Song("s|c2", "Their Show", durationMs = 3_600_000, chapterCount = 2, sharing = Sharing.HOUSEHOLD)
    private val ordinary = Song("s|t1", "Plain", durationMs = 200_000)

    @Test
    fun resumesWhereItWasLeftButNotInTheLastHalfMinuteOrAtNothing() {
        assertEquals(600_000L, ConcertTracker.resumeAt(600_000, 3_600_000))
        assertNull(ConcertTracker.resumeAt(3_580_000, 3_600_000))
        assertNull(ConcertTracker.resumeAt(0, 3_600_000))
        assertNull(ConcertTracker.resumeAt(null, 3_600_000))
        assertEquals("no length known: where it was", 42_000L, ConcertTracker.resumeAt(42_000, null))
    }

    @Test
    fun aConcertStartedFromItsBeginningGetsItsChaptersAndResumes() = runTest(StandardTestDispatcher()) {
        val source = Concerts(mapOf(concert.id to concert), saved = 600_000)
        val chapters = mutableMapOf<String, List<TrackChapter>>()
        val seeks = mutableListOf<String>()
        val tracker = ConcertTracker({ source }, { false }, this, { it() }, { id, list -> chapters[id] = list }, { id, ms -> seeks += "$id $ms" })
        tracker.started(concert.id, positionMs = 0, durationMs = null)
        advanceUntilIdle()
        assertEquals(listOf(TrackChapter("Intro", 0.0), TrackChapter("Second", 90.0)), chapters[concert.id])
        assertEquals(listOf("s|c1 600000"), seeks)
        assertEquals(ConcertTracker.Concert(concert.id, canEdit = true), tracker.current.value)

        tracker.progress(concert.id, 615_000)
        advanceUntilIdle()
        assertEquals(listOf("position s|c1 615000"), source.log)
    }

    @Test
    fun aConcertAlreadyUnderwayKeepsItsPlaceAndSomeoneElsesIsNotEditable() = runTest(StandardTestDispatcher()) {
        val source = Concerts(mapOf(othersConcert.id to othersConcert), saved = 600_000)
        val seeks = mutableListOf<String>()
        val tracker = ConcertTracker({ source }, { false }, this, { it() }, { _, _ -> }, { id, ms -> seeks += "$id $ms" })
        tracker.started(othersConcert.id, positionMs = 120_000, durationMs = null)
        advanceUntilIdle()
        assertTrue(seeks.isEmpty())
        assertEquals(false, tracker.current.value?.canEdit)
    }

    @Test
    fun anOrdinarySongIsLeftAloneAndItsPositionNotSaved() = runTest(StandardTestDispatcher()) {
        val source = Concerts(mapOf(ordinary.id to ordinary), saved = 60_000)
        val chapters = mutableMapOf<String, List<TrackChapter>>()
        val tracker = ConcertTracker({ source }, { false }, this, { it() }, { id, list -> chapters[id] = list }, { _, _ -> })
        tracker.started(ordinary.id, 0, 200_000)
        tracker.progress(ordinary.id, 15_000)
        advanceUntilIdle()
        assertTrue(chapters.isEmpty())
        assertTrue(source.log.isEmpty())
        assertNull(tracker.current.value)
    }

    @Test
    fun songsQueuedFromAListAreKnownWithoutAskingAndSavedChaptersGoToThePlayer() = runTest(StandardTestDispatcher()) {
        // The source itself would call it an ordinary song: what the list said wins.
        val source = Concerts(emptyMap())
        val chapters = mutableMapOf<String, List<TrackChapter>>()
        val tracker = ConcertTracker({ source }, { false }, this, { it() }, { id, list -> chapters[id] = list }, { _, _ -> })
        tracker.remember(listOf(concert))
        tracker.started(concert.id, 0, null)
        advanceUntilIdle()
        assertEquals(2, chapters[concert.id]?.size)

        tracker.save(concert.id, listOf(SongChapter("Opening", 0), SongChapter("Encore", 3_000_000)))
        assertEquals(listOf(TrackChapter("Opening", 0.0), TrackChapter("Encore", 3_000.0)), chapters[concert.id])
    }

    @Test
    fun offlineNothingIsAskedOrSaved() = runTest(StandardTestDispatcher()) {
        val source = Concerts(mapOf(concert.id to concert), saved = 600_000)
        val seeks = mutableListOf<String>()
        val tracker = ConcertTracker({ source }, { true }, this, { it() }, { _, _ -> }, { id, ms -> seeks += "$id $ms" })
        tracker.started(concert.id, 0, null)
        advanceUntilIdle()
        tracker.progress(concert.id, 10_000)
        advanceUntilIdle()
        assertTrue(seeks.isEmpty())
        assertTrue(source.log.isEmpty())
    }

    // The editor

    private val main = mainThread()

    @Before fun setMain() = Dispatchers.setMain(main)

    @After fun resetMain() {
        Dispatchers.resetMain()
        main.close()
    }

    private fun editor(position: Long = 0, save: suspend (List<SongChapter>) -> List<SongChapter> = { it }) =
        ChapterEditorViewModel(listOf(TrackChapter("Intro", 0.0), TrackChapter("Second", 566.0)), { position }, save)

    @Test
    fun theEditorStartsFromTheChaptersAndAddsAndMovesSongsToWhereThePlayerIs() {
        val vm = editor(position = 3_723_000)
        assertEquals(listOf("0:00", "9:26"), vm.state.value.rows.map { it.time })
        vm.addHere()
        assertEquals("1:02:03", vm.state.value.rows.last().time)
        val first = vm.state.value.rows.first().key
        vm.startHere(first)
        assertEquals("1:02:03", vm.state.value.rows.first().time)
        vm.delete(first)
        assertEquals(2, vm.state.value.rows.size)
        vm.setTime(vm.state.value.rows.first().key, "nine")
        assertFalse(vm.state.value.rows.first().isTime)
    }

    @Test
    fun eachProblemHasItsSentenceAndNothingIsSent() {
        var sent = 0
        val vm = editor { sent++; it }
        val rows = vm.state.value.rows
        vm.setTime(rows[1].key, "9:6x")
        vm.save()
        assertEquals(UiText.of(R.string.chapter_not_a_time, "9:6x"), vm.state.value.problem)
        vm.dismissProblem()
        vm.setTime(rows[1].key, "0:00")
        vm.save()
        assertEquals(UiText(R.string.chapter_same_start), vm.state.value.problem)
        vm.setTime(rows[1].key, "9:26")
        vm.setTitle(rows[1].key, "  ")
        vm.save()
        assertEquals(UiText(R.string.chapter_unnamed), vm.state.value.problem)
        assertEquals(0, sent)
    }

    @Test
    fun savesInOrderAndARefusalSaysWhy() = runBlocking {
        val got = mutableListOf<List<SongChapter>>()
        val vm = editor { got += it; it }
        val rows = vm.state.value.rows
        vm.setTime(rows[0].key, "10:00")
        vm.save()
        eventually { vm.state.value.saved }
        assertEquals(listOf(SongChapter("Second", 566_000), SongChapter("Intro", 600_000)), got.single())

        val refused = editor { throw SourceError.NotAllowed() }
        refused.save()
        eventually { refused.state.value.problem != null }
        assertEquals(UiText(audio.own.core.strings.R.string.error_not_allowed), refused.state.value.problem)
        assertFalse(refused.state.value.saved)
    }
}
