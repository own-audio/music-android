package audio.own.musicplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import audio.own.core.designsystem.components.OfflineBanner
import audio.own.core.servers.MobileData
import audio.own.music.library.AlbumSort
import audio.own.music.library.Capability
import audio.own.musicplayer.AppContainer
import audio.own.musicplayer.R
import audio.own.musicplayer.home.HomeContent
import audio.own.musicplayer.home.HomeShelf
import audio.own.musicplayer.home.HomeUi
import audio.own.musicplayer.library.DownloadsRoute
import audio.own.musicplayer.library.prefetchCover
import audio.own.musicplayer.downloads.DownloadsModel
import audio.own.musicplayer.downloads.OfflineAwareSource
import audio.own.musicplayer.downloads.OfflineLibrary
import audio.own.musicplayer.app.UserFacingException
import audio.own.music.library.MusicSource
import audio.own.music.library.model.Song
import audio.own.core.servers.CompositeId
import audio.own.musicplayer.library.HomeViewModel
import audio.own.musicplayer.library.AddToPlaylistSheet
import audio.own.musicplayer.library.AddToPlaylistViewModel
import audio.own.musicplayer.library.ArtworkTarget
import audio.own.musicplayer.library.rememberCoverPicker
import audio.own.musicplayer.library.LibraryContent
import audio.own.musicplayer.library.LibraryEnv
import audio.own.musicplayer.library.LibraryRoutes
import audio.own.musicplayer.library.LibrarySection
import audio.own.musicplayer.library.LibrarySidebar
import audio.own.musicplayer.library.LibraryUi
import audio.own.musicplayer.library.LocalLibraryUi
import audio.own.musicplayer.library.SearchViewModel
import audio.own.musicplayer.library.libraryDestinations
import audio.own.musicplayer.library.openAlbum
import audio.own.musicplayer.library.openArtist
import audio.own.musicplayer.library.openPlaylist
import audio.own.musicplayer.player.MiniPlayer
import audio.own.musicplayer.search.SearchContent
import kotlinx.coroutines.launch
import audio.own.core.strings.R as SR

/**
 * The tabs for the active server. Switching servers rebuilds every tab (they are keyed by the
 * server) while the queue keeps playing.
 */
@UnstableApi
@Composable
fun MainTabs(container: AppContainer, onSettings: () -> Unit, onOpenPlayer: () -> Unit, onError: (Throwable) -> Unit) {
    val servers by container.servers.state.collectAsStateWithLifecycle()
    val activeId = servers.activeId
    key(activeId) {
        val source = remember(activeId) { container.library.activeSource }
        var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
        val navs = rememberTabNavs(isWide())
        ProvideLibraryUi(container, onError) {
            TabsScreen(
                selected = tab,
                onSelect = { chosen ->
                    // The selected tab chosen again goes back to its first page.
                    if (chosen == tab) navs.getValue(chosen).let { it.popBackStack(it.graph.startDestinationId, false) } else tab = chosen
                },
                above = { BannerAndMiniPlayer(container, onOpenPlayer) },
            ) { selected ->
                if (source == null) {
                    UnsupportedServer()
                    return@TabsScreen
                }
                val env = remember(source) { libraryEnv(container, source, onError) }
                val nav = navs.getValue(selected)
                when (selected) {
                    Tab.HOME -> NavHost(nav, startDestination = "home") {
                        composable("home") { HomeRoute(container, env, nav, onSettings) }
                        libraryDestinations(nav, env)
                    }
                    Tab.LIBRARY -> LibraryTab(container, env, nav)
                    Tab.PLAYLISTS -> NavHost(nav, startDestination = "playlists-root") {
                        composable("playlists-root") { audio.own.musicplayer.library.PlaylistsRoute(env, nav, null) }
                        libraryDestinations(nav, env)
                    }
                    Tab.DOWNLOADS -> NavHost(nav, startDestination = "downloads-root") {
                        composable("downloads-root") { DownloadsRoute(env, nav, null) }
                        libraryDestinations(nav, env)
                    }
                    Tab.SEARCH -> NavHost(nav, startDestination = "search") {
                        composable("search") { SearchRoute(container, env, nav) }
                        libraryDestinations(nav, env)
                    }
                }
            }
        }
    }
}

/**
 * One page stack per tab. The Library tab's pages differ with the window's width (a sidebar and
 * Artists first when wide, the section grid when not), so it keeps one stack per width: a
 * tablet turned, a foldable opened or a window resized used to bring back the other width's
 * pages into the graph that hasn't got them, and the app crashed.
 */
