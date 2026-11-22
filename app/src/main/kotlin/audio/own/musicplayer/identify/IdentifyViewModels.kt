package audio.own.musicplayer.identify

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import audio.own.kit.ownaudio.IdentifyGroup
import audio.own.kit.ownaudio.IdentifyProposal
import audio.own.kit.ownaudio.IdentifyRun
import audio.own.kit.ownaudio.IdentifyScope
import audio.own.kit.ownaudio.LibraryFolder
import audio.own.kit.ownaudio.RecordingCandidate
import audio.own.music.library.identify.IdentifyRunReview
import audio.own.music.library.identify.IdentifyRuns
import audio.own.music.library.identify.IdentifyStart
import audio.own.music.library.identify.IdentifyTab
import audio.own.music.library.identify.ProposalGroup
import audio.own.music.library.identify.RecordingResults
import audio.own.musicplayer.app.Load
import audio.own.musicplayer.app.UiText
import audio.own.musicplayer.app.userMessage
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// The screens of music-ios App/OwnAudioMusic/Identify: the run list (IdentifyRunsView), one run
// (IdentifyRunView), another edition (EditionsSheet) and the recording picker (RecordingPicker).
// What they ask the server is in :library (IdentifyRuns, IdentifyRunReview); these hold what the
// screens show while it is asked.

/** What a run's scope is called in lists: the whole library, or the label it was given. */
fun IdentifyRun.title(wholeLibrary: String, selected: String): String =
    if (scope.kind == "library") wholeLibrary else scope.label ?: selected

data class IdentifyRunsState(
    val runs: Load<List<IdentifyRun>> = Load.Loading,
    /** The server's music folders; empty where only the whole library can be chosen. */
    val folders: List<LibraryFolder> = emptyList(),
    /** The folder chosen, or null for the whole library. */
    val folder: LibraryFolder? = null,
    val starting: Boolean = false,
    /** A server that charges quoted a price: confirm before starting. */
    val confirm: IdentifyStart.Confirm? = null,
    /** "Couldn't Start Identifying" with why. */
    val startError: UiText? = null,
    /** A run just started: open it. */
    val opened: String? = null,
)

/** Identify Library: scope, start (with the price first where it costs), and the runs, kept current. */
class IdentifyRunsViewModel(private val runs: IdentifyRuns, private val wholeLibrary: String) : ViewModel() {
    private val local = MutableStateFlow(IdentifyRunsState())
    private val failure = MutableStateFlow<UiText?>(null)

