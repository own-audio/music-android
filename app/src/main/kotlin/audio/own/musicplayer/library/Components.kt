package audio.own.musicplayer.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarHalf
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import audio.own.core.designsystem.components.CoverArt
import audio.own.core.designsystem.theme.AppBrand
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.core.designsystem.theme.tabular
import audio.own.music.library.AlbumSort
import audio.own.music.library.FavoriteKind
import audio.own.music.library.model.Album
import audio.own.music.library.model.Artist
import audio.own.music.library.model.Sharing
import audio.own.music.library.model.Song
import audio.own.musicplayer.R
import audio.own.core.strings.R as SR

/**
 * What every library screen needs from the rest of the app: the song playing, the marks the
 * listener changed, and the song actions. Provided once at the root; screenshots use the default.
 */
@Immutable
class LibraryUi(
    val currentTrackId: String? = null,
    val isPlaying: Boolean = false,
    val canRate: Boolean = false,
    val isOffline: Boolean = false,
    /** Bumped when favourites or ratings change, so rows read them again. */
    val marksVersion: Int = 0,
    val isFavorite: (id: String, fallback: Boolean) -> Boolean = { _, fallback -> fallback },
    val rating: (id: String, fallback: Int?) -> Int? = { _, fallback -> fallback },
    val play: (songs: List<Song>, startId: String?, shuffle: Boolean, containerId: String?) -> Unit = { _, _, _, _ -> },
    val enqueue: (songs: List<Song>, next: Boolean) -> Unit = { _, _ -> },
    val setFavorite: (kind: FavoriteKind, id: String, on: Boolean) -> Unit = { _, _, _ -> },
    val setRating: (id: String, rating: Int) -> Unit = { _, _ -> },
) {
    fun isFavorite(song: Song) = isFavorite(song.id, song.isFavorite)
    fun rating(song: Song) = rating(song.id, song.rating)
}

val LocalLibraryUi = staticCompositionLocalOf { LibraryUi() }

/** Some libraries have albums with no name in their tags (or only spaces). */
@Composable
fun Album.displayName(): String = name.trim().ifEmpty { stringResource(R.string.unknown_album) }

fun Album.coverId(): String = coverArt ?: id

fun Song.coverId(): String? = coverArt ?: albumId

val AlbumSort.title: Int
    get() = when (this) {
        AlbumSort.NEWEST -> R.string.recently_added
        AlbumSort.RECENTLY_PLAYED -> R.string.recently_played
        AlbumSort.MOST_PLAYED -> R.string.sort_most_played
        AlbumSort.RANDOM -> R.string.sort_random
        AlbumSort.NAME -> R.string.sort_by_title
        AlbumSort.ARTIST -> R.string.sort_by_artist
        AlbumSort.FAVORITES -> R.string.favorites
        AlbumSort.HIGHEST_RATED -> R.string.sort_top_rated
    }

/** "3:07", "1:02:03". */
fun formatDuration(totalSecs: Long): String {
    val secs = totalSecs.coerceAtLeast(0)
    val h = secs / 3600
    val m = (secs % 3600) / 60
    val s = secs % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** "12 songs, 48 min". */
@Composable
fun songsSummary(songs: List<Song>): String {
    val count = pluralStringResource(R.plurals.songs_count, songs.size, songs.size)
    val minutes = Math.round(songs.sumOf { it.durationMs ?: 0 } / 60_000.0).toInt()
    return if (minutes > 0) stringResource(R.string.songs_summary, count, minutes) else count
}

@Composable
fun AlbumTile(album: Album, size: Dp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Column(
        modifier.clip(MaterialTheme.shapes.medium).clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs),
    ) {
        CoverArt(rememberCover(album.coverId(), size), size, Icons.Filled.Album, radius = AppBrand.radii.cover)
        Text(album.displayName(), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(size))
        Text(album.artist.orEmpty(), style = MaterialTheme.typography.bodySmall, color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(size))
    }
}

/** The adaptive album grid: tiles at least 150 dp wide, with optional rows above it. */
@Composable
fun AlbumGrid(
    albums: List<Album>,
    onAlbum: (Album) -> Unit,
    modifier: Modifier = Modifier,
    onReachEnd: (() -> Unit)? = null,
    footer: (LazyGridScope.() -> Unit)? = null,
    header: (LazyGridScope.() -> Unit)? = null,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Tokens.Spacing.screenEdge, Tokens.Spacing.md, Tokens.Spacing.screenEdge, Tokens.Spacing.xl),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xl),
    ) {
        header?.invoke(this)
        items(albums, key = { it.id }) { album ->
            // The tile's cover follows the column it gets.
            androidx.compose.foundation.layout.BoxWithConstraints {
                AlbumTile(album, size = maxWidth, onClick = { onAlbum(album) })
            }
            if (album.id == albums.lastOrNull()?.id) androidx.compose.runtime.LaunchedEffect(albums.size) { onReachEnd?.invoke() }
        }
        footer?.invoke(this)
    }
}

fun LazyGridScope.fullWidth(content: @Composable () -> Unit) = item(span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable
fun ArtistImage(artist: Artist, size: Dp) {
    val palette = LocalPalette.current
    val model = artist.coverArt?.let { rememberCover(it, size) } ?: artist.imageUrl(with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() })
    Box(Modifier.size(size).clip(CircleShape).background(palette.backgroundAlt), contentAlignment = Alignment.Center) {
        CoverArt(model, size, Icons.Filled.Mic, radius = size / 2)
    }
}