@Composable
internal fun rememberTabNavs(wide: Boolean): Map<Tab, NavHostController> = Tab.entries.associateWith { tab ->
    if (tab == Tab.LIBRARY) key(wide) { rememberNavController() } else rememberNavController()
}

/**
 * The library pages' dependencies for [source]'s server: browsing goes through the offline
 * library when there is no network, and the downloads and server names come along.
 */
@UnstableApi
private fun libraryEnv(container: AppContainer, source: MusicSource, onError: (Throwable) -> Unit): LibraryEnv {
    val offline = OfflineLibrary({ container.downloads.state.value }, source.serverId)
    return LibraryEnv(
        source = OfflineAwareSource(source, offline, { container.offline.isOffline }, { container.playbackSettings.current.offlineWholeLibrary }),
        library = container.library,
        isOffline = { container.offline.isOffline },
        onError = onError,
        downloads = container.downloads,
        serverName = { id -> container.servers.state.value.server(id)?.displayName },
        background = container.scope,
    )
}

/** Offline, only downloaded songs play; a tapped song that isn't starts at the next that is. */
internal fun playableOffline(songs: List<Song>, startId: String?, isDownloaded: (String) -> Boolean): Pair<List<Song>, String?> {
    var start = startId
    val index = start?.let { id -> songs.indexOfFirst { it.id == id } } ?: -1
    if (index >= 0 && !isDownloaded(songs[index].id)) start = songs.drop(index).firstOrNull { isDownloaded(it.id) }?.id
    return songs.filter { isDownloaded(it.id) } to start
}

/** A server whose kind this build can't browse yet (Subsonic and Jellyfin come with M7 and M8). */
@Composable
private fun UnsupportedServer() {
    audio.own.musicplayer.library.ComingLater(stringResource(R.string.library), stringResource(R.string.placeholder_coming), Tab.LIBRARY.icon, null)
}