    val state: StateFlow<IdentifyRunsState> = combine(local, runs.runs, runs.folders, failure) { s, list, folders, failed ->
        s.copy(
            runs = when {
                list != null -> Load.Loaded(list)
                failed != null -> Load.Failed(failed)
                else -> Load.Loading
            },
            folders = folders,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, IdentifyRunsState())

    init {
        viewModelScope.launch {
            load()
            runs.follow()
        }
    }

    private suspend fun load() {
        try {
            runs.reload()
            failure.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure.value = e.userMessage()
        }
    }

    fun retry() {
        failure.value = null
        viewModelScope.launch { load() }
    }

    fun refresh() {
        viewModelScope.launch { load() }
    }

    fun choose(folder: LibraryFolder?) = local.update { it.copy(folder = folder) }

    private fun scope(): IdentifyScope = runs.scope(local.value.folder, wholeLibrary)

    fun start() {
        if (local.value.starting) return
        local.update { it.copy(starting = true) }
        viewModelScope.launch {
            try {
                when (val start = runs.begin(scope())) {
                    is IdentifyStart.Confirm -> local.update { it.copy(confirm = start) }
                    is IdentifyStart.Started -> local.update { it.copy(opened = start.run.id) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                local.update { it.copy(startError = e.userMessage()) }
            } finally {
                local.update { it.copy(starting = false) }
            }
        }
    }

    fun confirmStart() {
        val confirm = local.value.confirm ?: return
        local.update { it.copy(confirm = null, starting = true) }
        viewModelScope.launch {
            try {
                val run = runs.start(confirm.scope)
                local.update { it.copy(opened = run.id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                local.update { it.copy(startError = e.userMessage()) }
            } finally {
                local.update { it.copy(starting = false) }
            }
        }
    }

    fun cancelConfirm() = local.update { it.copy(confirm = null) }

    fun dismissError() = local.update { it.copy(startError = null) }

    fun openedShown() = local.update { it.copy(opened = null) }
}

/** The notes a run's actions leave. */
sealed interface RunNote {
    data class Identified(val count: Int) : RunNote
    data class PutBack(val count: Int) : RunNote
    data object SongPutBack : RunNote
    data object NeverIdentified : RunNote
    data object SongIdentified : RunNote
}

data class IdentifyRunUi(
    val review: IdentifyRunReview.State = IdentifyRunReview.State(),
    /** An action is on its way: the buttons wait. */
    val busy: Boolean = false,
    /** A short note at the bottom, gone after a moment ("12 songs identified"). */
    val message: RunNote? = null,
    val error: UiText? = null,
) {
    val groups: List<ProposalGroup> get() = review.groups
}

/** One run: tabs, proposals by album, and what is done with them. */
class IdentifyRunViewModel(
    val review: IdentifyRunReview,
    /** Songs were identified or put back: the catalogue on the phone catches up. */
    private val onChanged: () -> Unit = {},
) : ViewModel() {
    private val local = MutableStateFlow(IdentifyRunUi())

    val state: StateFlow<IdentifyRunUi> = combine(local, review.state) { s, r -> s.copy(review = r) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, IdentifyRunUi())

    init {
        viewModelScope.launch { review.follow() }
    }

    fun show(tab: IdentifyTab) {
        viewModelScope.launch { runCatching { review.show(tab) }.onFailure { failed(it) } }
    }

    fun refresh() {
        viewModelScope.launch { runCatching { review.reload() }.onFailure { failed(it) } }
    }

    fun acceptSure() = act(changes = true) { RunNote.Identified(review.acceptSure()) }

    fun accept(p: IdentifyProposal) = act(changes = true) { review.accept(p); null }

    fun reject(p: IdentifyProposal) = act { review.reject(p); null }

    fun keepAlbum(p: IdentifyProposal, keep: Boolean) = act { review.keepAlbum(p, keep); null }

    fun undo(p: IdentifyProposal) = act(changes = true) {
        if (review.undo(p)) RunNote.SongPutBack else RunNote.NeverIdentified
    }

    fun acceptAll(group: ProposalGroup) = act(changes = true) { review.acceptAll(group); null }

    fun revert() = act(changes = true) { RunNote.PutBack(review.revert()) }

    fun delete() = act { review.delete(); null }

    /** The recording picker chose one for [p]: accepted on the server already. */
    fun recordingChosen() {
        onChanged()
        local.update { it.copy(message = RunNote.SongIdentified) }
    }

    /** Another edition was chosen: the proposals change. */
    fun editionChanged() = refresh()

    fun messageShown() = local.update { it.copy(message = null) }

    fun dismissError() = local.update { it.copy(error = null) }

    private fun failed(e: Throwable) {
        if (e is CancellationException) throw e
        local.update { it.copy(error = e.userMessage()) }
    }

    private fun act(changes: Boolean = false, work: suspend () -> RunNote?) {
        if (local.value.busy) return
        local.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val note = work()
                if (changes) onChanged()
                local.update { it.copy(message = note ?: it.message) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Part of it may have been done: the catalogue catches up anyway.
                if (changes) onChanged()
                local.update { it.copy(error = e.userMessage()) }
            } finally {
                local.update { it.copy(busy = false) }
            }
        }
    }
}

data class EditionsState(
    val group: Load<IdentifyGroup> = Load.Loading,
    val album: String = "",
    val artist: String = "",
    val searching: Boolean = false,
    /** The index being chosen. */
    val choosing: Int? = null,
    val error: UiText? = null,
    /** Chosen: the sheet closes. */
    val done: Boolean = false,
)

/** Other Edition…: the releases the server found for an album, and a search in the owner's words. */
class EditionsViewModel(
    val review: IdentifyRunReview,
    private val groupId: String,
    album: String,
    artist: String,
    private val onChanged: () -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(EditionsState(album = album, artist = artist))
    val state: StateFlow<EditionsState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(group = Load.Loading) }
        viewModelScope.launch {
            val next = try {
                Load.Loaded(review.editions(groupId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed(e.userMessage())
            }
            _state.update { it.copy(group = next) }
        }
    }

    fun setAlbum(text: String) = _state.update { it.copy(album = text) }

    fun setArtist(text: String) = _state.update { it.copy(artist = text) }

    val canSearch: Boolean get() = _state.value.let { !it.searching && (it.album.isNotBlank() || it.artist.isNotBlank()) }

    fun search() {
        if (!canSearch) return
        _state.update { it.copy(searching = true) }
        viewModelScope.launch {
            try {
                val found = review.searchEditions(groupId, _state.value.album, _state.value.artist)
                _state.update { it.copy(group = Load.Loaded(found)) }
                onChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.userMessage()) }
            } finally {
                _state.update { it.copy(searching = false) }
            }
        }
    }

    fun choose(index: Int) {
        if (_state.value.choosing != null) return
        _state.update { it.copy(choosing = index) }
        viewModelScope.launch {
            try {
                review.chooseEdition(groupId, index)
                onChanged()
                _state.update { it.copy(done = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.userMessage()) }
            } finally {
                _state.update { it.copy(choosing = null) }
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }
}

data class RecordingPickerState(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val link: String = "",
    val results: List<RecordingCandidate> = emptyList(),
    val searched: Boolean = false,
    /** The album left out because nothing was found on it. */
    val widenedFrom: String? = null,
    val searching: Boolean = false,
    /** The candidate (or "link") being applied. */
    val choosing: String? = null,
    val error: UiText? = null,
    /** Applied: the picker closes. */
    val done: Boolean = false,
) {
    val isBlank: Boolean get() = title.isBlank() && artist.isBlank() && album.isBlank()
}

/**
 * Pick the MusicBrainz recording a song really is: from what was already found, a search in the
 * owner's words (the tags are often what is wrong), or a pasted musicbrainz.org link.
 */
class RecordingPickerViewModel(
    title: String,
    artist: String?,
    album: String?,
    /** What is known before searching: a proposal's alternatives, or nothing. */
    private val initial: suspend () -> List<RecordingCandidate> = { emptyList() },
    private val search: suspend (title: String, artist: String, album: String) -> RecordingResults,
    /** Applies the choice: a recording id or link, and the release it was found on. */
    private val choose: suspend (recording: String, release: String?) -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(RecordingPickerState(title = title, artist = artist.orEmpty(), album = album.orEmpty()))
    val state: StateFlow<RecordingPickerState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val found = initial()
                _state.update { it.copy(results = found) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.userMessage()) }
            }
        }
    }