/** Play and Shuffle side by side under a detail header; stacked at the largest text sizes. */
@Composable
fun PlayShuffleButtons(enabled: Boolean, onPlay: () -> Unit, onShuffle: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val stacked = androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.8f
    val play = @Composable { m: Modifier ->
        FilledTonalButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onPlay() }, enabled = enabled, modifier = m.heightIn(min = Tokens.minTarget)) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Text(stringResource(SR.string.play), modifier = Modifier.padding(start = Tokens.Spacing.sm))
        }
    }
    val shuffle = @Composable { m: Modifier ->
        FilledTonalButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onShuffle() }, enabled = enabled, modifier = m.heightIn(min = Tokens.minTarget)) {
            Icon(Icons.Filled.Shuffle, contentDescription = null)
            Text(stringResource(R.string.shuffle), modifier = Modifier.padding(start = Tokens.Spacing.sm))
        }
    }
    if (stacked) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md)) {
            play(Modifier.fillMaxWidth())
            shuffle(Modifier.fillMaxWidth())
        }
    } else {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md)) {
            play(Modifier.weight(1f))
            shuffle(Modifier.weight(1f))
        }
    }
}

/**
 * A song in a list: track number (a speaker on the one playing) or cover, title, artist, the
 * small marks, the duration, and the ⋯ menu, which a long press opens too.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showsCover: Boolean = false,
) {
    val ui = LocalLibraryUi.current
    val palette = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val isCurrent = ui.currentTrackId == song.id
    val starred = ui.isFavorite(song)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .combinedClickable(onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() }, onLongClick = { menu = true })
            .padding(start = Tokens.Spacing.screenEdge, top = Tokens.Spacing.xs, bottom = Tokens.Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
    ) {
        if (showsCover) {
            CoverArt(rememberCover(song.coverId(), 44.dp), 44.dp, Icons.Filled.MusicNote, radius = AppBrand.radii.rowCover)
        } else {
            Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterEnd) {
                if (isCurrent) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = stringResource(SR.string.now_playing), tint = palette.accent, modifier = Modifier.size(18.dp))
                } else {
                    Text(song.track?.toString().orEmpty(), style = MaterialTheme.typography.bodyMedium.tabular(), color = palette.muted)
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(song.title, color = if (isCurrent) palette.accent else palette.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            song.artist?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (song.sharing == Sharing.SHARED) {
            Icon(Icons.Filled.Group, contentDescription = stringResource(SR.string.shared_with_family), tint = palette.muted, modifier = Modifier.size(14.dp))
        }
        if (starred) {
            Icon(Icons.Filled.Star, contentDescription = stringResource(R.string.favorite_badge), tint = Color(0xFFFFCC00), modifier = Modifier.size(14.dp))
        }
        song.durationMs?.let {
            Text(formatDuration(it / 1000), style = MaterialTheme.typography.bodySmall.tabular(), color = palette.muted)
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreHoriz, contentDescription = stringResource(SR.string.more), tint = palette.muted)
            }
            SongMenu(song, expanded = menu, onDismiss = { menu = false })
        }
    }
}

/**
 * The song's actions that work today: Play Next, Add to Queue, Favorite, Rate. The others of
 * the iOS menu (Add to Playlist, Instant Mix, covers, sharing, Identify, Download) come with the
 * phases that build them.
 */
@Composable
fun SongMenu(song: Song, expanded: Boolean, onDismiss: () -> Unit) {
    val ui = LocalLibraryUi.current
    var rating by remember { mutableStateOf(false) }
    DropdownMenu(expanded = expanded && !rating, onDismissRequest = onDismiss) {
        QueueMenuItems(listOf(song), onDismiss)
        val starred = ui.isFavorite(song)
        DropdownMenuItem(
            text = { Text(stringResource(if (starred) R.string.unfavorite else R.string.favorite)) },
            leadingIcon = { Icon(if (starred) Icons.Filled.Star else Icons.Filled.StarOutline, contentDescription = null) },
            onClick = { onDismiss(); ui.setFavorite(FavoriteKind.SONG, song.id, !starred) },
        )
        if (ui.canRate) {
            RatingMenuItem(ui.rating(song)) { rating = true }
        }
    }
    if (rating) {
        RatingChoices(ui.rating(song), onDismiss = { rating = false; onDismiss() }) { ui.setRating(song.id, it) }
    }
}

@Composable
fun QueueMenuItems(songs: List<Song>, onDismiss: () -> Unit) {
    val ui = LocalLibraryUi.current
    DropdownMenuItem(
        text = { Text(stringResource(R.string.play_next)) },
        leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null) },
        onClick = { onDismiss(); ui.enqueue(songs, true) },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.add_to_queue)) },
        leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null) },
        onClick = { onDismiss(); ui.enqueue(songs, false) },
    )
}

@Composable
fun RatingMenuItem(rating: Int?, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(rating?.let { stringResource(R.string.rating_stars, "★".repeat(it)) } ?: stringResource(R.string.rate)) },
        leadingIcon = { Icon(Icons.Filled.StarHalf, contentDescription = null) },
        onClick = onClick,
    )
}

/** Five to one stars, and Clear Rating when there is one: the iOS rating submenu. */
@Composable
fun RatingChoices(rating: Int?, onDismiss: () -> Unit, onRate: (Int) -> Unit) {
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        for (value in 5 downTo 1) {
            DropdownMenuItem(
                text = { Text("★".repeat(value), modifier = Modifier.semantics { contentDescription = "$value" }) },
                trailingIcon = { if (rating == value) Icon(Icons.Filled.Check, contentDescription = stringResource(SR.string.selected)) },
                onClick = { onDismiss(); onRate(value) },
            )
        }
        if (rating != null) {
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.clear_rating), color = LocalPalette.current.error) },
                onClick = { onDismiss(); onRate(0) },
            )
        }
    }
}