@UnstableApi
@Composable
private fun ProvideLibraryUi(container: AppContainer, onError: (Throwable) -> Unit, content: @Composable () -> Unit) {
    val playback by container.playbackModel.state.collectAsStateWithLifecycle()
    val features by container.library.features.collectAsStateWithLifecycle()
    val favorites by container.library.favorites.collectAsStateWithLifecycle()
    val ratings by container.library.ratings.collectAsStateWithLifecycle()
    val sync by container.library.sync.collectAsStateWithLifecycle()
    val offline by container.offlineState.collectAsStateWithLifecycle()
    val downloadsState by container.downloads.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val library = container.library
    val model = container.playbackModel
    val downloads = container.downloads
    val background = container.scope
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    var adding by remember { mutableStateOf<List<String>?>(null) }
    val own = container.ownAudio
    val sharingChanges by own.sharingChanges.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf<Pair<ArtworkTarget, () -> Unit>?>(null) }
    val picker = rememberCoverPicker { jpeg ->
        val (target, done) = picking ?: return@rememberCoverPicker
        picking = null
        scope.launch { runCatching { own.setArtwork(target, jpeg) }.onSuccess { done() }.onFailure(onError) }
    }
    val activeServer = features.serverId
    val ui = remember(playback.currentTrack?.id, playback.isPlaying, features, favorites, ratings, sync.revision, offline.isOffline, downloadsState, sharingChanges) {
        val snapshot = downloadsState
        val isDownloaded: (String) -> Boolean = snapshot::isDownloaded
        val noneDownloaded = { onError(UserFacingException(R.string.offline_none_downloaded)) }
        LibraryUi(
            currentTrackId = playback.currentTrack?.id,
            isPlaying = playback.isPlaying,
            canRate = Capability.RATINGS in features.capabilities,
            isOffline = offline.isOffline,
            marksVersion = favorites.hashCode() + ratings.hashCode() + sync.revision,
            isFavorite = library::isFavorite,
            rating = library::rating,
            play = { songs, start, shuffle, containerId ->
                if (!offline.isOffline) {
                    model.play(songs, start, shuffle, containerId)
                } else {
                    val (playable, first) = playableOffline(songs, start, isDownloaded)
                    if (playable.isEmpty()) noneDownloaded() else model.play(playable, first, shuffle, containerId)
                }
            },
            enqueue = { songs, next ->
                val playable = if (offline.isOffline) songs.filter { isDownloaded(it.id) } else songs
                if (playable.isEmpty()) noneDownloaded() else model.enqueue(playable, next)
            },
            setFavorite = { kind, id, on -> scope.launch { runCatching { library.setFavorite(kind, id, on) }.onFailure(onError) } },
            setRating = { id, rating, kind -> scope.launch { runCatching { library.setRating(id, rating, kind) }.onFailure(onError) } },
            canDownload = true,
            downloadsVersion = snapshot.hashCode(),
            isDownloaded = isDownloaded,
            collectionProgress = snapshot::progress,
            isCollectionDownloading = snapshot::isDownloading,
            downloadSong = { song ->
                prefetchCover(context, song.coverArt ?: song.albumId)
                background.launch { downloads.download(song) }
            },
            downloadAlbum = { album, songs ->
                prefetchCover(context, album.coverArt ?: album.id)
                background.launch { downloads.download(album, songs) }
            },
            downloadPlaylist = { playlist, songs ->
                playlist.coverArt?.let { prefetchCover(context, it) }
                background.launch { downloads.download(playlist, songs) }
            },
            canInstantMix = Capability.INSTANT_MIX in features.capabilities,
            instantMix = { itemId ->
                scope.launch {
                    try {
                        val songs = library.activeSource?.instantMix(itemId).orEmpty()
                        if (songs.isEmpty()) onError(audio.own.music.library.SourceError.NotFound()) else model.play(songs, null, false, "mix:$itemId")
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        onError(e)
                    }
                }
            },
            addToPlaylist = { ids -> adding = ids },
            removeDownload = { id -> background.launch { downloads.remove(id) } },
            removeCollection = { id -> background.launch { downloads.removeCollection(id) } },
            sharingOf = own::sharing,
            canShare = activeServer != null && own.canShare(activeServer),
            setShared = { songs, shared -> scope.launch { runCatching { own.setShared(songs, shared) }.onFailure(onError) } },
            setAlbumShared = { album, songs, shared -> scope.launch { runCatching { own.setAlbumShared(album.id, songs, shared) }.onFailure(onError) } },
            canChoosePictures = activeServer != null && own.canChoosePictures(activeServer),
            choosePicture = { target, done ->
                picking = target to done
                picker()
            },
            canIdentify = features.identify != null && !offline.isOffline,
            identifySong = own::identify,
            undoIdentify = own::askUndoIdentify,
            identifyAlbum = { album ->
                val source = activeServer?.let(own::identifySource)
                if (source != null) own.beginRun(activeServer, source.albumScope(album.id, album.name))
            },
            identifyArtist = { artist ->
                val source = activeServer?.let(own::identifySource)
                if (source != null) own.beginRun(activeServer, source.artistScope(artist.id, artist.name))
            },
        )
    }
    CompositionLocalProvider(LocalLibraryUi provides ui) {
        content()
        adding?.let { ids ->
            val source = library.activeSource
            if (source != null) {
                val vm = viewModel(key = "add-${ids.hashCode()}") {
                    AddToPlaylistViewModel(source, ids, library.activeServer?.auth?.userName, onError, background)
                }
                val state by vm.state.collectAsStateWithLifecycle()
                LaunchedEffect(state.done) { if (state.done) adding = null }
                AddToPlaylistSheet(state, onDismiss = { adding = null }, onRetry = vm::load, onAdd = vm::add, onCreate = vm::create)
            }
        }
    }
}

@UnstableApi
@Composable
private fun BannerAndMiniPlayer(container: AppContainer, onOpenPlayer: () -> Unit) {
    val offline by container.offlineState.collectAsStateWithLifecycle()
    if (offline.isOffline) {
        when {
            !offline.byChoice -> OfflineBanner(stringResource(R.string.offline_no_network))
            offline.offlineMode -> OfflineBanner(stringResource(R.string.offline_by_choice), actionLabel = stringResource(SR.string.turn_off)) {
                container.playbackSettings.update { it.copy(offlineMode = false) }
            }
            else -> OfflineBanner(stringResource(R.string.offline_mobile_data), actionLabel = stringResource(R.string.stream)) {
                container.playbackSettings.update { it.copy(mobileData = MobileData.SAVER) }
            }
        }
    }
    val model = container.playbackModel
    val state by model.state.collectAsStateWithLifecycle()
    MiniPlayer(state, model::progress, onToggle = model::togglePlayPause, onNext = model::next, onOpen = onOpenPlayer)
}

