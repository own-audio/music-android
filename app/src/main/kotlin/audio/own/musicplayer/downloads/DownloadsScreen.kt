package audio.own.musicplayer.downloads

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import audio.own.core.designsystem.components.CoverArt
import audio.own.core.designsystem.components.EmptyState
import audio.own.core.designsystem.theme.AppBrand
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.core.designsystem.theme.tabular
import audio.own.core.downloads.DownloadsSnapshot
import audio.own.music.library.model.Song
import audio.own.musicplayer.R
import audio.own.musicplayer.library.ListPage
import audio.own.musicplayer.library.PlayShuffleButtons
import audio.own.musicplayer.library.SongRow
import audio.own.musicplayer.library.rememberCover
import audio.own.core.strings.R as SR

/** An album or playlist in the Downloads list. [serverName] null: its server was removed. */
data class DownloadedCollectionRow(
    val id: String,
    val name: String,
    val coverId: String?,
    val serverName: String?,
    val done: Int,
    val total: Int,
)

data class DownloadedSongRow(val song: Song, val serverName: String?, val bytes: Long)

/** What the Downloads tab shows, all from the index: it works with no network. */
data class DownloadsContentState(
    val totalBytes: Long = 0,
    val pending: Int = 0,
    val failed: Int = 0,
    val albums: List<DownloadedCollectionRow> = emptyList(),
    val playlists: List<DownloadedCollectionRow> = emptyList(),
    val songs: List<DownloadedSongRow> = emptyList(),
) {
    val isEmpty: Boolean get() = albums.isEmpty() && playlists.isEmpty() && songs.isEmpty() && pending == 0 && failed == 0
    val hasDownloads: Boolean get() = albums.isNotEmpty() || playlists.isNotEmpty() || songs.isNotEmpty()

    companion object {
        fun of(snapshot: DownloadsSnapshot, serverName: (String) -> String?): DownloadsContentState {
            fun row(view: DownloadsSnapshot.CollectionView) = DownloadedCollectionRow(
                id = view.collection.id,
                name = view.collection.name,
                coverId = view.collection.coverId,
                serverName = serverName(view.collection.serverId),
                done = view.keys.count(snapshot::isDownloaded),
                total = view.keys.size,
            )
            return DownloadsContentState(
                totalBytes = snapshot.totalBytes,
                pending = snapshot.pendingCount,
                failed = snapshot.failedCount,
                albums = snapshot.collections.filter { it.collection.kind == DownloadsModel.ALBUM }.map(::row),
                playlists = snapshot.collections.filter { it.collection.kind == DownloadsModel.PLAYLIST }.map(::row),
                songs = snapshot.loose.mapNotNull { e -> e.song()?.let { DownloadedSongRow(it, serverName(e.serverId), e.bytes) } },
            )
        }
    }
}

/**
 * What is on the device (`Library/DownloadsView.swift`): On This Device, Downloading, Retry N
 * Failed; Albums, Playlists, Songs, each row with its server; swipe or the menu to remove;
 * Remove All in the bar.
 */
@Composable
fun DownloadsContent(
    state: DownloadsContentState,
    onBack: (() -> Unit)?,
    onRetryFailed: () -> Unit,
    onOpen: (collectionId: String) -> Unit,
    onPlaySongs: (songs: List<Song>, startId: String) -> Unit,
    onRemoveCollection: (String) -> Unit,
    onRemoveSong: (String) -> Unit,
    onRemoveAll: () -> Unit,
) {
    var confirmAll by remember { mutableStateOf(false) }
    val palette = LocalPalette.current
    val context = LocalContext.current
    ListPage(
        title = stringResource(R.string.downloads),
        onBack = onBack,
        actions = {
            if (state.hasDownloads) TextButton(onClick = { confirmAll = true }) { Text(stringResource(R.string.remove_all), color = palette.error) }
        },
    ) { inner ->
        if (state.isEmpty) {
            Box(Modifier.padding(inner).fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(Icons.Filled.DownloadForOffline, stringResource(R.string.nothing_downloaded), stringResource(R.string.downloads_empty_text))
            }
            return@ListPage
        }
        LazyColumn(Modifier.padding(inner).fillMaxSize()) {
            item(key = "summary") {
                Column(Modifier.padding(vertical = Tokens.Spacing.sm)) {
                    InfoRow(stringResource(R.string.on_this_device), Formatter.formatFileSize(context, state.totalBytes))
                    if (state.pending > 0) InfoRow(stringResource(R.string.downloading), state.pending.toString())
                    if (state.failed > 0) {
                        Text(
                            stringResource(R.string.retry_n_failed, state.failed),
                            color = palette.accent,
                            modifier = Modifier.fillMaxWidth().clickable(onClick = onRetryFailed).heightIn(min = Tokens.minTarget)
                                .padding(horizontal = Tokens.Spacing.screenEdge, vertical = Tokens.Spacing.md),
                        )
                    }
                }
            }
            if (state.albums.isNotEmpty()) {
                item(key = "albums") { Header(stringResource(R.string.albums)) }
                items(state.albums, key = { it.id }) { row ->
                    Removable(onRemove = { onRemoveCollection(row.id) }) { CollectionRowView(row, Icons.Filled.Album, { onOpen(row.id) }) { onRemoveCollection(row.id) } }
                }
            }
            if (state.playlists.isNotEmpty()) {
                item(key = "playlists") { Header(stringResource(R.string.playlists)) }
                items(state.playlists, key = { it.id }) { row ->
                    Removable(onRemove = { onRemoveCollection(row.id) }) { CollectionRowView(row, Icons.Filled.MusicNote, { onOpen(row.id) }) { onRemoveCollection(row.id) } }
                }
            }
            if (state.songs.isNotEmpty()) {
                item(key = "songs") { Header(stringResource(R.string.songs)) }
                val songs = state.songs.map { it.song }
                items(state.songs, key = { it.song.id }) { row ->
                    Removable(onRemove = { onRemoveSong(row.song.id) }) { LooseSongRow(row, onClick = { onPlaySongs(songs, row.song.id) }) { onRemoveSong(row.song.id) } }
                }
            }
        }
    }
    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text(stringResource(R.string.remove_all_downloads_question)) },
            text = { Text(stringResource(R.string.remove_all_downloads_message)) },
            confirmButton = { TextButton(onClick = { confirmAll = false; onRemoveAll() }) { Text(stringResource(R.string.remove_all_downloads), color = palette.error) } },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text(stringResource(SR.string.cancel)) } },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Tokens.minTarget).padding(horizontal = Tokens.Spacing.screenEdge, vertical = Tokens.Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Text(value, color = LocalPalette.current.muted, style = MaterialTheme.typography.bodyLarge.tabular())
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = LocalPalette.current.muted,
        modifier = Modifier.padding(start = Tokens.Spacing.screenEdge, top = Tokens.Spacing.lg, bottom = Tokens.Spacing.xs).semantics { heading() },
    )
}

