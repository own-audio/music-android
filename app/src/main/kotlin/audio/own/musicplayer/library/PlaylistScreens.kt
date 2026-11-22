package audio.own.musicplayer.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import audio.own.core.designsystem.components.CoverArt
import audio.own.core.designsystem.components.EmptyState
import audio.own.core.designsystem.components.LoadStateView
import audio.own.core.designsystem.components.SetDownloadButton
import audio.own.core.designsystem.theme.AppBrand
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.music.library.SmartPresetInfo
import audio.own.music.library.model.Playlist
import audio.own.music.library.model.Song
import audio.own.musicplayer.R
import audio.own.musicplayer.app.Load
import audio.own.musicplayer.app.forView
import audio.own.musicplayer.downloads.DownloadsModel
import audio.own.musicplayer.servers.TextDialog
import kotlin.math.roundToInt
import audio.own.core.strings.R as SR

/** "12 songs", or "12 songs · by Sam" for someone else's. */
@Composable
fun playlistSubtitle(playlist: Playlist, username: String?): String {
    val songs = pluralStringResource(R.plurals.songs_count, playlist.songCount, playlist.songCount)
    val owner = playlist.otherOwner(username) ?: return songs
    return stringResource(R.string.by_owner, songs, owner)
}

@Composable
fun SmartPresetInfo.displayName(): String = Presets.name(slug)?.let { stringResource(it) } ?: name

@Composable
fun SmartPresetInfo.summaryText(): String? = Presets.summary(slug)?.let { stringResource(it) }

@Composable
fun SmartTarget.displayName(): String = when (this) {
    is SmartTarget.Saved -> info.name
    is SmartTarget.Preset -> info.displayName()
}

/** The wand on a tinted square: a smart playlist has no cover. */
@Composable
fun SmartIcon(size: Dp) {
    val palette = LocalPalette.current
    Box(
        Modifier.size(size).clip(RoundedCornerShape(if (size > 80.dp) AppBrand.radii.cover else AppBrand.radii.rowCover)).background(palette.accent.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = palette.accent, modifier = Modifier.size(size * 0.45f))
    }
}

/** One playlist in a list: cover, name, "12 songs · by Sam". */
@Composable
private fun PlaylistRow(playlist: Playlist, username: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 72.dp).padding(horizontal = Tokens.Spacing.screenEdge, vertical = Tokens.Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
    ) {
        CoverArt(rememberCover(playlist.coverArt, 56.dp), 56.dp, Icons.AutoMirrored.Filled.QueueMusic, radius = AppBrand.radii.rowCover)
        Column(Modifier.weight(1f)) {
            Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(playlistSubtitle(playlist, username), style = MaterialTheme.typography.bodySmall, color = LocalPalette.current.muted, maxLines = 1)
        }
        if (playlist.isShared == true) Icon(Icons.Filled.Group, contentDescription = stringResource(SR.string.shared_with_family), tint = LocalPalette.current.muted, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun SmartRow(name: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 72.dp).padding(horizontal = Tokens.Spacing.screenEdge, vertical = Tokens.Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
    ) {
        SmartIcon(56.dp)
        Column(Modifier.weight(1f)) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LocalPalette.current.muted, maxLines = 2)
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = LocalPalette.current.muted,
        modifier = Modifier.padding(start = Tokens.Spacing.screenEdge, top = Tokens.Spacing.lg, bottom = Tokens.Spacing.xs).semantics { heading() },
    )
}

/**
 * The Playlists tab: Smart Playlists and Ready-Made where the server has them, then the
 * playlists. Top bar: New Smart Playlist (with smart playlists) and New Playlist.
 */
