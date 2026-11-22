package audio.own.musicplayer.library

import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import audio.own.musicplayer.downloads.DownloadedCollectionContent
import audio.own.musicplayer.downloads.DownloadsContent
import audio.own.musicplayer.downloads.DownloadsContentState
import audio.own.musicplayer.downloads.DownloadsModel
import audio.own.musicplayer.downloads.OfflineAwareSource
import audio.own.musicplayer.downloads.songs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import audio.own.music.library.AlbumSort
import audio.own.music.library.MusicSource
import audio.own.music.library.model.Album
import audio.own.music.library.model.Artist
import audio.own.music.library.model.Genre
import audio.own.music.library.model.Playlist
import audio.own.music.library.SmartPlaylistSource
import audio.own.kit.ownaudio.SmartRule
import androidx.compose.runtime.setValue
import audio.own.musicplayer.R

/** The pages every tab can lead to, as the iOS `Route` cases. Ids are composite and encoded. */
object LibraryRoutes {
    const val ARTISTS = "artists"
    const val SONGS = "songs"
    const val GENRES = "genres"
    const val FAVORITES = "favorites"
    const val CONCERTS = "concerts"
    const val DOWNLOADS = "downloads"
    const val PLAYLISTS = "playlists"
    const val ALBUMS = "albums/{sort}"
    const val ALBUM = "album/{id}"
    const val ARTIST = "artist/{id}"
    const val GENRE = "genre/{id}/{name}"
    const val DOWNLOADED = "downloaded/{id}"
    const val PLAYLIST = "playlist/{id}"
    const val SMART = "smart/{key}"

    fun albums(sort: AlbumSort) = "albums/${sort.name}"
    fun album(id: String) = "album/${Uri.encode(id)}"
    fun artist(id: String) = "artist/${Uri.encode(id)}"
    fun genre(genre: Genre) = "genre/${Uri.encode(genre.id)}/${Uri.encode(genre.name)}"
    fun downloaded(collectionId: String) = "downloaded/${Uri.encode(collectionId)}"
    fun playlist(id: String) = "playlist/${Uri.encode(id)}"
    fun smart(key: String) = "smart/${Uri.encode(key)}"
}

/** What the library pages are built from. */
class LibraryEnv(
    /** What the screens browse: offline, the downloads or the catalogue (OfflineAwareSource). */
    val source: MusicSource,
    val library: LibraryModel,
    val isOffline: () -> Boolean,
    val onError: (Throwable) -> Unit,
    val downloads: DownloadsModel? = null,
    /** A server's name, or null for one that was removed. */
    val serverName: (String) -> String? = { null },
    /** For changes that outlive the screen (removing downloads). */
    val background: CoroutineScope? = null,
) {
    /** The server's own source, for the features only it has (concerts). */
    val rawSource: MusicSource get() = (source as? OfflineAwareSource)?.delegate ?: source

    /** The signed-in account's name: whose playlists are whose. */
    val username: String? get() = library.server(rawSource.serverId)?.auth?.userName
}

fun NavHostController.openPlaylist(env: LibraryEnv, playlist: Playlist) {
    env.library.learn(playlist)
    navigate(LibraryRoutes.playlist(playlist.id))
}

fun NavHostController.openSmart(env: LibraryEnv, target: SmartTarget) {
    env.library.learn(target.key, target)
    navigate(LibraryRoutes.smart(target.key))
}

private fun NavBackStackEntry.arg(name: String): String = arguments?.getString(name)?.let(Uri::decode).orEmpty()

fun NavHostController.openAlbum(env: LibraryEnv, album: Album) {
    env.library.learn(album)
    navigate(LibraryRoutes.album(album.id))
}

fun NavHostController.openArtist(env: LibraryEnv, artist: Artist) {
    env.library.learn(artist)
    navigate(LibraryRoutes.artist(artist.id))
}

