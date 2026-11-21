package audio.own.player.music

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import com.google.common.collect.ImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MusicUnitsTest {
    @Test
    fun `ReplayGain tags from ID3, Vorbis comments, iTunes atoms and Opus R128`() {
        val id3 = Metadata(
            TextInformationFrame("TXXX", "REPLAYGAIN_TRACK_GAIN", ImmutableList.of("-6.20 dB")),
            TextInformationFrame("TXXX", "replaygain_track_peak", ImmutableList.of("0.98")),
        )
        assertEquals(TrackGain(trackGainDb = -6.2, trackPeak = 0.98), ReplayGainTags.parse(id3))
        assertEquals(TrackGain(albumGainDb = 1.5), ReplayGainTags.parse(Metadata(VorbisComment("REPLAYGAIN_ALBUM_GAIN", "+1.50 dB"))))
        assertEquals(TrackGain(trackGainDb = -3.0), ReplayGainTags.parse(Metadata(InternalFrame("com.apple.iTunes", "replaygain_track_gain", "-3.00 dB"))))
        // Q7.8 relative to -23 LUFS: -512/256 + 5 = 3 dB at ReplayGain's -18.
        assertEquals(3.0, ReplayGainTags.parse(Metadata(VorbisComment("R128_TRACK_GAIN", "-512")))!!.trackGainDb!!, 1e-9)
        assertNull(ReplayGainTags.parse(Metadata(VorbisComment("ARTIST", "Someone"))))
        assertNull(ReplayGainTags.parse(Metadata(VorbisComment("REPLAYGAIN_TRACK_GAIN", "loud"))))
    }

    @Test
    fun `the format badge`() {
        val flac = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_FLAC).setSampleRate(44_100).setPcmEncoding(C.ENCODING_PCM_16BIT).build()
        assertEquals("FLAC · 44.1 kHz · 16-bit", FormatBadge.from(flac)!!.label())
        val mp3 = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_MPEG).setSampleRate(44_100).setAverageBitrate(320_000).build()
        assertEquals("MP3 320", FormatBadge.from(mp3)!!.label())
        assertEquals("MP3 320 · +2.5 dB", FormatBadge.from(mp3)!!.label(2.5))
        assertEquals("FLAC · 48 kHz · 24-bit · -1.0 dB", FormatBadge("FLAC", true, 48_000, 24).label(-1.0))
        assertNull(FormatBadge.from(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()))
    }

    @Test
    fun `ReplayGain splits into volume and a boost`() {
        val (volume, boost) = GainMath.split(-6.0)
        assertEquals(0.501f, volume, 0.001f)
        assertEquals(0.0, boost, 0.0)
        assertEquals(1f to 4.0, GainMath.split(4.0))
    }

    @Test
    fun `equalizer cutoffs sit between the band centres`() {
        val cutoffs = GainMath.cutoffsHz
        assertEquals(6, cutoffs.size)
        EqBand.standardFrequenciesHz.forEachIndexed { i, centre ->
            assertTrue(cutoffs[i] > centre)
            if (i > 0) assertTrue(cutoffs[i - 1] < centre)
        }
        assertEquals(20_000f, cutoffs.last())
        assertFalse(GainMath.needsEffects(EqualizerSettings(enabled = true), 0.0))
        assertFalse(GainMath.needsEffects(EqualizerSettings(enabled = false, preampDb = 3.0), 0.0))
        assertTrue(GainMath.needsEffects(EqualizerSettings(enabled = true, preampDb = -2.0), 0.0))
        assertEquals(1.0, GainMath.inputGainDb(EqualizerSettings(enabled = true, preampDb = -2.0), 3.0), 0.0)
    }
}
