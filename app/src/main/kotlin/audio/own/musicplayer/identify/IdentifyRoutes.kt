package audio.own.musicplayer.identify

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import audio.own.core.servers.CompositeId
import audio.own.kit.ownaudio.IdentifyProposal
import audio.own.music.library.IdentifyAvailability
import audio.own.music.library.IdentifySource
import audio.own.music.library.identify.ProposalGroup
import audio.own.music.library.model.Song
import audio.own.musicplayer.R
import audio.own.musicplayer.app.text
import audio.own.musicplayer.library.OwnAudioActions
import audio.own.musicplayer.library.OwnAudioRequest
import kotlinx.coroutines.launch
import audio.own.core.strings.R as SR

/** Identify Library, from the Library tab or a server's page. */
@Composable
fun IdentifyRunsRoute(source: IdentifySource, availability: IdentifyAvailability, onBack: () -> Unit, onOpenRun: (String) -> Unit) {
    val whole = stringResource(R.string.whole_library)
    val vm = viewModel(key = "identify-runs-${source.serverId}") { IdentifyRunsViewModel(source.identifyRuns(availability), whole) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.opened) {
        state.opened?.let {
            vm.openedShown()
            onOpenRun(it)
        }
    }
    // Back from a run: what was done there shows in its counts.
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose {}
    }
    IdentifyRunsContent(
        state = state,
        onBack = onBack,
        onRetry = vm::retry,
        onChoose = vm::choose,
        onStart = vm::start,
        onConfirm = vm::confirmStart,
        onCancelConfirm = vm::cancelConfirm,
        onRun = { onOpenRun(it.id) },
        onDismissError = vm::dismissError,
    )
}

/** One run, with Other Edition… and the recording picker over it. Closes once the run is gone. */
@Composable
fun IdentifyRunRoute(source: IdentifySource, runId: String, onBack: () -> Unit, onChanged: () -> Unit) {
    val vm = viewModel(key = "identify-run-$runId") { IdentifyRunViewModel(source.identifyRun(runId), onChanged) }
    val ui by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(ui.review.isGone) { if (ui.review.isGone) onBack() }
    var editions by remember { mutableStateOf<ProposalGroup?>(null) }
    var recording by remember { mutableStateOf<IdentifyProposal?>(null) }
    IdentifyRunContent(
        ui = ui,
        onBack = onBack,
        actions = RunActions(
            onTab = vm::show,
            onAcceptSure = vm::acceptSure,
            onAccept = vm::accept,
            onReject = vm::reject,
            onKeepAlbum = vm::keepAlbum,
            onUndo = vm::undo,
            onAcceptAll = vm::acceptAll,
            onOtherEdition = { editions = it },
            onOtherRecording = { recording = it },
            onRevert = vm::revert,
            onDelete = vm::delete,
            onMessageShown = vm::messageShown,
            onDismissError = vm::dismissError,
        ),
    )
    editions?.let { group ->
        val close = { editions = null }
        FullScreen(close) {
            val first = group.proposals.firstOrNull()?.track
            val evm = viewModel(key = "editions-${group.group.id}") {
                EditionsViewModel(vm.review, group.group.id, first?.album.orEmpty(), first?.artist.orEmpty(), onChanged = vm::editionChanged)
            }
            val state by evm.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.done) { if (state.done) close() }
            EditionsContent(
                state = state,
                label = group.group.label,
                onDone = close,
                onAlbum = evm::setAlbum,
                onArtist = evm::setArtist,
                onSearch = evm::search,
                onChoose = evm::choose,
                onRetry = evm::load,
                onDismissError = evm::dismissError,
            )
        }
    }
    recording?.let { proposal ->
        val close = { recording = null }
        val review = vm.review
        RecordingPickerOverlay(
            key = "proposal-${proposal.id}",
            title = proposal.track.title,
            artist = proposal.track.artist,
            album = proposal.track.album,
            songMs = proposal.track.durationSecs?.let { it * 1000L },
            initial = { review.alternatives(proposal) },
            search = { t, a, al -> review.searchRecording(proposal, t, a, al) },
            choose = { recordingId, release ->
                review.chooseRecording(proposal, recordingId, release)
                vm.recordingChosen()
            },
            onClose = close,
        )
    }
}

