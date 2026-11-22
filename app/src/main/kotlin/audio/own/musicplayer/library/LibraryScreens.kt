package audio.own.musicplayer.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import audio.own.musicplayer.ui.asHeading
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.ManageSearch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import audio.own.core.designsystem.components.CoverArt
import audio.own.core.designsystem.components.EmptyState
import audio.own.core.designsystem.components.LoadStateView
import audio.own.core.designsystem.theme.AppBrand
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.music.library.AlbumSort
import audio.own.music.library.FavoriteKind
import audio.own.music.library.model.Album
import audio.own.music.library.model.Artist
import audio.own.music.library.model.FavoritesResult
import audio.own.music.library.model.Genre
import audio.own.music.library.model.Song
import audio.own.musicplayer.R
import audio.own.musicplayer.app.Load
import audio.own.musicplayer.app.forView
import audio.own.core.strings.R as SR

/** A page below a tab: top bar with back, title and actions, and the content under it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListPage(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable () -> Unit = {},
    /** Lists stay a readable width on a tablet; a grid or a page of shelves passes null. */
    maxContentWidth: androidx.compose.ui.unit.Dp? = audio.own.musicplayer.ui.LIST_MAX_WIDTH,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.asHeading()) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(SR.string.back)) }
                },
                actions = { actions() },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        if (maxContentWidth == null) {
            content(inner)
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = maxContentWidth).fillMaxSize()) { content(inner) }
            }
        }
    }
}

/** Pull to refresh around a list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Refreshable(refreshing: Boolean, onRefresh: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) { content() }
}

// The Library tab's sections

enum class LibrarySection(val title: Int, val icon: ImageVector, val route: String) {
    ARTISTS(R.string.artists, Icons.Filled.Mic, LibraryRoutes.ARTISTS),
    ALBUMS(R.string.albums, Icons.Filled.Album, LibraryRoutes.albums(AlbumSort.NAME)),
    SONGS(R.string.songs, Icons.Filled.MusicNote, LibraryRoutes.SONGS),
    GENRES(R.string.genres, Icons.Filled.Piano, LibraryRoutes.GENRES),
    FAVORITES(R.string.favorites, Icons.Filled.StarOutline, LibraryRoutes.FAVORITES),
    CONCERTS(R.string.concerts, Icons.Filled.ConfirmationNumber, LibraryRoutes.CONCERTS),
    ;

    companion object {
        /** Concerts only where the server keeps them. */
        fun available(concerts: Boolean): List<LibrarySection> = entries.filter { it != CONCERTS || concerts }
    }
}

/** The Library tab's root on phones: a grid of section cards; Downloads in the top bar. */
@Composable
fun LibraryContent(concerts: Boolean, identify: Boolean = false, onOpen: (String) -> Unit) {
    val palette = LocalPalette.current
    ListPage(
        title = stringResource(R.string.library),
        onBack = null,
        actions = {
            IconButton(onClick = { onOpen(LibraryRoutes.DOWNLOADS) }) { Icon(Icons.Filled.DownloadForOffline, contentDescription = stringResource(R.string.downloads)) }
        },
    ) { inner ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.padding(inner),
            contentPadding = PaddingValues(Tokens.Spacing.screenEdge),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
        ) {
            items(LibrarySection.available(concerts)) { section ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 88.dp)
                        .background(palette.backgroundAlt, RoundedCornerShape(AppBrand.radii.card))
                        .clickable { onOpen(section.route) }
                        .padding(Tokens.Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm),
                ) {
                    Icon(section.icon, contentDescription = null, tint = palette.accent)
                    Text(stringResource(section.title), style = MaterialTheme.typography.titleMedium, color = palette.foreground)
                }
            }
            // Under the grid, on servers that identify (own.audio with MusicBrainz).
            if (identify) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = Tokens.minTarget)
                            .background(palette.backgroundAlt, RoundedCornerShape(AppBrand.radii.card))
                            .clickable { onOpen(LibraryRoutes.IDENTIFY) }
                            .padding(Tokens.Spacing.lg),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
                    ) {
                        Icon(Icons.Filled.ManageSearch, contentDescription = null, tint = palette.accent)
                        Text(stringResource(R.string.identify_library), style = MaterialTheme.typography.titleMedium, color = palette.foreground)
                    }
                }
            }
        }
    }
}