@Composable
fun PlaylistsContent(
    state: Load<PlaylistsContentState>,
    username: String?,
    refreshing: Boolean,
    onBack: (() -> Unit)?,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onPlaylist: (Playlist) -> Unit,
    onSmart: (SmartTarget) -> Unit,
    onCreate: (String) -> Unit,
    onNewSmart: () -> Unit,
) {
    var naming by remember { mutableStateOf(false) }
    val content = (state as? Load.Loaded)?.value
    ListPage(
        title = stringResource(R.string.playlists),
        onBack = onBack,
        actions = {
            if (content?.hasSmart == true) {
                IconButton(onClick = onNewSmart) { Icon(Icons.Filled.AutoAwesome, contentDescription = stringResource(R.string.new_smart_playlist)) }
            }
            IconButton(onClick = { naming = true }) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.new_playlist)) }
        },
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize()) {
            LoadStateView(state.forView(), onRetry = onRetry) { loaded ->
                if (loaded.playlists.isEmpty() && !loaded.hasSmart) {
                    EmptyState(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.no_playlists), stringResource(R.string.no_playlists_text))
                } else {
                    Refreshable(refreshing, onRefresh) {
                        LazyColumn(Modifier.fillMaxSize()) {
                            if (loaded.smart.isNotEmpty()) {
                                item { SectionHeader(stringResource(R.string.smart_playlists)) }
                                items(loaded.smart, key = { "s-${it.id}" }) { info ->
                                    SmartRow(info.name, if (info.isShared) stringResource(R.string.shared_with_the_family) else null) { onSmart(SmartTarget.Saved(info)) }
                                }
                            }
                            if (loaded.presets.isNotEmpty()) {
                                item { SectionHeader(stringResource(R.string.ready_made)) }
                                items(loaded.presets, key = { "p-${it.slug}" }) { preset ->
                                    SmartRow(preset.displayName(), preset.summaryText()) { onSmart(SmartTarget.Preset(preset)) }
                                }
                            }
                            if (loaded.hasSmart) item { SectionHeader(stringResource(R.string.playlists)) }
                            items(loaded.playlists, key = { it.id }) { playlist -> PlaylistRow(playlist, username) { onPlaylist(playlist) } }
                        }
                    }
                }
            }
        }
    }
    if (naming) NewPlaylistDialog(onCreate = { onCreate(it); naming = false }, onDismiss = { naming = false })
}

@Composable
fun NewPlaylistDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    TextDialog(
        title = stringResource(R.string.new_playlist),
        label = stringResource(SR.string.name),
        initial = "",
        confirm = stringResource(R.string.create),
        onConfirm = onCreate,
        onDismiss = onDismiss,
    )
}

/** What a playlist's page can do, decided by its view model. */
data class PlaylistActions(
    val isOwn: Boolean = false,
    val canChooseCover: Boolean = false,
    val canShare: Boolean = false,
    val canReorder: Boolean = false,
)

/**
 * A playlist: cover, name, description, Play / Shuffle, the songs, the download button, and on
 * the listener's own the ⋯ Edit Playlist menu, swipe to remove and the drag handles.
 */