/** Swipe from the end to remove, as the iOS list's swipe. */
@Composable
private fun Removable(onRemove: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState()
    audio.own.musicplayer.ui.SwipeActions(state) { if (it == SwipeToDismissBoxValue.EndToStart) onRemove() }
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(LocalPalette.current.error).padding(horizontal = Tokens.Spacing.screenEdge), contentAlignment = Alignment.CenterEnd) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(SR.string.remove_download), tint = MaterialTheme.colorScheme.onError)
            }
        },
    ) {
        Box(Modifier.background(MaterialTheme.colorScheme.background)) { content() }
    }
}

/** The server a download came from, small, under its title; "Removed server" for leftovers. */
@Composable
fun DownloadSource(serverName: String?) {
    val palette = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(Icons.Filled.Dns, contentDescription = null, tint = palette.muted, modifier = Modifier.size(12.dp))
        Text(serverName ?: stringResource(R.string.removed_server), style = MaterialTheme.typography.bodySmall, color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CollectionRowView(row: DownloadedCollectionRow, placeholder: ImageVector, onClick: () -> Unit, onRemove: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = Tokens.Spacing.screenEdge, top = Tokens.Spacing.sm, bottom = Tokens.Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
    ) {
        CoverArt(rememberCover(row.coverId, 56.dp), 56.dp, placeholder, radius = AppBrand.radii.rowCover)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            DownloadSource(row.serverName)
            Text(
                if (row.done == row.total) pluralStringResource(R.plurals.songs_count, row.total, row.total) else stringResource(R.string.n_of_m_downloaded, row.done, row.total),
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
            )
        }
        RemoveMenu(onRemove)
    }
}

@Composable
private fun LooseSongRow(row: DownloadedSongRow, onClick: () -> Unit, onRemove: () -> Unit) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = Tokens.Spacing.screenEdge, top = Tokens.Spacing.xs, bottom = Tokens.Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
    ) {
        CoverArt(rememberCover(row.song.coverArt ?: row.song.albumId, 44.dp), 44.dp, Icons.Filled.MusicNote, radius = AppBrand.radii.rowCover)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            row.song.artist?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            DownloadSource(row.serverName)
        }
        Text(Formatter.formatShortFileSize(context, row.bytes), style = MaterialTheme.typography.labelSmall, color = palette.muted)
        RemoveMenu(onRemove)
    }
}

/** The swipe's action in a menu, for a pointer and for TalkBack. */
@Composable
private fun RemoveMenu(onRemove: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(SR.string.more), tint = LocalPalette.current.muted) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(SR.string.remove_download)) },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = { open = false; onRemove() },
            )
        }
    }
}

/** A downloaded album or playlist, playable offline: only what is on the phone, in its order. */
@Composable
fun DownloadedCollectionContent(
    name: String?,
    artist: String?,
    coverId: String?,
    songs: List<Song>,
    onBack: () -> Unit,
    onPlay: (startId: String?, shuffle: Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val palette = LocalPalette.current
    ListPage(
        title = name ?: stringResource(R.string.removed),
        onBack = onBack,
        actions = {
            if (name != null) IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, contentDescription = stringResource(SR.string.remove_download)) }
        },
    ) { inner ->
        if (name == null) {
            Box(Modifier.padding(inner).fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(Icons.Filled.DownloadForOffline, stringResource(R.string.removed), null)
            }
            return@ListPage
        }
        LazyColumn(Modifier.padding(inner).fillMaxSize()) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.screenEdge, vertical = Tokens.Spacing.sm),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
                ) {
                    CoverArt(rememberCover(coverId, 200.dp), 200.dp, Icons.Filled.Album, radius = AppBrand.radii.cover)
                    Text(name, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
                    artist?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = palette.muted, textAlign = TextAlign.Center) }
                    PlayShuffleButtons(songs.isNotEmpty(), { onPlay(null, false) }, { onPlay(null, true) })
                }
            }
            items(songs, key = { it.id }) { song -> SongRow(song, onClick = { onPlay(song.id, false) }) }
            item { Spacer(Modifier.width(1.dp).heightIn(min = Tokens.Spacing.xl)) }
        }
    }
}
