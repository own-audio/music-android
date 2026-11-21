package audio.own.player.music

import kotlin.math.abs
import kotlin.math.log10
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of music-ios `ReplayGainTests`. */
class ReplayGainTest {
    private val loudTrack = TrackGain(trackGainDb = -6.5, albumGainDb = -5.0, trackPeak = 0.9, albumPeak = 0.95)
    private val quietTrack = TrackGain(trackGainDb = 3.2, albumGainDb = -5.0, trackPeak = 0.8, albumPeak = 0.95)

    @Test
    fun off() {
        assertEquals(0.0, ReplayGain.gainDb(loudTrack, ReplayGainMode.OFF), 0.0)
    }

    @Test
    fun modes() {
        assertEquals(-6.5, ReplayGain.gainDb(loudTrack, ReplayGainMode.TRACK), 0.0)
        assertEquals(-5.0, ReplayGain.gainDb(loudTrack, ReplayGainMode.ALBUM), 0.0)
    }

    /** +3.2 dB on a peak of 0.8 would clip; 0.8 can rise by 1.94 dB to reach full scale. */
    @Test
    fun peakProtection() {
        val db = ReplayGain.gainDb(quietTrack, ReplayGainMode.TRACK)
        assertTrue(abs(db - (-20 * log10(0.8))) < 0.0001)
        assertTrue(db < 3.2)
    }

    @Test
    fun fallback() {
        val onlyAlbum = TrackGain(albumGainDb = -4.0)
        val onlyTrack = TrackGain(trackGainDb = -3.0)
        assertEquals(-4.0, ReplayGain.gainDb(onlyAlbum, ReplayGainMode.TRACK), 0.0)
        assertEquals(-3.0, ReplayGain.gainDb(onlyTrack, ReplayGainMode.ALBUM), 0.0)
        assertEquals(0.0, ReplayGain.gainDb(null, ReplayGainMode.TRACK), 0.0)
    }

    /** Not in the Swift suite: the ±24 dB clamp, with no peak to limit it first. */
    @Test
    fun clamp() {
        assertEquals(24.0, ReplayGain.gainDb(TrackGain(trackGainDb = 40.0), ReplayGainMode.TRACK), 0.0)
        assertEquals(-24.0, ReplayGain.gainDb(TrackGain(trackGainDb = -40.0), ReplayGainMode.TRACK), 0.0)
    }
}
