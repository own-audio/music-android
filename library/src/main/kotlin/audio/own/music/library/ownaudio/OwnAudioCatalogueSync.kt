package audio.own.music.library.ownaudio

import androidx.room.withTransaction
import audio.own.kit.ownaudio.OwnAudioClient
import audio.own.kit.ownaudio.OwnAudioException
import audio.own.kit.ownaudio.SnapshotLine
import audio.own.kit.ownaudio.changes
import audio.own.kit.ownaudio.httpStatus
import audio.own.kit.ownaudio.me
import audio.own.kit.ownaudio.snapshot
import audio.own.music.library.CatalogueSyncResult
import audio.own.music.library.db.CatalogueDatabase
import audio.own.music.library.db.DeferredSchema
import audio.own.music.library.db.FeedStateRow
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Keeps one own.audio server's catalogue in Room from its change feed (foundation §9.1): the
 * whole library once from `/sync/snapshot`, then `/sync/changes` from the cursor it ended at.
 *
 * - Written in transactions of [BATCH] records ([FIRST_BATCH] in a first snapshot), so a
 *   600k-track library never sits in memory.
 * - A first snapshot (nothing older to keep usable) writes without the big tables' secondary
 *   indexes and the search table's triggers, and makes them once at its end ([DeferredSchema]);
 *   while it runs, SQLite syncs to disk less often and keeps more pages in memory.
 * - A snapshot cut short (no `end` line) resumes with `after=` and the same cursor, also after
 *   the app was killed: where it got to is saved with every batch.
 * - The cursor moves in the same transaction as the changes it covers, so a crash between the two
 *   can't skip changes or apply them twice in a way that matters.
 * - `reset`, `410 sync_cursor_expired` and `invalid_cursor` mean the changes since the cursor are
 *   gone: a fresh snapshot. Its rows replace the old ones only once it has finished.
 * - Kinds this app doesn't keep are skipped.
 *
 * Errors are the kit's [OwnAudioException]s; the source maps them for screens.
 */