fun NavGraphBuilder.libraryDestinations(nav: NavHostController, env: LibraryEnv) {
    val back: () -> Unit = { nav.popBackStack() }
    composable(LibraryRoutes.ARTISTS) {
        val vm = viewModel { ArtistsViewModel(env.source) }
        ArtistsScreen(vm, back) { nav.openArtist(env, it) }
    }
    composable(LibraryRoutes.ALBUMS) { entry ->
        val sort = AlbumSort.entries.firstOrNull { it.name == entry.arg("sort") } ?: AlbumSort.NAME
        val vm = viewModel { AlbumsViewModel(env.source, sort) }
        AlbumsScreen(vm, back) { nav.openAlbum(env, it) }
    }
    composable(LibraryRoutes.SONGS) {
        val vm = viewModel { SongsViewModel(env.source) }
        SongsScreen(vm, back)
    }
    composable(LibraryRoutes.GENRES) {
        val vm = viewModel { GenresViewModel(env.source) }
        GenresScreen(vm, back) { nav.navigate(LibraryRoutes.genre(it)) }
    }
    composable(LibraryRoutes.GENRE) { entry ->
        val genre = Genre(entry.arg("id"), entry.arg("name"))
        val vm = viewModel { GenreViewModel(env.source, genre, env.onError) }
        GenreScreen(genre.name, vm, back) { nav.openAlbum(env, it) }
    }
    composable(LibraryRoutes.FAVORITES) {
        val vm = viewModel { FavoritesViewModel(env.source) }
        FavoritesScreen(vm, back, onAlbum = { nav.openAlbum(env, it) }, onArtist = { nav.openArtist(env, it) })
    }
    composable(LibraryRoutes.CONCERTS) {
        val vm = viewModel { ConcertsViewModel(env.rawSource) }
        ConcertsScreen(vm, back) { nav.openAlbum(env, it) }
    }
    composable(LibraryRoutes.ALBUM) { entry ->
        val id = entry.arg("id")
        val vm = viewModel { AlbumViewModel(env.source, id) }
        AlbumScreen(vm, env.library.album(id), back) { nav.openArtist(env, it) }
    }
    composable(LibraryRoutes.ARTIST) { entry ->
        val id = entry.arg("id")
        val artist = env.library.artist(id) ?: Artist(id, "")
        val vm = viewModel { ArtistViewModel(env.source, id, env.onError) }
        ArtistScreen(vm, artist, back) { nav.openAlbum(env, it) }
    }
    composable(LibraryRoutes.DOWNLOADS) { DownloadsRoute(env, nav, back) }
    composable(LibraryRoutes.DOWNLOADED) { entry -> DownloadedCollectionRoute(env, entry.arg("id"), back) }
    composable(LibraryRoutes.PLAYLISTS) { PlaylistsRoute(env, nav, back) }
    composable(LibraryRoutes.PLAYLIST) { entry -> PlaylistRoute(env, entry.arg("id"), back) }
    composable(LibraryRoutes.SMART) { entry ->
        val target = env.library.smartTarget(entry.arg("key"))
        if (target == null) {
            androidx.compose.runtime.LaunchedEffect(Unit) { back() }
        } else {
            SmartPlaylistRoute(env, target, back)
        }
    }
}

/** The Playlists tab's first page, and the Library's Playlists. */
@Composable
fun PlaylistsRoute(env: LibraryEnv, nav: NavHostController, onBack: (() -> Unit)?) {
    val smart = env.rawSource as? SmartPlaylistSource
    val vm = viewModel { PlaylistsViewModel(env.source, smart, env.isOffline, env.onError) }
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    var editing by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    // Back from a playlist or a smart one: what changed there shows here.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose {}
    }
    PlaylistsContent(
        state = state,
        username = env.username,
        refreshing = refreshing,
        onBack = onBack,
        onRefresh = vm::refresh,
        onRetry = vm::load,
        onPlaylist = { nav.openPlaylist(env, it) },
        onSmart = { nav.openSmart(env, it) },
        onCreate = vm::create,
        onNewSmart = { editing = true },
    )
    if (editing && smart != null) {
        RuleEditorOverlay(smart, SmartDraft(null, "", SmartRule(), false), onDismiss = { editing = false }) { saved ->
            editing = false
            vm.refresh()
            nav.openSmart(env, SmartTarget.Saved(saved))
        }
    }
}

@Composable
fun PlaylistRoute(env: LibraryEnv, id: String, onBack: () -> Unit) {
    val vm = viewModel { PlaylistViewModel(env.source, env.rawSource, id, env.library.playlist(id), env.username, env.onError) }
    val state by vm.state.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    val pickCover = rememberCoverPicker(vm::setCover)
    PlaylistContent(
        state = state,
        actions = PlaylistActions(vm.isOwn, vm.canChooseCover, vm.canShare, vm.canReorder),
        onBack = onBack,
        onRetry = vm::load,
        onRename = vm::rename,
        onChooseCover = pickCover,
        onShare = vm::setShared,
        onDelete = vm::delete,
        onRemove = vm::remove,
        onMove = vm::move,
    )
}

