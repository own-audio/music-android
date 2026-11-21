package audio.own.music.library.identify

import audio.own.kit.ownaudio.IdentifyGroup
import audio.own.kit.ownaudio.IdentifyProposal
import audio.own.kit.ownaudio.IdentifyQuote
import audio.own.kit.ownaudio.IdentifyRun
import audio.own.kit.ownaudio.IdentifyScope
import audio.own.kit.ownaudio.LibraryFolder
import audio.own.kit.ownaudio.OwnAudioClient
import audio.own.kit.ownaudio.OwnAudioException
import audio.own.kit.ownaudio.RecordingCandidate
import audio.own.kit.ownaudio.RecordingSearch
import audio.own.kit.ownaudio.acceptProposal
import audio.own.kit.ownaudio.acceptSureMatches
import audio.own.kit.ownaudio.chooseEdition
import audio.own.kit.ownaudio.chooseRecording
import audio.own.kit.ownaudio.deleteIdentifyRun
import audio.own.kit.ownaudio.identifyGroup
import audio.own.kit.ownaudio.identifyProposals
import audio.own.kit.ownaudio.identifyQuote
import audio.own.kit.ownaudio.identifyRun
import audio.own.kit.ownaudio.identifyRuns
import audio.own.kit.ownaudio.musicFolders
import audio.own.kit.ownaudio.proposalAlternatives
import audio.own.kit.ownaudio.rejectProposal
import audio.own.kit.ownaudio.revertIdentifyRun
import audio.own.kit.ownaudio.searchGroup
import audio.own.kit.ownaudio.searchProposal
import audio.own.kit.ownaudio.setKeepAlbum
import audio.own.kit.ownaudio.startIdentify
import audio.own.kit.ownaudio.undoIdentify
import audio.own.music.library.IdentifyAvailability
import audio.own.music.library.SourceError
import audio.own.music.library.ownaudio.OwnAudioSource
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// The parts of music-ios App/OwnAudioMusic/Identify/*.swift that are not views: the run list and
// starting a run (IdentifyRunsView, IdentifyAccess), one run's proposals and what is done with
// them (IdentifyRunView), searching for a recording (RecordingPicker). Every call throws
// SourceError; 402 is SourceError.PaymentRequired.

/** What [IdentifyRuns.begin] did: started at once, or wants the price confirmed first. */
sealed interface IdentifyStart {
    /** A server that charges quoted more than nothing: show it, then [IdentifyRuns.start]. */
    data class Confirm(val quote: IdentifyQuote, val scope: IdentifyScope) : IdentifyStart

    data class Started(val run: IdentifyRun) : IdentifyStart
}

/** The run list and starting a run. */
class IdentifyRuns(
    private val client: OwnAudioClient,
    val availability: IdentifyAvailability,
    private val signals: () -> Flow<IdentifySignal> = { IdentifyEvents.signals(client) },
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val _runs = MutableStateFlow<List<IdentifyRun>?>(null)

    /** Newest first; null until loaded. */
    val runs: StateFlow<List<IdentifyRun>?> = _runs.asStateFlow()

    private val _folders = MutableStateFlow<List<LibraryFolder>>(emptyList())

    /** The server's music folders a run can be limited to; empty where it has none to offer. */
    val folders: StateFlow<List<LibraryFolder>> = _folders.asStateFlow()

    val hasActiveRun: Boolean get() = _runs.value?.any { it.isActive } == true

    /** A failure after a first load keeps what was loaded and doesn't throw. */
    suspend fun reload() {
        try {
            _runs.value = identifyCall { client.identifyRuns() }
        } catch (e: SourceError) {
            if (_runs.value == null) throw e
        }
    }

    /** Servers with library folders (and an account that may see them) offer them; others none. */
    suspend fun loadFolders() {
        _folders.value = try {
            client.musicFolders()
        } catch (_: OwnAudioException) {
            emptyList()
        }
    }

    /** The whole library, or one of [folders]; [wholeLibrary] names it in the run list. */
    fun scope(folder: LibraryFolder?, wholeLibrary: String): IdentifyScope =
        if (folder == null) IdentifyScope.library(wholeLibrary) else IdentifyScope("folder", id = folder.id, label = folder.path)

    /** Quotes first where the server charges; starts at once where it is free. */
    suspend fun begin(scope: IdentifyScope): IdentifyStart {
        if (availability.charges) {
            val quote = identifyCall { client.identifyQuote(scope) }
            if (quote.chargeMicro > 0) return IdentifyStart.Confirm(quote, scope)
        }
        return IdentifyStart.Started(start(scope))
    }

    suspend fun start(scope: IdentifyScope): IdentifyRun {
        val run = identifyCall { client.startIdentify(scope) }
        runCatchingSource { reload() }
        return run
    }

    /** Loads, then keeps the list current until cancelled (see [IdentifyFollower]). */
    suspend fun follow() {
        runCatchingSource { reload() }
        loadFolders()
        IdentifyFollower(now).follow(signals(), runActive = { hasActiveRun }, reload = { runCatchingSource { reload() } })
    }
}

