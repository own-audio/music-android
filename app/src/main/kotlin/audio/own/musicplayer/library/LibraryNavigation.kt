package audio.own.musicplayer.library

import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.runtime.Composable
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

    fun albums(sort: AlbumSort) = "albums/${sort.name}"
    fun album(id: String) = "album/${Uri.encode(id)}"
    fun artist(id: String) = "artist/${Uri.encode(id)}"
    fun genre(genre: Genre) = "genre/${Uri.encode(genre.id)}/${Uri.encode(genre.name)}"
}

/** What the library pages are built from. */
class LibraryEnv(
    val source: MusicSource,
    val library: LibraryModel,
    val isOffline: () -> Boolean,
    val onError: (Throwable) -> Unit,
)

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
        val vm = viewModel { ConcertsViewModel(env.source) }
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
    composable(LibraryRoutes.DOWNLOADS) { DownloadsLater(back) }
    composable(LibraryRoutes.PLAYLISTS) { PlaylistsLater(back) }
}

/** The Downloads tab until F5 builds it. */
@Composable
fun DownloadsLater(onBack: (() -> Unit)?) =
    ComingLater(stringResource(R.string.downloads), stringResource(R.string.coming_with_downloads), Icons.Filled.DownloadForOffline, onBack)

/** The Playlists tab until M5 builds it. */
@Composable
fun PlaylistsLater(onBack: (() -> Unit)?) =
    ComingLater(stringResource(R.string.playlists), stringResource(R.string.coming_with_playlists), Icons.AutoMirrored.Filled.QueueMusic, onBack)
