package audio.own.musicplayer.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import audio.own.music.library.AlbumSort
import audio.own.music.library.AlbumWithSongs
import audio.own.music.library.ConcertSource
import audio.own.music.library.MusicSource
import audio.own.music.library.SourceError
import audio.own.music.library.model.Album
import audio.own.music.library.model.Artist
import audio.own.music.library.model.Genre
import audio.own.music.library.model.Playlist
import audio.own.music.library.model.SearchResult
import audio.own.music.library.model.Song
import audio.own.musicplayer.app.AppLog
import audio.own.musicplayer.app.Load
import audio.own.musicplayer.app.userMessage
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Runs [work], turning a failure (not a cancellation) into a sentence for the screen. */
internal suspend fun <T> loading(work: suspend () -> T): Load<T> = try {
    Load.Loaded(work())
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    Load.Failed(error.userMessage())
}

/** A screen that fetches once, and again on Try Again or pull to refresh. */
open class LoadViewModel<T>(private val loader: suspend () -> T) : ViewModel() {
    private val _state = MutableStateFlow<Load<T>>(Load.Loading)
    val state: StateFlow<Load<T>> = _state.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private var job: Job? = null

    init {
        load()
    }

    /** Try Again: the spinner, then the answer. */
    fun load() {
        job?.cancel()
        _state.value = Load.Loading
        job = viewModelScope.launch { _state.value = loading(loader) }
    }

    /** Pull to refresh: what is shown stays until the new answer is in. */
    fun refresh() {
        job?.cancel()
        _refreshing.value = true
        job = viewModelScope.launch {
            val next = loading(loader)
            if (next is Load.Loaded || _state.value !is Load.Loaded) _state.value = next
            _refreshing.value = false
        }
    }
}

// Home

data class HomeContentState(
    val recent: List<Album> = emptyList(),
    val newest: List<Album> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
) {
    val isEmpty: Boolean get() = recent.isEmpty() && newest.isEmpty() && playlists.isEmpty()
}

/**
 * Shuffle All and the three shelves. Offline only downloads count: the albums by download date
 * and the downloaded playlists ([offlineHome]); Shuffle All draws from the downloaded songs.
 */
class HomeViewModel(
    private val source: MusicSource,
    private val isOffline: () -> Boolean,
    private val onError: (Throwable) -> Unit = {},
    offlineHome: suspend () -> HomeContentState = { HomeContentState() },
    private val log: AppLog? = null,
) : LoadViewModel<HomeContentState>({ if (isOffline()) offlineHome() else home(source, false) }) {
    private val _shuffling = MutableStateFlow(false)
    val shuffling: StateFlow<Boolean> = _shuffling.asStateFlow()

    // Kept here, not in the screen: the network can go while another tab is open and Home's
    // screen is gone, and on the way back its shelves were still the online ones.
    private var shownFor: Any? = null

    /** The catalogue's revision and the network as the screen sees them: the shelves again when either changed since. */
    fun showFor(key: Any) {
        val was = shownFor
        shownFor = key
        if (was != null && was != key) refresh()
    }

    /** A random hundred from the whole library: the server picks, so it works for any size. */
    fun shuffleAll(play: (List<Song>) -> Unit) {
        if (_shuffling.value) return
        _shuffling.value = true
        viewModelScope.launch {
            try {
                play(source.randomSongs(100))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log?.error("library", "Shuffle All failed: ${AppLog.describe(error)}")
                onError(error)
            } finally {
                _shuffling.value = false
            }
        }
    }

    companion object {
        internal suspend fun home(source: MusicSource, offline: Boolean): HomeContentState {
            if (offline) return HomeContentState()
            return kotlinx.coroutines.coroutineScope {
                val recent = async { runCatching { source.albums(AlbumSort.RECENTLY_PLAYED, 20) }.getOrDefault(emptyList()) }
                val playlists = async { runCatching { source.playlists() }.getOrDefault(emptyList()) }
                val newest = source.albums(AlbumSort.NEWEST, 20)
                HomeContentState(recent.await(), newest, playlists.await())
            }
        }
    }
}

// Albums, a page at a time

data class AlbumsState(
    val sort: AlbumSort,
    val albums: Load<List<Album>> = Load.Loading,
    val loadingMore: Boolean = false,
    val reachedEnd: Boolean = false,
)

class AlbumsViewModel(private val source: MusicSource, initial: AlbumSort) : ViewModel() {
    private val _state = MutableStateFlow(AlbumsState(initial))
    val state: StateFlow<AlbumsState> = _state.asStateFlow()
    val sorts: List<AlbumSort> get() = source.albumSorts
    private var job: Job? = null

    init {
        reload()
    }

    fun setSort(sort: AlbumSort) {
        if (sort == _state.value.sort) return
        _state.value = AlbumsState(sort)
        reload()
    }

    fun reload() {
        job?.cancel()
        val sort = _state.value.sort
        _state.update { it.copy(albums = if (it.albums is Load.Loaded) it.albums else Load.Loading, reachedEnd = false) }
        job = viewModelScope.launch {
            val page = loading { source.albums(sort, PAGE) }
            _state.update {
                it.copy(albums = page, reachedEnd = page !is Load.Loaded || page.value.size < PAGE || sort == AlbumSort.RANDOM)
            }
        }
    }

