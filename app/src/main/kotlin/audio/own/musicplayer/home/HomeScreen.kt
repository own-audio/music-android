package audio.own.musicplayer.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import audio.own.core.designsystem.components.CoverArt
import audio.own.core.designsystem.components.EmptyState
import audio.own.core.designsystem.components.LoadStateView
import audio.own.core.designsystem.theme.AppBrand
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.core.servers.ServerRecord
import audio.own.music.library.model.Album
import audio.own.music.library.model.Playlist
import audio.own.musicplayer.R
import audio.own.musicplayer.app.Load
import audio.own.musicplayer.app.forView
import audio.own.musicplayer.library.AlbumTile
import audio.own.musicplayer.library.HomeContentState
import audio.own.musicplayer.library.ListPage
import audio.own.musicplayer.library.Refreshable
import audio.own.musicplayer.library.rememberCover
import audio.own.musicplayer.servers.ServerKindIcon
import audio.own.core.strings.R as SR

data class HomeUi(
    val content: Load<HomeContentState> = Load.Loading,
    val servers: List<ServerRecord> = emptyList(),
    val activeId: String? = null,
    val offlineMode: Boolean = false,
    val isOffline: Boolean = false,
    val shuffling: Boolean = false,
    val refreshing: Boolean = false,
    /** The first catalogue sync is still running: "No Music Yet" would be wrong. */
    val firstSync: Boolean = false,
)

/**
 * Home: Shuffle All, then Recently Played, Recently Added (offline: Downloaded Albums) and the
 * playlists. The bar has the server menu (with more than one server), Offline Mode and Settings.
 */
@Composable
fun HomeContent(
    ui: HomeUi,
    onSelectServer: (String) -> Unit,
    onOfflineMode: (Boolean) -> Unit,
    onSettings: () -> Unit,
    onShuffleAll: () -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onAlbum: (Album) -> Unit,
    onPlaylist: (Playlist) -> Unit,
    onShelf: (String) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    ListPage(
        title = stringResource(R.string.tab_home),
        onBack = null,
        maxContentWidth = null,
        actions = {
            if (ui.servers.size > 1) ServerMenu(ui.servers, ui.activeId, onSelectServer)
            IconToggleButton(checked = ui.offlineMode, onCheckedChange = onOfflineMode) {
                Icon(if (ui.offlineMode) Icons.Filled.WifiOff else Icons.Filled.Wifi, contentDescription = stringResource(R.string.offline_mode))
            }
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = stringResource(SR.string.settings)) }
        },
    ) { inner ->
        Refreshable(ui.refreshing, onRefresh, Modifier.padding(inner)) {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xl)) {
                item {
                    Button(
                        onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onShuffleAll() },
                        enabled = !ui.shuffling,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.screenEdge).padding(top = Tokens.Spacing.md).heightIn(min = Tokens.minTarget),
                    ) {
                        if (ui.shuffling) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Shuffle, contentDescription = null)
                            Text(stringResource(R.string.shuffle_all), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = Tokens.Spacing.sm))
                        }
                    }
                }
                item {
                    Box(Modifier.fillMaxWidth().heightIn(min = 200.dp), contentAlignment = Alignment.Center) {
                        val loading = ui.firstSync && (ui.content as? Load.Loaded)?.value?.isEmpty == true
                        LoadStateView(if (loading) Load.Loading.forView() else ui.content.forView(), onRetry = onRetry) { home ->
                            Shelves(home, ui.isOffline, onAlbum, onPlaylist, onShelf)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Shelves(home: HomeContentState, isOffline: Boolean, onAlbum: (Album) -> Unit, onPlaylist: (Playlist) -> Unit, onShelf: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xl)) {
        if (home.recent.isNotEmpty()) {
            Shelf(stringResource(R.string.recently_played), onClick = { onShelf(HomeShelf.RECENTLY_PLAYED) }) {
                items(home.recent, key = { it.id }) { AlbumTile(it, 140.dp, onClick = { onAlbum(it) }) }
            }
        }
        if (home.newest.isNotEmpty()) {
            Shelf(stringResource(if (isOffline) R.string.downloaded_albums else R.string.recently_added), onClick = { onShelf(HomeShelf.NEWEST) }) {
                items(home.newest, key = { it.id }) { AlbumTile(it, 140.dp, onClick = { onAlbum(it) }) }
            }
        }
        if (home.playlists.isNotEmpty()) {
            Shelf(stringResource(R.string.playlists), onClick = { onShelf(HomeShelf.PLAYLISTS) }) {
                items(home.playlists, key = { it.id }) { PlaylistTile(it, onClick = { onPlaylist(it) }) }
            }
        }
        if (home.isEmpty) {
            if (isOffline) {
                EmptyState(Icons.Filled.DownloadForOffline, stringResource(R.string.nothing_downloaded), stringResource(R.string.nothing_downloaded_text))
            } else {
                EmptyState(Icons.Filled.MusicNote, stringResource(R.string.no_music_yet), stringResource(R.string.no_music_yet_text))
            }
        }
    }
}

object HomeShelf {
    const val RECENTLY_PLAYED = "recent"
    const val NEWEST = "newest"
    const val PLAYLISTS = "playlists"
}

@Composable
private fun Shelf(title: String, onClick: () -> Unit, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val palette = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm)) {
        Row(
            Modifier.padding(horizontal = Tokens.Spacing.sm).heightIn(min = Tokens.minTarget).clickable(onClick = onClick).padding(horizontal = Tokens.Spacing.sm, vertical = Tokens.Spacing.xs).semantics { heading() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = palette.muted)
        }
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Tokens.Spacing.screenEdge),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg),
            content = content,
        )
    }
}

@Composable
private fun PlaylistTile(playlist: Playlist, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Column(Modifier.width(140.dp).clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs)) {
        CoverArt(rememberCover(playlist.coverArt, 140.dp), 140.dp, Icons.AutoMirrored.Filled.QueueMusic, radius = AppBrand.radii.cover)
        Text(playlist.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(pluralStringResource(R.plurals.songs_count, playlist.songCount, playlist.songCount), style = MaterialTheme.typography.bodySmall, color = palette.muted)
    }
}

/** With more than one server, which one the library shows is a tap away. */
@Composable
fun ServerMenu(servers: List<ServerRecord>, activeId: String?, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val active = servers.firstOrNull { it.id == activeId }
    Box {
        TextButton(onClick = { open = true }) {
            Icon(Icons.Filled.Dns, contentDescription = stringResource(SR.string.servers))
            Text(active?.displayName.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = Tokens.Spacing.xs).width(96.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            servers.forEach { server ->
                DropdownMenuItem(
                    text = { Text(server.displayName) },
                    leadingIcon = { ServerKindIcon(server.kind, size = 22.dp) },
                    trailingIcon = { if (server.id == activeId) Icon(Icons.Filled.Check, contentDescription = stringResource(SR.string.selected)) },
                    onClick = { open = false; onSelect(server.id) },
                )
            }
        }
    }
}
