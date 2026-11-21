package audio.own.player.music

import kotlin.math.log10
import kotlinx.serialization.Serializable

@Serializable
enum class ReplayGainMode {
    OFF,

    /** Every song at the same loudness. */
    TRACK,

    /** Albums keep their own quiet and loud songs; albums match each other. */
    ALBUM;

    companion object {
        val DEFAULT = TRACK
    }
}

object ReplayGain {
    const val MAX_DB = 24.0

    /**
     * The gain to apply, in dB. Falls back to the other value when the preferred one is missing,
     * and never raises a song so far that its peak would clip.
     */
    fun gainDb(gain: TrackGain?, mode: ReplayGainMode): Double {
        if (gain == null || mode == ReplayGainMode.OFF) return 0.0
        val (db, peak) = if (mode == ReplayGainMode.ALBUM) {
            (gain.albumGainDb ?: gain.trackGainDb) to (if (gain.albumGainDb != null) gain.albumPeak else gain.trackPeak)
        } else {
            (gain.trackGainDb ?: gain.albumGainDb) to (if (gain.trackGainDb != null) gain.trackPeak else gain.albumPeak)
        }
        var value = db ?: return 0.0
        if (peak != null && peak > 0) value = minOf(value, -20 * log10(peak))
        return value.coerceIn(-MAX_DB, MAX_DB)
    }
}