/** The tabs of a run, and what each asks the server for. */
enum class IdentifyTab(val proposalClass: String?, val decision: String?) {
    REVIEW("review", "pending"),
    SURE("sure", "pending"),
    NONE("none", "pending"),

    /** Accepted or rejected. */
    DONE(null, null),
}

/** One album's (or one group's) proposals, its songs by disc and number. */
data class ProposalGroup(val group: IdentifyProposal.Group, val proposals: List<IdentifyProposal>) {
    /** What the album's Accept All takes. */
    val acceptable: List<IdentifyProposal> get() = acceptable(proposals)

    /** The album the songs were matched to, for the group's header; null for other groups. */
    val albumMatch: IdentifyProposal.Match? get() =
        if (group.kind == "album") proposals.firstOrNull { it.proposal != null }?.proposal?.takeIf { it.album != null } else null

    companion object {
        /** By group in the server's order; within one, by disc and track number. */
        fun of(proposals: List<IdentifyProposal>): List<ProposalGroup> {
            val byGroup = LinkedHashMap<String, MutableList<IdentifyProposal>>()
            for (p in proposals) byGroup.getOrPut(p.group.id) { ArrayList() } += p
            return byGroup.values.map { items ->
                ProposalGroup(items.first().group, items.sortedWith(compareBy({ it.track.discNumber ?: 1 }, { it.track.trackNumber ?: 999 })))
            }
        }

        /**
         * Songs an album's Accept takes: pending, with a match, and not matched to another
         * artist, which is probably a wrong match to decide on its own.
         */
        fun acceptable(items: List<IdentifyProposal>): List<IdentifyProposal> =
            items.filter { it.decision == "pending" && it.proposal != null && "artist_differs" !in it.reasons }
    }
}

/** Recordings found, and the album left out to find them when nothing was on it. */
data class RecordingResults(
    val candidates: List<RecordingCandidate>,
    /** "Nothing on ‹album›": the results are from anywhere. Null when the album was kept. */
    val widenedFrom: String? = null,
)

