package audio.own.musicplayer.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import audio.own.core.designsystem.components.CoverArt
import audio.own.core.designsystem.components.LoadStateView
import audio.own.core.designsystem.components.SetDownloadButton
import audio.own.core.designsystem.components.downloadingText
import audio.own.musicplayer.downloads.DownloadsModel
import audio.own.core.designsystem.theme.AppBrand
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.music.library.AlbumWithSongs
import audio.own.music.library.FavoriteKind
import audio.own.music.library.model.Album
import audio.own.music.library.model.Artist
import audio.own.musicplayer.R
import audio.own.musicplayer.app.Load
import audio.own.musicplayer.app.forView
import audio.own.core.strings.R as SR

@Composable
fun AlbumScreen(vm: AlbumViewModel, known: Album?, onBack: () -> Unit, onArtist: (Artist) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    AlbumContent(state, known, onBack, onArtist, onRetry = vm::load, onChanged = vm::refresh)
}

/**
 * An album: cover, title, artist (opens the artist), year, Play / Shuffle, the songs split by
 * disc, and "N songs, M min". Top bar: the star and the ⋯ menu (Play Next, Add to Queue, Rate,
 * and on own.audio the cover, sharing and identify of the listener's own songs on it).
 */
@Composable
fun AlbumContent(state: Load<AlbumWithSongs>, known: Album?, onBack: () -> Unit, onArtist: (Artist) -> Unit, onRetry: () -> Unit, onChanged: () -> Unit = {}) {
    val loaded = (state as? Load.Loaded)?.value
    val album = loaded?.album ?: known
    val ui = LocalLibraryUi.current
    val palette = LocalPalette.current
    var menu by remember { mutableStateOf(false) }
    var rating by remember { mutableStateOf(false) }
    val title = album?.displayName().orEmpty()
    val collectionId = album?.let { DownloadsModel.albumCollection(it.id) }.orEmpty()
    val progress = album?.let { ui.collectionProgress(collectionId) }
    ListPage(
        title = title,
        onBack = onBack,
        actions = {
            val songs = loaded?.songs.orEmpty()
            if (album != null && ui.canDownload && (songs.isNotEmpty() || progress != null)) {
                val (done, total) = progress ?: (0 to songs.size)
                SetDownloadButton(
                    done = done,
                    total = total,
                    downloading = progress != null && ui.isCollectionDownloading(collectionId),
                    onDownload = { if (!ui.isOffline) ui.downloadAlbum(album, songs) },
                    onRemove = { ui.removeCollection(collectionId) },
                )
            }
            if (album != null) FavoriteStar(FavoriteKind.ALBUM, album.id, album.isFavorite)
            if (album != null) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(SR.string.more)) }
                    DropdownMenu(expanded = menu && !rating, onDismissRequest = { menu = false }) {
                        val songs = loaded?.songs.orEmpty()
                        if (songs.isNotEmpty()) QueueMenuItems(songs) { menu = false }
                        if (ui.canRate) RatingMenuItem(ui.rating(album.id, album.rating)) { rating = true }
                        InstantMixItem(album.id) { menu = false }
                        val add = ui.addToPlaylist
                        if (add != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.add_album_to_playlist)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null) },
                                enabled = songs.isNotEmpty(),
                                onClick = { menu = false; add(songs.map { it.id }) },
                            )
                        }
                        OwnAlbumItems(album, songs, onDismiss = { menu = false }, onChanged = onChanged)
                    }
                    if (rating) RatingChoices(ui.rating(album.id, album.rating), onDismiss = { rating = false; menu = false }) { ui.setRating(album.id, it, FavoriteKind.ALBUM) }
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
                    CoverArt(rememberCover(album?.coverId(), 240.dp), 240.dp, Icons.Filled.Album, radius = AppBrand.radii.cover)
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs)) {
                        Text(title, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
                        album?.artist?.let { artist ->
                            val id = album.artistId
                            if (id != null) {
                                Text(artist, style = MaterialTheme.typography.titleMedium, color = palette.accent, textAlign = TextAlign.Center, modifier = Modifier.clickable { onArtist(Artist(id, artist)) })
                            } else {
                                Text(artist, style = MaterialTheme.typography.titleMedium, color = palette.muted, textAlign = TextAlign.Center)
                            }
                        }
                        album?.year?.let { Text(it.toString(), style = MaterialTheme.typography.bodyMedium, color = palette.muted) }
                        if (progress != null && progress.first < progress.second && ui.isCollectionDownloading(collectionId)) {
                            Text(downloadingText(progress.first, progress.second), style = MaterialTheme.typography.bodySmall, color = palette.muted)
                        }
                    }
                    val songs = loaded?.songs.orEmpty()
                    val container = album?.let { "album:${it.id}" }
                    PlayShuffleButtons(songs.isNotEmpty(), { ui.play(songs, null, false, container) }, { ui.play(songs, null, true, container) })
                }
            }
            when (state) {
                is Load.Loaded -> {
                    val songs = state.value.songs
                    val container = "album:${state.value.album.id}"
                    val discs = songs.groupBy { it.discNumber ?: 1 }
                    for ((disc, discSongs) in discs.toSortedMap()) {
                        if (discs.size > 1) {
                            item(key = "disc-$disc") {
                                Text(
                                    stringResource(R.string.disc_n, disc),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = palette.muted,
                                    modifier = Modifier.padding(start = Tokens.Spacing.screenEdge, top = Tokens.Spacing.lg, bottom = Tokens.Spacing.xs).semantics { heading() },
                                )
                            }
                        }
                        items(discSongs, key = { it.id }) { song -> SongRow(song, onClick = { ui.play(songs, song.id, false, container) }) }
                    }
                    item {
                        Text(songsSummary(songs), style = MaterialTheme.typography.bodySmall, color = palette.muted, modifier = Modifier.padding(Tokens.Spacing.screenEdge))
                    }
                }
                else -> item {
                    Box(Modifier.fillMaxWidth().padding(Tokens.Spacing.xl), contentAlignment = Alignment.Center) {
                        LoadStateView(state.forView(), onRetry = onRetry) {}
                    }
                }
            }
        }
    }
}

