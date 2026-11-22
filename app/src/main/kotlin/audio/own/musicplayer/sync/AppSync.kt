package audio.own.musicplayer.sync

import android.content.Context
import audio.own.core.servers.CompositeId
import audio.own.core.servers.NetworkState
import audio.own.core.sync.Delivery
import audio.own.core.sync.LiveUpdates
import audio.own.core.sync.Outbox
import audio.own.core.sync.OutboxDatabase
import audio.own.core.sync.OutboxSender
import audio.own.core.sync.PendingChange
import audio.own.core.sync.PendingPlay
import audio.own.core.sync.RefreshWorkerFactory
import audio.own.kit.ownaudio.OwnAudioException
import audio.own.music.library.BatchedOfflineEdits
import audio.own.music.library.FavoriteKind
import audio.own.music.library.LiveLibraryUpdates
import audio.own.music.library.MusicSource
import audio.own.music.library.OfflineEdit
import audio.own.music.library.SourceError
import audio.own.musicplayer.app.AppLog
import audio.own.musicplayer.library.LibraryModel
import audio.own.musicplayer.servers.ServersModel
import java.io.IOException
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * What the phone owes its servers, and how it hears from them: the outbox (favourites and
 * ratings made offline, plays) flushed through `/sync/push` where the server takes it, live
 * updates while a screen is open, and the periodic background refresh.
 */
class AppSync(
    context: Context,
    private val servers: ServersModel,
    private val sourceForServer: (String) -> MusicSource?,
    private val library: LibraryModel,
    private val isOffline: () -> Boolean,
    private val network: StateFlow<NetworkState>,
    private val log: AppLog,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    /** The widget's albums asked for again, from the periodic refresh. */
    private val refreshWidget: suspend () -> Unit = {},
) {
    val outbox = Outbox(OutboxDatabase.open(context), now) { log.info("sync", it) }

    val refreshWorkerFactory = RefreshWorkerFactory { backgroundRefresh() }

    /** `GET /events` while a screen is open, for the server shown. */
    val liveUpdates = LiveUpdates(
        scope = scope,
        canRun = { !isOffline() && !network.value.dataSaver && library.activeSource is LiveLibraryUpdates },
        open = { (library.activeSource as? LiveLibraryUpdates)?.libraryChanges() },
        onChange = { library.syncActive(force = true) },
        isFatal = { it is SourceError.SignInRejected || it is SourceError.NotAllowed || it is OwnAudioException.SignInRefused || it is OwnAudioException.SignedOut },
        log = { log.info("live", it) },
    )

    private var flushJob: Job? = null

    @OptIn(FlowPreview::class)
    fun start() {
        // After the network comes back and settles: send what waited, listen again.
        scope.launch {
            network.map { it.connected && !it.dataSaver }.distinctUntilChanged().drop(1).debounce(3_000).collect { connected ->
                if (connected) flushSoon()
                liveUpdates.refresh()
            }
        }
        // Another server shown: its stream, not the last one's.
        scope.launch {
            servers.state.map { it.activeId }.distinctUntilChanged().drop(1).collect { liveUpdates.refresh(restart = true) }
        }
    }

    // region Recording

    /** A favourite made while offline (or that couldn't reach the server): kept and sent later. */
    suspend fun recordFavorite(kind: FavoriteKind, id: String, on: Boolean) = record(id, OP_FAVOURITE, kind, on.toString())

    /** A rating, 0 to take it away. */
    suspend fun recordRating(kind: FavoriteKind, id: String, rating: Int) = record(id, OP_RATING, kind, rating.toString())

    private suspend fun record(id: String, op: String, kind: FavoriteKind, value: String) {
        val serverId = CompositeId.split(id)?.first ?: return
        val at = now()
        outbox.recordChange(
            PendingChange(serverId = serverId, itemId = id, op = op, kind = kind.name, value = value, mergeKey = "$op:$id", clientId = "$id/$op/$at", madeAt = at),
        )
    }

    /** A counted play, stored first so a dead network or a killed app can't lose it, then sent. */
    suspend fun recordPlay(trackId: String, startedAtMs: Long, heardMs: Long) {
        val serverId = CompositeId.split(trackId)?.first ?: return
        val ended = now()
        outbox.recordPlay(PendingPlay(Outbox.playId(trackId, startedAtMs), serverId, trackId, startedAtMs, maxOf(ended, startedAtMs), heardMs.coerceAtLeast(0)))
        flushSoon()
    }

    // endregion

    // region Sending

    /** Sends what waits, soon and once; nothing while offline. */
    fun flushSoon() {
        if (isOffline()) return
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(500)
            flush()
        }
    }

    /** Sends what waits; true when nothing was left behind for a network reason. */
    suspend fun flush(): Boolean {
        if (isOffline()) return false
        servers.state.first { it.loaded }
        val report = outbox.flush(sender)
        if (report.sent > 0) library.syncActive(force = true)
        return report.kept == 0
    }

    /** The periodic job: outbox, catalogue, the widget. */
    private suspend fun backgroundRefresh(): Boolean {
        servers.state.first { it.loaded }
        val flushed = flush()
        val synced = library.syncActiveNow()
        refreshWidget()
        return flushed && synced
    }

    private val sender = MusicOutboxSender(
        sourceForServer,
        isKnownServer = { servers.state.value.server(it) != null },
        onRefused = { change -> library.undoRefused(change.itemId, isFavorite = change.op == OP_FAVOURITE) },
    )

    // endregion

    companion object {
        const val OP_FAVOURITE = "favourite"
        const val OP_RATING = "rating"

        fun isNetwork(e: Throwable): Boolean = e is IOException || e is SourceError.Network || e is SourceError.Offline ||
            e is OwnAudioException.Network || e is OwnAudioException.Offline || (e is SourceError.Http && e.status >= 500)
    }
}

