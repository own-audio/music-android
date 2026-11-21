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
import audio.own.music.library.db.FeedStateRow
import java.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps one own.audio server's catalogue in Room from its change feed (foundation §9.1): the
 * whole library once from `/sync/snapshot`, then `/sync/changes` from the cursor it ended at.
 *
 * - Written in transactions of [BATCH] records, so a 600k-track library never sits in memory.
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
        val writer = CatalogueWriter(dao, serverId, state.generation, changes = false)
        val buffer = ArrayList<FeedItem>(BATCH)
        var cursor = state.snapshotCursor
        var after = state.snapshotAfter
        var lastKey = after
        var count = 0
        var resumes = 0

        suspend fun flush() {
            val items = buffer.toList()
            buffer.clear()
            state = state.copy(snapshotCursor = cursor, snapshotAfter = lastKey)
            val progress = state
            db.withTransaction {
                count += writer.apply(items)
                dao.upsertFeedState(progress)
            }
        }

        var end: String? = null
        while (end == null) {
            try {
                client.snapshot(kinds = SNAPSHOT_KINDS, after = after, cursor = cursor).collect { line ->
                    when (line) {
                        is SnapshotLine.Begin -> cursor = line.cursor
                        is SnapshotLine.Record -> {
                            lastKey = "${line.kind}:${line.id}"
                            if (CatalogueWriter.keeps(line.kind)) buffer += FeedItem(line.kind, line.id, line.record)
                            if (buffer.size >= BATCH) flush()
                        }
                        is SnapshotLine.Id -> lastKey = "${line.kind}:${line.id}"
                        is SnapshotLine.End -> end = line.cursor
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
        return CatalogueSyncResult(fromSnapshot = true, changes = count, cursor = endCursor)
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

        /** A connection that keeps breaking is a failed sync, not an endless one. */
        const val MAX_RESUMES = 5
    }
}
