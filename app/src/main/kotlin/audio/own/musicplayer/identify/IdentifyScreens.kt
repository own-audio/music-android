package audio.own.musicplayer.identify

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ManageSearch
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import audio.own.core.designsystem.components.ErrorAlert
import audio.own.core.designsystem.components.LoadStateView
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.core.designsystem.theme.tabular
import audio.own.kit.ownaudio.CoverArtArchive
import audio.own.kit.ownaudio.IdentifyGroup
import audio.own.kit.ownaudio.IdentifyProposal
import audio.own.kit.ownaudio.IdentifyQuote
import audio.own.kit.ownaudio.IdentifyRun
import audio.own.kit.ownaudio.RecordingCandidate
import audio.own.music.library.identify.IdentifyTab
import audio.own.music.library.identify.ProposalGroup
import audio.own.music.library.lengthFits
import audio.own.musicplayer.R
import audio.own.musicplayer.app.text
import audio.own.musicplayer.app.forView
import audio.own.musicplayer.library.ListPage
import audio.own.musicplayer.library.formatDuration
import audio.own.musicplayer.ui.ButtonRow
import audio.own.musicplayer.ui.NoteRow
import audio.own.musicplayer.ui.Page
import audio.own.musicplayer.ui.RowDivider
import audio.own.musicplayer.ui.Section
import coil3.compose.AsyncImage
import java.text.NumberFormat
import java.util.Currency
import kotlinx.coroutines.delay
import audio.own.core.strings.R as SR

private val Waiting = Color(0xFFFF9500)

// Identify Library

/**
 * Identify Library: Whole Library or one of the server's music folders, the start button, and
 * the runs with their state and counts. A price to confirm first where the server charges.
 */
@Composable
fun IdentifyRunsContent(
    state: IdentifyRunsState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onChoose: (audio.own.kit.ownaudio.LibraryFolder?) -> Unit,
    onStart: () -> Unit,
    onConfirm: () -> Unit,
    onCancelConfirm: () -> Unit,
    onRun: (IdentifyRun) -> Unit,
    onDismissError: () -> Unit,
) {
    val whole = stringResource(R.string.whole_library)
    val selected = stringResource(R.string.selected_songs)
    Page(title = stringResource(R.string.identify_library), onBack = onBack) {
        when (val runs = state.runs) {
            is audio.own.musicplayer.app.Load.Loaded -> {
                Section(header = null, footer = stringResource(R.string.identify_runs_footer)) {
                    if (state.folders.isNotEmpty()) {
                        ScopeRow(whole, selected = state.folder == null) { onChoose(null) }
                        for (folder in state.folders) {
                            RowDivider()
                            ScopeRow(folder.path, selected = state.folder?.id == folder.id) { onChoose(folder) }
                        }
                        RowDivider()
                    }
                    ButtonRow(
                        stringResource(if (state.folder == null) R.string.identify_whole_library else R.string.identify_this_folder),
                        onClick = onStart,
                        icon = Icons.Filled.ManageSearch,
                        color = LocalPalette.current.accent,
                        working = state.starting,
                        enabled = !state.starting,
                    )
                }
                if (runs.value.isNotEmpty()) {
                    Section(header = stringResource(R.string.runs)) {
                        runs.value.forEachIndexed { index, run ->
                            if (index > 0) RowDivider()
                            RunRow(run, run.title(whole, selected)) { onRun(run) }
                        }
                    }
                }
            }
            else -> Box(Modifier.fillMaxWidth().padding(Tokens.Spacing.xl), contentAlignment = Alignment.Center) {
                LoadStateView(runs.forView(), onRetry = onRetry) {}
            }
        }
    }
    state.confirm?.let { confirm ->
        AlertDialog(
            onDismissRequest = onCancelConfirm,
            text = { Text(identifyChargeText(confirm.quote)) },
            confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.start)) } },
            dismissButton = { TextButton(onClick = onCancelConfirm) { Text(stringResource(SR.string.cancel)) } },
        )
    }
    state.startError?.let { message ->
        AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text(stringResource(R.string.couldnt_start_identifying)) },
            text = { Text(message.text()) },
            confirmButton = { TextButton(onClick = onDismissError) { Text(stringResource(SR.string.ok)) } },
        )
    }
}

@Composable
private fun ScopeRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Tokens.minTarget)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = Tokens.Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        RadioButton(selected = selected, onClick = null)
    }
}