/**
 * Choose Album Cover… and Share My Songs (or Stop Sharing My Songs) when some of the album's
 * songs are the listener's; Identify Album unless every song is known to be someone else's.
 */
@Composable
private fun OwnAlbumItems(album: Album, songs: List<audio.own.music.library.model.Song>, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val ui = LocalLibraryUi.current
    val mine = songs.filter(ui::isMine)
    if (ui.canChoosePictures && mine.isNotEmpty()) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.choose_album_cover)) },
            leadingIcon = { Icon(Icons.Filled.Image, contentDescription = null) },
            onClick = { onDismiss(); ui.choosePicture(ArtworkTarget.Album(album, songs), onChanged) },
        )
    }
    if (ui.canShare && mine.isNotEmpty()) {
        val allShared = mine.all { ui.sharingOf(it) == audio.own.music.library.model.Sharing.SHARED }
        DropdownMenuItem(
            text = { Text(stringResource(if (allShared) R.string.stop_sharing_my_songs else R.string.share_my_songs)) },
            leadingIcon = { Icon(if (allShared) Icons.Filled.Person else Icons.Filled.Group, contentDescription = null) },
            onClick = { onDismiss(); ui.setAlbumShared(album, songs, !allShared) },
        )
    }
    if (ui.canIdentify && (songs.isEmpty() || songs.any { ui.sharingOf(it)?.isMine != false })) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.identify_album)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            onClick = { onDismiss(); ui.identifyAlbum(album) },
        )
    }
}

/**
 * An artist: picture, name, Play / Shuffle all albums, the album grid; the star in the bar, and
 * on own.audio a menu with Choose Picture… and Identify Artist.
 */
@Composable
fun ArtistScreen(vm: ArtistViewModel, artist: Artist, onBack: () -> Unit, onAlbum: (Album) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val starting by vm.starting.collectAsStateWithLifecycle()
    ArtistContent(state, starting, artist, onBack, onAlbum, onRetry = vm::load, onPlayAll = vm::playAll)
}

@Composable
fun ArtistContent(
    state: Load<List<Album>>,
    starting: Boolean,
    known: Artist,
    onBack: () -> Unit,
    onAlbum: (Album) -> Unit,
    onRetry: () -> Unit,
    onPlayAll: ((List<audio.own.music.library.model.Song>) -> Unit) -> Unit,
) {
    val ui = LocalLibraryUi.current
    // A picture chosen here: drawn from the artist's own id from then on, whatever it had.
    var pictured by remember { mutableStateOf(false) }
    val artist = if (pictured) known.copy(coverArt = known.coverArt ?: known.id) else known
    var menu by remember { mutableStateOf(false) }
    ListPage(artist.name, onBack, actions = {
        FavoriteStar(FavoriteKind.ARTIST, artist.id, artist.isFavorite)
        if (ui.canChoosePictures || ui.canIdentify) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(SR.string.more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (ui.canChoosePictures) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.choose_picture)) },
                            leadingIcon = { Icon(Icons.Filled.Image, contentDescription = null) },
                            onClick = { menu = false; ui.choosePicture(ArtworkTarget.ArtistPicture(artist)) { pictured = true } },
                        )
                    }
                    if (ui.canIdentify) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.identify_artist)) },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            onClick = { menu = false; ui.identifyArtist(artist) },
                        )
                    }
                }
            }
        }
    }) { inner ->
        val albums = (state as? Load.Loaded)?.value.orEmpty()
        AlbumGrid(albums, onAlbum, modifier = Modifier.padding(inner), header = {
            fullWidth {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg), modifier = Modifier.fillMaxWidth()) {
                    ArtistImage(artist, 160.dp)
                    Text(artist.name, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.Center)
                    val container = "artist:${artist.id}"
                    PlayShuffleButtons(
                        enabled = !starting && albums.isNotEmpty(),
                        onPlay = { onPlayAll { ui.play(it, null, false, container) } },
                        onShuffle = { onPlayAll { ui.play(it, null, true, container) } },
                    )
                }
            }
            if (state !is Load.Loaded) {
                fullWidth {
                    Box(Modifier.fillMaxWidth().padding(Tokens.Spacing.xl), contentAlignment = Alignment.Center) {
                        if (state is Load.Loading) CircularProgressIndicator() else LoadStateView(state.forView(), onRetry = onRetry) {}
                    }
                }
            }
        })
    }
}