class OwnAudioCatalogueSync(
    private val db: CatalogueDatabase,
    private val serverId: String,
    private val client: OwnAudioClient,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val dao = db.catalogue()
    private val running = Mutex()

    private val _progress = MutableStateFlow<Int?>(null)

    /** Songs a snapshot has written so far, while one runs; null otherwise. */
    val progress: StateFlow<Int?> = _progress.asStateFlow()

    /** One sync at a time per server; a second caller waits and then syncs what is left. */
    suspend fun sync(): CatalogueSyncResult = running.withLock {
        var state = dao.feedState(serverId) ?: FeedStateRow(serverId, null, null, null, 0, null, null)
        if (state.userId == null) {
            // Whose each song is; asked once. A failure here mustn't stop the library syncing.
            val me = runCatching { client.me().id }.getOrNull()
            if (me != null) {
                state = state.copy(userId = me)
                dao.upsertFeedState(state)
            }
        }
        val cursor = state.cursor
        if (cursor != null) {
            changes(state, cursor)?.let { return@withLock it }
            state = dao.feedState(serverId)!!
            // The changes are gone: a new snapshot, in a new generation. The old cursor goes too,
            // so a snapshot interrupted by the app being killed resumes rather than starting over.
            state = state.copy(cursor = null, generation = state.generation + 1, snapshotCursor = null, snapshotAfter = null)
        } else if (state.snapshotCursor == null) {
            state = state.copy(generation = state.generation + 1)
        }
        snapshot(state)
    }

    /** The changes since [cursor], or null when a fresh snapshot is needed. */
    private suspend fun changes(start: FeedStateRow, cursor: String): CatalogueSyncResult? {
        var state = start
        var from = cursor
        var count = 0
        val writer = CatalogueWriter(dao, serverId, state.generation, changes = true)
        while (true) {
            val page = try {
                client.changes(from, kinds = SNAPSHOT_KINDS, limit = BATCH)
            } catch (e: OwnAudioException.Problem) {
                if (e.code == "sync_cursor_expired" || e.code == "invalid_cursor" || e.status == 410) return null
                throw e
            } catch (e: OwnAudioException.Http) {
                if (e.httpStatus == 410) return null
                throw e
            }
            if (page.reset) return null
            val items = page.changes.filter { CatalogueWriter.keeps(it.kind) }
                .map { FeedItem(it.kind, it.id, if (it.op == "delete") null else it.record) }
            state = state.copy(cursor = page.cursor, syncedAtMs = if (page.hasMore) state.syncedAtMs else clock.millis())
            val next = state
            db.withTransaction {
                count += writer.apply(items)
                dao.upsertFeedState(next)
            }
            from = page.cursor
            if (!page.hasMore) break
        }
        return CatalogueSyncResult(fromSnapshot = false, changes = count, cursor = from)
    }

    private suspend fun snapshot(start: FeedStateRow): CatalogueSyncResult {
        var state = start
        dao.upsertFeedState(state)
        // Nothing older to keep usable: a first snapshot, or one resumed after being cut off.
        val first = !dao.hasOlderRows(serverId, state.generation)
        // Other servers' songs are browsed meanwhile: without their indexes only while few.
        val defer = first && dao.otherServersTracks(serverId, DEFER_LIMIT) < DEFER_LIMIT
        val writer = CatalogueWriter(dao, serverId, state.generation, changes = false, fresh = first)
        val batch = if (first) FIRST_BATCH else BATCH
        val buffer = ArrayList<FeedItem>(batch)
        var cursor = state.snapshotCursor
        var after = state.snapshotAfter
        var lastKey = after
        var count = 0
        var resumes = 0
        var songs = dao.tracksOfGeneration(serverId, state.generation)
        _progress.value = songs

        suspend fun flush() {
            val items = buffer.toList()
            buffer.clear()
            state = state.copy(snapshotCursor = cursor, snapshotAfter = lastKey)
            val progress = state
            db.withTransaction {
                count += writer.apply(items)
                dao.upsertFeedState(progress)
            }
            songs += items.count { it.kind == "track" && it.record != null }
            _progress.value = songs
        }

        val tuning = withContext(Dispatchers.IO) { BulkTuning.start(db.openHelper.writableDatabase, deferSchema = defer) }
        var restored = false
        try {
            var end: String? = null
            while (end == null) {
                try {
                    // Three steps at once: the kit reads and parses lines, the records are
                    // decoded on another thread, and the batch before them is written. A failure
                    // reading comes in line, after everything read before it: those are written
                    // and the resume starts after the last of them.
                    client.snapshot(kinds = SNAPSHOT_KINDS, after = after, cursor = cursor)
                        .map(::prepare)
                        .catch { emit(Line(null, null, failure = it)) }
                        .flowOn(Dispatchers.Default)
                        .buffer(batch)
                        .collect { (line, item, failure) ->
                            if (failure != null) throw failure
                            when (line) {
                                is SnapshotLine.Begin -> cursor = line.cursor
                                is SnapshotLine.Record -> {
                                    lastKey = "${line.kind}:${line.id}"
                                    if (item != null) buffer += item
                                    if (buffer.size >= batch) flush()
                                }
                                is SnapshotLine.Id -> lastKey = "${line.kind}:${line.id}"
                                is SnapshotLine.End -> end = line.cursor
                                null -> Unit
                            }
                        }
                } catch (e: OwnAudioException.SnapshotTruncated) {
                    // What arrived is good: keep it, and ask for the rest.
                    flush()
                    if (++resumes > MAX_RESUMES) throw e
                    after = e.after ?: lastKey
                    cursor = e.cursor ?: cursor
                    continue
                } catch (e: OwnAudioException.Problem) {
                    // A resume point the server no longer takes (its cursor ran out): start over in
                    // this generation; rows already written are rewritten, older ones pruned at the end.
                    if (after == null || (e.code != "invalid_cursor" && e.code != "invalid_after" && e.code != "sync_cursor_expired")) throw e
                    flush()
                    // Rewritten records may have fewer credits than before: clear them first again.
                    writer.fresh = false
                    after = null
                    cursor = null
                    lastKey = null
                    continue
                }
                if (end == null) throw OwnAudioException.SnapshotTruncated(lastKey, cursor)
            }

            val endCursor = end!!
            val rest = buffer.toList()
            buffer.clear()
            val finished = state.copy(cursor = endCursor, syncedAtMs = clock.millis(), snapshotCursor = null, snapshotAfter = null)
            db.withTransaction {
                count += writer.apply(rest)
                prune(finished.generation)
                dao.upsertFeedState(finished)
            }
            // The indexes and the search table, built once over everything written.
            withContext(Dispatchers.IO) { tuning.finish() }
            restored = true
            return CatalogueSyncResult(fromSnapshot = true, changes = count, cursor = endCursor)
        } finally {
            _progress.value = null
            // A failed or cancelled snapshot leaves the catalogue browsable until it resumes.
            if (!restored) withContext(NonCancellable + Dispatchers.IO) { runCatching { tuning.finish() } }
        }
    }

    /** A snapshot line, and the record it carries decoded when the catalogue keeps its kind. */
    private data class Line(val line: SnapshotLine?, val item: FeedItem?, val failure: Throwable? = null)

    private fun prepare(line: SnapshotLine): Line =
        if (line is SnapshotLine.Record && CatalogueWriter.keeps(line.kind)) {
            Line(line, CatalogueWriter.decode(FeedItem(line.kind, line.id, line.record)))
        } else {
            Line(line, null)
        }

    private suspend fun prune(gen: Long) = with(dao) {
        pruneArtists(serverId, gen)
        pruneAlbums(serverId, gen)
        pruneAlbumCredits(serverId, gen)
        pruneTracks(serverId, gen)
        pruneTrackCredits(serverId, gen)
        pruneTrackGenres(serverId, gen)
        pruneGenres(serverId, gen)
        prunePlaylists(serverId, gen)
        pruneFavourites(serverId, gen)
        pruneRatings(serverId, gen)
        pruneOrphanEntries(serverId)
    }

    companion object {
        /** What the music app keeps (the iOS app's list, plus playlists). */
        val SNAPSHOT_KINDS = listOf("contributor", "album", "genre", "track", "playlist", "favourite", "rating")

        /** Records per transaction, and changes per page (the server's maximum). */
        const val BATCH = 500

        /** Records per transaction of a first snapshot, which no screen is waiting on row by row. */
        const val FIRST_BATCH = 5_000

        /** Other servers' songs above which a first snapshot keeps the indexes they are browsed by. */
        const val DEFER_LIMIT = 50_000

        /** A connection that keeps breaking is a failed sync, not an endless one. */
        const val MAX_RESUMES = 5
    }
}

