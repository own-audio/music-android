package audio.own.player.music

/**
 * When a crossfade may start, free of any player, so the rules can be tested one by one. Ported
 * from music-ios `PlaybackEngine.beginCrossfade` and its tick loop.
 */
object CrossfadeRules {
    const val MIN_SECS = 1.0
    const val MAX_SECS = 12.0
    const val DEFAULT_SECS = 4.0

    /** The volume ramp: this many steps over the fade, as on iOS. */
    const val STEPS = 20

    data class Situation(
        /** The fade length the listener chose; null when crossfade is off. */
        val fadeSecs: Double?,
        val isPlaying: Boolean,
        val isFading: Boolean,
        /** The sleep timer is set to End of Track: that song plays to its end, unfaded. */
        val sleepsAtEndOfTrack: Boolean,
        /** There is a song after this one (repeat included). */
        val hasNext: Boolean,
        /**
         * The next song is fully on the phone (download or complete listening cache). "End of the
         * data so far" of a stream still arriving is not the end of the song, and a fade into a
         * song that then stalls is worse than none.
         */
        val nextFullyAvailable: Boolean,
        /** Duration of the current song from its own header, or null while unknown. */
        val durationMs: Long?,
        val positionMs: Long,
    )

    sealed interface Decision {
        data object No : Decision

        /** Start the fade in [delayMs] (0: now). */
        data class StartIn(val delayMs: Long) : Decision
    }

    /**
     * [tickMs] is how often the player asks: a fade due before the next ask is scheduled for its
     * moment instead of starting up to a tick late with too little audio left.
     */
    fun decide(s: Situation, tickMs: Long): Decision {
        val fade = s.fadeSecs ?: return Decision.No
        if (!s.isPlaying || s.isFading || s.sleepsAtEndOfTrack || !s.hasNext || !s.nextFullyAvailable) return Decision.No
        val duration = s.durationMs ?: return Decision.No
        val fadeMs = (fade.coerceIn(MIN_SECS, MAX_SECS) * 1000).toLong()
        // A song shorter than two fades would be faded in and out at once.
        if (duration < fadeMs * 2) return Decision.No
        val lead = duration - s.positionMs - fadeMs
        return when {
            lead <= 0 -> Decision.StartIn(0)
            lead < tickMs -> Decision.StartIn(lead)
            else -> Decision.No
        }
    }

    /** Outgoing and incoming volume factors at [step] of [STEPS]. */
    fun ramp(step: Int): Pair<Float, Float> {
        val f = (step.toFloat() / STEPS).coerceIn(0f, 1f)
        return (1 - f) to f
    }
}
