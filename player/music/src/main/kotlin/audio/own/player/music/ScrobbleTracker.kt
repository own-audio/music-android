package audio.own.player.music

/**
 * Decides when a song counts as played, from the player's start and stop events.
 *
 * The rule is the one Last.fm established and most scrobblers follow: "now playing" as soon as a
 * song starts, and a play once it has been *heard* for half its length or four minutes,
 * whichever comes first. Songs under 30 seconds never count. Heard means wall-clock time while
 * playing, so seeking to the end isn't listening, and a pause isn't either.
 *
 * Use it from one thread (the player's).
 */
class ScrobbleTracker(
    private val now: () -> Long = System::currentTimeMillis,
    private val send: (Event) -> Unit,
) {
    sealed interface Event {
        data class NowPlaying(val key: String) : Event

        /**
         * [startedAtMs] is when listening began, which the server records as the play's time;
         * [heardSecs] how much had been heard when it counted.
         */
        data class Played(val key: String, val startedAtMs: Long, val heardSecs: Double) : Event
    }

    private class Current(val key: String, val durationSecs: Int?, val startedAtMs: Long, var playingSinceMs: Long?) {
        var heardSecs = 0.0
        var counted = false
    }

    private var current: Current? = null

    /** Audio for [key] (the caller's track id) started or resumed. */
    fun started(key: String, durationSecs: Int?) {
        val playing = current
        if (playing != null && playing.key == key) {
            if (playing.playingSinceMs == null) playing.playingSinceMs = now()
            return
        }
        finish()
        current = Current(key, durationSecs, now(), now())
        send(Event.NowPlaying(key))
    }

    /**
     * Audio for [key] stopped. [isEnd] is true when the song is over (played to its end,
     * skipped, replaced) rather than paused or seeked; the next start is then a new listen even
     * for the same song, as with Repeat One.
     */
    fun stopped(key: String, isEnd: Boolean) {
        if (current?.key != key) return
        accumulate()
        countIfDue()
        if (isEnd) current = null
    }

    /** A periodic check while playing, so a play counts even if the app is killed before the end. */
    fun tick() {
        val playing = current ?: return
        if (playing.playingSinceMs == null) return
        accumulate()
        playing.playingSinceMs = now()
        countIfDue()
    }

    private fun finish() {
        if (current == null) return
        accumulate()
        countIfDue()
        current = null
    }

    private fun accumulate() {
        val playing = current ?: return
        val since = playing.playingSinceMs ?: return
        playing.heardSecs += maxOf(now() - since, 0L) / 1000.0
        playing.playingSinceMs = null
    }

    private fun countIfDue() {
        val playing = current ?: return
        if (playing.counted || playing.heardSecs < threshold(playing)) return
        playing.counted = true
        send(Event.Played(playing.key, playing.startedAtMs, playing.heardSecs))
    }

    private fun threshold(track: Current): Double {
        val duration = track.durationSecs ?: return MAXIMUM_THRESHOLD_SECS
        if (duration < MINIMUM_TRACK_SECS) return Double.POSITIVE_INFINITY
        return minOf(duration / 2.0, MAXIMUM_THRESHOLD_SECS)
    }

    companion object {
        const val MINIMUM_TRACK_SECS = 30
        const val MAXIMUM_THRESHOLD_SECS = 240.0
    }
}
