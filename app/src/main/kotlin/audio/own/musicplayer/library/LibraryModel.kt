package audio.own.musicplayer.library

import audio.own.core.servers.CompositeId
import audio.own.core.servers.ServerRecord
import audio.own.kit.ownaudio.discover
import audio.own.music.library.Capability
import audio.own.music.library.ChangeFeedCatalogue
import audio.own.music.library.ConcertSource
import audio.own.music.library.FavoriteKind
import audio.own.music.library.MusicSource
import audio.own.music.library.db.CatalogueDatabase
import audio.own.music.library.model.Album
import audio.own.music.library.model.Artist
import audio.own.music.library.model.Song
import audio.own.music.library.ownaudio.ListenerState
import audio.own.music.library.ownaudio.OwnAudioSource
import audio.own.musicplayer.app.AppLog
import audio.own.musicplayer.servers.ServerKinds
import audio.own.musicplayer.servers.ServersModel
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The music sources of the saved servers, one per server, rebuilt when the server's address
 * changes (a client talks to one address). The listener's favourites and ratings outlive a
 * rebuild.
 */
class SourceRegistry(
    private val servers: ServersModel,
    private val db: () -> CatalogueDatabase,
) {
    private class Entry(val address: String, val source: MusicSource)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val listeners = ConcurrentHashMap<String, ListenerState>()

    /** The source for [serverId], or null for a server this build can't play from yet. */
    fun source(serverId: String): MusicSource? {
        val record = servers.state.value.server(serverId) ?: return null
        if (record.kind != ServerKinds.OWN_AUDIO) return null
        val client = servers.client(serverId) ?: return null
        val address = client.baseUrl.toString()
        entries[serverId]?.takeIf { it.address == address }?.let { return it.source }
        val source = OwnAudioSource(
            serverId = serverId,
            username = record.auth.userName,
            client = client,
            db = db(),
            listener = listeners.getOrPut(serverId) { ListenerState() },
        )
        entries[serverId] = Entry(address, source)
        return source
    }

    /** The source a composite id ("<server>|<item>") belongs to. */
    fun sourceOf(compositeId: String): MusicSource? = CompositeId.split(compositeId)?.first?.let(::source)

    fun forget(serverId: String) {
        entries.remove(serverId)
        listeners.remove(serverId)
    }
}

/**
 * What the library screens share: the active server's source, the catalogue sync, which
 * features it has, and the favourites and ratings the listener changed here.
 */