/**
 * SQLite set for writing a whole library and put back after: syncs to disk at checkpoints only
 * (WAL's NORMAL: a crash may lose the last transactions, never corrupt; the snapshot resumes),
 * a bigger page cache, and with [deferSchema] the indexes left to the end.
 */
private class BulkTuning private constructor(
    private val sqlite: androidx.sqlite.db.SupportSQLiteDatabase,
    private val synchronous: Int,
    private val cacheSize: Int,
) {
    private var finished = false

    /** Indexes back (built once, when they were deferred), then the settings. */
    fun finish() {
        if (finished) return
        DeferredSchema.restore(sqlite)
        primary { execSQL("PRAGMA cache_size = $cacheSize") }
        if (synchronous > NORMAL) setSynchronous(sqlite, synchronous)
        finished = true
    }

    private fun primary(block: androidx.sqlite.db.SupportSQLiteDatabase.() -> Unit) = Companion.primary(sqlite, block)

    companion object {
        /** In WAL, NORMAL is 1. */
        private const val NORMAL = 1

        /** 32 MiB, as SQLite counts it in KiB when negative. */
        private const val CACHE_KIB = -32_768

        fun start(sqlite: androidx.sqlite.db.SupportSQLiteDatabase, deferSchema: Boolean): BulkTuning {
            var synchronous = NORMAL
            var cache = -2_000
            primary(sqlite) {
                synchronous = query("PRAGMA synchronous").use { if (it.moveToFirst()) it.getInt(0) else NORMAL }
                cache = query("PRAGMA cache_size").use { if (it.moveToFirst()) it.getInt(0) else -2_000 }
                execSQL("PRAGMA cache_size = $CACHE_KIB")
            }
            if (synchronous > NORMAL) setSynchronous(sqlite, NORMAL)
            if (deferSchema) DeferredSchema.defer(sqlite)
            return BulkTuning(sqlite, synchronous, cache)
        }

        /**
         * SQLite refuses this one inside a transaction; outside one, Android runs a statement
         * that writes nothing on the connection that writes. Many devices already use NORMAL.
         */
        private fun setSynchronous(sqlite: androidx.sqlite.db.SupportSQLiteDatabase, level: Int) {
            runCatching { sqlite.execSQL("PRAGMA synchronous = $level") }
        }

        /** Settings are per connection: a transaction holds the one that writes. */
        private fun primary(sqlite: androidx.sqlite.db.SupportSQLiteDatabase, block: androidx.sqlite.db.SupportSQLiteDatabase.() -> Unit) {
            sqlite.beginTransaction()
            try {
                sqlite.block()
                sqlite.setTransactionSuccessful()
            } finally {
                sqlite.endTransaction()
            }
        }
    }
}