    fun setTitle(text: String) = _state.update { it.copy(title = text) }
    fun setArtist(text: String) = _state.update { it.copy(artist = text) }
    fun setAlbum(text: String) = _state.update { it.copy(album = text) }
    fun setLink(text: String) = _state.update { it.copy(link = text) }

    fun runSearch() {
        val s = _state.value
        if (s.searching || s.isBlank) return
        _state.update { it.copy(searching = true, widenedFrom = null) }
        viewModelScope.launch {
            try {
                val found = search(s.title, s.artist, s.album)
                _state.update { it.copy(results = found.candidates, widenedFrom = found.widenedFrom, searched = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.userMessage()) }
            } finally {
                _state.update { it.copy(searching = false) }
            }
        }
    }

    fun pick(candidate: RecordingCandidate) = apply(candidate.mbRecordingId, candidate.mbReleaseId, candidate.key)

    fun useLink() {
        val link = _state.value.link.trim()
        if (link.isEmpty()) return
        apply(link, null, LINK)
    }

    private fun apply(recording: String, release: String?, key: String) {
        if (_state.value.choosing != null) return
        _state.update { it.copy(choosing = key) }
        viewModelScope.launch {
            try {
                choose(recording.trim(), release)
                _state.update { it.copy(done = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.userMessage()) }
            } finally {
                _state.update { it.copy(choosing = null) }
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    companion object {
        const val LINK = "link"
    }
}