@Composable
fun PlaylistContent(
    state: PlaylistPageState,
    actions: PlaylistActions,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRename: (String) -> Unit,
    onChooseCover: () -> Unit,
    onShare: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    val ui = LocalLibraryUi.current
    val palette = LocalPalette.current
    val playlist = state.playlist
    val songs = (state.songs as? Load.Loaded)?.value.orEmpty()
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val collectionId = playlist?.let { DownloadsModel.playlistCollection(it.id) }.orEmpty()
    val progress = playlist?.let { ui.collectionProgress(collectionId) }
    val container = playlist?.let { "playlist:${it.id}" }
    ListPage(
        title = playlist?.name.orEmpty(),
        onBack = onBack,
        actions = {
            if (playlist != null && ui.canDownload && (songs.isNotEmpty() || progress != null)) {
                val (done, total) = progress ?: (0 to songs.size)
                SetDownloadButton(
                    done = done,
                    total = total,
                    downloading = progress != null && ui.isCollectionDownloading(collectionId),
                    onDownload = { if (!ui.isOffline) ui.downloadPlaylist(playlist, songs) },
                    onRemove = { ui.removeCollection(collectionId) },
                )
            }
            if (playlist != null && actions.isOwn) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.edit_playlist)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(SR.string.rename)) },
                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                            onClick = { menu = false; renaming = true },
                        )
                        if (actions.canChooseCover && !ui.isOffline) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.choose_cover)) },
                                leadingIcon = { Icon(Icons.Filled.Image, contentDescription = null) },
                                onClick = { menu = false; onChooseCover() },
                            )
                        }
                        if (actions.canShare && !ui.isOffline) {
                            val shared = playlist.isShared == true
                            DropdownMenuItem(
                                text = { Text(stringResource(if (shared) R.string.stop_sharing else R.string.share_with_the_family)) },
                                leadingIcon = { Icon(if (shared) Icons.Filled.Person else Icons.Filled.Group, contentDescription = null) },
                                onClick = { menu = false; onShare(!shared) },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete_playlist), color = palette.error) },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = palette.error) },
                            onClick = { menu = false; confirmDelete = true },
                        )
                    }
                }
            }
        },
    ) { inner ->
        LazyColumn(Modifier.padding(inner).fillMaxSize()) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.screenEdge, vertical = Tokens.Spacing.sm),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        CoverArt(rememberCover(playlist?.coverArt, 200.dp), 200.dp, Icons.AutoMirrored.Filled.QueueMusic, radius = AppBrand.radii.cover)
                        if (state.savingCover) CircularProgressIndicator()
                    }
                    Text(playlist?.name.orEmpty(), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
                    playlist?.comment?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.muted, textAlign = TextAlign.Center)
                    }
                    PlayShuffleButtons(songs.isNotEmpty(), { ui.play(songs, null, false, container) }, { ui.play(songs, null, true, container) })
                }
            }
            when (val s = state.songs) {
                is Load.Loaded -> {
                    // Keyed by position: a playlist can hold the same song twice.
                    itemsIndexed(s.value, key = { index, song -> "$index-${song.playlistEntryId ?: song.id}" }) { index, song ->
                        PlaylistSongRow(
                            song = song,
                            index = index,
                            count = s.value.size,
                            canEdit = actions.isOwn && !ui.isOffline,
                            canReorder = actions.canReorder && !ui.isOffline,
                            onClick = { ui.play(s.value, song.id, false, container) },
                            onRemove = { onRemove(index) },
                            onMove = onMove,
                        )
                    }
                    if (s.value.isNotEmpty()) {
                        item { Text(songsSummary(s.value), style = MaterialTheme.typography.bodySmall, color = palette.muted, modifier = Modifier.padding(Tokens.Spacing.screenEdge)) }
                    }
                }
                else -> item {
                    Box(Modifier.fillMaxWidth().padding(Tokens.Spacing.xl), contentAlignment = Alignment.Center) {
                        LoadStateView(s.forView(), onRetry = onRetry) {}
                    }
                }
            }
        }
    }
    if (renaming && playlist != null) {
        TextDialog(
            title = stringResource(R.string.rename_playlist),
            label = stringResource(SR.string.name),
            initial = playlist.name,
            confirm = stringResource(SR.string.save),
            onConfirm = { onRename(it); renaming = false },
            onDismiss = { renaming = false },
        )
    }
    if (confirmDelete && playlist != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_named_question, playlist.name)) },
            text = { Text(stringResource(R.string.delete_playlist_message)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(stringResource(R.string.delete_playlist), color = palette.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(SR.string.cancel)) } },
        )
    }
}

