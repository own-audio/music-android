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
import audio.own.musicplayer.library.DownloadsLater
import audio.own.musicplayer.library.HomeViewModel
import audio.own.musicplayer.library.LibraryContent
import audio.own.musicplayer.library.LibraryEnv
import audio.own.musicplayer.library.LibraryRoutes
import audio.own.musicplayer.library.LibrarySection
import audio.own.musicplayer.library.LibrarySidebar
import audio.own.musicplayer.library.LibraryUi
import audio.own.musicplayer.library.LocalLibraryUi
import audio.own.musicplayer.library.PlaylistsLater
import audio.own.musicplayer.library.SearchViewModel
import audio.own.musicplayer.library.libraryDestinations
import audio.own.musicplayer.library.openAlbum
import audio.own.musicplayer.library.openArtist
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
        val navs = Tab.entries.associateWith { rememberNavController() }
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
                val env = remember(source) { LibraryEnv(source, container.library, { container.offline.isOffline }, onError) }
                val nav = navs.getValue(selected)
                when (selected) {
                    Tab.HOME -> NavHost(nav, startDestination = "home") {
                        composable("home") { HomeRoute(container, env, nav, onSettings) }
                        libraryDestinations(nav, env)
                    }
                    Tab.LIBRARY -> LibraryTab(container, env, nav)
                    Tab.PLAYLISTS -> NavHost(nav, startDestination = "playlists-root") {
                        composable("playlists-root") { PlaylistsLater(null) }
                        libraryDestinations(nav, env)
                    }
                    Tab.DOWNLOADS -> NavHost(nav, startDestination = "downloads-root") {
                        composable("downloads-root") { DownloadsLater(null) }
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
    val scope = rememberCoroutineScope()
    val library = container.library
    val model = container.playbackModel
    val ui = remember(playback.currentTrack?.id, playback.isPlaying, features, favorites, ratings, sync.revision, offline.isOffline) {
        LibraryUi(
            currentTrackId = playback.currentTrack?.id,
            isPlaying = playback.isPlaying,
            canRate = Capability.RATINGS in features.capabilities,
            isOffline = offline.isOffline,
            marksVersion = favorites.hashCode() + ratings.hashCode() + sync.revision,
            isFavorite = library::isFavorite,
            rating = library::rating,
            play = { songs, start, shuffle, containerId -> model.play(songs, start, shuffle, containerId) },
            enqueue = model::enqueue,
            setFavorite = { kind, id, on -> scope.launch { runCatching { library.setFavorite(kind, id, on) }.onFailure(onError) } },
            setRating = { id, rating -> scope.launch { runCatching { library.setRating(id, rating) }.onFailure(onError) } },
        )
    }
    CompositionLocalProvider(LocalLibraryUi provides ui, content = content)
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
    val vm = viewModel { HomeViewModel(env.source, env.isOffline, env.onError) }
    val content by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val shuffling by vm.shuffling.collectAsStateWithLifecycle()
    val servers by container.servers.state.collectAsStateWithLifecycle()
    val sync by container.library.sync.collectAsStateWithLifecycle()
    val offline by container.offlineState.collectAsStateWithLifecycle()
    // The catalogue caught up, or the network came or went: the shelves again.
    var seen by remember { mutableStateOf(sync.revision to offline.isOffline) }
    LaunchedEffect(sync.revision, offline.isOffline) {
        val now = sync.revision to offline.isOffline
        if (now != seen) {
            seen = now
            vm.refresh()
        }
    }
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
        onPlaylist = { nav.navigate(LibraryRoutes.PLAYLISTS) },
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
                )
            },
        ) {
            NavHost(nav, startDestination = LibraryRoutes.ARTISTS) { libraryDestinations(nav, env) }
        }
    } else {
        NavHost(nav, startDestination = "library") {
            composable("library") { LibraryContent(features.concerts) { nav.navigate(it) } }
            libraryDestinations(nav, env)
        }
    }
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
