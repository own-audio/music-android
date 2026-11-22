package audio.own.musicplayer.library

import audio.own.core.servers.CompositeId
import audio.own.core.servers.DiskAnswerCache
import audio.own.kit.subsonic.OpenSubsonicExtension
import audio.own.music.library.PagedCatalogue
import audio.own.music.library.forJellyfin
import audio.own.music.library.forSubsonic
import audio.own.music.library.jellyfin.JellyfinSource
import audio.own.music.library.jellyfinLog
import audio.own.music.library.subsonic.SubsonicSource
import audio.own.music.library.subsonicLog
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
import audio.own.music.library.model.Playlist
import audio.own.music.library.model.Song
import audio.own.music.library.ownaudio.ListenerState
import audio.own.music.library.ownaudio.OwnAudioSource
import audio.own.musicplayer.app.AppLog
import audio.own.musicplayer.app.UiText
import audio.own.musicplayer.app.userMessage
import kotlin.coroutines.cancellation.CancellationException
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
 * The music sources of the saved servers, one per server, rebuilt when what its client is made
 * of changes (a client talks to one address, with one sign-in, and for Jellyfin one music
 * library). The listener's favourites and ratings outlive a rebuild. Subsonic and Jellyfin
 * clients keep their answers in [answers] for offline use and log to [log].
 */