/** Tablets: the sections as a permanent list beside the page (the iPad's split view). */
@Composable
fun LibrarySidebar(concerts: Boolean, selected: String?, onOpen: (String) -> Unit, modifier: Modifier = Modifier, identify: Boolean = false) {
    val palette = LocalPalette.current
    Column(modifier.fillMaxSize().background(palette.backgroundAlt).padding(vertical = Tokens.Spacing.md)) {
        Text(
            stringResource(R.string.library),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.md).asHeading(),
        )
        val rows = LibrarySection.available(concerts).map { Triple(it.route, it.icon, it.title) } +
            Triple(LibraryRoutes.DOWNLOADS, Icons.Filled.DownloadForOffline, R.string.downloads) +
            listOfNotNull(Triple(LibraryRoutes.IDENTIFY, Icons.Filled.ManageSearch, R.string.identify_library).takeIf { identify })
        rows.forEach { (route, icon, title) ->
            val isSelected = route == selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Tokens.minTarget)
                    .padding(horizontal = Tokens.Spacing.sm)
                    .background(if (isSelected) palette.accent.copy(alpha = 0.15f) else palette.backgroundAlt, RoundedCornerShape(10.dp))
                    // Selected is said, and drawn in weight as well as in the tint.
                    .selectable(selected = isSelected) { onOpen(route) }
                    .padding(horizontal = Tokens.Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
            ) {
                Icon(icon, contentDescription = null, tint = palette.accent)
                Text(stringResource(title), color = palette.foreground, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

// Artists

@Composable
fun ArtistsScreen(vm: ArtistsViewModel, onBack: () -> Unit, onArtist: (Artist) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("") }
    val palette = LocalPalette.current
    ListPage(stringResource(R.string.artists), onBack) { inner ->
        Box(Modifier.padding(inner)) {
            LoadStateView(state.forView(), onRetry = vm::load) { artists ->
                if (artists.isEmpty()) {
                    EmptyState(Icons.Filled.Mic, stringResource(R.string.no_artists), null)
                } else {
                    Refreshable(refreshing, vm::refresh) {
                        val shown = if (filter.isBlank()) artists else artists.filter { it.name.contains(filter.trim(), ignoreCase = true) }
                        LazyColumn(Modifier.fillMaxSize()) {
                            item {
                                OutlinedTextField(
                                    value = filter,
                                    onValueChange = { filter = it },
                                    placeholder = { Text(stringResource(R.string.filter_artists)) },
                                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.screenEdge, vertical = Tokens.Spacing.sm),
                                )
                            }
                            items(shown, key = { it.id }) { artist ->
                                ArtistRow(artist, onClick = { onArtist(artist) }) {
                                    artist.albumCount?.let { Text(pluralStringResource(R.plurals.albums_count, it, it), style = MaterialTheme.typography.bodySmall, color = palette.muted) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ArtistRow(artist: Artist, onClick: () -> Unit, size: androidx.compose.ui.unit.Dp = 44.dp, below: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = Tokens.Spacing.screenEdge, vertical = Tokens.Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
    ) {
        ArtistImage(artist, size)
        Column(Modifier.weight(1f)) {
            Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            below()
        }
    }
}

@Composable
fun AlbumRow(album: Album, onClick: () -> Unit, coverSize: androidx.compose.ui.unit.Dp = 56.dp) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = Tokens.Spacing.screenEdge, vertical = Tokens.Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
    ) {
        CoverArt(rememberCover(album.coverId(), coverSize), coverSize, Icons.Filled.Album, radius = AppBrand.radii.miniCover)
        Column(Modifier.weight(1f)) {
            Text(album.displayName(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(album.artist.orEmpty(), style = MaterialTheme.typography.bodySmall, color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// Albums

@Composable
fun AlbumsScreen(vm: AlbumsViewModel, onBack: () -> Unit, onAlbum: (Album) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val canRate = LocalLibraryUi.current.canRate
    var menu by remember { mutableStateOf(false) }
    val favorites = state.sort == AlbumSort.FAVORITES
    ListPage(
        title = stringResource(if (favorites) R.string.favorites else R.string.albums),
        onBack = onBack,
        maxContentWidth = null,
        actions = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.sort)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    vm.sorts.filter { it != AlbumSort.HIGHEST_RATED || canRate }.forEach { sort ->
                        DropdownMenuItem(
                            text = { Text(stringResource(sort.title)) },
                            trailingIcon = { if (sort == state.sort) Icon(Icons.Filled.Check, contentDescription = stringResource(SR.string.selected)) },
                            onClick = { menu = false; vm.setSort(sort) },
                        )
                    }
                }
            }
        },
    ) { inner ->
        Box(Modifier.padding(inner)) {
            LoadStateView(state.albums.forView(), onRetry = vm::reload) { albums ->
                if (albums.isEmpty()) {
                    EmptyState(if (favorites) Icons.Filled.StarOutline else Icons.Filled.Album, stringResource(if (favorites) R.string.no_favorites else R.string.no_albums), null)
                } else {
                    AlbumGrid(albums, onAlbum, onReachEnd = vm::loadMore, footer = {
                        if (state.loadingMore) fullWidth { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                    })
                }
            }
        }
    }
}

// Songs

@Composable
fun SongsScreen(vm: SongsViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ui = LocalLibraryUi.current
    ListPage(stringResource(R.string.songs), onBack) { inner ->
        Box(Modifier.padding(inner)) {
            LoadStateView(state.songs.forView(), onRetry = vm::reload) { songs ->
                if (songs.isEmpty()) {
                    EmptyState(Icons.Filled.MusicNote, stringResource(R.string.no_songs), null)
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        item {
                            PlayShuffleButtons(true, { ui.play(songs, null, false, "songs") }, { ui.play(songs, null, true, "songs") }, Modifier.padding(Tokens.Spacing.screenEdge))
                        }
                        items(songs, key = { it.id }) { song ->
                            SongRow(song, onClick = { ui.play(songs, song.id, false, "songs") }, showsCover = true)
                            if (song.id == songs.last().id) androidx.compose.runtime.LaunchedEffect(songs.size) { vm.loadMore() }
                        }
                        if (state.loadingMore) item { Box(Modifier.fillMaxWidth().padding(Tokens.Spacing.md), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                    }
                }
            }
        }
    }
}

// Genres

@Composable
fun GenresScreen(vm: GenresViewModel, onBack: () -> Unit, onGenre: (Genre) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val palette = LocalPalette.current
    ListPage(stringResource(R.string.genres), onBack) { inner ->
        Box(Modifier.padding(inner)) {
            LoadStateView(state.forView(), onRetry = vm::load) { genres ->
                if (genres.isEmpty()) {
                    EmptyState(Icons.Filled.Piano, stringResource(R.string.no_genres), null)
                } else {
                    Refreshable(refreshing, vm::refresh) {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(genres, key = { it.id }) { genre ->
                                Column(
                                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onGenre(genre) }
                                        .padding(horizontal = Tokens.Spacing.screenEdge, vertical = Tokens.Spacing.sm),
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Text(genre.name)
                                    val albums = genre.albumCount
                                    val songs = genre.songCount
                                    if (albums != null && songs != null) {
                                        Text(
                                            stringResource(R.string.dot_join, pluralStringResource(R.plurals.albums_count, albums, albums), pluralStringResource(R.plurals.songs_count, songs, songs)),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = palette.muted,
                                        )
                                    }
                                }
                                HorizontalDivider(Modifier.padding(start = Tokens.Spacing.screenEdge), color = palette.border)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GenreScreen(name: String, vm: GenreViewModel, onBack: () -> Unit, onAlbum: (Album) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val shuffling by vm.shuffling.collectAsStateWithLifecycle()
    val ui = LocalLibraryUi.current
    ListPage(name, onBack, maxContentWidth = null) { inner ->
        Box(Modifier.padding(inner)) {
            LoadStateView(state.forView(), onRetry = vm::load) { albums ->
                AlbumGrid(albums, onAlbum, header = {
                    fullWidth {
                        FilledTonalButton(onClick = { vm.shuffle { ui.play(it, null, false, "genre:$name") } }, enabled = !shuffling, modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.minTarget)) {
                            Icon(Icons.Filled.Shuffle, contentDescription = null)
                            Text(stringResource(R.string.shuffle), modifier = Modifier.padding(start = Tokens.Spacing.sm))
                        }
                    }
                })
            }
        }
    }
}

// Favorites

@Composable
fun FavoritesScreen(vm: FavoritesViewModel, onBack: () -> Unit, onAlbum: (Album) -> Unit, onArtist: (Artist) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    ListPage(stringResource(R.string.favorites), onBack) { inner ->
        Box(Modifier.padding(inner)) {
            LoadStateView(state.forView(), onRetry = vm::load) { starred ->
                Refreshable(refreshing, vm::refresh) { FavoritesContent(starred, onAlbum, onArtist) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesContent(starred: FavoritesResult, onAlbum: (Album) -> Unit, onArtist: (Artist) -> Unit) {
    var kind by rememberSaveable { mutableIntStateOf(0) }
    val ui = LocalLibraryUi.current
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            val labels = listOf(
                stringResource(R.string.favorites_songs_n, starred.songs.size),
                stringResource(R.string.favorites_albums_n, starred.albums.size),
                stringResource(R.string.favorites_artists_n, starred.artists.size),
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(Tokens.Spacing.screenEdge)) {
                labels.forEachIndexed { index, label ->
                    SegmentedButton(selected = kind == index, onClick = { kind = index }, shape = SegmentedButtonDefaults.itemShape(index, labels.size)) {
                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        when (kind) {
            0 -> if (starred.songs.isEmpty()) {
                emptyItem(R.string.no_favorite_songs, R.string.no_favorite_songs_text)
            } else {
                item { PlayShuffleButtons(true, { ui.play(starred.songs, null, false, "favorites") }, { ui.play(starred.songs, null, true, "favorites") }, Modifier.padding(horizontal = Tokens.Spacing.screenEdge)) }
                items(starred.songs, key = { it.id }) { song -> SongRow(song, onClick = { ui.play(starred.songs, song.id, false, "favorites") }, showsCover = true) }
            }
            1 -> if (starred.albums.isEmpty()) {
                emptyItem(R.string.no_favorite_albums, R.string.no_favorite_albums_text)
            } else {
                items(starred.albums, key = { it.id }) { album -> AlbumRow(album, onClick = { onAlbum(album) }) }
            }
            else -> if (starred.artists.isEmpty()) {
                emptyItem(R.string.no_favorite_artists, R.string.no_favorite_artists_text)
            } else {
                items(starred.artists, key = { it.id }) { artist -> ArtistRow(artist, onClick = { onArtist(artist) }) }
            }
        }
    }
}

private fun LazyListScope.emptyItem(title: Int, text: Int) = item {
    EmptyState(Icons.Filled.Star, stringResource(title), stringResource(text), Modifier.padding(top = Tokens.Spacing.xl))
}

// Concerts

@Composable
fun ConcertsScreen(vm: ConcertsViewModel, onBack: () -> Unit, onAlbum: (Album) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    ListPage(stringResource(R.string.concerts), onBack, maxContentWidth = null) { inner ->
        Box(Modifier.padding(inner)) {
            LoadStateView(state.forView(), onRetry = vm::load) { concerts ->
                if (concerts.isEmpty()) {
                    EmptyState(Icons.Filled.ConfirmationNumber, stringResource(R.string.no_concerts), stringResource(R.string.no_concerts_text))
                } else {
                    AlbumGrid(concerts, onAlbum)
                }
            }
        }
    }
}

/** A tab or section that comes with a later phase. */
@Composable
fun ComingLater(title: String, text: String, icon: ImageVector, onBack: (() -> Unit)?) {
    ListPage(title, onBack) { inner ->
        Box(Modifier.padding(inner).fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(icon, title, text)
        }
    }
}

@Composable
internal fun Heading(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), modifier = modifier)
}

@Composable
internal fun VerticalSpace(height: androidx.compose.ui.unit.Dp) = Spacer(Modifier.size(height))

@Composable
internal fun FavoriteStar(kind: FavoriteKind, id: String, fallback: Boolean) {
    val ui = LocalLibraryUi.current
    val starred = ui.isFavorite(id, fallback)
    val label = when (kind) {
        FavoriteKind.ALBUM -> if (starred) R.string.unfavorite_album else R.string.favorite_album
        FavoriteKind.ARTIST -> if (starred) R.string.unfavorite_artist else R.string.favorite_artist
        FavoriteKind.SONG -> if (starred) R.string.unfavorite else R.string.favorite
    }
    IconButton(onClick = { ui.setFavorite(kind, id, !starred) }) {
        Icon(if (starred) Icons.Filled.Star else Icons.Filled.StarOutline, contentDescription = stringResource(label))
    }
}