@Composable
private fun RunRow(run: IdentifyRun, title: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = Tokens.minTarget).clickable(onClick = onClick)
            .padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm)) {
            Text(title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            IdentifyStatusBadge(run.status)
        }
        RunProgress(run)
        Text(runSummary(run), style = MaterialTheme.typography.bodySmall, color = LocalPalette.current.muted)
    }
}

@Composable
private fun RunProgress(run: IdentifyRun) {
    if (run.isActive && run.groupsTotal > 0) {
        LinearProgressIndicator(progress = { run.groupsDone.toFloat() / run.groupsTotal }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun runSummary(run: IdentifyRun): String =
    stringResource(R.string.run_summary, run.counts.sure.toInt(), run.counts.review.toInt(), run.counts.none.toInt(), run.counts.accepted.toInt())

/** Queued / Identifying / Waiting / Ready / Failed / Cancelled, in a tinted capsule. */
@Composable
fun IdentifyStatusBadge(status: String) {
    val palette = LocalPalette.current
    val (title, color) = when (status) {
        "queued" -> R.string.run_queued to palette.accent
        "running" -> R.string.run_running to palette.accent
        "waiting" -> R.string.run_waiting to Waiting
        "ready" -> R.string.run_ready to palette.success
        "failed" -> R.string.run_failed to palette.error
        else -> R.string.run_cancelled to palette.muted
    }
    Text(
        stringResource(title),
        color = color,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
        modifier = Modifier.background(color.copy(alpha = 0.15f), CircleShape).padding(horizontal = Tokens.Spacing.sm, vertical = 2.dp),
    )
}

/** "12 songs to identify. This can cost up to $0.012: …" */
@Composable
fun identifyChargeText(quote: IdentifyQuote): String {
    val format = NumberFormat.getCurrencyInstance(androidx.compose.ui.platform.LocalConfiguration.current.locales[0]).apply {
        currency = Currency.getInstance("USD")
        minimumFractionDigits = 2
        maximumFractionDigits = 3
    }
    val price = format.format(quote.chargeMicro / 1_000_000.0)
    return pluralStringResource(R.plurals.identify_charge, quote.tracks, quote.tracks, price)
}

// One run

/** The tabs in their order, with their titles. */
private val TABS = listOf(
    IdentifyTab.REVIEW to R.string.tab_to_review,
    IdentifyTab.SURE to R.string.tab_sure,
    IdentifyTab.NONE to R.string.tab_not_found,
    IdentifyTab.DONE to R.string.tab_done,
)

/** What a run's actions call; every one reloads the run afterwards. */
class RunActions(
    val onTab: (IdentifyTab) -> Unit = {},
    val onAcceptSure: () -> Unit = {},
    val onAccept: (IdentifyProposal) -> Unit = {},
    val onReject: (IdentifyProposal) -> Unit = {},
    val onKeepAlbum: (IdentifyProposal, Boolean) -> Unit = { _, _ -> },
    val onUndo: (IdentifyProposal) -> Unit = {},
    val onAcceptAll: (ProposalGroup) -> Unit = {},
    val onOtherEdition: (ProposalGroup) -> Unit = {},
    val onOtherRecording: (IdentifyProposal) -> Unit = {},
    val onRevert: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onMessageShown: () -> Unit = {},
    val onDismissError: () -> Unit = {},
)

/**
 * A run: its state and progress, the tabs To Review / Sure / Not Found / Done, Accept … Sure
 * Matches, and the proposals grouped by album, each with its menu. More: Undo Run, Delete Run.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentifyRunContent(ui: IdentifyRunUi, onBack: () -> Unit, actions: RunActions) {
    val palette = LocalPalette.current
    val review = ui.review
    val run = review.run
    var more by remember { mutableStateOf(false) }
    var confirmRevert by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val title = run?.title(stringResource(R.string.whole_library), stringResource(R.string.selected_songs)).orEmpty()
    ListPage(title, onBack, actions = {
        if (run != null) {
            Box {
                IconButton(onClick = { more = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(SR.string.more)) }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    if (run.counts.accepted > 0) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.undo_run)) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null) },
                            onClick = { more = false; confirmRevert = true },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete_run), color = palette.error) },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = palette.error) },
                        onClick = { more = false; confirmDelete = true },
                    )
                }
            }
        }
    }) { inner ->
        Box(Modifier.padding(inner).fillMaxSize()) {
            when {
                run == null && review.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                run == null -> Text(stringResource(R.string.run_gone), color = palette.muted, modifier = Modifier.align(Alignment.Center).padding(Tokens.Spacing.xl))
                else -> RunList(ui, run, actions)
            }
            ui.message?.let { note ->
                LaunchedEffect(note) {
                    delay(2_500)
                    actions.onMessageShown()
                }
                Surface(
                    shape = CircleShape,
                    color = palette.backgroundAlt,
                    shadowElevation = 2.dp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = Tokens.Spacing.lg),
                ) {
                    Text(noteText(note), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.sm))
                }
            }
        }
    }
    if (confirmRevert) {
        Confirm(stringResource(R.string.undo_run_question), stringResource(R.string.undo_run), onDismiss = { confirmRevert = false }) { actions.onRevert() }
    }
    if (confirmDelete) {
        Confirm(stringResource(R.string.delete_run_question), stringResource(R.string.delete_run), onDismiss = { confirmDelete = false }) { actions.onDelete() }
    }
    ui.error?.let { ErrorAlert(it.text(), onDismiss = actions.onDismissError) }
}

@Composable
private fun noteText(note: RunNote): String = when (note) {
    is RunNote.Identified -> pluralStringResource(R.plurals.songs_identified, note.count, note.count)
    is RunNote.PutBack -> pluralStringResource(R.plurals.songs_put_back, note.count, note.count)
    RunNote.SongPutBack -> stringResource(R.string.put_back)
    RunNote.NeverIdentified -> stringResource(R.string.not_identified)
    RunNote.SongIdentified -> stringResource(R.string.song_identified)
}

/** A destructive question with its one button, as the iOS confirmation dialogs. */
@Composable
fun Confirm(question: String, action: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(question) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(action, color = LocalPalette.current.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(SR.string.cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RunList(ui: IdentifyRunUi, run: IdentifyRun, actions: RunActions) {
    val palette = LocalPalette.current
    val review = ui.review
    val groups = ui.groups
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "status") {
            Column(Modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.screenEdge, vertical = Tokens.Spacing.sm), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm)) {
                IdentifyStatusBadge(run.status)
                RunProgress(run)
                Text(runSummary(run), style = MaterialTheme.typography.bodySmall, color = palette.muted)
                if (run.status == "waiting") Text(stringResource(R.string.run_waiting_note), style = MaterialTheme.typography.bodySmall, color = palette.muted)
                if (run.status == "failed") run.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = palette.error) }
            }
        }
        item(key = "tabs") {
            val index = TABS.indexOfFirst { it.first == review.tab }.coerceAtLeast(0)
            SecondaryScrollableTabRow(selectedTabIndex = index, edgePadding = Tokens.Spacing.screenEdge, containerColor = MaterialTheme.colorScheme.background) {
                TABS.forEachIndexed { i, (tab, title) ->
                    Tab(selected = i == index, onClick = { actions.onTab(tab) }, text = { Text(stringResource(title), maxLines = 1) })
                }
            }
        }
        if (review.tab == IdentifyTab.SURE && review.proposals.isNotEmpty()) {
            item(key = "accept-sure") {
                ButtonRow(
                    pluralStringResource(R.plurals.accept_sure_matches, review.proposals.size, review.proposals.size),
                    onClick = actions.onAcceptSure,
                    icon = Icons.Filled.CheckCircle,
                    color = palette.accent,
                    enabled = !ui.busy,
                )
            }
        }
        if (review.proposals.isEmpty() && !review.isLoading) {
            item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(Tokens.Spacing.screenEdge), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm)) {
                    Text(stringResource(emptyText(review.tab)), color = palette.muted)
                    if (run.isActive) Text(stringResource(R.string.still_looking), style = MaterialTheme.typography.bodySmall, color = palette.muted)
                }
            }
        }
        if (review.proposals.isEmpty() && review.isLoading) {
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(Tokens.Spacing.xl), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
        for (group in groups) {
            item(key = "g-${group.group.id}") { GroupHeader(group, ui.busy, actions) }
            items(group.proposals, key = { "p-${it.id}" }) { proposal ->
                ProposalRow(proposal, ui.busy, actions)
                HorizontalDivider(Modifier.padding(start = Tokens.Spacing.screenEdge), color = palette.border)
            }
        }
    }
}