/**
 * Sends the outbox to the music servers: in one `/sync/push` where the server takes it
 * (own.audio), else one call each. A network failure keeps what is left; a refusal drops it.
 */
class MusicOutboxSender(
    private val sourceForServer: (String) -> MusicSource?,
    /** Whether a server is saved (a server not saved any more was removed: drop its entries). */
    private val isKnownServer: (String) -> Boolean,
    /** A favourite or rating the server refused for good: the screen goes back to the server's. */
    private val onRefused: (PendingChange) -> Unit = {},
) : OutboxSender {
    override suspend fun sendChanges(serverId: String, changes: List<PendingChange>): List<Delivery> {
        val source = sourceForServer(serverId) ?: return gone(serverId, changes.size)
        val edits = changes.map { it.edit() }
        if (source is BatchedOfflineEdits) {
            try {
                val sent = changes.zip(edits).filter { it.second != null }
                val answers = source.push(sent.map { it.second!! }).associateBy { it.clientId }
                return changes.map { change ->
                    val answer = answers[change.clientId]
                    if (answer == null || answer.accepted) {
                        Delivery.DONE
                    } else {
                        onRefused(change)
                        Delivery.DROP
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (AppSync.isNetwork(e)) throw e
                // Signed in the old way, or the request was refused: one at a time below.
            }
        }
        return changes.map { change -> one { send(source, change) }.also { if (it == Delivery.DROP) onRefused(change) } }
    }

    override suspend fun sendPlays(serverId: String, plays: List<PendingPlay>): List<Delivery> {
        val source = sourceForServer(serverId) ?: return gone(serverId, plays.size)
        if (source is BatchedOfflineEdits) {
            try {
                val answers = source.push(plays.map { OfflineEdit(it.clientId, it.itemId, OfflineEdit.Change.Played(Instant.ofEpochMilli(it.startedAt), it.listenedMs), Instant.ofEpochMilli(it.endedAt)) })
                    .associateBy { it.clientId }
                // A play of a song that is gone is dropped: sending it again changes nothing.
                return plays.map { if (answers[it.clientId]?.accepted == false) Delivery.DROP else Delivery.DONE }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (AppSync.isNetwork(e)) throw e
            }
        }
        return plays.map { play -> one { source.reportPlayed(play.itemId, Instant.ofEpochMilli(play.startedAt)) } }
    }

    /** A server that was removed has nowhere to send to: drop; one not read yet: keep. */
    private fun gone(serverId: String, count: Int): List<Delivery> {
        if (isKnownServer(serverId)) throw IOException("server $serverId not ready")
        return List(count) { Delivery.DROP }
    }

    private suspend fun one(send: suspend () -> Unit): Delivery = try {
        send()
        Delivery.DONE
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (AppSync.isNetwork(e)) Delivery.KEEP else Delivery.DROP
    }

    private suspend fun send(source: MusicSource, change: PendingChange) {
        val kind = FavoriteKind.entries.firstOrNull { it.name == change.kind } ?: FavoriteKind.SONG
        when (change.op) {
            AppSync.OP_FAVOURITE -> source.setFavorite(kind, change.itemId, change.value.toBoolean())
            AppSync.OP_RATING -> source.setRating(change.itemId, change.value?.toIntOrNull() ?: 0)
        }
    }

    private fun PendingChange.edit(): OfflineEdit? {
        val favoriteKind = FavoriteKind.entries.firstOrNull { it.name == kind } ?: FavoriteKind.SONG
        val change = when (op) {
            AppSync.OP_FAVOURITE -> OfflineEdit.Change.Favorite(favoriteKind, value.toBoolean())
            AppSync.OP_RATING -> OfflineEdit.Change.Rating(favoriteKind, value?.toIntOrNull() ?: 0)
            else -> return null
        }
        return OfflineEdit(clientId, itemId, change, Instant.ofEpochMilli(madeAt))
    }
}
