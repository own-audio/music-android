package audio.own.musicplayer.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import audio.own.music.library.ArtworkSource
import audio.own.music.library.HouseholdSource
import audio.own.music.library.MusicSource
import audio.own.music.library.PlaylistDetailsSource
import audio.own.music.library.SmartPlaylistInfo
import audio.own.music.library.SmartPlaylistSource
import audio.own.music.library.SmartPresetInfo
import audio.own.music.library.model.Playlist
import audio.own.music.library.model.PlaylistEntry
import audio.own.music.library.model.Song
import audio.own.musicplayer.app.Load
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A smart playlist to open: a saved one, or one of the server's ready-made rules. */
sealed interface SmartTarget {
    data class Saved(val info: SmartPlaylistInfo) : SmartTarget
    data class Preset(val info: SmartPresetInfo) : SmartTarget

    /** The key its page is opened with. */
    val key: String
        get() = when (this) {
            is Saved -> "saved:${info.id}"
            is Preset -> "preset:${info.slug}"
        }
}

/** Whether a playlist is the listener's to change. A server that doesn't say gets the edits; it refuses what it doesn't allow. */
fun Playlist.isOwn(username: String?): Boolean = isMine ?: (owner == null || owner == username)

/** Who made someone else's playlist, for "12 songs · by Sam"; null for one's own. */
fun Playlist.otherOwner(username: String?): String? {
    if (isOwn(username)) return null
    return (ownerName ?: owner)?.takeIf { it.isNotBlank() }
}

// The Playlists tab

data class PlaylistsContentState(
    val smart: List<SmartPlaylistInfo> = emptyList(),
    val presets: List<SmartPresetInfo> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    /** The server has smart playlists: New Smart Playlist is offered. */
    val hasSmart: Boolean = false,
)

/**
 * The Playlists tab (`Library/PlaylistsView.swift`): on a server with smart playlists, the saved
 * ones and the ready-made rules first (online only), then the playlists.
 */
class PlaylistsViewModel(
    private val source: MusicSource,
    private val smart: SmartPlaylistSource?,
    private val isOffline: () -> Boolean,
    private val onError: (Throwable) -> Unit = {},
) : ViewModel() {
    private val _state = MutableStateFlow<Load<PlaylistsContentState>>(Load.Loading)
    val state: StateFlow<Load<PlaylistsContentState>> = _state.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch { _state.value = loading { fetch() } }
    }

    fun refresh() {
        _refreshing.value = true
        viewModelScope.launch {
            val next = loading { fetch() }
            if (next is Load.Loaded || _state.value !is Load.Loaded) _state.value = next
            _refreshing.value = false
        }
    }

    private suspend fun fetch(): PlaylistsContentState = coroutineScope {
        // A server without smart playlists answers an error, and none show.
        val saved = async { if (smart != null && !isOffline()) runCatching { smart.smartPlaylists() }.getOrDefault(emptyList()) else emptyList() }
        val ready = async { if (smart != null && !isOffline()) runCatching { smart.smartPresets() }.getOrDefault(emptyList()) else emptyList() }
        val playlists = source.playlists()
        val s = saved.await()
        val p = ready.await()
        PlaylistsContentState(s, p, playlists, hasSmart = s.isNotEmpty() || p.isNotEmpty())
    }

    /** + then a name: an empty playlist, songs come from their menus. */
    fun create(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            try {
                source.createPlaylist(trimmed, emptyList())
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
            }
        }
    }
}

// One playlist

data class PlaylistPageState(
    val playlist: Playlist?,
    val songs: Load<List<Song>> = Load.Loading,
    /** Deleted: the page closes. */
    val deleted: Boolean = false,
    val savingCover: Boolean = false,
)

/**
 * A playlist's page (`PlaylistDetailView`): its songs, and for the listener's own the edits —
 * rename, cover, sharing, delete, removing a song (by its entry: a song can be in it twice) and
 * a new order (by every entry id, where the server takes one).
 */