class LibraryModel(
    private val servers: ServersModel,
    private val registry: SourceRegistry,
    private val isOffline: () -> Boolean,
    private val canSyncNow: () -> Boolean,
    private val log: AppLog,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class SyncState(
        val serverId: String? = null,
        val running: Boolean = false,
        /** Bumped after every sync that changed something: screens reload. */
        val revision: Int = 0,
        val lastSyncedAtMs: Long? = null,
    )

    data class Features(
        val serverId: String? = null,
        val capabilities: Set<Capability> = emptySet(),
        val concerts: Boolean = false,
    )

    private val _sync = MutableStateFlow(SyncState())
    val sync: StateFlow<SyncState> = _sync.asStateFlow()

    private val _features = MutableStateFlow(Features())
    val features: StateFlow<Features> = _features.asStateFlow()

    /** Favourite on or off as changed here, by composite id, over what the models carried. */
    private val _favorites = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val favorites: StateFlow<Map<String, Boolean>> = _favorites.asStateFlow()

    private val _ratings = MutableStateFlow<Map<String, Int>>(emptyMap())
    val ratings: StateFlow<Map<String, Int>> = _ratings.asStateFlow()

    /** Albums and artists seen in lists, so a detail page has its header before it loads. */
    private val albums = ConcurrentHashMap<String, Album>()
    private val artists = ConcurrentHashMap<String, Artist>()

    private var syncJob: Job? = null
    private var again = false
    private val syncedAt = ConcurrentHashMap<String, Long>()

    val activeServer: ServerRecord? get() = servers.state.value.active
    val activeSource: MusicSource? get() = servers.state.value.activeId?.let(registry::source)

    fun source(serverId: String): MusicSource? = registry.source(serverId)

    fun learn(album: Album) { albums[album.id] = album }
    fun learn(artist: Artist) { artists[artist.id] = artist }
    fun album(id: String): Album? = albums[id]
    fun artist(id: String): Artist? = artists[id]

    /**
     * Brings the active server's catalogue up to date when it is older than [maxAgeMs]: at launch
     * and whenever the app comes back to the screen. A sync asked for during one runs once more.
     */
    fun syncActive(force: Boolean = false, maxAgeMs: Long = MAX_AGE_MS) {
        val serverId = servers.state.value.activeId ?: return
        if (_features.value.serverId != serverId) refreshFeatures(serverId)
        if (isOffline()) return
        if (!force && !canSyncNow()) return
        val last = syncedAt[serverId]
        if (!force && last != null && now() - last < maxAgeMs) return
        if (syncJob?.isActive == true) {
            again = true
            return
        }
        val source = registry.source(serverId) as? ChangeFeedCatalogue ?: return
        syncJob = scope.launch {
            do {
                again = false
                _sync.update { it.copy(serverId = serverId, running = true) }
                try {
                    val result = source.syncCatalogue()
                    syncedAt[serverId] = now()
                    log.info("library", "catalogue synced: ${result.changes} changes${if (result.fromSnapshot) " (snapshot)" else ""}")
                    _sync.update { it.copy(serverId = serverId, running = false, revision = if (result.changes > 0) it.revision + 1 else it.revision, lastSyncedAtMs = now()) }
                } catch (error: Exception) {
                    log.error("library", "catalogue sync failed: ${error.message}")
                    _sync.update { it.copy(serverId = serverId, running = false) }
                }
            } while (again)
        }
    }

    private fun refreshFeatures(serverId: String) {
        val source = registry.source(serverId)
        _features.value = Features(serverId, source?.capabilities.orEmpty(), concerts = false)
        if (source !is ConcertSource) return
        scope.launch {
            val concerts = runCatching { source.supportsConcerts() }.getOrDefault(false)
            _features.update { if (it.serverId == serverId) it.copy(concerts = concerts) else it }
        }
    }

    /** Whether [id] is a favourite: as changed here, else as the server's records say. */
    fun isFavorite(id: String, fallback: Boolean): Boolean {
        _favorites.value[id]?.let { return it }
        val (serverId, raw) = CompositeId.split(id) ?: return fallback
        val source = registry.source(serverId) as? OwnAudioSource ?: return fallback
        return raw in source.listener.current.favourites || fallback
    }

    fun rating(id: String, fallback: Int?): Int? {
        _ratings.value[id]?.let { return it.takeIf { r -> r > 0 } }
        val (serverId, raw) = CompositeId.split(id) ?: return fallback
        val source = registry.source(serverId) as? OwnAudioSource ?: return fallback
        return source.listener.current.ratings[raw] ?: fallback
    }

    suspend fun setFavorite(kind: FavoriteKind, id: String, on: Boolean) {
        val source = registry.sourceOf(id) ?: return
        _favorites.update { it + (id to on) }
        try {
            source.setFavorite(kind, id, on)
        } catch (error: Exception) {
            _favorites.update { it - id }
            throw error
        }
    }

    suspend fun setRating(id: String, rating: Int) {
        val source = registry.sourceOf(id) ?: return
        val before = _ratings.value[id]
        _ratings.update { it + (id to rating) }
        try {
            source.setRating(id, rating)
        } catch (error: Exception) {
            _ratings.update { if (before == null) it - id else it + (id to before) }
            throw error
        }
    }

    /** A song as the lists show it, with what was changed here. */
    fun marked(song: Song): Song = song.copy(isFavorite = isFavorite(song.id, song.isFavorite), rating = rating(song.id, song.rating))

    /** For screens that read features once: whether [serverId]'s discovery names [module]. */
    suspend fun hasModule(serverId: String, module: String): Boolean {
        val client = servers.client(serverId) ?: return false
        return runCatching { client.discover().has(module) }.getOrDefault(false)
    }

    companion object {
        /** A catalogue fed by the change feed is caught up at most every ten minutes. */
        const val MAX_AGE_MS = 10 * 60 * 1000L
    }
}