@Composable
fun SmartPlaylistRoute(env: LibraryEnv, target: SmartTarget, onBack: () -> Unit) {
    val source = env.rawSource as? SmartPlaylistSource ?: return
    val vm = viewModel(key = target.key) { SmartPlaylistViewModel(source, target, env.onError) }
    val state by vm.state.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    var draft by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<SmartDraft?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val presetName: (audio.own.music.library.SmartPresetInfo) -> String = { p -> Presets.name(p.slug)?.let(context::getString) ?: p.name }
    SmartPlaylistContent(
        state = state,
        onBack = onBack,
        onReload = vm::load,
        onGenre = vm::chooseGenre,
        onClearGenre = vm::clearGenre,
        onEditRule = { draft = vm.draft(presetName) },
        onFreeze = vm::freeze,
        onDelete = vm::delete,
        onSaveAsSmart = { draft = vm.draft(presetName) },
        onDismissSaved = vm::dismissSaved,
    )
    draft?.let { d ->
        RuleEditorOverlay(source, d, onDismiss = { draft = null }) { saved ->
            draft = null
            vm.saved(saved)
        }
    }
}

/** The rule editor over the page that opened it, full screen as the iOS sheet. */
@Composable
fun RuleEditorOverlay(source: SmartPlaylistSource, draft: SmartDraft, onDismiss: () -> Unit, onSaved: (audio.own.music.library.SmartPlaylistInfo) -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
            val vm = viewModel(key = "rule-${draft.hashCode()}") { SmartRuleEditorViewModel(source, draft) }
            val state by vm.state.collectAsStateWithLifecycle()
            androidx.compose.runtime.LaunchedEffect(state.saved) { state.saved?.let(onSaved) }
            SmartRuleEditorContent(state, onCancel = onDismiss, onSave = vm::save, onName = vm::setName, onShared = vm::setShared, onEdit = vm::edit)
        }
    }
}

/** The Downloads tab (and the Library's Downloads page): everything from the index. */
@Composable
fun DownloadsRoute(env: LibraryEnv, nav: NavHostController, onBack: (() -> Unit)?) {
    val model = env.downloads
    val ui = LocalLibraryUi.current
    if (model == null) {
        DownloadsContent(DownloadsContentState(), onBack, {}, {}, { _, _ -> }, {}, {}, {})
        return
    }
    val snapshot by model.state.collectAsStateWithLifecycle()
    val state = remember(snapshot) { DownloadsContentState.of(snapshot, env.serverName) }
    val run: (suspend () -> Unit) -> Unit = { work -> env.background?.launch { work() } }
    DownloadsContent(
        state = state,
        onBack = onBack,
        onRetryFailed = { run { model.retryFailed() } },
        onOpen = { nav.navigate(LibraryRoutes.downloaded(it)) },
        onPlaySongs = { songs, start -> ui.play(songs, start, false, null) },
        onRemoveCollection = { id -> run { model.removeCollection(id) } },
        onRemoveSong = { id -> run { model.remove(id) } },
        onRemoveAll = { run { model.removeAll() } },
    )
}

@Composable
fun DownloadedCollectionRoute(env: LibraryEnv, collectionId: String, onBack: () -> Unit) {
    val model = env.downloads ?: return
    val ui = LocalLibraryUi.current
    val snapshot by model.state.collectAsStateWithLifecycle()
    val view = snapshot.collection(collectionId)
    val songs = remember(snapshot, collectionId) { view?.let { snapshot.songs(it).map { s -> s.song } }.orEmpty() }
    DownloadedCollectionContent(
        name = view?.collection?.name,
        artist = view?.collection?.subtitle,
        coverId = view?.collection?.coverId,
        songs = songs,
        onBack = onBack,
        onPlay = { start, shuffle -> ui.play(songs, start, shuffle, collectionId) },
        onRemove = {
            env.background?.launch { model.removeCollection(collectionId) }
            onBack()
        },
    )
}