    /**
     * The next page. Stops when a page brings nothing new, as well as on a short one: a server
     * that ignores the offset would otherwise hand back the first page for ever.
     */
    fun loadMore() {
        val s = _state.value
        val shown = (s.albums as? Load.Loaded)?.value ?: return
        if (s.loadingMore || s.reachedEnd) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            val page = runCatching { source.albums(s.sort, PAGE, shown.size) }.getOrNull()
            _state.update { now ->
                if (page == null || now.sort != s.sort) return@update now.copy(loadingMore = false)
                val known = shown.mapTo(HashSet()) { it.id }
                val fresh = page.filter { it.id !in known }
                now.copy(albums = Load.Loaded(shown + fresh), loadingMore = false, reachedEnd = page.size < PAGE || fresh.isEmpty())
            }
        }
    }

    companion object {
        const val PAGE = 60
    }
}

// Songs, a page at a time

data class SongsState(val songs: Load<List<Song>> = Load.Loading, val loadingMore: Boolean = false, val reachedEnd: Boolean = false)

class SongsViewModel(private val source: MusicSource) : ViewModel() {
    private val _state = MutableStateFlow(SongsState())
    val state: StateFlow<SongsState> = _state.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            val page = loading { source.songs(PAGE) }
            _state.value = SongsState(page, reachedEnd = page !is Load.Loaded || page.value.size < PAGE)
        }
    }

    fun loadMore() {
        val s = _state.value
        val shown = (s.songs as? Load.Loaded)?.value ?: return
        if (s.loadingMore || s.reachedEnd) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            val page = runCatching { source.songs(PAGE, shown.size) }.getOrNull()
            _state.update { now ->
                if (page == null) return@update now.copy(loadingMore = false)
                val known = shown.mapTo(HashSet()) { it.id }
                val fresh = page.filter { it.id !in known }
                now.copy(songs = Load.Loaded(shown + fresh), loadingMore = false, reachedEnd = page.size < PAGE || fresh.isEmpty())
            }
        }
    }

    companion object {
        const val PAGE = 100
    }
}

// One album, one artist, one genre

class AlbumViewModel(source: MusicSource, albumId: String) : LoadViewModel<AlbumWithSongs>({ source.album(albumId) })

class ArtistViewModel(
    private val source: MusicSource,
    private val artistId: String,
    private val onError: (Throwable) -> Unit = {},
) : LoadViewModel<List<Album>>({ source.albums(artistId) }) {
    private val _starting = MutableStateFlow(false)
    val starting: StateFlow<Boolean> = _starting.asStateFlow()

    /**
     * Every album's songs, one request per album, in the order the albums are listed; from an
     * album credited to someone else (a compilation) only the artist's own songs.
     */
    fun playAll(play: (List<Song>) -> Unit) {
        val albums = (state.value as? Load.Loaded)?.value ?: return
        if (_starting.value) return
        _starting.value = true
        viewModelScope.launch {
            try {
                val songs = albums.flatMap { album ->
                    val all = runCatching { source.album(album.id).songs }.getOrDefault(emptyList())
                    if (album.artistId == artistId) all else all.filter { it.artistId == artistId }.ifEmpty { all }
                }
                if (songs.isEmpty()) onError(SourceError.NotFound()) else play(songs)
            } finally {
                _starting.value = false
            }
        }
    }
}

class GenreViewModel(
    private val source: MusicSource,
    private val genre: Genre,
    private val onError: (Throwable) -> Unit = {},
    private val log: AppLog? = null,
) : LoadViewModel<List<Album>>({ source.albums(genre, 200) }) {
    private val _shuffling = MutableStateFlow(false)
    val shuffling: StateFlow<Boolean> = _shuffling.asStateFlow()

    fun shuffle(play: (List<Song>) -> Unit) {
        if (_shuffling.value) return
        _shuffling.value = true
        viewModelScope.launch {
            try {
                play(source.randomSongs(100, genre))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log?.error("library", "genre shuffle failed: ${AppLog.describe(error)}")
                onError(error)
            } finally {
                _shuffling.value = false
            }
        }
    }
}

class ConcertsViewModel(source: MusicSource) : LoadViewModel<List<Album>>({
    (source as? ConcertSource)?.concerts() ?: emptyList()
})

class ArtistsViewModel(source: MusicSource) : LoadViewModel<List<Artist>>({ source.artists() })

class GenresViewModel(source: MusicSource) : LoadViewModel<List<Genre>>({ source.genres() })

class FavoritesViewModel(source: MusicSource) : LoadViewModel<audio.own.music.library.model.FavoritesResult>({ source.favorites() })

// Search

data class SearchState(
    val query: String = "",
    val result: SearchResult? = null,
    val error: audio.own.musicplayer.app.UiText? = null,
    val searching: Boolean = false,
)

/** Asks once typing pauses for [pauseMs]; a newer query cancels the one in flight. */
@OptIn(FlowPreview::class)
class SearchViewModel(
    private val source: MusicSource,
    private val isOffline: () -> Boolean,
    private val pauseMs: Long = 300,
) : ViewModel() {
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()
    private var job: Job? = null

    fun setQuery(text: String) {
        _state.update { it.copy(query = text) }
        job?.cancel()
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            _state.update { it.copy(result = null, error = null, searching = false) }
            return
        }
        job = viewModelScope.launch {
            if (!isOffline()) delay(pauseMs)
            _state.update { it.copy(searching = true) }
            try {
                val found = if (isOffline() && source is audio.own.music.library.ownaudio.OwnAudioSource) source.searchCatalogue(trimmed) else source.search(trimmed)
                _state.update { it.copy(result = found, error = null, searching = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(error = error.userMessage(), searching = false) }
            }
        }
    }
}
