package audio.own.musicplayer.widget

import audio.own.music.library.AlbumSort
import audio.own.music.library.MusicSource
import audio.own.musicplayer.app.AppLog
import audio.own.player.music.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Keeps the widget's snapshot in step with the player and the albums played last: a second after
 * a change, so a burst of them writes once, and only when the entries differ ([redraw] then).
 * The song playing follows the player; the albums are asked for by [refreshAlbums], at launch,
 * on another server and from the periodic refresh, as the iOS app's `refreshWidget(fetch:)`.
 */
class WidgetUpdater(
    private val store: WidgetStore,
    private val track: Flow<Track?>,
    private val activeServerId: Flow<String?>,
    private val source: () -> MusicSource?,
    private val isOffline: () -> Boolean,
    private val jpeg: suspend (String) -> ByteArray?,
    private val redraw: suspend () -> Unit,
    private val log: AppLog? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val debounceMs: Long = 1_000,
) {
    /** The albums from the last time, so a relaunch shows them before the server answers again. */
    private val albums = MutableStateFlow(
        runCatching { store.load()?.entries.orEmpty() }.getOrDefault(emptyList())
            .filter { it.kind != WidgetSnapshot.Kind.RESUME }
            .map { WidgetSnapshot.Picked(it, null) },
    )

    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        scope.launch {
            combine(track.distinctUntilChanged { a, b -> a?.id == b?.id }, albums, activeServerId) { current, recent, server -> WidgetSnapshot.pick(current, recent, server) }
                .distinctUntilChanged { a, b -> a.map { it.entry } == b.map { it.entry } }
                .debounce(debounceMs)
                .collect { picked ->
                    try {
                        if (store.save(picked, now(), jpeg)) redraw()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        log?.error("widget", "the snapshot wasn't written: ${error.javaClass.simpleName}")
                    }
                }
        }
        // The servers loaded, or another one chosen: its albums, not the last one's.
        scope.launch { activeServerId.distinctUntilChanged().filterNotNull().collect { refreshAlbums(force = true) } }
    }

    private var fetchedAt: Long? = null
    private var fetchedFor: String? = null

    /**
     * The albums played last on the active server; kept as they were when it can't be asked.
     * Asked again within a minute only when [force]d (the launch and the periodic refresh can
     * come together when the system starts the app for the refresh).
     */
    suspend fun refreshAlbums(force: Boolean = false) {
        if (isOffline()) return
        val source = source() ?: return
        val last = fetchedAt
        if (!force && fetchedFor == source.serverId && last != null && now() - last < MIN_INTERVAL_MS) return
        try {
            albums.value = source.albums(AlbumSort.RECENTLY_PLAYED, WidgetSnapshot.ALBUMS_ASKED).map(WidgetSnapshot::album)
            fetchedAt = now()
            fetchedFor = source.serverId
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log?.info("widget", "recently played albums unavailable: ${error.javaClass.simpleName}")
        }
    }

    private companion object {
        const val MIN_INTERVAL_MS = 60_000L
    }
}