private fun emptyText(tab: IdentifyTab): Int = when (tab) {
    IdentifyTab.REVIEW -> R.string.nothing_to_review
    IdentifyTab.SURE -> R.string.no_sure_matches
    IdentifyTab.NONE -> R.string.nothing_unfound
    IdentifyTab.DONE -> R.string.nothing_decided
}

@Composable
private fun GroupHeader(group: ProposalGroup, busy: Boolean, actions: RunActions) {
    val palette = LocalPalette.current
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(palette.backgroundAlt).padding(start = Tokens.Spacing.screenEdge, top = Tokens.Spacing.xs, bottom = Tokens.Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).semantics { heading() }) {
            Text(group.group.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            group.albumMatch?.let { match ->
                val album = match.album.orEmpty()
                Text(match.year?.let { "→ $album ($it)" } ?: "→ $album", style = MaterialTheme.typography.labelSmall, color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (group.group.kind == "album") {
            Box {
                IconButton(onClick = { menu = true }, enabled = !busy) { Icon(Icons.Filled.MoreHoriz, contentDescription = stringResource(R.string.album_actions)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    val count = group.acceptable.size
                    if (count > 1) {
                        DropdownMenuItem(
                            text = { Text(pluralStringResource(R.plurals.accept_all_songs, count, count)) },
                            leadingIcon = { Icon(Icons.Filled.Check, contentDescription = null) },
                            onClick = { menu = false; actions.onAcceptAll(group) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.other_edition)) },
                        leadingIcon = { Icon(Icons.Filled.Album, contentDescription = null) },
                        onClick = { menu = false; actions.onOtherEdition(group) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProposalRow(proposal: IdentifyProposal, busy: Boolean, actions: RunActions) {
    val palette = LocalPalette.current
    var menu by remember { mutableStateOf(false) }
    val match = proposal.proposal
    val pending = proposal.decision == "pending"
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = { menu = true }, onLongClick = { menu = true })
            .padding(start = Tokens.Spacing.screenEdge, top = Tokens.Spacing.sm, bottom = Tokens.Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm)) {
                Text(position(proposal.track.discNumber, proposal.track.trackNumber), style = MaterialTheme.typography.labelMedium.tabular(), color = palette.muted)
                Text(proposal.track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (match != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm)) {
                    CoverThumb(if (proposal.keepAlbum) emptyList() else CoverArtArchive.fronts(match.mbReleaseId, match.mbReleaseGroupId), 36.dp)
                    Column {
                        Text(match.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val line = listOfNotNull(match.artist, if (proposal.keepAlbum) proposal.track.album else match.album, match.year?.toString()).joinToString(" · ")
                        Text(line, style = MaterialTheme.typography.bodySmall, color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.nothing_found), style = MaterialTheme.typography.bodySmall, color = palette.muted, modifier = Modifier.weight(1f))
                    if (proposal.decision != "accepted") {
                        TextButton(onClick = { actions.onOtherRecording(proposal) }, enabled = !busy) { Text(stringResource(R.string.search_ellipsis)) }
                    }
                }
            }
            if (proposal.reasons.isNotEmpty()) {
                Text(proposal.reasons.map { reasonTitle(it) }.joinToString(" · "), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium), color = Waiting)
            }
            if (match != null && pending && ("album_differs" in proposal.reasons || proposal.keepAlbum)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.keep_it_on, proposal.track.album.orEmpty()), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Switch(checked = proposal.keepAlbum, onCheckedChange = { actions.onKeepAlbum(proposal, it) }, enabled = !busy)
                }
            }
            if (!pending) {
                val accepted = proposal.decision == "accepted"
                Text(
                    stringResource(if (accepted) R.string.accepted else R.string.rejected),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                    color = if (accepted) palette.success else palette.muted,
                )
            }
        }
        Box {
            IconButton(onClick = { menu = true }, enabled = !busy) { Icon(Icons.Filled.MoreHoriz, contentDescription = stringResource(SR.string.more), tint = palette.muted) }
            DropdownMenu(expanded = menu && !busy, onDismissRequest = { menu = false }) {
                if (pending) {
                    if (match != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.accept)) },
                            leadingIcon = { Icon(Icons.Filled.Check, contentDescription = null) },
                            onClick = { menu = false; actions.onAccept(proposal) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.reject)) },
                        leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null) },
                        onClick = { menu = false; actions.onReject(proposal) },
                    )
                } else if (proposal.decision == "accepted") {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.undo_identify)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null) },
                        onClick = { menu = false; actions.onUndo(proposal) },
                    )
                }
                if (proposal.decision != "accepted") {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.other_recording)) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        onClick = { menu = false; actions.onOtherRecording(proposal) },
                    )
                }
            }
        }
    }
}