@UnstableApi
@Composable
private fun HomeRoute(container: AppContainer, env: LibraryEnv, nav: NavHostController, onSettings: () -> Unit) {
    val vm = viewModel { HomeViewModel(env.source, env.isOffline, env.onError, { offlineHome(container, env.source) }, container.log) }
    val content by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val shuffling by vm.shuffling.collectAsStateWithLifecycle()
    val servers by container.servers.state.collectAsStateWithLifecycle()
    val sync by container.library.sync.collectAsStateWithLifecycle()
    val offline by container.offlineState.collectAsStateWithLifecycle()
    // The catalogue caught up, or the network came or went: the shelves again.
    LaunchedEffect(sync.revision, offline.isOffline) { vm.showFor(sync.revision to offline.isOffline) }
    val model = container.playbackModel
    HomeContent(
        ui = HomeUi(
            content = content,
            servers = servers.servers,
            activeId = servers.activeId,
            offlineMode = offline.offlineMode,
            isOffline = offline.isOffline,
            shuffling = shuffling,
            refreshing = refreshing,
            firstSync = sync.running && sync.lastSyncedAtMs == null,
        ),
        onSelectServer = { id -> container.scope.launch { container.servers.select(id) } },
        onOfflineMode = { on -> container.playbackSettings.update { it.copy(offlineMode = on) } },
        onSettings = onSettings,
        onShuffleAll = { vm.shuffleAll { model.play(it, containerId = "shuffle-all") } },
        onRefresh = {
            container.library.syncActive(force = true)
            vm.refresh()
        },
        onRetry = vm::load,
        onAlbum = { nav.openAlbum(env, it) },
        onPlaylist = { nav.openPlaylist(env, it) },
        onShelf = { shelf ->
            when (shelf) {
                HomeShelf.RECENTLY_PLAYED -> nav.navigate(LibraryRoutes.albums(AlbumSort.RECENTLY_PLAYED))
                HomeShelf.NEWEST -> nav.navigate(LibraryRoutes.albums(AlbumSort.NEWEST))
                else -> nav.navigate(LibraryRoutes.PLAYLISTS)
            }
        },
    )
}

@UnstableApi
@Composable
private fun LibraryTab(container: AppContainer, env: LibraryEnv, nav: NavHostController) {
    val features by container.library.features.collectAsStateWithLifecycle()
    if (isWide()) {
        val entry by nav.currentBackStackEntryAsState()
        WithSidebar(
            sidebar = {
                LibrarySidebar(
                    features.concerts,
                    selected = entry?.destination?.route?.let { route -> sidebarRoute(route, entry?.arguments?.getString("sort")) },
                    onOpen = { route -> nav.navigate(route) { popUpTo(nav.graph.startDestinationId) { inclusive = true } } },
                    identify = features.identify != null && !container.offline.isOffline,
                )
            },
        ) {
            NavHost(nav, startDestination = LibraryRoutes.ARTISTS) { libraryDestinations(nav, env) }
        }
    } else {
        NavHost(nav, startDestination = "library") {
            composable("library") {
                LaunchedEffect(Unit) { container.library.refreshIdentify() }
                LibraryContent(features.concerts, identify = features.identify != null && !container.offline.isOffline) { nav.navigate(it) }
            }
            libraryDestinations(nav, env)
        }
    }
}

/** Home with no network: the albums by download date, the downloaded playlists. */
@UnstableApi
private suspend fun offlineHome(container: AppContainer, source: MusicSource): audio.own.musicplayer.library.HomeContentState {
    val offline = OfflineLibrary({ container.downloads.state.value }, source.serverId)
    return audio.own.musicplayer.library.HomeContentState(newest = offline.albumsByDownloadDate().take(20), playlists = offline.playlists())
}

private fun sidebarRoute(route: String, sort: String?): String = when (route) {
    LibraryRoutes.ALBUMS -> LibrarySection.ALBUMS.route.takeIf { sort == AlbumSort.NAME.name } ?: route
    else -> route
}

@UnstableApi
@Composable
private fun SearchRoute(container: AppContainer, env: LibraryEnv, nav: NavHostController) {
    val vm = viewModel { SearchViewModel(env.source, env.isOffline) }
    val state by vm.state.collectAsStateWithLifecycle()
    val servers by container.servers.state.collectAsStateWithLifecycle()
    val offline by container.offlineState.collectAsStateWithLifecycle()
    SearchContent(
        state = state,
        serverName = servers.active?.displayName.orEmpty(),
        isOffline = offline.isOffline,
        onQuery = vm::setQuery,
        onArtist = { nav.openArtist(env, it) },
        onAlbum = { nav.openAlbum(env, it) },
    )
}
