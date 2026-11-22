package audio.own.musicplayer.player

import audio.own.music.library.Capability
import audio.own.music.library.MusicSource
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What the servers hear about playback, by the rules of music-ios `AppModel.wireScrobbling`:
 * "now playing" when a song starts or resumes, progress every 15 s while it plays (the player's
 * own tick), "stopped" when it pauses or ends but not on a seek, and a counted play once it was
 * heard long enough. A server that counts plays from those reports (Jellyfin) is sent a play
 * only when it never heard the start (the song played offline); every other server gets each
 * play, through the outbox.
 *
 * Calls come from the player's thread; the requests go out on [scope].
 */
class PlayReporter(
    private val sourceOf: (String) -> MusicSource?,
    private val enabled: () -> Boolean,
    private val scope: CoroutineScope,
    /** A counted play, stored first and sent when the server can be reached. */
    private val recordPlay: (suspend (trackId: String, startedAtMs: Long, heardMs: Long) -> Unit)?,
    private val onError: (String) -> Unit = {},
) {
    /** Tracks whose start the server heard: it counts those plays itself. */
    private val reportedLive: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private var current: String? = null
    private var lastPositionMs = 0L

    /** Whether the song playing was reported stopped (paused); playing again is a new start. */
    private var stopped = false

    /** A song started from the beginning: a new listen, even of the same song (Repeat One). */
    fun started(trackId: String, positionMs: Long = 0) {
        val previous = current
        if (previous != null && !stopped) {
            val at = lastPositionMs
            send(previous) { it.reportStopped(previous, at) }
        }
        reportedLive.remove(trackId)
        current = trackId
        stopped = false
        lastPositionMs = positionMs
        if (!enabled()) return
        scope.launch {
            val source = sourceOf(trackId) ?: return@launch
            try {
                source.reportNowPlaying(trackId, positionMs)
                if (Capability.COUNTS_PLAYS_FROM_REPORTS in source.capabilities) reportedLive += trackId
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Offline or unreachable: the play is then sent afterwards, as one it never heard.
                onError("couldn't report now playing: ${e.message}")
            }
        }
    }

    /** Playing again after a pause: the server took the pause for the end, so this starts anew. */
    fun resumed(trackId: String, positionMs: Long) {
        if (current != trackId || !stopped) return
        stopped = false
        lastPositionMs = positionMs
        send(trackId) { it.reportNowPlaying(trackId, positionMs) }
    }

    /** The player's 15-second tick while playing, and its note when playback pauses. */
    fun progress(trackId: String, positionMs: Long, isPlaying: Boolean) {
        if (trackId != current) return
        lastPositionMs = positionMs
        if (isPlaying) {
            send(trackId) { it.reportProgress(trackId, positionMs, isPaused = false) }
        } else if (!stopped) {
            stopped = true
            send(trackId) { it.reportStopped(trackId, positionMs) }
        }
    }

    /** Nothing plays any more (the queue ended, or was stopped). */
    fun ended() {
        val previous = current ?: return
        current = null
        if (!stopped) {
            val at = lastPositionMs
            send(previous) { it.reportStopped(previous, at) }
        }
        stopped = false
    }

    /** The song counted as played (heard half its length or four minutes). */
    fun played(trackId: String, startedAtMs: Long, heardMs: Long) {
        if (!enabled()) return
        if (trackId in reportedLive) return
        scope.launch {
            try {
                val outbox = recordPlay
                if (outbox != null) {
                    outbox(trackId, startedAtMs, heardMs)
                } else {
                    sourceOf(trackId)?.reportPlayed(trackId, Instant.ofEpochMilli(startedAtMs))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError("couldn't report a play: ${e.message}")
            }
        }
    }

    private fun send(trackId: String, report: suspend (MusicSource) -> Unit) {
        if (!enabled()) return
        val source = sourceOf(trackId) ?: return
        scope.launch {
            try {
                report(source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError("couldn't report playback: ${e.message}")
            }
        }
    }
}