/** One run: its proposals tab by tab, and accepting, rejecting and undoing them. */
class IdentifyRunReview(
    private val client: OwnAudioClient,
    val runId: String,
    private val signals: () -> Flow<IdentifySignal> = { IdentifyEvents.signals(client) },
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    data class State(
        val run: IdentifyRun? = null,
        val tab: IdentifyTab = IdentifyTab.REVIEW,
        val proposals: List<IdentifyProposal> = emptyList(),
        val isLoading: Boolean = true,
        /** The run was deleted (here or elsewhere): close the screen. */
        val isGone: Boolean = false,
    ) {
        val groups: List<ProposalGroup> get() = ProposalGroup.of(proposals)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** The run and every page of the tab's proposals. */
    suspend fun reload() {
        val tab = _state.value.tab
        try {
            val run = identifyCall { client.identifyRun(runId) }
            val all = ArrayList<IdentifyProposal>()
            var cursor: String? = null
            do {
                val page = identifyCall { client.identifyProposals(runId, tab.proposalClass, tab.decision, cursor) }
                all += page.items
                cursor = page.next
            } while (cursor != null)
            val shown = if (tab == IdentifyTab.DONE) all.filter { it.decision != "pending" } else all
            _state.update { if (it.tab == tab) it.copy(run = run, proposals = shown, isLoading = false) else it.copy(run = run) }
        } catch (_: SourceError.NotFound) {
            _state.update { it.copy(run = null, isGone = true, isLoading = false) }
        } catch (e: SourceError) {
            _state.update { it.copy(isLoading = false) }
            if (_state.value.run == null) throw e
        }
    }

    suspend fun show(tab: IdentifyTab) {
        _state.update { it.copy(tab = tab, proposals = emptyList(), isLoading = true) }
        reload()
    }

    /** How many songs were identified. */
    suspend fun acceptSure(): Int = act { client.acceptSureMatches(runId) }

    suspend fun accept(proposal: IdentifyProposal) = act { client.acceptProposal(proposal.id) }

    suspend fun reject(proposal: IdentifyProposal) = act { client.rejectProposal(proposal.id) }

    /** Keep the file's own album and set only the recording (a compilation's song). */
    suspend fun keepAlbum(proposal: IdentifyProposal, keep: Boolean) = act { client.setKeepAlbum(proposal.id, keep) }

    /** Puts an accepted song back as it was; false when it had not been identified after all. */
    suspend fun undo(proposal: IdentifyProposal): Boolean = act { undoTrack(client, proposal.track.id) }

    /** The group's [ProposalGroup.acceptable] songs, one after another; how many. */
    suspend fun acceptAll(group: ProposalGroup): Int = act {
        val items = group.acceptable
        for (p in items) client.acceptProposal(p.id)
        items.size
    }

    /** The album's editions, for Other Edition…. */
    suspend fun editions(groupId: String): IdentifyGroup = identifyCall { client.identifyGroup(groupId) }

    suspend fun chooseEdition(groupId: String, index: Int): IdentifyGroup = act { client.chooseEdition(groupId, index) }

    /** Searches the album's editions in the owner's words; blank ones are left out. */
    suspend fun searchEditions(groupId: String, album: String, artist: String): IdentifyGroup =
        act { client.searchGroup(groupId, words(album), words(artist)) }

    /** The other recordings the server keeps for the song, for Other Recording…. */
    suspend fun alternatives(proposal: IdentifyProposal): List<RecordingCandidate> = identifyCall { client.proposalAlternatives(proposal.id) }

    suspend fun searchRecording(proposal: IdentifyProposal, title: String, artist: String, album: String): RecordingResults =
        searchWidening(title, artist, album) { client.searchProposal(proposal.id, it) }

    /** A found recording, or a pasted musicbrainz.org link or id; accepted at once. */
    suspend fun chooseRecording(proposal: IdentifyProposal, recording: String, release: String? = null) =
        act { client.chooseRecording(proposal.id, recording.trim(), release, accept = true) }

    /** Undo Run: every accept of the run goes back; how many songs. */
    suspend fun revert(): Int = act { client.revertIdentifyRun(runId) }

    /** Stops the run and forgets its proposals; what was accepted stays. */
    suspend fun delete() {
        identifyCall { client.deleteIdentifyRun(runId) }
        _state.update { it.copy(isGone = true) }
    }

    /** Loads, then keeps the run current until cancelled (see [IdentifyFollower]). */
    suspend fun follow() {
        runCatchingSource { reload() }
        IdentifyFollower(now).follow(
            signals(),
            matching = { it.runId == runId },
            runActive = { _state.value.run?.isActive == true },
            reload = { runCatchingSource { reload() } },
        )
    }

    /** Every action reloads afterwards, failed or not: the server may have done part of it. */
    private suspend fun <T> act(work: suspend () -> T): T = try {
        identifyCall(work)
    } finally {
        runCatchingSource { reload() }
    }
}

internal fun words(text: String): String? = text.trim().ifEmpty { null }

/**
 * Searches in the owner's words. The album is often the tag that was wrong too: with nothing on
 * it, the search runs again without it.
 */
internal suspend fun searchWidening(
    title: String,
    artist: String,
    album: String,
    search: suspend (RecordingSearch) -> List<RecordingCandidate>,
): RecordingResults {
    val t = words(title)
    val a = words(artist)
    val onAlbum = words(album)
    val first = identifyCall { search(RecordingSearch(t, a, onAlbum)) }
    if (first.isNotEmpty() || onAlbum == null || (t == null && a == null)) return RecordingResults(first)
    val wider = identifyCall { search(RecordingSearch(t, a, null)) }
    return RecordingResults(wider, onAlbum.takeIf { wider.isNotEmpty() })
}

/** Undoes one song's identify; `409` (it never was) is false, not an error. */
internal suspend fun undoTrack(client: OwnAudioClient, trackId: String): Boolean = try {
    identifyCall { client.undoIdentify(trackId) }
    true
} catch (_: SourceError.Conflict) {
    false
}

internal suspend fun <T> identifyCall(work: suspend () -> T): T = try {
    work()
} catch (e: OwnAudioException) {
    throw OwnAudioSource.sourceError(e)
}

private suspend fun runCatchingSource(work: suspend () -> Unit) {
    try {
        work()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Shown by the next reload that works; following goes on.
    }
}
