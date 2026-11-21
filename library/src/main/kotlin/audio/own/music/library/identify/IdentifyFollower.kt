package audio.own.music.library.identify

import audio.own.kit.ownaudio.IdentifyProgress
import audio.own.kit.ownaudio.LiveEvent
import audio.own.kit.ownaudio.OwnAudioClient
import audio.own.kit.ownaudio.events
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

// Ported from music-ios App/OwnAudioMusic/Identify/IdentifyLive.swift. Times are milliseconds
// from a clock passed in, so tests run them on virtual time.

/** What the server's event stream says about identify runs. */
sealed interface IdentifySignal {
    /** The server accepted the stream: it will tell about progress from now on. */
    data object Open : IdentifySignal

    /** The connection ended; it is tried again after a pause. */
    data object Closed : IdentifySignal

    data class Progress(val progress: IdentifyProgress) : IdentifySignal
}

object IdentifyEvents {
    const val FIRST_RETRY_MS = 2_000L
    const val LONGEST_RETRY_MS = 60_000L

    fun signals(client: OwnAudioClient): Flow<IdentifySignal> = signals { onOpen -> client.events(onOpen = onOpen) }

    /**
     * The stream opening and closing, and its `identify` events. Reconnects after a pause that
     * doubles with every failed try, up to a minute, and starts over at 2 s once a connection
     * opened: an unreachable server is not asked every few seconds for as long as a screen is open.
     */
    fun signals(events: (onOpen: () -> Unit) -> Flow<LiveEvent>): Flow<IdentifySignal> = channelFlow {
        var wait = FIRST_RETRY_MS
        while (true) {
            val opened = AtomicBoolean(false)
            try {
                events {
                    opened.set(true)
                    trySend(IdentifySignal.Open)
                }.collect { event -> IdentifyProgress.of(event)?.let { send(IdentifySignal.Progress(it)) } }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Any end is a reason to try again later; the pause grows while nothing opens.
            }
            send(IdentifySignal.Closed)
            val (pause, next) = retry(wait, opened.get())
            wait = next
            delay(pause)
        }
    }

    /** How long to wait before the next try, and the wait after that one. */
    fun retry(afterMs: Long, opened: Boolean): Pair<Long, Long> {
        val pause = if (opened) FIRST_RETRY_MS else afterMs
        return pause to minOf(pause * 2, LONGEST_RETRY_MS)
    }
}

/**
 * Runs an action at most once per [intervalMs]. A call inside the interval is not lost: one run
 * follows when the interval is up, for every call made meanwhile. A burst of identify events (one
 * per song matched) would otherwise reload the whole list for each.
 */
class ReloadThrottle(private val intervalMs: Long, private val scope: CoroutineScope, private val now: () -> Long) {
    private val lock = Any()
    private var last = NEVER
    private var trailing: Job? = null

    suspend fun run(action: suspend () -> Unit) {
        val runNow = synchronized(lock) {
            if (trailing != null) return
            val wait = intervalMs - (now() - last)
            if (wait <= 0) {
                last = now()
                true
            } else {
                trailing = scope.launch {
                    delay(wait)
                    synchronized(lock) {
                        trailing = null
                        last = now()
                    }
                    action()
                }
                false
            }
        }
        if (runNow) action()
    }

    /** A reload made some other way counts as a run. */
    fun noteRan() = synchronized(lock) { last = now() }

    fun cancel() = synchronized(lock) {
        trailing?.cancel()
        trailing = null
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE / 4
    }
}

/**
 * Keeps an identify screen current: the event stream says when something moved, and a reload
 * follows, at most every 2 s. A slow poll stands in only while a run is going and the stream
 * can't help: closed, or open but silent for half a minute (a server that sends no identify
 * events). Polling and listening at once would load everything twice.
 */
class IdentifyFollower(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    @Volatile private var isOpen = false

    @Volatile private var lastHeard = NEVER

    /**
     * Until the calling coroutine is cancelled. [reload] should not throw; what it throws ends
     * the following.
     */
    suspend fun follow(
        signals: Flow<IdentifySignal>,
        matching: (IdentifyProgress) -> Boolean = { true },
        runActive: () -> Boolean,
        reload: suspend () -> Unit,
    ) {
        coroutineScope {
            val throttle = ReloadThrottle(RELOAD_EVERY_MS, this, now)
            launch {
                signals.collect { signal ->
                    when (signal) {
                        IdentifySignal.Open -> {
                            isOpen = true
                            lastHeard = now()
                        }
                        IdentifySignal.Closed -> isOpen = false
                        is IdentifySignal.Progress -> {
                            lastHeard = now()
                            if (matching(signal.progress)) throttle.run(reload)
                        }
                    }
                }
            }
            launch {
                while (true) {
                    delay(POLL_EVERY_MS)
                    if (!shouldPoll(runActive(), isOpen, now() - lastHeard)) continue
                    throttle.noteRan()
                    reload()
                }
            }
        }
    }

    companion object {
        const val RELOAD_EVERY_MS = 2_000L
        const val POLL_EVERY_MS = 10_000L
        const val SILENCE_BEFORE_POLLING_MS = 30_000L
        private const val NEVER = Long.MIN_VALUE / 4

        fun shouldPoll(runActive: Boolean, streamOpen: Boolean, silentForMs: Long): Boolean =
            runActive && (!streamOpen || silentForMs >= SILENCE_BEFORE_POLLING_MS)
    }
}