class SourceRegistry(
    private val servers: ServersModel,
    private val db: () -> CatalogueDatabase,
    private val answers: DiskAnswerCache? = null,
    private val log: AppLog? = null,
    private val scope: CoroutineScope? = null,
) {
    private class Entry(val signature: String, val source: MusicSource)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val listeners = ConcurrentHashMap<String, ListenerState>()

    /** Subsonic servers that take requests as POST forms (OpenSubsonic `formPost`), once asked. */
    private val formPost = ConcurrentHashMap<String, Boolean>()

    /** The source for [serverId], or null for a server not saved (or not signed in). */
    fun source(serverId: String): MusicSource? {
        val record = servers.state.value.server(serverId) ?: return null
        return when (record.kind) {
            ServerKinds.OWN_AUDIO -> ownAudio(record)
            ServerKinds.JELLYFIN -> jellyfin(record)
            ServerKinds.SUBSONIC -> subsonic(record)
            else -> null
        }
    }

    private fun ownAudio(record: ServerRecord): MusicSource? {
        val client = servers.client(record.id) ?: return null
        return cached(record.id, client.baseUrl.toString()) {
            OwnAudioSource(
                serverId = record.id,
                username = record.auth.userName,
                client = client,
                db = db(),
                listener = listeners.getOrPut(record.id) { ListenerState() },
            )
        }
    }

    private fun jellyfin(record: ServerRecord): MusicSource? {
        val client = servers.jellyfinClient(record.id, answers?.forJellyfin(), jellyfinLog { log?.error("jellyfin", it) }) ?: return null
        val signature = "${client.host}|${record.libraryId}|${client.session.accessToken.hashCode()}"
        return cached(record.id, signature) { JellyfinSource(record.id, client, db()) }
    }

    private fun subsonic(record: ServerRecord): MusicSource? {
        val plain = servers.subsonicClient(record.id, answers?.forSubsonic(), subsonicLog { log?.error("subsonic", it) }) ?: return null
        val known = formPost[record.id]
        val client = if (known == true) plain.withFormPost(true) else plain
        val signature = "${client.host}|${record.auth.hashCode()}|${client.useFormPost}"
        return cached(record.id, signature) {
            if (known == null) learnFormPost(record.id, plain)
            SubsonicSource(record.id, client, db())
        }
    }

    /**
     * Whether the server takes POST forms: long requests fit, and the sign-in stays out of the
     * URLs its logs keep. Asked once in the background; the next source built for it uses it.
     */
    private fun learnFormPost(serverId: String, client: audio.own.kit.subsonic.SubsonicClient) {
        val scope = scope ?: return
        formPost[serverId] = false
        scope.launch {
            val offered = OpenSubsonicExtension.FORM_POST in client.openSubsonicExtensions()
            formPost[serverId] = offered
            if (offered) log?.info("subsonic", "the server takes POST forms")
        }
    }

    private inline fun cached(serverId: String, signature: String, make: () -> MusicSource): MusicSource {
        entries[serverId]?.takeIf { it.signature == signature }?.let { return it.source }
        val source = make()
        entries[serverId] = Entry(signature, source)
        return source
    }

    /** The source a composite id ("<server>|<item>") belongs to. */
    fun sourceOf(compositeId: String): MusicSource? = CompositeId.split(compositeId)?.first?.let(::source)

    fun forget(serverId: String) {
        entries.remove(serverId)
        listeners.remove(serverId)
        formPost.remove(serverId)
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
    /** Wi-Fi or another network that isn't metered: where a whole library is read. */
    private val onUnmeteredNetwork: () -> Boolean = { true },
    /** For the counts on the server page; null in tests without a database. */
    private val catalogueDb: (() -> CatalogueDatabase)? = null,
) {
    data class SyncState(
        val serverId: String? = null,
        /** The active server's sync is running. */
        val running: Boolean = false,
        /** Every server whose catalogue is being read now. */
        val syncing: Set<String> = emptySet(),
        /** Why a server's last sync failed. */
        val failures: Map<String, UiText> = emptyMap(),
        /** Bumped after every sync that changed something: screens reload. */
        val revision: Int = 0,
        val lastSyncedAtMs: Long? = null,
    )

    data class Features(
        val serverId: String? = null,
        val capabilities: Set<Capability> = emptySet(),
        val concerts: Boolean = false,
        /** The server's identify (MusicBrainz), once it said it has one. */
        val identify: audio.own.music.library.IdentifyAvailability? = null,
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
    private val playlists = ConcurrentHashMap<String, Playlist>()
    private val smartTargets = ConcurrentHashMap<String, SmartTarget>()

    private val syncJobs = ConcurrentHashMap<String, Job>()
    private val again: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val syncedAt = ConcurrentHashMap<String, Long>()

    /**
     * Where favourites and ratings go when the server can't be reached: kept, and sent when it
     * can (the outbox, set by the container once it exists).
     */
    interface OfflineChanges {
        suspend fun favorite(kind: FavoriteKind, id: String, on: Boolean)
        suspend fun rating(kind: FavoriteKind, id: String, rating: Int)
    }

    var offlineChanges: OfflineChanges? = null

    val activeServer: ServerRecord? get() = servers.state.value.active
    val activeSource: MusicSource? get() = servers.state.value.activeId?.let(registry::source)

    fun source(serverId: String): MusicSource? = registry.source(serverId)

    fun server(serverId: String): ServerRecord? = servers.state.value.server(serverId)

    fun learn(album: Album) { albums[album.id] = album }
    fun learn(artist: Artist) { artists[artist.id] = artist }
    fun learn(playlist: Playlist) { playlists[playlist.id] = playlist }
    fun album(id: String): Album? = albums[id]
    fun artist(id: String): Artist? = artists[id]
    fun playlist(id: String): Playlist? = playlists[id]

    /** A smart playlist or a preset opened from the list, by the key its page is opened with. */
    fun learn(key: String, target: SmartTarget) { smartTargets[key] = target }
    fun smartTarget(key: String): SmartTarget? = smartTargets[key]

    /**
     * The server refused a favourite or a rating made offline for good: what the screen shows
     * goes back to what the server says.
     */
    fun undoRefused(id: String, isFavorite: Boolean) {
        if (isFavorite) _favorites.update { it - id } else _ratings.update { it - id }
        log.info("sync", "the server refused a change made offline; it is undone here")
    }

    /**
     * Brings the active server's catalogue up to date when it is older than [maxAgeMs]: at launch
     * and whenever the app comes back to the screen. A sync asked for during one runs once more.
     */
    fun syncActive(force: Boolean = false, maxAgeMs: Long = MAX_AGE_MS) {
        val serverId = servers.state.value.activeId ?: return
        if (_features.value.serverId != serverId) refreshFeatures(serverId)
        syncServer(serverId, force, maxAgeMs)
    }

    /**
     * One server's catalogue. A change feed (own.audio) is cheap to follow and runs on any
     * network but Data Saver; a library read whole (Subsonic, Jellyfin) waits for Wi-Fi and its
     * own age of six hours, unless [force]d (Update Now).
     */
    fun syncServer(serverId: String, force: Boolean = false, maxAgeMs: Long = MAX_AGE_MS) {
        if (isOffline()) return
        if (!force && !canSyncNow()) return
        val source = registry.source(serverId) ?: return
        if (source !is ChangeFeedCatalogue && source !is PagedCatalogue) return
        if (source is PagedCatalogue && !force && !onUnmeteredNetwork()) return
        val last = syncedAt[serverId]
        if (!force && last != null && now() - last < maxAgeMs) return
        if (syncJobs[serverId]?.isActive == true) {
            again += serverId
            return
        }
        syncJobs[serverId] = scope.launch {
            do {
                again -= serverId
                _sync.update { it.copy(serverId = serverId, running = serverId == servers.state.value.activeId, syncing = it.syncing + serverId, failures = it.failures - serverId) }
                try {
                    val changed = runSync(source, force)
                    syncedAt[serverId] = now()
                    _sync.update {
                        it.copy(
                            serverId = serverId,
                            running = false,
                            syncing = it.syncing - serverId,
                            revision = if (changed) it.revision + 1 else it.revision,
                            lastSyncedAtMs = now(),
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    log.error("library", "catalogue sync failed: ${error.message}")
                    _sync.update { it.copy(serverId = serverId, running = false, syncing = it.syncing - serverId, failures = it.failures + (serverId to error.userMessage())) }
                }
            } while (serverId in again)
        }
    }

    /** True when the catalogue changed. */
    private suspend fun runSync(source: MusicSource, force: Boolean): Boolean = when (source) {
        is ChangeFeedCatalogue -> {
            val result = source.syncCatalogue()
            log.info("library", "catalogue synced: ${result.changes} changes${if (result.fromSnapshot) " (snapshot)" else ""}")
            result.changes > 0
        }
        is PagedCatalogue -> {
            val result = source.syncCatalogue(force)
            if (result != null) {
                log.info("library", "catalogue read: ${result.songs} songs, ${result.albums} albums${if (result.readAlbumByAlbum) " (album by album)" else ""}")
            }
            result != null
        }
        else -> false
    }

    /**
     * The active server's catalogue brought up to date now, waiting for it: the periodic
     * background job. True when it worked or there was nothing to do.
     */
    suspend fun syncActiveNow(): Boolean {
        val serverId = servers.state.value.activeId ?: return true
        if (isOffline() || !canSyncNow()) return true
        val source = registry.source(serverId) ?: return true
        if (source !is ChangeFeedCatalogue && source !is PagedCatalogue) return true
        if (source is PagedCatalogue && !onUnmeteredNetwork()) return true
        syncJobs[serverId]?.join()
        return try {
            val changed = runSync(source, force = false)
            syncedAt[serverId] = now()
            _sync.update { it.copy(serverId = serverId, revision = if (changed) it.revision + 1 else it.revision, lastSyncedAtMs = now()) }
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.error("library", "background sync failed: ${error.message}")
            false
        }
    }

    /** What the server page's "Library on This Device" shows. */
    data class CatalogueInfo(val songs: Int, val albums: Int, val syncedAtMs: Long?)

    suspend fun catalogueInfo(serverId: String): CatalogueInfo? {
        val source = registry.source(serverId) ?: return null
        val dao = (catalogueDb ?: return null)().catalogue()
        val synced = when (source) {
            is PagedCatalogue -> source.catalogueSyncedAtMs()
            else -> dao.feedState(serverId)?.syncedAtMs
        }
        if (synced == null) return null
        return CatalogueInfo(dao.trackCount(serverId), dao.albumCount(serverId), synced)
    }

    private fun refreshFeatures(serverId: String) {
        val source = registry.source(serverId)
        _features.value = Features(serverId, source?.capabilities.orEmpty(), concerts = false)
        refreshIdentify()
        if (source !is ConcertSource) return
        scope.launch {
            val concerts = runCatching { source.supportsConcerts() }.getOrDefault(false)
            _features.update { if (it.serverId == serverId) it.copy(concerts = concerts) else it }
        }
    }

    /**
     * Asks the active server whether it identifies, until it answered once (the source keeps the
     * answer): a server asked while signed out or offline is asked again when a screen shows.
     */
    fun refreshIdentify() {
        val serverId = servers.state.value.activeId ?: return
        val source = registry.source(serverId) as? audio.own.music.library.IdentifySource ?: return
        if (_features.value.serverId == serverId && _features.value.identify != null) return
        scope.launch {
            val answer = runCatching { source.identifyAvailability() }.getOrNull()
            _features.update { if (it.serverId == serverId) it.copy(identify = answer) else it }
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

    /**
     * Shown at once. Offline, or when the server can't be reached, it is kept and sent later;
     * a refusal puts it back.
     */
    suspend fun setFavorite(kind: FavoriteKind, id: String, on: Boolean) {
        val source = registry.sourceOf(id) ?: return
        _favorites.update { it + (id to on) }
        val outbox = offlineChanges
        if (isOffline() && outbox != null) return outbox.favorite(kind, id, on)
        try {
            source.setFavorite(kind, id, on)
        } catch (error: Exception) {
            if (outbox != null && isUnreachable(error)) return outbox.favorite(kind, id, on)
            _favorites.update { it - id }
            throw error
        }
    }

    /** A song's or an album's rating; the kind is the catalogue's, a song where it doesn't know. */
    suspend fun setRating(id: String, rating: Int, kind: FavoriteKind = FavoriteKind.SONG) {
        val source = registry.sourceOf(id) ?: return
        val before = _ratings.value[id]
        _ratings.update { it + (id to rating) }
        val outbox = offlineChanges
        if (isOffline() && outbox != null) return outbox.rating(kind, id, rating)
        try {
            source.setRating(id, rating)
        } catch (error: Exception) {
            if (outbox != null && isUnreachable(error)) return outbox.rating(kind, id, rating)
            _ratings.update { if (before == null) it - id else it + (id to before) }
            throw error
        }
    }

    private fun isUnreachable(error: Exception) = error is audio.own.music.library.SourceError.Network || error is audio.own.music.library.SourceError.Offline

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