/** "1-04" for a song on a known disc, "4" without one. */
private fun position(disc: Int?, track: Int?): String {
    track ?: return ""
    disc ?: return track.toString()
    return "$disc-${track.toString().padStart(2, '0')}"
}

@Composable
private fun reasonTitle(code: String): String = when (code) {
    "incomplete" -> stringResource(R.string.reason_incomplete)
    "partial" -> stringResource(R.string.reason_partial)
    "layout" -> stringResource(R.string.reason_layout)
    "duplicate" -> stringResource(R.string.reason_duplicate)
    "ambiguous" -> stringResource(R.string.reason_ambiguous)
    "album_differs" -> stringResource(R.string.reason_album_differs)
    "artist_differs" -> stringResource(R.string.reason_artist_differs)
    "weak" -> stringResource(R.string.reason_weak)
    "not_found" -> stringResource(R.string.reason_not_found)
    "not_on_release" -> stringResource(R.string.reason_not_on_release)
    "chosen" -> stringResource(R.string.reason_chosen)
    else -> code
}

/**
 * A match's cover from the Cover Art Archive, small: the first of [urls] that loads, else a
 * plain placeholder. People tell editions and recordings apart by their cover first.
 */
@Composable
fun CoverThumb(urls: List<String>, size: Dp = 44.dp) {
    val palette = LocalPalette.current
    var index by remember(urls) { mutableIntStateOf(0) }
    Box(Modifier.size(size).clip(RoundedCornerShape(4.dp)).background(palette.backgroundAlt), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.MusicNote, contentDescription = null, tint = palette.muted, modifier = Modifier.size(size * 0.45f))
        urls.getOrNull(index)?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { index += 1 },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// Other Edition…

/** The album's editions the server found, a search in the owner's words, and choosing one. */
@Composable
fun EditionsContent(
    state: EditionsState,
    label: String,
    onDone: () -> Unit,
    onAlbum: (String) -> Unit,
    onArtist: (String) -> Unit,
    onSearch: () -> Unit,
    onChoose: (Int) -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
) {
    val palette = LocalPalette.current
    Page(title = label, onBack = onDone, closeIsCancel = true) {
        Section(header = null, footer = stringResource(R.string.editions_footer)) {
            Field(stringResource(R.string.album), state.album, onAlbum)
            Field(stringResource(R.string.artist), state.artist, onArtist, onDone = onSearch)
            ButtonRow(
                stringResource(R.string.search_musicbrainz),
                onClick = onSearch,
                icon = Icons.Filled.Search,
                color = palette.accent,
                working = state.searching,
                enabled = !state.searching && (state.album.isNotBlank() || state.artist.isNotBlank()),
            )
        }
        when (val group = state.group) {
            is audio.own.musicplayer.app.Load.Loaded -> {
                val detail = group.value
                if (detail.chosen == null) {
                    Text(stringResource(R.string.no_single_release), style = MaterialTheme.typography.bodySmall, color = palette.muted, modifier = Modifier.padding(horizontal = Tokens.Spacing.screenEdge * 2, vertical = Tokens.Spacing.sm))
                }
                Section(header = null) {
                    if (detail.editions.isEmpty()) NoteRow(stringResource(R.string.no_other_editions))
                    detail.editions.forEachIndexed { i, edition ->
                        if (i > 0) RowDivider(start = 72.dp)
                        EditionRow(edition, inUse = detail.chosen == edition.index, choosing = state.choosing == edition.index, enabled = state.choosing == null) { onChoose(edition.index) }
                    }
                }
            }
            else -> Box(Modifier.fillMaxWidth().padding(Tokens.Spacing.xl), contentAlignment = Alignment.Center) {
                LoadStateView(group.forView(), onRetry = onRetry) {}
            }
        }
    }
    state.error?.let { ErrorAlert(it.text(), onDismiss = onDismissError) }
}

@Composable
private fun EditionRow(edition: IdentifyGroup.Edition, inUse: Boolean, choosing: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = Tokens.minTarget)
            .then(if (!inUse && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
    ) {
        CoverThumb(CoverArtArchive.fronts(edition.mbReleaseId, edition.mbReleaseGroupId))
        Column(Modifier.weight(1f)) {
            Text(edition.year?.let { "${edition.title} ($it)" } ?: edition.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val parts = listOf(stringResource(R.string.edition_tracks, edition.trackCount, edition.matched)) +
                edition.secondaryTypes + listOfNotNull(edition.status?.takeIf { it != "Official" })
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = palette.muted)
        }
        when {
            inUse -> Text(stringResource(R.string.in_use), style = MaterialTheme.typography.labelMedium, color = palette.accent)
            choosing -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
    }
}

// The recording picker

/**
 * Pick the MusicBrainz recording a song is: the fields filled from its tags, Search MusicBrainz,
 * the recordings found with their covers, or a pasted link with Use This Recording.
 */
@Composable
fun RecordingPickerContent(
    state: RecordingPickerState,
    songTitle: String,
    songMs: Long?,
    onCancel: () -> Unit,
    onTitle: (String) -> Unit,
    onArtist: (String) -> Unit,
    onAlbum: (String) -> Unit,
    onSearch: () -> Unit,
    onPick: (RecordingCandidate) -> Unit,
    onLink: (String) -> Unit,
    onUseLink: () -> Unit,
    onDismissError: () -> Unit,
) {
    val palette = LocalPalette.current
    Page(title = songTitle, onBack = onCancel, closeIsCancel = true) {
        Section(header = stringResource(R.string.search), footer = stringResource(R.string.picker_footer)) {
            Field(stringResource(R.string.title), state.title, onTitle)
            Field(stringResource(R.string.artist), state.artist, onArtist)
            Field(stringResource(R.string.album), state.album, onAlbum, onDone = onSearch)
            ButtonRow(
                stringResource(R.string.search_musicbrainz),
                onClick = onSearch,
                icon = Icons.Filled.Search,
                color = palette.accent,
                working = state.searching,
                enabled = !state.searching && !state.isBlank,
            )
        }
        Section(header = stringResource(R.string.recordings)) {
            if (state.results.isEmpty()) {
                NoteRow(stringResource(if (state.searched) R.string.nothing_found_try else R.string.nothing_found_yet))
            } else {
                state.widenedFrom?.let { NoteRow(stringResource(R.string.nothing_on_album, it)) }
            }
            state.results.forEachIndexed { i, candidate ->
                if (i > 0 || state.widenedFrom != null) RowDivider(start = 72.dp)
                CandidateRow(candidate, songMs, choosing = state.choosing == candidate.key, enabled = state.choosing == null) { onPick(candidate) }
            }
        }
        Section(header = stringResource(R.string.or_paste_a_link), footer = stringResource(R.string.paste_link_footer)) {
            Field(stringResource(R.string.recording_link_placeholder), state.link, onLink, keyboard = KeyboardType.Uri, onDone = onUseLink)
            ButtonRow(
                stringResource(R.string.use_this_recording),
                onClick = onUseLink,
                color = palette.accent,
                working = state.choosing == RecordingPickerViewModel.LINK,
                enabled = state.link.isNotBlank() && state.choosing == null,
            )
        }
    }
    state.error?.let { ErrorAlert(it.text(), onDismiss = onDismissError) }
}

@Composable
private fun CandidateRow(candidate: RecordingCandidate, songMs: Long?, choosing: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = Tokens.minTarget)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
    ) {
        CoverThumb(CoverArtArchive.fronts(candidate.mbReleaseId, null))
        Column(Modifier.weight(1f)) {
            Text(candidate.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val line = listOfNotNull(candidate.artist, candidate.album, candidate.year?.toString()).joinToString(" · ")
            Text(line, style = MaterialTheme.typography.bodySmall, color = palette.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (choosing) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            candidate.durationMs?.let { ms ->
                Text(
                    formatDuration(ms / 1000),
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    color = if (candidate.lengthFits(songMs)) palette.muted else Waiting,
                )
            }
        }
    }
}

/** A text field in a card, with a clear button: the tags fill these, and a fix usually starts anew. */
@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, keyboard: KeyboardType = KeyboardType.Text, onDone: (() -> Unit)? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        trailingIcon = if (value.isEmpty()) null else ({
            IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Cancel, contentDescription = stringResource(R.string.clear)) }
        }),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = if (onDone != null) ImeAction.Search else ImeAction.Next),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onDone?.invoke() }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.xs),
    )
}