@Composable
private fun RecordingPickerOverlay(
    key: String,
    title: String,
    artist: String?,
    album: String?,
    songMs: Long?,
    initial: suspend () -> List<audio.own.kit.ownaudio.RecordingCandidate> = { emptyList() },
    search: suspend (String, String, String) -> audio.own.music.library.identify.RecordingResults,
    choose: suspend (String, String?) -> Unit,
    onClose: () -> Unit,
) {
    FullScreen(onClose) {
        val vm = viewModel(key = "picker-$key") { RecordingPickerViewModel(title, artist, album, initial, search, choose) }
        val state by vm.state.collectAsStateWithLifecycle()
        LaunchedEffect(state.done) { if (state.done) onClose() }
        RecordingPickerContent(
            state = state,
            songTitle = title,
            songMs = songMs,
            onCancel = onClose,
            onTitle = vm::setTitle,
            onArtist = vm::setArtist,
            onAlbum = vm::setAlbum,
            onSearch = vm::runSearch,
            onPick = vm::pick,
            onLink = vm::setLink,
            onUseLink = vm::useLink,
            onDismissError = vm::dismissError,
        )
    }
}

/** A page over everything, as the iOS sheets: the picker, an edition, a run just started. */
@Composable
fun FullScreen(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = MaterialTheme.colorScheme.background) { content() }
    }
}

/**
 * What the song menus and the player ask for, over whichever page asked: the recording picker,
 * Undo Identify's question, a run's price, a run just started, and why one didn't start.
 */
@Composable
fun OwnAudioOverlays(actions: OwnAudioActions, onError: (Throwable) -> Unit) {
    val request by actions.request.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    when (val r = request) {
        null -> Unit
        is OwnAudioRequest.IdentifySong -> {
            val song = r.song
            val source = serverOf(song)?.let(actions::identifySource)
            if (source == null) {
                LaunchedEffect(Unit) { actions.dismiss() }
            } else {
                RecordingPickerOverlay(
                    key = "song-${song.id}",
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    songMs = song.durationMs,
                    search = { t, a, al -> source.searchRecordings(song.id, t, a, al) },
                    choose = { recordingId, release ->
                        source.identifySong(song.id, recordingId, release)
                        actions.changed(song.id)
                    },
                    onClose = actions::dismiss,
                )
            }
        }
        is OwnAudioRequest.UndoIdentify -> Confirm(stringResource(R.string.undo_identify_question), stringResource(R.string.undo_identify), onDismiss = actions::dismiss) {
            scope.launch { runCatching { actions.undoIdentify(r.song) }.onFailure(onError) }
        }
        is OwnAudioRequest.Confirm -> AlertDialog(
            onDismissRequest = actions::dismiss,
            text = { Text(identifyChargeText(r.quote)) },
            confirmButton = { TextButton(onClick = { actions.startConfirmed(r) }) { Text(stringResource(R.string.start)) } },
            dismissButton = { TextButton(onClick = actions::dismiss) { Text(stringResource(SR.string.cancel)) } },
        )
        is OwnAudioRequest.OpenRun -> {
            val source = actions.identifySource(r.serverId)
            if (source == null) {
                LaunchedEffect(Unit) { actions.dismiss() }
            } else {
                FullScreen(actions::dismiss) {
                    IdentifyRunRoute(source, r.runId, onBack = actions::dismiss, onChanged = { actions.changedServer(r.serverId) })
                }
            }
        }
        is OwnAudioRequest.StartFailed -> AlertDialog(
            onDismissRequest = actions::dismiss,
            title = { Text(stringResource(R.string.couldnt_start_identifying)) },
            text = { Text(r.message.text()) },
            confirmButton = { TextButton(onClick = actions::dismiss) { Text(stringResource(SR.string.ok)) } },
        )
    }
}

private fun serverOf(song: Song): String? = CompositeId.split(song.id)?.first