/** A song of one's own playlist: swipe left to remove, the handle to drag it elsewhere. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistSongRow(
    song: Song,
    index: Int,
    count: Int,
    canEdit: Boolean,
    canReorder: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    val palette = LocalPalette.current
    val removeItem: (@Composable (() -> Unit) -> Unit)? = if (canEdit) {
        { dismiss ->
            DropdownMenuItem(
                text = { Text(stringResource(R.string.remove_from_playlist), color = palette.error) },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = palette.error) },
                onClick = { dismiss(); onRemove() },
            )
        }
    } else {
        null
    }
    var offset by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableIntStateOf(1) }
    val handle: @Composable () -> Unit = {
        if (canReorder) {
            Icon(
                Icons.Filled.DragHandle,
                contentDescription = stringResource(R.string.reorder),
                tint = palette.muted,
                modifier = Modifier.size(Tokens.minTarget).padding(12.dp).pointerInput(index, count) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            val target = (index + (offset / rowHeight).roundToInt()).coerceIn(0, count - 1)
                            offset = 0f
                            if (target != index) onMove(index, target)
                        },
                        onDragCancel = { offset = 0f },
                    ) { change, dy ->
                        change.consume()
                        offset += dy
                    }
                },
            )
        }
    }
    val row = @Composable {
        SongRow(
            song,
            onClick = onClick,
            showsCover = true,
            menuExtra = removeItem,
            trailing = handle,
            modifier = Modifier.background(MaterialTheme.colorScheme.background),
        )
    }
    val moving = Modifier.zIndex(if (offset != 0f) 1f else 0f).graphicsLayer { translationY = offset }.onSizeChanged { rowHeight = it.height.coerceAtLeast(1) }
    if (!canEdit) {
        Box(moving) { row() }
        return
    }
    val swipe = rememberSwipeToDismissBoxState()
    audio.own.musicplayer.ui.SwipeActions(swipe) { if (it == SwipeToDismissBoxValue.EndToStart) onRemove() }
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = false,
        modifier = moving,
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(palette.error).padding(horizontal = Tokens.Spacing.lg), contentAlignment = Alignment.CenterEnd) {
                Text(stringResource(SR.string.remove), color = Color.White)
            }
        },
    ) { row() }
}

/** Add to Playlist: New Playlist…, then the listener's own playlists with their song counts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(state: AddToPlaylistState, onDismiss: () -> Unit, onRetry: () -> Unit, onAdd: (Playlist) -> Unit, onCreate: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        AddToPlaylistBody(state, onRetry, onAdd, onCreate)
    }
}

@Composable
fun AddToPlaylistBody(state: AddToPlaylistState, onRetry: () -> Unit, onAdd: (Playlist) -> Unit, onCreate: (String) -> Unit) {
    var naming by remember { mutableStateOf(false) }
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(bottom = Tokens.Spacing.xl)) {
        Text(
            stringResource(R.string.add_to_playlist),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = Tokens.Spacing.screenEdge, vertical = Tokens.Spacing.sm).semantics { heading() },
        )
        Row(
            Modifier.fillMaxWidth().clickable { naming = true }.heightIn(min = Tokens.minTarget).padding(horizontal = Tokens.Spacing.screenEdge),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
        ) {
            Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null, tint = palette.accent)
            Text(stringResource(R.string.new_playlist_ellipsis), color = palette.accent)
        }
        when (val p = state.playlists) {
            is Load.Loaded -> LazyColumn {
                items(p.value, key = { it.id }) { playlist ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onAdd(playlist) }.heightIn(min = Tokens.minTarget).padding(horizontal = Tokens.Spacing.screenEdge),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(playlist.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(playlist.songCount.toString(), color = palette.muted)
                    }
                }
            }
            else -> Box(Modifier.fillMaxWidth().heightIn(min = 160.dp), contentAlignment = Alignment.Center) { LoadStateView(p.forView(), onRetry = onRetry) {} }
        }
    }
    if (naming) NewPlaylistDialog(onCreate = { onCreate(it); naming = false }, onDismiss = { naming = false })
}

/** The header shared by the smart playlist page: the wand, the name, what it is. */
@Composable
internal fun SmartHeader(name: String, explanation: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md)) {
        SmartIcon(160.dp)
        Text(name, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text(explanation, style = MaterialTheme.typography.bodyMedium, color = LocalPalette.current.muted, textAlign = TextAlign.Center)
    }
}