class PlaylistViewModel(
    private val source: MusicSource,
    /** The server's own source, for what only it has (covers, sharing, order). */
    private val raw: MusicSource,
    private val playlistId: String,
    known: Playlist?,
    private val username: String?,
    private val onError: (Throwable) -> Unit = {},
) : ViewModel() {
    private val _state = MutableStateFlow(PlaylistPageState(known))
    val state: StateFlow<PlaylistPageState> = _state.asStateFlow()

    init {
        load()
    }

    val isOwn: Boolean get() = _state.value.playlist?.isOwn(username) ?: false

    /** Covers on own.audio. */
    val canChooseCover: Boolean get() = raw is ArtworkSource && isOwn

    /** Sharing where the server keeps a household and says whether this one is shared. */
    val canShare: Boolean get() = raw is HouseholdSource && isOwn && _state.value.playlist?.isShared != null

    /** Drag to reorder: where the server takes a new order by entry ids, and every song has one. */
    val canReorder: Boolean
        get() {
            val songs = (_state.value.songs as? Load.Loaded)?.value ?: return false
            return raw is PlaylistDetailsSource && isOwn && songs.size > 1 && songs.all { it.playlistEntryId != null }
        }

    fun load() {
        viewModelScope.launch {
            val next = loading { source.playlist(playlistId) }
            _state.update { s ->
                when (next) {
                    is Load.Loaded -> s.copy(playlist = next.value.playlist, songs = Load.Loaded(next.value.songs))
                    is Load.Failed -> s.copy(songs = next)
                    Load.Loading -> s
                }
            }
        }
    }

    fun rename(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        perform {
            source.renamePlaylist(playlistId, trimmed)
            _state.update { s -> s.copy(playlist = s.playlist?.copy(name = trimmed)) }
            load()
        }
    }

    fun delete() = perform {
        source.deletePlaylist(playlistId)
        _state.update { it.copy(deleted = true) }
    }

    fun setShared(shared: Boolean) {
        val household = raw as? HouseholdSource ?: return
        perform {
            household.setShared(playlistId, shared)
            _state.update { s -> s.copy(playlist = s.playlist?.copy(isShared = shared)) }
        }
    }

    /** [jpeg] is the chosen picture as the app made it (1600 px at most). */
    fun setCover(jpeg: ByteArray) {
        val artwork = raw as? ArtworkSource ?: return
        _state.update { it.copy(savingCover = true) }
        viewModelScope.launch {
            try {
                artwork.setCover(playlistId, jpeg, "image/jpeg")
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
            } finally {
                _state.update { it.copy(savingCover = false) }
            }
        }
    }

    /** Swipe or menu: that entry goes at once, then the server's list is read again (positions shift). */
    fun remove(index: Int) {
        val songs = (_state.value.songs as? Load.Loaded)?.value ?: return
        if (index !in songs.indices) return
        val entry = PlaylistEntry(index, songs[index].playlistEntryId)
        _state.update { it.copy(songs = Load.Loaded(songs.toMutableList().apply { removeAt(index) })) }
        perform(reloadAfter = true) { source.removeFromPlaylist(playlistId, listOf(entry)) }
    }

    /** A drag ended: the new order shows at once and goes to the server with every entry id. */
    fun move(from: Int, to: Int) {
        val details = raw as? PlaylistDetailsSource ?: return
        val songs = (_state.value.songs as? Load.Loaded)?.value ?: return
        if (from !in songs.indices || to !in songs.indices || from == to) return
        val moved = songs.toMutableList().apply { add(to, removeAt(from)) }
        val entryIds = moved.mapNotNull { it.playlistEntryId }
        if (entryIds.size != moved.size) return
        _state.update { it.copy(songs = Load.Loaded(moved)) }
        viewModelScope.launch {
            try {
                details.reorderPlaylist(playlistId, entryIds)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
                load()
            }
        }
    }

    private fun perform(reloadAfter: Boolean = false, work: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
            } finally {
                if (reloadAfter) load()
            }
        }
    }
}

// Add to Playlist

data class AddToPlaylistState(val playlists: Load<List<Playlist>> = Load.Loading, val done: Boolean = false)

/** The Add to Playlist sheet: only the listener's own playlists can be added to, or a new one made with the songs. */
class AddToPlaylistViewModel(
    private val source: MusicSource,
    private val songIds: List<String>,
    private val username: String?,
    private val onError: (Throwable) -> Unit = {},
    /** Where the change is sent from: the sheet is gone by then. */
    private val background: CoroutineScope? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(AddToPlaylistState())
    val state: StateFlow<AddToPlaylistState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(playlists = Load.Loading) }
            val next = loading { source.playlists().filter { it.isOwn(username) } }
            _state.update { it.copy(playlists = next) }
        }
    }

    fun add(playlist: Playlist) = finish { source.addToPlaylist(playlist.id, songIds) }

    fun create(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        finish { source.createPlaylist(trimmed, songIds) }
    }

    /** The sheet closes at once; a refusal shows afterwards. */
    private fun finish(work: suspend () -> Unit) {
        _state.update { it.copy(done = true) }
        (background ?: viewModelScope).launch {
            try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
            }
        }
    }
}
