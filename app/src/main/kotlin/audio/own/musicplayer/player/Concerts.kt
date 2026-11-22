package audio.own.musicplayer.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import audio.own.music.library.ChapterDraft
import audio.own.music.library.ChapterTime
import audio.own.music.library.ConcertSource
import audio.own.music.library.MusicSource
import audio.own.music.library.model.Song
import audio.own.music.library.model.SongChapter
import audio.own.musicplayer.R
import audio.own.musicplayer.app.UiText
import audio.own.musicplayer.app.userMessage
import audio.own.player.music.TrackChapter
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Concerts: a whole concert as one song, with a chapter where each song of the show starts
 * (music-ios `AppModel+Concerts.swift`). When one starts, its chapters go to the player, it
 * resumes where it was left on any device (the server keeps the position) unless that was its
 * last half minute, and the position is saved every 15 s and on pause. Calls come from the
 * player's thread; the requests run on [scope], and what changes the player goes back through
 * [onPlayer].
 */
class ConcertTracker(
    private val sourceOf: (String) -> MusicSource?,
    private val isOffline: () -> Boolean,
    private val scope: CoroutineScope,
    private val onPlayer: (() -> Unit) -> Unit,
    private val setChapters: (trackId: String, chapters: List<TrackChapter>) -> Unit,
    /** Seeks the track if it still plays. */
    private val seek: (trackId: String, positionMs: Long) -> Unit,
) {
    /** The concert playing, and whether it is the listener's own (only the owner edits its songs). */
    data class Concert(val trackId: String, val canEdit: Boolean)

    private val _current = MutableStateFlow<Concert?>(null)
    val current: StateFlow<Concert?> = _current.asStateFlow()

    /** Songs played from a list, known to be concerts (or not) without asking. */
    private val known = ConcurrentHashMap<String, Song>()

    /** Tracks found to be concerts: their position is saved. */
    private val concerts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun remember(songs: List<Song>) {
        for (song in songs) if (song.chapterCount != null) known[song.id] = song
    }

    /** A track became the current one, at [positionMs]. */
    fun started(trackId: String, positionMs: Long, durationMs: Long?) {
        if (_current.value?.trackId != trackId) _current.value = null
        val source = sourceOf(trackId) as? ConcertSource ?: return
        scope.launch {
            val song = known[trackId] ?: quietly { source.song(trackId) }
            if (song?.isConcert != true && trackId !in concerts) return@launch
            concerts += trackId
            _current.value = Concert(trackId, canEdit = song?.sharing?.isMine == true)
            quietly { source.chapters(trackId) }?.let { chapters -> onPlayer { setChapters(trackId, chapters.map(::trackChapter)) } }
            if (positionMs > FROM_START_MS || isOffline()) return@launch
            val resume = resumeAt(quietly { source.savedPosition(trackId) }, song?.durationMs ?: durationMs) ?: return@launch
            onPlayer { seek(trackId, resume) }
        }
    }

    /** The player's 15-second tick while playing, and its note on pause. */
    fun progress(trackId: String, positionMs: Long) {
        if (trackId !in concerts || isOffline()) return
        val source = sourceOf(trackId) as? ConcertSource ?: return
        scope.launch { quietly { source.savePosition(trackId, positionMs) } }
    }

    /** Saves edited songs of a concert; the player follows at once. Throws what the server said. */
    suspend fun save(trackId: String, chapters: List<SongChapter>): List<SongChapter> {
        val source = sourceOf(trackId) as? ConcertSource ?: throw audio.own.music.library.SourceError.NotFound()
        val stored = source.saveChapters(trackId, chapters)
        onPlayer { setChapters(trackId, stored.map(::trackChapter)) }
        return stored
    }

    private suspend fun <T> quietly(work: suspend () -> T): T? = try {
        work()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    companion object {
        /** A concert started this near its beginning was started anew: it resumes. */
        const val FROM_START_MS = 3_000L
        const val LAST_PART_MS = 30_000L

        fun trackChapter(chapter: SongChapter) = TrackChapter(chapter.title, chapter.startMs / 1000.0)

        /** Where to start: where it was left, unless that was the last half minute (or nothing). */
        fun resumeAt(savedMs: Long?, durationMs: Long?): Long? {
            val saved = savedMs ?: return null
            if (saved <= 0) return null
            if (durationMs != null && saved > durationMs - LAST_PART_MS) return null
            return saved
        }
    }
}

/** The concert editor's rows and their checks (`Player/ConcertSheets.swift` ChapterEditorView). */
data class ChapterEditorState(
    val rows: List<Row> = emptyList(),
    val saving: Boolean = false,
    /** "Couldn't Save" with why. */
    val problem: UiText? = null,
    /** Saved: the editor closes. */
    val saved: Boolean = false,
) {
    data class Row(val key: Int, val title: String, val time: String) {
        val isTime: Boolean get() = ChapterTime.parse(time) != null
    }
}

class ChapterEditorViewModel(
    chapters: List<TrackChapter>,
    /** Where the player is, for Start Here and Add a Song Here. */
    private val position: () -> Long,
    private val save: suspend (List<SongChapter>) -> List<SongChapter>,
) : ViewModel() {
    private var nextKey = 0
    private val _state = MutableStateFlow(
        ChapterEditorState(chapters.map { ChapterEditorState.Row(nextKey++, it.title, ChapterTime.format((it.startSecs * 1000).toLong())) }),
    )
    val state: StateFlow<ChapterEditorState> = _state.asStateFlow()

    fun setTitle(key: Int, title: String) = edit(key) { it.copy(title = title) }

    fun setTime(key: Int, time: String) = edit(key) { it.copy(time = time) }

    fun startHere(key: Int) = edit(key) { it.copy(time = ChapterTime.format(position())) }

    fun addHere() = _state.update { it.copy(rows = it.rows + ChapterEditorState.Row(nextKey++, "", ChapterTime.format(position()))) }

    fun delete(key: Int) = _state.update { s -> s.copy(rows = s.rows.filterNot { it.key == key }) }

    fun dismissProblem() = _state.update { it.copy(problem = null) }

    fun save() {
        if (_state.value.saving) return
        val chapters = when (val outcome = ChapterDraft.check(_state.value.rows.map { ChapterDraft.Row(it.title, it.time) })) {
            is ChapterDraft.Outcome.Ready -> outcome.chapters
            is ChapterDraft.Outcome.NotATime -> return problem(UiText.of(R.string.chapter_not_a_time, outcome.time))
            ChapterDraft.Outcome.Unnamed -> return problem(UiText(R.string.chapter_unnamed))
            ChapterDraft.Outcome.SameStart -> return problem(UiText(R.string.chapter_same_start))
        }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                save(chapters)
                _state.update { it.copy(saving = false, saved = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, problem = e.userMessage()) }
            }
        }
    }

    private fun problem(text: UiText) = _state.update { it.copy(problem = text) }

    private fun edit(key: Int, change: (ChapterEditorState.Row) -> ChapterEditorState.Row) =
        _state.update { s -> s.copy(rows = s.rows.map { if (it.key == key) change(it) else it }) }
}
